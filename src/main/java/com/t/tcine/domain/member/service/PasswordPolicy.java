package com.t.tcine.domain.member.service;

import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * 비밀번호 정책 (KISA 등에서 흔히 권고하는 기준을 참고).
 * <ul>
 *   <li>영문 대문자/소문자/숫자/특수문자 중 3종류 이상이면 8자 이상, 2종류면 10자 이상</li>
 *   <li>최대 64자 (BCrypt는 72바이트까지만 사용하므로 그 이상은 거부)</li>
 *   <li>공백 불가, 같은 문자 3번 연속 금지, 4자 이상 연속된 문자/숫자(abcd, 1234) 금지</li>
 *   <li>아이디 포함 금지, 흔한 단어/키보드 패턴 금지</li>
 * </ul>
 */
public final class PasswordPolicy {

    private static final int MIN_LENGTH_3_KINDS = 8;
    private static final int MIN_LENGTH_2_KINDS = 10;
    private static final int MAX_LENGTH = 64;
    private static final int MAX_BYTES = 72;

    private static final List<String> COMMON = List.of(
            "password", "passw0rd", "qwerty", "qwer", "asdf", "zxcv", "1q2w3e", "abc123",
            "admin", "welcome", "iloveyou", "letmein", "dragon", "monkey", "master", "login", "yummy");

    private PasswordPolicy() {
    }

    /** 정책 위반 시 사용자에게 보여줄 메시지로 IllegalArgumentException을 던진다 */
    public static void validate(String username, String password) {
        if (password == null || password.isEmpty()) {
            throw new IllegalArgumentException("비밀번호를 입력해 주세요.");
        }
        if (password.length() > MAX_LENGTH || password.getBytes(StandardCharsets.UTF_8).length > MAX_BYTES) {
            throw new IllegalArgumentException("비밀번호는 64자 이하로 입력해 주세요.");
        }
        if (password.chars().anyMatch(Character::isWhitespace)) {
            throw new IllegalArgumentException("비밀번호에는 공백을 사용할 수 없어요.");
        }

        int kinds = kinds(password);
        if (kinds < 2) {
            throw new IllegalArgumentException("영문 대/소문자, 숫자, 특수문자 중 2종류 이상을 조합해 주세요.");
        }
        int required = kinds >= 3 ? MIN_LENGTH_3_KINDS : MIN_LENGTH_2_KINDS;
        if (password.length() < required) {
            throw new IllegalArgumentException(kinds >= 3
                    ? "3종류 이상 조합 시 8자 이상이어야 해요."
                    : "2종류 조합은 10자 이상이어야 해요. (3종류 이상이면 8자 이상)");
        }

        String lower = password.toLowerCase();
        if (username != null && username.length() >= 3 && lower.contains(username.toLowerCase())) {
            throw new IllegalArgumentException("비밀번호에 아이디를 포함할 수 없어요.");
        }
        if (hasRepeated(password, 3)) {
            throw new IllegalArgumentException("같은 문자를 3번 이상 연속해서 사용할 수 없어요.");
        }
        if (hasSequence(lower, 4)) {
            throw new IllegalArgumentException("abcd, 1234처럼 연속된 문자/숫자를 4자 이상 사용할 수 없어요.");
        }
        for (String word : COMMON) {
            if (lower.contains(word)) {
                throw new IllegalArgumentException("너무 흔한 단어/패턴이 들어 있어요. 다른 비밀번호를 사용해 주세요.");
            }
        }
    }

    private static int kinds(String s) {
        boolean upper = false, lower = false, digit = false, special = false;
        for (char c : s.toCharArray()) {
            if (c >= 'A' && c <= 'Z') upper = true;
            else if (c >= 'a' && c <= 'z') lower = true;
            else if (c >= '0' && c <= '9') digit = true;
            else special = true;
        }
        return (upper ? 1 : 0) + (lower ? 1 : 0) + (digit ? 1 : 0) + (special ? 1 : 0);
    }

    /** 같은 문자가 limit번 이상 연속되는지 */
    private static boolean hasRepeated(String s, int limit) {
        int run = 1;
        for (int i = 1; i < s.length(); i++) {
            run = s.charAt(i) == s.charAt(i - 1) ? run + 1 : 1;
            if (run >= limit) {
                return true;
            }
        }
        return false;
    }

    /** 영문 소문자/숫자가 +1 또는 -1씩 limit자 이상 이어지는지 (abcd, dcba, 1234, 4321) */
    private static boolean hasSequence(String s, int limit) {
        int up = 1, down = 1;
        for (int i = 1; i < s.length(); i++) {
            char prev = s.charAt(i - 1), cur = s.charAt(i);
            boolean sameClass = (Character.isDigit(prev) && Character.isDigit(cur))
                    || (isLowerAscii(prev) && isLowerAscii(cur));
            up = sameClass && cur - prev == 1 ? up + 1 : 1;
            down = sameClass && prev - cur == 1 ? down + 1 : 1;
            if (up >= limit || down >= limit) {
                return true;
            }
        }
        return false;
    }

    private static boolean isLowerAscii(char c) {
        return c >= 'a' && c <= 'z';
    }
}
