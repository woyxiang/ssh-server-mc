# -*- coding: utf-8 -*-
"""tmux 交互式测试：物理 shell（root）+ MC 控制台（mc）。独立会话，不杀 srv。"""
import os
import subprocess
import sys
import time

sys.stdout.reconfigure(encoding="utf-8", errors="replace")

SESSION = "tssh"
HOST = os.environ.get("SSH_HOST", "127.0.0.1")
ROOT_PWD = "LocalTestPwd-2222"
MC_PWD = "mcconsole123"


def sh(cmd):
    subprocess.run(cmd, shell=True, capture_output=True)


def cap():
    r = subprocess.run(f"tmux capture-pane -t {SESSION} -p -S -400",
                       shell=True, capture_output=True)
    return r.stdout.decode("utf-8", "replace")


def wait_for(text, timeout=45):
    deadline = time.time() + timeout
    while time.time() < deadline:
        if text in cap():
            return True
        time.sleep(0.5)
    return False


def send(cmd):
    sh(f'tmux send-keys -t {SESSION} -l "{cmd}"')
    sh(f"tmux send-keys -t {SESSION} Enter")


def ssh_login(user, pwd, prompt_hint):
    sh(f"tmux clear-history -t {SESSION}")  # 清掉上一段登录的历史，防 Password 残留误判
    send(f"ssh -o StrictHostKeyChecking=no -o UserKnownHostsFile=/dev/null"
         f" -o PubkeyAuthentication=no {user}@{HOST} -p 2222")
    assert wait_for("Password:"), f"{user}: 未出现 Password 提示"
    sh(f'tmux send-keys -t {SESSION} -l "{pwd}"')
    sh(f"tmux send-keys -t {SESSION} Enter")
    assert wait_for(prompt_hint), f"{user}: 未出现预期提示符 {prompt_hint!r}"
    print(f"PASS  {user} 登录 → {prompt_hint!r}")


def start_session():
    sh(f"tmux kill-session -t {SESSION}")  # 只清自己的会话，不 kill-server
    time.sleep(1)
    sh(f"tmux -f NUL new-session -d -s {SESSION}")
    time.sleep(1.5)


def main():
    start_session()
    failures = []

    # ---- 1. 物理 shell（root → Windows PowerShell）----
    try:
        ssh_login("root", ROOT_PWD, "PS E:")
        send("echo INTERACT-OK")
        assert wait_for("INTERACT-OK"), "shell 命令输出未回显"
        print("PASS  物理 shell 命令回显 INTERACT-OK")
        # 退格编辑验证
        # 退格编辑验证：echo AB + BSpace + 123 → 执行 echo A123
        sh(f"tmux send-keys -t {SESSION} -l \"echo AB\"")
        sh(f"tmux send-keys -t {SESSION} BSpace")  # 退掉 B
        sh(f"tmux send-keys -t {SESSION} -l \"123\"")
        sh(f"tmux send-keys -t {SESSION} Enter")
        time.sleep(1.5)
        snap = cap()
        assert "A123" in snap and "AB123" not in snap, "退格未生效"
        print("PASS  退格编辑（echo AB+BSpace+123 → A123）")
        # ^C 中断运行中的命令：ping 运行中 C-c → echo 应答 = ping 被杀且会话存活
        sh(f"tmux send-keys -t {SESSION} -l \"ping 127.0.0.1 -n 60\"")
        sh(f"tmux send-keys -t {SESSION} Enter")
        time.sleep(3)
        assert "Reply from" in cap(), "ping 未启动"
        sh(f"tmux send-keys -t {SESSION} C-c")  # 不带 Enter
        time.sleep(1.5)
        send("echo CTRL-C-INT")
        assert wait_for("CTRL-C-INT"), "^C 未中断 ping（echo 无响应）"
        print("PASS  ^C 中断运行中的 ping")
        # 会话状态保持：cd 后提示符目录变化
        send("cd C:")
        time.sleep(1)
        assert "PS C:" in cap(), "cd 后会话状态未保持"
        print("PASS  会话状态保持（cd C:）")
        send("exit")
        time.sleep(2)
        print("PASS  物理 shell exit 断开")
    except AssertionError as e:
        failures.append(f"物理shell: {e}")
        print(f"FAIL  物理shell: {e}")

    # ---- 2. MC 控制台（mc）----
    try:
        ssh_login("mc", MC_PWD, "mc>")  # capture-pane 会去掉行尾空格，不能带尾随空格
        snap = cap()
        assert "MC 控制台模式" in snap, "MOTD 缺失"
        assert "服务器状态: running" in snap, "服务器状态非 running"
        print("PASS  控制台 MOTD（running）")
        send("say interact-check")
        assert wait_for("interact-check"), "控制台命令输出未回显（tailer 推送）"
        print("PASS  控制台命令输出实时回显")
        send("exit")
        time.sleep(2)
        assert "mc>" not in cap().splitlines()[-5:], "exit 后提示符仍在"
        print("PASS  控制台 exit 断开")
    except AssertionError as e:
        failures.append(f"控制台: {e}")
        print(f"FAIL  控制台: {e}")

    sh(f"tmux kill-session -t {SESSION}")
    if failures:
        print(f"\nRESULT FAIL ({len(failures)})")
        sys.exit(1)
    print("\nRESULT ALL PASS")


if __name__ == "__main__":
    main()
