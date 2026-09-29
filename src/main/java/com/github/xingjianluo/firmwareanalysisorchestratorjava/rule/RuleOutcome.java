package com.github.xingjianluo.firmwareanalysisorchestratorjava.rule;

/**
 * 单条规则的判定输出：是否命中 + 判定依据说明。
 */
public record RuleOutcome(boolean matched, String evidence) {
}
