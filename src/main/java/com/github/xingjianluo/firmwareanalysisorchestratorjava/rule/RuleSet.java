package com.github.xingjianluo.firmwareanalysisorchestratorjava.rule;

import java.util.List;

/**
 * 某一版本的不可变规则集快照。历史任务固化 version，
 * 可据此版本取回完全相同的规则集复现结论。
 */
public record RuleSet(int version, List<AnalysisRule> rules) {
}
