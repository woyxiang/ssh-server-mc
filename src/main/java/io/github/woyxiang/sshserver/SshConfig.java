package io.github.woyxiang.sshserver;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
/**
 * sshserver.properties 的加载、校验与首次启动生成：
 * - 首次启动在配置目录生成带注释的默认配置（默认监听 127.0.0.1、空凭据拒绝一切登录）；
 * - 旧配置缺项时按默认值补齐并给出警告，绝不静默改写用户的配置文件；
 * - 无效取值（端口越界、非法布尔等）汇总后拒绝启动，不留下半生效状态。
 */
public final class SshConfig {

    /** 配置项无效：抛出即拒绝启动，消息含全部错误、键名与期望值。 */
    public static final class InvalidConfigException extends Exception {
        public InvalidConfigException(String message) {
            super(message);
        }
    }

    private static final Map<String, String> DEFAULTS = new LinkedHashMap<>();

    static {
        DEFAULTS.put("bind", "127.0.0.1");
        DEFAULTS.put("port", "2222");
        DEFAULTS.put("password-auth-enabled", "true");
        DEFAULTS.put("public-key-auth-enabled", "true");
        DEFAULTS.put("users", "");
        DEFAULTS.put("console-user", "mc");
        DEFAULTS.put("console-password", "");
        DEFAULTS.put("shell-enabled", "true");
        DEFAULTS.put("console-enabled", "true");
        DEFAULTS.put("sftp-enabled", "true");
        DEFAULTS.put("max-connections", "8");
        DEFAULTS.put("max-auth-requests", "3");
        DEFAULTS.put("auth-failures-per-ip", "5");
        DEFAULTS.put("auth-lockout-seconds", "60");
        DEFAULTS.put("auth-timeout-seconds", "30");
        DEFAULTS.put("idle-timeout-seconds", "900");
        DEFAULTS.put("max-console-command-length", "2048");
        DEFAULTS.put("max-console-output-bytes", "1048576");
        DEFAULTS.put("console-command-timeout-ms", "5000");
    }

    /** 首次启动写入的模板：与 DEFAULTS 的键集一致。 */
    static final String TEMPLATE = """
            # sshserver 配置文件（首次启动自动生成）
            # 修改后重启服务器生效。此文件不会被自动改写；新增配置项请手动补充，缺失时按默认值运行。
            # 注意：本文件可能包含明文密码，请勿提交到版本库。

            # 监听地址与端口。默认仅本机可访问；设为 0.0.0.0 将对整个网络开放（请先配置强密码）。
            bind=127.0.0.1
            port=2222

            # 认证开关。关闭后对应认证方式将被直接拒绝。
            password-auth-enabled=true
            public-key-auth-enabled=true

            # Shell 用户：逗号分隔，格式 用户名:密码。留空则拒绝所有 shell 密码登录。
            # 另外可在本目录 authorized_keys/<用户名> 放置 OpenSSH 公钥文件启用公钥登录。
            users=

            # 控制台用户：以该用户名登录进入 MC 控制台。console-password 留空则禁止控制台登录。
            console-user=mc
            console-password=

            # 功能开关：物理 shell / MC 控制台 / SFTP。
            shell-enabled=true
            console-enabled=true
            sftp-enabled=true

            # 资源限制（防爆破与滥用）。
            max-connections=8
            max-auth-requests=3
            auth-failures-per-ip=5
            auth-lockout-seconds=60
            auth-timeout-seconds=30
            idle-timeout-seconds=900

            # MC 控制台 exec 模式（ssh mc@host "命令"）限制。
            max-console-command-length=2048
            max-console-output-bytes=1048576
            console-command-timeout-ms=5000
            """;

    private final Path file;
    private final String bind;
    private final int port;
    private final boolean passwordAuthEnabled;
    private final boolean publicKeyAuthEnabled;
    private final Map<String, String> users;
    private final String consoleUser;
    private final String consolePassword;
    private final boolean shellEnabled;
    private final boolean consoleEnabled;
    private final boolean sftpEnabled;
    private final int maxConnections;
    private final int maxAuthRequests;
    private final int authFailuresPerIp;
    private final int authLockoutSeconds;
    private final int authTimeoutSeconds;
    private final int idleTimeoutSeconds;
    private final int maxConsoleCommandLength;
    private final long maxConsoleOutputBytes;
    private final long consoleCommandTimeoutMs;
    private final List<String> warnings;

    private static final Logger LOG = LoggerFactory.getLogger("sshserver");
    public static SshConfig loadOrCreate(Path base) throws IOException, InvalidConfigException {
        Files.createDirectories(base);
        Path file = base.resolve("sshserver.properties");
        if (Files.exists(file) && !Files.isRegularFile(file)) {
            throw new IOException("配置路径存在但不是普通文件: " + file);
        }
        if (!Files.isRegularFile(file)) {
            Files.writeString(file, TEMPLATE, StandardCharsets.UTF_8);
            LOG.info("[sshserver] 首次启动：已生成默认配置 {}（仅监听 127.0.0.1，未配置任何凭据，所有登录都会被拒绝）", file);
        }
        Properties props = new Properties();
        try (InputStream in = Files.newInputStream(file)) {
            props.load(in);
        }
        return new SshConfig(file, props);
    }

    private SshConfig(Path file, Properties props) throws InvalidConfigException {
        this.file = file;
        List<String> errors = new ArrayList<>();
        List<String> warns = new ArrayList<>();

        // 旧配置缺项 → 默认值 + 警告；生成的模板自带全部键，不产生这类警告
        for (Map.Entry<String, String> e : DEFAULTS.entrySet()) {
            if (!props.containsKey(e.getKey())) {
                warns.add("配置缺少 " + e.getKey() + "，使用默认值 " + e.getValue());
            }
        }
        for (String name : props.stringPropertyNames()) {
            if (!DEFAULTS.containsKey(name)) {
                if (name.equals("auth")) {
                    warns.add("配置项 auth 已废弃并被忽略，请改用 password-auth-enabled / public-key-auth-enabled");
                } else {
                    warns.add("未知配置项 " + name + "（请检查拼写）");
                }
            }
        }

        bind = value(props, "bind").trim();
        if (bind.isEmpty()) {
            errors.add("配置项 bind 不能为空（默认 127.0.0.1 仅本机访问）");
        }
        if (bind.equals("0.0.0.0") || bind.equals("[::]") || bind.equals("::")) {
            warns.add("bind=" + bind + " 将监听所有网络接口，请确认已配置强密码并了解暴露风险");
        }
        port = intProp(props, "port", 1, 65535, errors);
        passwordAuthEnabled = boolProp(props, "password-auth-enabled", errors);
        publicKeyAuthEnabled = boolProp(props, "public-key-auth-enabled", errors);
        consoleUser = value(props, "console-user").trim();
        consolePassword = value(props, "console-password");
        shellEnabled = boolProp(props, "shell-enabled", errors);
        consoleEnabled = boolProp(props, "console-enabled", errors);
        sftpEnabled = boolProp(props, "sftp-enabled", errors);
        maxConnections = intProp(props, "max-connections", 1, 128, errors);
        maxAuthRequests = intProp(props, "max-auth-requests", 1, 10, errors);
        authFailuresPerIp = intProp(props, "auth-failures-per-ip", 1, 100, errors);
        authLockoutSeconds = intProp(props, "auth-lockout-seconds", 1, 86400, errors);
        authTimeoutSeconds = intProp(props, "auth-timeout-seconds", 1, 3600, errors);
        idleTimeoutSeconds = intProp(props, "idle-timeout-seconds", 0, 86400, errors);
        maxConsoleCommandLength = intProp(props, "max-console-command-length", 1, 65536, errors);
        maxConsoleOutputBytes = longProp(props, "max-console-output-bytes", 1024, 64L * 1024 * 1024, errors);
        consoleCommandTimeoutMs = longProp(props, "console-command-timeout-ms", 1000, 600000, errors);

        Map<String, String> parsed = new LinkedHashMap<>();
        for (String entry : value(props, "users").split(",")) {
            String e = entry.trim();
            if (e.isEmpty()) {
                continue;
            }
            int i = e.indexOf(':');
            if (i > 0 && i < e.length() - 1) {
                parsed.put(e.substring(0, i).trim(), e.substring(i + 1).trim());
            } else {
                warns.add("users 中的 '" + e + "' 格式无效（应为 用户名:密码），已忽略");
            }
        }
        if (!consoleUser.isEmpty() && parsed.containsKey(consoleUser)) {
            warns.add("用户 '" + consoleUser + "' 同时出现在 users 与 console-user，控制台密码（console-password）优先");
        }
        users = Collections.unmodifiableMap(parsed);

        warnings = List.copyOf(warns);
        if (!errors.isEmpty()) {
            throw new InvalidConfigException("配置文件 " + file + " 存在无效项: " + String.join("; ", errors));
        }
    }

    private static String value(Properties props, String key) {
        return props.getProperty(key, DEFAULTS.get(key));
    }

    private int intProp(Properties props, String key, int min, int max, List<String> errors) {
        String raw = valueOrEmpty(props, key);
        if (raw.isEmpty()) {
            return Integer.parseInt(DEFAULTS.get(key));
        }
        try {
            int v = Integer.parseInt(raw.trim());
            if (v < min || v > max) {
                errors.add("配置项 " + key + "=" + raw + " 超出范围（应为 " + min + ".." + max + "）");
            }
            return v;
        } catch (NumberFormatException e) {
            errors.add("配置项 " + key + "=" + raw + " 无效：应为 " + min + ".." + max + " 的整数");
            return Integer.parseInt(DEFAULTS.get(key));
        }
    }

    private long longProp(Properties props, String key, long min, long max, List<String> errors) {
        String raw = valueOrEmpty(props, key);
        if (raw.isEmpty()) {
            return Long.parseLong(DEFAULTS.get(key));
        }
        try {
            long v = Long.parseLong(raw.trim());
            if (v < min || v > max) {
                errors.add("配置项 " + key + "=" + raw + " 超出范围（应为 " + min + ".." + max + "）");
            }
            return v;
        } catch (NumberFormatException e) {
            errors.add("配置项 " + key + "=" + raw + " 无效：应为 " + min + ".." + max + " 的整数");
            return Long.parseLong(DEFAULTS.get(key));
        }
    }

    private boolean boolProp(Properties props, String key, List<String> errors) {
        String raw = valueOrEmpty(props, key);
        if (raw.isEmpty()) {
            return Boolean.parseBoolean(DEFAULTS.get(key));
        }
        String t = raw.trim();
        if (t.equalsIgnoreCase("true")) {
            return true;
        }
        if (t.equalsIgnoreCase("false")) {
            return false;
        }
        errors.add("配置项 " + key + "=" + raw + " 无效：应为 true 或 false");
        return Boolean.parseBoolean(DEFAULTS.get(key));
    }

    private static String valueOrEmpty(Properties props, String key) {
        return props.getProperty(key, "");
    }

    public Path file() {
        return file;
    }

    public Path base() {
        return file.getParent();
    }

    public String bind() {
        return bind;
    }

    public boolean isLoopbackBind() {
        return bind.equals("127.0.0.1") || bind.equals("localhost") || bind.equals("::1");
    }

    public int port() {
        return port;
    }

    public boolean passwordAuthEnabled() {
        return passwordAuthEnabled;
    }

    public boolean publicKeyAuthEnabled() {
        return publicKeyAuthEnabled;
    }

    /** 用户名 → 密码；只读。 */
    public Map<String, String> users() {
        return users;
    }

    public String consoleUser() {
        return consoleUser;
    }

    public String consolePassword() {
        return consolePassword;
    }

    public boolean shellEnabled() {
        return shellEnabled;
    }

    public boolean consoleEnabled() {
        return consoleEnabled;
    }

    public boolean sftpEnabled() {
        return sftpEnabled;
    }

    public int maxConnections() {
        return maxConnections;
    }

    public int maxAuthRequests() {
        return maxAuthRequests;
    }

    public int authFailuresPerIp() {
        return authFailuresPerIp;
    }

    public int authLockoutSeconds() {
        return authLockoutSeconds;
    }

    public int authTimeoutSeconds() {
        return authTimeoutSeconds;
    }

    public int idleTimeoutSeconds() {
        return idleTimeoutSeconds;
    }

    public int maxConsoleCommandLength() {
        return maxConsoleCommandLength;
    }

    public long maxConsoleOutputBytes() {
        return maxConsoleOutputBytes;
    }

    public long consoleCommandTimeoutMs() {
        return consoleCommandTimeoutMs;
    }

    public List<String> warnings() {
        return warnings;
    }

    /** 当前没有任何可用凭据（空 users、无控制台密码、也没有任何公钥文件）→ 登录必然全被拒绝。 */
    public boolean noCredentialsConfigured() {
        if (!users.isEmpty() || !consolePassword.isEmpty()) {
            return false;
        }
        Path dir = base().resolve("authorized_keys");
        if (Files.isDirectory(dir)) {
            try (var files = Files.list(dir)) {
                if (files.anyMatch(Files::isRegularFile)) {
                    return false;
                }
            } catch (IOException ignored) {
                // 读取失败按「无公钥」保守处理，只影响启动摘要文案
            }
        }
        return true;
    }
}
