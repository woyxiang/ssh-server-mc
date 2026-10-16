package io.github.woyxiang.sshserver;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * exec 模式的契约测试：真实 MINA Command 流程，无 MC 服务器（standalone → 结果未知）。
 */
class ConsoleExecCommandTest {

    @TempDir
    Path dir;

    private record Result(int exitCode, String output) {}

    private Result run(String command, String extraConfig) throws Exception {
        Files.writeString(dir.resolve("sshserver.properties"),
                "console-command-timeout-ms=1000\n" + extraConfig, StandardCharsets.UTF_8);
        SshConfig cfg = SshConfig.loadOrCreate(dir);

        ConsoleExecCommand cmd = new ConsoleExecCommand(command, cfg);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        cmd.setOutputStream(out);
        CountDownLatch done = new CountDownLatch(1);
        int[] code = { -1 };
        cmd.setExitCallback((exitValue, exitMessage, signal) -> {
            code[0] = exitValue;
            done.countDown();
        });
        cmd.start(null, null);
        assertTrue(done.await(5, TimeUnit.SECONDS), "exec 应在超时内完成回调");
        return new Result(code[0], out.toString(StandardCharsets.UTF_8));
    }

    @Test
    void withoutServerReportsCompletionUnknownWithExit3() throws Exception {
        Result r = run("list", "");
        assertEquals(3, r.exitCode(), "服务器未就绪 → completion_unknown → 退出码 3");
        assertTrue(r.output().contains("status=completion_unknown exit=3"), r.output());
    }

    @Test
    void emptyCommandIsRejectedWithExit1() throws Exception {
        Result r = run("   ", "");
        assertEquals(1, r.exitCode());
        assertTrue(r.output().contains("status=rejected exit=1"), r.output());
        assertTrue(r.output().contains("空命令"));
    }

    @Test
    void overlongCommandIsRejectedWithExit1() throws Exception {
        Result r = run("a".repeat(50), "max-console-command-length=10\n");
        assertEquals(1, r.exitCode());
        assertTrue(r.output().contains("命令过长"), r.output());
        assertTrue(r.output().contains("status=rejected exit=1"), r.output());
    }
}
