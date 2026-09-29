package com.github.xingjianluo.firmwareanalysisorchestratorjava.tests;

import com.github.xingjianluo.firmwareanalysisorchestratorjava.model.ParsedFirmware;
import com.github.xingjianluo.firmwareanalysisorchestratorjava.model.RuleFinding;
import com.github.xingjianluo.firmwareanalysisorchestratorjava.model.RuleVerdict;
import com.github.xingjianluo.firmwareanalysisorchestratorjava.model.SampleSubmission;
import com.github.xingjianluo.firmwareanalysisorchestratorjava.model.TaskConclusion;
import com.github.xingjianluo.firmwareanalysisorchestratorjava.rule.AbstractAnalysisRule;
import com.github.xingjianluo.firmwareanalysisorchestratorjava.rule.AnalysisRule;
import com.github.xingjianluo.firmwareanalysisorchestratorjava.rule.BuiltInRules;
import com.github.xingjianluo.firmwareanalysisorchestratorjava.rule.FindingAggregator;
import com.github.xingjianluo.firmwareanalysisorchestratorjava.rule.RuleContext;
import com.github.xingjianluo.firmwareanalysisorchestratorjava.sample.DefaultSampleParser;
import com.github.xingjianluo.firmwareanalysisorchestratorjava.sample.MagicFormatDetector;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 规则匹配、冲突判定与“规则失败即部分失败”的单测。 */
class RuleEngineTest {

    private static final Logger log = LoggerFactory.getLogger(RuleEngineTest.class);

    private final DefaultSampleParser parser = new DefaultSampleParser(new MagicFormatDetector());
    private final FindingAggregator aggregator = new FindingAggregator();

    private RuleContext ctx(byte[] data) {
        SampleSubmission sub = new SampleSubmission("dev.fwimg", data);
        ParsedFirmware fw = parser.parse(sub);
        return new RuleContext(sub, fw);
    }

    private List<RuleFinding> runAll(List<AnalysisRule> rules, RuleContext context) {
        return rules.stream().map(r -> r.evaluate(context)).toList();
    }

    @Test
    void cleanFirmwarePassesAllRules() {
        List<RuleFinding> findings = runAll(BuiltInRules.defaults(), ctx(Samples.cleanFirmware()));
        TaskConclusion conclusion = aggregator.aggregate(findings);
        findings.forEach(f -> log.info(
                "taskId=unit-clean ruleId={} subject={} verdict={} 判定依据={} errorSource={}",
                f.ruleId(), f.subject(), f.verdict(), f.basis(),
                f.errorSource() == null ? "-" : f.errorSource()));
        assertEquals(TaskConclusion.PASS, conclusion);
    }

    @Test
    void riskyFirmwareFails() {
        List<RuleFinding> findings = runAll(BuiltInRules.defaults(), ctx(Samples.riskyFirmware()));
        TaskConclusion conclusion = aggregator.aggregate(findings);
        log.info("taskId=unit-risky conclusion={} 判定依据=命中FAIL规则", conclusion);
        assertEquals(TaskConclusion.FAIL, conclusion);
        assertTrue(findings.stream().anyMatch(f -> f.ruleId().equals("R001")
                && f.verdict() == RuleVerdict.FAIL));
    }

    @Test
    void conflictingRulesOnSameSubjectYieldConflict() {
        // 干净样本下 R001(CREDENTIALS) 为 PASS；再加一条同主体、判 FAIL 的规则，形成冲突
        AnalysisRule credentialBanV2 = new AbstractAnalysisRule() {
            @Override public String id() { return "R901"; }
            @Override public int version() { return 2; }
            @Override public String subject() { return "CREDENTIALS"; }
            @Override
            protected RuleFinding doEvaluate(RuleContext context) {
                return finding(RuleVerdict.FAIL, "凭证策略 v2 要求 META 中声明 credential-policy=hsm");
            }
        };
        List<AnalysisRule> rules = List.of(
                BuiltInRules.defaults().get(0), credentialBanV2);
        List<RuleFinding> findings = runAll(rules, ctx(Samples.cleanFirmware()));
        TaskConclusion conclusion = aggregator.aggregate(findings);
        findings.forEach(f -> log.info(
                "taskId=unit-conflict ruleId={} subject={} verdict={} 判定依据={}",
                f.ruleId(), f.subject(), f.verdict(), f.basis()));
        assertEquals(TaskConclusion.CONFLICT, conclusion, "同一主体 PASS/FAIL 必须判为 CONFLICT");
    }

    @Test
    void failingRuleProducesPartialNeverPass() {
        AnalysisRule broken = BuiltInRules.alwaysError("R999", "EXTERNAL_ENGINE");
        List<AnalysisRule> rules = List.of(
                BuiltInRules.defaults().get(0), // R001 在干净样本上 PASS
                broken);
        List<RuleFinding> findings = runAll(rules, ctx(Samples.cleanFirmware()));
        TaskConclusion conclusion = aggregator.aggregate(findings);
        RuleFinding errorFinding = findings.stream()
                .filter(f -> f.verdict() == RuleVerdict.ERROR).findFirst().orElseThrow();
        log.info("taskId=unit-partial verdict=ERROR ruleId={} 判定依据={} errorSource={}",
                errorFinding.ruleId(), errorFinding.basis(), errorFinding.errorSource());
        assertEquals(TaskConclusion.PARTIAL, conclusion, "规则执行失败必须是部分失败");
        assertNotNull(errorFinding.errorSource(), "错误来源必须被记录");
        assertTrue(errorFinding.errorSource().contains("IllegalStateException"),
                "errorSource 应反映底层来源，但不向上抛裸异常");
    }

    @Test
    void backdoorAndMissingVersionAreExplainable() {
        TaskConclusion backdoor = aggregator.aggregate(
                runAll(BuiltInRules.defaults(), ctx(Samples.backdoorFirmware())));
        TaskConclusion noVersion = aggregator.aggregate(
                runAll(BuiltInRules.defaults(), ctx(Samples.noVersionFirmware())));
        log.info("taskId=unit-backdoor conclusion={}；taskId=unit-noversion conclusion={}",
                backdoor, noVersion);
        assertEquals(TaskConclusion.FAIL, backdoor);
        assertEquals(TaskConclusion.FAIL, noVersion);
    }
}
