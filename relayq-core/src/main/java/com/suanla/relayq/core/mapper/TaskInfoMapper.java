package com.suanla.relayq.core.mapper;

import com.suanla.relayq.core.domain.FailureKind;
import com.suanla.relayq.core.domain.TaskInfo;
import com.suanla.relayq.core.domain.TaskStatus;
import com.suanla.relayq.core.metrics.TaskStatusCount;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;

/** Internal storage contract; no ORM dependency. Single-row lookups return null when absent. */
public interface TaskInfoMapper {

    TaskInfo selectById(long id);

    int insertForSubmission(
            TaskInfo task,
            LocalDateTime absoluteScheduledTime,
            Long delaySeconds);

    List<Long> selectDueIdsForUpdateSkipLocked(int batchSize);

    int markRunning(
            Collection<Long> ids,
            String instanceId,
            long leaseSeconds);

    List<TaskInfo> selectByIdList(Collection<Long> ids);

    int finishWithFencing(
            long id,
            TaskStatus status,
            String errorMsg,
            FailureKind lastFailureKind,
            String owner);

    int requeueForRetry(
            long id,
            long delayMillis,
            String errorMsg,
            FailureKind failureKind,
            String owner);

    int requeueRejected(Collection<Long> ids, String owner);

    List<Long> selectExpiredLeaseIds(int limit);

    int reclaimExpiredLeasesByIds(Collection<Long> ids);

    int renewLease(
            Collection<Long> ids,
            String owner,
            long leaseSeconds);

    int cancelPending(long id);

    int redriveDeadLetter(
            long id,
            String redriveBy,
            String redriveReason);

    TaskInfo selectByBizKey(String bizKey);

    List<TaskInfo> selectPageByStatus(
            TaskStatus status,
            long offset,
            int limit);

    long countByStatus(TaskStatus status);

    List<TaskStatusCount> countActionableBacklog();
}
