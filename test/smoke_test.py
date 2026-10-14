import paramiko, sys, pathlib

import os
HOST, PORT = os.environ.get("SSH_HOST", "127.0.0.1"), 2222

def connect(user, pwd=None, key=None):
    c = paramiko.SSHClient()
    c.set_missing_host_key_policy(paramiko.AutoAddPolicy())
    c.connect(HOST, PORT, user, password=pwd, key_filename=key, look_for_keys=False, allow_agent=False, timeout=8)
    return c

results = []
# 1. root 密码认证 + SFTP
c = connect("root", pwd="LocalTestPwd-2222")
_, out, err = c.exec_command("echo auth-pwd-ok")
results.append(("root 密码认证", out.read().decode().strip() == "auth-pwd-ok"))
sftp = c.open_sftp()
names = sftp.listdir(".")
results.append(("root SFTP listdir", any("mods" in n for n in names) or len(names) > 0))
with sftp.open("smoke-write-test.txt", "w") as f:
    f.write("hello sshserver")
back = sftp.open("smoke-write-test.txt").read().decode()
results.append(("root SFTP 写入回读", back == "hello sshserver"))
sftp.remove("smoke-write-test.txt")
c.close()

# 2. root 公钥认证
c = connect("root", key=str(pathlib.Path(__file__).parent / "id_test"))
_, out, _ = c.exec_command("echo auth-key-ok")
results.append(("root 公钥认证", out.read().decode().strip() == "auth-key-ok"))
c.close()

# 3. mc 密码认证 + exec
c = connect("mc", pwd="mcconsole123")
_, out, _ = c.exec_command("say hello-from-smoke")
out.channel.recv_exit_status()
data = out.read().decode()
results.append(("mc 密码认证+exec", "exec: say" in data))
c.close()

# 4. mc SFTP（设计上 mc 用户应拒绝）
c = connect("mc", pwd="mcconsole123")
try:
    c.open_sftp().listdir(".")
    results.append(("mc SFTP 被拒绝", False))
except Exception:
    results.append(("mc SFTP 被拒绝", True))
c.close()

print()
for name, ok in results:
    print(("PASS  " if ok else "FAIL  ") + name)
sys.exit(0 if all(ok for _, ok in results) else 1)
