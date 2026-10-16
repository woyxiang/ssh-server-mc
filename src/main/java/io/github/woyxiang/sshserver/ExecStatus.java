package io.github.woyxiang.sshserver;

/**
 * exec 模式的命令结果状态与退出码（脚本可编程判断）：
 * - completed(0)：命令在游戏线程执行完成且返回成功；
 * - rejected(1)：命令被拒绝（空白/超长/不存在/执行报错/返回失败）；
 * - timed_out(2)：已提交但游戏线程未在超时内执行（如服务器正在停止）；
 * - completion_unknown(3)：未能提交（服务器未就绪/反射失败），结果未知。
 * 输出被截断时，completed 升级为退出码 4，其余状态保持原码（截断信息仍以标记行给出）。
 */
enum ExecStatus {
    COMPLETED(0),
    REJECTED(1),
    TIMED_OUT(2),
    COMPLETION_UNKNOWN(3);

    final int code;

    ExecStatus(int code) {
        this.code = code;
    }

    int exitCode(boolean truncated) {
        return (this == COMPLETED && truncated) ? 4 : code;
    }

    /** 输出中的 status= 字段值。 */
    String wire() {
        return name().toLowerCase();
    }
}
