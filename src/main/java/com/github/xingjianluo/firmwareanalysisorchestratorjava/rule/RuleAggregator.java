package com.github.xingjianluo.firmwareanalysisorchestratorjava.rule;

import com.github.xingjianluo.firmwareanalysisorchestratorjava.model.ParsedFirmware;
import com.github.xingjianluo.firmwareanalysisorchestratorjava.model.RuleFinding;
import com.github.xingjianluo.firmwareanalysisorchestratorjava.model.TaskConclusion;

import java.util.List;

/** 汇总多条规则的执行记录，产出稳定可解释的最终结论。 */
public interface RuleAggregator {
    TaskConclusion aggregate(List<RuleFinding> findings);
}
