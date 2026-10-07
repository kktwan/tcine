package com.t.tcine.global.web;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ModelAttribute;

import java.security.Principal;

/** 모든 화면(Thymeleaf)에서 공통으로 쓰는 값을 모델에 넣는다 */
@ControllerAdvice(annotations = org.springframework.stereotype.Controller.class)
public class ModelAdvice {

    /** Cloudflare Turnstile 사이트 키 (공개용). 비어 있으면 로그인/가입 화면에 캡챠 위젯을 그리지 않는다 */
    private final String turnstileSiteKey;

    public ModelAdvice(@Value("${turnstile.site-key:}") String turnstileSiteKey) {
        this.turnstileSiteKey = turnstileSiteKey;
    }

    /** Turnstile 사이트 키: ${turnstileSiteKey} */
    @ModelAttribute("turnstileSiteKey")
    public String turnstileSiteKey() {
        return turnstileSiteKey;
    }

    /** 로그인한 사용자 이름: ${username} */
    @ModelAttribute("username")
    public String username(Principal principal) {
        return principal == null ? null : principal.getName();
    }
}
