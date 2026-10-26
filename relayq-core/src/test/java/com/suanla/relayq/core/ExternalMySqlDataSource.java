package com.suanla.relayq.core;

import org.springframework.jdbc.datasource.AbstractDataSource;

import javax.sql.DataSource;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/** Narrow SQL adapter for the repository's test SQL, not a general-purpose SQL parser/security sandbox. */
final class ExternalMySqlDataSource extends AbstractDataSource {
    private static final Set<String> TABLES = Set.of("task_info", "task_execute_log", "task_snapshot", "host_record");
    private static final Pattern TOKENS = Pattern.compile(
            "'(?:(?:'')|\\\\.|[^'])*'|\"(?:(?:\"\")|\\\\.|[^\"])*\"|`[^`]*`|--[^\\r\\n]*|/\\*[\\s\\S]*?\\*/|[A-Za-z_][A-Za-z0-9_]*|[^\\s]" );
    private final DataSource delegate;
    private final String prefix;

    ExternalMySqlDataSource(DataSource delegate, String prefix) {
        if (!prefix.matches("rqit_[0-9a-f]{32}_")) {
            throw new IllegalArgumentException("Invalid test table prefix");
        }
        this.delegate = delegate;
        this.prefix = prefix;
    }

    @Override
    public Connection getConnection() throws SQLException {
        return wrap(delegate.getConnection());
    }

    @Override
    public Connection getConnection(String user, String password) throws SQLException {
        throw new SQLException("Per-call credentials are not supported by the test fixture");
    }

    private Connection wrap(Connection connection) {
        return (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(), new Class<?>[]{Connection.class},
                (proxy, method, arguments) -> {
                    if (method.getName().equals("unwrap") || method.getName().equals("prepareCall")) {
                        throw new SQLException("Raw connection access / stored procedures are disabled in tests");
                    }
                    if (method.getName().equals("prepareStatement")) {
                        arguments = arguments.clone();
                        arguments[0] = rewrite((String) arguments[0]);
                    }
                    Object result = invoke(connection, method, arguments);
                    if (method.getName().equals("createStatement")) {
                        Statement statement = (Statement) result;
                        return Proxy.newProxyInstance(Statement.class.getClassLoader(), new Class<?>[]{Statement.class},
                                (statementProxy, statementMethod, statementArgs) -> {
                                    if (statementMethod.getName().equals("getConnection")) {
                                        return proxy;
                                    }
                                    if (statementMethod.getName().equals("unwrap")) {
                                        throw new SQLException("Raw statement access is disabled in tests");
                                    }
                                    if (statementArgs != null && statementArgs.length > 0
                                            && statementArgs[0] instanceof String sql) {
                                        statementArgs = statementArgs.clone();
                                        statementArgs[0] = rewrite(sql);
                                    }
                                    return invoke(statement, statementMethod, statementArgs);
                                });
                    }
                    return result;
                });
    }

    String rewrite(String sql) {
        List<String> tokens = TOKENS.matcher(sql).results().map(match -> match.group()).toList();
        if (tokens.isEmpty() || !Set.of("SELECT", "INSERT", "UPDATE", "DELETE", "ALTER", "SET")
                .contains(tokens.getFirst().toUpperCase(Locale.ROOT))) {
            throw new IllegalArgumentException("Unsupported SQL in isolated MySQL tests");
        }
        if (tokens.contains(";") || tokens.stream().anyMatch(t -> t.startsWith("/*") || t.startsWith("--"))) {
            throw new IllegalArgumentException("Multi-statement SQL and comments are disabled in tests");
        }
        if (tokens.getFirst().equalsIgnoreCase("SET")
                && !sql.trim().matches("(?is)SET\\s+(?:SESSION\\s+TRANSACTION\\s+ISOLATION\\s+LEVEL\\s+(?:READ COMMITTED|REPEATABLE READ)|(?:SESSION\\s+)?(?:autocommit|time_zone|character_set_results|net_write_timeout|sql_select_limit)\\s*=.*)")) {
            throw new IllegalArgumentException("Only the fixture's known session SET statements are allowed");
        }
        for (int i = 0; i < tokens.size(); i++) {
            String token = tokens.get(i).toUpperCase(Locale.ROOT);
            if (Set.of("FROM", "JOIN", "INTO", "TABLE", "UPDATE").contains(token)) {
                // FOR UPDATE [SKIP LOCKED] is a locking clause, not an UPDATE target.
                if (token.equals("UPDATE") && i > 0 && tokens.get(i - 1).equalsIgnoreCase("FOR")) {
                    continue;
                }
                if (i + 1 >= tokens.size() || !TABLES.contains(identifier(tokens.get(i + 1)))
                        || (i + 2 < tokens.size() && tokens.get(i + 2).equals("."))) {
                    throw new IllegalArgumentException("SQL references a table outside this test fixture");
                }
            }
        }
        var matcher = TOKENS.matcher(sql);
        StringBuilder output = new StringBuilder();
        int end = 0;
        while (matcher.find()) {
            output.append(sql, end, matcher.start());
            String token = matcher.group();
            String name = identifier(token);
            if (TABLES.contains(name) || name.equals("reject_success")) {
                output.append(prefix).append(name);
            } else {
                output.append(token);
            }
            end = matcher.end();
        }
        return output.append(sql, end, sql.length()).toString();
    }

    private static String identifier(String token) {
        return (token.startsWith("`") ? token.substring(1, token.length() - 1) : token)
                .toLowerCase(Locale.ROOT);
    }

    private static Object invoke(Object target, Method method, Object[] args) throws Throwable {
        try {
            return method.invoke(target, args);
        } catch (InvocationTargetException e) {
            throw e.getCause();
        }
    }
}
