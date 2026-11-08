package com.pd.pds.common;

import java.security.SecureRandom;

/**
 * 业务主键生成：前缀 + 时间有序的随机串，便于排序与排障，例如 u_01JC8Z4K7Q2W3E5R6T。
 */
public final class Ids {

    private static final char[] ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ".toCharArray();
    private static final SecureRandom RANDOM = new SecureRandom();

    private Ids() {
    }

    public static String next(String prefix) {
        long time = System.currentTimeMillis();
        char[] buf = new char[18];
        for (int i = 9; i >= 0; i--) {
            buf[i] = ALPHABET[(int) (time & 31)];
            time >>>= 5;
        }
        for (int i = 10; i < 18; i++) {
            buf[i] = ALPHABET[RANDOM.nextInt(32)];
        }
        return prefix + "_" + new String(buf);
    }

    /** 短 ID：前缀 + 指定长度的随机串，例如权限节点 pn_8F3K2Q7M。调用方负责唯一性校验。 */
    public static String shortId(String prefix, int length) {
        char[] buf = new char[length];
        for (int i = 0; i < length; i++) {
            buf[i] = ALPHABET[RANDOM.nextInt(32)];
        }
        return prefix + "_" + new String(buf);
    }
}
