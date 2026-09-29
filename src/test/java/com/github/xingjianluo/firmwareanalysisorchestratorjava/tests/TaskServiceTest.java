package com.github.xingjianluo.firmwareanalysisorchestratorjava.tests;

import com.github.xingjianluo.firmwareanalysisorchestratorjava.model.AnalysisTask;
import com.github.xingjianluo.firmwareanalysisorchestratorjava.model.RuleFinding;
import com.github.xingjianluo.firmwareanalysisorchestratorjava.model.RuleVerdict;
import com.github.xingjianluo.firmwareanalysisorchestratorjava.model.SampleSubmission;
import com.github.xingjianluo.firmwareanalysisorchestratorjava.model.TaskConclusion;
import com.github.xingjianluo.firmwareanalysisorchestratorjava.model.TaskStatus;
import com.github.xingjianluo.firmwareanalysisorchestratorjava.rule.AbstractAnalysisRule;
import com.github.xingjianluo.firmwareanalysisorchestratorjava.rule.AnalysisRule;
import com.github.xingjianluo.firmwareanalysisorchestratorjava.rule.BuiltInRules;
import com.github.xingjianluo.firmwareanalysisorchestratorjava.rule.FindingAggregator;
import com.github.xingjianluo.firmwareanalysisorchestratorjava.rule.InMemoryRuleRegistry;
import com.github.xingjianluo.firmwareanalysisorchestratorjava.rule.RuleContext;
import com.github.xingjianluo.firmwareanalysisorchestratorjava.sample.DefaultSampleParser;
import com.github.xingjianluo.firmwareanalysisorchestratorjava.sample.MagicFormatDetector;
import com.github.xingjianluo.firmwareanalysisorchestratorjava.service.InMemoryTaskRepository;
import com.github.xingjianluo.firmwareanalysisorchestratorjava.service.ReplayReport;
import com.github.xingjianluo.firmwareanalysisorchestratorjava.service.TaskService;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 任务编排：并发提交/取消、重复提交去重、状态不回退、规则版本复现。 */
class TaskServiceTest {

    private static final Logger log = LoggerFactory.getLogger(TaskServiceTest.class);

    private TaskService newService(int workers) {
        return new TaskService(
                new InMemoryTaskRepository(),
                new DefaultSampleParser(new MagicFormatDetector()),
                new InMemoryRuleRegistry(),
                new FindingAggregator(),
                workers);
    }

    private static AnalysisTask awaitTerminal(TaskService svc, String id, Duration timeout) {
        long deadline = System.nanoTime() + timeout.toNanos();
        AnalysisTask task;
        do {
            task = svc.get(id);
            if (task.isTerminal()) {
                return task;
            }
            try {
                Thread.sleep(5);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException(e);
            }
        } while (System.nanoTime() < deadline);
        return task;
    }

    @Test
    void concurrentSubmissionsOfSameSampleRunExactlyOnce() throws Exception {
        TaskService svc = newService(4);
        int threads = 16;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        Set<String> ids = ConcurrentHashMap.newKeySet();
        List<Integer> submitCounts = new ArrayList<>();

        for (int i = 0; i < threads; i++) {
            pool.submit(() -> {
                try {
                    start.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new RuntimeException(e);
                }
                AnalysisTask t = svc.submit(
                        new SampleSubmission("dev.fwimg", Samples.cleanFirmware()), null);
                ids.add(t.getTaskId());
                synchronized (submitCounts) {
                    submitCounts.add(t.getSubmitCount());
                }
            });
        }
        start.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));

        assertEquals(1, ids.size(), "相同内容并发提交必须收敛到同一条任务");
        String id = ids.iterator().next();
        AnalysisTask done = awaitTerminal(svc, id, Duration.ofSeconds(10));
        log.info("场景=并发相同样本 taskId={} status={} conclusion={}",
                id, done.getStatus(), done.getConclusion());
        assertEquals(TaskStatus.COMPLETED, done.getStatus(), "任务只能执行一次且正常完成");
        assertEquals(TaskConclusion.PASS, done.getConclusion());
        assertEquals(threads, svc.get(id).getSubmitCount(), "提交计数应等于并发次数");
    }

    @Test
    void distinctSamplesRunIndependently() throws Exception {
        TaskService svc = newService(2);
        List<String> ids = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            byte[] data = Samples.fwimg(List.of(
                    Samples.seg((byte) 1, "model=X" + i),
                    Samples.seg((byte) 2, "signed=1"),
                    Samples.seg((byte) 3, "9.9." + i)));
            ids.add(svc.submit(new SampleSubmission("d" + i + ".fwimg", data), null).getTaskId());
        }
        for (String id : ids) {
            AnalysisTask t = awaitTerminal(svc, id, Duration.ofSeconds(10));
            assertEquals(TaskStatus.COMPLETED, t.getStatus());
            assertEquals(TaskConclusion.PASS, t.getConclusion());
        }
        assertEquals(6, svc.list().size(), "6 条不同样本彼此独立，互不去重");
        log.info("场景=独立并发任务 任务数={} 全部COMPLETED/PASS", ids.size());
    }

    @Test
    void queuedTaskCanBeCancelledAndNeverRuns() {
        // 单工作线程：先占住一个慢任务，后续任务保持 QUEUED，取消后不能执行
        TaskService svc = newService(1);
        InMemoryRuleRegistry registry = new InMemoryRuleRegistry(
                List.of(slowRule("R-SLOW", "SLOW", 400)));
        TaskService slowSvc = new TaskService(new InMemoryTaskRepository(),
                new DefaultSampleParser(new MagicFormatDetector()),
                registry, new FindingAggregator(), 1);

        AnalysisTask first = slowSvc.submit(
                new SampleSubmission("a.fwimg", Samples.cleanFirmware()), "first");
        awaitStatus(slowSvc, first.getTaskId(), TaskStatus.RUNNING, Duration.ofSeconds(5));

        AnalysisTask queued = slowSvc.submit(
                new SampleSubmission("b.fwimg", Samples.cleanFirmware()), "second");
        assertEquals(TaskStatus.QUEUED, slowSvc.get(queued.getTaskId()).getStatus());

        AnalysisTask cancelled = slowSvc.cancel(queued.getTaskId());
        assertEquals(TaskStatus.CANCELLED, cancelled.getStatus());

        // 等待足够时间，确认排队任务始终没有执行、没有结论被写回
        try {
            Thread.sleep(700);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        AnalysisTask after = slowSvc.get(queued.getTaskId());
        log.info("场景=排队取消 taskId={} status={} conclusion={} findings={}",
                after.getTaskId(), after.getStatus(), after.getConclusion(), after.getFindings().size());
        assertEquals(TaskStatus.CANCELLED, after.getStatus());
        assertEquals(TaskConclusion.NONE, after.getConclusion());
        assertTrue(after.getFindings().isEmpty(), "已取消任务不得写回任何规则结果");
    }

    @Test
    void runningTaskCancellationKeepsConsistentFinalState() {
        TaskService svc = new TaskService(new InMemoryTaskRepository(),
                new DefaultSampleParser(new MagicFormatDetector()),
                new InMemoryRuleRegistry(List.of(slowRule("R1", "SLOW", 300),
                        slowRule("R2", "SLOW", 300))),
                new FindingAggregator(), 1);
        AnalysisTask task = svc.submit(
                new SampleSubmission("run.fwimg", Samples.cleanFirmware()), "run");
        awaitStatus(svc, task.getTaskId(), TaskStatus.RUNNING, Duration.ofSeconds(5));
        svc.cancel(task.getTaskId());
        AnalysisTask end = awaitTerminal(svc, task.getTaskId(), Duration.ofSeconds(10));
        log.info("场景=运行中取消 taskId={} 最终status={} conclusion={}",
                task.getTaskId(), end.getStatus(), end.getConclusion());
        assertEquals(TaskStatus.CANCELLED, end.getStatus(),
                "取消与完成写回竞争时，最终状态必须确定为 CANCELLED 或 COMPLETED 之一且不回退");
    }

    @Test
    void terminalTaskCannotTransitionBack() {
        TaskService svc = newService(2);
        AnalysisTask task = svc.submit(
                new SampleSubmission("x.fwimg", Samples.cleanFirmware()), "once");
        AnalysisTask done = awaitTerminal(svc, task.getTaskId(), Duration.ofSeconds(10));
        assertEquals(TaskStatus.COMPLETED, done.getStatus());
        // 对终态任务再次取消/重复提交都不能改变状态或触发执行
        svc.cancel(task.getTaskId());
        svc.submit(new SampleSubmission("x.fwimg", Samples.cleanFirmware()), "once");
        AnalysisTask again = svc.get(task.getTaskId());
        log.info("场景=终态不回退 taskId={} status={} submitCount={}",
                again.getTaskId(), again.getStatus(), again.getSubmitCount());
        assertEquals(TaskStatus.COMPLETED, again.getStatus());
        assertEquals(TaskConclusion.PASS, again.getConclusion());
        assertEquals(2, again.getSubmitCount());
    }

    private static void awaitStatus(TaskService svc, String id, TaskStatus expected, Duration timeout) {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            if (svc.get(id).getStatus() == expected) {
                return;
            }
            try {
                Thread.sleep(2);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException(e);
            }
        }
        throw new AssertionError("任务 " + id + " 未在超时内进入状态 " + expected
                + "，当前状态=" + svc.get(id).getStatus());
    }

    private static AnalysisRule slowRule(String id, String subject, long sleepMs) {
        return new AbstractAnalysisRule() {
            @Override public String id() { return id; }
            @Override public int version() { return 1; }
            @Override public String subject() { return subject; }
            @Override
            protected RuleFinding doEvaluate(RuleContext context) {
                try {
                    Thread.sleep(sleepMs);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("规则被中断: " + id, e);
                }
                return finding(RuleVerdict.PASS, "慢规则完成 " + id);
            }
        };
    }

    @Test
    void failingRuleMarksTaskPartialFailedNeverPass() {
        InMemoryRuleRegistry registry = new InMemoryRuleRegistry(List.of(
                BuiltInRules.defaults().get(0),
                BuiltInRules.alwaysError("R999", "EXTERNAL_ENGINE")));
        TaskService svc = new TaskService(new InMemoryTaskRepository(),
                new DefaultSampleParser(new MagicFormatDetector()),
                registry, new FindingAggregator(), 2);
        AnalysisTask task = svc.submit(
                new SampleSubmission("p.fwimg", Samples.cleanFirmware()), "partial");
        AnalysisTask end = awaitTerminal(svc, task.getTaskId(), Duration.ofSeconds(10));
        log.info("场景=规则异常 taskId={} status={} conclusion={} 错误来源=EXTERNAL_ENGINE",
                task.getTaskId(), end.getStatus(), end.getConclusion());
        assertEquals(TaskStatus.PARTIAL_FAILED, end.getStatus(),
                "任一条规则执行失败必须标成部分失败");
        assertEquals(TaskConclusion.PARTIAL, end.getConclusion());
        assertTrue(end.getFindings().stream().anyMatch(f -> f.verdict() == RuleVerdict.ERROR),
                "失败规则必须留下 ERROR 判定记录");
    }

    @Test
    void historicalConclusionReproducesAfterRuleUpdate() {
        InMemoryRuleRegistry registry = new InMemoryRuleRegistry();
        TaskService svc = new TaskService(new InMemoryTaskRepository(),
                new DefaultSampleParser(new MagicFormatDetector()),
                registry, new FindingAggregator(), 2);
        // 1) 用 v1 内置规则跑干净样本 -> PASS
        AnalysisTask task = svc.submit(
                new SampleSubmission("h.fwimg", Samples.cleanFirmware()), "history");
        AnalysisTask done = awaitTerminal(svc, task.getTaskId(), Duration.ofSeconds(10));
        long frozenVersion = done.getRuleSetVersion();
        assertEquals(TaskConclusion.PASS, done.getConclusion());

        // 2) 规则更新：替换为“同主体硬判 FAIL”的更严规则，版本递增
        AnalysisRule strict = new AbstractAnalysisRule() {
            @Override public String id() { return "R001"; }
            @Override public int version() { return 2; }
            @Override public String subject() { return "CREDENTIALS"; }
            @Override
            protected RuleFinding doEvaluate(RuleContext context) {
                return finding(RuleVerdict.FAIL, "v2 凭证策略强制要求 HSM 托管");
            }
        };
        registry.replaceAll(List.of(strict));
        assertTrue(registry.ruleSetVersion() > frozenVersion, "规则更新后版本必须递增");

        // 3) 历史任务按冻结的旧快照复跑，结论与依据必须完全一致
        ReplayReport report = svc.replay(task.getTaskId());
        log.info("场景=规则版本复现 taskId={} frozen=v{} current=v{} original={} replay={} reproducible={}",
                task.getTaskId(), frozenVersion, registry.ruleSetVersion(),
                report.originalConclusion(), report.replayConclusion(), report.reproducible());
        assertTrue(report.reproducible(), "规则更新后历史结论必须可复现");
        assertEquals(TaskConclusion.PASS, report.replayConclusion());
        assertEquals(done.getConclusion(), svc.get(task.getTaskId()).getConclusion(),
                "已存储的历史结论不允许被规则更新改写");
    }

    @Test
    void malformedTaskHasDistinguishableFailureReason() {
        TaskService svc = newService(2);
        AnalysisTask truncated = svc.submit(new SampleSubmission(
                "t.fwimg", Samples.truncated(Samples.cleanFirmware(), 8)), "trunc");
        AnalysisTask unsupported = svc.submit(new SampleSubmission(
                "u.bin", Samples.unknownBytes()), "unsup");
        AnalysisTask mismatch = svc.submit(new SampleSubmission(
                "m.elf", Samples.cleanFirmware()), "mismatch");

        for (AnalysisTask t : List.of(truncated, unsupported, mismatch)) {
            AnalysisTask end = awaitTerminal(svc, t.getTaskId(), Duration.ofSeconds(10));
            assertEquals(TaskStatus.FAILED, end.getStatus());
            assertNotNull(end.getFailureReason());
            log.info("场景=畸形样本失败 taskId={} file={} reason={} 错误来源=样本解析 判定依据={}",
                    end.getTaskId(), end.getFileName(),
                    end.getFailureReason().getCode(), end.getFailureMessage());
        }
        assertEquals("TRUNCATED_CONTENT", truncated.getFailureReason().getCode());
        assertEquals("UNSUPPORTED_FORMAT", unsupported.getFailureReason().getCode());
        assertEquals("EXTENSION_MISMATCH", mismatch.getFailureReason().getCode());
    }
}
