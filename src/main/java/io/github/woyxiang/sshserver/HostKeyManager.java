package io.github.woyxiang.sshserver;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.util.Set;

import org.apache.sshd.common.config.keys.KeyUtils;
import org.apache.sshd.common.config.keys.writer.openssh.OpenSSHKeyPairResourceWriter;
import org.apache.sshd.common.keyprovider.FileKeyPairProvider;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
/**
 * 主机密钥管理：
 * - 不存在则自动生成 Ed25519（仅在缺失时生成，之后每次复用，绝不静默轮换）；
 * - 已存在但损坏/不可读 → 明确报错并拒绝启动（自动换新密钥会造成中间人风险）；
 * - 启动输出 SHA256 指纹供客户端核对；
 * - POSIX 系统上尽力把私钥权限收敛为 600（best-effort，Windows 跳过）。
 */
final class HostKeyManager {

    static final String KEY_NAME = "ssh_host_ed25519_key";

    private HostKeyManager() {}

    private static final Logger LOG = LoggerFactory.getLogger("sshserver");
    /** 确保主机密钥存在且可读；返回私钥路径。 */
    static Path ensureKey(Path base) throws IOException {
        Path key = base.resolve(KEY_NAME);
        if (Files.exists(key) && !Files.isRegularFile(key)) {
            throw new IOException("主机密钥路径存在但不是普通文件: " + key);
        }
        if (!Files.isRegularFile(key)) {
            Files.createDirectories(base);
            // 必须用 i2p 的 EdDSA provider 生成：MINA 的 OpenSSH 编码器会把私钥 cast 成
            // net.i2p.crypto.eddsa.EdDSAPrivateKey，用 JDK 内置 sun.security.ec.ed 生成会 ClassCastException
            KeyPair kp;
            try {
                KeyPairGenerator kpg = KeyPairGenerator.getInstance("EdDSA", "EdDSA");
                kp = kpg.generateKeyPair();
                try (var os = Files.newOutputStream(key)) {
                    OpenSSHKeyPairResourceWriter.INSTANCE.writePrivateKey(kp, null, null, os);
                }
                try (var os = Files.newOutputStream(base.resolve(KEY_NAME + ".pub"))) {
                    OpenSSHKeyPairResourceWriter.INSTANCE.writePublicKey(kp.getPublic(), "sshserver host key", os);
                }
            } catch (GeneralSecurityException e) {
                throw new IOException("生成 Ed25519 主机密钥失败（EdDSA provider 不可用）: "
                        + McBridge.rootMessage(e), e);
            }
            LOG.info("[sshserver] hostkey: 生成 Ed25519 主机密钥 {}", key);
        } else {
            // 先自检一次：损坏的密钥在启动早期就给出可操作的错误，而不是在认证阶段莫名失败
            try {
                new FileKeyPairProvider(key).loadKeys(null);
            } catch (Throwable t) {
                throw new IOException("主机密钥 " + key + " 损坏或不可读: " + McBridge.rootMessage(t)
                        + "（不会自动生成新密钥，以免客户端遭遇中间人警告；确认无误后删除该文件并重启以重新生成）", t);
            }
        }
        restrictPermissions(key);
        return key;
    }

    /** 私钥 SHA256 指纹（MINA 格式 "SHA256:..."）；不可用返回 null。 */
    static String fingerprint(Path key) {
        try {
            for (KeyPair kp : new FileKeyPairProvider(key).loadKeys(null)) {
                return KeyUtils.getFingerPrint(kp.getPublic());
            }
        } catch (Throwable ignored) {
            // 指纹只用于启动摘要，失败不影响运行
        }
        return null;
    }

    /** POSIX 权限收敛为 600；非 POSIX 文件系统（Windows）静默跳过。 */
    private static void restrictPermissions(Path key) {
        try {
            Set<PosixFilePermission> want = Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE);
            if (!Files.getPosixFilePermissions(key).equals(want)) {
                Files.setPosixFilePermissions(key, want);
                LOG.info("[sshserver] hostkey: 已将 {} 权限收敛为 600", key);
            }
        } catch (UnsupportedOperationException ignored) {
            // 非 POSIX 文件系统
        } catch (IOException e) {
            LOG.warn("[sshserver] hostkey: 警告，无法调整 {} 的权限: {}", key, McBridge.rootMessage(e));
        }
    }
}
