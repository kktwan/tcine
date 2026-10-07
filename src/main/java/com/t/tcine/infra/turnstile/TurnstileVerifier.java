package com.t.tcine.infra.turnstile;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.net.http.HttpClient;
import java.time.Duration;

/**
 * Cloudflare Turnstile 서버 검증.
 * 브라우저가 폼에 넣어 보낸 토큰(cf-turnstile-response)을 Cloudflare에 보내 "사람이 맞는지" 확인한다.
 * secret-key가 설정되지 않으면 캡챠를 끈다(로컬 개발 편의).
 */
@Component
public class TurnstileVerifier {

    private static final Logger log = LoggerFactory.getLogger(TurnstileVerifier.class);

    /** 폼에서 토큰이 담겨 오는 파라미터 이름 (Turnstile 위젯이 자동으로 채운다) */
    public static final String TOKEN_PARAM = "cf-turnstile-response";

    private final RestClient restClient;
    private final String secretKey;

    public TurnstileVerifier(RestClient.Builder builder,
                             @Value("${turnstile.secret-key:}") String secretKey) {
        this.secretKey = secretKey;
        // Cloudflare가 느려도 가입/로그인 요청이 오래 붙잡히지 않도록 타임아웃을 둔다
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build());
        factory.setReadTimeout(Duration.ofSeconds(5));
        this.restClient = builder
                .baseUrl("https://challenges.cloudflare.com")
                .requestFactory(factory)
                .build();
    }

    /** 캡챠를 쓰는 설정인지 (secret-key가 있을 때만 true) */
    public boolean isEnabled() {
        return secretKey != null && !secretKey.isBlank();
    }

    /**
     * 토큰을 검증한다. 캡챠가 꺼져 있으면 항상 true.
     * 토큰이 없거나 검증 실패/통신 오류면 false (통신 오류는 안전하게 실패로 처리).
     */
    public boolean verify(String token) {
        if (!isEnabled()) {
            return true;
        }
        if (token == null || token.isBlank()) {
            return false;
        }
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("secret", secretKey);
        form.add("response", token);
        try {
            SiteVerifyResponse response = restClient.post()
                    .uri("/turnstile/v0/siteverify")
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .body(form)
                    .retrieve()
                    .body(SiteVerifyResponse.class);
            return response != null && response.success();
        } catch (RestClientException e) {
            log.warn("Turnstile 검증 요청 실패: {}", e.getMessage());
            return false;
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record SiteVerifyResponse(boolean success) {
    }
}
