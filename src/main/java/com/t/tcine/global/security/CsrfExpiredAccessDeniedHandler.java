package com.t.tcine.global.security;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.security.web.access.AccessDeniedHandlerImpl;
import org.springframework.security.web.csrf.CsrfException;

import java.io.IOException;

/**
 * CSRF 토큰이 맞지 않을 때(화면을 오래 열어 뒀거나, 배포로 앱이 재시작돼 세션이 사라진 경우)
 * 흰 403 화면 대신 로그인 화면으로 보내 다시 시도하게 한다. 그 밖의 접근 거부는 기본 처리(403).
 */
public class CsrfExpiredAccessDeniedHandler implements AccessDeniedHandler {

    private final AccessDeniedHandler fallback = new AccessDeniedHandlerImpl();

    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response,
                       AccessDeniedException accessDeniedException) throws IOException, ServletException {
        if (accessDeniedException instanceof CsrfException) {
            response.sendRedirect(request.getContextPath() + "/login?expired");
            return;
        }
        fallback.handle(request, response, accessDeniedException);
    }
}
