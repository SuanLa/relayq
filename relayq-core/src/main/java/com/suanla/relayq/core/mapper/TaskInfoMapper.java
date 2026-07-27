package com.suanla.relayq.core.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.suanla.relayq.core.domain.FailureKind;
import com.suanla.relayq.core.domain.TaskInfo;
import com.suanla.relayq.core.domain.TaskStatus;
import com.suanla.relayq.core.metrics.TaskStatusCount;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;

public interface TaskInfoMapper extends BaseMapper<TaskInfo> {

    int insertForSubmission(
            @Param("task") TaskInfo task,
            @Param("absoluteScheduledTime") LocalDateTime absoluteScheduledTime,
            @Param("delaySeconds") Long delaySeconds);

    List<Long> selectDueIdsForUpdateSkipLocked(@Param("batchSize") int batchSize);

    int markRunning(
            @Param("ids") Collection<Long> ids,
            @Param("instanceId") String instanceId,
            @Param("leaseSeconds") long leaseSeconds);

    // 不复用 BaseMapper.selectByIds，避免与 MyBatis-Plus 内置注入方法撞 statement id。
    List<TaskInfo> selectByIdList(@Param("ids") Collection<Long> ids);

    int finishWithFencing(
            @Param("id") long id,
            @Param("status") TaskStatus status,
            @Param("errorMsg") String errorMsg,
            @Param("lastFailureKind") FailureKind lastFailureKind,
            @Param("owner") String owner);

    int requeueForRetry(
            @Param("id") long id,
            @Param("delayMillis") long delayMillis,
            @Param("errorMsg") String errorMsg,
            @Param("failureKind") FailureKind failureKind,
            @Param("owner") String owner);

    int requeueRejected(@Param("ids") Collection<Long> ids, @Param("owner") String owner);

    int reclaimExpiredLeases(@Param("limit") int limit);

    int renewLease(
            @Param("ids") Collection<Long> ids,
            @Param("owner") String owner,
            @Param("leaseSeconds") long leaseSeconds);

    int cancelPending(@Param("id") long id);

    int redriveDeadLetter(
            @Param("id") long id,
            @Param("redriveBy") String redriveBy,
            @Param("redriveReason") String redriveReason);

    TaskInfo selectByBizKey(@Param("bizKey") String bizKey);

    List<TaskInfo> selectPageByStatus(
            @Param("status") TaskStatus status,
            @Param("offset") long offset,
            @Param("limit") int limit);

    long countByStatus(@Param("status") TaskStatus status);

    List<TaskStatusCount> countGroupedByStatus();
}
