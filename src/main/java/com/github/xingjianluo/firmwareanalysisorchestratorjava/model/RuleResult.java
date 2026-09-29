package com.github.xingjianluo.firmwareanalysisorchestratorjava.model;

/**
 * 单条规则对某个样本的判定结果。
 *
 * @param ruleId      规则标识
 * @param group       规则所属冲突分组
 * @param version     规则版本（规则集版本）
 * @param matched     是否命中
 * @param errored     规则执行是否抛出异常
 * @param evidence    判定依据的可读说明（单测日志据此核对）
 * @param errorSource 错误来源（errored=true 时记录异常类型，否则 null）
 */
public record RuleResult(String ruleId,
                         String group,
                         int version,
                         boolean matched,
                         boolean errored,
                         String evidence,
                         String errorSource) {

    public static RuleResult matched(String ruleId, String group, int version, String evidence) {
        return new RuleResult(ruleId, group, version, true, false, evidence, null);
    }

    public static RuleResult notMatched(String ruleId, String group, int version, String evidence) {
        return new RuleResult(ruleId, group, version, false, false, evidence, null);
    }

    public static RuleResult error(String ruleId, String group, int version,
                                   String evidence, String errorSource) {
        return new RuleResult(ruleId, group, version, false, true, evidence, errorSource);
    }
}
