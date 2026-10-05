package com.ticketing.booking.auth;

import com.ticketing.booking.model.po.UserPO;
import com.ticketing.booking.repository.AuthRepository;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AuthRepositoryOfflineTest {
    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final AuthRepository users = new AuthRepository(jdbc);

    @Test void repeatRegistrationNeverPromotesAnExistingUserRole() {
        users.registerIfAbsent("server-subject", "观众8000", "13800138000", "ADMIN");
        verify(jdbc).update("INSERT INTO app_user(subject_key,nickname,phone,role) VALUES(?,?,?,?) ON DUPLICATE KEY UPDATE id=id",
            "server-subject", "观众8000", "13800138000", "ADMIN");
    }

    @Test void duplicateIdentityRowsFailClosedInsteadOfSelectingAnArbitraryRole() {
        when(jdbc.query(anyString(), org.mockito.ArgumentMatchers.<RowMapper<UserPO>>any(), eq("13800138000")))
            .thenReturn(List.of(new UserPO(1L, "a", "13800138000", "USER"), new UserPO(2L, "b", "13800138000", "ADMIN")));
        assertEquals("MULTIPLE_ROWS", assertThrows(IllegalStateException.class, () -> users.findByPhone("13800138000")).getMessage());
    }
}
