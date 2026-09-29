package com.github.xingjianluo.firmwareanalysisorchestratorjava.rule;

import com.github.xingjianluo.firmwareanalysisorchestratorjava.model.RuleFinding;

/**
 * 一条不可变的分析规则。版本号随规则内容变更而递增，
 * 任务提交时会对规则集合做快照，保证历史结论可复现。
 */
public interface AnalysisRule {
    String id();
    int version();
    String subject();
    RuleFinding evaluate( RuleContext context);
}
