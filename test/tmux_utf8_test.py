# -*- coding: utf-8 -*-
"""tmux + 真实 ssh.exe 测试：输入回显（回车前可见）+ 中文显示"""
import subprocess, time, sys

def sh(cmd):
    subprocess.run(cmd, shell=True, capture_output=True)

S = "tmux -f NUL new-session -d -s t2"
sh("tmux kill-server 2>NUL")
sh(S + " 'ssh -o StrictHostKeyChecking=no root@127.0.0.1 -p 2222'")
time.sleep(3)  # 等 banner + 提示符

# 逐字符输入，回车之前先抓一次屏——字符应当已经显示（回显生效）
for c in "ECHO HELL":
    sh(f'tmux send-keys -t t2 -l "{c}"')
    time.sleep(0.15)
before_enter = subprocess.run("tmux capture-pane -t t2 -p", shell=True, capture_output=True).stdout.decode("utf-8", "replace")
sh('tmux send-keys -t t2 -l "O 中文测试"')
time.sleep(0.3)
sh('tmux send-keys -t t2 Enter')
time.sleep(2)
after = subprocess.run("tmux capture-pane -t t2 -p", shell=True, capture_output=True).stdout.decode("utf-8", "replace")
sh("tmux kill-server 2>NUL")

print("=== 回车前屏幕（应能看到已输入的 ECHO HELL 字符）===")
print(before_enter[-400:])
print("=== 回车后（应有命令输出 + 中文正常）===")
print(after[-600:])
ok1 = "ECHO HELL" in before_enter
ok2 = "中文测试" in after and "HELL" in after and "?" not in after.replace("? [y/N]", "")
print(f"回显生效: {ok1} | 中文正常: {ok2}")
sys.exit(0 if ok1 and ok2 else 1)
