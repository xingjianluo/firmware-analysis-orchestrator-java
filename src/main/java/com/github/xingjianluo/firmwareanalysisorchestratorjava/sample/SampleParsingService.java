package com.github.xingjianluo.firmwareanalysisorchestratorjava.sample;

import com.github.xingjianluo.firmwareanalysisorchestratorjava.model.FailureCode;
import com.github.xingjianluo.firmwareanalysisorchestratorjava.model.ParseOutcome;
import com.github.xingjianluo.firmwareanalysisorchestratorjava.model.SampleDescriptor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 样本格式探测与解析入口：
 * 扩展名与真实格式不符会在元数据中标注（以真实魔数为准）；
 * 不支持的格式与损坏样本返回不同的失败原因码；
 * 任何底层异常都在此收敛，不向调用方透出原始解析异常。
 */
@Service
public class SampleParsingService {

    private static final Logger log = LoggerFactory.getLogger(SampleParsingService.class);

    private final List<SampleParser> parsers;

    @Autowired
    public SampleParsingService(List<SampleParser> parsers) {
        this.parsers = List.copyOf(parsers);
    }

    /** 测试/独立使用时的内置解析器组合。 */
    public static SampleParsingService withBuiltInParsers() {
        return new SampleParsingService(List.of(new Fw1Parser(), new Arc1Parser()));
    }

    public ParseOutcome parse(SampleDescriptor sample) {
        if (sample == null) {
            return ParseOutcome.failure(FailureCode.EMPTY_SAMPLE, "样本为空");
        }
        byte[] content = sample.content();
        if (content.length == 0) {
            return ParseOutcome.failure(FailureCode.EMPTY_SAMPLE, "样本内容为空，无法探测格式");
        }
        for (SampleParser parser : parsers) {
            if (!parser.supports(content)) {
                continue;
            }
            try {
                ParseOutcome outcome = parser.parse(sample);
                log.debug("样本解析成功 format={} declaredExt={}",
                        outcome.detectedFormat(), sample.declaredExtension());
                return outcome;
            } catch (SampleParseException e) {
                // 预期的映射异常：带稳定原因码
                log.warn("样本解析失败 code={} reason={}", e.failureCode(), e.getMessage());
                return ParseOutcome.failure(e.failureCode(), e.getMessage());
            } catch (RuntimeException e) {
                // 非预期底层异常：收敛成 CORRUPT_PAYLOAD，绝不直接透出
                log.error("样本解析出现未预期异常，收敛为 CORRUPT_PAYLOAD", e);
                return ParseOutcome.failure(FailureCode.CORRUPT_PAYLOAD,
                        "样本解析失败：内容损坏或不符合 " + parser.formatId() + " 布局");
            }
        }
        return unsupported("未识别到受支持的固件/归档魔数，声明扩展名："
                + sample.declaredExtension());
    }

    static ParseOutcome unsupported(String detail) {
        return ParseOutcome.failure(FailureCode.UNSUPPORTED_FORMAT, detail);
    }
}
