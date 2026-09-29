package com.github.xingjianluo.firmwareanalysisorchestratorjava.rule;

import com.github.xingjianluo.firmwareanalysisorchestratorjava.model.ParsedFirmware;
import com.github.xingjianluo.firmwareanalysisorchestratorjava.model.RuleFinding;
import com.github.xingjianluo.firmwareanalysisorchestratorjava.model.RuleVerdict;
import com.github.xingjianluo.firmwareanalysisorchestratorjava.model.Segment;

import java.util.List;
import java.util.regex.Pattern;

/** 内置安全规则集合。规则对象不可变；version 随内容修改递增。 */
public final class BuiltInRules {

    private BuiltInRules() {
    }

    public static List<AnalysisRule> defaults() {
        return List.of(
                new HardcodedPasswordRule(),
                new VersionPresentRule(),
                new BackdoorMarkerRule(),
                new IntegritySignedRule(),
                new DebugSwitchRule()
        );
    }

    private static String joinText(ParsedFirmware fw) {
        StringBuilder sb = new StringBuilder();
        for (Segment s : fw.segments()) {
            sb.append(s.content()).append('\n');
        }
        return sb.toString();
    }

    private static boolean hasSegment(ParsedFirmware fw, String type) {
        return fw.segments().stream().anyMatch(s -> s.type().equals(type));
    }

    /** R001：任意分段出现 password=xxx（口令非空）即不通过。 */
    static final class HardcodedPasswordRule extends AbstractAnalysisRule {
        private static final Pattern PWD =
                Pattern.compile("password\\s*=\\s*([^\\s,;]+)", Pattern.CASE_INSENSITIVE);

        @Override public String id() { return "R001"; }
        @Override public int version() { return 1; }
        @Override public String subject() { return "CREDENTIALS"; }

        @Override
        protected RuleFinding doEvaluate(RuleContext context) {
            String text = joinText(context.firmware());
            var m = PWD.matcher(text);
            if (m.find()) {
                return finding(RuleVerdict.FAIL,
                        "匹配到硬编码口令模式 password=<redacted>，命中值长度=" + m.group(1).length());
            }
            return finding(RuleVerdict.PASS, "未匹配到硬编码口令模式 password=");
        }
    }

    /** R002：必须存在非空 VERSION 分段。 */
    static final class VersionPresentRule extends AbstractAnalysisRule {
        @Override public String id() { return "R002"; }
        @Override public int version() { return 1; }
        @Override public String subject() { return "VERSION"; }

        @Override
        protected RuleFinding doEvaluate(RuleContext context) {
            return context.firmware().segments().stream()
                    .filter(s -> s.type().equals("VERSION"))
                    .map(Segment::content)
                    .filter(c -> c != null && !c.isBlank())
                    .findFirst()
                    .map(v -> finding(RuleVerdict.PASS, "存在非空 VERSION 分段: " + v.trim()))
                    .orElseGet(() -> finding(RuleVerdict.FAIL, "缺少非空 VERSION 分段"));
        }
    }

    /** R003：任意分段出现 BACKDOOR / backdoor:on 标记即不通过。 */
    static final class BackdoorMarkerRule extends AbstractAnalysisRule {
        @Override public String id() { return "R003"; }
        @Override public int version() { return 1; }
        @Override public String subject() { return "BACKDOOR"; }

        @Override
        protected RuleFinding doEvaluate(RuleContext context) {
            String text = joinText(context.firmware());
            if (text.toUpperCase().contains("BACKDOOR")) {
                return finding(RuleVerdict.FAIL, "匹配到后门标记 BACKDOOR");
            }
            return finding(RuleVerdict.PASS, "未匹配到后门标记 BACKDOOR");
        }
    }

    /** R004：完整性签名必须开启（signed 标记为 1）。 */
    static final class IntegritySignedRule extends AbstractAnalysisRule {
        private static final Pattern SIGNED =
                Pattern.compile("signed\\s*=\\s*([01])", Pattern.CASE_INSENSITIVE);

        @Override public String id() { return "R004"; }
        @Override public int version() { return 1; }
        @Override public String subject() { return "INTEGRITY"; }

        @Override
        protected RuleFinding doEvaluate(RuleContext context) {
            var m = SIGNED.matcher(joinText(context.firmware()));
            if (!m.find()) {
                return finding(RuleVerdict.FAIL, "未声明 signed 标记，完整性签名缺失");
            }
            if ("1".equals(m.group(1))) {
                return finding(RuleVerdict.PASS, "signed=1，完整性签名已开启");
            }
            return finding(RuleVerdict.FAIL, "signed=0，完整性签名被关闭");
        }
    }

    /** R005：DEBUG=true 给出警告（不直接否决）。 */
    static final class DebugSwitchRule extends AbstractAnalysisRule {
        private static final Pattern DEBUG =
                Pattern.compile("debug\\s*=\\s*true", Pattern.CASE_INSENSITIVE);

        @Override public String id() { return "R005"; }
        @Override public int version() { return 1; }
        @Override public String subject() { return "DEBUG"; }

        @Override
        protected RuleFinding doEvaluate(RuleContext context) {
            if (DEBUG.matcher(joinText(context.firmware())).find()) {
                return finding(RuleVerdict.WARN, "匹配到调试开关 DEBUG=true");
            }
            return finding(RuleVerdict.PASS, "未开启 DEBUG 开关");
        }
    }

    /** 供测试使用的恒失败规则。 */
    public static AnalysisRule alwaysError(String id, String subject) {
        return new AbstractAnalysisRule() {
            @Override public String id() { return id; }
            @Override public int version() { return 1; }
            @Override public String subject() { return subject; }
            @Override
            protected RuleFinding doEvaluate(RuleContext context) {
                throw new IllegalStateException("simulated rule failure: " + id);
            }
        };
    }
}
