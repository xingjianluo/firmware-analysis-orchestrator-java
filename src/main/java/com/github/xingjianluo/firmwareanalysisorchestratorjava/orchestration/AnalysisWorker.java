package com.github.xingjianluo.firmwareanalysisorchestratorjava.orchestration;

import com.github.xingjianluo.firmwareanalysisorchestratorjava.model.AnalysisConclusion;
import com.github.xingjianluo.firmwareanalysisorchestratorjava.model.FailureCode;
import com.github.xingjianluo.firmwareanalysisorchestratorjava.model.ParseOutcome;
import com.github.xingjianluo.firmwareanalysisorchestratorjava.model.TaskRecord;
import com.github.xingjianluo.firmwareanalysisorchestratorjava.model.TaskStatus;
import com.github.xingjianluo.firmwareanalysisorchestratorjava.model.Verdict;
import com.github.xingjianluo.firmwareanalysisorchestratorjava.rule.RuleEvaluator;
import com.github.xingjianluo.firmwareanalysisorchestratorjava.rule.RuleRegistry;
import com.github.xingjianluo.firmwareanalysisorchestratorjava.rule.RuleSet;
import com.github.xingjianluo.firmwareanalysisorchestratorjava.sample.SampleParsingService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 任务执行单元：解析样本 -> 执行规则 -> 原子写回结果。
 * 每个关键节点都检查取消状态；已进入终态（含已取消）的任务，
 * {@link TaskRecord#complete} 会拒绝写入，保证取消后结果不回写。
 */
class AnalysisWorker implements Runnable {

    private static final Logger log = LoggerFactory.getLogger(AnalysisWorker.class);

    private final TaskRecord task;
    private final SampleParsingService parsingService;
    private final RuleRegistry ruleRegistry;
    private final RuleEvaluator ruleEvaluator;

    AnalysisWorker(TaskRecord task,
                   SampleParsingService parsingService,
                   RuleRegistry ruleRegistry,
                   RuleEvaluator ruleEvaluator) {
        this.task = task;
        this.parsingService = parsingService;
        this.ruleRegistry = ruleRegistry;
        this.ruleEvaluator = ruleEvaluator;
    }

    @Override
    public void run() {
        String taskId = task.getTaskId();
        try {
            if (!task.transitionTo(TaskStatus.RUNNING)) {
                log.info("任务 {} 在执行前已被取消或已终结，跳过执行", taskId);
                return;
            }
            log.info("任务 {} 开始执行", taskId);

            ParseOutcome parseOutcome = parsingService.parse(task.getSample());
            if (!parseOutcome.success()) {
                boolean written = task.complete(parseOutcome, null, TaskStatus.FAILED,
                        parseOutcome.failureCode(), parseOutcome.failureMessage(), null);
                log.info("任务 {} 解析失败终态写入 {} code={} reason={}",
                        taskId, written ? "成功" : "被拒绝（任务已取消）",
                        parseOutcome.failureCode(), parseOutcome.failureMessage());
                return;
            }

            // 解析成功后、规则执行前再确认一次取消状态
            if (task.getStatus() != TaskStatus.RUNNING) {
                log.info("任务 {} 在规则执行前已不处于 RUNNING，放弃写回", taskId);
                return;
            }

            RuleSet ruleSet = ruleRegistry.current();
            AnalysisConclusion conclusion = ruleEvaluator.evaluate(parseOutcome, ruleSet);

            TaskStatus terminal = conclusion.verdict() == Verdict.PARTIAL
                    ? TaskStatus.PARTIAL_FAILED
                    : TaskStatus.COMPLETED;
            FailureCode code = conclusion.verdict() == Verdict.PARTIAL
                    ? FailureCode.RULE_EXECUTION_ERROR : null;
            String message = conclusion.verdict() == Verdict.PARTIAL
                    ? "存在规则执行失败，任务标记为部分失败，结论不视为通过" : null;

            boolean written = task.complete(parseOutcome, conclusion, terminal,
                    code, message, ruleSet.version());
            log.info("任务 {} 执行结束终态写入 {} verdict={} ruleVersion={}",
                    taskId, written ? "成功" : "被拒绝（任务已取消）",
                    conclusion.verdict(), ruleSet.version());
        } catch (RuntimeException e) {
            // 执行管线本身的兜底：收敛成 FAILED，不向调用方透出堆栈
            log.error("任务 {} 执行管线出现未预期异常", taskId, e);
            boolean written = task.complete(null, null, TaskStatus.FAILED,
                    FailureCode.RULE_EXECUTION_ERROR,
                    "任务执行发生内部错误，已收敛处理：" + e.getClass().getSimpleName(), null);
            log.info("任务 {} 异常兜底终态写入 {}", taskId, written);
        }
    }
}
