package com.github.xingjianluo.firmwareanalysisorchestratorjava.sample;

import com.github.xingjianluo.firmwareanalysisorchestratorjava.model.FormatType;
import com.github.xingjianluo.firmwareanalysisorchestratorjava.model.SampleSubmission;
import org.springframework.stereotype.Component;

import java.util.HexFormat;

/**
 * 基于文件魔数的格式探测器：只看字节，不看扩展名。
 *
 * <ul>
 *   <li>{@code "FWIMG"} —— 本地构造的固件镜像格式</li>
 *   <li>{@code 0x7F 'E' 'L' 'F'} —— ELF 二进制</li>
 *   <li>{@code "PK\003\004"} —— ZIP 归档</li>
 * </ul>
 * 识别不到返回 {@link FormatType#UNKNOWN}，由解析层转成 UNSUPPORTED_FORMAT。
 */
@Component
public class MagicFormatDetector implements FormatDetector {

    static final byte[] FWIMG_MAGIC = {0x46, 0x57, 0x49, 0x4D, 0x47}; // "FWIMG"
    static final byte[] ELF_MAGIC = {0x7F, 0x45, 0x4C, 0x46};
    static final byte[] ZIP_MAGIC = {0x50, 0x4B, 0x03, 0x04};

    @Override
    public FormatType detect(SampleSubmission submission) {
        byte[] data = submission.content();
        if (data == null || data.length == 0) {
            return FormatType.UNKNOWN;
        }
        if (startsWith(data, FWIMG_MAGIC)) {
            return FormatType.FWIMG;
        }
        if (startsWith(data, ELF_MAGIC)) {
            return FormatType.ELF;
        }
        if (startsWith(data, ZIP_MAGIC)) {
            return FormatType.ZIP;
        }
        return FormatType.UNKNOWN;
    }

    private static boolean startsWith(byte[] data, byte[] prefix) {
        if (data.length < prefix.length) {
            return false;
        }
        for (int i = 0; i < prefix.length; i++) {
            if (data[i] != prefix[i]) {
                return false;
            }
        }
        return true;
    }

    static String toHex(byte[] bytes) {
        return HexFormat.of().formatHex(bytes);
    }
}
