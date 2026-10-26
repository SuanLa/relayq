package com.suanla.relayq.core.jdbc;

import com.suanla.relayq.core.domain.TaskSnapshot;
import com.suanla.relayq.core.domain.TriggerType;
import com.suanla.relayq.core.mapper.TaskSnapshotMapper;
import org.springframework.jdbc.core.RowMapper;

import javax.sql.DataSource;
import java.time.LocalDateTime;
import java.util.List;

/** MySQL storage using Spring-bound connections, parameter binding and exception translation. */
public class JdbcTaskSnapshotMapper extends JdbcStoreSupport implements TaskSnapshotMapper {

    private static final RowMapper<TaskSnapshot> ROW_MAPPER = (rs, rowNum) -> {
        TaskSnapshot record = new TaskSnapshot();
        record.setId(rs.getObject("id", Long.class));
        record.setTaskId(rs.getObject("task_id", Long.class));
        record.setExecuteLogId(rs.getObject("execute_log_id", Long.class));
        record.setAttemptNo(rs.getObject("attempt_no", Integer.class));
        record.setTriggerType(enumValue(rs, "trigger_type", TriggerType.class));
        record.setThreadDump(rs.getString("thread_dump"));
        record.setThreadDumpOriginalLen(rs.getObject("thread_dump_original_len", Integer.class));
        record.setLockInfoIncluded(rs.getObject("lock_info_included", Boolean.class));
        record.setPoolState(rs.getString("pool_state"));
        record.setHeapState(rs.getString("heap_state"));
        record.setBacklogCount(rs.getObject("backlog_count", Long.class));
        record.setCreatedAt(rs.getObject("created_at", LocalDateTime.class));
        return record;
    };

    public JdbcTaskSnapshotMapper(DataSource dataSource) {
        super(dataSource);
    }

    @Override
    public int insert(TaskSnapshot record) {
        return insert("task_snapshot", parameters(
                "id", record.getId(),
                "task_id", record.getTaskId(),
                "execute_log_id", record.getExecuteLogId(),
                "attempt_no", record.getAttemptNo(),
                "trigger_type", record.getTriggerType(),
                "thread_dump", record.getThreadDump(),
                "thread_dump_original_len", record.getThreadDumpOriginalLen(),
                "lock_info_included", record.getLockInfoIncluded(),
                "pool_state", record.getPoolState(),
                "heap_state", record.getHeapState(),
                "backlog_count", record.getBacklogCount(),
                "created_at", record.getCreatedAt()));
    }

    @Override
    public List<TaskSnapshot> selectByTaskId(long taskId) {
        String sql = """
                SELECT id, task_id, execute_log_id, attempt_no, trigger_type,
                thread_dump, thread_dump_original_len, lock_info_included,
                pool_state, heap_state, backlog_count, created_at
                FROM task_snapshot
                WHERE task_id = :taskId
                ORDER BY created_at, id
                """;
        return jdbc.query(sql, parameters("taskId", taskId), ROW_MAPPER);
    }
}
