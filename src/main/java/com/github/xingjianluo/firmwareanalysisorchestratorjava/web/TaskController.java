package com.github.xingjianluo.firmwareanalysisorchestratorjava.web;

import com.github.xingjianluo.firmwareanalysisorchestratorjava.model.SampleSubmission;
import com.github.xingjianluo.firmwareanalysisorchestratorjava.service.ReplayReport;
import com.github.xingjianluo.firmwareanalysisorchestratorjava.service.TaskService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.Base64;
import java.util.List;

/** 任务编排 REST 接口。 */
@RestController
@RequestMapping("/api/tasks")
public class TaskController {

    private final TaskService taskService;

    public TaskController(TaskService taskService) {
        this.taskService = taskService;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.ACCEPTED)
    public TaskView submit(@RequestBody SubmitRequest request) {
        if (request == null || request.getContentBase64() == null) {
            throw new IllegalArgumentException("contentBase64 不能为空");
        }
        byte[] content;
        try {
            content = Base64.getDecoder().decode(request.getContentBase64());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("contentBase64 不是合法的 Base64 编码");
        }
        return TaskView.from(taskService.submit(
                new SampleSubmission(request.getFileName(), content),
                request.getIdempotencyKey()));
    }

    @GetMapping
    public List<TaskView> list() {
        return taskService.list().stream().map(TaskView::from).toList();
    }

    @GetMapping("/{taskId}")
    public TaskView get(@PathVariable String taskId) {
        return TaskView.from(taskService.get(taskId));
    }

    @PostMapping("/{taskId}/cancel")
    public TaskView cancel(@PathVariable String taskId) {
        return TaskView.from(taskService.cancel(taskId));
    }

    @PostMapping("/{taskId}/replay")
    public ReplayReport replay(@PathVariable String taskId) {
        return taskService.replay(taskId);
    }
}
