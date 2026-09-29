package com.github.xingjianluo.firmwareanalysisorchestratorjava.model;

/**
 * 可区分、可直接透出给调用方的失败原因。
 * 不允许把底层解析异常的堆栈/类名直接暴露给调用方。
 */
public enum FailureReason {
    EMPTY_SAMPLE("EMPTY_SAMPLE", "样本内容为空"),
    UNSUPPORTED_FORMAT("UNSUPPORTED_FORMAT", "不支持的文件格式，未识别到已知文件魔数"),
    EXTENSION_MISMATCH("EXTENSION_MISMATCH", "文件扩展名与真实格式不符"),
    TRUNCATED_CONTENT("TRUNCATED_CONTENT", "样本内容被截断，长度短于头部声明"),
    DECLARED_LENGTH_MISMATCH("DECLARED_LENGTH_MISMATCH", "头部声明的长度与实际内容长度不符"),
    CORRUPT_SAMPLE("CORRUPT_SAMPLE", "样本结构损坏，无法完成解析"),
    TASK_NOT_FOUND("TASK_NOT_FOUND", "任务不存在"),
    INTERNAL_ERROR("INTERNAL_ERROR", "分析服务内部错误");

    private final String code;
    private final String userMessage;

    FailureReason(String code, String userMessage) {
        this.code = code;
        this.userMessage = userMessage;
    }

    public String getCode() {
        return code;
    }

    public String getUserMessage() {
        return userMessage;
    }
}
