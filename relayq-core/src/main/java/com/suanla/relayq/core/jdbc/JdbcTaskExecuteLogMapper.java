package com.suanla.relayq.core.jdbc;

import com.suanla.relayq.core.domain.ExecuteOutcome;
import com.suanla.relayq.core.domain.FailureKind;
import com.suanla.relayq.core.domain.TaskExecuteLog;
import com.suanla.relayq.core.mapper.TaskExecuteLogMapper;
import org.springframework.jdbc.core.RowMapper;

import javax.sql.DataSource;
import java.time.LocalDateTime;
import java.util.List;

/** MySQL storage using Spring-bound connections, parameter binding and exception translation. */
public class JdbcTaskExecuteLogMapper extends JdbcStoreSupport implements TaskExecuteLogMapper {

    private static final RowMapper<TaskExecuteLog> ROW_MAPPER = (rs, rowNum) -> {
        TaskExecuteLog record = new TaskExecuteLog();
        record.setId(rs.getObject("id", Long.class));
        record.setTaskId(rs.getObject("task_id", Long.class));
        record.setAttemptNo(rs.getObject("attempt_no", Integer.class));
        record.setStartTime(rs.getObject("start_time", LocalDateTime.class));
        record.setEndTime(rs.getObject("end_time", LocalDateTime.class));
        record.setOutcome(enumValue(rs, "outcome", ExecuteOutcome.class));
        record.setFailureKind(enumValue(rs, "failure_kind", FailureKind.class));
        record.setErrorStack(rs.getString("error_stack"));
        record.setErrorStackOriginalLen(rs.getObject("error_stack_original_len", Integer.class));
        record.setWorkerInstance(rs.getString("worker_instance"));
        record.setTraceId(rs.getString("trace_id"));
        record.setCreatedAt(rs.getObject("created_at", LocalDateTime.class));
        return record;
    };

    public JdbcTaskExecuteLogMapper(DataSource dataSource) {
        super(dataSource);
    }

    @Override
    public int insert(TaskExecuteLog record) {
        return insert("task_execute_log", parameters(
                "id", record.getId(),
                "task_id", record.getTaskId(),
                "attempt_no", record.getAttemptNo(),
                "start_time", record.getStartTime(),
                "end_time", record.getEndTime(),
                "outcome", record.getOutcome(),
                "failure_kind", record.getFailureKind(),
                "error_stack", record.getErrorStack(),
                "error_stack_original_len", record.getErrorStackOriginalLen(),
                "worker_instance", record.getWorkerInstance(),
                "trace_id", record.getTraceId(),
                "created_at", record.getCreatedAt()));
    }

    @Override
    public TaskExecuteLog selectByTaskAndAttempt(long taskId, int attemptNo) {
        String sql = """
                SELECT id, task_id, attempt_no, start_time, end_time, outcome, failure_kind,
                error_stack, error_stack_original_len, worker_instance, trace_id, created_at
                FROM task_execute_log
                WHERE task_id = :taskId
                    AND attempt_no = :attemptNo
                """;
        return one(sql, parameters("taskId", taskId, "attemptNo", attemptNo), ROW_MAPPER);
    }

    @Override
    public List<TaskExecuteLog> selectByTaskId(long taskId) {
        String sql = """
                SELECT id, task_id, attempt_no, start_time, end_time, outcome, failure_kind,
                error_stack, error_stack_original_len, worker_instance, trace_id, created_at
                FROM task_execute_log
                WHERE task_id = :taskId
                ORDER BY attempt_no, id
                """;
        return jdbc.query(sql, parameters("taskId", taskId), ROW_MAPPER);
    }
}
