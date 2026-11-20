package org.dromara.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * JWT 签名密钥的启动守卫。
 *
 * <p>密钥泄露等于门钥匙公开：拿到密钥的人能伪造任意用户（含 admin）的合法令牌。
 * 这些用例把「哪些密钥不许上线」钉死，防止又有人把默认值填回去。
 */
@DisplayName("JWT 密钥启动守卫")
class JwtSecretGuardTest {

    @Test
    @DisplayName("生产没给密钥就拒绝启动")
    void productionWithoutSecretFails() {
        IllegalStateException error = assertThrows(IllegalStateException.class,
            () -> JwtSecretGuard.validate("", "prod"));

        assertTrue(error.getMessage().contains("SA_TOKEN_JWT_SECRET"),
            "报错要指名用哪个环境变量");
    }

    @Test
    @DisplayName("仓库里的公开默认值在任何 profile 下都不许用")
    void publicDefaultIsRejectedEverywhere() {
        assertThrows(IllegalStateException.class,
            () -> JwtSecretGuard.validate("abcdefghijklmnopqrstuvwxyz", "prod"));
        assertThrows(IllegalStateException.class,
            () -> JwtSecretGuard.validate("abcdefghijklmnopqrstuvwxyz", "dev"));
    }

    @Test
    @DisplayName("开发兜底值只在本机 profile 下放行")
    void devFallbackOnlyForLocalProfiles() {
        assertDoesNotThrow(() -> JwtSecretGuard.validate(
            "heron-local-dev-jwt-secret-not-for-production", "dev"));
        assertDoesNotThrow(() -> JwtSecretGuard.validate(
            "heron-local-dev-jwt-secret-not-for-production", "local"));
        assertThrows(IllegalStateException.class, () -> JwtSecretGuard.validate(
            "heron-local-dev-jwt-secret-not-for-production", "prod"));
    }

    @Test
    @DisplayName("自己给的密钥一律放行")
    void ownSecretIsAccepted() {
        assertDoesNotThrow(() -> JwtSecretGuard.validate("a-very-private-value-9f2c", "prod"));
    }
}
