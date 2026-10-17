# Third-Party Licenses

本文件列出 SSH Server 发行 JAR 中随附（shaded）的第三方组件及其许可证。
构建产物 `build/libs/sshserver-*.jar` 与发布归档应同时包含本文件、`LICENSE`（本项目，MIT）、
`LICENSE-APACHE`（Apache License 2.0 全文）与 `NOTICE`（Apache 归属声明）。

## 随附（打包进 JAR）的依赖

| 组件 | 版本 | 许可证 | 说明 |
|---|---|---|---|
| [Apache MINA SSHD](https://mina.apache.org/sshd-project/) (`sshd-core`) | 2.14.0 | Apache License 2.0 | 版权 2018-2024 The Apache Software Foundation |
| Apache MINA SSHD (`sshd-common`) | 2.14.0 | Apache License 2.0 | 同上 |
| Apache MINA SSHD (`sshd-sftp`) | 2.14.0 | Apache License 2.0 | 同上 |
| [EdDSA](https://github.com/str4d/ed25519-java) (`net.i2p.crypto:eddsa`) | 0.3.0 | CC0 1.0 Universal | ssh-ed25519 算法实现，MINA 2.14 依赖 |

- Apache License 2.0 全文见发行物中的 `LICENSE-APACHE`；Apache 归属声明见 `NOTICE`。
- CC0 1.0 Universal 为公有领域贡献声明，无署名义务：<https://creativecommons.org/publicdomain/zero/1.0/>。

## 未随附的依赖（仅供参考）

| 组件 | 版本 | 许可证 | 用途 |
|---|---|---|---|
| NeoForge / FML | 21.1.77 / 4.0.45 | LGPL-3.0（NeoForge） | 运行时由服务端提供，不打包 |
| SLF4J API | （随 NeoForge 提供） | MIT | 显式排除，不打包 |
| JUnit Jupiter | 5.10.2 | EPL-2.0 | 仅单元测试使用，不打包 |
