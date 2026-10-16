package io.github.woyxiang.sshserver;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.apache.sshd.server.Environment;
import org.apache.sshd.server.ExitCallback;
import org.apache.sshd.server.channel.ChannelSession;
import org.apache.sshd.server.command.Command;

/**
 * 物理 shell 命令：自泵子进程流（替代 MINA 的 InvertedShellWrapper）。
 * 行为分工：
 * - 输入（交互模式）：LineInputEditor 行编辑（回显/退格/Ctrl+C/Ctrl+D）后整行写子进程；
 * - 输入（exec 模式）：TtyFilter.icrnl（\r→\n）直通写子进程，不做回显；
 * - 输出：TtyFilter.onlcr（补 \r）回写通道，stdout/stderr 分流转发。
 */
final class PhysicalShellCommand implements Command {
    private static final Logger LOG = LoggerFactory.getLogger("sshserver");
    private final String[] cmd;
    private final boolean interactive;
    private InputStream chanIn;
    private OutputStream chanOut;
    private OutputStream chanErr;
    private ExitCallback exit;
    private Process proc;
    private volatile boolean closing;
    private final List<Thread> pumps = new ArrayList<>();

    PhysicalShellCommand(String[] cmd, boolean interactive) {
        this.cmd = cmd;
        this.interactive = interactive;
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

        if (interactive) {
            LineInputEditor editor =
                new LineInputEditor(chanIn, chanOut, shellIn, this::interruptForeground, BashShell.interactivePrompt());
            pumps.add(thread("sshserver-sh-in", editor::run));
        } else {
            pumps.add(thread("sshserver-sh-in", () -> {
                byte[] buf = new byte[4096];
                int n;
                while (!closing && (n = chanIn.read(buf)) >= 0) {
                    shellIn.write(buf, 0, n);
                    shellIn.flush();
                }
                try { shellIn.close(); } catch (IOException ignored) { }
            }));
        }
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

    /**
     * Ctrl+C 打断前台命令：终止 shell 的子进程树（当前运行的命令），保留 shell 本身。
     * 返回是否确有子进程被终止——true 时命令被打断、shell 回到提示循环自打提示，
     * 行编辑器不应再重打提示；false 时（无命令在跑）由行编辑器清行并重打提示。
     */
    private boolean interruptForeground() {
        try {
            if (proc == null) {
                return false;
            }
            boolean[] killed = {false};
            if (BashShell.isWindows()) {
                killCommandsWin(proc.toHandle(), killed);
            } else {
                // bash 的直接子进程即前台命令，SIGINT 与终端 Ctrl+C 语义一致
                proc.toHandle().children().forEach(child -> {
                    killed[0] = true;
                    logKill(child);
                    kill(child, "kill", "-INT", String.valueOf(child.pid()));
                });
            }
            return killed[0];
        } catch (Throwable t) {
            LOG.error("[sshserver] ^C 中断处理异常", t);
            return false;
        }
    }

    /**
     * Windows 前台命令定位：shell 进程可能隔着启动壳（如 scoop shim powershell.exe→pwsh.exe），
     * 递归下钻、跳过外壳与控制台宿主（cmd/pwsh/bash/conhost 等），其余后代即前台命令。
     */
    private static void killCommandsWin(ProcessHandle parent, boolean[] killed) {
        parent.children().forEach(child -> {
            String path = child.info().command().orElse("");
            if (isShellOrConsoleHost(path)) {
                killCommandsWin(child, killed); // 外壳保留，往它的子进程找前台命令
                return;
            }
            killed[0] = true;
            logKill(child);
            // /T 连同命令自身的子孙进程一起终止，/F 兜底不响应者
            kill(child, "taskkill", "/PID", String.valueOf(child.pid()), "/T", "/F");
        });
    }

    private static boolean isShellOrConsoleHost(String path) {
        String n = path.substring(Math.max(path.lastIndexOf('\\'), path.lastIndexOf('/')) + 1)
                .toLowerCase();
        return n.equals("cmd.exe") || n.equals("powershell.exe") || n.equals("pwsh.exe")
                || n.equals("bash.exe") || n.equals("sh.exe") || n.equals("conhost.exe")
                || n.equals("openconsole.exe") || n.equals("winpty-agent.exe");
    }

    private static void logKill(ProcessHandle child) {
        LOG.info("[sshserver] ^C 打断前台命令，终止子进程 pid=" + child.pid()
                + " name=" + child.info().command().orElse("?"));
    }

    private static void kill(ProcessHandle child, String... command) {
        try {
            Process p = new ProcessBuilder(command).redirectErrorStream(true).start();
            if (!p.waitFor(2, TimeUnit.SECONDS)) {
                p.destroyForcibly();
            }
        } catch (Exception e) {
            child.destroy(); // taskkill/kill 不可用时的兜底
        }
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
            } catch (Throwable e) {
                LOG.error("[sshserver] shell 泵线程异常退出: " + name, e);
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
