package com.suanla.relayq.core.jdbc;

import com.suanla.relayq.core.domain.FailureKind;
import com.suanla.relayq.core.domain.TaskInfo;
import com.suanla.relayq.core.domain.TaskStatus;
import com.suanla.relayq.core.mapper.TaskInfoMapper;
import com.suanla.relayq.core.metrics.TaskStatusCount;
import org.springframework.jdbc.core.RowMapper;

import javax.sql.DataSource;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;

/** MySQL storage using Spring-bound connections, parameter binding and exception translation. */
public class JdbcTaskInfoMapper extends JdbcStoreSupport implements TaskInfoMapper {

    private static final RowMapper<TaskInfo> ROW_MAPPER = (rs, rowNum) -> {
        TaskInfo record = new TaskInfo();
        record.setId(rs.getObject("id", Long.class));
        record.setBizKey(rs.getString("biz_key"));
        record.setHandlerName(rs.getString("handler_name"));
        record.setParams(rs.getString("params"));
        record.setStatus(enumValue(rs, "status", TaskStatus.class));
        record.setScheduledTime(rs.getObject("scheduled_time", LocalDateTime.class));
        record.setRetryCount(rs.getObject("retry_count", Integer.class));
        record.setMaxRetry(rs.getObject("max_retry", Integer.class));
        record.setNextRetryTime(rs.getObject("next_retry_time", LocalDateTime.class));
        record.setLeaseOwner(rs.getString("lease_owner"));
        record.setLeaseExpireTime(rs.getObject("lease_expire_time", LocalDateTime.class));
        record.setCurrentAttemptNo(rs.getObject("current_attempt_no", Integer.class));
        record.setTraceId(rs.getString("trace_id"));
        record.setLastFailureKind(enumValue(rs, "last_failure_kind", FailureKind.class));
        record.setRedriveBy(rs.getString("redrive_by"));
        record.setRedriveReason(rs.getString("redrive_reason"));
        record.setRedriveAt(rs.getObject("redrive_at", LocalDateTime.class));
        record.setErrorMsg(rs.getString("error_msg"));
        record.setCreatedAt(rs.getObject("created_at", LocalDateTime.class));
        record.setUpdatedAt(rs.getObject("updated_at", LocalDateTime.class));
        return record;
    };

    private static final RowMapper<TaskStatusCount> STATUS_COUNT_ROW_MAPPER = (rs, rowNum) -> {
        TaskStatusCount count = new TaskStatusCount();
        count.setStatus(enumValue(rs, "status", TaskStatus.class));
        count.setCount(rs.getLong("count"));
        return count;
    };

    public JdbcTaskInfoMapper(DataSource dataSource) {
        super(dataSource);
    }

    @Override
    public TaskInfo selectById(long id) {
        String sql = """
                SELECT id, biz_key, handler_name, params, status, scheduled_time,
                    retry_count, max_retry, next_retry_time, lease_owner, lease_expire_time,
                    current_attempt_no, trace_id, last_failure_kind, redrive_by, redrive_reason,
                    redrive_at, error_msg, created_at, updated_at
                FROM task_info
                WHERE id = :id
                """;
        return one(sql, parameters("id", id), ROW_MAPPER);
    }

    @Override
    public int insertForSubmission(TaskInfo task, LocalDateTime absoluteScheduledTime, Long delaySeconds) {
        String schedule = absoluteScheduledTime != null ? ":absoluteScheduledTime"
                : delaySeconds != null ? "DATE_ADD(NOW(3), INTERVAL :delaySeconds SECOND)" : "NOW(3)";
        String sql = """
                INSERT INTO task_info (
                id, biz_key, handler_name, params, status, scheduled_time,
                retry_count, max_retry, current_attempt_no, trace_id
                )
                VALUES (
                :id,
                :bizKey,
                :handlerName,
                :params,
                :status,
                ${schedule},
                :retryCount,
                :maxRetry,
                :currentAttemptNo,
                :traceId
                )
                """.replace("${schedule}", schedule);
        return jdbc.update(sql, parameters(
                "id", task.getId(),
                "bizKey", task.getBizKey(),
                "handlerName", task.getHandlerName(),
                "params", task.getParams(),
                "status", task.getStatus(),
                "retryCount", task.getRetryCount(),
                "maxRetry", task.getMaxRetry(),
                "currentAttemptNo", task.getCurrentAttemptNo(),
                "traceId", task.getTraceId(),
                "absoluteScheduledTime", absoluteScheduledTime,
                "delaySeconds", delaySeconds));
    }

    @Override
    public List<Long> selectDueIdsForUpdateSkipLocked(int batchSize) {
        String sql = """
                SELECT id
                FROM task_info
                WHERE status = 'PENDING'
                    AND scheduled_time <= NOW(3)
                ORDER BY scheduled_time
                LIMIT :batchSize
                FOR UPDATE SKIP LOCKED
                """;
        return jdbc.queryForList(sql, parameters("batchSize", batchSize), Long.class);
    }

    @Override
    public int markRunning(Collection<Long> ids, String instanceId, long leaseSeconds) {
        if (ids.isEmpty()) {
            return 0;
        }
        String sql = """
                UPDATE task_info
                SET status = 'RUNNING',
                    lease_owner = :instanceId,
                    lease_expire_time = DATE_ADD(NOW(3), INTERVAL :leaseSeconds SECOND),
                    current_attempt_no = current_attempt_no + 1,
                    updated_at = NOW(3)
                WHERE id IN (:ids)
                    AND status = 'PENDING'
                """;
        return jdbc.update(sql, parameters("ids", ids, "instanceId", instanceId, "leaseSeconds", leaseSeconds));
    }

    @Override
    public List<TaskInfo> selectByIdList(Collection<Long> ids) {
        if (ids.isEmpty()) {
            return List.of();
        }
        String sql = """
                SELECT
                id, biz_key, handler_name, params, status, scheduled_time,
                    retry_count, max_retry, next_retry_time, lease_owner, lease_expire_time,
                    current_attempt_no, trace_id, last_failure_kind, redrive_by, redrive_reason,
                    redrive_at, error_msg, created_at, updated_at
                FROM task_info
                WHERE id IN (:ids)
                """;
        return jdbc.query(sql, parameters("ids", ids), ROW_MAPPER);
    }

    @Override
    public int finishWithFencing(long id, TaskStatus status, String errorMsg, FailureKind lastFailureKind, String owner) {
        String sql = """
                UPDATE task_info
                SET status = :status,
                    error_msg = :errorMsg,
                    last_failure_kind = :lastFailureKind,
                    next_retry_time = NULL,
                    lease_owner = NULL,
                    lease_expire_time = NULL,
                    updated_at = NOW(3)
                WHERE id = :id
                    AND status = 'RUNNING'
                    AND lease_owner = :owner
                """;
        return jdbc.update(sql, parameters(
                "id", id,
                "status", status,
                "errorMsg", errorMsg,
                "lastFailureKind", lastFailureKind,
                "owner", owner));
    }

    @Override
    public int requeueForRetry(long id, long delayMillis, String errorMsg, FailureKind failureKind, String owner) {
        String sql = """
                UPDATE task_info
                SET status = 'PENDING',
                    retry_count = retry_count + 1,
                    scheduled_time = DATE_ADD(
                    NOW(3), INTERVAL :delayMillis * 1000 MICROSECOND),
                    next_retry_time = DATE_ADD(
                    NOW(3), INTERVAL :delayMillis * 1000 MICROSECOND),
                    error_msg = :errorMsg,
                    last_failure_kind = :failureKind,
                    lease_owner = NULL,
                    lease_expire_time = NULL,
                    updated_at = NOW(3)
                WHERE id = :id
                    AND status = 'RUNNING'
                    AND lease_owner = :owner
                """;
        return jdbc.update(sql, parameters(
                "id", id,
                "delayMillis", delayMillis,
                "errorMsg", errorMsg,
                "failureKind", failureKind,
                "owner", owner));
    }

    @Override
    public int requeueRejected(Collection<Long> ids, String owner) {
        if (ids.isEmpty()) {
            return 0;
        }
        String sql = """
                UPDATE task_info
                SET status = 'PENDING',
                    lease_owner = NULL,
                    lease_expire_time = NULL,
                    updated_at = NOW(3)
                WHERE id IN (:ids)
                    AND status = 'RUNNING'
                    AND lease_owner = :owner
                """;
        return jdbc.update(sql, parameters("ids", ids, "owner", owner));
    }

    @Override
    public List<Long> selectExpiredLeaseIds(int limit) {
        String sql = """
                SELECT id
                FROM task_info FORCE INDEX (idx_status_lease)
                WHERE status = 'RUNNING'
                    AND lease_expire_time < NOW(3)
                ORDER BY lease_expire_time
                LIMIT :limit
                """;
        return jdbc.queryForList(sql, parameters("limit", limit), Long.class);
    }

    @Override
    public int reclaimExpiredLeasesByIds(Collection<Long> ids) {
        if (ids.isEmpty()) {
            return 0;
        }
        String sql = """
                UPDATE task_info FORCE INDEX (PRIMARY)
                SET status = 'PENDING',
                    lease_owner = NULL,
                    lease_expire_time = NULL,
                    updated_at = NOW(3)
                WHERE id IN (:ids)
                    AND status = 'RUNNING'
                    AND lease_expire_time < NOW(3)
                """;
        return jdbc.update(sql, parameters("ids", ids));
    }

    @Override
    public int renewLease(Collection<Long> ids, String owner, long leaseSeconds) {
        if (ids.isEmpty()) {
            return 0;
        }
        String sql = """
                UPDATE task_info
                SET lease_expire_time = DATE_ADD(NOW(3), INTERVAL :leaseSeconds SECOND),
                    updated_at = NOW(3)
                WHERE id IN (:ids)
                    AND status = 'RUNNING'
                    AND lease_owner = :owner
                """;
        return jdbc.update(sql, parameters("ids", ids, "owner", owner, "leaseSeconds", leaseSeconds));
    }

    @Override
    public int cancelPending(long id) {
        String sql = """
                UPDATE task_info
                SET status = 'CANCELLED',
                    updated_at = NOW(3)
                WHERE id = :id
                    AND status = 'PENDING'
                """;
        return jdbc.update(sql, parameters("id", id));
    }

    @Override
    public int redriveDeadLetter(long id, String redriveBy, String redriveReason) {
        String sql = """
                UPDATE task_info
                SET status = 'PENDING',
                    retry_count = 0,
                    scheduled_time = NOW(3),
                    next_retry_time = NULL,
                    lease_owner = NULL,
                    lease_expire_time = NULL,
                    redrive_by = :redriveBy,
                    redrive_reason = :redriveReason,
                    redrive_at = NOW(3),
                    updated_at = NOW(3)
                WHERE id = :id
                    AND status = 'DEAD'
                """;
        return jdbc.update(sql, parameters("id", id, "redriveBy", redriveBy, "redriveReason", redriveReason));
    }

    @Override
    public TaskInfo selectByBizKey(String bizKey) {
        String sql = """
                SELECT
                id, biz_key, handler_name, params, status, scheduled_time,
                    retry_count, max_retry, next_retry_time, lease_owner, lease_expire_time,
                    current_attempt_no, trace_id, last_failure_kind, redrive_by, redrive_reason,
                    redrive_at, error_msg, created_at, updated_at
                FROM task_info
                WHERE biz_key = :bizKey
                """;
        return one(sql, parameters("bizKey", bizKey), ROW_MAPPER);
    }

    @Override
    public List<TaskInfo> selectPageByStatus(TaskStatus status, long offset, int limit) {
        String sql = """
                SELECT
                id, biz_key, handler_name, params, status, scheduled_time,
                    retry_count, max_retry, next_retry_time, lease_owner, lease_expire_time,
                    current_attempt_no, trace_id, last_failure_kind, redrive_by, redrive_reason,
                    redrive_at, error_msg, created_at, updated_at
                FROM task_info
                WHERE status = :status
                ORDER BY created_at DESC, id DESC
                LIMIT :offset, :limit
                """;
        return jdbc.query(sql, parameters("status", status, "offset", offset, "limit", limit), ROW_MAPPER);
    }

    @Override
    public long countByStatus(TaskStatus status) {
        String sql = """
                SELECT COUNT(*)
                FROM task_info
                WHERE status = :status
                """;
        return jdbc.queryForObject(sql, parameters("status", status), Long.class);
    }

    @Override
    public List<TaskStatusCount> countActionableBacklog() {
        String sql = """
                SELECT 'PENDING' AS status, COUNT(*) AS count
                FROM task_info FORCE INDEX (idx_status_scheduled)
                WHERE status = 'PENDING'
                    AND scheduled_time <= NOW(3)
                UNION ALL
                SELECT 'RUNNING', COUNT(*)
                FROM task_info FORCE INDEX (idx_status_lease)
                WHERE status = 'RUNNING'
                UNION ALL
                SELECT 'DEAD', COUNT(*)
                FROM task_info FORCE INDEX (idx_status_created)
                WHERE status = 'DEAD'
                """;
        return jdbc.query(sql, parameters(), STATUS_COUNT_ROW_MAPPER);
    }
}
