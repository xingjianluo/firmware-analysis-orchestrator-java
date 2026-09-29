package com.github.xingjianluo.firmwareanalysisorchestratorjava.service;

import com.github.xingjianluo.firmwareanalysisorchestratorjava.model.RuleFinding;
import com.github.xingjianluo.firmwareanalysisorchestratorjava.model.TaskConclusion;

import java.util.List;

/**
 * 历史任务按提交时的规则快照复跑的结果。
 * originalConclusion 与 replayConclusion 必须一致，findings 必须可复现。
 */
public record ReplayReport(
        String taskId,
        long ruleSetVersion,
        TaskConclusion originalConclusion,
        TaskConclusion replayConclusion,
        boolean reproducible,
        List<RuleFinding> replayFindings
) {
}
