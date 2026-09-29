package com.github.xingjianluo.firmwareanalysisorchestratorjava.tests;

import com.github.xingjianluo.firmwareanalysisorchestratorjava.model.AnalysisConclusion;
import com.github.xingjianluo.firmwareanalysisorchestratorjava.model.ParseOutcome;
import com.github.xingjianluo.firmwareanalysisorchestratorjava.model.Verdict;
import com.github.xingjianluo.firmwareanalysisorchestratorjava.rule.AnalysisRule;
import com.github.xingjianluo.firmwareanalysisorchestratorjava.rule.BuiltInRules;
import com.github.xingjianluo.firmwareanalysisorchestratorjava.rule.RuleEvaluationContext;
import com.github.xingjianluo.firmwareanalysisorchestratorjava.rule.RuleEvaluator;
import com.github.xingjianluo.firmwareanalysisorchestratorjava.rule.RuleOutcome;
import com.github.xingjianluo.firmwareanalysisorchestratorjava.rule.RuleRegistry;
import com.github.xingjianluo.firmwareanalysisorchestratorjava.rule.RuleSet;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 规则命中/缺失/冲突/执行失败的汇总结论稳定性，以及历史版本复现。
 */
class RuleEvaluatorTest {

    private static final Logger log = LoggerFactory.getLogger(RuleEvaluatorTest.class);

    private final RuleEvaluator evaluator = new RuleEvaluator();

    @Test
    void allMatchedProducesPass() {
        AnalysisConclusion c = evaluator.evaluate(goodFw1Parse(),
                new RuleSet(1, BuiltInRules.version1()));
        assertEquals(Verdict.PASS, c.verdict());
        assertTrue(c.results().stream().allMatch(r -> r.matched() && !r.errored()));
        log.info("[规则汇总] taskId=test-rule-pass 结论={} 判定依据={}", c.verdict(), c.reasons());
    }

    @Test
    void missingRuleProducesFailWithStableReason() {
        // 超长样本：R-PAYLOAD-LIMIT 未命中 -> FAIL
        ParseOutcome oversized = ParseOutcome.success("FW1", java.util.Map.of(
                "format", "FW1",
                "version", "2",
                "device", "x",
                "rawContentLength", "120"));
        AnalysisConclusion c = evaluator.evaluate(oversized,
                new RuleSet(1, BuiltInRules.version1()));
        assertEquals(Verdict.FAIL, c.verdict());
        assertTrue(c.results().stream().anyMatch(r -> r.ruleId().equals("R-PAYLOAD-LIMIT") && !r.matched()));
        log.info("[规则缺失] taskId=test-rule-miss 结论={} 判定依据={}", c.verdict(), c.reasons());
    }

    @Test
    void conflictingRulesProduceInconclusive() {
        AnalysisRule positive = rule("R-GRP-A-YES", "grpA", true, "证据：应当通过");
        AnalysisRule negative = rule("R-GRP-A-NO", "grpA", false, "证据：应当拒绝");
        AnalysisConclusion c = evaluator.evaluate(goodFw1Parse(),
                new RuleSet(1, List.of(positive, negative)));
        assertEquals(Verdict.INCONCLUSIVE, c.verdict());
        assertEquals(1, c.conflicts().size());
        assertTrue(c.conflicts().get(0).contains("grpA"));
        log.info("[规则冲突] taskId=test-rule-conflict 结论={} 判定依据={}", c.verdict(), c.reasons());
    }

    @Test
    void anyRuleErrorProducesPartialVerdict() {
        AnalysisRule broken = new AnalysisRule() {
            @Override
            public String id() {
                return "R-ALWAYS-BROKEN";
            }

            @Override
            public String group() {
                return "grpBroken";
            }

            @Override
            public RuleOutcome evaluate(RuleEvaluationContext context) {
                throw new IllegalStateException("模拟规则内部故障");
            }
        };
        AnalysisConclusion c = evaluator.evaluate(goodFw1Parse(),
                new RuleSet(1, List.of(BuiltInRules.version1().get(0), broken)));
        assertEquals(Verdict.PARTIAL, c.verdict());
        assertTrue(c.results().stream().anyMatch(r -> r.ruleId().equals("R-ALWAYS-BROKEN")
                && r.errored() && "IllegalStateException".equals(r.errorSource())));
        // 即使有规则命中，只要一条规则出错，也绝不能是 PASS
        assertFalse(c.verdict() == Verdict.PASS);
        log.info("[规则错误] taskId=test-rule-error 结论={} 错误来源=IllegalStateException 判定依据={}",
                c.verdict(), c.reasons());
    }

    @Test
    void historicalConclusionReproducedWithOldRuleVersion() {
        RuleRegistry registry = new RuleRegistry();
        ParseOutcome parse = goodFw1Parse();

        // v1 下得到结论并固化
        AnalysisConclusion stored = evaluator.evaluate(parse, registry.current());
        assertEquals(Verdict.PASS, stored.verdict());

        // 更新规则到 v2：device 字段不再要求（替换为空 device 校验的规则），长度限制改严
        registry.updateRules(List.of(
                BuiltInRules.formatKnownRule(),
                BuiltInRules.versionV2Rule(),
                rule("R-PAYLOAD-LIMIT-V2", "size", false, "v2 新规则：更严格的限制"),
                BuiltInRules.requiredDeviceRule()));
        assertEquals(2, registry.currentVersionNumber());

        AnalysisConclusion underV2 = evaluator.evaluate(parse, registry.current());
        assertEquals(Verdict.FAIL, underV2.verdict());

        // 用固化的 v1 规则集复现，结论必须与历史一致
        RuleSet pinned = registry.get(stored.ruleVersion()).orElseThrow();
        AnalysisConclusion reproduced = evaluator.evaluate(parse, pinned);
        assertEquals(stored.verdict(), reproduced.verdict());
        assertEquals(stored.results(), reproduced.results());
        log.info("[历史复现] taskId=test-rule-reproduce 固化版本={} stored={} 当前版本={} underV2={} 复现一致={}",
                stored.ruleVersion(), stored.verdict(),
                registry.currentVersionNumber(), underV2.verdict(),
                reproduced.results().equals(stored.results()));
    }

    private static ParseOutcome goodFw1Parse() {
        return ParseOutcome.success("FW1", java.util.Map.of(
                "format", "FW1",
                "version", "2",
                "device", "router-x1",
                "payloadLength", "24",
                "rawContentLength", "34"));
    }

    private static AnalysisRule rule(String id, String group, boolean matched, String evidence) {
        return new AnalysisRule() {
            @Override
            public String id() {
                return id;
            }

            @Override
            public String group() {
                return group;
            }

            @Override
            public RuleOutcome evaluate(RuleEvaluationContext context) {
                return new RuleOutcome(matched, evidence);
            }
        };
    }
}
