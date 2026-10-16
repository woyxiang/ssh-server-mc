# SSH Server MC

**Manage your Minecraft server through SSH and SFTP — directly from your Minecraft server.**

SSH Server MC is a server-side NeoForge mod that provides SSH access to a running Minecraft server. It allows administrators to execute Minecraft commands remotely, access a server-side shell, and transfer files using standard SSH and SFTP clients.


## Features

- **SSH access** — Connect to your Minecraft server using standard SSH clients.
- **Remote Minecraft console** — Execute Minecraft commands without joining the game.
- **Shell access** — Run operating system commands under the permissions of the Minecraft server process.
- **SFTP file transfers** — Upload and download server files using standard SFTP clients.
- **SSH authentication** — Configure supported authentication methods through the server configuration.

## Compatibility

| Requirement | Details |
|---|---|
| Minecraft | 1.21.1|
| Mod loader | NeoForge |
| Java | Java 21 |

## Installation

1. Download the JAR from the project's [GitHub Releases](https://github.com/woyxiang/ssh-server-mc/releases) or the distribution platform hosting the release.
2. Place the JAR in your Minecraft server's `mods` directory.
3. Start the server once to initialize the mod and check the startup log.
4. Follow the configuration instructions below.
5. Restart the server after changing configuration.

**Important:** Do not expose the SSH service to the public internet until you have reviewed the authentication settings, bind address, firewall rules, and operating system permissions.

## Configuration

SSH Server MC uses a properties-based configuration file.

The expected configuration path is:

`config/sshd/sshserver.properties`

On first start the mod generates this file with commented defaults. **The generated configuration denies every login**: `users` and `console-password` are empty, and the service binds to `127.0.0.1`. Credentials must be configured explicitly before any login succeeds; invalid values (for example an out-of-range port) abort startup with a clear error instead of leaving a partially working service. The file is never rewritten automatically, and the generated comments are the authoritative reference for the installed version.

### Options

| Option | Default | Purpose |
|---|---|---|
| `bind` | `127.0.0.1` | Listen address. `0.0.0.0` is accepted but logs a startup warning |
| `port` | `2222` | SSH listen port (validated to 1–65535) |
| `password-auth-enabled` | `true` | Master switch for password authentication |
| `public-key-auth-enabled` | `true` | Master switch for public-key authentication |
| `users` | *(empty)* | Shell accounts, comma-separated `username:password` entries; an empty list denies all password logins. Public keys go to `authorized_keys/<username>` in the same directory |
| `console-user` / `console-password` | `mc` / *(empty)* | Minecraft console account; an empty password denies console login |
| `shell-enabled` | `true` | Global switch for OS shell access |
| `console-enabled` | `true` | Global switch for Minecraft console access |
| `sftp-enabled` | `true` | Global switch for the SFTP subsystem |
| `max-connections` | `8` | Maximum concurrent SSH sessions |
| `max-auth-requests` | `3` | Maximum authentication attempts per session |
| `auth-failures-per-ip` | `5` | Failures per source IP before lockout |
| `auth-lockout-seconds` | `60` | Lockout window for blocked IPs; locked addresses are rejected without blocking server threads |
| `auth-timeout-seconds` | `30` | Deadline for completing authentication |
| `idle-timeout-seconds` | `900` | Disconnect idle sessions after this period (`0` disables) |
| `max-console-command-length` | `2048` | Console commands longer than this are rejected |
| `max-console-output-bytes` | `1048576` | Output cap per exec request; overflow prints a truncation marker |
| `console-command-timeout-ms` | `5000` | How long exec waits for command execution and output before reporting a timeout |

Use `127.0.0.1` for local-only access. If remote access is required, explicitly configure the intended network interface, set strong credentials, and restrict access with your firewall.


### Security recommendations

- Run Minecraft under a dedicated, unprivileged operating system account.
- Use SSH public-key authentication where supported and disable password authentication if it is not needed.
- Use strong, unique credentials if password authentication remains enabled.
- Restrict inbound connections with firewall rules or a private network.
- Keep the mod and its bundled dependencies up to date.
- Avoid running the Minecraft server as `root` or an administrator account.

**Shell access is powerful.** Commands executed through the shell inherit the operating system permissions of the Minecraft server process. An SSH username configured by this mod is not necessarily a separate operating system account.

## Connecting over SSH

You can use OpenSSH on Linux, macOS, or Windows, or another compatible SSH client.

Assuming the server is reachable at `192.0.2.10` and SSH is configured to listen on port `2222`:

### Connect interactively

```bash
ssh -p 2222 <username>@192.0.2.10
```

Replace `<username>` with an account configured for SSH Server MC.


Interactive shells provide server-side line editing: Backspace edits the current line, `Ctrl+C` interrupts the running foreground command (or clears the pending line when idle), and `Ctrl+D` closes the shell from an empty line. Command history keys and completion are not provided by the server.
### Execute a Minecraft command

If the account is configured for Minecraft console access, you can execute a command remotely:

```bash
ssh -p 2222 <username>@192.0.2.10 "list"
```

Other examples include:

```bash
ssh -p 2222 <username>@192.0.2.10 "time query daytime"
ssh -p 2222 <username>@192.0.2.10 "weather query"
```

The available commands depend on the permissions of the connected account and the Minecraft server.

Each exec request ends with a status line and a machine-readable exit code:

| Exit code | Status | Meaning |
|---|---|---|
| `0` | `completed` | The command executed successfully in the game thread |
| `1` | `rejected` | Blank, over-long, unknown or failed command |
| `2` | `timed_out` | Submitted but not executed within `console-command-timeout-ms` (for example, the server is stopping) |
| `3` | `completion_unknown` | Could not be submitted (server not ready) — the result is unknown and the command may still run later |
| `4` | — | Completed, but output exceeded `max-console-output-bytes` and was truncated |

```bash
ssh -p 2222 mc@192.0.2.10 "list"; echo $?
# [sshserver] status=completed exit=0
# 0
```

### Run shell commands

For an account configured for shell access, you can execute operating system commands:

```bash
ssh -p 2222 <username>@192.0.2.10 "ls"
```

On Windows, the available commands depend on the shell and operating system environment.

Shell access and Minecraft console access are different operating modes. Use the appropriate account and mode for the operation you intend to perform.

## File transfers with SFTP

If SFTP is enabled for your account, connect using:

```bash
sftp -P 2222 <username>@192.0.2.10
```

Common SFTP commands include:

```text
ls
pwd
cd world
get server.properties
put server.properties
exit
```

Use SFTP to manage files without requiring a graphical file-transfer client.

**File access considerations:** Treat SFTP access as access to server data. Review the permissions of the Minecraft process and avoid granting file-transfer access to untrusted users.

## Troubleshooting

### I cannot connect

- Verify that the Minecraft server is running.
- Check the configured bind address and SSH port.
- Confirm that the firewall allows connections to the configured port.
- Check the server log for startup errors.
- Verify that your client is connecting to the correct address.

### Authentication fails

- Verify the configured username and authentication method.
- Check the server log for authentication errors.
- Confirm that your SSH client is using the intended key or password.
- Review whether password authentication or public-key authentication is enabled for the account.

### Minecraft commands return incomplete output

Exec mode always reports a machine-readable status: check the final `[sshserver] status=... exit=...` line and the shell exit code against the table above. `status=rejected` means the command did not run (blank, too long, unknown, or failed); `status=timed_out` means the game thread did not execute it before `console-command-timeout-ms`; `status=completion_unknown` means it could not be submitted. If output was cut off, the last line of the captured output is a truncation marker naming `max-console-output-bytes`.

When reporting an issue, include the exact command, the status line, exit code, Minecraft version, mod version, and relevant server logs.

### Interactive terminal applications do not work correctly

Full-screen applications such as `vim` require a proper pseudo-terminal (PTY). Interactive terminal support may be limited in versions that do not implement PTY allocation.

If you need advanced interactive shell functionality, check the limitations documented for your installed release.

### The server fails to start

- Confirm that the Minecraft and NeoForge versions match the supported release.
- Verify that Java 21 is being used.
- Check the configuration syntax and file paths.
- Look for errors related to SSH initialization, port binding, or missing dependencies.

## Known Limitations

Depending on the installed version, the following limitations may apply:

- Interactive shell sessions may not support PTY allocation.
- Some terminal applications may not work as expected.
- Minecraft command output collection may not handle every long-running or high-volume command perfectly.
- Compatibility outside the versions explicitly tested by the release is not guaranteed.

See the project's [Issues](https://github.com/woyxiang/ssh-server-mc/issues) page for known problems and ongoing development.

## Contributing and Bug Reports

Bug reports, feature requests, and contributions are welcome.

When opening an issue, please include:

- Minecraft version
- NeoForge version
- SSH Server MC version
- Operating system
- Steps to reproduce the problem
- Relevant server logs, with passwords, private keys, IP addresses, and other sensitive information removed

Please avoid posting credentials or production configuration files in public issues.

## License

See the [LICENSE](LICENSE) file for the project's license.

Third-party dependencies may be distributed under their own licenses.

## Disclaimer

SSH Server MC provides remote access to a Minecraft server and may expose operating system command execution or server file access, depending on its configuration.

You are responsible for securing your server, configuring appropriate access controls, and protecting your credentials. Do not expose the service to untrusted networks without understanding the associated risks.

---

**Repository:** [woyxiang/ssh-server-mc](https://github.com/woyxiang/ssh-server-mc)

If you find this project useful, consider starring the repository, reporting bugs, or contributing improvements.