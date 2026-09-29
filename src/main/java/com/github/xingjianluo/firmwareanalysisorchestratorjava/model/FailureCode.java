package com.github.xingjianluo.firmwareanalysisorchestratorjava.model;

/**
 * 可区分的失败原因码。底层解析异常永远不会直接透出给调用方，
 * 只会映射为这里的稳定原因码加上可读消息。
 */
public enum FailureCode {
    // ---- 样本解析类 ----
    UNSUPPORTED_FORMAT,
    TRUNCATED_SAMPLE,
    HEADER_LENGTH_MISMATCH,
    CORRUPT_PAYLOAD,
    EMPTY_SAMPLE,

    // ---- 任务类 ----
    TASK_ALREADY_CANCELLED,
    RULE_EXECUTION_ERROR,

    // ---- 输入校验类 ----
    INVALID_REQUEST
}
