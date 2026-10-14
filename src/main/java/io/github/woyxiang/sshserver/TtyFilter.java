package io.github.woyxiang.sshserver;

import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * tty 行规程模拟（无 PTY 管道 shell 的标准补偿），语义对齐真实终端驱动：
 * - ICRNL（输入）：\r → \n。真实 SSH 客户端在 PTY 模式下回车发送裸 \r，
 *   而 cmd.exe / bash 的管道输入只认 \n，不转换会导致"敲键盘无响应"。
 *   粘贴 CRLF 文本会变成两个换行——与真实终端 ICRNL 行为一致，无害。
 * - ONLCR（输出）：裸 \n → \r\n。子进程经管道输出时不会自己做 tty 转换
 *   （都是裸 \n），不补 \r 会在客户端终端上阶梯错位；已带 \r 的 \r\n 原样放行。
 */
final class TtyFilter {
    private TtyFilter() {
    }

    /** 输入侧：\r → \n，其余原样。 */
    static OutputStream icrnl(OutputStream out) {
        return new FilterOutputStream(out) {
            @Override
            public void write(int b) throws IOException {
                out.write(b == '\r' ? '\n' : b);
            }

            @Override
            public void write(byte[] b, int off, int len) throws IOException {
                for (int i = 0; i < len; i++) {
                    write(b[off + i]);
                }
            }
        };
    }

    /** 输出侧：裸 \n → \r\n，其余原样。单线程泵读取，状态机无并发问题。 */
    static InputStream onlcr(InputStream in) {
        return new InputStream() {
            private int pending = -1;       // ONLCR 拆出的第二个字节（\n）
            private boolean lastCr = false; // 上一返回字节是否 \r

            @Override
            public int read() throws IOException {
                if (pending >= 0) {
                    int p = pending;
                    pending = -1;
                    lastCr = false;
                    return p;
                }
                int b = in.read();
                if (b < 0) {
                    return b;
                }
                if (b == '\n' && !lastCr) {
                    pending = '\n';
                    lastCr = true;
                    return '\r';
                }
                lastCr = b == '\r';
                return b;
            }

            @Override
            public int available() throws IOException {
                // MINA 泵线程轮询 available() 决定是否读取；漏掉会永远判定"无数据"
                return in.available() + (pending >= 0 ? 1 : 0);
            }

            @Override
            public int read(byte[] buf, int off, int len) throws IOException {
                int first = read(); // 至少等一个真实字节（与底层管道语义一致，不凑满缓冲）
                if (first < 0) {
                    return -1;
                }
                buf[off] = (byte) first;
                int n = 1;
                while (n < len && in.available() > 0) { // 尽力多读，不阻塞
                    int b = read();
                    if (b < 0) {
                        break;
                    }
                    buf[off + n++] = (byte) b;
                }
                return n;
            }
        };
    }

    /** 输出侧（写向 SSH 通道的字节流）：裸 \n → \r\n，其余原样。写入方串行调用，状态机无并发问题。 */
    static OutputStream onlcrOut(OutputStream out) {
        return new FilterOutputStream(out) {
            private boolean lastCr = false; // 上一写出字节是否 \r

            @Override
            public void write(int b) throws IOException {
                if (b == '\n' && !lastCr) {
                    out.write('\r');
                }
                lastCr = b == '\r';
                out.write(b);
            }

            @Override
            public void write(byte[] b, int off, int len) throws IOException {
                for (int i = 0; i < len; i++) {
                    write(b[off + i]);
                }
            }
        };
    }
}
