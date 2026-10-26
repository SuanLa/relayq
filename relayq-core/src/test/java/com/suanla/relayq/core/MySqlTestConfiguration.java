package com.suanla.relayq.core;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;

/** Shared test-only configuration; no production configuration fallback. */
final class MySqlTestConfiguration {
    private static final String LOCAL_FILE = "relayq-test.local.properties";
    private static final Map<String, String> KEYS = Map.of(
            "RELAYQ_TEST_MYSQL_URL", "mysql.url",
            "RELAYQ_TEST_MYSQL_USER", "mysql.username",
            "RELAYQ_TEST_MYSQL_PASSWORD", "mysql.password",
            "RELAYQ_TEST_MYSQL_WRITE_ACK", "mysql.write-ack");

    private MySqlTestConfiguration() {
    }

    static Map<String, String> load() {
        return load(Path.of(System.getProperty("user.dir")),
                System.getProperty("relayq.mysql.config"), System.getenv());
    }

    // Explicit path > repository-local discovery; environment values override file values.
    static Map<String, String> load(Path workingDirectory, String explicitPath,
                                    Map<String, String> environment) {
        Path file = null;
        if (explicitPath != null && !explicitPath.isBlank()) {
            file = workingDirectory.resolve(explicitPath).toAbsolutePath().normalize();
            if (!Files.isRegularFile(file)) {
                throw new IllegalStateException("MySQL test configuration file does not exist: " + file);
            }
        } else {
            for (Path directory = workingDirectory.toAbsolutePath().normalize();
                 directory != null; directory = directory.getParent()) {
                Path candidate = directory.resolve(LOCAL_FILE);
                if (Files.isRegularFile(candidate)) {
                    file = candidate;
                    break;
                }
                // Do not accidentally load a connection from outside this Git workspace.
                if (Files.exists(directory.resolve(".git"))) {
                    break;
                }
            }
        }
        Properties properties = new Properties();
        if (file != null) {
            try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                properties.load(reader);
            } catch (IOException | IllegalArgumentException e) {
                // Do not include properties or parser exceptions that might expose credentials.
                throw new IllegalStateException("Cannot read MySQL test configuration: " + file);
            }
        }
        Map<String, String> resolved = new HashMap<>();
        KEYS.forEach((envKey, fileKey) -> {
            String value = environment.containsKey(envKey)
                    ? environment.get(envKey) : properties.getProperty(fileKey);
            if (value != null) {
                resolved.put(envKey, value);
            }
        });
        return resolved;
    }
}
