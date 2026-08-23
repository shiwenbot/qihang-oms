package cn.qihangerp.api.controller.intel;

import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.beans.factory.annotation.Value;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

@Component
class MarketIntelTokenCipher {
    private static final String PREFIX = "v2:";
    private static final int IV_BYTES = 12;
    private final SecureRandom random = new SecureRandom();
    @Value("${market-intel.data-key:}")
    private String dataKey;

    String encrypt(String plaintext) {
        requireKey();
        if (!StringUtils.hasText(plaintext)) return "";
        try {
            byte[] iv = new byte[IV_BYTES];
            random.nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key(), new GCMParameterSpec(128, iv));
            byte[] encrypted = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            byte[] result = new byte[iv.length + encrypted.length];
            System.arraycopy(iv, 0, result, 0, iv.length);
            System.arraycopy(encrypted, 0, result, iv.length, encrypted.length);
            return PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(result);
        } catch (Exception e) {
            throw new IllegalStateException("竞品访问凭据加密失败", e);
        }
    }

    String decrypt(String encoded) {
        requireKey();
        if (!StringUtils.hasText(encoded)) return "";
        if (!encoded.startsWith(PREFIX)) {
            throw new IllegalStateException("竞品访问凭据需要重新保存");
        }
        try {
            byte[] value = Base64.getUrlDecoder().decode(encoded.substring(PREFIX.length()));
            if (value.length <= IV_BYTES) throw new IllegalArgumentException("invalid ciphertext");
            byte[] iv = new byte[IV_BYTES];
            byte[] encrypted = new byte[value.length - IV_BYTES];
            System.arraycopy(value, 0, iv, 0, IV_BYTES);
            System.arraycopy(value, IV_BYTES, encrypted, 0, encrypted.length);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key(), new GCMParameterSpec(128, iv));
            return new String(cipher.doFinal(encrypted), StandardCharsets.UTF_8);
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("竞品访问凭据无法解密，请重新添加竞品", e);
        }
    }

    private SecretKeySpec key() throws Exception {
        return new SecretKeySpec(MessageDigest.getInstance("SHA-256")
                .digest(("QihangOMS.MarketIntel.DataKey.v1\0" + dataKey).getBytes(StandardCharsets.UTF_8)), "AES");
    }

    private void requireKey() {
        if (!StringUtils.hasText(dataKey) || dataKey.length() < 32) {
            throw new IllegalStateException("MARKET_INTEL_DATA_KEY 未配置或长度不足");
        }
    }
}
