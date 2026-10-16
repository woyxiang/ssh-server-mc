package io.github.woyxiang.sshserver;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicBoolean;

import org.apache.sshd.server.Environment;
import org.apache.sshd.server.ExitCallback;
import org.apache.sshd.server.channel.ChannelSession;
import org.apache.sshd.server.command.Command;

/**
 * MC 控制台 exec 命令（ssh mc@host "命令"，供脚本/工具调用）：
 * - 命令派发到游戏线程并等待执行结果，返回真实状态与退出码（见 {@link ExecStatus}）；
 * - 输出 = 派发时刻起 latest.log 的新增内容，按 max-console-output-bytes 有界收集，
 *   每个请求独立收集器，互不串扰；超出上限打印截断标记；
 * - 执行完成且日志安静 quietMs 即返回（短命令通常 1s 内），总等待受
 *   console-command-timeout-ms 约束；超时返回 timed_out，迟到输出可能不完整；
 * - 输出行统一以 status=... exit=... 结尾，脚本可解析。
 */
final class ConsoleExecCommand implements Command {

    /** 执行完成后继续等待日志安静的时间：吸收命令的延迟/异步日志输出。 */
    private static final long QUIET_MS = 500;
    /** 输出收集轮询间隔。 */
    private static final long POLL_MS = 50;

    private final String command;
    private final SshConfig config;
    private InputStream in;
    private OutputStream out;
    private ExitCallback exit;
    private volatile Thread worker;
    private volatile boolean stopped;
    private final AtomicBoolean exited = new AtomicBoolean();

    ConsoleExecCommand(String command, SshConfig config) {
        this.command = (command == null) ? "" : command.trim();
        this.config = config;
    }

    @Override
    public void setInputStream(InputStream in) {
        this.in = in;
    }

    @Override
    public void setOutputStream(OutputStream out) {
        this.out = out;
    }

    @Override
    public void setErrorStream(OutputStream err) {
        // 与 out 合并
    }

    @Override
    public void setExitCallback(ExitCallback callback) {
        this.exit = callback;
    }

    @Override
    public void start(ChannelSession channel, Environment env) {
        Thread t = new Thread(this::run, "sshserver-exec");
        t.setDaemon(true);
        worker = t;
        t.start();
    }

    private void run() {
        int exitCode = ExecStatus.REJECTED.exitCode(false);
        try {
            if (command.isEmpty()) {
                write("[sshserver] 空命令，已拒绝\r\n");
                writeStatus(ExecStatus.REJECTED, exitCode, null);
            } else if (command.length() > config.maxConsoleCommandLength()) {
                write("[sshserver] 命令过长（" + command.length() + " 字符 > 上限 "
                        + config.maxConsoleCommandLength() + "），已拒绝\r\n");
                writeStatus(ExecStatus.REJECTED, exitCode, null);
            } else {
                exitCode = execute();
            }
        } catch (Throwable t) {
            write("[sshserver] exec 失败: " + McBridge.rootMessage(t) + "\r\n");
        } finally {
            shutdown(exitCode);
        }
    }

    private void writeStatus(ExecStatus status, int exitCode, String detail) {
        write("[sshserver] status=" + status.wire() + " exit=" + exitCode
                + (detail != null ? " (" + detail + ")" : "") + "\r\n");
    }

    private int execute() throws IOException {
        long timeoutMs = config.consoleCommandTimeoutMs();
        write("exec: " + command + "\r\n"); // 回显命令（兼容既有 exec 输出契约）
        Path logFile = LogTailer.defaultLogFile();
        long offset = Files.isRegularFile(logFile) ? Files.size(logFile) : 0;
        long start = System.currentTimeMillis();

        McBridge.ExecOutcome outcome = McBridge.dispatchSync(command, timeoutMs);
        ExecStatus status = switch (outcome.kind) {
            case COMPLETED -> ExecStatus.COMPLETED;
            case REJECTED, FAILED -> ExecStatus.REJECTED;
            case TIMED_OUT -> ExecStatus.TIMED_OUT;
            case NOT_READY -> ExecStatus.COMPLETION_UNKNOWN;
        };
        // 硬上限：总超时 + 安静窗口；游戏线程操作不可安全取消，超时即返回并如实标注状态
        long deadline = start + timeoutMs + QUIET_MS;

        try (BoundedLogReader reader = new BoundedLogReader(logFile, offset, config.maxConsoleOutputBytes())) {
            long lastData = System.currentTimeMillis();
            while (!stopped) {
                if (reader.readAvailable() > 0) {
                    lastData = System.currentTimeMillis();
                }
                long now = System.currentTimeMillis();
                if (now - lastData >= QUIET_MS || now >= deadline) {
                    break;
                }
                try {
                    Thread.sleep(POLL_MS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
            // 文件行尾是 CRLF（Windows log4j），先归一成 LF 再转 SSH 风格 CRLF，避免 \r\r\n
            String text = reader.text().replace("\r\n", "\n").replace("\n", "\r\n");
            if (!text.isEmpty()) {
                write(text);
                if (!text.endsWith("\r\n")) {
                    write("\r\n");
                }
            }
            if (reader.truncated()) {
                write("[sshserver] 输出超过上限 " + config.maxConsoleOutputBytes() + " 字节，已截断\r\n");
            }
            int exitCode = status.exitCode(reader.truncated());
            writeStatus(status, exitCode, outcome.detail);
            return exitCode;
        }
    }

    private void write(String s) {
        try {
            synchronized (out) {
                out.write(s.getBytes(StandardCharsets.UTF_8));
                out.flush();
            }
        } catch (IOException ignored) {
            // 会话已断开
        }
    }

    private void shutdown(int code) {
        if (!exited.compareAndSet(false, true)) {
            return;
        }
        if (exit != null) {
            try {
                exit.onExit(code);
            } catch (Throwable ignored) {
                // 已回调或会话已销毁
            }
        }
    }

    @Override
    public void destroy(ChannelSession channel) {
        stopped = true;
        Thread t = worker;
        if (t != null) {
            t.interrupt();
        }
        shutdown(255);
    }
}
