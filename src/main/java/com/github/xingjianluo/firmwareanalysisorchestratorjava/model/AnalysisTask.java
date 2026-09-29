package com.github.xingjianluo.firmwareanalysisorchestratorjava.model;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 分析任务聚合根（可变实体）。
 *
 * <p>状态流转严格单向，任何回退或重复执行写回都会被拒绝：
 * <pre>
 *   QUEUED -> RUNNING -> COMPLETED
 *                      -> FAILED           （解析失败 / 内部错误）
 *                      -> PARTIAL_FAILED   （规则部分执行失败）
 *                      -> CANCELLED
 *   QUEUED -> CANCELLED
 * </pre>
 * 终态（CANCELLED / COMPLETED / FAILED / PARTIAL_FAILED）不可再变，
 * 因此“已取消任务再写回结果”在状态机层面直接被挡掉。
 */
public class AnalysisTask {

    private static final Map<TaskStatus, Set<TaskStatus>> ALLOWED = Map.of(
            TaskStatus.QUEUED, Set.of(TaskStatus.RUNNING, TaskStatus.CANCELLED),
            TaskStatus.RUNNING, Set.of(TaskStatus.COMPLETED, TaskStatus.FAILED,
                    TaskStatus.PARTIAL_FAILED, TaskStatus.CANCELLED)
    );

    private String taskId;
    private String idempotencyKey;
    private String contentHash;
    private String fileName;
    private byte[] content;
    private TaskStatus status;
    private TaskConclusion conclusion = TaskConclusion.NONE;
    private FailureReason failureReason;
    private String failureMessage;
    private List<RuleFinding> findings = new ArrayList<>();
    private long ruleSetVersion;
    private Instant createdAt;
    private Instant updatedAt;
    private int submitCount;

    /**
     * 显式取消标记：即使规则内部吞掉了线程中断，工作线程在检查点
     * 也能通过该标记感知取消，保证“运行中取消”有确定的落点。
     */
    private volatile boolean cancelRequested;

    public AnalysisTask() {
    }

    /**
     * 尝试把任务从当前状态切换到 next。
     * 只有合法的单向流转才会成功；终态任务、已取消任务的写回都会返回 false。
     */
    public synchronized boolean transitionTo(TaskStatus next) {
        if (next == null || next == status) {
            return false;
        }
        boolean allowed = ALLOWED.getOrDefault(status, Set.of()).contains(next);
        if (!allowed) {
            return false;
        }
        this.status = next;
        touch();
        return true;
    }

    /** 标记进入运行态：只有 QUEUED 可以进入，保证重复提交/重复调度不会重复执行。 */
    public synchronized boolean markRunning() {
        return transitionTo(TaskStatus.RUNNING);
    }

    public synchronized boolean isTerminal() {
        return status == TaskStatus.CANCELLED
                || status == TaskStatus.COMPLETED
                || status == TaskStatus.FAILED
                || status == TaskStatus.PARTIAL_FAILED;
    }

    /** 规则全部跑完后的成功写回；已取消任务写回会被拒绝。 */
    public synchronized boolean completeWith(TaskConclusion result, List<RuleFinding> ruleFindings) {
        if (!transitionTo(TaskStatus.COMPLETED)) {
            return false;
        }
        this.conclusion = result;
        this.findings = new ArrayList<>(ruleFindings);
        return true;
    }

    /** 有规则执行失败时的部分失败写回；结论一律带 PARTIAL 语义，绝不能算通过。 */
    public synchronized boolean partialFailWith(List<RuleFinding> ruleFindings) {
        if (!transitionTo(TaskStatus.PARTIAL_FAILED)) {
            return false;
        }
        this.findings = new ArrayList<>(ruleFindings);
        this.conclusion = TaskConclusion.PARTIAL;
        return true;
    }

    /** 解析/内部失败写回；同样不允许覆盖终态。 */
    public synchronized boolean failWith(FailureReason reason, String message) {
        if (!transitionTo(TaskStatus.FAILED)) {
            return false;
        }
        this.failureReason = reason;
        this.failureMessage = message;
        return true;
    }

    public synchronized void incrementSubmitCount() {
        this.submitCount++;
        touch();
    }

    private void touch() {
        this.updatedAt = Instant.now();
    }

    public String getTaskId() { return taskId; }
    public void setTaskId(String taskId) { this.taskId = taskId; }
    public String getIdempotencyKey() { return idempotencyKey; }
    public void setIdempotencyKey(String idempotencyKey) { this.idempotencyKey = idempotencyKey; }
    public String getContentHash() { return contentHash; }
    public void setContentHash(String contentHash) { this.contentHash = contentHash; }
    public String getFileName() { return fileName; }
    public void setFileName(String fileName) { this.fileName = fileName; }
    public byte[] getContent() { return content; }
    public void setContent(byte[] content) { this.content = content; }
    public synchronized TaskStatus getStatus() { return status; }
    public void setStatus(TaskStatus status) { this.status = status; }
    public synchronized TaskConclusion getConclusion() { return conclusion; }
    public void setConclusion(TaskConclusion conclusion) { this.conclusion = conclusion; }
    public FailureReason getFailureReason() { return failureReason; }
    public void setFailureReason(FailureReason failureReason) { this.failureReason = failureReason; }
    public String getFailureMessage() { return failureMessage; }
    public void setFailureMessage(String failureMessage) { this.failureMessage = failureMessage; }
    public synchronized List<RuleFinding> getFindings() { return new ArrayList<>(findings); }
    public void setFindings(List<RuleFinding> findings) { this.findings = findings; }
    public long getRuleSetVersion() { return ruleSetVersion; }
    public void setRuleSetVersion(long ruleSetVersion) { this.ruleSetVersion = ruleSetVersion; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
    public int getSubmitCount() { return submitCount; }
    public void setSubmitCount(int submitCount) { this.submitCount = submitCount; }
    public boolean isCancelRequested() { return cancelRequested; }
    public void setCancelRequested(boolean cancelRequested) { this.cancelRequested = cancelRequested; }
}
