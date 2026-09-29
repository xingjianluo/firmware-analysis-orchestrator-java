package com.github.xingjianluo.firmwareanalysisorchestratorjava.model;

/**
 * 分析任务的生命周期状态。
 * <p>合法流转：
 * PENDING -> QUEUED -> RUNNING -> COMPLETED / FAILED / PARTIAL_FAILED；
 * 任意非终态 -> CANCELLED。状态只允许向前流转，不允许回退。
 */
public enum TaskStatus {
    PENDING,
    QUEUED,
    RUNNING,
    COMPLETED,
    FAILED,
    PARTIAL_FAILED,
    CANCELLED;

    /** 终态任务的任何状态写入都会被拒绝。 */
    public boolean isTerminal() {
        return this == COMPLETED || this == FAILED || this == PARTIAL_FAILED || this == CANCELLED;
    }

    /**
     * 判断从当前状态流转到目标状态是否合法。
     * 规则：非终态 -> CANCELLED 总是合法；其余只允许
     * PENDING -> QUEUED -> RUNNING -> COMPLETED/FAILED/PARTIAL_FAILED。
     */
    public boolean canTransitionTo(TaskStatus target) {
        if (target == null || this.isTerminal()) {
            return false;
        }
        if (target == CANCELLED) {
            return true;
        }
        return switch (this) {
            case PENDING -> target == QUEUED;
            case QUEUED -> target == RUNNING;
            case RUNNING -> target == COMPLETED || target == FAILED || target == PARTIAL_FAILED;
            default -> false;
        };
    }
}
