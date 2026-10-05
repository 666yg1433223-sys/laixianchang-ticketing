package com.ticketing.booking.persistence;

import java.sql.Statement;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;

/** Raw SQL fixture helper. Never packaged in the application. */
public class BookingStore {
    private final JdbcTemplate jdbc;
    public BookingStore(JdbcTemplate jdbc) { this.jdbc = jdbc; jdbc.setQueryTimeout(5); }
    public JdbcTemplate jdbc() { return jdbc; }
    public Map<String,Object> optional(String sql, Object... args) {
        List<Map<String,Object>> rows = jdbc.queryForList(sql,args);
        if (rows.size() > 1) { throw new IllegalStateException("MULTIPLE_ROWS"); }
        return rows.isEmpty() ? null : rows.get(0);
    }
    public Map<String,Object> required(String sql, Object... args) {
        var row = optional(sql,args);
        if (row == null) { throw new IllegalStateException("MISSING_FACT"); }
        return row;
    }
    public long insert(String sql, Object... args) {
        GeneratedKeyHolder key = new GeneratedKeyHolder();
        int changed = jdbc.update(connection -> {
            var statement = connection.prepareStatement(sql,Statement.RETURN_GENERATED_KEYS);
            statement.setQueryTimeout(5);
            for (int i = 0; i < args.length; i++) { statement.setObject(i+1,args[i]); }
            return statement;
        },key);
        com.ticketing.booking.domain.Values.one(changed);
        if (key.getKey() == null) { throw new IllegalStateException("MISSING_GENERATED_ID"); }
        return key.getKey().longValue();
    }
}
