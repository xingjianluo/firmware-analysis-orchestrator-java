package com.github.xingjianluo.firmwareanalysisorchestratorjava.service;

import com.github.xingjianluo.firmwareanalysisorchestratorjava.model.AnalysisTask;

import java.util.List;
import java.util.Optional;

/** 任务存储（内存实现）。 */
public interface TaskRepository {
    void save(AnalysisTask task);
    Optional<AnalysisTask> findById(String taskId);
    Optional<AnalysisTask> findByContentHash(String contentHash);
    List<AnalysisTask> findAll();
}
