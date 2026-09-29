package com.github.xingjianluo.firmwareanalysisorchestratorjava.rule;

import java.util.Map;

/**
 * 规则执行上下文：真实格式、解析元数据、原始字节长度。
 */
public record RuleEvaluationContext(String format,
                                    Map<String, String> metadata,
                                    int contentLength) {
}
