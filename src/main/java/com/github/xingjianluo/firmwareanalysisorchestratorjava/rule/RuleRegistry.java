package com.github.xingjianluo.firmwareanalysisorchestratorjava.rule;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 规则版本注册中心：保存每一版不可变规则集。
 * 更新规则只会新增版本，绝不覆盖历史版本，保证历史结论可复现。
 */
@Component
public class RuleRegistry {

    private final ConcurrentHashMap<Integer, RuleSet> versions = new ConcurrentHashMap<>();
    private final AtomicInteger currentVersion = new AtomicInteger();

    public RuleRegistry() {
        registerInitial(new RuleSet(1, List.copyOf(BuiltInRules.version1())));
    }

    /** 测试用：允许从指定初始版本起步。 */
    RuleRegistry(int initialVersion, List<AnalysisRule> initialRules) {
        registerInitial(new RuleSet(initialVersion, List.copyOf(initialRules)));
    }

    private void registerInitial(RuleSet initial) {
        versions.put(initial.version(), initial);
        currentVersion.set(initial.version());
    }

    /** 用给定规则替换当前规则集，生成新版本并返回。 */
    public synchronized RuleSet updateRules(List<AnalysisRule> rules) {
        Objects.requireNonNull(rules, "rules");
        int next = currentVersion.incrementAndGet();
        RuleSet ruleSet = new RuleSet(next, List.copyOf(rules));
        versions.put(next, ruleSet);
        return ruleSet;
    }

    public RuleSet current() {
        return versions.get(currentVersion.get());
    }

    public int currentVersionNumber() {
        return currentVersion.get();
    }

    public Optional<RuleSet> get(int version) {
        return Optional.ofNullable(versions.get(version));
    }
}
