package com.github.xingjianluo.firmwareanalysisorchestratorjava.model;

/** 一次提交的原始样本：文件名（含扩展名）+ 原始字节。 */
public record SampleSubmission(String fileName, byte[] content) {
}
