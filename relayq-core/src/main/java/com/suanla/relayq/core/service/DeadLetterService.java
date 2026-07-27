package com.suanla.relayq.core.service;

import com.suanla.relayq.core.domain.TaskInfo;
import com.suanla.relayq.core.domain.TaskStatus;
import com.suanla.relayq.core.exception.IllegalTaskStateException;
import com.suanla.relayq.core.mapper.TaskInfoMapper;
import lombok.extern.slf4j.Slf4j;

import java.util.Objects;

@Slf4j
public class DeadLetterService {

    private final TaskInfoMapper taskInfoMapper;
    private final TaskQueryService taskQueryService;

    public DeadLetterService(TaskInfoMapper taskInfoMapper, TaskQueryService taskQueryService) {
        this.taskInfoMapper = Objects.requireNonNull(taskInfoMapper, "taskInfoMapper must not be null");
        this.taskQueryService = Objects.requireNonNull(
                taskQueryService, "taskQueryService must not be null");
    }

    public PageResult<TaskInfo> listDeadLetters(long pageNumber, int pageSize) {
        return taskQueryService.pageByStatus(TaskStatus.DEAD, pageNumber, pageSize);
    }

    public void redrive(long taskId, String redriveBy, String redriveReason) {
        int affected = taskInfoMapper.redriveDeadLetter(taskId, redriveBy, redriveReason);
        if (affected == 0) {
            TaskInfo actual = taskQueryService.getById(taskId);
            throw new IllegalTaskStateException(taskId, actual.getStatus(), "dead-letter redrive");
        }
        log.info(
                "Dead-letter task redriven: taskId={}, redriveBy={}, redriveReason={}",
                taskId,
                redriveBy,
                redriveReason);
    }
}
