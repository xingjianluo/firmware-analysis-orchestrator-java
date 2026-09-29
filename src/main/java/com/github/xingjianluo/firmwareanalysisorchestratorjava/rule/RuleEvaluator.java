package com.github.xingjianluo.firmwareanalysisorchestratorjava.rule;

import com.github.xingjianluo.firmwareanalysisorchestratorjava.model.AnalysisConclusion;
import com.github.xingjianluo.firmwareanalysisorchestratorjava.model.ParseOutcome;
import com.github.xingjianluo.firmwareanalysisorchestratorjava.model.RuleResult;
import com.github.xingjianluo.firmwareanalysisorchestratorjava.model.Verdict;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 规则执行与汇总器：逐条执行规则、收敛单条规则异常、
 * 检测同组规则冲突、按固定优先级（PARTIAL &gt; INCONCLUSIVE &gt; FAIL &gt; PASS）
 * 产出稳定结论。规则明细按 ruleId 排序，保证可重复。
 */
@Component
public class RuleEvaluator {

    private static final Logger log = LoggerFactory.getLogger(RuleEvaluator.class);

    /**
     * 使用指定版本规则集对解析结果求值。
     */
    public AnalysisConclusion evaluate(ParseOutcome parseOutcome, RuleSet ruleSet) {
        int version = ruleSet.version();
        RuleEvaluationContext context = new RuleEvaluationContext(
                parseOutcome.detectedFormat(),
                parseOutcome.metadata(),
                rawLength(parseOutcome));

        List<RuleResult> results = new ArrayList<>();
        for (AnalysisRule rule : ruleSet.rules()) {
            results.add(executeOne(rule, version, context));
        }
        results.sort(Comparator.comparing(RuleResult::ruleId));

        List<String> conflicts = detectConflicts(results);
        List<String> reasons = buildReasons(results, conflicts);

        boolean hasError = results.stream().anyMatch(RuleResult::errored);
        boolean hasConflict = !conflicts.isEmpty();
        boolean hasMiss = results.stream().anyMatch(r -> !r.matched() && !r.errored());

        Verdict verdict;
        if (hasError) {
            // 任意规则执行失败都不能算通过
            verdict = Verdict.PARTIAL;
        } else if (hasConflict) {
            verdict = Verdict.INCONCLUSIVE;
        } else if (hasMiss) {
            verdict = Verdict.FAIL;
        } else {
            verdict = Verdict.PASS;
        }

        log.debug("规则汇总完成 ruleVersion={} verdict={} rules={} conflicts={}",
                version, verdict, results.size(), conflicts.size());
        return new AnalysisConclusion(verdict, version, List.copyOf(results),
                List.copyOf(conflicts), List.copyOf(reasons));
    }

    private static RuleResult executeOne(AnalysisRule rule, int version, RuleEvaluationContext context) {
        try {
            RuleOutcome outcome = rule.evaluate(context);
            if (outcome.matched()) {
                return RuleResult.matched(rule.id(), rule.group(), version, outcome.evidence());
            }
            return RuleResult.notMatched(rule.id(), rule.group(), version, outcome.evidence());
        } catch (Exception e) {
            String source = e.getClass().getSimpleName();
            log.warn("规则执行失败 ruleId={} ruleVersion={} errorSource={} message={}",
                    rule.id(), version, source, e.getMessage());
            return RuleResult.error(rule.id(), rule.group(), version,
                    "规则执行抛出异常：" + e.getMessage(), source);
        }
    }

    /**
     * 同组内同时存在命中与未命中（均非错误）即判定为冲突。
     * 错误规则不参与冲突判定，其优先级更高（PARTIAL）。
     */
    private static List<String> detectConflicts(List<RuleResult> results) {
        Map<String, List<RuleResult>> byGroup = new LinkedHashMap<>();
        for (RuleResult r : results) {
            byGroup.computeIfAbsent(r.group(), k -> new ArrayList<>()).add(r);
        }
        List<String> conflicts = new ArrayList<>();
        for (Map.Entry<String, List<RuleResult>> entry : byGroup.entrySet()) {
            List<RuleResult> groupResults = entry.getValue();
            boolean anyMatched = groupResults.stream().anyMatch(r -> r.matched() && !r.errored());
            boolean anyMissed = groupResults.stream().anyMatch(r -> !r.matched() && !r.errored());
            if (anyMatched && anyMissed) {
                List<String> ids = groupResults.stream().map(RuleResult::ruleId)
                        .sorted().toList();
                conflicts.add("规则组 [" + entry.getKey() + "] 内规则结论互相冲突：" + ids);
            }
        }
        conflicts.sort(Comparator.naturalOrder());
        return conflicts;
    }

    private static List<String> buildReasons(List<RuleResult> results, List<String> conflicts) {
        List<String> reasons = new ArrayList<>();
        for (RuleResult r : results) {
            String prefix = r.errored() ? "[ERROR:" + r.errorSource() + "] "
                    : r.matched() ? "[MATCH] " : "[MISS] ";
            reasons.add(r.ruleId() + " " + prefix + r.evidence());
        }
        reasons.addAll(conflicts);
        return reasons;
    }

    private static int rawLength(ParseOutcome parseOutcome) {
        String raw = parseOutcome.metadata().get("rawContentLength");
        if (raw == null) {
            return 0;
        }
        try {
            return Integer.parseInt(raw);
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
