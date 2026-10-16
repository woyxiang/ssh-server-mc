package io.github.woyxiang.sshserver;

import java.nio.charset.StandardCharsets;

/**
 * 密码认证决策（与 MINA 装配解耦，便于测试）：
 * - 密码认证开关关闭 → METHOD_DISABLED；
 * - IP 处于限流冷却 → LOCKED（调用方负责尽快断开会话）；
 * - console-user 走 console-password（留空即禁止控制台登录），其余走 users 表；
 * - 无凭据（空 users、空 console-password）天然拒绝一切登录——安全默认值；
 * - 比较用常量时间实现，减少时序侧信道。
 */
final class AuthService {

    enum Result {
        OK, METHOD_DISABLED, LOCKED, DENIED
    }

    private final SshConfig config;
    private final AuthRateLimiter limiter;

    AuthService(SshConfig config, AuthRateLimiter limiter) {
        this.config = config;
        this.limiter = limiter;
    }

    Result passwordAuth(String username, String password, String ip) {
        if (!config.passwordAuthEnabled()) {
            return Result.METHOD_DISABLED;
        }
        if (ip != null && limiter.isLocked(ip)) {
            return Result.LOCKED;
        }
        boolean ok;
        String consoleUser = config.consoleUser();
        if (!consoleUser.isEmpty() && consoleUser.equals(username)) {
            String expect = config.consolePassword();
            ok = !expect.isEmpty() && constantTimeEquals(expect, password);
        } else {
            String expect = config.users().get(username);
            ok = expect != null && !expect.isEmpty() && constantTimeEquals(expect, password);
        }
        if (ok) {
            if (ip != null) {
                limiter.recordSuccess(ip);
            }
        } else if (ip != null) {
            limiter.recordFailure(ip);
        }
        return ok ? Result.OK : Result.DENIED;
    }

    /** 常量时间比较：长度差异并入累加器，避免按字节提前返回。 */
    static boolean constantTimeEquals(String a, String b) {
        byte[] x = a.getBytes(StandardCharsets.UTF_8);
        byte[] y = b.getBytes(StandardCharsets.UTF_8);
        int r = x.length ^ y.length;
        for (int i = 0; i < Math.min(x.length, y.length); i++) {
            r |= x[i] ^ y[i];
        }
        return r == 0;
    }
}
