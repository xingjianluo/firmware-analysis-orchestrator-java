package com.github.xingjianluo.firmwareanalysisorchestratorjava.sample;

import com.github.xingjianluo.firmwareanalysisorchestratorjava.model.ParseOutcome;
import com.github.xingjianluo.firmwareanalysisorchestratorjava.model.SampleDescriptor;

/**
 * 单一格式的解析器：负责格式探测与容错解析。
 */
interface SampleParser {

    /** 格式标识，如 "FW1"、"ARC1"。 */
    String formatId();

    /** 是否识别给定字节的魔数（与扩展名无关）。 */
    boolean supports(byte[] content);

    /** 解析样本。任何损坏情形都抛出映射好原因码的 {@link SampleParseException}。 */
    ParseOutcome parse(SampleDescriptor sample);
}
