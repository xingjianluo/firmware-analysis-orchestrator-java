package com.github.xingjianluo.firmwareanalysisorchestratorjava.model;

/**
 * 任务的不可变查询快照。
 *
 * @param accepted  本次提交是否被接受（重复提交时为 false，但仍返回既有快照）
 * @param duplicate 是否因任务标识重复而被去重
 */
public record TaskSnapshot(String taskId,
                           TaskStatus status,
                           ParseOutcome parseOutcome,
                           AnalysisConclusion conclusion,
                           FailureCode failureCode,
                           String failureMessage,
                           Integer ruleVersion,
                           long createdAtMillis,
                           long updatedAtMillis,
                           boolean accepted,
                           boolean duplicate) {

    /** 基于既有任务生成快照；duplicate=true 时 accepted=false。 */
    public static TaskSnapshot existing(TaskRecord record, boolean duplicate) {
        TaskSnapshot base = record.toSnapshot();
        return new TaskSnapshot(base.taskId(), base.status(), base.parseOutcome(),
                base.conclusion(), base.failureCode(), base.failureMessage(),
                base.ruleVersion(), base.createdAtMillis(), base.updatedAtMillis(),
                !duplicate, duplicate);
    }
}
