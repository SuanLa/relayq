package com.suanla.relayq.core;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;

/** Opt-in external MySQL tests; application SQL uses per-suite isolated tables. */
@EnabledIfSystemProperty(named = "relayq.mysql.tests", matches = "true")
public abstract class MySqlIntegrationSupport {
    private static ExternalMySqlFixture fixture;

    protected static DataSource dataSource;

    @BeforeAll
    static void initializeDataSource() {
        fixture = ExternalMySqlFixture.open();
        dataSource = fixture.dataSource();
    }

    @AfterAll
    static void closeDatabase() {
        if (fixture != null) {
            fixture.close();
            fixture = null;
            dataSource = null;
        }
    }

    protected void cleanDatabase() {
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        jdbc.update("DELETE FROM task_snapshot");
        jdbc.update("DELETE FROM task_execute_log");
        jdbc.update("DELETE FROM task_info");
    }
}
