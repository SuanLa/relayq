package com.suanla.relayq.core.snapshot;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.suanla.relayq.core.config.RelayqProperties;
import com.suanla.relayq.core.domain.TaskSnapshot;
import com.suanla.relayq.core.mapper.TaskSnapshotMapper;
import com.suanla.relayq.core.support.ByteTruncator;
import com.suanla.relayq.core.support.SnowflakeIdGenerator;
import lombok.extern.slf4j.Slf4j;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Objects;

@Slf4j
public class SnapshotWriter {

    private final TaskSnapshotMapper taskSnapshotMapper;
    private final SnowflakeIdGenerator idGenerator;
    private final ObjectMapper objectMapper;
    private final int threadDumpMaxBytes;
    private final Clock clock;

    public SnapshotWriter(
            TaskSnapshotMapper taskSnapshotMapper,
            SnowflakeIdGenerator idGenerator,
            ObjectMapper objectMapper,
            RelayqProperties.Snapshot properties) {
        this(
                taskSnapshotMapper,
                idGenerator,
                objectMapper,
                properties,
                Clock.systemDefaultZone());
    }

    SnapshotWriter(
            TaskSnapshotMapper taskSnapshotMapper,
            SnowflakeIdGenerator idGenerator,
            ObjectMapper objectMapper,
            RelayqProperties.Snapshot properties,
            Clock clock) {
        this.taskSnapshotMapper = Objects.requireNonNull(
                taskSnapshotMapper, "taskSnapshotMapper must not be null");
        this.idGenerator = Objects.requireNonNull(idGenerator, "idGenerator must not be null");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper must not be null");
        Objects.requireNonNull(properties, "snapshot properties must not be null");
        if (properties.getThreadDumpMaxBytes() < 0) {
            throw new IllegalArgumentException(
                    "snapshot threadDumpMaxBytes must not be negative");
        }
        this.threadDumpMaxBytes = properties.getThreadDumpMaxBytes();
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
    }

    public boolean write(SnapshotCapture capture) {
        Objects.requireNonNull(capture, "capture must not be null");
        SnapshotTrigger trigger = capture.trigger();
        try {
            ByteTruncator.Result threadDump = ByteTruncator.truncate(
                    capture.threadDump(),
                    threadDumpMaxBytes);
            TaskSnapshot snapshot = new TaskSnapshot();
            snapshot.setId(idGenerator.nextId());
            snapshot.setTaskId(trigger.taskId());
            snapshot.setExecuteLogId(trigger.executeLogId());
            snapshot.setAttemptNo(trigger.attemptNo());
            snapshot.setTriggerType(trigger.triggerType());
            snapshot.setThreadDump(threadDump.text());
            snapshot.setThreadDumpOriginalLen(
                    threadDump.truncated() ? threadDump.originalByteLength() : null);
            snapshot.setLockInfoIncluded(capture.lockInfoIncluded());
            snapshot.setPoolState(toJson(capture.poolState()));
            snapshot.setHeapState(toJson(capture.heapState()));
            snapshot.setBacklogCount(capture.backlogCount());
            // created_at 是纯审计列，不参与数据库 NOW(3) 比较，可以使用应用时间。
            snapshot.setCreatedAt(LocalDateTime.now(clock));
            int affected = taskSnapshotMapper.insert(snapshot);
            if (affected == 1) {
                return true;
            }
            log.error(
                    "Task snapshot insert affected an unexpected number of rows: taskId={}, attemptNo={}, affected={}",
                    trigger.taskId(),
                    trigger.attemptNo(),
                    affected);
            return false;
        } catch (RuntimeException | JsonProcessingException error) {
            // 观察者写库失败只能降级为日志与 failed 指标，绝不能污染业务线程。
            log.error(
                    "Failed to persist task snapshot: taskId={}, attemptNo={}, triggerType={}",
                    trigger.taskId(),
                    trigger.attemptNo(),
                    trigger.triggerType(),
                    error);
            return false;
        }
    }

    private String toJson(Object value) throws JsonProcessingException {
        return objectMapper.writeValueAsString(value);
    }
}
