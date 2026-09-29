package com.github.xingjianluo.firmwareanalysisorchestratorjava.sample;

import com.github.xingjianluo.firmwareanalysisorchestratorjava.model.FailureCode;
import com.github.xingjianluo.firmwareanalysisorchestratorjava.model.ParseOutcome;
import com.github.xingjianluo.firmwareanalysisorchestratorjava.model.SampleDescriptor;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * ARC1 归档容器格式解析器（本地构造样本）。
 * 布局：魔数 "ARC1"(4) + entryCount(2, 大端) + declaredTotalLength(4, 大端) + entries。
 * declaredTotalLength 为整个文件的声明长度；每个 entry：
 * nameLength(2) + name(ASCII) + valueLength(4) + value(ASCII)。
 * <ul>
 *   <li>entry 区域读不完 -> TRUNCATED_SAMPLE；</li>
 *   <li>总长度声明与实际不符 -> HEADER_LENGTH_MISMATCH；</li>
 *   <li>空名/空值等结构损坏 -> CORRUPT_PAYLOAD。</li>
 * </ul>
 */
@Component
class Arc1Parser implements SampleParser {

    static final String FORMAT_ID = "ARC1";
    static final byte[] MAGIC = {'A', 'R', 'C', '1'};
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
                    "ARC1 样本头部不完整：实际 " + content.length + " 字节，至少需要 " + HEADER_SIZE);
        }
        int entryCount = ((content[4] & 0xFF) << 8) | (content[5] & 0xFF);
        int declaredTotal = readInt(content, 6);
        if (declaredTotal < 0) {
            throw new SampleParseException(FailureCode.HEADER_LENGTH_MISMATCH,
                    "ARC1 头部声明的总长度非法：" + declaredTotal);
        }
        if (content.length < declaredTotal) {
            throw new SampleParseException(FailureCode.TRUNCATED_SAMPLE,
                    "ARC1 样本内容被截断：声明总长 " + declaredTotal + " 字节，实际仅 " + content.length + " 字节");
        }
        if (content.length > declaredTotal) {
            throw new SampleParseException(FailureCode.HEADER_LENGTH_MISMATCH,
                    "ARC1 头部声明长度与实际不符：声明总长 " + declaredTotal
                            + " 字节，实际有 " + content.length + " 字节");
        }

        Map<String, String> metadata = new LinkedHashMap<>();
        int offset = HEADER_SIZE;
        for (int i = 0; i < entryCount; i++) {
            if (offset + 2 > content.length) {
                throw truncatedEntry();
            }
            int nameLength = ((content[offset] & 0xFF) << 8) | (content[offset + 1] & 0xFF);
            offset += 2;
            if (nameLength <= 0 || offset + nameLength > content.length) {
                throw truncatedEntry();
            }
            String name = new String(content, offset, nameLength, StandardCharsets.US_ASCII).trim();
            offset += nameLength;
            if (offset + 4 > content.length) {
                throw truncatedEntry();
            }
            int valueLength = readInt(content, offset);
            offset += 4;
            if (valueLength < 0 || offset + valueLength > content.length) {
                throw truncatedEntry();
            }
            String value = new String(content, offset, valueLength, StandardCharsets.US_ASCII).trim();
            offset += valueLength;
            if (name.isEmpty()) {
                throw new SampleParseException(FailureCode.CORRUPT_PAYLOAD,
                        "ARC1 第 " + (i + 1) + " 个 entry 的名称为空");
            }
            if (value.isEmpty()) {
                throw new SampleParseException(FailureCode.CORRUPT_PAYLOAD,
                        "ARC1 entry '" + name + "' 的值为空");
            }
            metadata.put(name, value);
        }
        if (offset != content.length) {
            throw new SampleParseException(FailureCode.CORRUPT_PAYLOAD,
                    "ARC1 entry 区域解析后存在无法识别的剩余字节：" + (content.length - offset));
        }

        metadata.put("format", FORMAT_ID);
        metadata.put("entryCount", Integer.toString(entryCount));
        metadata.put("rawContentLength", Integer.toString(content.length));

        String declaredExtension = sample.declaredExtension();
        if (declaredExtension != null && !declaredExtension.isBlank()
                && !"arc".equalsIgnoreCase(declaredExtension)
                && !"arc1".equalsIgnoreCase(declaredExtension)) {
            metadata.put("extensionMismatch",
                    "声明扩展名 ." + declaredExtension + " 与真实格式 ARC1 不符，已按真实格式解析");
        }
        return ParseOutcome.success(FORMAT_ID, metadata);
    }

    private static SampleParseException truncatedEntry() {
        return new SampleParseException(FailureCode.TRUNCATED_SAMPLE,
                "ARC1 entry 区域被截断，无法读取完整的名称/值长度");
    }

    private static int readInt(byte[] content, int offset) {
        return ((content[offset] & 0xFF) << 24) | ((content[offset + 1] & 0xFF) << 16)
                | ((content[offset + 2] & 0xFF) << 8) | (content[offset + 3] & 0xFF);
    }
}
