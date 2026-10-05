package com.ticketing.booking.service;

import com.ticketing.booking.model.dto.AuthCodeDTO;
import com.ticketing.booking.model.dto.AuthLoginDTO;
import com.ticketing.booking.model.vo.AuthCodeSentVO;
import com.ticketing.booking.model.vo.AuthLoginVO;
import com.ticketing.booking.model.vo.AuthLogoutVO;
import com.ticketing.booking.model.vo.UserVO;

/** Login, session and server-side permission rules. */
public interface AuthService {
    AuthCodeSentVO sendCode(AuthCodeDTO input, String remoteAddress);
    AuthLoginVO login(AuthLoginDTO input);
    long authenticate(String authorization);
    UserVO me(long userId);
    void requireAdmin(long userId);
    AuthLogoutVO logout(String authorization);
}
