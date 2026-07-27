package com.suanla.relayq;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;

@SpringBootTest
@Testcontainers(disabledWithoutDocker = true)
/*
 * 刻意不设置 MySQL 容器 TZ：容器 UTC 与开发机 JVM CST 的错位是时区漂移回归防线。
 * DDL 始终直接加载 core 的 db/schema.sql，不在 example 复制第二份。
 */
class RelayqApplicationTests {

    @Container
    @ServiceConnection
    private static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.4")
            .withDatabaseName("relayq_context_test")
            .withUsername("relayq")
            .withPassword("relayq")
            .withInitScript("db/schema.sql");

    @Test
    void contextLoadsWithRealMySql() {
    }
}
