package io.github.woyxiang.sshserver;

import java.io.IOException;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 轮询式日志尾巴：把 latest.log 的增量行推送到 SSH 会话输出流，
 * 并把 MC 的 § 颜色码转换为 ANSI 转义（客户端终端直接显示颜色）。
 * 会话开始时可回放文件末尾 replayBytes 的历史（从行边界起）。
 * 推送前/后回调 hooks，供会话重绘正在编辑的输入行（避免日志打断打字）。
 * 末尾半行不推送也不丢弃（下轮续读）；会话断开导致写失败时静默停止。
 */
final class LogTailer implements Runnable {
    /** 推送时的会话回调：负责清行、写日志、重绘输入行（全程持会话同一把锁）。 */
    interface Hooks {
        void push(String text);
    }

    /** 轮询间隔 ms。 */
    private static final long POLL_MS = 400;

    private final Path file;
    private final OutputStream out;
    private final long replayBytes;
    private final Hooks hooks;
    private volatile boolean running = true;

    /** replayBytes>0 时登录先回放这么多历史（0=只推增量）。hooks 可为 null。 */
    LogTailer(Path file, OutputStream out, long replayBytes, Hooks hooks) {
        this.file = file;
        this.out = out;
        this.replayBytes = replayBytes;
        this.hooks = hooks;
    }

    void stop() {
        running = false;
    }

    @Override
    public void run() {
        try (RandomAccessFile raf = new RandomAccessFile(file.toFile(), "r")) {
            long pos = raf.length();
            if (replayBytes > 0 && pos > 0) {
                long start = Math.max(0, pos - replayBytes);
                raf.seek(start);
                if (start > 0) { // 丢弃回放起点的残行，从整行开始
                    int b;
                    boolean found = false;
                    while ((b = raf.read()) != -1) {
                        if (b == '\n') {
                            found = true;
                            break;
                        }
                    }
                    pos = found ? raf.getFilePointer() : raf.length();
                }
            }
            while (running) {
                long len = raf.length();
                if (len < pos) {
                    pos = 0; // 日志轮转/重建（服务器重启），从头重放
                }
                if (len > pos) {
                    raf.seek(pos);
                    byte[] chunk = new byte[(int) Math.min(len - pos, 4 * 1024 * 1024)];
                    raf.readFully(chunk);
                    pos += pushLines(chunk); // 只推进到最后一个 \n；半行下轮重读
                }
                Thread.sleep(POLL_MS);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Throwable ignored) {
            // 文件消失/IO 失败：tailer 静默退出，不影响会话本身
        }
    }

    /** 推送 chunk 中所有完整行，返回推送的字节数（无完整行返回 0）。 */
    private int pushLines(byte[] chunk) {
        int lastNl = -1;
        for (int i = chunk.length - 1; i >= 0; i--) {
            if (chunk[i] == '\n') {
                lastNl = i;
                break;
            }
        }
        if (lastNl < 0) {
            return 0;
        }
        String text = new String(chunk, 0, lastNl + 1, StandardCharsets.UTF_8);
        try {
            if (hooks != null) {
                hooks.push(mcToAnsi(text)); // 会话侧持锁完成清行+日志+重绘
            } else {
                synchronized (out) {
                    out.write(mcToAnsi(text).getBytes(StandardCharsets.UTF_8));
                    out.flush();
                }
            }
        } catch (IOException e) {
            running = false; // 会话已断
        }
        return lastNl + 1;
    }
    /** MC §颜色码 → ANSI；无 § 时原样返回。 */
    static String mcToAnsi(String s) {
        if (s.indexOf('\u00a7') < 0) {
            return s;
        }
        StringBuilder b = new StringBuilder(s.length() + 16);
        int n = s.length();
        for (int i = 0; i < n; i++) {
            char c = s.charAt(i);
            if (c == '\u00a7' && i + 1 < n) {
                b.append(ansiFor(s.charAt(++i)));
            } else {
                b.append(c);
            }
        }
        return b.toString();
    }

    private static String ansiFor(char code) {
        switch (Character.toLowerCase(code)) {
            case '0': return "\u001b[30m";
            case '1': return "\u001b[34m";
            case '2': return "\u001b[32m";
            case '3': return "\u001b[36m";
            case '4': return "\u001b[31m";
            case '5': return "\u001b[35m";
            case '6': return "\u001b[33m";
            case '7': return "\u001b[37m";
            case '8': return "\u001b[90m";
            case '9': return "\u001b[94m";
            case 'a': return "\u001b[92m";
            case 'b': return "\u001b[96m";
            case 'c': return "\u001b[91m";
            case 'd': return "\u001b[95m";
            case 'e': return "\u001b[93m";
            case 'f': return "\u001b[97m";
            case 'l': return "\u001b[1m";  // bold
            case 'n': return "\u001b[4m";  // underline
            case 'o': return "\u001b[3m";  // italic
            case 'm': return "\u001b[9m";  // strike
            case 'k': return "";           // obfuscated：终端无法表示
            case 'r': return "\u001b[0m";  // reset
            default: return "";
        }
    }

    /** latest.log 常规位置：<gamedir>/logs/latest.log。 */
    static Path defaultLogFile() {
        return McBridge.gameDir().resolve("logs").resolve("latest.log");
    }

    static boolean exists(Path p) {
        return Files.isRegularFile(p);
    }
}
