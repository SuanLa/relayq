package com.suanla.relayq.core;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Properties;

public class TestDatabaseProperties {
    private static final Properties FILE = load();

    private static Properties load() {
        Properties properties = new Properties();
        try (InputStream in = TestDatabaseProperties.class.getClassLoader()
                .getResourceAsStream("relayq-test.properties")) {
            if (in != null) {                        // ← 文件缺失返回 null，不判会 NPE
                properties.load(new InputStreamReader(in, StandardCharsets.UTF_8));
            }
        } catch (IOException error) {
            throw new IllegalStateException("Failed to load relayq-test.properties", error);
        }
        return properties;
    }

    public static String get(String key, String fallback) {
        String fromSystem = System.getProperty(key);   // -D 优先
        if (fromSystem != null && !fromSystem.isBlank()) {
            return fromSystem;
        }
        return FILE.getProperty(key, fallback);
    }

    public static String jdbcUrl(String database) {
        return get("relayq.test.jdbc-base-url", "jdbc:mysql://localhost:3306") + "/" + database;
    }

    private TestDatabaseProperties() {
    }
}
