package com.github.xingjianluo.firmwareanalysisorchestratorjava.tests;

import com.github.xingjianluo.firmwareanalysisorchestratorjava.model.FailureCode;
import com.github.xingjianluo.firmwareanalysisorchestratorjava.model.ParseOutcome;
import com.github.xingjianluo.firmwareanalysisorchestratorjava.model.SampleDescriptor;
import com.github.xingjianluo.firmwareanalysisorchestratorjava.sample.SampleParsingService;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 格式探测与解析容错：扩展名不符、截断、头部长度不符、不支持格式、损坏样本。
 */
class SampleParsingServiceTest {

    private static final Logger log = LoggerFactory.getLogger(SampleParsingServiceTest.class);

    private final SampleParsingService service = SampleParsingService.withBuiltInParsers();

    @Test
    void detectsRealFormatIgnoringExtension() {
        SampleDescriptor lyingExt = SampleFixtures.descriptor("bin", SampleFixtures.validFw1());
        ParseOutcome outcome = service.parse(lyingExt);

        assertTrue(outcome.success());
        assertEquals("FW1", outcome.detectedFormat());
        assertTrue(outcome.metadata().containsKey("extensionMismatch"),
                "扩展名与真实格式不符必须被标注");
        log.info("[格式探测] taskId=test-extension-mismatch 真实格式={} 判定依据={}",
                outcome.detectedFormat(), outcome.metadata().get("extensionMismatch"));

        ParseOutcome arc = service.parse(
                SampleFixtures.descriptor("fw1",
                        SampleFixtures.arc1(SampleFixtures.metadata("device", "a"))));
        assertTrue(arc.success());
        assertEquals("ARC1", arc.detectedFormat());
        assertTrue(arc.metadata().containsKey("extensionMismatch"));
        log.info("[格式探测] taskId=test-arc-as-fw1 真实格式={} 判定依据={}",
                arc.detectedFormat(), arc.metadata().get("extensionMismatch"));
    }

    @Test
    void distinguishesTruncatedFromLengthMismatchAndCorruption() {
        LinkedHashMap<String, String> meta = SampleFixtures.metadata("device", "r1");

        ParseOutcome truncated = service.parse(SampleFixtures.descriptor("fw1",
                SampleFixtures.fw1Truncated(meta, 4)));
        assertFalse(truncated.success());
        assertEquals(FailureCode.TRUNCATED_SAMPLE, truncated.failureCode());

        ParseOutcome lengthMismatch = service.parse(SampleFixtures.descriptor("fw1",
                SampleFixtures.fw1HeaderDeclaredLength(meta, -4)));
        assertFalse(lengthMismatch.success());
        assertEquals(FailureCode.HEADER_LENGTH_MISMATCH, lengthMismatch.failureCode());

        ParseOutcome corrupt = service.parse(SampleFixtures.descriptor("fw1",
                SampleFixtures.fw1CorruptPayload(meta)));
        assertFalse(corrupt.success());
        assertEquals(FailureCode.CORRUPT_PAYLOAD, corrupt.failureCode());

        // 三类原因必须两两可区分
        assertNotEquals(truncated.failureCode(), lengthMismatch.failureCode());
        assertNotEquals(truncated.failureCode(), corrupt.failureCode());
        assertNotEquals(lengthMismatch.failureCode(), corrupt.failureCode());

        log.info("[畸形样本] taskId=test-truncated 错误来源=FW1 失败码={} 判定依据={}",
                truncated.failureCode(), truncated.failureMessage());
        log.info("[畸形样本] taskId=test-length-mismatch 错误来源=FW1 失败码={} 判定依据={}",
                lengthMismatch.failureCode(), lengthMismatch.failureMessage());
        log.info("[畸形样本] taskId=test-corrupt 错误来源=FW1 失败码={} 判定依据={}",
                corrupt.failureCode(), corrupt.failureMessage());

        // ARC1 的截断与长度不符同样可区分
        ParseOutcome arcTruncated = service.parse(SampleFixtures.descriptor("arc",
                SampleFixtures.arc1Truncated(meta, 3)));
        ParseOutcome arcMismatch = service.parse(SampleFixtures.descriptor("arc",
                SampleFixtures.arc1LengthMismatch(meta, 3)));
        assertEquals(FailureCode.TRUNCATED_SAMPLE, arcTruncated.failureCode());
        assertEquals(FailureCode.HEADER_LENGTH_MISMATCH, arcMismatch.failureCode());
    }

    @Test
    void unsupportedFormatHasDistinctFailureCode() {
        ParseOutcome unknown = service.parse(
                SampleFixtures.descriptor("fw1", SampleFixtures.unknownMagic()));
        assertFalse(unknown.success());
        assertEquals(FailureCode.UNSUPPORTED_FORMAT, unknown.failureCode());
        assertEquals("UNKNOWN", unknown.detectedFormat());
        log.info("[不支持格式] taskId=test-unknown-magic 错误来源=魔数探测 失败码={} 判定依据={}",
                unknown.failureCode(), unknown.failureMessage());
    }

    @Test
    void emptySampleRejected() {
        ParseOutcome empty = service.parse(SampleFixtures.descriptor("fw1", new byte[0]));
        assertFalse(empty.success());
        assertEquals(FailureCode.EMPTY_SAMPLE, empty.failureCode());

        ParseOutcome tooShort = service.parse(
                SampleFixtures.descriptor("fw1", SampleFixtures.fw1TooShort()));
        assertFalse(tooShort.success());
        assertEquals(FailureCode.TRUNCATED_SAMPLE, tooShort.failureCode());
        log.info("[空/过短样本] taskId=test-empty 失败码={} 判定依据={}",
                empty.failureCode(), empty.failureMessage());
        log.info("[空/过短样本] taskId=test-too-short 失败码={} 判定依据={}",
                tooShort.failureCode(), tooShort.failureMessage());
    }
}
