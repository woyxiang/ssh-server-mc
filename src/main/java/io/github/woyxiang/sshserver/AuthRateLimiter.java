package io.github.woyxiang.sshserver;

import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

/**
 * 按 IP 的认证失败限流：达到阈值后进入冷却窗口，窗口内直接拒绝（不 sleep、不占用线程）。
 * - 线程安全（ConcurrentHashMap），内存有界（条目超上限先清理过期项，仍超则淘汰旧条目）；
 * - 时钟可注入，测试无需真实等待。
 */
final class AuthRateLimiter {

    /** 内存上限：超过时优先清理过期项，仍超则按插入序淘汰，防止伪造 IP 撑爆内存。 */
    private static final int MAX_TRACKED_IPS = 4096;

    private static final class State {
        int failures;
        long lockedUntilNanos; // 0 = 未锁定
        long lastFailNanos;
    }

    private final int maxFailures;
    private final long lockoutNanos;
    private final LongSupplier clock;
    private final Map<String, State> failures = new ConcurrentHashMap<>();

    AuthRateLimiter(int maxFailures, long lockoutSeconds) {
        this(maxFailures, lockoutSeconds, System::nanoTime);
    }

    AuthRateLimiter(int maxFailures, long lockoutSeconds, LongSupplier clock) {
        this.maxFailures = maxFailures;
        this.lockoutNanos = lockoutSeconds * 1_000_000_000L;
        this.clock = clock;
    }

    /** 该 IP 是否处于锁定冷却期。 */
    boolean isLocked(String ip) {
        State s = failures.get(ip);
        return s != null && s.lockedUntilNanos != 0 && clock.getAsLong() < s.lockedUntilNanos;
    }

    /** 记录一次失败；达到阈值即锁定。 */
    void recordFailure(String ip) {
        long now = clock.getAsLong();
        prune(now);
        failures.compute(ip, (k, v) -> {
            State s = (v == null) ? new State() : v;
            if (s.lockedUntilNanos != 0 && now >= s.lockedUntilNanos) {
                s.failures = 0; // 上一轮锁定已过期，重新计数
                s.lockedUntilNanos = 0;
            }
            s.failures++;
            s.lastFailNanos = now;
            if (s.failures >= maxFailures) {
                s.lockedUntilNanos = now + lockoutNanos;
            }
            return s;
        });
    }

    /** 当前跟踪的 IP 条目数（供测试断言内存有界）。 */
    int trackedIps() {
        return failures.size();
    }

    /** 认证成功后清零该 IP 的失败记录。 */
    void recordSuccess(String ip) {
        failures.remove(ip);
    }

    /** 条目数超上限时清理：先删过期项，仍超则按迭代序淘汰，保证内存有界。 */
    private void prune(long now) {
        if (failures.size() < MAX_TRACKED_IPS) {
            return;
        }
        Iterator<Map.Entry<String, State>> it = failures.entrySet().iterator();
        while (it.hasNext()) {
            State s = it.next().getValue();
            if (now - s.lastFailNanos > lockoutNanos) {
                it.remove();
            }
        }
        it = failures.entrySet().iterator();
        while (failures.size() >= MAX_TRACKED_IPS && it.hasNext()) {
            it.next();
            it.remove();
        }
    }
}
