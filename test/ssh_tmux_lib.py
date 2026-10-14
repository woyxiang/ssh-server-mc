# -*- coding: utf-8 -*-
"""tmux + 真实 ssh.exe 的可靠登录助手：轮询提示符，消除时序竞争"""
import subprocess, time, sys

sys.stdout.reconfigure(encoding="utf-8", errors="replace")

SESSION = "tssh"
import os
HOST = os.environ.get("SSH_USER", "root") + "@" + os.environ.get("SSH_HOST", "127.0.0.1")
PASSWORD = "LocalTestPwd-2222"


def sh(cmd):
    subprocess.run(cmd, shell=True, capture_output=True)


def cap():
    return subprocess.run(f"tmux capture-pane -t {SESSION} -p", shell=True,
                          capture_output=True).stdout.decode("utf-8", "replace")


def wait_for(text, timeout=30):
    deadline = time.time() + timeout
    while time.time() < deadline:
        if text in cap():
            return True
        time.sleep(0.5)
    return False

def login():
    sh("tmux kill-server 2>NUL")
    time.sleep(2)  # psmux server 重启有竞态，给足缓冲
    sh(f"tmux -f NUL new-session -d -s {SESSION}")
    time.sleep(1.5)
    sh(f'tmux send-keys -t {SESSION} "ssh -o StrictHostKeyChecking=no {HOST} -p 2222" Enter')
    assert wait_for("Password:"), "password prompt not shown"
    sh(f'tmux send-keys -t {SESSION} -l "{PASSWORD}"')
    sh(f"tmux send-keys -t {SESSION} Enter")
    assert wait_for("PS E:") or wait_for("]$"), "shell prompt not shown"


def logout():
    sh("tmux kill-server 2>NUL")


if __name__ == "__main__":
    login()
    print("logged in OK, prompt:", cap().strip().splitlines()[-1])
    logout()
