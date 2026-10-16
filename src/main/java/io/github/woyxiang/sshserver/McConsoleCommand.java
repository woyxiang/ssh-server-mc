package io.github.woyxiang.sshserver;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

import org.apache.sshd.server.Environment;
import org.apache.sshd.server.ExitCallback;
import org.apache.sshd.server.channel.ChannelSession;
import org.apache.sshd.server.command.Command;

/**
 * MC 控制台会话（虚拟用户登录，如 mc）：
 * - 每行输入作为 MC 控制台命令提交到服务器主线程（fire-and-forget）；
 * - latest.log 实时推送到会话，登录先回放末尾 REPLAY_BYTES 历史；
 * - 伪终端无行规程（服务器端不回显），自行做字符回显/退格/转义序列消费；
 * - 日志到达时清行写入并重绘提示符与编辑缓冲，不打断打字；
 * - exit / quit / Ctrl+D（空行）结束会话。
 */
final class McConsoleCommand implements Command {
    private static final String PROMPT = "mc> ";
    /** 交互登录回放的历史日志字节数（从行边界起）。 */
    private static final long REPLAY_BYTES = 8192;

    private InputStream in;
    private OutputStream out;
    private ExitCallback exit;
    private volatile Thread readerThread;
    private volatile LogTailer tailer;

    /** 交互编辑缓冲：tailer 重绘与 reader 线程共同访问，一律在 synchronized(out) 内。 */
    private final StringBuilder buffer = new StringBuilder();

    McConsoleCommand() {}

    @Override
    public void setInputStream(InputStream in) { this.in = in; }

    @Override
    public void setOutputStream(OutputStream out) { this.out = out; }

    @Override
    public void setErrorStream(OutputStream err) { /* 与 out 合并 */ }

    @Override
    public void setExitCallback(ExitCallback callback) { this.exit = callback; }

    @Override
    public void start(ChannelSession channel, Environment env) throws IOException {
        write(motd());
        synchronized (out) {
            buffer.setLength(0);
            write(PROMPT); // 先于 tailer 启动，回放自然接在提示符之后
        }
        LogTailer.Hooks hooks = text -> {
            synchronized (out) {
                try {
                    out.write("\r\u001b[2K".getBytes(StandardCharsets.UTF_8)); // 清掉输入行
                    out.write(text.replace("\n", "\r\n").getBytes(StandardCharsets.UTF_8));
                    out.write((PROMPT + buffer).getBytes(StandardCharsets.UTF_8)); // 重绘
                    out.flush();
                } catch (IOException ignored) {
                    // 会话断开，tailer 下轮停止
                }
            }
        };
        tailer = new LogTailer(LogTailer.defaultLogFile(), TtyFilter.onlcrOut(out), REPLAY_BYTES, hooks);
        Thread t = new Thread(tailer, "sshserver-logtail");
        t.setDaemon(true);
        t.start();

        readerThread = new Thread(() -> readLoop(channel), "sshserver-console-reader");
        readerThread.setDaemon(true);
        readerThread.start();
    }

    private void readLoop(ChannelSession channel) {
        try (InputStreamReader r = new InputStreamReader(in, StandardCharsets.UTF_8)) {
            int c;
            boolean skipLf = false;
            while ((c = r.read()) != -1) {
                char ch = (char) c;
                if (skipLf) {
                    skipLf = false;
                    if (ch == "\n".charAt(0)) {
                        continue; // CR LF 只算一次回车
                    }
                }
                if (ch == "\r".charAt(0) || ch == "\n".charAt(0)) {
                    skipLf = (ch == "\r".charAt(0));
                    if (submit(channel)) {
                        break;
                    }
                    continue;
                }
                if (ch == "\u007f".charAt(0) || ch == "\b".charAt(0)) { // 退格
                    synchronized (out) {
                        if (buffer.length() > 0) {
                            buffer.setLength(buffer.length() - 1);
                            write("\b \b");
                        }
                    }
                    continue;
                }
                if (ch == "\u001b".charAt(0)) { // 转义序列（方向键等）吞掉，不入缓冲
                    consumeEscape(r);
                    continue;
                }

                if (ch == "\u0003".charAt(0)) { // Ctrl+C 清行重来
                    synchronized (out) {
                        buffer.setLength(0);
                        write("^C\r\n" + PROMPT);
                    }
                    continue;
                }
                if (ch == "\u0004".charAt(0) && buffer.length() == 0) { // Ctrl+D 空行退出
                    break;
                }
                if (ch < 0x20) {
                    continue; // 其余控制字符忽略
                }
                synchronized (out) {
                    buffer.append(ch);
                    write(String.valueOf(ch)); // 服务器端回显
                }
            }
        } catch (IOException ignored) {
            // 会话断开
        } finally {
            shutdown();
        }
    }

    /** 提交一行；返回 true 表示会话应结束。整段在 synchronized(out) 内，与 tailer 重绘互斥。 */
    private boolean submit(ChannelSession channel) {
        synchronized (out) {
            String cmd = buffer.toString().trim();
            buffer.setLength(0);
            write("\r\n");
            if (cmd.equals("exit") || cmd.equals("quit")) {
                return true;
            }
            if (!cmd.isEmpty() && !McBridge.dispatch(cmd)) {
                write("[sshserver] 服务器未就绪（standalone 模式或正在启动/停止），命令未执行\r\n");
            }
            write(PROMPT);
        }
        return false;
    }

    /** 吞掉一个转义序列：CSI（ESC [ ... 最终字节 0x40-0x7E）、OSC（ESC ] ... BEL）、其余单字符。 */
    private void consumeEscape(InputStreamReader r) throws IOException {
        int c = r.read();
        if (c == -1) {
            return;
        }
        char ch = (char) c;
        if (ch == '[' || ch == "O".charAt(0)) {
            int d;
            while ((d = r.read()) != -1) {
                if (d >= 0x40 && d <= 0x7e) {
                    break;
                }
            }
        } else if (ch == ']') {
            int d;
            while ((d = r.read()) != -1) {
                if (d == 7) {
                    break;
                }
                if (d == "\u001b".charAt(0)) {
                    r.read();
                    break;
                }
            }
        }
    }

    private String motd() {
        Object server = McBridge.server();
        String state = server != null ? "running" : "not ready";
        int players = McBridge.playerCount();
        String playersText = players >= 0 ? String.valueOf(players) : "?";
        return "\r\n[sshserver] MC 控制台模式（服务器状态: " + state + "，在线玩家: " + playersText + "）\r\n"
                + "\r\n[sshserver] 输入 MC 命令回车执行；日志实时回显；exit 退出。\r\n\r\n";
    }

    private void write(String s) {
        try {
            synchronized (out) {
                out.write(s.getBytes(StandardCharsets.UTF_8));
                out.flush();
            }
        } catch (IOException ignored) {
            // 会话断开，reader 会因流关闭而退出
        }
    }

    private void shutdown() {
        if (tailer != null) {
            tailer.stop();
        }
        if (exit != null) {
            try {
                exit.onExit(0);
            } catch (Throwable ignored) {
                // 已回调或会话已销毁
            }
        }
    }

    @Override
    public void destroy(ChannelSession channel) {
        shutdown();
        Thread t = readerThread;
        if (t != null) {
            t.interrupt();
        }
    }
}
