package com.t.tcine.global.security;

import com.t.tcine.infra.turnstile.TurnstileVerifier;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * 로그인(POST /login) 요청이 인증 필터에 닿기 전에 Turnstile 토큰을 검증한다.
 * 실패하면 로그인 화면으로 돌려보낸다(/login?captcha). 캡챠가 꺼져 있으면 아무것도 하지 않는다.
 * 스프링 빈으로 등록하면 서블릿 필터로 한 번 더 등록되므로, SecurityConfig에서만 직접 생성해 쓴다.
 */
public class TurnstileLoginFilter extends OncePerRequestFilter {

    private final TurnstileVerifier verifier;

    public TurnstileLoginFilter(TurnstileVerifier verifier) {
        this.verifier = verifier;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        boolean loginSubmit = "POST".equalsIgnoreCase(request.getMethod())
                && "/login".equals(request.getServletPath());
        return !loginSubmit || !verifier.isEnabled();
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        if (verifier.verify(request.getParameter(TurnstileVerifier.TOKEN_PARAM))) {
            chain.doFilter(request, response);
            return;
        }
        response.sendRedirect(request.getContextPath() + "/login?captcha");
    }
}
