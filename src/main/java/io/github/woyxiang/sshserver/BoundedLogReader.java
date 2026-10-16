package io.github.woyxiang.sshserver;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 从指定偏移量收集日志文件新增内容的有界读取器（供 exec 模式做命令级输出收集）：
 * - 每个 exec 请求持有独立实例，互不串扰；
 * - 读取量受 maxBytes 上限约束，触顶置 truncated 标记，防止超大输出撑爆会话；
 * - 文件不存在/轮转（长度变短）时从头继续，不抛错。
 */
final class BoundedLogReader implements AutoCloseable {

    private final RandomAccessFile raf; // 文件不存在时为 null（读到空）
    private final long maxBytes;
    private final ByteArrayOutputStream buf = new ByteArrayOutputStream();
    private long pos;
    private long total;
    private boolean truncated;

    BoundedLogReader(Path file, long startOffset, long maxBytes) throws IOException {
        this.maxBytes = maxBytes;
        if (Files.isRegularFile(file)) {
            raf = new RandomAccessFile(file.toFile(), "r");
            long len = raf.length();
            pos = (startOffset <= len) ? startOffset : 0; // 文件已被轮转 → 从头收集
            raf.seek(pos);
        } else {
            raf = null;
        }
    }

    /** 读取当前新增的可用字节（受上限约束）；返回本次读取量。 */
    int readAvailable() throws IOException {
        if (raf == null) {
            return 0;
        }
        long len = raf.length();
        if (len < pos) {
            pos = 0; // 日志轮转/重建
        }
        long avail = len - pos;
        if (avail <= 0) {
            return 0;
        }
        long budget = maxBytes - total;
        if (budget <= 0) {
            truncated = true;
            pos = len;
            return 0;
        }
        int n = (int) Math.min(avail, Math.min(budget, 1024 * 1024));
        byte[] chunk = new byte[n];
        raf.seek(pos);
        raf.readFully(chunk);
        buf.write(chunk);
        total += n;
        pos += n;
        if (n < avail) {
            truncated = true; // 超出上限的部分被丢弃
        }
        return n;
    }

    /** 收集到的文本：§ 颜色码转 ANSI。 */
    String text() {
        return LogTailer.mcToAnsi(buf.toString(StandardCharsets.UTF_8));
    }

    boolean truncated() {
        return truncated;
    }

    @Override
    public void close() throws IOException {
        if (raf != null) {
            raf.close();
        }
    }
}
