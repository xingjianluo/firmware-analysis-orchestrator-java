package com.github.xingjianluo.firmwareanalysisorchestratorjava.tests;

import com.github.xingjianluo.firmwareanalysisorchestratorjava.model.TaskSnapshot;
import com.github.xingjianluo.firmwareanalysisorchestratorjava.model.TaskStatus;
import com.github.xingjianluo.firmwareanalysisorchestratorjava.orchestration.TaskOrchestrator;
import com.github.xingjianluo.firmwareanalysisorchestratorjava.orchestration.TaskStore;
import com.github.xingjianluo.firmwareanalysisorchestratorjava.rule.RuleEvaluator;
import com.github.xingjianluo.firmwareanalysisorchestratorjava.rule.RuleRegistry;
import com.github.xingjianluo.firmwareanalysisorchestratorjava.sample.SampleParsingService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 并发提交：彼此独立、重复提交不重复执行、状态不回退。
 */
class TaskOrchestratorConcurrencyTest {

    private static final Logger log = LoggerFactory.getLogger(TaskOrchestratorConcurrencyTest.class);

    private TaskOrchestrator orchestrator;
    private ExecutorService pool;

    private TaskOrchestrator newOrchestrator() {
        return new TaskOrchestrator(new TaskStore(),
                SampleParsingService.withBuiltInParsers(),
                new RuleRegistry(), new RuleEvaluator());
    }

    @AfterEach
    void tearDown() {
        if (pool != null) {
            pool.shutdownNow();
        }
    }

    private static void awaitTerminal(TaskOrchestrator orchestrator, String taskId, long timeoutMs)
            throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            TaskSnapshot s = orchestrator.get(taskId).orElseThrow();
            if (s.status().isTerminal()) {
                return;
            }
            Thread.sleep(5);
        }
        throw new AssertionError("任务 " + taskId + " 在超时内未进入终态，当前状态="
                + orchestrator.get(taskId).orElseThrow().status());
    }

    @Test
    void concurrentDistinctTasksRunIndependently() throws Exception {
        orchestrator = newOrchestrator();
        int taskCount = 30;
        pool = Executors.newFixedThreadPool(taskCount);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(taskCount);
        Set<String> terminalVerdicts = ConcurrentHashMap.newKeySet();

        for (int i = 0; i < taskCount; i++) {
            String taskId = "task-concurrent-" + i;
            pool.submit(() -> {
                try {
                    start.await();
                    TaskSnapshot s = orchestrator.submit(taskId,
                            SampleFixtures.descriptor("fw1", SampleFixtures.validFw1()));
                    assertTrue(s.accepted());
                    done.countDown();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
        }
        start.countDown();
        assertTrue(done.await(10, TimeUnit.SECONDS), "所有提交都应在超时内返回");

        for (int i = 0; i < taskCount; i++) {
            String taskId = "task-concurrent-" + i;
            awaitTerminal(orchestrator, taskId, 10_000);
            TaskSnapshot finalSnap = orchestrator.get(taskId).orElseThrow();
            assertEquals(TaskStatus.COMPLETED, finalSnap.status());
            terminalVerdicts.add(finalSnap.conclusion().verdict().name());
        }
        assertEquals(Set.of("PASS"), terminalVerdicts);
        log.info("[并发独立] taskId=task-concurrent-0..29 全部完成 结论集合={}（彼此独立，终态一致）",
                terminalVerdicts);
    }

    @Test
    void duplicateSubmissionsExecuteOnce() throws Exception {
        orchestrator = newOrchestrator();
        int threads = 20;
        String taskId = "task-duplicate";
        pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<TaskSnapshot> snapshots = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            snapshots.add(null);
        }
        List<Exception> errors = new ArrayList<>();

        for (int i = 0; i < threads; i++) {
            int idx = i;
            pool.submit(() -> {
                try {
                    start.await();
                    snapshots.set(idx, orchestrator.submit(taskId,
                            SampleFixtures.descriptor("fw1", SampleFixtures.validFw1())));
                } catch (Exception e) {
                    synchronized (errors) {
                        errors.add(e);
                    }
                }
            });
        }
        start.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));
        assertTrue(errors.isEmpty(), "并发重复提交不应抛异常：" + errors);

        long accepted = snapshots.stream().filter(TaskSnapshot::accepted).count();
        long duplicates = snapshots.stream().filter(TaskSnapshot::duplicate).count();
        assertEquals(1, accepted, "同一 taskId 只能被接受一次");
        assertEquals(threads - 1, duplicates, "其余提交必须被去重");

        awaitTerminal(orchestrator, taskId, 10_000);
        TaskSnapshot fin = orchestrator.get(taskId).orElseThrow();
        assertEquals(TaskStatus.COMPLETED, fin.status());
        log.info("[重复提交] taskId={} 并发线程={} accepted={} duplicate={} 最终状态={} 结论={}（只执行一次）",
                taskId, threads, accepted, duplicates, fin.status(), fin.conclusion().verdict());
    }

    @Test
    void statusNeverMovesBackwards() throws Exception {
        orchestrator = newOrchestrator();
        String taskId = "task-forward";
        TaskSnapshot first = orchestrator.submit(taskId,
                SampleFixtures.descriptor("fw1", SampleFixtures.validFw1()));
        List<TaskStatus> observed = new ArrayList<>();
        observed.add(first.status());

        long deadline = System.currentTimeMillis() + 10_000;
        TaskStatus last = first.status();
        while (System.currentTimeMillis() < deadline) {
            TaskStatus current = orchestrator.get(taskId).orElseThrow().status();
            if (current != last) {
                observed.add(current);
                last = current;
            }
            if (current.isTerminal()) {
                break;
            }
            Thread.sleep(1);
        }
        // 观察到的状态序列必须严格沿 PENDING/QUEUED -> RUNNING -> COMPLETED 前进
        for (int i = 1; i < observed.size(); i++) {
            assertTrue(observed.get(i - 1).ordinal() <= observed.get(i).ordinal(),
                    "状态不允许回退：" + observed);
        }
        assertEquals(TaskStatus.COMPLETED, last);
        log.info("[状态流转] taskId={} 观察到的状态序列={}", taskId, observed);
    }
}
