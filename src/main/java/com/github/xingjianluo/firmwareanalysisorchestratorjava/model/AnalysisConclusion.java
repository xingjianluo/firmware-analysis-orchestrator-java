package com.github.xingjianluo.firmwareanalysisorchestratorjava.model;

import java.util.List;

/**
 * 多条规则的汇总结论。结论与规则集版本一起固化在任务里，
 * 规则更新后历史任务的结论仍可原样复现。
 *
 * @param verdict     总体结论
 * @param ruleVersion 产出该结论时使用的规则集版本
 * @param results     每条规则的判定明细（按规则标识排序，保证稳定）
 * @param conflicts   冲突说明（同组规则结论矛盾时存在）
 * @param reasons     结论的判定依据汇总
 */
public record AnalysisConclusion(Verdict verdict,
                                 int ruleVersion,
                                 List<RuleResult> results,
                                 List<String> conflicts,
                                 List<String> reasons) {
}
