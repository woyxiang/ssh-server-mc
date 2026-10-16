package io.github.woyxiang.sshserver;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SshConfigTest {

    @TempDir
    Path dir;

    private void write(String content) throws Exception {
        Files.writeString(dir.resolve("sshserver.properties"), content, StandardCharsets.UTF_8);
    }

    @Test
    void firstStartGeneratesTemplateAndDeniesAllLogins() throws Exception {
        SshConfig cfg = SshConfig.loadOrCreate(dir);

        Path file = dir.resolve("sshserver.properties");
        assertTrue(Files.isRegularFile(file), "首启应生成配置文件");
        assertTrue(Files.readString(file).contains("bind=127.0.0.1"), "默认仅监听本机");
        assertEquals("127.0.0.1", cfg.bind());
        assertTrue(cfg.isLoopbackBind());
        assertTrue(cfg.users().isEmpty());
        assertEquals("", cfg.consolePassword());
        assertTrue(cfg.noCredentialsConfigured(), "空凭据应拒绝一切登录");
        assertTrue(cfg.warnings().isEmpty(), "自生成模板自带全部键，不应有迁移警告");
    }


    @Test
    void authorizedKeysCountAsCredentials() throws Exception {
        SshConfig cfg = SshConfig.loadOrCreate(dir);
        assertTrue(cfg.noCredentialsConfigured(), "无公钥文件时按空凭据处理");

        Path keys = Files.createDirectories(dir.resolve("authorized_keys"));
        Files.writeString(keys.resolve("root"), "ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIFakeKeyForTest test\n",
                StandardCharsets.UTF_8);
        assertFalse(cfg.noCredentialsConfigured(), "存在 authorized_keys 公钥即算有可用凭据");
    }
    @Test
    void invalidPortRejectsStartupWithKeyAndRange() throws Exception {
        write("port=99999\n");
        SshConfig.InvalidConfigException e = assertThrows(SshConfig.InvalidConfigException.class,
                () -> SshConfig.loadOrCreate(dir));
        assertTrue(e.getMessage().contains("port"));
        assertTrue(e.getMessage().contains("1..65535"));
        assertTrue(e.getMessage().contains("sshserver.properties"), "错误应指出配置文件路径");
    }

    @Test
    void invalidBooleanRejectsStartup() throws Exception {
        write("password-auth-enabled=maybe\n");
        SshConfig.InvalidConfigException e = assertThrows(SshConfig.InvalidConfigException.class,
                () -> SshConfig.loadOrCreate(dir));
        assertTrue(e.getMessage().contains("password-auth-enabled"));
        assertTrue(e.getMessage().contains("true"));
    }

    @Test
    void legacyConfigMigratesWithWarningsAndIsNotRewritten() throws Exception {
        String original = "bind=0.0.0.0\nport=2223\nusers=alice:s3cret\n";
        write(original);
        SshConfig cfg = SshConfig.loadOrCreate(dir);

        assertEquals(2223, cfg.port());
        assertEquals("s3cret", cfg.users().get("alice"));
        assertTrue(cfg.warnings().stream().anyMatch(w -> w.contains("console-password")),
                "缺键应给出默认值警告");
        assertTrue(cfg.warnings().stream().anyMatch(w -> w.contains("0.0.0.0")),
                "全网监听应给出安全警告");
        assertEquals(original, Files.readString(dir.resolve("sshserver.properties")),
                "绝不回写用户的配置文件");
    }

    @Test
    void unknownAndDeprecatedKeysWarn() throws Exception {
        write("auth=true\nbindr=0.0.0.0\n");
        SshConfig cfg = SshConfig.loadOrCreate(dir);
        assertTrue(cfg.warnings().stream().anyMatch(w -> w.contains("auth") && w.contains("废弃")),
                "废弃键 auth 应提示新键名");
        assertTrue(cfg.warnings().stream().anyMatch(w -> w.contains("bindr")),
                "拼写错误的未知键应被点名");
    }
}
