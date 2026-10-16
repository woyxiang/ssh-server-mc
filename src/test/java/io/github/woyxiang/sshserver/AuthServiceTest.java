package io.github.woyxiang.sshserver;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AuthServiceTest {

    @TempDir
    Path dir;

    private final AtomicLong clock = new AtomicLong(0);

    @BeforeEach
    void reset() {
        clock.set(0);
    }

    private AuthService service(String content, int maxFailures) throws Exception {
        Files.writeString(dir.resolve("sshserver.properties"), content, StandardCharsets.UTF_8);
        SshConfig cfg = SshConfig.loadOrCreate(dir);
        return new AuthService(cfg, new AuthRateLimiter(maxFailures, 60, clock::get));
    }

    @Test
    void emptyCredentialsDenyEveryLogin() throws Exception {
        AuthService auth = service("users=\nconsole-user=mc\nconsole-password=\n", 10);
        assertEquals(AuthService.Result.DENIED, auth.passwordAuth("mc", "", "1.2.3.4"));
        assertEquals(AuthService.Result.DENIED, auth.passwordAuth("mc", "anything", "1.2.3.4"));
        assertEquals(AuthService.Result.DENIED, auth.passwordAuth("alice", "pw", "1.2.3.4"));
    }

    @Test
    void consolePasswordGatesConsoleUserOnly() throws Exception {
        AuthService auth = service("users=bob:bpw\nconsole-user=mc\nconsole-password=cpw\n", 10);
        assertEquals(AuthService.Result.OK, auth.passwordAuth("mc", "cpw", "1.2.3.4"));
        assertEquals(AuthService.Result.DENIED, auth.passwordAuth("mc", "wrong", "1.2.3.5"));
        assertEquals(AuthService.Result.OK, auth.passwordAuth("bob", "bpw", "1.2.3.6"));
        assertEquals(AuthService.Result.DENIED, auth.passwordAuth("bob", "cpw", "1.2.3.7"));
    }

    @Test
    void passwordAuthSwitchDisablesMethod() throws Exception {
        AuthService auth = service("password-auth-enabled=false\nusers=bob:bpw\nconsole-user=mc\nconsole-password=cpw\n", 10);
        assertEquals(AuthService.Result.METHOD_DISABLED, auth.passwordAuth("bob", "bpw", "1.2.3.4"));
        assertEquals(AuthService.Result.METHOD_DISABLED, auth.passwordAuth("mc", "cpw", "1.2.3.4"));
        assertFalse(auth.passwordAuth("bob", "bpw", "1.2.3.4") == AuthService.Result.OK);
    }

    @Test
    void lockoutAfterRepeatedFailures() throws Exception {
        AuthService auth = service("users=bob:bpw\nconsole-user=mc\nconsole-password=cpw\n", 3);
        assertEquals(AuthService.Result.DENIED, auth.passwordAuth("bob", "bad", "9.9.9.9"));
        assertEquals(AuthService.Result.DENIED, auth.passwordAuth("bob", "bad", "9.9.9.9"));
        assertEquals(AuthService.Result.DENIED, auth.passwordAuth("bob", "bad", "9.9.9.9"));
        assertEquals(AuthService.Result.LOCKED, auth.passwordAuth("bob", "bpw", "9.9.9.9"),
                "冷却期内即使密码正确也拒绝");
        assertEquals(AuthService.Result.OK, auth.passwordAuth("bob", "bpw", "8.8.8.8"),
                "其他 IP 不受影响");
    }

    @Test
    void correctPasswordResetsFailureCount() throws Exception {
        AuthService auth = service("users=bob:bpw\nconsole-user=mc\nconsole-password=\n", 3);
        assertEquals(AuthService.Result.DENIED, auth.passwordAuth("bob", "bad", "7.7.7.7"));
        assertEquals(AuthService.Result.DENIED, auth.passwordAuth("bob", "bad", "7.7.7.7"));
        assertEquals(AuthService.Result.OK, auth.passwordAuth("bob", "bpw", "7.7.7.7"));
        assertEquals(AuthService.Result.DENIED, auth.passwordAuth("bob", "bad", "7.7.7.7"));
        assertEquals(AuthService.Result.DENIED, auth.passwordAuth("bob", "bad", "7.7.7.7"));
        assertFalse(auth.passwordAuth("bob", "bad", "7.7.7.7") == AuthService.Result.LOCKED,
                "成功登录后失败计数应已清零");
    }

    @Test
    void constantTimeEqualsMatchesOnlyExactBytes() {
        assertTrue(AuthService.constantTimeEquals("s3cret", "s3cret"));
        assertFalse(AuthService.constantTimeEquals("s3cret", "s3cret "));
        assertFalse(AuthService.constantTimeEquals("s3cret", ""));
        assertFalse(AuthService.constantTimeEquals("", "s3cret"));
        assertTrue(AuthService.constantTimeEquals("", ""));
    }
}
