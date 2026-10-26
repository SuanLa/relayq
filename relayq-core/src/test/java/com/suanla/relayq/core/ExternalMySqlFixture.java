package com.suanla.relayq.core;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import javax.sql.DataSource;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Test-only fixture. Never creates a database or writes the existing RelayQ tables. */
public final class ExternalMySqlFixture implements AutoCloseable {
    static final String ACKNOWLEDGEMENT = "CREATE_AND_DROP_ISOLATED_TEST_TABLES";
    private final JdbcTemplate administrativeJdbc;
    private final DataSource scopedDataSource;
    private final List<String> createdTables = new ArrayList<>();

    public static ExternalMySqlFixture open() {
        if (!Boolean.getBoolean("relayq.mysql.tests")) {
            throw new IllegalStateException("External MySQL tests require -Drelayq.mysql.tests=true");
        }
        return open(MySqlTestConfiguration.load());
    }

    static ExternalMySqlFixture open(Map<String, String> environment) {
        if (!ACKNOWLEDGEMENT.equals(environment.get("RELAYQ_TEST_MYSQL_WRITE_ACK"))) {
            throw new IllegalStateException("External MySQL writes are disabled. Obtain approval for isolated"
                    + " test-table CREATE/INSERT/UPDATE/DELETE/ALTER/DROP, then set mysql.write-ack"
                    + " in relayq-test.local.properties or RELAYQ_TEST_MYSQL_WRITE_ACK.");
        }
        String url = required(environment, "RELAYQ_TEST_MYSQL_URL");
        String user = required(environment, "RELAYQ_TEST_MYSQL_USER");
        String password = required(environment, "RELAYQ_TEST_MYSQL_PASSWORD");
        // No default endpoint, implicit database creation, URL credentials or multi-statement execution.
        if (!url.matches("jdbc:mysql://[^/?;]+/[A-Za-z0-9_]+(?:\\?[^;]*)?")
                || url.matches("(?i).*[?&](allowMultiQueries|createDatabaseIfNotExist|user|password|sessionVariables)=.*")) {
            throw new IllegalArgumentException("Use an explicit single-database JDBC URL without URL credentials,"
                    + " sessionVariables, allowMultiQueries or createDatabaseIfNotExist.");
        }
        return create(new DriverManagerDataSource(url, user, password),
                "rqit_" + UUID.randomUUID().toString().replace("-", "") + "_");
    }

    // Package-private injection point for offline safety tests.
    static ExternalMySqlFixture create(DataSource source, String prefix) {
        ExternalMySqlFixture fixture = new ExternalMySqlFixture(source, prefix);
        System.out.println("External MySQL test-table prefix: " + prefix);
        try {
            for (String original : List.of("task_info", "task_execute_log", "task_snapshot")) {
                String table = prefix + original;
                // LIKE copies schema/indexes only, never business rows. No IF NOT EXISTS:
                // a collision fails, and the pre-existing table is not added to the cleanup list.
                fixture.administrativeJdbc.execute("CREATE TABLE " + table + " LIKE " + original);
                fixture.createdTables.add(table);
            }
            String hostTable = prefix + "host_record";
            fixture.administrativeJdbc.execute("CREATE TABLE " + hostTable
                    + " (id BIGINT PRIMARY KEY, name VARCHAR(100)) ENGINE=InnoDB");
            fixture.createdTables.add(hostTable);
            return fixture;
        } catch (RuntimeException failure) {
            try {
                fixture.close();
            } catch (RuntimeException cleanupFailure) {
                failure.addSuppressed(cleanupFailure);
            }
            throw failure;
        }
    }

    private ExternalMySqlFixture(DataSource source, String prefix) {
        scopedDataSource = new ExternalMySqlDataSource(source, prefix);
        administrativeJdbc = new JdbcTemplate(source);
    }

    public DataSource dataSource() {
        return scopedDataSource;
    }

    @Override
    public void close() {
        RuntimeException failure = null;
        List<String> reverse = new ArrayList<>(createdTables);
        Collections.reverse(reverse);
        for (String table : reverse) {
            try {
                administrativeJdbc.execute("DROP TABLE " + table);
                createdTables.remove(table);
            } catch (RuntimeException e) {
                System.err.println("Test table cleanup failed; inspect this exact table manually: " + table);
                if (failure == null) {
                    failure = e;
                } else {
                    failure.addSuppressed(e);
                }
            }
        }
        if (failure != null) {
            throw failure;
        }
    }

    private static String required(Map<String, String> environment, String name) {
        String value = environment.get(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("Missing MySQL test setting: " + name
                    + "; configure relayq-test.local.properties or the environment override.");
        }
        return value;
    }
}
