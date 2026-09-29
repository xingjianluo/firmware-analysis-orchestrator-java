package com.github.xingjianluo.firmwareanalysisorchestratorjava.model;

/** 固件解析后的逻辑分段（规则只面向分段匹配，不直接接触底层字节解析）。 */
public record Segment(String type, String content) {
}
