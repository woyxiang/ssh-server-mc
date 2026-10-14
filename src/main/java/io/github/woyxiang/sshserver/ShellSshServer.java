package io.github.woyxiang.sshserver;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import org.apache.sshd.common.config.keys.writer.openssh.OpenSSHKeyPairResourceWriter;
import org.apache.sshd.common.keyprovider.FileKeyPairProvider;
import org.apache.sshd.server.SshServer;
import org.apache.sshd.server.command.Command;
import org.apache.sshd.common.Factory;
import org.apache.sshd.server.channel.ChannelSession;
import org.apache.sshd.server.config.keys.AuthorizedKeysAuthenticator;
import org.apache.sshd.sftp.server.SftpSubsystemFactory;

/**
 * SSH 服务器装配：
 * - 双模式登录：虚拟用户（默认 mc）进 MC 控制台会话；其他已配置用户进宿主物理 shell；
 * - 认证：密码（sshserver.properties）+ 可选公钥（authorized_keys/<用户名>，OpenSSH 格式）；
 * - 防爆破：单 IP 失败计数，失败间延迟 3s，累计 3 次断开连接；
 * - SFTP：所有通过认证的用户可用。
 *
 * 所有异常吞掉只记日志，绝不影响 MC 进程。
 */
public final class ShellSshServer {

    /** 单 IP 认证失败记录：>=3 次断开。 */
    private final Map<String, AtomicInteger> failCounts = new ConcurrentHashMap<>();
    private static final int MAX_FAILS = 3;
    private static final long FAIL_DELAY_MS = 3000;

    private final Path base;
    private final Properties cfg;

    private ShellSshServer(Path base, Properties cfg) {
        this.base = base;
        this.cfg = cfg;
    }

    /** 配置目录：按 mod 惯例放 config 下（<gamedir>/config/sshd），可用 -Dsshserver.base=... 覆盖。 */
    public static Path defaultBase() {
        return McBridge.gameDir().resolve("config").resolve("sshd").toAbsolutePath().normalize();
    }

    /** 启动并阻塞（供线程调用）。bind 失败只记日志，不抛出。 */
    public static void run(Path base) {
        try {
            new ShellSshServer(base, loadConfig(base)).run();
        } catch (Throwable t) {
            System.out.println("[sshserver] sshd 退出: " + McBridge.rootMessage(t));
        }
    }

    private static Properties loadConfig(Path base) throws IOException {
        Path file = base.resolve("sshserver.properties");
        if (!Files.isRegularFile(file)) {
            throw new IOException("缺少配置文件 " + file + "（参考 README 生成）");
        }
        Properties cfg = new Properties();
        try (InputStream in = Files.newInputStream(file)) {
            cfg.load(in);
        }
        return cfg;
    }

    private void run() throws IOException, InterruptedException, java.security.GeneralSecurityException {
        int port = Integer.parseInt(cfg.getProperty("port", "2222"));
        String bind = cfg.getProperty("bind", "0.0.0.0");
        String consoleUser = cfg.getProperty("console-user", "mc").trim();

        SshServer sshd = SshServer.setUpDefaultServer();
        sshd.setHost(bind);
        sshd.setPort(port);
        sshd.setKeyPairProvider(new FileKeyPairProvider(ensureHostKey()));
        sshd.setPasswordAuthenticator(this::authenticate);
        sshd.setPublickeyAuthenticator(this::authenticateKey);

        sshd.setCommandFactory((channel, command) -> {
            String username = channel.getServerSession().getUsername();
            if (username.equals(consoleUser) && !consoleUser.isEmpty()) {
                return new McConsoleCommand(command); // exec 模式：command 作为初始 MC 命令
            }
            return new PhysicalShellCommand(BashShell.execCommand(command));
        });
        sshd.setShellFactory(channel -> {
            String username = channel.getServerSession().getUsername();
            if (username.equals(consoleUser) && !consoleUser.isEmpty()) {
                return new McConsoleCommand(null); // 交互模式
            }
            return new PhysicalShellCommand(BashShell.interactiveCommand());
        });
        sshd.setSubsystemFactories(List.of(new SftpSubsystemFactory() { // console 用户禁用 SFTP
            @Override
            public Command createSubsystem(ChannelSession channel) throws IOException {
                if (channel.getServerSession().getUsername().equals(consoleUser)) {
                    throw new IOException("SFTP disabled for console user '" + consoleUser + "'");
                }
                return super.createSubsystem(channel);
            }
        }));

        sshd.start();
        String modes = "shell=[" + String.join(",", users().keySet()) + "]"
                + (consoleUser.isEmpty() ? "" : " console=[" + consoleUser + "]");
        System.out.printf("[sshserver] sshd listening on %s:%d (base=%s, %s)%n", bind, port, base, modes);
        Thread.currentThread().join(); // 常驻
    }

    /** 用户名 → 密码。users=root:pwd1,admin:pwd2；console-user 的密码单独配 console-password。 */
    private Map<String, String> users() {
        Map<String, String> m = new ConcurrentHashMap<>();
        for (String entry : cfg.getProperty("users", "").split(",")) {
            String e = entry.trim();
            int i = e.indexOf(':');
            if (i > 0 && i < e.length() - 1) {
                m.put(e.substring(0, i).trim(), e.substring(i + 1).trim());
            }
        }
        return m;
    }

    private boolean authenticate(String username, String password, org.apache.sshd.server.session.ServerSession session) {
        boolean ok;
        if (cfg.getProperty("console-user", "mc").trim().equals(username)) {
            ok = !cfg.getProperty("console-password", "").isEmpty()
                    && cfg.getProperty("console-password").equals(password);
        } else {
            String expect = users().get(username);
            ok = expect != null && !expect.isEmpty() && constantTimeEquals(expect, password);
        }
        log("auth", username + " from=" + session.getRemoteAddress() + " ok=" + ok);
        // 公钥失败不计数不延迟：客户端按顺序尝试多个 key 是正常协商，防爆破只针对密码
        return ok;
    }

    private boolean authenticateKey(String username, java.security.PublicKey key,
                                    org.apache.sshd.server.session.ServerSession session) {
        Path file = base.resolve("authorized_keys").resolve(username);
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
        log("pubkey", username + " from=" + session.getRemoteAddress() + " ok=" + ok);
        return handleFailure(session, ok);
    }

    /** 失败处理：延迟拖慢爆破 + 计数达标断开。返回原 ok。 */
    private boolean handleFailure(org.apache.sshd.server.session.ServerSession session, boolean ok) {
        if (ok) {
            ipOf(session).ifPresent(ip -> failCounts.remove(ip));
            return true;
        }
        try {
            Thread.sleep(FAIL_DELAY_MS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return ipOf(session).map(ip -> {
            int n = failCounts.computeIfAbsent(ip, k -> new AtomicInteger()).incrementAndGet();
            if (n >= MAX_FAILS) {
                log("auth", "too many failures from " + ip + ", closing session");
                failCounts.remove(ip);
                session.close(true);
            }
            return false;
        }).orElse(false);
    }

    private static java.util.Optional<String> ipOf(org.apache.sshd.server.session.ServerSession session) {
        try {
            return java.util.Optional.of(((InetSocketAddress) session.getRemoteAddress()).getAddress().getHostAddress());
        } catch (Throwable t) {
            return java.util.Optional.empty();
        }
    }

    private static boolean constantTimeEquals(String a, String b) {
        byte[] x = a.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        byte[] y = b.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        int r = x.length ^ y.length;
        for (int i = 0; i < Math.min(x.length, y.length); i++) {
            r |= x[i] ^ y[i];
        }
        return r == 0;
    }

    private Path ensureHostKey() throws IOException, java.security.GeneralSecurityException {
    /** 主机密钥：不存在则自动生成 Ed25519（OpenSSH PEM 格式写入）。 */
        Path key = base.resolve("ssh_host_ed25519_key");
        if (Files.isRegularFile(key)) {
            return key;
        }
        Files.createDirectories(base);
        Files.createDirectories(base);
        // 必须用 i2p 的 EdDSA provider 生成：MINA 的 OpenSSH 编码器会把私钥 cast 成
        // net.i2p.crypto.eddsa.EdDSAPrivateKey，用 JDK 内置 sun.security.ec.ed 生成会 ClassCastException
        // i2p provider 注册的算法名是 "EdDSA"（KeyPairGenerator.EdDSA，OID 1.3.101.112 别名 Ed25519）
        KeyPairGenerator kpg = KeyPairGenerator.getInstance("EdDSA", "EdDSA");
        KeyPair kp = kpg.generateKeyPair();
        try (var os = Files.newOutputStream(key)) {
            OpenSSHKeyPairResourceWriter.INSTANCE.writePrivateKey(kp, null, null, os);
        }
        try (var os = Files.newOutputStream(base.resolve("ssh_host_ed25519_key.pub"))) {
            OpenSSHKeyPairResourceWriter.INSTANCE.writePublicKey(kp.getPublic(), "sshserver host key", os);
        }
        log("hostkey", "生成 Ed25519 主机密钥 " + key);
        return key;
    }

    private static void log(String tag, String msg) {
        System.out.printf("[sshserver] %s: %s%n", tag, msg);
    }

    /** 独立运行入口：java -cp sshserver.jar io.github.woyxiang.sshserver.ShellSshServer（无 MC，控制台模式降级）。 */
    public static void main(String[] args) {
        Path base = args.length > 0 ? Path.of(args[0]) : defaultBase();
        run(base);
    }
}
