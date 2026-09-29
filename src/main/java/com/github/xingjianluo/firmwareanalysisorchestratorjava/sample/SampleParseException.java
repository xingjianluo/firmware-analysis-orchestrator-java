package com.github.xingjianluo.firmwareanalysisorchestratorjava.sample;

import com.github.xingjianluo.firmwareanalysisorchestratorjava.model.FailureCode;

/**
 * 样本解析内部异常。该异常只在解析层内部使用，
 * 最终会被映射为 {@link FailureCode}，绝不直接透出给调用方。
 */
class SampleParseException extends RuntimeException {

    private final FailureCode failureCode;

    SampleParseException(FailureCode failureCode, String message) {
        super(message);
        this.failureCode = failureCode;
    }

    SampleParseException(FailureCode failureCode, String message, Throwable cause) {
        super(message, cause);
        this.failureCode = failureCode;
    }

    FailureCode failureCode() {
        return failureCode;
    }
}
