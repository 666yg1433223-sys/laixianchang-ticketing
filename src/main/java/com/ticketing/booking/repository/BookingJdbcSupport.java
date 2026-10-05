package com.ticketing.booking.repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import com.ticketing.booking.model.po.BookingRows.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;

/** Package-private SQL plumbing: public repositories expose business operations only. */
final class BookingJdbcSupport {
    private BookingJdbcSupport() { }
    static <T> T optional(JdbcTemplate jdbc,String sql,RowMapper<T> mapper,Object... args) {
        var rows=jdbc.query(sql,mapper,args);
        if(rows.size()>1) { throw new IllegalStateException("MULTIPLE_ROWS"); }
        return rows.isEmpty()?null:rows.get(0);
    }
    static <T> T required(T row) { if(row==null) { throw new IllegalStateException("MISSING_FACT"); }return row; }
    static long insert(JdbcTemplate jdbc,String sql,Object... args) {
        var key=new GeneratedKeyHolder();
        int count=jdbc.update(connection->{var statement=connection.prepareStatement(sql,Statement.RETURN_GENERATED_KEYS);statement.setQueryTimeout(5);
            for(int i=0;i<args.length;i++) { statement.setObject(i+1,args[i]); }return statement;},key);
        com.ticketing.booking.domain.Values.one(count);
        if(key.getKey()==null) { throw new IllegalStateException("MISSING_GENERATED_ID"); }return key.getKey().longValue();
    }
    static boolean column(ResultSet rs,String name) throws SQLException {
        var metadata=rs.getMetaData();for(int i=1;i<=metadata.getColumnCount();i++) { if(name.equalsIgnoreCase(metadata.getColumnLabel(i))) { return true; } }return false;
    }
    static Long nullableLong(ResultSet rs,String name) throws SQLException { long value=rs.getLong(name);return rs.wasNull()?null:value; }
    static Instant timestamp(ResultSet rs,String name) throws SQLException {
        // DATETIME is stored as UTC; preserve the former JDBC LocalDateTime/Timestamp conversion.
        Object value=rs.getObject(name);return value==null?null:com.ticketing.booking.domain.Values.instant(value);
    }
    static final RowMapper<Session> SESSION=(r,n)->new Session(r.getLong("id"),r.getString("state"),timestamp(r,"booking_open_at"),timestamp(r,"booking_close_at"));
    static final RowMapper<Tier> TIER=(r,n)->new Tier(r.getLong("id"),r.getLong("session_id"),r.getInt("unit_price_cent"),r.getInt("available"),r.getInt("capacity"));
    static final RowMapper<Quota> QUOTA=(r,n)->new Quota(r.getLong("user_id"),r.getLong("session_id"),r.getString("owner_type"),nullableLong(r,"owner_id"));
    static final RowMapper<Request> REQUEST=(r,n)->new Request(r.getLong("id"),r.getLong("user_id"),r.getLong("session_id"),r.getLong("tier_id"),r.getBytes("payload_hash"),r.getString("status"),r.getString("reason_code"),timestamp(r,"accepted_at"),timestamp(r,"process_deadline"),nullableLong(r,"order_id"));
    static final RowMapper<Outbox> OUTBOX=(r,n)->new Outbox(r.getLong("id"),r.getString("state"),r.getInt("attempts"),timestamp(r,"next_attempt_at"),r.getLong("recovery_generation"));
    static final RowMapper<Waitlist> WAITLIST=(r,n)->new Waitlist(r.getLong("id"),r.getLong("user_id"),r.getLong("session_id"),r.getLong("tier_id"),r.getBytes("payload_hash"),r.getString("status"),nullableLong(r,"order_id"),timestamp(r,"created_at"));
    static final RowMapper<Order> ORDER=(r,n)->new Order(r.getLong("id"),nullableLong(r,"request_id"),column(r,"waitlist_entry_id")?nullableLong(r,"waitlist_entry_id"):null,
        r.getLong("user_id"),r.getLong("session_id"),r.getLong("tier_id"),r.getString("status"),r.getInt("unit_price_cent"),r.getInt("quantity"),r.getInt("payable_cent"),r.getString("currency"),
        timestamp(r,"hold_created_at"),timestamp(r,"pay_expires_at"),timestamp(r,"closed_at"),r.getString("close_reason"),nullableLong(r,"current_attempt_id"),nullableLong(r,"paid_attempt_id"),timestamp(r,"paid_at"),
        column(r,"current_refund_id")?nullableLong(r,"current_refund_id"):null,column(r,"refunded_at")?timestamp(r,"refunded_at"):null,
        column(r,"refund_deadline")?timestamp(r,"refund_deadline"):null,column(r,"refund_clock")?timestamp(r,"refund_clock"):null,column(r,"waitlist_entry_id"),column(r,"current_refund_id"));
}
