package com.t.tcine.domain.member.service;

import com.t.tcine.domain.member.entity.Member;
import com.t.tcine.domain.member.repository.MemberRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class MemberService {

    private static final String USERNAME_PATTERN = "[A-Za-z0-9_]{4,20}";

    private final MemberRepository memberRepository;
    private final PasswordEncoder passwordEncoder;

    public MemberService(MemberRepository memberRepository, PasswordEncoder passwordEncoder) {
        this.memberRepository = memberRepository;
        this.passwordEncoder = passwordEncoder;
    }

    /** 로그인한 사용자의 아이디로 회원 조회 (다른 서비스에서 공통으로 사용) */
    @Transactional(readOnly = true)
    public Member getByUsername(String username) {
        return memberRepository.findByUsername(username)
                .orElseThrow(() -> new IllegalStateException("회원 정보를 찾을 수 없습니다."));
    }

    @Transactional
    public void signup(String username, String password) {
        if (username == null || !username.matches(USERNAME_PATTERN)) {
            throw new IllegalArgumentException("아이디는 영문/숫자/_ 4~20자로 입력해 주세요.");
        }
        PasswordPolicy.validate(username, password);
        if (memberRepository.existsByUsername(username)) {
            throw new IllegalArgumentException("이미 사용 중인 아이디입니다.");
        }
        try {
            memberRepository.saveAndFlush(new Member(username, passwordEncoder.encode(password)));
        } catch (DataIntegrityViolationException e) {
            // 동시에 같은 아이디로 가입한 경우 (unique 제약 위반)
            throw new IllegalArgumentException("이미 사용 중인 아이디입니다.");
        }
    }
}
