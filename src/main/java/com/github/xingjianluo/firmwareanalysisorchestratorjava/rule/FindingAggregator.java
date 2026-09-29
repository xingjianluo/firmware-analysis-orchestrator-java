package com.github.xingjianluo.firmwareanalysisorchestratorjava.rule;

import com.github.xingjianluo.firmwareanalysisorchestratorjava.model.RuleFinding;
import com.github.xingjianluo.firmwareanalysisorchestratorjava.model.RuleVerdict;
import com.github.xingjianluo.firmwareanalysisorchestratorjava.model.TaskConclusion;
import org.springframework.stereotype.Component;

import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 规则结论汇总，判定顺序固定，保证结论稳定可解释：
 * <ol>
 *   <li>任一条规则 ERROR ——&gt; PARTIAL（部分失败，绝不算通过）</li>
 *   <li>同一 subject 下同时出现 FAIL 与 PASS ——&gt; CONFLICT（规则互相冲突）</li>
 *   <li>否则按 FAIL &gt; WARN &gt; PASS 的稳定优先级汇总</li>
 * </ol>
 * 规则缺失（没有任何 finding）得到 NONE，由编排层按空结果处理。
 */
@Component
public class FindingAggregator implements RuleAggregator {

    @Override
    public TaskConclusion aggregate(List<RuleFinding> findings) {
        if (findings == null || findings.isEmpty()) {
            return TaskConclusion.NONE;
        }

        boolean hasError = findings.stream().anyMatch(f -> f.verdict() == RuleVerdict.ERROR);
        if (hasError) {
            return TaskConclusion.PARTIAL;
        }

        Map<String, Set<RuleVerdict>> bySubject = new HashMap<>();
        for (RuleFinding f : findings) {
            bySubject.computeIfAbsent(f.subject(), k -> EnumSet.noneOf(RuleVerdict.class))
                    .add(f.verdict());
        }
        for (var entry : bySubject.entrySet()) {
            Set<RuleVerdict> verdicts = entry.getValue();
            if (verdicts.contains(RuleVerdict.FAIL) && verdicts.contains(RuleVerdict.PASS)) {
                return TaskConclusion.CONFLICT;
            }
        }

        if (findings.stream().anyMatch(f -> f.verdict() == RuleVerdict.FAIL)) {
            return TaskConclusion.FAIL;
        }
        if (findings.stream().anyMatch(f -> f.verdict() == RuleVerdict.WARN)) {
            return TaskConclusion.WARN;
        }
        return TaskConclusion.PASS;
    }
}
