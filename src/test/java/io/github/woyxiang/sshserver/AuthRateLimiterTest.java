package io.github.woyxiang.sshserver;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.Test;

class AuthRateLimiterTest {

    private final AtomicLong clock = new AtomicLong(0);

    private AuthRateLimiter limiter(int maxFailures, long lockoutSeconds) {
        return new AuthRateLimiter(maxFailures, lockoutSeconds, clock::get);
    }

    @Test
    void locksAfterThresholdAndExpiresAfterWindow() {
        AuthRateLimiter l = limiter(3, 60);
        assertFalse(l.isLocked("1.2.3.4"));
        l.recordFailure("1.2.3.4");
        l.recordFailure("1.2.3.4");
        assertFalse(l.isLocked("1.2.3.4"), "未达阈值不锁定");
        l.recordFailure("1.2.3.4");
        assertTrue(l.isLocked("1.2.3.4"), "达到阈值即锁定");

        clock.addAndGet(59_000_000_000L);
        assertTrue(l.isLocked("1.2.3.4"), "窗口内保持锁定");
        clock.addAndGet(1_000_000_001L);
        assertFalse(l.isLocked("1.2.3.4"), "窗口过后自动解锁");
    }

    @Test
    void successClearsFailures() {
        AuthRateLimiter l = limiter(3, 60);
        l.recordFailure("1.2.3.4");
        l.recordFailure("1.2.3.4");
        l.recordSuccess("1.2.3.4");
        l.recordFailure("1.2.3.4");
        assertFalse(l.isLocked("1.2.3.4"), "成功后重新计数");
    }

    @Test
    void otherIpsUnaffected() {
        AuthRateLimiter l = limiter(3, 60);
        l.recordFailure("1.1.1.1");
        l.recordFailure("1.1.1.1");
        l.recordFailure("1.1.1.1");
        assertTrue(l.isLocked("1.1.1.1"));
        assertFalse(l.isLocked("2.2.2.2"), "锁定只针对失败来源 IP");
    }

    @Test
    void trackedIpsStayBounded() {
        AuthRateLimiter l = limiter(10, 60);
        for (int i = 0; i < 20000; i++) {
            l.recordFailure("10.0." + (i >> 8) + "." + (i & 0xff));
        }
        assertTrue(l.trackedIps() <= 4096, "伪造大量 IP 不应无限占用内存");
    }
}
