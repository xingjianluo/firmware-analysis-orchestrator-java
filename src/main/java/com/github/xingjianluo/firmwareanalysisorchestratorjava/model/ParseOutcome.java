package com.github.xingjianluo.firmwareanalysisorchestratorjava.model;

import java.util.Map;

/**
 * 样本解析结果。解析失败时 {@code success=false}，并带稳定的失败原因码。
 *
 * @param success           是否解析成功
 * @param detectedFormat    探测到的真实格式标识（失败时为 "UNKNOWN"）
 * @param metadata          解析出的键值元数据
 * @param failureCode       失败原因码（成功时为 null）
 * @param failureMessage    可读的失败说明（成功时为 null），不含底层堆栈
 */
public record ParseOutcome(boolean success,
                           String detectedFormat,
                           Map<String, String> metadata,
                           FailureCode failureCode,
                           String failureMessage) {

    private static final String UNKNOWN = "UNKNOWN";

    public ParseOutcome {
        detectedFormat = detectedFormat == null ? UNKNOWN : detectedFormat;
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }

    public static ParseOutcome success(String detectedFormat, Map<String, String> metadata) {
        return new ParseOutcome(true, detectedFormat, metadata, null, null);
    }

    public static ParseOutcome failure(FailureCode code, String message) {
        return new ParseOutcome(false, UNKNOWN, Map.of(), code, message);
    }
}
