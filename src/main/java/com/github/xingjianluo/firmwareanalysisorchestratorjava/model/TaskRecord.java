package com.github.xingjianluo.firmwareanalysisorchestratorjava.model;

/**
 * 任务实体。所有状态变更都在对象锁内完成并做流转校验：
 * 终态任务拒绝任何写入，状态不允许回退，取消后写入的结果会被丢弃。
 */
public class TaskRecord {

    private final String taskId;
    private final SampleDescriptor sample;
    private final long createdAtMillis;

    private TaskStatus status;
    private long updatedAtMillis;
    private ParseOutcome parseOutcome;
    private AnalysisConclusion conclusion;
    private FailureCode failureCode;
    private String failureMessage;
    private Integer ruleVersion;

    public TaskRecord(String taskId, SampleDescriptor sample) {
        this.taskId = taskId;
        this.sample = sample;
        this.status = TaskStatus.PENDING;
        long now = System.currentTimeMillis();
        this.createdAtMillis = now;
        this.updatedAtMillis = now;
    }

    public String getTaskId() {
        return taskId;
    }

    public SampleDescriptor getSample() {
        return sample;
    }

    public long getCreatedAtMillis() {
        return createdAtMillis;
    }

    public synchronized long getUpdatedAtMillis() {
        return updatedAtMillis;
    }

    public synchronized TaskStatus getStatus() {
        return status;
    }

    public synchronized ParseOutcome getParseOutcome() {
        return parseOutcome;
    }

    public synchronized AnalysisConclusion getConclusion() {
        return conclusion;
    }

    public synchronized FailureCode getFailureCode() {
        return failureCode;
    }

    public synchronized String getFailureMessage() {
        return failureMessage;
    }

    public synchronized Integer getRuleVersion() {
        return ruleVersion;
    }

    /**
     * 尝试把状态流转到 target。只有合法且未处于终态时成功，
     * 状态不可能回退。
     *
     * @return true 表示流转生效
     */
    public synchronized boolean transitionTo(TaskStatus target) {
        if (!status.canTransitionTo(target)) {
            return false;
        }
        status = target;
        updatedAtMillis = System.currentTimeMillis();
        return true;
    }

    /**
     * 原子地进入终态并写入结果。若任务已处于终态（例如已取消），
     * 整个写入被拒绝，保证已取消任务不再写回任何结果。
     */
    public synchronized boolean complete(ParseOutcome parseOutcome,
                                         AnalysisConclusion conclusion,
                                         TaskStatus terminalStatus,
                                         FailureCode failureCode,
                                         String failureMessage,
                                         Integer ruleVersion) {
        if (status.isTerminal()) {
            return false;
        }
        if (!status.canTransitionTo(terminalStatus)) {
            return false;
        }
        this.parseOutcome = parseOutcome;
        this.conclusion = conclusion;
        this.failureCode = failureCode;
        this.failureMessage = failureMessage;
        this.ruleVersion = ruleVersion;
        this.status = terminalStatus;
        this.updatedAtMillis = System.currentTimeMillis();
        return true;
    }

    /**
     * 取消任务。只有非终态任务可以被取消，取消同样是一次性流转。
     */
    public synchronized boolean cancel() {
        if (status.isTerminal()) {
            return false;
        }
        status = TaskStatus.CANCELLED;
        this.updatedAtMillis = System.currentTimeMillis();
        return true;
    }

    /** 生成不可变快照，供查询接口返回。 */
    public synchronized TaskSnapshot toSnapshot() {
        return new TaskSnapshot(taskId, status, parseOutcome, conclusion, failureCode,
                failureMessage, ruleVersion, createdAtMillis, updatedAtMillis, true, false);
    }
}
