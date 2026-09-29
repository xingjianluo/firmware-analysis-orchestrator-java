package com.github.xingjianluo.firmwareanalysisorchestratorjava.tests;

import com.github.xingjianluo.firmwareanalysisorchestratorjava.model.SampleDescriptor;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 本地样本构造工具：直接按二进制布局拼出正常/畸形样本，不依赖任何外部文件。
 */
public final class SampleFixtures {

    public static final byte[] FW1_MAGIC = {'F', 'W', '0', '1'};
    public static final byte[] ARC1_MAGIC = {'A', 'R', 'C', '1'};

    private SampleFixtures() {
    }

    // ---------- FW1 ----------

    /** 正常的 FW1 样本，declaredLength 与 payload 实际长度严格一致。 */
    public static byte[] fw1(Map<String, String> metadata) {
        return fw1With(metadata, 0, 0);
    }

    public static byte[] fw1(String... kvPairs) {
        return fw1(metadata(kvPairs));
    }

    /**
     * 声明长度与实际不符（不截断字节，只改头部声明）。
     * declaredDelta &gt; 0 -> 声明偏大（读取时内容不足 -> TRUNCATED）；
     * declaredDelta &lt; 0 -> 声明偏小（实际字节多余 -> HEADER_LENGTH_MISMATCH）。
     */
    public static byte[] fw1HeaderDeclaredLength(Map<String, String> metadata, int declaredDelta) {
        return fw1With(metadata, 0, declaredDelta);
    }

    /** 内容被截断：真实截掉尾部 missing 个字节（头部仍按完整长度声明）。 */
    public static byte[] fw1Truncated(Map<String, String> metadata, int missing) {
        return fw1With(metadata, missing, 0);
    }

    /** payload 中包含无法解析的坏键值对（没有 '='）。 */
    public static byte[] fw1CorruptPayload(Map<String, String> metadata) {
        byte[] good = fw1(metadata);
        String broken = "BAD_PAIR_WITHOUT_EQ";
        byte[] brokenBytes = broken.getBytes(StandardCharsets.US_ASCII);
        byte[] out = new byte[good.length + 1 + brokenBytes.length];
        System.arraycopy(good, 0, out, 0, good.length);
        out[good.length] = ';';
        System.arraycopy(brokenBytes, 0, out, good.length + 1, brokenBytes.length);
        // 重新写头部 declaredLength
        int declared = out.length - 10;
        writeInt(out, 6, declared);
        return out;
    }

    private static byte[] fw1With(Map<String, String> metadata, int truncateTail, int declaredDelta) {
        String payload = encodePayload(metadata);
        byte[] payloadBytes = payload.getBytes(StandardCharsets.US_ASCII);
        int declared = payloadBytes.length + declaredDelta;
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.writeBytes(FW1_MAGIC);
        out.write(0);
        out.write(2); // version = 2
        writeInt(out, declared);
        out.writeBytes(payloadBytes);
        byte[] full = out.toByteArray();
        if (truncateTail > 0) {
            int cut = Math.min(truncateTail, full.length - 10);
            byte[] cutBytes = new byte[full.length - cut];
            System.arraycopy(full, 0, cutBytes, 0, cutBytes.length);
            return cutBytes;
        }
        return full;
    }

    private static String encodePayload(Map<String, String> metadata) {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> e : metadata.entrySet()) {
            sb.append(e.getKey()).append('=').append(e.getValue()).append(';');
        }
        return sb.toString();
    }

    // ---------- ARC1 ----------

    /** 正常的 ARC1 样本。 */
    public static byte[] arc1(Map<String, String> entries) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.writeBytes(ARC1_MAGIC);
        writeShort(out, entries.size());
        // 占位 totalLength
        int totalPos = out.size();
        writeInt(out, 0);
        for (Map.Entry<String, String> e : entries.entrySet()) {
            byte[] name = e.getKey().getBytes(StandardCharsets.US_ASCII);
            byte[] value = e.getValue().getBytes(StandardCharsets.US_ASCII);
            writeShort(out, name.length);
            out.writeBytes(name);
            writeInt(out, value.length);
            out.writeBytes(value);
        }
        byte[] full = out.toByteArray();
        writeInt(full, totalPos, full.length);
        return full;
    }

    /** ARC1 截断样本（头部声明完整总长，实际截掉尾部）。 */
    public static byte[] arc1Truncated(Map<String, String> entries, int missing) {
        byte[] full = arc1(entries);
        byte[] cut = new byte[full.length - missing];
        System.arraycopy(full, 0, cut, 0, cut.length);
        return cut;
    }

    /** ARC1 头部声明总长与实际不符（在尾部追加多余字节，声明不变）。 */
    public static byte[] arc1LengthMismatch(Map<String, String> entries, int extra) {
        byte[] full = arc1(entries);
        byte[] out = new byte[full.length + extra];
        System.arraycopy(full, 0, out, 0, full.length);
        return out;
    }

    // ---------- 通用 ----------

    /** 未知魔数样本。 */
    public static byte[] unknownMagic() {
        return new byte[]{'X', 'X', 'X', 'X', 0, 1, 0, 0, 0, 2, 'a', 'b'};
    }

    /** 只有几个字节的过短样本（魔数可识别但头部不完整）。 */
    public static byte[] fw1TooShort() {
        return new byte[]{'F', 'W', '0', '1', 0};
    }

    /** 标准“好样本”：FW1 + version=2 + device 字段 + 长度不超限。 */
    public static byte[] validFw1() {
        return fw1(metadata("device", "router-x1", "build", "2026"));
    }

    /** 超大样本，用于触发 R-PAYLOAD-LIMIT 未命中。 */
    public static byte[] oversizedFw1() {
        StringBuilder big = new StringBuilder();
        while (big.length() < 80) {
            big.append('x');
        }
        return fw1(metadata("device", big.toString()));
    }

    public static SampleDescriptor descriptor(String ext, byte[] content) {
        return new SampleDescriptor(ext, content);
    }

    public static LinkedHashMap<String, String> metadata(String... kv) {
        if (kv.length % 2 != 0) {
            throw new IllegalArgumentException("kv 必须成对出现");
        }
        LinkedHashMap<String, String> map = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            map.put(kv[i], kv[i + 1]);
        }
        return map;
    }

    private static void writeShort(ByteArrayOutputStream out, int value) {
        out.write((value >>> 8) & 0xFF);
        out.write(value & 0xFF);
    }

    private static void writeInt(ByteArrayOutputStream out, int value) {
        out.write((value >>> 24) & 0xFF);
        out.write((value >>> 16) & 0xFF);
        out.write((value >>> 8) & 0xFF);
        out.write(value & 0xFF);
    }

    private static void writeInt(byte[] buf, int offset, int value) {
        buf[offset] = (byte) ((value >>> 24) & 0xFF);
        buf[offset + 1] = (byte) ((value >>> 16) & 0xFF);
        buf[offset + 2] = (byte) ((value >>> 8) & 0xFF);
        buf[offset + 3] = (byte) (value & 0xFF);
    }
}
