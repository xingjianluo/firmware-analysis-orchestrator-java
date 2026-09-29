package com.github.xingjianluo.firmwareanalysisorchestratorjava.tests;

import com.github.xingjianluo.firmwareanalysisorchestratorjava.model.AnalysisConclusion;
import com.github.xingjianluo.firmwareanalysisorchestratorjava.model.FailureCode;
import com.github.xingjianluo.firmwareanalysisorchestratorjava.model.RuleResult;
import com.github.xingjianluo.firmwareanalysisorchestratorjava.model.TaskSnapshot;
import com.github.xingjianluo.firmwareanalysisorchestratorjava.model.TaskStatus;
import com.github.xingjianluo.firmwareanalysisorchestratorjava.model.Verdict;
import com.github.xingjianluo.firmwareanalysisorchestratorjava.orchestration.ReproductionResult;
import com.github.xingjianluo.firmwareanalysisorchestratorjava.orchestration.TaskOrchestrator;
import com.github.xingjianluo.firmwareanalysisorchestratorjava.orchestration.TaskStore;
import com.github.xingjianluo.firmwareanalysisorchestratorjava.rule.AnalysisRule;
import com.github.xingjianluo.firmwareanalysisorchestratorjava.rule.BuiltInRules;
import com.github.xingjianluo.firmwareanalysisorchestratorjava.rule.RuleEvaluationContext;
import com.github.xingjianluo.firmwareanalysisorchestratorjava.rule.RuleEvaluator;
import com.github.xingjianluo.firmwareanalysisorchestratorjava.rule.RuleOutcome;
import com.github.xingjianluo.firmwareanalysisorchestratorjava.rule.RuleRegistry;
import com.github.xingjianluo.firmwareanalysisorchestratorjava.sample.SampleParsingService;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 端到端：正常样本通过、畸形样本失败原因可区分、规则更新后历史结论可复现、
 * 规则异常导致部分失败。
 */
class OrchestrationEndToEndTest {

    private static final Logger log = LoggerFactory.getLogger(OrchestrationEndToEndTest.class);

    private static TaskSnapshot awaitTerminal(TaskOrchestrator o, String id, long timeoutMs)
            throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            TaskSnapshot s = o.get(id).orElseThrow();
            if (s.status().isTerminal()) {
                return s;
            }
            Thread.sleep(5);
        }
        throw new AssertionError("任务 " + id + " 超时未终结："
                + o.get(id).orElseThrow().status());
    }

    @Test
    void validSampleCompletesWithPass() throws Exception {
        TaskOrchestrator orchestrator = new TaskOrchestrator(new TaskStore(),
                SampleParsingService.withBuiltInParsers(), new RuleRegistry(), new RuleEvaluator());

        TaskSnapshot fin = awaitTerminalAfterSubmit(orchestrator, "e2e-valid",
                SampleFixtures.validFw1());

        assertEquals(TaskStatus.COMPLETED, fin.status());
        assertEquals(Verdict.PASS, fin.conclusion().verdict());
        assertEquals(1, fin.ruleVersion());
        for (RuleResult r : fin.conclusion().results()) {
            assertTrue(r.matched() && !r.errored(), "PASS 要求每条规则都命中：" + r.ruleId());
        }
        log.info("[端到端通过] taskId={} 状态={} 结论={} 判定依据={}",
                fin.taskId(), fin.status(), fin.conclusion().verdict(),
                fin.conclusion().reasons());
    }

    @Test
    void malformedSampleReportsDistinctFailureCode() throws Exception {
        TaskOrchestrator orchestrator = new TaskOrchestrator(new TaskStore(),
                SampleParsingService.withBuiltInParsers(), new RuleRegistry(), new RuleEvaluator());
        LinkedHashMap<String, String> meta = SampleFixtures.metadata("device", "r1");

        TaskSnapshot truncated = awaitTerminalAfterSubmit(orchestrator, "e2e-truncated",
                SampleFixtures.fw1Truncated(meta, 5));
        TaskSnapshot mismatch = awaitTerminalAfterSubmit(orchestrator, "e2e-mismatch",
                SampleFixtures.fw1HeaderDeclaredLength(meta, -5));
        TaskSnapshot corrupt = awaitTerminalAfterSubmit(orchestrator, "e2e-corrupt",
                SampleFixtures.fw1CorruptPayload(meta));
        TaskSnapshot unsupported = awaitTerminalAfterSubmit(orchestrator, "e2e-unsupported",
                SampleFixtures.unknownMagic());
        TaskSnapshot empty = awaitTerminalAfterSubmit(orchestrator, "e2e-empty",
                new byte[0]);

        assertFailure(truncated, FailureCode.TRUNCATED_SAMPLE);
        assertFailure(mismatch, FailureCode.HEADER_LENGTH_MISMATCH);
        assertFailure(corrupt, FailureCode.CORRUPT_PAYLOAD);
        assertFailure(unsupported, FailureCode.UNSUPPORTED_FORMAT);
        assertFailure(empty, FailureCode.EMPTY_SAMPLE);

        log.info("[端到端失败原因] taskId=e2e-truncated 错误来源=解析阶段 code={} 依据={}",
                truncated.failureCode(), truncated.failureMessage());
        log.info("[端到端失败原因] taskId=e2e-mismatch 错误来源=解析阶段 code={} 依据={}",
                mismatch.failureCode(), mismatch.failureMessage());
        log.info("[端到端失败原因] taskId=e2e-corrupt 错误来源=解析阶段 code={} 依据={}",
                corrupt.failureCode(), corrupt.failureMessage());
        log.info("[端到端失败原因] taskId=e2e-unsupported 错误来源=格式探测 code={} 依据={}",
                unsupported.failureCode(), unsupported.failureMessage());
        log.info("[端到端失败原因] taskId=e2e-empty 错误来源=输入校验 code={} 依据={}",
                empty.failureCode(), empty.failureMessage());
    }

    @Test
    void ruleUpdateKeepsHistoricalConclusionReproducible() throws Exception {
        RuleRegistry registry = new RuleRegistry();
        TaskOrchestrator orchestrator = new TaskOrchestrator(new TaskStore(),
                SampleParsingService.withBuiltInParsers(), registry, new RuleEvaluator());

        TaskSnapshot fin = awaitTerminalAfterSubmit(orchestrator, "e2e-history",
                SampleFixtures.validFw1());
        assertEquals(TaskStatus.COMPLETED, fin.status());
        int pinnedVersion = fin.ruleVersion();
        Verdict storedVerdict = fin.conclusion().verdict();
        List<RuleResult> storedResults = fin.conclusion().results();

        // 规则更新：长度限制变为永远不命中（模拟规则变化），新版本=2
        registry.updateRules(List.of(
                BuiltInRules.formatKnownRule(),
                BuiltInRules.versionV2Rule(),
                new AnalysisRule() {
                    @Override
                    public String id() {
                        return "R-PAYLOAD-LIMIT";
                    }

                    @Override
                    public String group() {
                        return "size";
                    }

                    @Override
                    public RuleOutcome evaluate(RuleEvaluationContext ctx) {
                        return new RuleOutcome(false, "v2 收紧：所有样本都超限");
                    }
                },
                BuiltInRules.requiredDeviceRule()));

        // 同一字节的新任务按新规则得到不同结论
        TaskSnapshot after = awaitTerminalAfterSubmit(orchestrator, "e2e-after-update",
                SampleFixtures.validFw1());
        assertEquals(Verdict.FAIL, after.conclusion().verdict());
        assertEquals(2, after.ruleVersion());

        // 历史任务的固化结论保持原样，且可按 v1 复现
        ReproductionResult reproduction = orchestrator.reproduce("e2e-history").orElseThrow();
        assertTrue(reproduction.consistent());
        assertEquals(storedVerdict, reproduction.storedVerdict());
        assertEquals(storedVerdict, reproduction.reproducedVerdict());
        assertEquals(pinnedVersion, reproduction.ruleVersion());

        // 任务快照本身也没被规则更新影响
        TaskSnapshot stillThere = orchestrator.get("e2e-history").orElseThrow();
        assertEquals(storedResults, stillThere.conclusion().results());

        log.info("[端到端历史复现] taskId=e2e-history 固化版本={} stored={} | 新任务 e2e-after-update 版本={} verdict={} | 复现一致={}",
                pinnedVersion, storedVerdict, 2, after.conclusion().verdict(),
                reproduction.consistent());
    }

    @Test
    void failingRuleProducesPartialFailureNotPass() throws Exception {
        RuleRegistry registry = new RuleRegistry();
        // 替换规则集：保留三条正常规则，加一条必抛异常的规则
        AnalysisRule broken = new AnalysisRule() {
            @Override
            public String id() {
                return "R-EXPLODING";
            }

            @Override
            public String group() {
                return "security";
            }

            @Override
            public RuleOutcome evaluate(RuleEvaluationContext context) {
                throw new ArithmeticException("模拟规则引擎内部错误");
            }
        };
        registry.updateRules(List.of(
                BuiltInRules.formatKnownRule(),
                BuiltInRules.versionV2Rule(),
                BuiltInRules.payloadLimitRule(),
                BuiltInRules.requiredDeviceRule(),
                broken));

        TaskOrchestrator orchestrator = new TaskOrchestrator(new TaskStore(),
                SampleParsingService.withBuiltInParsers(), registry, new RuleEvaluator());

        TaskSnapshot fin = awaitTerminalAfterSubmit(orchestrator, "e2e-partial",
                SampleFixtures.validFw1());
        assertEquals(TaskStatus.PARTIAL_FAILED, fin.status());
        AnalysisConclusion c = fin.conclusion();
        assertEquals(Verdict.PARTIAL, c.verdict());
        RuleResult brokenResult = c.results().stream()
                .filter(r -> r.ruleId().equals("R-EXPLODING")).findFirst().orElseThrow();
        assertTrue(brokenResult.errored());
        assertEquals("ArithmeticException", brokenResult.errorSource());
        assertEquals(FailureCode.RULE_EXECUTION_ERROR, fin.failureCode());
        assertNotNull(fin.failureMessage());
        log.info("[端到端部分失败] taskId=e2e-partial 状态={} 结论={} 错误来源={} 判定依据={}",
                fin.status(), c.verdict(), brokenResult.errorSource(), c.reasons());
    }

    private static TaskSnapshot awaitTerminalAfterSubmit(TaskOrchestrator orchestrator,
                                                         String taskId, byte[] content)
            throws InterruptedException {
        orchestrator.submit(taskId, SampleFixtures.descriptor("fw1", content));
        return awaitTerminal(orchestrator, taskId, 10_000);
    }

    private static void assertFailure(TaskSnapshot snapshot, FailureCode expected) {
        assertEquals(TaskStatus.FAILED, snapshot.status(), "任务应为 FAILED：" + snapshot.taskId());
        assertEquals(expected, snapshot.failureCode(), "失败原因码必须可区分：" + snapshot.taskId());
        assertNotNull(snapshot.failureMessage(), "必须给出可读失败说明");
    }
}
