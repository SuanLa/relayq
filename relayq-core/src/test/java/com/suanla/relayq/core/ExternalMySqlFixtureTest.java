package com.suanla.relayq.core;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class ExternalMySqlFixtureTest {
    private static final String PREFIX = "rqit_0123456789abcdef0123456789abcdef_";

    @Test
    void refusesMissingWriteApprovalBeforeConnecting() {
        assertThrows(IllegalStateException.class, () -> ExternalMySqlFixture.open(Map.of()));
    }

    @Test
    void requiresExplicitCredentialsAndRejectsUnsafeUrlOptions() {
        Map<String, String> env = new HashMap<>();
        env.put("RELAYQ_TEST_MYSQL_WRITE_ACK", ExternalMySqlFixture.ACKNOWLEDGEMENT);
        assertThrows(IllegalStateException.class, () -> ExternalMySqlFixture.open(env));
        env.put("RELAYQ_TEST_MYSQL_USER", "test");
        env.put("RELAYQ_TEST_MYSQL_PASSWORD", "not-a-real-password");
        for (String url : new String[]{"jdbc:mysql://localhost/", "jdbc:mysql://localhost/relayq?allowMultiQueries=true",
                "jdbc:mysql://localhost/relayq?password=secret", "jdbc:mysql://localhost/relayq?createDatabaseIfNotExist=true"}) {
            env.put("RELAYQ_TEST_MYSQL_URL", url);
            assertThrows(IllegalArgumentException.class, () -> ExternalMySqlFixture.open(env));
        }
    }

    @Test
    void rewritesOnlyIdentifiersAndPreservesBoundValuesAndLockingClauses() {
        ExternalMySqlDataSource source = scoped(mock(DataSource.class));
        assertEquals("SELECT id FROM " + PREFIX + "task_info WHERE biz_key = 'task_info' FOR UPDATE SKIP LOCKED",
                source.rewrite("SELECT id FROM `task_info` WHERE biz_key = 'task_info' FOR UPDATE SKIP LOCKED"));
        assertEquals("DELETE FROM " + PREFIX + "task_snapshot", source.rewrite("DELETE FROM task_snapshot"));
        assertEquals("ALTER TABLE " + PREFIX + "task_execute_log DROP CHECK " + PREFIX + "reject_success",
                source.rewrite("ALTER TABLE task_execute_log DROP CHECK reject_success"));
        assertEquals("SELECT @@SESSION.transaction_read_only", source.rewrite("SELECT @@SESSION.transaction_read_only"));
    }

    @Test
    void rejectsUnscopedTablesAndMultipleStatements() {
        ExternalMySqlDataSource source = scoped(mock(DataSource.class));
        for (String sql : new String[]{"DELETE FROM payments", "UPDATE other.task_info SET status='DEAD'",
                "DELETE FROM task_info; DELETE FROM payments", "DROP DATABASE relayq", "CALL clear_all()",
                "SELECT * FROM task_info /* unsafe */", "TRUNCATE task_info", "SET GLOBAL sync_binlog=0"}) {
            assertThrows(IllegalArgumentException.class, () -> source.rewrite(sql), sql);
        }
        assertThrows(IllegalArgumentException.class,
                () -> new ExternalMySqlDataSource(mock(DataSource.class), "task_"));
    }

    @Test
    void routesPreparedAndPlainStatementsToTheSameScopedTables() throws Exception {
        DataSource raw = mock(DataSource.class);
        Connection connection = mock(Connection.class);
        Statement statement = mock(Statement.class);
        when(raw.getConnection()).thenReturn(connection);
        when(connection.createStatement()).thenReturn(statement);
        when(connection.prepareStatement(anyString())).thenReturn(mock(PreparedStatement.class));
        try (Connection scoped = scoped(raw).getConnection()) {
            scoped.prepareStatement("INSERT INTO task_info (id) VALUES (?)");
            scoped.createStatement().executeUpdate("DELETE FROM task_info");
            scoped.rollback();
        }
        verify(connection).prepareStatement("INSERT INTO " + PREFIX + "task_info (id) VALUES (?)");
        verify(statement).executeUpdate("DELETE FROM " + PREFIX + "task_info");
        verify(connection).rollback();
        verify(connection).close();
    }

    @Test
    void createsSchemaCopiesWithoutRowsAndDropsOnlyOwnedTables() throws Exception {
        DataSource source = mock(DataSource.class);
        Connection connection = mock(Connection.class);
        Statement statement = mock(Statement.class);
        when(source.getConnection()).thenReturn(connection);
        when(connection.createStatement()).thenReturn(statement);
        ExternalMySqlFixture fixture = ExternalMySqlFixture.create(source, PREFIX);
        fixture.close();
        fixture.close();
        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(statement, times(8)).execute(sql.capture());
        assertEquals("CREATE TABLE " + PREFIX + "task_info LIKE task_info", sql.getAllValues().getFirst());
        assertEquals("DROP TABLE " + PREFIX + "task_info", sql.getAllValues().getLast());
        assertTrue(sql.getAllValues().stream().allMatch(s -> s.startsWith("CREATE TABLE " + PREFIX)
                || s.startsWith("DROP TABLE " + PREFIX)));
    }

    @Test
    void partialSetupNeverDropsTheTableWhoseCreationFailed() throws Exception {
        DataSource source = mock(DataSource.class);
        Connection connection = mock(Connection.class);
        Statement statement = mock(Statement.class);
        when(source.getConnection()).thenReturn(connection);
        when(connection.createStatement()).thenReturn(statement);
        when(statement.execute("CREATE TABLE " + PREFIX + "task_execute_log LIKE task_execute_log"))
                .thenThrow(new IllegalStateException("creation failed"));
        assertThrows(IllegalStateException.class, () -> ExternalMySqlFixture.create(source, PREFIX));
        verify(statement).execute("DROP TABLE " + PREFIX + "task_info");
        verify(statement, never()).execute("DROP TABLE " + PREFIX + "task_execute_log");
        verify(statement, never()).execute("DROP TABLE task_info");
    }

    private ExternalMySqlDataSource scoped(DataSource source) {
        return new ExternalMySqlDataSource(source, PREFIX);
    }
}
