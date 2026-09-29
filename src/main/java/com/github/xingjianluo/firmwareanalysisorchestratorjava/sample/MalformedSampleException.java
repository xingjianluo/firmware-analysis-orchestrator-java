package com.github.xingjianluo.firmwareanalysisorchestratorjava.sample;

import com.github.xingjianluo.firmwareanalysisorchestratorjava.model.FailureReason;

/** 样本解析失败的内部异常，携带可透出给调用方的失败原因（绝不透传底层异常）。 */
public class MalformedSampleException extends RuntimeException {

    private final FailureReason reason;

    public MalformedSampleException(FailureReason reason, String detail) {
        super(detail);
        this.reason = reason;
    }

    public MalformedSampleException(FailureReason reason, String detail, Throwable cause) {
        super(detail, cause);
        this.reason = reason;
    }

    public FailureReason getReason() {
        return reason;
    }
}
