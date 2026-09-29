package com.github.xingjianluo.firmwareanalysisorchestratorjava.rule;

import com.github.xingjianluo.firmwareanalysisorchestratorjava.model.RuleFinding;
import com.github.xingjianluo.firmwareanalysisorchestratorjava.model.RuleVerdict;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 规则基类：模板方法统一兜住规则内部异常，转为 ERROR 判定，
 * 这样任何一条规则执行失败都会在汇总时被标成部分失败，绝不当通过，
 * 同时底层异常只进入服务端日志，不直接透出给调用方。
 */
public abstract class AbstractAnalysisRule implements AnalysisRule {

    protected final Logger log = LoggerFactory.getLogger(getClass());

    @Override
    public final RuleFinding evaluate(RuleContext context) {
        try {
            return doEvaluate(context);
        } catch (RuntimeException e) {
            log.warn("规则执行失败 ruleId={} subject={} errorSource={} reason={}",
                    id(), subject(), e.getClass().getSimpleName(), e.getMessage());
            return new RuleFinding(id(), "v" + version(), subject(), RuleVerdict.ERROR,
                    "规则内部错误，未能完成判定",
                    e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    protected abstract RuleFinding doEvaluate(RuleContext context);

    protected RuleFinding finding(RuleVerdict verdict, String basis) {
        return new RuleFinding(id(), "v" + version(), subject(), verdict, basis, null);
    }
}
