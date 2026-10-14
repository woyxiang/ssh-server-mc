import paramiko, time, sys

c = paramiko.SSHClient()
c.set_missing_host_key_policy(paramiko.AutoAddPolicy())
c.connect("127.0.0.1", 2222, "mc", password="mcconsole123", look_for_keys=False, allow_agent=False, timeout=8)
ch = c.invoke_shell(width=120, height=40)
time.sleep(1.5)
buf = b""
ch.send("list\n"); time.sleep(2)
while ch.recv_ready(): buf += ch.recv(8192); time.sleep(0.2)
out = buf.decode("utf-8", "replace")
print(out)
ok = "players online" in out or "max of" in out
ch.send("exit\n"); time.sleep(0.5)
c.close()
print("PASS" if ok else "FAIL", "- mc interactive dispatch")
sys.exit(0 if ok else 1)
