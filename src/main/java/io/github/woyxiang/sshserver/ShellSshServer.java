package io.github.woyxiang.sshserver;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.PublicKey;
import java.time.Duration;
import java.util.List;
import java.util.Optional;

import org.apache.sshd.common.keyprovider.FileKeyPairProvider;
import org.apache.sshd.core.CoreModuleProperties;
import org.apache.sshd.server.SshServer;
import org.apache.sshd.server.command.Command;
import org.apache.sshd.server.channel.ChannelSession;
import org.apache.sshd.server.config.keys.AuthorizedKeysAuthenticator;
import org.apache.sshd.server.session.ServerSession;
import org.apache.sshd.sftp.server.SftpSubsystemFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * SSH 服务器装配：
 * - 首启自动生成配置（{@link SshConfig}）：默认仅监听 127.0.0.1、空凭据拒绝一切登录；
 * - 双模式登录：console-user 进 MC 控制台；其他已配用户进宿主物理 shell；
 * - 认证：密码（sshserver.properties）+ 公钥（authorized_keys/<用户名>），各自有全局开关；
 * - 防爆破：按 IP 失败限流（冷却窗口，不 sleep 不占线程）+ MINA 原生
 *   会话数/每会话认证次数/认证与空闲超时限制；
 * - 功能开关：shell / console / sftp 全局启停；SFTP 对 console 用户始终禁止。
 *
 * 所有异常吞掉只记日志，绝不影响 MC 进程（配置无效则拒绝启动 SSH 并给出原因）。
 */
public final class ShellSshServer {

    private final SshConfig config;
    private final AuthRateLimiter limiter;
    private final AuthService auth;
    private Path hostKey;

    private ShellSshServer(SshConfig config) {
        this.config = config;
        this.limiter = new AuthRateLimiter(config.authFailuresPerIp(), config.authLockoutSeconds());
        this.auth = new AuthService(config, limiter);
    }

    /** 配置目录：按 mod 惯例放 config 下（<gamedir>/config/sshd），可用 -Dsshserver.base=... 覆盖。 */
    public static Path defaultBase() {
        return McBridge.gameDir().resolve("config").resolve("sshd").toAbsolutePath().normalize();
    }

    /** 启动并阻塞（供线程调用）。任何失败只记日志，不抛出。 */
    public static void run(Path base) {
        try {
            new ShellSshServer(SshConfig.loadOrCreate(base)).run();
        } catch (Throwable t) {
            LOG.error("[sshserver] sshd 启动失败: {}", McBridge.rootMessage(t));
        }
    }

    private void run() throws IOException {
        SshServer sshd = build();
        try {
            sshd.start();
        } catch (Throwable t) {
            throw new IOException("sshd 监听 " + config.bind() + ":" + config.port() + " 失败: "
                    + McBridge.rootMessage(t), t);
        }
        summary();
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try {
                sshd.stop(true);
            } catch (Throwable ignored) {
                // 进程正在退出
            }
        }, "sshserver-stop"));
        try {
            Thread.currentThread().join(); // 常驻
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** 装配但不启动（配置/密钥错误在监听端口前就抛出，不留半生效状态）。 */
    private SshServer build() throws IOException {
        SshServer sshd = SshServer.setUpDefaultServer();
        sshd.setHost(config.bind());
        sshd.setPort(config.port());
        hostKey = HostKeyManager.ensureKey(config.base());
        sshd.setKeyPairProvider(new FileKeyPairProvider(hostKey));
        if (config.passwordAuthEnabled()) {
            sshd.setPasswordAuthenticator(this::authenticate);
        }
        if (config.publicKeyAuthEnabled()) {
            sshd.setPublickeyAuthenticator(this::authenticateKey);
        }

        // 资源限制：MINA 原生强制执行（并发会话数 / 每会话认证次数 / 认证阶段与空闲超时）
        var props = sshd.getProperties();
        props.put(CoreModuleProperties.MAX_CONCURRENT_SESSIONS.getName(), config.maxConnections());
        props.put(CoreModuleProperties.MAX_AUTH_REQUESTS.getName(), config.maxAuthRequests());
        // 注意：值必须是 MINA 的「存储格式」——getProperties() 是裸 map，绕过 Property.toStorage()：
        // Duration 若直接塞对象，会话 announce 阶段会数字解析 "PT30S" 抛 NumberFormatException，
        // 连接在发 banner 前就被静默关闭；DurationProperty 正确存储格式是毫秒 Long（读取端 toLong→ofMillis）。
        props.put(CoreModuleProperties.AUTH_TIMEOUT.getName(), Duration.ofSeconds(config.authTimeoutSeconds()).toMillis());
        if (config.idleTimeoutSeconds() > 0) {
            props.put(CoreModuleProperties.IDLE_TIMEOUT.getName(), Duration.ofSeconds(config.idleTimeoutSeconds()).toMillis());
        }

        sshd.setCommandFactory((channel, command) -> route(channel, command));
        sshd.setShellFactory(channel -> route(channel, null));
        if (config.sftpEnabled()) {
            sshd.setSubsystemFactories(List.of(new SftpSubsystemFactory() { // console 用户禁用 SFTP
                @Override
                public Command createSubsystem(ChannelSession channel) throws IOException {
                    if (isConsoleUser(channel)) {
                        throw new IOException("SFTP disabled for console user '" + config.consoleUser() + "'");
                    }
                    return super.createSubsystem(channel);
                }
            }));
        }
        return sshd;
    }

    /** 按用户与功能开关路由：console 用户 → MC 控制台，其余 → 物理 shell；开关关闭则拒绝开通道。 */
    private Command route(ChannelSession channel, String command) throws IOException {
        if (isConsoleUser(channel)) {
            if (!config.consoleEnabled()) {
                throw new IOException("MC 控制台功能已禁用（console-enabled=false）");
            }
            return (command == null || command.isBlank())
                    ? new McConsoleCommand()
                    : new ConsoleExecCommand(command, config);
        }
        if (!config.shellEnabled()) {
            throw new IOException("Shell 功能已禁用（shell-enabled=false）");
        }
        return new PhysicalShellCommand(command == null
                ? BashShell.interactiveCommand()
                : BashShell.execCommand(command), command == null);
    }

    private boolean isConsoleUser(ChannelSession channel) {
        String consoleUser = config.consoleUser();
        return !consoleUser.isEmpty() && consoleUser.equals(channel.getServerSession().getUsername());
    }

    private boolean authenticate(String username, String password, ServerSession session) {
        String ip = ipOf(session).orElse(null);
        AuthService.Result result = auth.passwordAuth(username, password, ip);
        log("auth", username + " from=" + session.getRemoteAddress() + " result=" + result.name().toLowerCase());
        if (result == AuthService.Result.LOCKED) {
            session.close(true); // 冷却期内快速断开，不给继续尝试的机会
        }
        return result == AuthService.Result.OK;
    }

    private boolean authenticateKey(String username, PublicKey key, ServerSession session) {
        Path file = config.base().resolve("authorized_keys").resolve(username);
        if (!Files.isRegularFile(file)) {
            return false;
        }
        boolean ok;
        try {
            ok = new AuthorizedKeysAuthenticator(file).authenticate(username, key, session);
        } catch (Throwable t) {
            log("pubkey", "读取/解析 " + file + " 失败: " + McBridge.rootMessage(t));
            ok = false;
        }
        // 公钥失败不计数：客户端按顺序尝试多个 key 是正常协商，防爆破只针对密码
        log("pubkey", username + " from=" + session.getRemoteAddress() + " ok=" + ok);
        return ok;
    }

    /** 启动摘要：不输出任何秘密；凭据未配置、非本机监听等安全状态一目了然。 */
    private void summary() {
        for (String w : config.warnings()) {
            log("config", "警告: " + w);
        }
        log("sshd", "listening on " + config.bind() + ":" + config.port()
                + (config.isLoopbackBind() ? "（仅本机访问）" : "（⚠ 对网络开放）"));
        log("auth", "password=" + onOff(config.passwordAuthEnabled())
                + " pubkey=" + onOff(config.publicKeyAuthEnabled())
                + " console-user=" + (config.consoleUser().isEmpty() ? "(none)" : config.consoleUser())
                + " console-password=" + (config.consolePassword().isEmpty() ? "未设置（控制台登录禁用）" : "已设置"));
        if (config.noCredentialsConfigured()) {
            log("auth", "未配置任何可用凭据，所有登录都会被拒绝（请设置 users 或 console-password）");
        }
        log("features", "shell=" + onOff(config.shellEnabled())
                + " console=" + onOff(config.consoleEnabled())
                + " sftp=" + onOff(config.sftpEnabled())
                + (config.users().isEmpty() ? "" : " shell-users=" + String.join(",", config.users().keySet())));
        log("limits", "max-connections=" + config.maxConnections()
                + " max-auth-requests=" + config.maxAuthRequests()
                + " auth-failures-per-ip=" + config.authFailuresPerIp()
                + "/lockout-" + config.authLockoutSeconds() + "s"
                + " auth-timeout=" + config.authTimeoutSeconds() + "s"
                + " idle-timeout=" + config.idleTimeoutSeconds() + "s");
        String fp = HostKeyManager.fingerprint(hostKey);
        log("hostkey", (fp != null ? fp : "指纹不可用") + " (" + hostKey + ")");
        log("config", String.valueOf(config.file()));
    }

    private static String onOff(boolean b) {
        return b ? "on" : "off";
    }

    private static Optional<String> ipOf(ServerSession session) {
        try {
            return Optional.of(((InetSocketAddress) session.getRemoteAddress()).getAddress().getHostAddress());
        } catch (Throwable t) {
            return Optional.empty();
        }
    }

    private static final Logger LOG = LoggerFactory.getLogger("sshserver");
    private static void log(String tag, String msg) {
        LOG.info("[sshserver] {}: {}", tag, msg);
    }

    /** 独立运行入口：java -cp sshserver.jar io.github.woyxiang.sshserver.ShellSshServer（无 MC，控制台模式降级）。 */
    public static void main(String[] args) {
        Path base = args.length > 0 ? Path.of(args[0]) : defaultBase();
        run(base);
    }
}
