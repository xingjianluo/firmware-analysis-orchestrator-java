package com.github.xingjianluo.firmwareanalysisorchestratorjava.orchestration;

import com.github.xingjianluo.firmwareanalysisorchestratorjava.model.TaskRecord;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 内存任务存储。putIfAbsent 语义保证同一 taskId 只有一个任务实体，
 * 重复提交拿到的是同一个对象，绝不会触发第二次执行。
 */
@Component
public class TaskStore {

    private final ConcurrentHashMap<String, TaskRecord> tasks = new ConcurrentHashMap<>();

    /** 不存在则放入，返回 true 表示本次是新任务。 */
    public boolean register(TaskRecord record) {
        return tasks.putIfAbsent(record.getTaskId(), record) == null;
    }

    public Optional<TaskRecord> find(String taskId) {
        return Optional.ofNullable(tasks.get(taskId));
    }

    public int size() {
        return tasks.size();
    }
}
