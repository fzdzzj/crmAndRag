package com.slz.crm.common.untils;

import java.security.SecureRandom;

public class RandomStringGenerator {
    // 定义可用字符集：大小写字母+数字
    private static final String CHARACTERS = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";
    private static final int LENGTH = 8;
    private static final SecureRandom random = new SecureRandom();

    /**
     * 生成8位随机字符
     * @return 8位随机字符串
     */
    public static String generateRandomString() {
        StringBuilder sb = new StringBuilder(LENGTH);

        for (int i = 0; i < LENGTH; i++) {
            // 随机从字符集中选择一个字符
            int index = random.nextInt(CHARACTERS.length());
            sb.append(CHARACTERS.charAt(index));
        }

        return sb.toString();
    }
}
