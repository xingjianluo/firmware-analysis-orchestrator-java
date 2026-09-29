package com.github.xingjianluo.firmwareanalysisorchestratorjava.orchestration;

import com.github.xingjianluo.firmwareanalysisorchestratorjava.model.AnalysisConclusion;
import com.github.xingjianluo.firmwareanalysisorchestratorjava.model.FailureCode;
import com.github.xingjianluo.firmwareanalysisorchestratorjava.model.ParseOutcome;
import com.github.xingjianluo.firmwareanalysisorchestratorjava.model.SampleDescriptor;
import com.github.xingjianluo.firmwareanalysisorchestratorjava.model.TaskRecord;
import com.github.xingjianluo.firmwareanalysisorchestratorjava.model.TaskSnapshot;
import com.github.xingjianluo.firmwareanalysisorchestratorjava.model.TaskStatus;
import com.github.xingjianluo.firmwareanalysisorchestratorjava.rule.RuleEvaluator;
import com.github.xingjianluo.firmwareanalysisorchestratorjava.rule.RuleRegistry;
import com.github.xingjianluo.firmwareanalysisorchestratorjava.rule.RuleSet;
import com.github.xingjianluo.firmwareanalysisorchestratorjava.sample.SampleParsingService;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 任务编排门面：提交（去重）、排队、异步执行、取消、查询、复现。
 * 并发提交通过 {@link TaskStore} 的 putIfAbsent 与 {@link TaskRecord} 的内置锁
 * 保证彼此独立、同一任务不重复执行、状态不回退。
 */
@Service
public class TaskOrchestrator {

    private static final Logger log = LoggerFactory.getLogger(TaskOrchestrator.class);

    private final TaskStore store;
    private final SampleParsingService parsingService;
    private final RuleRegistry ruleRegistry;
    private final RuleEvaluator ruleEvaluator;
    private final ExecutorService executor;

    @Autowired
    public TaskOrchestrator(TaskStore store,
                            SampleParsingService parsingService,
                            RuleRegistry ruleRegistry,
                            RuleEvaluator ruleEvaluator) {
        this(store, parsingService, ruleRegistry, ruleEvaluator, defaultExecutor());
    }

    /** 允许注入执行器（主要用于测试中的确定性排队/取消控制）。 */
    public TaskOrchestrator(TaskStore store,
                     SampleParsingService parsingService,
                     RuleRegistry ruleRegistry,
                     RuleEvaluator ruleEvaluator,
                     ExecutorService executor) {
        this.store = store;
        this.parsingService = parsingService;
        this.ruleRegistry = ruleRegistry;
        this.ruleEvaluator = ruleEvaluator;
        this.executor = executor;
    }

    private static ExecutorService defaultExecutor() {
        AtomicInteger seq = new AtomicInteger();
        ThreadFactory factory = r -> {
            Thread t = new Thread(r, "analysis-worker-" + seq.incrementAndGet());
            t.setDaemon(true);
            return t;
        };
        return Executors.newFixedThreadPool(8, factory);
    }

    /**
     * 提交任务。taskId 重复时返回既有任务快照（duplicate=true, accepted=false），
     * 不会重复执行；非法输入抛出 {@link IllegalArgumentException}。
     */
    public TaskSnapshot submit(String taskId, SampleDescriptor sample) {
        validate(taskId, sample);
        TaskRecord record = new TaskRecord(taskId, sample);
        if (!store.register(record)) {
            TaskSnapshot snapshot = store.find(taskId)
                    .map(r -> TaskSnapshot.existing(r, true))
                    .orElseThrow();
            log.info("任务 {} 重复提交，复用既有任务，状态={}，不重复执行", taskId, snapshot.status());
            return snapshot;
        }
        // register 成功后立刻置为 QUEUED；并发下只有一个线程能走到这里
        record.transitionTo(TaskStatus.QUEUED);
        executor.execute(new AnalysisWorker(record, parsingService, ruleRegistry, ruleEvaluator));
        log.info("任务 {} 已接受并入队，当前规则版本={}", taskId, ruleRegistry.currentVersionNumber());
        return TaskSnapshot.existing(record, false);
    }

    /** 取消任务；不存在返回 empty；已终态则取消被拒绝（快照仍返回）。 */
    public Optional<TaskSnapshot> cancel(String taskId) {
        return store.find(taskId).map(record -> {
            boolean cancelled = record.cancel();
            log.info("任务 {} 取消请求处理结果：{}，当前状态={}",
                    taskId, cancelled ? "已取消" : "任务已终结，取消被拒绝", record.getStatus());
            return TaskSnapshot.existing(record, false);
        });
    }

    /** 查询任务快照；不存在返回 empty。 */
    public Optional<TaskSnapshot> get(String taskId) {
        return store.find(taskId).map(r -> TaskSnapshot.existing(r, false));
    }

    /**
     * 用任务固化的规则版本重新计算结论（不改写任务状态），
     * 验证规则更新后历史结论仍可按旧版本复现。
     * 仅对成功跑完规则的任务（存在固化结论与版本）有意义。
     */
    public Optional<ReproductionResult> reproduce(String taskId) {
        Optional<TaskRecord> found = store.find(taskId);
        if (found.isEmpty()) {
            return Optional.empty();
        }
        TaskRecord record = found.get();
        AnalysisConclusion stored = record.getConclusion();
        ParseOutcome parseOutcome = record.getParseOutcome();
        if (stored == null || parseOutcome == null || record.getRuleVersion() == null) {
            log.info("任务 {} 尚无固化结论，无法复现", taskId);
            return Optional.empty();
        }
        RuleSet pinned = ruleRegistry.get(record.getRuleVersion())
                .orElseThrow(() -> new IllegalStateException(
                        "固化的规则版本已丢失：" + record.getRuleVersion()));
        AnalysisConclusion reproduced = ruleEvaluator.evaluate(parseOutcome, pinned);
        boolean consistent = reproduced.verdict() == stored.verdict()
                && reproduced.results().equals(stored.results());
        log.info("任务 {} 历史结论复现：stored={} reproduced={} ruleVersion={} consistent={}",
                taskId, stored.verdict(), reproduced.verdict(), pinned.version(), consistent);
        return Optional.of(new ReproductionResult(taskId, pinned.version(),
                stored.verdict(), reproduced.verdict(), consistent));
    }

    private static void validate(String taskId, SampleDescriptor sample) {
        if (taskId == null || taskId.isBlank()) {
            throw new IllegalArgumentException(FailureCode.INVALID_REQUEST.name()
                    + ": taskId 不能为空");
        }
        if (sample == null) {
            throw new IllegalArgumentException(FailureCode.INVALID_REQUEST.name()
                    + ": sample 不能为空");
        }
        if (sample.content().length == 0) {
            // 空内容允许入队，由解析阶段以 EMPTY_SAMPLE 失败告终，保证失败原因可区分
            log.debug("任务 {} 提交了空样本，将在解析阶段失败", taskId);
        }
    }

    @PreDestroy
    void shutdown() {
        executor.shutdownNow();
    }
}
