package com.t.tcine.domain.member.controller;

import com.t.tcine.domain.member.service.MemberService;
import com.t.tcine.infra.turnstile.TurnstileVerifier;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

@Controller
public class AuthController {

    private final MemberService memberService;
    private final TurnstileVerifier turnstile;

    public AuthController(MemberService memberService, TurnstileVerifier turnstile) {
        this.memberService = memberService;
        this.turnstile = turnstile;
    }

    @GetMapping("/login")
    public String loginForm() {
        return "login";
    }

    @GetMapping("/signup")
    public String signupForm() {
        return "signup";
    }

    @PostMapping("/signup")
    public String signup(@RequestParam String username,
                         @RequestParam String password,
                         @RequestParam String passwordConfirm,
                         @RequestParam(name = TurnstileVerifier.TOKEN_PARAM, required = false) String captchaToken,
                         Model model) {
        try {
            // 봇 가입을 막기 위해 다른 검사보다 먼저 캡챠(Turnstile)를 확인한다
            if (!turnstile.verify(captchaToken)) {
                throw new IllegalArgumentException("사람인지 확인하지 못했어요. 잠시 후 다시 시도해 주세요.");
            }
            if (!password.equals(passwordConfirm)) {
                throw new IllegalArgumentException("비밀번호 확인이 일치하지 않아요.");
            }
            memberService.signup(username, password);
        } catch (IllegalArgumentException e) {
            model.addAttribute("error", e.getMessage());
            model.addAttribute("formUsername", username);
            return "signup";
        }
        return "redirect:/login?signup";
    }
}
