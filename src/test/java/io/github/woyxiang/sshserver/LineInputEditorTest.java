package io.github.woyxiang.sshserver;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** LineInputEditor 回归：交互 shell 退格（用户 m00589 报告）与 ^C 打断（用户 m01072 报告）。 */
class LineInputEditorTest {

    /** @return [通道输出, 子进程 stdin]；打断回调默认返回 false（无命令在跑）。 */
    private static String[] run(String input) {
        return run(input, () -> false);
    }

    private static String[] run(String input, BooleanSupplier interrupt) {
        ByteArrayInputStream in = new ByteArrayInputStream(input.getBytes(StandardCharsets.UTF_8));
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream proc = new ByteArrayOutputStream();
        new LineInputEditor(in, out, proc, interrupt, "PS> ").run();
        return new String[] {
            out.toString(StandardCharsets.UTF_8), proc.toString(StandardCharsets.UTF_8)};
    }

    @Test
    void backspaceDeletesAndEchoes() {
        String[] r = run("abc\u007fd\r");
        assertEquals("abc\b \bd\r\n", r[0], "退格回显 \\b \\b");
        assertEquals("abd\n", r[1], "退格删字符");
    }

    @Test
    void bsAlsoDeletes() {
        String[] r = run("abc\bd\r");
        assertEquals("abd\n", r[1], "\\x08 同样删字符");
    }

    @Test
    void crlfIsOneLine() {
        assertEquals("echo A\n", run("echo A\r\n")[1], "CRLF 只算一次回车");
    }

    @Test
    void ctrlCClearsLineAndReprintsPrompt() {
        String[] r = run("ab\u0003ok\r");
        assertEquals("ab^C\r\nPS> ok\r\n", r[0], "无命令在跑：^C 清行并重打提示");
        assertEquals("ok\n", r[1], "^C 前内容不入行");
    }

    @Test
    void ctrlCInterruptsRunningCommand() {
        AtomicBoolean called = new AtomicBoolean();
        String[] r = run("\u0003", () -> {
            called.set(true);
            return true;
        });
        assertTrue(called.get(), "^C 触发打断回调");
        assertEquals("^C\r\n", r[0], "打断成功不重打提示（shell 自打，防双提示）");
    }

    @Test
    void ctrlDEmptyEndsInput() {
        assertEquals("line1\n", run("line1\r\u0004")[1], "空行 ^D 发 EOF");
    }

    @Test
    void chinesePassThrough() {
        assertEquals("你好\n", run("你好\r")[1], "中文按字符入行");
    }
}
