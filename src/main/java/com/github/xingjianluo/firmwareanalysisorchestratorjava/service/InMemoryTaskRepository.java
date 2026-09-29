package com.github.xingjianluo.firmwareanalysisorchestratorjava.service;

import com.github.xingjianluo.firmwareanalysisorchestratorjava.model.AnalysisTask;
import org.springframework.stereotype.Repository;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/** 内存任务仓储，基于 {@link ConcurrentHashMap}，提交与查询可安全并发。 */
@Repository
public class InMemoryTaskRepository implements TaskRepository {

    private final ConcurrentMap<String, AnalysisTask> byId = new ConcurrentHashMap<>();

    @Override
    public void save(AnalysisTask task) {
        byId.put(task.getTaskId(), task);
    }

    @Override
    public Optional<AnalysisTask> findById(String taskId) {
        return Optional.ofNullable(byId.get(taskId));
    }

    @Override
    public Optional<AnalysisTask> findByContentHash(String contentHash) {
        return byId.values().stream()
                .filter(t -> contentHash.equals(t.getContentHash()))
                .findFirst();
    }

    @Override
    public List<AnalysisTask> findAll() {
        return byId.values().stream()
                .sorted(Comparator.comparing(AnalysisTask::getCreatedAt))
                .toList();
    }
}
