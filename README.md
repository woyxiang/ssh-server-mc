# SSH Server

给 NeoForge 服务端内嵌一个 SSH 服务器：装一个 mod，就能用 `ssh` 远程操作服务器的宿主 shell 和 MC 控制台，不需要外部 sshd，也不需要 RCON。

## 它能做什么

| 你想做的事 | 以前 | 装了 SSH Server |
|---|---|---|
| 在服务器宿主上执行命令（装 mod、改文件、看进程） | 面板/物理机登录 | `ssh user@host -p 2222 "命令"` |
| 传文件进/出服务器 | 面板文件管理器 | `sftp -P 2222 user@host` |
| 执行 MC 命令（whitelist、op、stop…） | RCON / 面板控制台 | `ssh mc@host -p 2222` 或 `ssh mc@host "whitelist add xxx"` |
| 实时看服务器日志 | 翻 latest.log | 登录 `mc` 用户，日志自动推到会话里 |

双模式设计：

- **物理 shell 模式** — 用配置文件里定义的真实用户名登录，得到 MC 进程所在宿主的真实 shell（Linux 是 bash，Windows 是 cmd）
- **MC 控制台模式** — 用虚拟用户名（默认 `mc`）登录，得到一个 MC 控制台会话：输入 MC 命令、实时回显服务器日志

## 环境要求

- Minecraft 1.21.x / NeoForge 21.1+（**仅服务端**，客户端装了也不会起 SSH）
- 宿主有 `bash`（Linux）或 `cmd.exe`（Windows）——一般都有

## 安装

1. 把 `sshserver-x.y.z.jar` 放进服务器的 `mods/` 目录
2. 启动一次服务器——mod 会在游戏目录下创建 `config/sshd/` 工作目录并自动生成 SSH host key（`ssh_host_ed25519_key`，**会持久保留，客户端首次连接的指纹提示就来自它**）
3. 首次启动会因为没有配置文件而在日志里报一行 `[sshserver] sshd 退出: 缺少配置文件 .../config/sshd/sshserver.properties`，这是预期的——下一步写配置

## 配置

编辑 `<游戏目录>/config/sshd/sshserver.properties`：

```properties
# SSH 监听端口
port=2222

# 监听地址。0.0.0.0 = 所有网卡（请确认防火墙策略）；
# 只想本机/内网用就写 127.0.0.1，配合 SSH 隧道访问
bind=0.0.0.0

# 物理 shell 用户，格式 用户名:密码，逗号分隔多个
# 注意：这些用户名只用于 SSH 登录，与宿主系统账户无关！
# 登录后执行的命令以「运行 MC 进程的系统用户」身份运行
users=root:改成你的密码,admin:另一个密码

# MC 控制台的虚拟用户名（留空 = 禁用控制台模式）
console-user=mc

# mc 用户的密码
console-password=改成你的密码
```

重启服务器（改配置必须重启），日志出现这行即成功：

```
[sshserver] sshd listening on 0.0.0.0:2222 (base=/path/to/sshd, shell=[root,admin] console=[mc])
```

### 公钥登录（可选，推荐）

把 OpenSSH 公钥放进 `config/sshd/authorized_keys/<用户名>`（一个文件对应一个用户，标准 authorized_keys 格式）：

```bash
# 本机执行，把你的公钥装到服务器的 root 用户上
cat ~/.ssh/id_ed25519.pub | ssh root@host -p 2222 "cat >> config/sshd/authorized_keys/root"
# 或者手动编辑服务器上的 config/sshd/authorized_keys/root 文件
```

配置了公钥的用户可以免密登录，密码认证同时仍然有效。

## 使用

### 物理 shell

```bash
ssh root@host -p 2222              # 交互式 shell
ssh root@host -p 2222 "ls mods"    # 单命令（exec 模式），输出后自动退出
sftp -P 2222 root@host             # SFTP 文件传输
```

### MC 控制台

```bash
ssh mc@host -p 2222                # 交互式控制台
ssh mc@host -p 2222 "list"         # 单命令，等 3 秒收集输出后退出
```

交互式登录后你会看到 motd（服务器状态 + 在线人数），之后：

- 直接输入 MC 命令（`list`、`whitelist add xxx`、`say hi`、`stop`…），等同在服务器控制台输入
- 服务器日志实时回显在你的会话里（其他玩家加入、报错等都能看到）
- `exit` 或 `quit` 退出；`mc` 用户不能使用 SFTP

## 安全模型

- **防爆破**：认证失败每次延迟 3 秒；同一 IP 累计失败 3 次直接断开
- **密码存储**：`sshserver.properties` 是明文，请确保文件权限（该目录只有运行 MC 的用户可读）
- **host key**：首次启动生成后固定不变；更换会导致客户端报 host key 警告（删除 `config/sshd/ssh_host_ed25519_key*` 可重置）
- **网络暴露**：`bind=0.0.0.0` 会把 SSH 暴露到所有网卡。公网服务器建议：
  - 保持 `bind=127.0.0.1`，用跳板/隧道访问；或
  - 防火墙只放行可信 IP；或
  - 全部用户改用公钥认证，并设置强密码兜底

## 构建

标准 Gradle 工程（ModDevGradle 插件，依赖自动从 Maven 下载）：

```bash
gradle build       # 需要 JDK 21（工具链自动探测）
# 产物: build/libs/sshserver-<版本>.jar（可直接放入 mods/）
```

依赖关系由 shadow 合并处理：

- MINA SSHD（core/common/sftp）+ eddsa 打进 mod jar
- **slf4j 排除在外**——NeoForge 自带 `org.slf4j` 模块，重复的包会导致 JPMS `ResolutionException` 崩服
- MINA sshd 的部分包横跨 core/common 两个 jar（历史设计），因此不能用 Jar-in-Jar 分发，必须合并

本地起一个开发服务器：`gradle runServer`。
## 已知限制

- 物理 shell 没有 PTY：没有 tab 补全 / 光标控制，vim 这类全屏程序显示会乱（日常命令、脚本执行没问题）
- Windows 交互模式使用 PowerShell（回显与行编辑由 PowerShell 自带）；cmd 仅用于 exec 单命令模式（`chcp 65001` 输出 UTF-8）——cmd 在 UTF-8 码页下从管道读多字节输入会异常退出（Windows 自身缺陷），故交互模式不用 cmd
- exec 模式的 MC 命令输出固定等 3 秒（命令输出要经服务器日志回流），大输出的命令可能被截断
- MC 控制台的命令由服务器主线程执行，刷屏类命令（如超大范围的 `forceload`）会卡服务器，请像对待服务器控制台一样对待它

## License

MIT
