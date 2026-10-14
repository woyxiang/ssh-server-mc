"""逐字符模拟真实 ssh 客户端敲键盘: 单独 send 每个字符, 回车用 \\r, 测退格"""
import sys
import time

import paramiko

host, port, user, pw = "127.0.0.1", 2222, sys.argv[1], sys.argv[2]

cli = paramiko.SSHClient()
cli.set_missing_host_key_policy(paramiko.AutoAddPolicy())
cli.connect(host, port=port, username=user, password=pw,
            allow_agent=False, look_for_keys=False, timeout=5)

ch = cli.invoke_shell(term="xterm", width=80, height=24)
time.sleep(1.0)


def drain(tag, wait=0.6):
    time.sleep(wait)
    out = b""
    while ch.recv_ready():
        out += ch.recv(4096)
    print(f"--- {tag} ---")
    print(repr(out))
    return out


drain("login banner/prompt", 1.0)

# 逐字符敲 "echo HI"（真实客户端每键一个包）
for c in "echo HI":
    ch.send(c)
    time.sleep(0.08)
drain("typed 'echo HI' char-by-char (expect per-char echo)")

ch.send("\r")  # 回车只有 \r，真实客户端如此
drain("after Enter (expect echo of \\r + command output + new prompt)")

# 退格测试: 敲 "ecoh" 再退格修正为 "echo X"
for c in "ecoh":
    ch.send(c)
    time.sleep(0.05)
ch.send("\x7f")  # backspace
time.sleep(0.1)
ch.send("\x7f")  # 再退格删掉 'h' -> "eco"
time.sleep(0.1)
for c in "o X":
    ch.send(c)
    time.sleep(0.05)
ch.send("\r")
drain("backspace edit 'ecoh'->'echo X' + Enter (expect 'eco\\b \\bh' erasure + X output)")

ch.close()
cli.close()
