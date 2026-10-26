package com.suanla.relayq.core;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class MySqlTestConfigurationTest {
    @TempDir
    Path directory;

    @Test
    void allModulesDiscoverTheSameRootConfiguration() throws Exception {
        Files.createDirectory(directory.resolve(".git"));
        Files.writeString(directory.resolve("relayq-test.local.properties"), """
                mysql.url=jdbc:mysql://test.invalid:3306/test_schema
                mysql.username=test_user
                mysql.password=test_password
                mysql.write-ack=CREATE_AND_DROP_ISOLATED_TEST_TABLES
                """);
        Map<String, String> root = MySqlTestConfiguration.load(directory, null, Map.of());
        assertEquals("jdbc:mysql://test.invalid:3306/test_schema", root.get("RELAYQ_TEST_MYSQL_URL"));
        assertEquals("test_user", root.get("RELAYQ_TEST_MYSQL_USER"));
        assertEquals("test_password", root.get("RELAYQ_TEST_MYSQL_PASSWORD"));
        assertEquals(ExternalMySqlFixture.ACKNOWLEDGEMENT, root.get("RELAYQ_TEST_MYSQL_WRITE_ACK"));
        for (String module : new String[]{"relayq-core", "relayq-spring-boot-starter", "relayq-example"}) {
            Path moduleDirectory = Files.createDirectories(directory.resolve(module).resolve("src/test"));
            assertEquals(root, MySqlTestConfiguration.load(moduleDirectory, null, Map.of()));
        }
    }

    @Test
    void environmentOverridesFileIncludingExplicitEmptyValues() throws Exception {
        Files.writeString(directory.resolve("relayq-test.local.properties"),
                "mysql.username=file_user\nmysql.password=file_password\n");
        Map<String, String> values = MySqlTestConfiguration.load(directory, null,
                Map.of("RELAYQ_TEST_MYSQL_USER", "env_user", "RELAYQ_TEST_MYSQL_PASSWORD", ""));
        assertEquals("env_user", values.get("RELAYQ_TEST_MYSQL_USER"));
        assertEquals("", values.get("RELAYQ_TEST_MYSQL_PASSWORD"));
    }

    @Test
    void explicitFileOverridesDiscoveryAndSupportsUtf8() throws Exception {
        Files.writeString(directory.resolve("relayq-test.local.properties"), "mysql.username=ignored\n");
        Path file = directory.resolve("custom.properties");
        Files.writeString(file, "mysql.username=测试用户\n");
        assertEquals("测试用户", MySqlTestConfiguration.load(directory, "custom.properties", Map.of())
                .get("RELAYQ_TEST_MYSQL_USER"));
        assertEquals("测试用户", MySqlTestConfiguration.load(directory, file.toString(), Map.of())
                .get("RELAYQ_TEST_MYSQL_USER"));
    }

    @Test
    void missingExplicitFileFailsInsteadOfFallingBack() {
        assertThrows(IllegalStateException.class,
                () -> MySqlTestConfiguration.load(directory, "missing.properties", Map.of()));
    }

    @Test
    void doesNotReadProductionConfigurationOrOutsideWorkspace() throws Exception {
        Files.writeString(directory.resolve("relayq-test.local.properties"), "mysql.username=outside\n");
        Path workspace = Files.createDirectory(directory.resolve("workspace"));
        Files.createDirectory(workspace.resolve(".git"));
        Files.writeString(workspace.resolve("application.yaml"), "spring.datasource.username: production\n");
        assertTrue(MySqlTestConfiguration.load(workspace, null, Map.of()).isEmpty());
        assertEquals(Map.of("RELAYQ_TEST_MYSQL_USER", "env_user"),
                MySqlTestConfiguration.load(workspace, null, Map.of("RELAYQ_TEST_MYSQL_USER", "env_user")));
    }

    @Test
    void malformedFileFailsWithoutExposingItsContents() throws Exception {
        Files.writeString(directory.resolve("relayq-test.local.properties"), "mysql.password=\\uINVALID_SECRET\n");
        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> MySqlTestConfiguration.load(directory, null, Map.of()));
        assertFalse(failure.getMessage().contains("INVALID_SECRET"));
        assertNull(failure.getCause());
    }
}
