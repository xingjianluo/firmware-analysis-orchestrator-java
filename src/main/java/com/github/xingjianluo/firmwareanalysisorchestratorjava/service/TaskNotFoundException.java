package com.github.xingjianluo.firmwareanalysisorchestratorjava.service;

/** 查询/取消/复跑一个不存在的任务时抛出。 */
public class TaskNotFoundException extends RuntimeException {
    public TaskNotFoundException(String taskId) {
        super("任务不存在: " + taskId);
    }
}
