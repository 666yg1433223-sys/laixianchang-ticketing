package com.ticketing.booking.repository;

import java.sql.Statement;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import static com.ticketing.booking.domain.Values.one;

/** Package-private insert mechanics; business callers only see named repository operations. */
final class PaymentJdbcSupport {
    private PaymentJdbcSupport() { }

    static long insert(JdbcTemplate jdbc,String sql,Object... args) {
        GeneratedKeyHolder key=new GeneratedKeyHolder();
        int changed=jdbc.update(connection -> {
            var statement=connection.prepareStatement(sql,Statement.RETURN_GENERATED_KEYS);
            statement.setQueryTimeout(5);
            for(int i=0;i<args.length;i++) { statement.setObject(i+1,args[i]); }
            return statement;
        },key);
        one(changed);
        if(key.getKey()==null) { throw new IllegalStateException("MISSING_GENERATED_ID"); }
        return key.getKey().longValue();
    }
}
