import sys, time, paramiko
cli = paramiko.SSHClient()
cli.set_missing_host_key_policy(paramiko.AutoAddPolicy())
cli.connect("127.0.0.1", port=2222, username="root", password="LocalTestPwd-2222",
            allow_agent=False, look_for_keys=False, timeout=5)
ch = cli.invoke_shell(term="xterm")
time.sleep(1)
def drain(tag, wait=0.8):
    time.sleep(wait); out = b""
    while ch.recv_ready(): out += ch.recv(4096)
    print(f"--- {tag} ---"); print(repr(out))
for c in "echo A": ch.send(c); time.sleep(0.05)
ch.send("\r\n")   # 逐字符 + \r\n
drain("char-by-char + CRLF")
for c in "echo B": ch.send(c); time.sleep(0.05)
ch.send("\n")     # 逐字符 + 裸 \n
drain("char-by-char + bare LF")
ch.close(); cli.close()
