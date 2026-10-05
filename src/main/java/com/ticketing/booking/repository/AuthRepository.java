package com.ticketing.booking.repository;

import com.ticketing.booking.model.po.UserPO;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/** Database operations for identities. Redis sessions remain an authentication concern. */
@Repository
public class AuthRepository {
    private static final RowMapper<UserPO> USER = (row, index) -> new UserPO(
        row.getLong("id"), row.getString("nickname"), row.getString("phone"), row.getString("role"));
    private final JdbcTemplate jdbc;

    public AuthRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public void registerIfAbsent(String subjectKey, String nickname, String phone, String role) {
        // A unique phone serializes concurrent registration; an existing role is never overwritten.
        jdbc.update("INSERT INTO app_user(subject_key,nickname,phone,role) VALUES(?,?,?,?) "
            + "ON DUPLICATE KEY UPDATE id=id", subjectKey, nickname, phone, role);
    }

    public UserPO findByPhone(String phone) {
        return single(jdbc.query("SELECT id,nickname,phone,role FROM app_user WHERE phone=?", USER, phone));
    }

    public UserPO findById(long userId) {
        return single(jdbc.query("SELECT id,nickname,phone,role FROM app_user WHERE id=?", USER, userId));
    }

    private static UserPO single(List<UserPO> rows) {
        if (rows.size() > 1) { throw new IllegalStateException("MULTIPLE_ROWS"); }
        return rows.isEmpty() ? null : rows.get(0);
    }
}
