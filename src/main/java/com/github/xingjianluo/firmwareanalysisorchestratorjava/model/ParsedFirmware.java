package com.github.xingjianluo.firmwareanalysisorchestratorjava.model;

import java.util.List;

/** 解析完成的固件：格式 + 分段视图。 */
public record ParsedFirmware(FormatType format, List<Segment> segments) {
}
