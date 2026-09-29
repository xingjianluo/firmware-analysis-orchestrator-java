package com.github.xingjianluo.firmwareanalysisorchestratorjava.rule;

import java.util.List;

/** 规则注册表：支持取当前规则快照、替换规则（版本递增）。 */
public interface RuleRegistry {
    long ruleSetVersion();
    List<AnalysisRule> snapshot();
    void replaceAll(List<AnalysisRule> rules);
}
