# Changelog

本项目所有值得注意的变更都记录在此文件中。

格式参考 [Keep a Changelog](https://keepachangelog.com/zh-CN/1.1.0/)，
版本号遵循 [语义化版本](https://semver.org/lang/zh-CN/)。

## [Unreleased]

## [0.1.1] - 2026-10-17

### Added

- GitHub Actions CI（`.github/workflows/ci.yml`）：Gradle 构建缓存、单元测试、
  构建发行 JAR 与 SHA-256 校验和并归档产物（REL-02）。

- `Gradle Wrapper 8.10.2`（`gradlew` / `gradlew.bat`），固定构建工具版本并校验发行包
  SHA-256，实现可复现构建（REL-01）。
- `THIRD-PARTY-LICENSES.md`、`LICENSE-APACHE`、`NOTICE`：shaded 依赖许可证清单、
  Apache License 2.0 全文与 Apache MINA SSHD 归属声明；发行 JAR 内同时携带
  `LICENSE` / `LICENSE-APACHE` / `NOTICE` / `THIRD-PARTY-LICENSES.md`（REL-04/05）。
- `releaseChecksums` Gradle 任务：生成发行 JAR 的 SHA-256 校验和（`.sha256` 文件）。

### Changed

- `neoforge.mods.toml` 兼容声明收紧为实测范围：NeoForge `[21.1.77,21.2)`、
  Minecraft `[1.21.1,1.21.2)`；此前声明的 `[21.1.0,)` / `[1.21,1.22)` 超出实测矩阵
  （FR-12，先窄后宽）。使用未声明版本组合的用户需升级 NeoForge/Minecraft 版本。

- README「License」一节改为明确 MIT 许可与随附第三方组件许可声明（FR-07）。

### Fixed

- 版本号注入：修复修改 `mod_version` 后增量构建仍把旧版本号打进 jar 的问题
  （`processResources` 未声明展开属性为任务输入）。
## [0.1.0] - 2026-10-16

### Added

- 安全基线（SEC）：
  - 新配置默认仅监听 `127.0.0.1`；首次启动生成带注释的配置文件，
    空凭据默认拒绝一切登录；显式设置 `bind=0.0.0.0` 时输出暴露警告。
  - 密码 / 公钥认证可分别用 `password-auth-enabled`、`public-key-auth-enabled` 开关。
  - 按 IP 的认证失败锁定（`AuthRateLimiter`，无 `sleep` 占线程）；
    连接数上限、空闲与握手超时（`max-connections`、`max-auth-requests`、
    `idle-timeout-seconds` 等）。
  - Host Key 首次生成并持久化，启动输出 SHA256 指纹与文件权限检查。
  - shell / console / sftp 全局开关；Console 虚拟用户始终禁用 SFTP 与物理 Shell。
- Console 可靠性（CON）：
  - 单命令执行不再以固定 3 秒等待判断完成：新增执行结果语义
    （`completed` / `rejected` / `timed_out` / `completion_unknown`）与退出码。
  - 每次请求独立的输出收集器，`max-console-output-bytes` 字节上限与截断标记，
    `max-console-command-length` 命令长度限制。
- Shell 行编辑：服务端 Backspace 编辑、Ctrl+C 中断前台命令、Ctrl+D 退出语义、
  PowerShell 提示符显示当前目录。
- 32 个单元测试（配置解析、认证、限流、输出截断、退出码语义等）。

### Changed

- 构建解析对 `maven.neoforged.net` DNS 污染具备容错（仓库过滤 + DoH CNAME 改写）。

### Deprecated

- 配置项 `auth` 已废弃并被忽略，请改用 `password-auth-enabled` /
  `public-key-auth-enabled`；启动日志会提示迁移。

## [0.0.1] - 2026-10-14

### Added

- 初始原型：内嵌 SSH/SFTP 服务器（Apache MINA SSHD），支持物理 Shell、
  MC 控制台命令（虚拟用户）与 SFTP 文件传输；密码与可选公钥认证；
  Ed25519 Host Key 首次启动生成。

[Unreleased]: https://github.com/woyxiang/ssh-server-mc/compare/v0.1.1...HEAD
[0.1.1]: https://github.com/woyxiang/ssh-server-mc/compare/v0.1.0...v0.1.1
[0.1.0]: https://github.com/woyxiang/ssh-server-mc/compare/v0.0.1...v0.1.0
[0.0.1]: https://github.com/woyxiang/ssh-server-mc/releases/tag/v0.0.1
