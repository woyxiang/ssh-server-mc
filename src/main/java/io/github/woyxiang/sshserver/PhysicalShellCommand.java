package io.github.woyxiang.sshserver;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;

import org.apache.sshd.server.Environment;
import org.apache.sshd.server.ExitCallback;
import org.apache.sshd.server.channel.ChannelSession;
import org.apache.sshd.server.command.Command;

/**
 * 物理 shell 命令：自泵子进程流（替代 MINA 的 InvertedShellWrapper）。
 * 行为分工：
 * - 输入：TtyFilter.icrnl（\r→\n）写子进程。不做回显——PS/bash 的交互 reader 自带回显，
 *   cmd 仅用于无输入的 exec 模式，多余的 echo 会叠加成双显；
 * - 输出：TtyFilter.onlcr（补 \r）回写通道，stdout/stderr 分流转发。
 */
final class PhysicalShellCommand implements Command {
    private final String[] cmd;
    private InputStream chanIn;
    private OutputStream chanOut;
    private OutputStream chanErr;
    private ExitCallback exit;
    private Process proc;
    private volatile boolean closing;
    private final List<Thread> pumps = new ArrayList<>();

    PhysicalShellCommand(String[] cmd) {
        this.cmd = cmd;
    }

    @Override public void setInputStream(InputStream in) { this.chanIn = in; }
    @Override public void setOutputStream(OutputStream out) { this.chanOut = out; }
    @Override public void setErrorStream(OutputStream err) { this.chanErr = err; }
    @Override public void setExitCallback(ExitCallback cb) { this.exit = cb; }

    @Override
    public void start(ChannelSession channel, Environment env) throws IOException {
        proc = BashShell.startProcess(cmd, env.getEnv().get("TERM"));
        OutputStream shellIn = TtyFilter.icrnl(proc.getOutputStream());
        InputStream shellOut = TtyFilter.onlcr(proc.getInputStream());
        InputStream shellErr = TtyFilter.onlcr(proc.getErrorStream());

        pumps.add(thread("sshserver-sh-in", () -> {
            byte[] buf = new byte[4096];
            int n;
            while (!closing && (n = chanIn.read(buf)) >= 0) {
                shellIn.write(buf, 0, n);
                shellIn.flush();
            }
            try { shellIn.close(); } catch (IOException ignored) { }
        }));
        Thread outPump = thread("sshserver-sh-out", () -> pump(shellOut, chanOut));
        Thread errPump = thread("sshserver-sh-err", () -> pump(shellErr, chanErr));
        pumps.add(outPump);
        pumps.add(errPump);
        pumps.add(thread("sshserver-sh-wait", () -> {
            int code;
            try {
                code = proc.waitFor();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                code = 130;
            }
            closing = true;
            // 等 stdout/stderr 泵排空再发 exit，否则最后的输出会与 channel 关闭竞态而丢失
            try { outPump.join(2000); } catch (InterruptedException ignored) { }
            try { errPump.join(2000); } catch (InterruptedException ignored) { }
            try { chanOut.flush(); } catch (IOException ignored) { }
            try { exit.onExit(code); } catch (Throwable ignored) { }
        }));
        pumps.forEach(Thread::start);
    }

    @Override
    public void destroy(ChannelSession channel) {
        closing = true;
        if (proc != null) {
            proc.destroy();
        }
    }

    private static Thread thread(String name, Io body) {
        Thread t = new Thread(() -> {
            try {
                body.run();
            } catch (Throwable ignored) {
                // 单个泵异常不打紧，wait 线程负责收尾
            }
        }, name);
        t.setDaemon(true);
        return t;
    }

    @FunctionalInterface
    private interface Io {
        void run() throws Exception;
    }

    private void pump(InputStream in, OutputStream out) throws Exception {
        byte[] buf = new byte[8192];
        int n;
        while (!closing && (n = in.read(buf)) >= 0) {
            out.write(buf, 0, n);
            out.flush();
        }
    }
}
