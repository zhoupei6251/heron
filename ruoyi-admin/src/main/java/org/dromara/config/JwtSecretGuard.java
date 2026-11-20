package org.dromara.config;

import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Set;

/**
 * JWT 签名密钥的启动守卫。
 *
 * <p>为什么要有这道闸：Sa-Token 用 JWT 签发登录令牌，签名密钥一旦泄露，
 * 任何人都能伪造出任意用户（含 admin）的合法令牌，不需要密码、不需要验证码。
 * 仓库里曾经把密钥写成 {@code abcdefghijklmnopqrstuvwxyz}，
 * 那份源码或对应的 jar 一旦流出去，等于把门钥匙挂在网上。
 *
 * <p>现在的规矩：密钥必须由外部显式给出。生产没给就启动失败，
 * 不带着空密钥或默认值上线——带着默认值上线比启动失败危险得多，
 * 因为它看起来是正常的。
 */
@Component
public class JwtSecretGuard {

    /** 仓库里出现过的公开默认值：还在用就等于没有密钥。 */
    private static final String OLD_PUBLIC_SECRET = "abcdefghijklmnopqrstuvwxyz";

    /** 本地开发兜底值，只在本机开发用；任何对外环境都不许用它。 */
    private static final String DEV_FALLBACK_SECRET = "heron-local-dev-jwt-secret-not-for-production";

    /** 允许使用开发兜底值的 profile。 */
    private static final Set<String> DEV_PROFILES = Set.of("dev", "local");

    @Value("${sa-token.jwt-secret-key:}")
    private String jwtSecretKey;

    @Value("${spring.profiles.active:default}")
    private String activeProfile;

    /**
     * 在容器刷新阶段校验密钥：抛异常会让启动失败，Web 容器此时还没开始对外服务。
     *
     * @throws IllegalStateException 密钥为空或仍是公开默认值时抛出
     */
    @PostConstruct
    public void check() {
        validate(jwtSecretKey, activeProfile);
    }

    /**
     * 判定一个密钥能不能上线。
     *
     * @param secret        配置里的密钥
     * @param activeProfile 当前激活的 profile
     * @throws IllegalStateException 密钥不可用时抛出
     */
    static void validate(String secret, String activeProfile) {
        String value = secret == null ? "" : secret.strip();
        if (value.isEmpty()) {
            throw new IllegalStateException("sa-token.jwt-secret-key 没给：请通过环境变量 SA_TOKEN_JWT_SECRET 指定"
                + "（当前 profile=" + activeProfile + "）。生产不允许用默认值上线。");
        }
        if (OLD_PUBLIC_SECRET.equals(value)) {
            throw new IllegalStateException("sa-token.jwt-secret-key 还是仓库里的公开默认值，等于没有密钥："
                + "请通过环境变量 SA_TOKEN_JWT_SECRET 指定一个只有你知道的值（当前 profile=" + activeProfile + "）。");
        }
        if (DEV_FALLBACK_SECRET.equals(value) && !DEV_PROFILES.contains(activeProfile)) {
            throw new IllegalStateException("sa-token.jwt-secret-key 用的是本地开发兜底值，"
                + "对外环境必须换成自己的密钥（当前 profile=" + activeProfile + "）。");
        }
    }
}
