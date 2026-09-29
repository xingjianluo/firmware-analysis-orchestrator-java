package com.github.xingjianluo.firmwareanalysisorchestratorjava.orchestration;

import com.github.xingjianluo.firmwareanalysisorchestratorjava.model.Verdict;

/**
 * 历史结论复现结果：用任务固化的规则版本重新求值，与已固化结论对比。
 *
 * @param taskId               任务标识
 * @param ruleVersion          固化的规则版本
 * @param storedVerdict        任务完成时固化的结论
 * @param reproducedVerdict    用同版本规则重新计算的结论
 * @param consistent           两者是否一致
 */
public record ReproductionResult(String taskId,
                                 int ruleVersion,
                                 Verdict storedVerdict,
                                 Verdict reproducedVerdict,
                                 boolean consistent) {
}
