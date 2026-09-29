package com.github.xingjianluo.firmwareanalysisorchestratorjava.web;

import com.github.xingjianluo.firmwareanalysisorchestratorjava.model.AnalysisTask;
import com.github.xingjianluo.firmwareanalysisorchestratorjava.model.FailureReason;
import com.github.xingjianluo.firmwareanalysisorchestratorjava.model.RuleFinding;
import com.github.xingjianluo.firmwareanalysisorchestratorjava.model.TaskConclusion;
import com.github.xingjianluo.firmwareanalysisorchestratorjava.model.TaskStatus;

import java.time.Instant;
import java.util.List;

/** 任务对外只读视图：含状态、结论、可区分失败原因与每条规则的判定依据。 */
public record TaskView(
        String taskId,
        String fileName,
        TaskStatus status,
        TaskConclusion conclusion,
        String failureCode,
        String failureMessage,
        long ruleSetVersion,
        int submitCount,
        boolean deduplicated,
        Instant createdAt,
        Instant updatedAt,
        List<RuleFinding> findings
) {
    public static TaskView from(AnalysisTask task) {
        String code = null;
        String message = null;
        FailureReason reason = task.getFailureReason();
        if (reason != null) {
            code = reason.getCode();
            message = task.getFailureMessage();
        }
        return new TaskView(
                task.getTaskId(),
                task.getFileName(),
                task.getStatus(),
                task.getConclusion(),
                code,
                message,
                task.getRuleSetVersion(),
                task.getSubmitCount(),
                task.getSubmitCount() > 1,
                task.getCreatedAt(),
                task.getUpdatedAt(),
                task.getFindings());
    }
}
