package com.github.xingjianluo.firmwareanalysisorchestratorjava.web;

/** 提交任务的 HTTP 请求体（content 为 Base64 编码的样本字节）。 */
public class SubmitRequest {
    private String fileName;
    private String contentBase64;
    private String idempotencyKey;

    public String getFileName() { return fileName; }
    public void setFileName(String fileName) { this.fileName = fileName; }
    public String getContentBase64() { return contentBase64; }
    public void setContentBase64(String contentBase64) { this.contentBase64 = contentBase64; }
    public String getIdempotencyKey() { return idempotencyKey; }
    public void setIdempotencyKey(String idempotencyKey) { this.idempotencyKey = idempotencyKey; }
}
