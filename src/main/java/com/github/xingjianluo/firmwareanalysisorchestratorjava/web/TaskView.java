package com.github.xingjianluo.firmwareanalysisorchestratorjava.web;

import com.github.xingjianluo.firmwareanalysisorchestratorjava.model.AnalysisConclusion;
import com.github.xingjianluo.firmwareanalysisorchestratorjava.model.ParseOutcome;
import com.github.xingjianluo.firmwareanalysisorchestratorjava.model.RuleResult;
import com.github.xingjianluo.firmwareanalysisorchestratorjava.model.TaskSnapshot;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 对外的任务视图，字段均为稳定可序列化结构。
 */
public record TaskView(String taskId,
                       String status,
                       boolean accepted,
                       boolean duplicate,
                       String detectedFormat,
                       boolean parseSuccess,
                       String failureCode,
                       String failureMessage,
                       String verdict,
                       Integer ruleVersion,
                       List<Map<String, Object>> rules,
                       List<String> conflicts,
                       List<String> reasons) {

    public static TaskView from(TaskSnapshot s) {
        ParseOutcome parse = s.parseOutcome();
        AnalysisConclusion conclusion = s.conclusion();

        String detectedFormat = parse == null ? null : parse.detectedFormat();
        boolean parseSuccess = parse != null && parse.success();
        String failureCode = s.failureCode() == null ? null : s.failureCode().name();
        String verdict = conclusion == null ? null : conclusion.verdict().name();

        List<Map<String, Object>> rules = new ArrayList<>();
        List<String> conflicts = List.of();
        List<String> reasons = List.of();
        if (conclusion != null) {
            for (RuleResult r : conclusion.results()) {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("ruleId", r.ruleId());
                item.put("group", r.group());
                item.put("matched", r.matched());
                item.put("errored", r.errored());
                item.put("evidence", r.evidence());
                item.put("errorSource", r.errorSource());
                rules.add(item);
            }
            conflicts = conclusion.conflicts();
            reasons = conclusion.reasons();
        }
        return new TaskView(s.taskId(), s.status().name(), s.accepted(), s.duplicate(),
                detectedFormat, parseSuccess, failureCode, s.failureMessage(),
                verdict, s.ruleVersion(), List.copyOf(rules),
                List.copyOf(conflicts), List.copyOf(reasons));
    }
}
