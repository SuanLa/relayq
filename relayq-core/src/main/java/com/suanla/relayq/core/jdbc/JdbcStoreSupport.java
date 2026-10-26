package com.suanla.relayq.core.jdbc;

import org.springframework.dao.support.DataAccessUtils;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import javax.sql.DataSource;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Collectors;

/** Shared JDBC mechanics only; identifiers are constants supplied by the concrete stores. */
abstract class JdbcStoreSupport {
    protected final NamedParameterJdbcTemplate jdbc;

    JdbcStoreSupport(DataSource dataSource) {
        this.jdbc = new NamedParameterJdbcTemplate(dataSource);
    }

    protected static MapSqlParameterSource parameters(Object... pairs) {
        MapSqlParameterSource values = new MapSqlParameterSource();
        for (int i = 0; i < pairs.length; i += 2) {
            Object value = pairs[i + 1];
            values.addValue((String) pairs[i], value instanceof Enum<?> e ? e.name() : value);
        }
        return values;
    }

    protected <T> T one(String sql, MapSqlParameterSource params, RowMapper<T> mapper) {
        return DataAccessUtils.singleResult(jdbc.query(sql, params, mapper));
    }

    protected static <E extends Enum<E>> E enumValue(ResultSet rs, String column, Class<E> type)
            throws SQLException {
        String value = rs.getString(column);
        return value == null ? null : Enum.valueOf(type, value);
    }

    // Preserve the old insert strategy: omitted null fields use database defaults (e.g. created_at).
    protected int insert(String table, MapSqlParameterSource fields) {
        Map<String, Object> nonNull = new LinkedHashMap<>();
        fields.getValues().forEach((key, value) -> {
            if (value != null) {
                nonNull.put(key, value);
            }
        });
        String columns = String.join(", ", nonNull.keySet());
        String placeholders = nonNull.keySet().stream().map(key -> ":" + key)
                .collect(Collectors.joining(", "));
        return jdbc.update("INSERT INTO " + table + " (" + columns + ") VALUES ("
                + placeholders + ")", nonNull);
    }
}
