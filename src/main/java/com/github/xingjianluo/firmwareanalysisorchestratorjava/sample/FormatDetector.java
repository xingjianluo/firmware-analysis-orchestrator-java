package com.github.xingjianluo.firmwareanalysisorchestratorjava.sample;

import com.github.xingjianluo.firmwareanalysisorchestratorjava.model.FormatType;
import com.github.xingjianluo.firmwareanalysisorchestratorjava.model.SampleSubmission;

/** 根据魔数探测样本真实格式。 */
public interface FormatDetector {
    FormatType detect(SampleSubmission submission);
}
