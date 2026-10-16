package io.github.woyxiang.sshserver;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.function.BooleanSupplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 交互 shell 的服务器端行编辑输入泵。
 * 子进程 stdin 是管道：PowerShell 极简 REPL（ReadLine 逐行执行）与 bash 管道读行均无行编辑，
 * 真实客户端（raw 终端）下退格键（\x7f/\x08）会被吞掉 → 由本类在服务器侧完成编辑：
 * - 可打印字符入行缓冲并回显（PS 的 ReadLine / bash 均不自回显，故不会双显）；
 * - Backspace（\x7f/\x08）删行尾字符并回显 {@code \b \b}；
 * - 回车/换行把整行（LF 结尾）写入子进程 stdin 并回显换行；
 * - Ctrl+C 打断前台命令（interrupt 回调终止 shell 子进程树）；无前台命令时清空当前行并重打提示；
 * - Ctrl+D（空行）结束输入发 EOF，子进程读到 EOF 自然退出；
 * - 其余控制字符忽略，不入行。
 * prompt 可为 null（进程自打印提示的场景，如 bash -i）。
 */
final class LineInputEditor implements Runnable {
    private final InputStream chanIn;
    private final OutputStream chanOut;
    private final OutputStream procIn;
    private final String prompt;
    private static final Logger LOG = LoggerFactory.getLogger("sshserver");
    private final BooleanSupplier interrupt;
    private final StringBuilder buffer = new StringBuilder();

    LineInputEditor(InputStream chanIn, OutputStream chanOut, OutputStream procIn, BooleanSupplier interrupt, String prompt) {
        this.chanIn = chanIn;
        this.chanOut = chanOut;
        this.procIn = procIn;
        this.prompt = prompt;
        this.interrupt = interrupt;
    }

    @Override
    public void run() {
        try (InputStreamReader r = new InputStreamReader(chanIn, StandardCharsets.UTF_8)) {
            int c;
            boolean skipLf = false;
            while ((c = r.read()) != -1) {
                char ch = (char) c;
                if (skipLf) {
                    skipLf = false;
                    if (ch == '\n') {
                        continue; // CRLF 只算一次回车
                    }
                }
                if (ch == '\r' || ch == '\n') {
                    skipLf = (ch == '\r');
                    flushLine();
                } else if (ch == '\u007f' || ch == '\b') {
                    backspace();
                } else if (ch == '\u0003') {
                    synchronized (chanOut) {
                        buffer.setLength(0);
                        write("^C\r\n"); // 先回显 ^C，中断处理异常也不能吞掉它
                        boolean interrupted = false;
                        try {
                            interrupted = interrupt.getAsBoolean();
                        } catch (Throwable t) {
                            LOG.error("[sshserver] ^C 中断处理异常", t);
                        }
                        if (prompt != null && !interrupted) {
                            write(prompt);
                        }
                    }
                } else if (ch == '\u0004' && buffer.length() == 0) {
                    break;
                } else if (ch >= 0x20) {
                    synchronized (chanOut) {
                        buffer.append(ch);
                        write(String.valueOf(ch));
                    }
                }
            }
        } catch (IOException ignored) {
            // 会话断开
        } finally {
            try {
                procIn.close(); // EOF：PowerShell -Command - 读到 EOF 退出，bash 结束
            } catch (IOException ignored) {
                // 已关闭
            }
        }
    }

    private void flushLine() throws IOException {
        synchronized (chanOut) {
            write("\r\n"); // 先回显行尾，命令输出必然在进程收到整行之后，顺序正确
            String line = buffer.toString();
            buffer.setLength(0);
            procIn.write((line + "\n").getBytes(StandardCharsets.UTF_8));
            procIn.flush();
        }
    }

    private void backspace() throws IOException {
        synchronized (chanOut) {
            if (buffer.length() > 0) {
                buffer.setLength(buffer.length() - 1);
                write("\b \b");
            }
        }
    }

    private void write(String s) throws IOException {
        chanOut.write(s.getBytes(StandardCharsets.UTF_8));
        chanOut.flush();
    }
}
