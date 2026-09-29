package com.github.xingjianluo.firmwareanalysisorchestratorjava.rule;

import java.util.List;
import java.util.Set;

/**
 * 内置规则集合（版本 1）。
 * <ul>
 *   <li>R-FORMAT-KNOWN（group=format）：真实格式必须是 FW1/ARC1；</li>
 *   <li>R-VERSION-V2（group=security）：FW1 元数据 version=2；</li>
 *   <li>R-PAYLOAD-LIMIT（group=size）：payload 字节不超过 {@link #PAYLOAD_LIMIT}；</li>
 *   <li>R-REQUIRED-DEVICE（group=metadata）：元数据必须包含非空 device 字段。</li>
 * </ul>
 * 每条规则的 evidence 都给出具体判定依据，保证汇总结论可解释。
 */
public final class BuiltInRules {

    public static final int PAYLOAD_LIMIT = 64;

    static final Set<String> SUPPORTED_FORMATS = Set.of("FW1", "ARC1");

    private BuiltInRules() {
    }

    public static List<AnalysisRule> version1() {
        return List.of(
                formatKnownRule(),
                versionV2Rule(),
                payloadLimitRule(),
                requiredDeviceRule()
        );
    }

    public static AnalysisRule formatKnownRule() {
        return new AnalysisRule() {
            @Override
            public String id() {
                return "R-FORMAT-KNOWN";
            }

            @Override
            public String group() {
                return "format";
            }

            @Override
            public RuleOutcome evaluate(RuleEvaluationContext ctx) {
                String format = ctx.format();
                if (SUPPORTED_FORMATS.contains(format)) {
                    return new RuleOutcome(true, "真实格式 " + format + " 属于受支持格式 " + SUPPORTED_FORMATS);
                }
                return new RuleOutcome(false, "真实格式 " + format + " 不在受支持列表 " + SUPPORTED_FORMATS);
            }
        };
    }

    public static AnalysisRule versionV2Rule() {
        return new AnalysisRule() {
            @Override
            public String id() {
                return "R-VERSION-V2";
            }

            @Override
            public String group() {
                return "security";
            }

            @Override
            public RuleOutcome evaluate(RuleEvaluationContext ctx) {
                String version = ctx.metadata().get("version");
                if ("2".equals(version)) {
                    return new RuleOutcome(true, "FW1 version=2，满足安全基线");
                }
                return new RuleOutcome(false, "FW1 version=" + version + "，不满足要求的 2");
            }
        };
    }

    public static AnalysisRule payloadLimitRule() {
        return new AnalysisRule() {
            @Override
            public String id() {
                return "R-PAYLOAD-LIMIT";
            }

            @Override
            public String group() {
                return "size";
            }

            @Override
            public RuleOutcome evaluate(RuleEvaluationContext ctx) {
                int length = ctx.contentLength();
                if (length <= PAYLOAD_LIMIT) {
                    return new RuleOutcome(true, "样本长度 " + length + " <= 限制 " + PAYLOAD_LIMIT);
                }
                return new RuleOutcome(false, "样本长度 " + length + " > 限制 " + PAYLOAD_LIMIT);
            }
        };
    }

    public static AnalysisRule requiredDeviceRule() {
        return new AnalysisRule() {
            @Override
            public String id() {
                return "R-REQUIRED-DEVICE";
            }

            @Override
            public String group() {
                return "metadata";
            }

            @Override
            public RuleOutcome evaluate(RuleEvaluationContext ctx) {
                String device = ctx.metadata().get("device");
                if (device != null && !device.isBlank()) {
                    return new RuleOutcome(true, "元数据包含 device=" + device);
                }
                return new RuleOutcome(false, "元数据缺少非空 device 字段");
            }
        };
    }
}
