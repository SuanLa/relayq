package com.suanla.relayq.core.service;

import com.suanla.relayq.core.domain.TaskExecuteLog;
import com.suanla.relayq.core.domain.TaskInfo;
import com.suanla.relayq.core.domain.TaskSnapshot;
import com.suanla.relayq.core.domain.TaskStatus;
import com.suanla.relayq.core.exception.TaskNotFoundException;
import com.suanla.relayq.core.mapper.TaskExecuteLogMapper;
import com.suanla.relayq.core.mapper.TaskInfoMapper;
import com.suanla.relayq.core.mapper.TaskSnapshotMapper;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

public class TaskQueryService {

    private final TaskInfoMapper taskInfoMapper;
    private final TaskExecuteLogMapper taskExecuteLogMapper;
    private final TaskSnapshotMapper taskSnapshotMapper;

    public TaskQueryService(
            TaskInfoMapper taskInfoMapper,
            TaskExecuteLogMapper taskExecuteLogMapper,
            TaskSnapshotMapper taskSnapshotMapper) {
        this.taskInfoMapper = Objects.requireNonNull(taskInfoMapper, "taskInfoMapper must not be null");
        this.taskExecuteLogMapper = Objects.requireNonNull(
                taskExecuteLogMapper, "taskExecuteLogMapper must not be null");
        this.taskSnapshotMapper = Objects.requireNonNull(
                taskSnapshotMapper, "taskSnapshotMapper must not be null");
    }

    public TaskInfo getById(long id) {
        TaskInfo task = taskInfoMapper.selectById(id);
        if (task == null) {
            throw new TaskNotFoundException(id);
        }
        return task;
    }

    public Optional<TaskInfo> findByBizKey(String bizKey) {
        return Optional.ofNullable(taskInfoMapper.selectByBizKey(bizKey));
    }

    /**
     * 使用简单 offset/limit，避免 core 强依赖分页拦截器；status + created_at 可直接利用 idx_status_created。
     */
    public PageResult<TaskInfo> pageByStatus(TaskStatus status, long pageNumber, int pageSize) {
        Objects.requireNonNull(status, "status must not be null");
        validatePage(pageNumber, pageSize);
        long offset;
        try {
            offset = Math.multiplyExact(pageNumber - 1L, pageSize);
        } catch (ArithmeticException error) {
            throw new IllegalArgumentException(
                    "page offset overflow: pageNumber=" + pageNumber + ", pageSize=" + pageSize,
                    error);
        }
        List<TaskInfo> records = taskInfoMapper.selectPageByStatus(status, offset, pageSize);
        return new PageResult<>(records, taskInfoMapper.countByStatus(status), pageNumber, pageSize);
    }

    public List<TaskExecuteLog> listExecuteLogs(long taskId) {
        return taskExecuteLogMapper.selectByTaskId(taskId);
    }

    public List<TaskSnapshot> listSnapshots(long taskId) {
        return taskSnapshotMapper.selectByTaskId(taskId);
    }

    public long countByStatus(TaskStatus status) {
        return taskInfoMapper.countByStatus(Objects.requireNonNull(status, "status must not be null"));
    }

    private void validatePage(long pageNumber, int pageSize) {
        if (pageNumber < 1) {
            throw new IllegalArgumentException("pageNumber must be at least 1: " + pageNumber);
        }
        if (pageSize < 1) {
            throw new IllegalArgumentException("pageSize must be at least 1: " + pageSize);
        }
    }
}
