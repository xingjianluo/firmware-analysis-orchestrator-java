package com.github.xingjianluo.firmwareanalysisorchestratorjava.model;

/** 单条规则的执行记录，构成任务结论的可解释依据。 */
public record RuleFinding(
        String ruleId,
        String ruleVersion,
        String subject,
        RuleVerdict verdict,
        String basis,
        String errorSource
) {
}
