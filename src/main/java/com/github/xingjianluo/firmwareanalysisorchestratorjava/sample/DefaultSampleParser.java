package com.github.xingjianluo.firmwareanalysisorchestratorjava.sample;

import com.github.xingjianluo.firmwareanalysisorchestratorjava.model.FormatType;
import com.github.xingjianluo.firmwareanalysisorchestratorjava.model.ParsedFirmware;
import com.github.xingjianluo.firmwareanalysisorchestratorjava.model.SampleSubmission;
import com.github.xingjianluo.firmwareanalysisorchestratorjava.model.Segment;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import static com.github.xingjianluo.firmwareanalysisorchestratorjava.model.FailureReason.CORRUPT_SAMPLE;
import static com.github.xingjianluo.firmwareanalysisorchestratorjava.model.FailureReason.DECLARED_LENGTH_MISMATCH;
import static com.github.xingjianluo.firmwareanalysisorchestratorjava.model.FailureReason.EMPTY_SAMPLE;
import static com.github.xingjianluo.firmwareanalysisorchestratorjava.model.FailureReason.EXTENSION_MISMATCH;
import static com.github.xingjianluo.firmwareanalysisorchestratorjava.model.FailureReason.TRUNCATED_CONTENT;
import static com.github.xingjianluo.firmwareanalysisorchestratorjava.model.FailureReason.UNSUPPORTED_FORMAT;

/**
 * 默认样本解析器。
 *
 * <p>任何畸形情况都收敛为携带 {@code FailureReason} 的 {@link MalformedSampleException}，
 * 不会把 ArrayIndexOutOfBounds 等底层异常透出调用方。
 *
 * <h3>FWIMG 本地格式（小端）</h3>
 * <pre>
 *  偏移  长度  含义
 *  0     5     魔数 "FWIMG"
 *  5     1     版本
 *  6     4     声明总长度（uint32）
 *  10    1     签名标记（0/1）
 *  11    1     分段数量 N（uint8）
 *  12    ...   N 个分段：[1 字节类型][4 字节内容长度][内容字节]
 * </pre>
 * 分段类型：1=META, 2=CONFIG, 3=VERSION, 4=BINARY。
 */
@Component
public class DefaultSampleParser implements SampleParser {

    private static final int HEADER_LEN = 12;
    private static final Set<String> KNOWN_EXTENSIONS = Set.of("fwimg", "elf", "zip", "bin");

    private final FormatDetector detector;

    public DefaultSampleParser(FormatDetector detector) {
        this.detector = detector;
    }

    @Override
    public ParsedFirmware parse(SampleSubmission submission) {
        byte[] data = submission.content();
        if (data == null || data.length == 0) {
            throw new MalformedSampleException(EMPTY_SAMPLE, "提交的样本字节为空");
        }
        FormatType format = detector.detect(submission);
        if (format == FormatType.UNKNOWN) {
            throw new MalformedSampleException(UNSUPPORTED_FORMAT,
                    "未识别到已知文件魔数，样本长度=" + data.length);
        }
        verifyExtension(submission, format);
        return switch (format) {
            case FWIMG -> parseFwimg(data);
            case ELF -> new ParsedFirmware(FormatType.ELF,
                    List.of(new Segment("STRINGS", extractPrintableStrings(data))));
            case ZIP -> new ParsedFirmware(FormatType.ZIP,
                    List.of(new Segment("STRINGS", extractPrintableStrings(data))));
            default -> throw new MalformedSampleException(UNSUPPORTED_FORMAT, "不支持的格式");
        };
    }

    /** 扩展名与真实格式不符时给 EXTENSION_MISMATCH；未知扩展名不拦截（按真实格式解析）。 */
    private void verifyExtension(SampleSubmission submission, FormatType actual) {
        String name = submission.fileName();
        if (name == null) {
            return;
        }
        int dot = name.lastIndexOf('.');
        if (dot < 0 || dot == name.length() - 1) {
            return;
        }
        String ext = name.substring(dot + 1).toLowerCase(Locale.ROOT);
        if (!KNOWN_EXTENSIONS.contains(ext) || "bin".equals(ext)) {
            return;
        }
        if (!ext.equals(actual.name().toLowerCase(Locale.ROOT))) {
            throw new MalformedSampleException(EXTENSION_MISMATCH,
                    "扩展名 ." + ext + " 与真实格式 " + actual + " 不符");
        }
    }

    private ParsedFirmware parseFwimg(byte[] data) {
        try {
            if (data.length < HEADER_LEN) {
                throw new MalformedSampleException(TRUNCATED_CONTENT,
                        "样本长度 " + data.length + " 短于 FWIMG 头部 " + HEADER_LEN);
            }
            long declared = readUint32(data, 6);
            long actual = data.length & 0xffffffffL;
            if (actual < declared) {
                // 实际内容比头部声明短：内容被截断
                throw new MalformedSampleException(TRUNCATED_CONTENT,
                        "实际长度 " + actual + " 短于头部声明长度 " + declared);
            }
            if (actual > declared) {
                // 头部声明长度与实际不符（多出脏数据）
                throw new MalformedSampleException(DECLARED_LENGTH_MISMATCH,
                        "实际长度 " + actual + " 超出头部声明长度 " + declared);
            }
            int segCount = data[11] & 0xFF;
            List<Segment> segments = new ArrayList<>();
            int offset = HEADER_LEN;
            for (int i = 0; i < segCount; i++) {
                if (offset + 5 > declared) {
                    throw new MalformedSampleException(CORRUPT_SAMPLE,
                            "第 " + (i + 1) + " 个分段头部越界");
                }
                int typeCode = data[offset] & 0xFF;
                long len = readUint32(data, offset + 1);
                offset += 5;
                if (offset + len > declared) {
                    throw new MalformedSampleException(CORRUPT_SAMPLE,
                            "第 " + (i + 1) + " 个分段内容越界（声明 " + len + " 字节）");
                }
                String type = segmentType(typeCode);
                if (type == null) {
                    throw new MalformedSampleException(CORRUPT_SAMPLE,
                            "第 " + (i + 1) + " 个分段类型码非法: " + typeCode);
                }
                String content = new String(data, offset, (int) len, StandardCharsets.UTF_8);
                segments.add(new Segment(type, content));
                offset += (int) len;
            }
            return new ParsedFirmware(FormatType.FWIMG, List.copyOf(segments));
        } catch (MalformedSampleException e) {
            throw e;
        } catch (RuntimeException e) {
            // 兜底：任何未预期的底层异常都不允许透出
            throw new MalformedSampleException(CORRUPT_SAMPLE,
                    "FWIMG 解析过程中发生不可恢复错误", e);
        }
    }

    private static String segmentType(int code) {
        return switch (code) {
            case 1 -> "META";
            case 2 -> "CONFIG";
            case 3 -> "VERSION";
            case 4 -> "BINARY";
            default -> null;
        };
    }

    private static long readUint32(byte[] data, int off) {
        return (data[off] & 0xFFL)
                | ((data[off + 1] & 0xFFL) << 8)
                | ((data[off + 2] & 0xFFL) << 16)
                | ((data[off + 3] & 0xFFL) << 24);
    }

    /** 从 ELF/ZIP 原始字节里抽取长度不少于 4 的可打印 ASCII 串，作为规则匹配的分段视图。 */
    static String extractPrintableStrings(byte[] data) {
        StringBuilder out = new StringBuilder();
        StringBuilder run = new StringBuilder();
        for (byte b : data) {
            int c = b & 0xFF;
            if (c >= 0x20 && c < 0x7F) {
                run.append((char) c);
            } else {
                if (run.length() >= 4) {
                    if (!out.isEmpty()) {
                        out.append('\n');
                    }
                    out.append(run);
                }
                run.setLength(0);
            }
        }
        if (run.length() >= 4 && !out.isEmpty()) {
            out.append('\n').append(run);
        } else if (run.length() >= 4) {
            out.append(run);
        }
        return out.toString();
    }
}
