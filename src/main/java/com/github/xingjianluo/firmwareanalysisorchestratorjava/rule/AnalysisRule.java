package com.github.xingjianluo.firmwareanalysisorchestratorjava.rule;

/**
 * 分析规则。同 {@code group()} 内的规则若命中结论互相矛盾，
 * 汇总时会判定为 INCONCLUSIVE。规则实现允许抛异常，由执行器收敛。
 */
public interface AnalysisRule {

    String id();

    /** 冲突分组标识。 */
    String group();

    RuleOutcome evaluate(RuleEvaluationContext context) throws Exception;
}
