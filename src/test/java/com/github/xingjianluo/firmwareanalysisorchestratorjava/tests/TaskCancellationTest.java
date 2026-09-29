package com.github.xingjianluo.firmwareanalysisorchestratorjava.tests;

import com.github.xingjianluo.firmwareanalysisorchestratorjava.model.ParseOutcome;
import com.github.xingjianluo.firmwareanalysisorchestratorjava.model.SampleDescriptor;
import com.github.xingjianluo.firmwareanalysisorchestratorjava.model.TaskSnapshot;
import com.github.xingjianluo.firmwareanalysisorchestratorjava.model.TaskStatus;
import com.github.xingjianluo.firmwareanalysisorchestratorjava.orchestration.TaskOrchestrator;
import com.github.xingjianluo.firmwareanalysisorchestratorjava.orchestration.TaskStore;
import com.github.xingjianluo.firmwareanalysisorchestratorjava.rule.RuleEvaluator;
import com.github.xingjianluo.firmwareanalysisorchestratorjava.rule.RuleRegistry;
import com.github.xingjianluo.firmwareanalysisorchestratorjava.sample.SampleParsingService;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 取消语义：排队阶段取消则不执行、不写回结果；终态任务取消被拒绝。
 */
class TaskCancellationTest {

    private static final Logger log = LoggerFactory.getLogger(TaskCancellationTest.class);

    private static void waitForStatus(TaskOrchestrator orchestrator, String taskId,
                                      TaskStatus expected, long timeoutMs) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            TaskStatus current = orchestrator.get(taskId).orElseThrow().status();
            if (current == expected) {
                return;
            }
            Thread.sleep(2);
        }
        throw new AssertionError("任务 " + taskId + " 未在超时内到达 " + expected
                + "，当前=" + orchestrator.get(taskId).orElseThrow().status());
    }

    @Test
    void cancelledTaskKeepsCancelledAndNoResult() throws Exception {
        // 单线程执行器先被“阻塞门”占住，保证提交的任务停留在 QUEUED
        ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "blocking-worker");
            t.setDaemon(true);
            return t;
        });
        CountDownLatch gate = new CountDownLatch(1);
        executor.execute(() -> {
            try {
                gate.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });

        SampleParsingService parsingService = SampleParsingService.withBuiltInParsers();
        TaskOrchestrator orchestrator = new TaskOrchestrator(new TaskStore(), parsingService,
                new RuleRegistry(), new RuleEvaluator(), executor);

        String taskId = "task-cancel-queued";
        TaskSnapshot submitted = orchestrator.submit(taskId,
                SampleFixtures.descriptor("fw1", SampleFixtures.validFw1()));
        assertEquals(TaskStatus.QUEUED, submitted.status());

        TaskSnapshot cancelled = orchestrator.cancel(taskId).orElseThrow();
        assertEquals(TaskStatus.CANCELLED, cancelled.status());

        // 放开阻塞门，让被取消的工作单元真正运行；它必须在 QUEUED->RUNNING 处被拒绝
        gate.countDown();
        Thread.sleep(200);

        TaskSnapshot after = orchestrator.get(taskId).orElseThrow();
        assertEquals(TaskStatus.CANCELLED, after.status());
        assertNull(after.parseOutcome(), "已取消任务不得写回解析结果");
        assertNull(after.conclusion(), "已取消任务不得写回汇总结论");
        assertNull(after.failureCode());
        log.info("[取消不写回] taskId={} 最终状态={} parseOutcome=null conclusion=null（取消生效，结果未写回）",
                taskId, after.status());

        executor.shutdownNow();
        executor.awaitTermination(5, TimeUnit.SECONDS);
    }

    @Test
    void cancelTerminalTaskIsRejected() throws Exception {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        TaskOrchestrator orchestrator = new TaskOrchestrator(new TaskStore(),
                SampleParsingService.withBuiltInParsers(), new RuleRegistry(),
                new RuleEvaluator(), executor);

        String taskId = "task-cancel-terminal";
        orchestrator.submit(taskId, SampleFixtures.descriptor("fw1", SampleFixtures.validFw1()));
        waitForStatus(orchestrator, taskId, TaskStatus.COMPLETED, 10_000);

        TaskSnapshot cancelledAgain = orchestrator.cancel(taskId).orElseThrow();
        assertEquals(TaskStatus.COMPLETED, cancelledAgain.status());

        // 解析失败的终态任务同样不可取消
        String failedId = "task-cancel-failed";
        orchestrator.submit(failedId, SampleFixtures.descriptor("fw1", SampleFixtures.unknownMagic()));
        waitForStatus(orchestrator, failedId, TaskStatus.FAILED, 10_000);
        TaskSnapshot failed = orchestrator.get(failedId).orElseThrow();
        assertEquals(TaskStatus.FAILED, failed.status());
        assertEquals(com.github.xingjianluo.firmwareanalysisorchestratorjava.model.FailureCode.UNSUPPORTED_FORMAT,
                failed.failureCode());
        orchestrator.cancel(failedId);
        assertEquals(TaskStatus.FAILED, orchestrator.get(failedId).orElseThrow().status());

        // 不存在的任务取消返回 empty
        assertTrue(orchestrator.cancel("not-exists").isEmpty());

        log.info("[终态不可取消] taskId={} 保持 COMPLETED；taskId={} 保持 FAILED/UNSUPPORTED_FORMAT",
                taskId, failedId);
        executor.shutdownNow();
    }

    /** 防止 IDE 误报未使用：占位，解析逻辑由真实 parsingService 承担。 */
    @SuppressWarnings("unused")
    private static ParseOutcome unused(SampleDescriptor d, SampleParsingService s) {
        return s.parse(d);
    }
}
