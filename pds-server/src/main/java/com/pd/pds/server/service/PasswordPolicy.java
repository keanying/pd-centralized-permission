package com.pd.pds.server.service;

import com.pd.pds.common.BizException;
import com.pd.pds.common.ErrorCode;

import java.security.SecureRandom;

/**
 * 密码策略：长度 ≥ 8，且包含大写字母、小写字母、数字、特殊字符中的至少 3 类；不得包含账号。
 */
public final class PasswordPolicy {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final String UPPER = "ABCDEFGHJKLMNPQRSTUVWXYZ";
    private static final String LOWER = "abcdefghijkmnpqrstuvwxyz";
    private static final String DIGIT = "23456789";
    private static final String SYMBOL = "!@#$%^&*-_=+";

    private PasswordPolicy() {
    }

    public static void validate(String username, String password) {
        if (password == null || password.length() < 8) {
            throw new BizException(ErrorCode.VALIDATION_FAILED, "密码长度不能少于 8 位");
        }
        if (password.length() > 64) {
            throw new BizException(ErrorCode.VALIDATION_FAILED, "密码长度不能超过 64 位");
        }
        int classes = 0;
        if (password.chars().anyMatch(Character::isUpperCase)) {
            classes++;
        }
        if (password.chars().anyMatch(Character::isLowerCase)) {
            classes++;
        }
        if (password.chars().anyMatch(Character::isDigit)) {
            classes++;
        }
        if (password.chars().anyMatch(c -> !Character.isLetterOrDigit(c))) {
            classes++;
        }
        if (classes < 3) {
            throw new BizException(ErrorCode.VALIDATION_FAILED, "密码需包含大写字母、小写字母、数字、特殊字符中的至少 3 类");
        }
        if (username != null && password.toLowerCase().contains(username.toLowerCase())) {
            throw new BizException(ErrorCode.VALIDATION_FAILED, "密码不能包含账号");
        }
    }

    /** 生成满足策略的随机初始密码（12 位）。 */
    public static String generate() {
        StringBuilder sb = new StringBuilder();
        sb.append(pick(UPPER)).append(pick(LOWER)).append(pick(DIGIT)).append(pick(SYMBOL));
        String all = UPPER + LOWER + DIGIT + SYMBOL;
        while (sb.length() < 12) {
            sb.append(pick(all));
        }
        char[] chars = sb.toString().toCharArray();
        for (int i = chars.length - 1; i > 0; i--) {
            int j = RANDOM.nextInt(i + 1);
            char t = chars[i];
            chars[i] = chars[j];
            chars[j] = t;
        }
        return new String(chars);
    }

    private static char pick(String s) {
        return s.charAt(RANDOM.nextInt(s.length()));
    }
}
