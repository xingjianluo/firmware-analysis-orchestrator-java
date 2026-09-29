package com.github.xingjianluo.firmwareanalysisorchestratorjava.model;

/**
 * 规则汇总后的总体结论。
 * <ul>
 *   <li>{@link #PASS}：所有规则命中（或没有规则），无冲突无错误。</li>
 *   <li>{@link #FAIL}：存在规则缺失（未命中）。</li>
 *   <li>{@link #INCONCLUSIVE}：同组规则互相冲突，无法给出确定结论。</li>
 *   <li>{@link #PARTIAL}：有规则执行失败，任务同时会被标记为 PARTIAL_FAILED。</li>
 * </ul>
 */
public enum Verdict {
    PASS,
    FAIL,
    INCONCLUSIVE,
    PARTIAL
}
