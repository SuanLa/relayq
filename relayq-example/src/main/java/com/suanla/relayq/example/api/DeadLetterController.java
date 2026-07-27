package com.suanla.relayq.example.api;

import com.suanla.relayq.core.domain.TaskInfo;
import com.suanla.relayq.core.service.DeadLetterService;
import com.suanla.relayq.core.service.PageResult;
import com.suanla.relayq.core.service.TaskQueryService;
import com.suanla.relayq.example.api.dto.RedriveRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Validated
@RestController
@RequestMapping("/api/dead-letters")
public class DeadLetterController {

    private final DeadLetterService deadLetterService;
    private final TaskQueryService taskQueryService;

    public DeadLetterController(
            DeadLetterService deadLetterService,
            TaskQueryService taskQueryService) {
        this.deadLetterService = deadLetterService;
        this.taskQueryService = taskQueryService;
    }

    @GetMapping
    public PageResult<TaskInfo> list(
            @RequestParam(defaultValue = "1")
            @Min(value = 1, message = "page must be at least 1") long page,
            @RequestParam(defaultValue = "20")
            @Min(value = 1, message = "size must be at least 1")
            @Max(value = 100, message = "size must not exceed 100") int size) {
        return deadLetterService.listDeadLetters(page, size);
    }

    @PostMapping("/{id}/redrive")
    public TaskInfo redrive(
            @PathVariable long id,
            @Valid @RequestBody RedriveRequest request) {
        deadLetterService.redrive(id, request.getRedriveBy(), request.getRedriveReason());
        return taskQueryService.getById(id);
    }
}
