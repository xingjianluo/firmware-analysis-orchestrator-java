package com.github.xingjianluo.firmwareanalysisorchestratorjava.model;

/**
 * 调用方提交的样本描述：声明的扩展名 + 原始字节。
 *
 * @param declaredExtension 声明的扩展名（可能与真实格式不符，允许为空）
 * @param content           样本原始字节
 */
public record SampleDescriptor(String declaredExtension, byte[] content) {

    public SampleDescriptor {
        content = content == null ? new byte[0] : content.clone();
    }

    @Override
    public byte[] content() {
        return content.clone();
    }
}
