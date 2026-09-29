package com.github.xingjianluo.firmwareanalysisorchestratorjava.sample;

import com.github.xingjianluo.firmwareanalysisorchestratorjava.model.FailureCode;
import com.github.xingjianluo.firmwareanalysisorchestratorjava.model.ParseOutcome;
import com.github.xingjianluo.firmwareanalysisorchestratorjava.model.SampleDescriptor;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * FW1 固件描述格式解析器（本地构造样本）。
 * 布局：魔数 "FW01"(4) + version(2, 大端) + declaredLength(4, 大端)
 *       + payload(ASCII，键值对 "k=v"，以 ';' 分隔，允许以 ';' 结尾)。
 * declaredLength 为 payload 的声明长度：
 * <ul>
 *   <li>实际字节比声明少（连头部都不完整/尾部缺失）-> TRUNCATED_SAMPLE；</li>
 *   <li>实际字节比声明多（头部声明与实际不符）-> HEADER_LENGTH_MISMATCH；</li>
 *   <li>键值对无法解析 -> CORRUPT_PAYLOAD。</li>
 * </ul>
 */
@Component
class Fw1Parser implements SampleParser {

    static final String FORMAT_ID = "FW1";
    static final byte[] MAGIC = {'F', 'W', '0', '1'};
    static final int HEADER_SIZE = 10;

    @Override
    public String formatId() {
        return FORMAT_ID;
    }

    @Override
    public boolean supports(byte[] content) {
        if (content == null || content.length < MAGIC.length) {
            return false;
        }
        for (int i = 0; i < MAGIC.length; i++) {
            if (content[i] != MAGIC[i]) {
                return false;
            }
        }
        return true;
    }

    @Override
    public ParseOutcome parse(SampleDescriptor sample) {
        byte[] content = sample.content();
        if (content.length < HEADER_SIZE) {
            throw new SampleParseException(FailureCode.TRUNCATED_SAMPLE,
                    "FW1 样本头部不完整：实际 " + content.length + " 字节，至少需要 " + HEADER_SIZE);
        }
        int version = ((content[4] & 0xFF) << 8) | (content[5] & 0xFF);
        int declaredLength = ((content[6] & 0xFF) << 24) | ((content[7] & 0xFF) << 16)
                | ((content[8] & 0xFF) << 8) | (content[9] & 0xFF);
        if (declaredLength < 0) {
            throw new SampleParseException(FailureCode.HEADER_LENGTH_MISMATCH,
                    "FW1 头部声明的 payload 长度非法：" + declaredLength);
        }

        int actualLength = content.length - HEADER_SIZE;
        if (actualLength < declaredLength) {
            throw new SampleParseException(FailureCode.TRUNCATED_SAMPLE,
                    "FW1 样本内容被截断：声明 payload " + declaredLength + " 字节，实际仅 " + actualLength + " 字节");
        }
        if (actualLength > declaredLength) {
            throw new SampleParseException(FailureCode.HEADER_LENGTH_MISMATCH,
                    "FW1 头部声明长度与实际不符：声明 payload " + declaredLength
                            + " 字节，实际有 " + actualLength + " 字节");
        }

        String payload = new String(content, HEADER_SIZE, declaredLength, StandardCharsets.US_ASCII);
        Map<String, String> metadata = parsePayload(payload);
        metadata.put("format", FORMAT_ID);
        metadata.put("version", Integer.toString(version));
        metadata.put("payloadLength", Integer.toString(declaredLength));
        metadata.put("rawContentLength", Integer.toString(content.length));

        String declaredExtension = sample.declaredExtension();
        if (declaredExtension != null && !declaredExtension.isBlank()
                && !"fw1".equalsIgnoreCase(declaredExtension)) {
            metadata.put("extensionMismatch",
                    "声明扩展名 ." + declaredExtension + " 与真实格式 FW1 不符，已按真实格式解析");
        }
        return ParseOutcome.success(FORMAT_ID, metadata);
    }

    private static Map<String, String> parsePayload(String payload) {
        Map<String, String> metadata = new LinkedHashMap<>();
        String trimmed = payload.endsWith(";") ? payload.substring(0, payload.length() - 1) : payload;
        if (trimmed.isEmpty()) {
            return metadata;
        }
        for (String pair : trimmed.split(";", -1)) {
            int eq = pair.indexOf('=');
            if (eq <= 0 || eq == pair.length() - 1) {
                throw new SampleParseException(FailureCode.CORRUPT_PAYLOAD,
                        "FW1 payload 含无法解析的键值对：'" + pair + "'");
            }
            String key = pair.substring(0, eq).trim();
            String value = pair.substring(eq + 1).trim();
            if (key.isEmpty() || value.isEmpty()) {
                throw new SampleParseException(FailureCode.CORRUPT_PAYLOAD,
                        "FW1 payload 含空键或空值：'" + pair + "'");
            }
            metadata.put(key, value);
        }
        return metadata;
    }
}
