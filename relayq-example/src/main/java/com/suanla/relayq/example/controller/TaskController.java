package com.suanla.relayq.example.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.suanla.relayq.core.domain.TaskExecuteLog;
import com.suanla.relayq.core.domain.TaskInfo;
import com.suanla.relayq.core.domain.TaskSnapshot;
import com.suanla.relayq.core.domain.TaskStatus;
import com.suanla.relayq.core.service.PageResult;
import com.suanla.relayq.core.service.SubmitCommand;
import com.suanla.relayq.core.service.SubmitResult;
import com.suanla.relayq.core.service.TaskQueryService;
import com.suanla.relayq.core.service.TaskStateMachine;
import com.suanla.relayq.core.service.TaskSubmitService;
import com.suanla.relayq.core.support.TraceContext;
import com.suanla.relayq.example.exception.ApiResourceNotFoundException;
import com.suanla.relayq.example.component.TraceIdFilter;
import com.suanla.relayq.example.dto.SubmitTaskRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@Slf4j
@Validated
@RestController
@RequestMapping("/api/tasks")
public class TaskController {

    private final TaskSubmitService taskSubmitService;
    private final TaskQueryService taskQueryService;
    private final TaskStateMachine taskStateMachine;

    public TaskController(
            TaskSubmitService taskSubmitService,
            TaskQueryService taskQueryService,
            TaskStateMachine taskStateMachine) {
        this.taskSubmitService = taskSubmitService;
        this.taskQueryService = taskQueryService;
        this.taskStateMachine = taskStateMachine;
    }

    @PostMapping
    public ResponseEntity<SubmitResult> submit(@Valid @RequestBody SubmitTaskRequest request) {
        JsonNode params = request.getParams();
        SubmitResult result = taskSubmitService.submit(new SubmitCommand(
                request.getBizKey(),
                request.getHandlerName(),
                params == null || params.isNull() ? null : params.toString(),
                request.getScheduledTime(),
                request.getDelaySeconds(),
                request.getMaxRetry()));
        HttpStatus status = result.alreadyExists() ? HttpStatus.OK : HttpStatus.CREATED;
        String taskTraceId = taskQueryService.getById(result.taskId()).getTraceId();
        try (TraceContext.Scope ignored = TraceContext.withTraceId(taskTraceId)) {
            log.info(
                    "Task submission API completed: taskId={}, bizKey={}, alreadyExists={}",
                    result.taskId(),
                    result.bizKey(),
                    result.alreadyExists());
        }
        return ResponseEntity.status(status)
                .header(TraceIdFilter.TRACE_ID_HEADER, taskTraceId)
                .body(result);
    }

    @GetMapping("/{id}")
    public TaskInfo getById(@PathVariable long id) {
        return taskQueryService.getById(id);
    }

    @GetMapping
    public PageResult<TaskInfo> listByStatus(
            @RequestParam @NotNull(message = "status must not be null") TaskStatus status,
            @RequestParam(defaultValue = "1")
            @Min(value = 1, message = "page must be at least 1") long page,
            @RequestParam(defaultValue = "20")
            @Min(value = 1, message = "size must be at least 1")
            @Max(value = 100, message = "size must not exceed 100") int size) {
        return taskQueryService.pageByStatus(status, page, size);
    }

    @GetMapping("/by-biz-key/{bizKey}")
    public TaskInfo getByBizKey(
            @PathVariable
            @NotBlank(message = "bizKey must not be blank")
            @Size(max = 128, message = "bizKey length must not exceed 128") String bizKey) {
        return taskQueryService.findByBizKey(bizKey)
                .orElseThrow(() -> new ApiResourceNotFoundException(
                        "task not found for bizKey: " + bizKey));
    }

    @PostMapping("/{id}/cancel")
    public TaskInfo cancel(@PathVariable long id) {
        taskStateMachine.cancel(id);
        return taskQueryService.getById(id);
    }

    @GetMapping("/{id}/logs")
    public List<TaskExecuteLog> listLogs(@PathVariable long id) {
        taskQueryService.getById(id);
        return taskQueryService.listExecuteLogs(id);
    }

    @GetMapping("/{id}/snapshots")
    public List<TaskSnapshot> listSnapshots(@PathVariable long id) {
        taskQueryService.getById(id);
        return taskQueryService.listSnapshots(id);
    }
}
