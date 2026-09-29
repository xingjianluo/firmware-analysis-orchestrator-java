package com.github.xingjianluo.firmwareanalysisorchestratorjava.model;

/** 分析任务生命周期状态。 */
public enum TaskStatus {
    QUEUED,
    RUNNING,
    CANCELLED,
    COMPLETED,
    FAILED,
    PARTIAL_FAILED
}
