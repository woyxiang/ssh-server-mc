package io.github.woyxiang.sshserver;

import java.io.File;
import java.io.IOException;
import java.util.Map;

/**
 * 物理 shell 进程构造（Linux: bash，Windows: cmd / PowerShell）。
 *
 * 编码与输入兼容性（实测结论）：
 * - cmd 的内部输出默认跟随系统码页（中文系统 GBK），客户端按 UTF-8 解必乱码 → exec 模式
 *   以 "chcp 65001 >nul & " 前缀切换到 UTF-8（无输入，不触发 cmd 的输入缺陷）；
 * - cmd 在 65001 码页下从管道读多字节输入会异常退出（"More?" 后崩，Windows 自身缺陷），
 *   交互模式改用系统自带的 PowerShell 并显式设 UTF-8 编码，中文输入/输出/回显全部正常。
 * - Linux 一切默认即 UTF-8，不动。
 */
final class BashShell {
    private BashShell() {
    }

    /** 非交互执行：platform 上的"执行单条命令"形式。 */
    static String[] execCommand(String command) {
        if (isWindows()) {
            return new String[] {"cmd.exe", "/d", "/c", "chcp 65001 >nul & " + command};
        }
        return new String[] {"bash", "-c", command};
    }

    /** 交互 shell：platform 上的登录 shell 形式。 */
    static String[] interactiveCommand() {
        if (isWindows()) {
            return new String[] {"powershell.exe", "-NoLogo", "-NoProfile", "-NoExit", "-Command",
                "[Console]::OutputEncoding=[System.Text.Encoding]::UTF8;"
                    + "[Console]::InputEncoding=[System.Text.Encoding]::UTF8"};
        }
        return new String[] {"bash", "-i"};
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase().contains("win");
    }

    /** 启动进程；TERM 透传，Linux 补齐最小环境。 */
    static Process startProcess(String[] cmd, String term) throws IOException {
        ProcessBuilder pb = new ProcessBuilder(cmd);
        Map<String, String> e = pb.environment();
        if (!isWindows()) {
            e.put("PATH", "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin");
            e.put("HOME", "/root");
            e.put("USER", "root");
            e.put("LANG", "C.UTF-8");
        }
        if (term != null && !term.isBlank()) {
            e.put("TERM", term);
        }
        pb.directory(new File(McBridge.gameDir().toFile(), "."));
        pb.redirectErrorStream(false);
        // stdin/stdout/stderr 保持管道，由 PhysicalShellCommand 泵转发到 SSH 流
        return pb.start();
    }
}
