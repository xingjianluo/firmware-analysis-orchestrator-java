package com.github.xingjianluo.firmwareanalysisorchestratorjava.tests;

import com.github.xingjianluo.firmwareanalysisorchestratorjava.model.FormatType;
import com.github.xingjianluo.firmwareanalysisorchestratorjava.model.ParsedFirmware;
import com.github.xingjianluo.firmwareanalysisorchestratorjava.model.SampleSubmission;
import com.github.xingjianluo.firmwareanalysisorchestratorjava.sample.DefaultSampleParser;
import com.github.xingjianluo.firmwareanalysisorchestratorjava.sample.MagicFormatDetector;
import com.github.xingjianluo.firmwareanalysisorchestratorjava.sample.MalformedSampleException;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static com.github.xingjianluo.firmwareanalysisorchestratorjava.model.FailureReason.CORRUPT_SAMPLE;
import static com.github.xingjianluo.firmwareanalysisorchestratorjava.model.FailureReason.DECLARED_LENGTH_MISMATCH;
import static com.github.xingjianluo.firmwareanalysisorchestratorjava.model.FailureReason.EMPTY_SAMPLE;
import static com.github.xingjianluo.firmwareanalysisorchestratorjava.model.FailureReason.EXTENSION_MISMATCH;
import static com.github.xingjianluo.firmwareanalysisorchestratorjava.model.FailureReason.TRUNCATED_CONTENT;
import static com.github.xingjianluo.firmwareanalysisorchestratorjava.model.FailureReason.UNSUPPORTED_FORMAT;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 格式探测与解析容错：失败原因必须可区分，且不允许透出底层异常。 */
class DefaultSampleParserTest {

    private static final Logger log = LoggerFactory.getLogger(DefaultSampleParserTest.class);

    private final DefaultSampleParser parser = new DefaultSampleParser(new MagicFormatDetector());

    private SampleSubmission sub(String name, byte[] data) {
        return new SampleSubmission(name, data);
    }

    private void assertReason(String scenario, byte[] data, String name,
                              com.github.xingjianluo.firmwareanalysisorchestratorjava.model.FailureReason expected) {
        MalformedSampleException ex = assertThrows(MalformedSampleException.class,
                () -> parser.parse(sub(name, data)),
                scenario + " 应解析失败");
        assertEquals(expected, ex.getReason(), scenario + " 失败原因必须可区分");
        // 透出给调用方的信息只能是稳定错误码/文案，绝不能是底层异常类名
        String surfaced = ex.getReason().getUserMessage();
        assertTrue(surfaced != null && !surfaced.isBlank(), scenario + " 必须有可读错误信息");
        log.info("场景={} 错误来源=样本解析 reason={} 透出信息={}", scenario, expected.getCode(), surfaced);
    }

    @Test
    void parsesValidFwimg() {
        ParsedFirmware fw = parser.parse(sub("device.fwimg", Samples.cleanFirmware()));
        assertEquals(FormatType.FWIMG, fw.format());
        assertTrue(fw.segments().stream().anyMatch(s -> s.type().equals("VERSION")));
        log.info("场景=合法FWIMG 判定依据=魔数FWIMG 解析分段数={}", fw.segments().size());
    }

    @Test
    void emptySampleIsRejected() {
        assertReason("空样本", new byte[0], "x.fwimg", EMPTY_SAMPLE);
    }

    @Test
    void unsupportedFormatIsRejected() {
        assertReason("未知魔数", Samples.unknownBytes(), "x.bin", UNSUPPORTED_FORMAT);
    }

    @Test
    void extensionMismatchIsDetected() {
        assertReason("扩展名与真实格式不符", Samples.cleanFirmware(), "device.elf", EXTENSION_MISMATCH);
    }

    @Test
    void truncatedContentIsDetected() {
        byte[] valid = Samples.cleanFirmware();
        assertReason("尾部截断", Samples.truncated(valid, 6), "t.fwimg", TRUNCATED_CONTENT);
        assertReason("短于头部", Samples.tooShortHeader(), "t.fwimg", TRUNCATED_CONTENT);
    }

    @Test
    void declaredLengthMismatchIsDetected() {
        assertReason("声明长度过大", Samples.declaredLengthMismatch(Samples.cleanFirmware()),
                "d.fwimg", DECLARED_LENGTH_MISMATCH);
    }

    @Test
    void corruptSegmentIsDetected() {
        assertReason("非法分段类型", Samples.corruptSegment(Samples.cleanFirmware()),
                "c.fwimg", CORRUPT_SAMPLE);
    }

    @Test
    void elfAndZipProduceStringSegments() {
        ParsedFirmware elf = parser.parse(sub("blob.elf", Samples.elfWithText()));
        assertEquals(FormatType.ELF, elf.format());
        assertTrue(elf.segments().get(0).content().contains("password=rootpw"));

        ParsedFirmware zip = parser.parse(sub("blob.zip", Samples.zipLike()));
        assertEquals(FormatType.ZIP, zip.format());
        assertTrue(zip.segments().get(0).content().contains("1.0.0"));
        log.info("场景=ELF/ZIP字符串抽取 判定依据=魔数 错误来源=-");
    }

    @Test
    void underlyingExceptionIsNotSurfaced() {
        // 即使传入极端畸形数据，也只能拿到 MalformedSampleException，不会冒出 IndexOutOfBounds 等
        byte[] nasty = new byte[]{'F', 'W', 'I', 'M', 'G', 1, 1, 0, 0, 0, 0, 5,
                0, 0, 0, 0, 0, 0, 0, 0};
        MalformedSampleException ex = assertThrows(MalformedSampleException.class,
                () -> parser.parse(sub("nasty.fwimg", nasty)));
        log.info("场景=极端畸形 错误来源=内部收敛 reason={} 底层cause={}",
                ex.getReason().getCode(),
                ex.getCause() == null ? "-" : ex.getCause().getClass().getSimpleName());
    }
}
