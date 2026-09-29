package com.github.xingjianluo.firmwareanalysisorchestratorjava.web;

import com.github.xingjianluo.firmwareanalysisorchestratorjava.model.SampleDescriptor;
import com.github.xingjianluo.firmwareanalysisorchestratorjava.model.TaskSnapshot;
import com.github.xingjianluo.firmwareanalysisorchestratorjava.orchestration.ReproductionResult;
import com.github.xingjianluo.firmwareanalysisorchestratorjava.orchestration.TaskOrchestrator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 任务 REST 接口：
 * POST /api/tasks              提交（重复 taskId 返回 200 + duplicate=true，不重复执行）；
 * POST /api/tasks/{id}/cancel  取消；
 * GET  /api/tasks/{id}         查询；
 * POST /api/tasks/{id}/reproduce 按固化规则版本复现结论。
 */
@RestController
@RequestMapping("/api/tasks")
public class TaskController {

    private static final Logger log = LoggerFactory.getLogger(TaskController.class);

    private final TaskOrchestrator orchestrator;

    public TaskController(TaskOrchestrator orchestrator) {
        this.orchestrator = orchestrator;
    }

    @PostMapping
    public ResponseEntity<?> submit(@RequestBody SubmitTaskRequest request) {
        if (request == null || request.taskId() == null || request.taskId().isBlank()) {
            return ResponseEntity.badRequest().body(error("INVALID_REQUEST", "taskId 不能为空"));
        }
        byte[] content;
        try {
            String base64 = request.contentBase64() == null ? "" : request.contentBase64();
            content = Base64.getDecoder().decode(base64);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest()
                    .body(error("INVALID_REQUEST", "contentBase64 不是合法的 Base64 文本"));
        }
        SampleDescriptor sample = new SampleDescriptor(request.declaredExtension(), content);
        TaskSnapshot snapshot = orchestrator.submit(request.taskId(), sample);
        HttpStatus status = snapshot.accepted() ? HttpStatus.ACCEPTED : HttpStatus.OK;
        log.info("任务提交接口 taskId={} accepted={} duplicate={} status={}",
                snapshot.taskId(), snapshot.accepted(), snapshot.duplicate(), snapshot.status());
        return ResponseEntity.status(status).body(TaskView.from(snapshot));
    }

    @PostMapping("/{taskId}/cancel")
    public ResponseEntity<?> cancel(@PathVariable String taskId) {
        return orchestrator.cancel(taskId)
                .<ResponseEntity<?>>map(snapshot -> {
                    log.info("任务取消接口 taskId={} status={}", taskId, snapshot.status());
                    return ResponseEntity.ok(TaskView.from(snapshot));
                })
                .orElseGet(() -> notFound(taskId));
    }

    @GetMapping("/{taskId}")
    public ResponseEntity<?> get(@PathVariable String taskId) {
        return orchestrator.get(taskId)
                .<ResponseEntity<?>>map(snapshot -> ResponseEntity.ok(TaskView.from(snapshot)))
                .orElseGet(() -> notFound(taskId));
    }

    @PostMapping("/{taskId}/reproduce")
    public ResponseEntity<?> reproduce(@PathVariable String taskId) {
        var reproduced = orchestrator.reproduce(taskId);
        if (reproduced.isEmpty()) {
            // 不存在 -> 404；存在但无固化结论 -> 409
            if (orchestrator.get(taskId).isEmpty()) {
                return notFound(taskId);
            }
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(error("NO_CONCLUSION", "任务尚无固化结论，无法复现"));
        }
        ReproductionResult r = reproduced.get();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("taskId", r.taskId());
        body.put("ruleVersion", r.ruleVersion());
        body.put("storedVerdict", r.storedVerdict().name());
        body.put("reproducedVerdict", r.reproducedVerdict().name());
        body.put("consistent", r.consistent());
        return ResponseEntity.ok(body);
    }

    private static Map<String, String> error(String code, String message) {
        return Map.of("errorCode", code, "message", message);
    }

    private static ResponseEntity<Map<String, Object>> notFound(String taskId) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(Map.of("errorCode", "TASK_NOT_FOUND", "taskId", taskId));
    }
}
