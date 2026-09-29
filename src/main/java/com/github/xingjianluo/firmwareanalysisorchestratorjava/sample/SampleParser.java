package com.github.xingjianluo.firmwareanalysisorchestratorjava.sample;

import com.github.xingjianluo.firmwareanalysisorchestratorjava.model.ParsedFirmware;
import com.github.xingjianluo.firmwareanalysisorchestratorjava.model.SampleSubmission;

/**
 * 将样本解析为分段视图。
 * 不支持/损坏的情况抛 {@link MalformedSampleException}，底层异常不允许透出。
 */
public interface SampleParser {
    ParsedFirmware parse(SampleSubmission submission);
}
