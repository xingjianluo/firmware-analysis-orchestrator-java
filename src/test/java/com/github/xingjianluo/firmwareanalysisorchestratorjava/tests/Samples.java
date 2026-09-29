package com.github.xingjianluo.firmwareanalysisorchestratorjava.tests;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

/** 测试专用的本地样本工厂：全部手工构造，不依赖任何外部文件。 */
public final class Samples {

    public static final byte[] FWIMG_MAGIC = {0x46, 0x57, 0x49, 0x4D, 0x47};
    public static final byte[] ELF_MAGIC = {0x7F, 0x45, 0x4C, 0x46};
    public static final byte[] ZIP_MAGIC = {0x50, 0x4B, 0x03, 0x04};

    private Samples() {
    }

    /** 构造一个干净、规则可通过的 FWIMG。 */
    public static byte[] cleanFirmware() {
        return fwimg(List.of(
                seg((byte) 1, "model=X100 vendor=Acme"),
                seg((byte) 2, "signed=1"),
                seg((byte) 3, "1.4.2"),
                seg((byte) 4, "BOOTLOADER-PAYLOAD-SAFE")));
    }

    /** 含硬编码口令、DEBUG 开关且未签名的样本（FAIL + WARN）。 */
    public static byte[] riskyFirmware() {
        return fwimg(List.of(
                seg((byte) 1, "model=X200"),
                seg((byte) 2, "password=admin123 signed=0 debug=true"),
                seg((byte) 3, "0.9.0")));
    }

    /** 含后门标记的样本。 */
    public static byte[] backdoorFirmware() {
        return fwimg(List.of(
                seg((byte) 2, "signed=1 BACKDOOR:ON"),
                seg((byte) 3, "2.0.0")));
    }

    /** 缺少 VERSION 分段的样本。 */
    public static byte[] noVersionFirmware() {
        return fwimg(List.of(
                seg((byte) 1, "model=X300"),
                seg((byte) 2, "signed=1")));
    }

    public static byte[] elfWithText() {
        String text = "GET /shell password=rootpw LOGIN OK";
        byte[] body = text.getBytes(StandardCharsets.US_ASCII);
        byte[] out = new byte[ELF_MAGIC.length + 8 + body.length];
        System.arraycopy(ELF_MAGIC, 0, out, 0, ELF_MAGIC.length);
        for (int i = 0; i < 8; i++) {
            out[ELF_MAGIC.length + i] = 0;
        }
        System.arraycopy(body, 0, out, ELF_MAGIC.length + 8, body.length);
        return out;
    }

    public static byte[] zipLike() {
        String text = "signed=1 RELEASE 1.0.0";
        byte[] body = text.getBytes(StandardCharsets.US_ASCII);
        byte[] out = new byte[ZIP_MAGIC.length + 4 + body.length];
        System.arraycopy(ZIP_MAGIC, 0, out, 0, ZIP_MAGIC.length);
        System.arraycopy(body, 0, out, ZIP_MAGIC.length + 4, body.length);
        return out;
    }

    public static byte[] unknownBytes() {
        return "this is definitely not a known firmware".getBytes(StandardCharsets.UTF_8);
    }

    /** 合法长度声明的完整 FWIMG。 */
    public static byte[] fwimg(List<byte[]> segments) {
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        for (byte[] s : segments) {
            body.writeBytes(s);
        }
        byte[] payload = body.toByteArray();
        int total = 12 + payload.length;
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.writeBytes(FWIMG_MAGIC);
        out.write(1);                                   // version
        writeU32(out, total);                           // declared length == actual
        out.write(1);                                   // signed flag
        out.write(segments.size() & 0xFF);
        out.writeBytes(payload);
        return out.toByteArray();
    }

    /** 截断：尾部切掉若干字节，但头部声明长度不变。 */
    public static byte[] truncated(byte[] validFwimg, int cutTail) {
        byte[] out = new byte[validFwimg.length - cutTail];
        System.arraycopy(validFwimg, 0, out, 0, out.length);
        return out;
    }

    /** 头部声明长度与实际不符：声明长度小于实际（尾部带多余字节）。 */
    public static byte[] declaredLengthMismatch(byte[] validFwimg) {
        byte[] out = new byte[validFwimg.length + 3];
        System.arraycopy(validFwimg, 0, out, 0, validFwimg.length);
        // 头部声明长度保持为原（较短）长度，实际多出 3 字节
        return out;
    }

    /** 结构损坏：分段类型码非法。 */
    public static byte[] corruptSegment(byte[] validFwimg) {
        byte[] out = validFwimg.clone();
        out[12] = 99;
        return out;
    }

    /** 短于头部的 FWIMG（只有魔数+几字节）。 */
    public static byte[] tooShortHeader() {
        byte[] out = new byte[7];
        System.arraycopy(FWIMG_MAGIC, 0, out, 0, 5);
        out[5] = 1;
        out[6] = 0;
        return out;
    }

    static byte[] seg(byte type, String content) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(type);
        byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
        writeU32(out, bytes.length);
        out.writeBytes(bytes);
        return out.toByteArray();
    }

    private static void writeU32(ByteArrayOutputStream out, long value) {
        out.write((int) (value & 0xFF));
        out.write((int) ((value >>> 8) & 0xFF));
        out.write((int) ((value >>> 16) & 0xFF));
        out.write((int) ((value >>> 24) & 0xFF));
    }
}
