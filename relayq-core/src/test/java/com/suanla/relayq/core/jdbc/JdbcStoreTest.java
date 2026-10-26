package com.suanla.relayq.core.jdbc;

import com.suanla.relayq.core.domain.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.IncorrectResultSizeDataAccessException;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.sql.*;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class JdbcStoreTest {
    private DataSource source;
    private Connection connection;
    private PreparedStatement statement;
    private ResultSet rows;
    private JdbcTaskInfoMapper tasks;

    @BeforeEach
    void setUp() throws Exception {
        source = mock(DataSource.class);
        connection = mock(Connection.class);
        statement = mock(PreparedStatement.class);
        rows = mock(ResultSet.class);
        when(source.getConnection()).thenReturn(connection);
        when(connection.prepareStatement(anyString())).thenReturn(statement);
        DatabaseMetaData metadata = mock(DatabaseMetaData.class);
        when(connection.getMetaData()).thenReturn(metadata);
        when(metadata.getDatabaseProductName()).thenReturn("MySQL");
        when(metadata.getDriverName()).thenReturn("MySQL Connector/J");
        when(statement.getConnection()).thenReturn(connection);
        ParameterMetaData parameterMetadata = mock(ParameterMetaData.class);
        when(statement.getParameterMetaData()).thenReturn(parameterMetadata);
        when(parameterMetadata.getParameterType(anyInt())).thenReturn(Types.VARCHAR);
        when(statement.executeUpdate()).thenReturn(1);
        when(statement.executeQuery()).thenReturn(rows);
        tasks = new JdbcTaskInfoMapper(source);
    }

    @Test
    void bindsEnumsAndNullsAndKeepsFencingPredicate() throws Exception {
        assertEquals(1, tasks.finishWithFencing(42, TaskStatus.SUCCESS, null, null, "owner"));
        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(connection).prepareStatement(sql.capture());
        assertTrue(sql.getValue().contains("AND status = 'RUNNING'"));
        assertTrue(sql.getValue().contains("AND lease_owner = ?"));
        assertTrue(sql.getValue().contains("lease_owner = NULL"));
        verify(statement).setString(1, "SUCCESS");
        verify(statement).setNull(eq(2), anyInt());
        verify(statement).setNull(eq(3), anyInt());
        verify(statement).setString(5, "owner");
        verify(connection).close();
    }

    @Test
    void bindsListElementsInsteadOfConcatenatingValues() throws Exception {
        tasks.markRunning(List.of(41L, 42L), "owner", 30);
        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(connection).prepareStatement(sql.capture());
        assertTrue(sql.getValue().contains("(?, ?)"));
        assertFalse(sql.getValue().contains("41"));
        verify(statement).setObject(3, 41L);
        verify(statement).setObject(4, 42L);
    }

    @Test
    void emptyBatchesNeverAcquireAConnection() {
        assertEquals(0, tasks.markRunning(List.of(), "owner", 30));
        assertEquals(0, tasks.renewLease(List.of(), "owner", 30));
        assertEquals(0, tasks.requeueRejected(List.of(), "owner"));
        assertEquals(0, tasks.reclaimExpiredLeasesByIds(List.of()));
        assertTrue(tasks.selectByIdList(List.of()).isEmpty());
        verifyNoInteractions(source);
    }

    @Test
    void missingRowsReturnNullButMultipleRowsAreNotSilentlyAccepted() throws Exception {
        assertNull(tasks.selectById(1));
        assertNull(new JdbcTaskExecuteLogMapper(source).selectByTaskAndAttempt(1, 1));
        when(rows.next()).thenReturn(true, true, false);
        assertThrows(IncorrectResultSizeDataAccessException.class, () -> tasks.selectByBizKey("key"));
    }

    @Test
    void mapsNullableFieldsEnumsAndDatabaseWallClockExplicitly() throws Exception {
        when(rows.next()).thenReturn(true, false);
        when(rows.getObject("id", Long.class)).thenReturn(7L);
        when(rows.getString("status")).thenReturn("RUNNING");
        when(rows.getString("last_failure_kind")).thenReturn("BUSINESS_ERROR");
        LocalDateTime time = LocalDateTime.of(2026, 9, 14, 12, 0, 0, 123000000);
        when(rows.getObject("scheduled_time", LocalDateTime.class)).thenReturn(time);
        TaskInfo task = tasks.selectById(7);
        assertEquals(7L, task.getId());
        assertEquals(TaskStatus.RUNNING, task.getStatus());
        assertEquals(FailureKind.BUSINESS_ERROR, task.getLastFailureKind());
        assertEquals(time, task.getScheduledTime());
        assertNull(task.getLeaseExpireTime());
        assertNull(task.getNextRetryTime());
    }

    @Test
    void nullInsertFieldsUseDatabaseDefaultsAndFalseIsNotOmitted() throws Exception {
        TaskSnapshot snapshot = new TaskSnapshot();
        snapshot.setId(1L);
        snapshot.setTriggerType(TriggerType.MANUAL);
        snapshot.setLockInfoIncluded(false);
        new JdbcTaskSnapshotMapper(source).insert(snapshot);
        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(connection).prepareStatement(sql.capture());
        assertEquals("INSERT INTO task_snapshot (id, trigger_type, lock_info_included) VALUES (?, ?, ?)",
                sql.getValue());
        verify(statement).setString(2, "MANUAL");
        verify(statement).setObject(3, false);
    }

    @Test
    void translatesMysqlDuplicateKeyWithoutAnOrm() throws Exception {
        when(statement.executeUpdate()).thenThrow(
                new SQLIntegrityConstraintViolationException("duplicate", "23000", 1062));
        TaskExecuteLog log = new TaskExecuteLog();
        log.setId(1L);
        assertThrows(DuplicateKeyException.class, () -> new JdbcTaskExecuteLogMapper(source).insert(log));
    }

    @Test
    void storesParticipateInTheSameSpringTransaction() throws Exception {
        when(connection.getAutoCommit()).thenReturn(true);
        TransactionTemplate tx = new TransactionTemplate(new DataSourceTransactionManager(source));
        tx.executeWithoutResult(status -> {
            tasks.cancelPending(1);
            TaskExecuteLog log = new TaskExecuteLog();
            log.setId(2L);
            new JdbcTaskExecuteLogMapper(source).insert(log);
            status.setRollbackOnly();
        });
        verify(source, times(1)).getConnection();
        verify(connection).rollback();
        verify(connection, never()).commit();
        verify(connection).close();
    }
}
