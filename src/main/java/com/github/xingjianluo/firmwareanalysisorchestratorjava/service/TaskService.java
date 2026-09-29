package com.github.xingjianluo.firmwareanalysisorchestratorjava.service;

import com.github.xingjianluo.firmwareanalysisorchestratorjava.model.AnalysisTask;
import com.github.xingjianluo.firmwareanalysisorchestratorjava.model.FailureReason;
import com.github.xingjianluo.firmwareanalysisorchestratorjava.model.ParsedFirmware;
import com.github.xingjianluo.firmwareanalysisorchestratorjava.model.RuleFinding;
import com.github.xingjianluo.firmwareanalysisorchestratorjava.model.SampleSubmission;
import com.github.xingjianluo.firmwareanalysisorchestratorjava.model.TaskConclusion;
import com.github.xingjianluo.firmwareanalysisorchestratorjava.model.TaskStatus;
import com.github.xingjianluo.firmwareanalysisorchestratorjava.rule.AnalysisRule;
import com.github.xingjianluo.firmwareanalysisorchestratorjava.rule.InMemoryRuleRegistry;
import com.github.xingjianluo.firmwareanalysisorchestratorjava.rule.RuleAggregator;
import com.github.xingjianluo.firmwareanalysisorchestratorjava.rule.RuleContext;
import com.github.xingjianluo.firmwareanalysisorchestratorjava.sample.MalformedSampleException;
import com.github.xingjianluo.firmwareanalysisorchestratorjava.sample.SampleParser;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * 任务编排服务。
 *
 * <p>关键不变量：
 * <ul>
 *   <li>提交按 idempotencyKey（缺省用内容 SHA-256）原子去重，重复提交只增加计数，绝不重复执行；</li>
 *   <li>任务状态只能单向流转，{@code QUEUED-&gt;RUNNING} 的抢占由状态机保证只有一个执行者；</li>
 *   <li>已取消任务的结果写回会被状态机拒绝，最终状态/结论始终一致可查；</li>
 *   <li>任务提交时冻结规则集版本，复跑使用同一快照，规则更新不改写历史结论。</li>
 * </ul>
 */
@Service
public class TaskService {

    private static final Logger log = LoggerFactory.getLogger(TaskService.class);

    private final TaskRepository repository;
    private final SampleParser parser;
    private final InMemoryRuleRegistry ruleRegistry;
    private final RuleAggregator aggregator;
    private final ExecutorService executor;
    private final ConcurrentMap<String, Future<?>> futures = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, String> dedupKeys = new ConcurrentHashMap<>();

    public TaskService(TaskRepository repository,
                       SampleParser parser,
                       InMemoryRuleRegistry ruleRegistry,
                       RuleAggregator aggregator,
                       @Value("${analysis.worker.count:2}") int workerCount) {
        this.repository = repository;
        this.parser = parser;
        this.ruleRegistry = ruleRegistry;
        this.aggregator = aggregator;
        this.executor = new ThreadPoolExecutor(
                workerCount, workerCount, 0L, TimeUnit.MILLISECONDS,
                new LinkedBlockingQueue<>(),
                r -> {
                    Thread t = new Thread(r, "analysis-worker");
                    t.setDaemon(true);
                    return t;
                });
    }

    /**
     * 提交分析任务。相同幂等键/相同内容并发提交只有第一次真正建任务并执行，
     * 后续调用拿到同一条任务（submitCount 累加）。
     */
    public AnalysisTask submit(SampleSubmission submission, String idempotencyKey) {
        String contentHash = sha256(submission.content());
        String dedupKey = (idempotencyKey == null || idempotencyKey.isBlank())
                ? "HASH:" + contentHash
                : "IDEM:" + idempotencyKey;

        // computeIfAbsent 对同一个 key 在 Map 分段上加锁，保证并发下映射函数只执行一次
        boolean[] created = {false};
        String taskId = dedupKeys.computeIfAbsent(dedupKey, k -> {
            AnalysisTask fresh = newTask(submission, idempotencyKey, contentHash);
            repository.save(fresh);
            created[0] = true;
            futures.put(fresh.getTaskId(),
                    executor.submit(() -> runTask(fresh.getTaskId())));
            log.info("任务已受理 taskId={} file={} ruleSet=v{} 判定依据=新任务入队",
                    fresh.getTaskId(), fresh.getFileName(), fresh.getRuleSetVersion());
            return fresh.getTaskId();
        });

        AnalysisTask result = repository.findById(taskId)
                .orElseThrow(() -> new IllegalStateException("任务写入后不可读"));
        if (!created[0]) {
            // 命中已有任务：只累加提交计数，不触发重复执行
            result.incrementSubmitCount();
            log.info("重复提交命中既有任务 taskId={} status={} submitCount={} 判定依据=幂等键去重",
                    result.getTaskId(), result.getStatus(), result.getSubmitCount());
        }
        return result;
    }

    private AnalysisTask newTask(SampleSubmission submission, String idempotencyKey, String hash) {
        AnalysisTask task = new AnalysisTask();
        task.setTaskId(UUID.randomUUID().toString());
        task.setIdempotencyKey(idempotencyKey);
        task.setContentHash(hash);
        task.setFileName(submission.fileName());
        task.setContent(submission.content());
        task.setStatus(TaskStatus.QUEUED);
        task.setRuleSetVersion(ruleRegistry.ruleSetVersion());
        task.setCreatedAt(Instant.now());
        task.setUpdatedAt(Instant.now());
        task.setSubmitCount(1);
        return task;
    }

    private void runTask(String taskId) {
        AnalysisTask task = repository.findById(taskId).orElse(null);
        if (task == null) {
            return;
        }
        // 抢占执行权：只有 QUEUED 能进 RUNNING。已被取消的任务在这里直接退出，不执行。
        if (!task.markRunning()) {
            log.info("任务跳过执行 taskId={} status={} 判定依据=状态非QUEUED（可能已取消）",
                    taskId, task.getStatus());
            return;
        }
        log.info("任务开始执行 taskId={}", taskId);

        ParsedFirmware firmware;
        try {
            firmware = parser.parse(new SampleSubmission(task.getFileName(), task.getContent()));
        } catch (MalformedSampleException e) {
            handleParseFailure(task, e);
            return;
        } catch (RuntimeException e) {
            log.error("任务解析兜底失败 taskId={} errorSource={}", taskId, e.getClass().getSimpleName());
            task.failWith(FailureReason.INTERNAL_ERROR, FailureReason.INTERNAL_ERROR.getUserMessage());
            return;
        }

        if (isCancelled(task)) {
            task.transitionTo(TaskStatus.CANCELLED);
            log.info("任务解析后检测到取消 taskId={} status={} 判定依据=cancelRequested/中断",
                    taskId, task.getStatus());
            return;
        }

        List<AnalysisRule> rules = ruleRegistry.snapshotAt(task.getRuleSetVersion());
        RuleContext context = new RuleContext(
                new SampleSubmission(task.getFileName(), task.getContent()), firmware);

        List<RuleFinding> findings = new ArrayList<>();
        for (AnalysisRule rule : rules) {
            // 每条规则前后各放一个取消检查点，取消后不再执行后续规则、不写回结论
            if (isCancelled(task)) {
                task.transitionTo(TaskStatus.CANCELLED);
                log.info("任务规则执行前被取消 taskId={} 判定依据=cancelRequested/线程中断", taskId);
                return;
            }
            RuleFinding finding = rule.evaluate(context);
            findings.add(finding);
            log.info("规则判定 taskId={} ruleId={} {} subject={} verdict={} 判定依据={} errorSource={}",
                    taskId, finding.ruleId(), finding.ruleVersion(), finding.subject(),
                    finding.verdict(), finding.basis(),
                    finding.errorSource() == null ? "-" : finding.errorSource());
            if (isCancelled(task)) {
                task.transitionTo(TaskStatus.CANCELLED);
                log.info("任务规则执行后被取消 taskId={} 已执行规则数={} 判定依据=cancelRequested/线程中断",
                        taskId, findings.size());
                return;
            }
        }

        TaskConclusion conclusion = aggregator.aggregate(findings);
        boolean written;
        if (conclusion == TaskConclusion.PARTIAL) {
            written = task.partialFailWith(findings);
        } else {
            written = task.completeWith(conclusion, findings);
        }
        if (!written) {
            // 任务在规则执行期间被取消，终态写回被状态机拒绝，结果丢弃
            log.info("结果写回被拒绝 taskId={} status={} 判定依据=终态不可回退（任务已取消）",
                    taskId, task.getStatus());
            return;
        }
        log.info("任务执行完成 taskId={} status={} conclusion={} 判定依据=规则汇总",
                taskId, task.getStatus(), conclusion);
    }

    private static boolean isCancelled(AnalysisTask task) {
        return task.isCancelRequested()
                || Thread.currentThread().isInterrupted()
                || task.getStatus() != TaskStatus.RUNNING;
    }

    private void handleParseFailure(AnalysisTask task, MalformedSampleException e) {
        boolean written = task.failWith(e.getReason(), e.getReason().getUserMessage());
        if (!written) {
            log.info("解析失败写回被拒绝 taskId={} status={} 判定依据=终态不可回退",
                    task.getTaskId(), task.getStatus());
            return;
        }
        log.warn("任务解析失败 taskId={} 错误来源=样本解析 reason={} 判定依据={}",
                task.getTaskId(), e.getReason().getCode(), e.getMessage());
    }

    public AnalysisTask get(String taskId) {
        return repository.findById(taskId).orElseThrow(() -> new TaskNotFoundException(taskId));
    }

    /**
     * 取消任务。排队中的任务直接取消且不会执行；运行中的任务收到中断，
     * 由工作线程在检查点把状态切到 CANCELLED。终态任务取消无效，返回当前状态。
     */
    public AnalysisTask cancel(String taskId) {
        AnalysisTask task = get(taskId);
        Future<?> future = futures.get(taskId);
        synchronized (task) {
            if (task.getStatus() == TaskStatus.QUEUED) {
                task.setCancelRequested(true);
                if (task.transitionTo(TaskStatus.CANCELLED) && future != null) {
                    future.cancel(false);
                }
                log.info("任务在排队阶段被取消 taskId={}", taskId);
            } else if (task.getStatus() == TaskStatus.RUNNING) {
                task.setCancelRequested(true);
                if (future != null) {
                    future.cancel(true);
                }
                log.info("任务在运行阶段收到取消信号 taskId={}", taskId);
            } else {
                log.info("取消请求忽略 taskId={} status={} 判定依据=任务已处于终态",
                        taskId, task.getStatus());
            }
        }
        return task;
    }

    public List<AnalysisTask> list() {
        return repository.findAll();
    }

    /**
     * 按提交时冻结的规则快照复跑历史任务。
     * 规则集即便已更新，也使用 task.ruleSetVersion 对应的旧快照，
     * 因此复跑结论必须与原始结论一致（reproducible=true）。
     */
    public ReplayReport replay(String taskId) {
        AnalysisTask task = get(taskId);
        if (task.getFindings().isEmpty()) {
            throw new IllegalStateException(
                    "任务没有可复现的规则结论（status=" + task.getStatus() + "）");
        }
        ParsedFirmware firmware =
                parser.parse(new SampleSubmission(task.getFileName(), task.getContent()));
        RuleContext context = new RuleContext(
                new SampleSubmission(task.getFileName(), task.getContent()), firmware);

        List<RuleFinding> replayFindings = new ArrayList<>();
        for (AnalysisRule rule : ruleRegistry.snapshotAt(task.getRuleSetVersion())) {
            replayFindings.add(rule.evaluate(context));
        }
        TaskConclusion replayConclusion = aggregator.aggregate(replayFindings);
        boolean reproducible = replayConclusion == task.getConclusion()
                && sameFindings(replayFindings, task.getFindings());
        log.info("历史任务复跑 taskId={} ruleSet=v{} original={} replay={} reproducible={}",
                taskId, task.getRuleSetVersion(), task.getConclusion(),
                replayConclusion, reproducible);
        return new ReplayReport(taskId, task.getRuleSetVersion(),
                task.getConclusion(), replayConclusion, reproducible, replayFindings);
    }

    private static boolean sameFindings(List<RuleFinding> a, List<RuleFinding> b) {
        if (a.size() != b.size()) {
            return false;
        }
        for (int i = 0; i < a.size(); i++) {
            RuleFinding x = a.get(i);
            RuleFinding y = b.get(i);
            if (!x.ruleId().equals(y.ruleId())
                    || x.verdict() != y.verdict()
                    || !safeEquals(x.basis(), y.basis())
                    || !safeEquals(x.errorSource(), y.errorSource())) {
                return false;
            }
        }
        return true;
    }

    private static boolean safeEquals(String a, String b) {
        return a == null ? b == null : a.equals(b);
    }

    private static String sha256(byte[] data) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(data == null ? new byte[0] : data));
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }

    @PreDestroy
    void shutdown() {
        executor.shutdownNow();
    }
}
