package com.github.xingjianluo.firmwareanalysisorchestratorjava.rule;

import com.github.xingjianluo.firmwareanalysisorchestratorjava.model.ParsedFirmware;
import com.github.xingjianluo.firmwareanalysisorchestratorjava.model.SampleSubmission;

/** 规则执行上下文：原始样本 + 解析结果。 */
public record RuleContext(SampleSubmission submission, ParsedFirmware firmware) {
}
