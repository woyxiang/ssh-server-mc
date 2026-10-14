"""复现交互式 shell 问题: 模拟真实 ssh 客户端（请求 PTY + shell 通道）"""
import sys
import time

import paramiko

host, port, user, pw = "127.0.0.1", 2222, sys.argv[1], sys.argv[2]

cli = paramiko.SSHClient()
cli.set_missing_host_key_policy(paramiko.AutoAddPolicy())
cli.connect(host, port=port, username=user, password=pw,
            allow_agent=False, look_for_keys=False, timeout=5)

ch = cli.invoke_shell(term="xterm", width=80, height=24)  # 请求 PTY + shell
time.sleep(1.0)


def drain(tag):
    out = b""
    while ch.recv_ready():
        out += ch.recv(4096)
        time.sleep(0.05)
    if out:
        print(f"--- {tag} ---")
        print(repr(out))


drain("banner/prompt after login")
ch.send("echo PTYTEST123\r\n")
time.sleep(2.0)
drain("after sending echo PTYTEST123")
ch.send("exit\r\n")
time.sleep(1.0)
drain("after exit")
ch.close()
cli.close()
