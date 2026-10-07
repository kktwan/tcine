package com.t.tcine.global.config;

import com.t.tcine.global.security.CsrfExpiredAccessDeniedHandler;
import com.t.tcine.global.security.TurnstileLoginFilter;
import com.t.tcine.infra.turnstile.TurnstileVerifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

@Configuration
public class SecurityConfig {

    @Bean
    PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http, TurnstileVerifier turnstileVerifier) throws Exception {
        http
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/login", "/signup", "/actuator/health", "/css/**", "/js/**", "/img/**", "/error").permitAll()
                        .anyRequest().authenticated())
                .formLogin(form -> form
                        .loginPage("/login")
                        .defaultSuccessUrl("/movies", true)
                        .permitAll())
                // CSRF 토큰 불일치(세션 만료 등)는 403 대신 로그인 화면으로 안내
                .exceptionHandling(e -> e.accessDeniedHandler(new CsrfExpiredAccessDeniedHandler()))
                .logout(logout -> logout.logoutSuccessUrl("/login?logout"))
                // 로그인 인증 전에 Turnstile 캡챠를 검증한다 (캡챠가 꺼져 있으면 통과)
                .addFilterBefore(new TurnstileLoginFilter(turnstileVerifier), UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }
}
