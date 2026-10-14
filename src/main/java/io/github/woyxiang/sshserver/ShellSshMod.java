package io.github.woyxiang.sshserver;

import java.nio.file.Path;

import net.neoforged.fml.common.Mod;
import net.neoforged.fml.loading.FMLPaths;

/**
 * NeoForge mod 入口：服务器加载 mod 时启动 SSH 服务线程。
 * 双模式：物理 shell（真实用户名）+ MC 控制台（虚拟用户名 mc）。
 * 所有异常吞掉，绝不影响 MC 启动。
 */
@Mod("sshserver")
public class ShellSshMod {
    public ShellSshMod() {
        Path gamedir = FMLPaths.GAMEDIR.get();
        McBridge.init(gamedir.toAbsolutePath().normalize());
        Thread t = new Thread(() -> ShellSshServer.run(ShellSshServer.defaultBase()), "sshserver-sshd");
        t.setDaemon(true);
        t.start();
    }
}
