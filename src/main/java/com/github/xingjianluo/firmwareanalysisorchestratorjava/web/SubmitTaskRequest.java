package com.github.xingjianluo.firmwareanalysisorchestratorjava.web;

/**
 * 提交任务请求。contentBase64 为样本字节的 Base64 文本。
 */
public record SubmitTaskRequest(String taskId, String declaredExtension, String contentBase64) {
}
