package com.github.xingjianluo.firmwareanalysisorchestratorjava.rule;

import com.github.xingjianluo.firmwareanalysisorchestratorjava.model.FailureReason;
import com.github.xingjianluo.firmwareanalysisorchestratorjava.sample.MalformedSampleException;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.NavigableMap;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * 内存规则注册表。
 *
 * <p>{@link #replaceAll} 替换规则集合后版本号单调递增，旧版本快照仍保留，
 * 历史任务在复跑时可凭提交时的版本号取回当时的规则集合 —— 规则更新不影响历史结论。
 */
@Component
public class InMemoryRuleRegistry implements RuleRegistry {

    private final NavigableMap<Long, List<AnalysisRule>> history = new TreeMap<>();
    private final AtomicLong versionSeq = new AtomicLong(0);
    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();

    public InMemoryRuleRegistry() {
        this(BuiltInRules.defaults());
    }

    public InMemoryRuleRegistry(List<AnalysisRule> initial) {
        long v = versionSeq.incrementAndGet();
        history.put(v, List.copyOf(initial));
    }

    @Override
    public long ruleSetVersion() {
        lock.readLock().lock();
        try {
            return history.lastKey();
        } finally {
            lock.readLock().unlock();
        }
    }

    @Override
    public List<AnalysisRule> snapshot() {
        return snapshotAt(ruleSetVersion());
    }

    @Override
    public void replaceAll(List<AnalysisRule> rules) {
        if (rules == null) {
            throw new IllegalArgumentException("规则集合不能为 null");
        }
        lock.writeLock().lock();
        try {
            long v = versionSeq.incrementAndGet();
            history.put(v, List.copyOf(rules));
        } finally {
            lock.writeLock().unlock();
        }
    }

    /** 取回指定版本的不可变规则快照；版本不存在时给出明确错误。 */
    public List<AnalysisRule> snapshotAt(long version) {
        lock.readLock().lock();
        try {
            List<AnalysisRule> rules = history.get(version);
            if (rules == null) {
                throw new MalformedSampleException(FailureReason.INTERNAL_ERROR,
                        "规则快照版本不存在: v" + version);
            }
            return rules;
        } finally {
            lock.readLock().unlock();
        }
    }
}
