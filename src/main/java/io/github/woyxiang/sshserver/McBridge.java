package io.github.woyxiang.sshserver;

import java.lang.reflect.Method;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * MC 服务端桥：mod 环境下反射访问 MinecraftServer，standalone（java -cp 直接运行）时优雅降级。
 * 全反射的原因：编译期不引入 MC 类依赖（javac 只需要 loader 的 @Mod 与 MINA），
 * 且 NeoForge 的 MinecraftServer 符号不在 universal jar 里，引入会显著加重构建。
 */
public final class McBridge {
    private static volatile Path gamedir;

    private McBridge() {}

    /** mod 入口调用；standalone 不调用则 gamedir = CWD。 */
    public static void init(Path gd) {
        gamedir = gd;
    }

    /** 服务器工作目录（latest.log、world 的父目录）。 */
    public static Path gameDir() {
        Path gd = gamedir;
        return gd != null ? gd : Paths.get(".").toAbsolutePath();
    }

    /** 当前 MinecraftServer 实例，未就绪/非 mod 环境返回 null。 */
    public static Object server() {
        try {
            Class<?> hooks = Class.forName("net.neoforged.neoforge.server.ServerLifecycleHooks");
            Method m = hooks.getMethod("getCurrentServer");
            return m.invoke(null);
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * 把一条控制台命令提交到服务器主线程执行（fire-and-forget，输出进服务器日志）。
     *
     * @return false 表示服务器未就绪（standalone 或启动/停止中），命令未提交。
     */
    public static boolean dispatch(String command) {
        Object server = server();
        if (server == null) {
            return false;
        }
        try {
            Class<?> srvClass = server.getClass();
            final Object src = srvClass.getMethod("createCommandSourceStack").invoke(server);
            // 1.21+：performPrefixedCommand 在 Commands 上；老版本在 MinecraftServer 上，做兜底
            final Object commands = srvClass.getMethod("getCommands").invoke(server);
            final Method perform = resolvePerform(server, commands, src.getClass());
            final Object owner = perform.getDeclaringClass().isInstance(commands) ? commands : server;
            final String cmd = command;
            Runnable task = () -> {
                try {
                    perform.invoke(owner, src, cmd);
                } catch (Throwable t) {
                    System.out.println("[sshserver] dispatch failed: " + rootMessage(t));
                }
            };
            srvClass.getMethod("execute", Runnable.class).invoke(server, task);
            return true;
        } catch (Throwable t) {
            System.out.println("[sshserver] dispatch submit failed: " + rootMessage(t));
            return false;
        }
    }

    /** 1.21+: Commands.performPrefixedCommand；老版本 fallback：MinecraftServer.performPrefixedCommand。 */
    private static Method resolvePerform(Object server, Object commands, Class<?> srcClass) throws NoSuchMethodException {
        try {
            return commands.getClass().getMethod("performPrefixedCommand", srcClass, String.class);
        } catch (NoSuchMethodException old) {
            return server.getClass().getMethod("performPrefixedCommand", srcClass, String.class);
        }
    }

    /** 在线玩家数；不可用返回 -1。 */
    public static int playerCount() {
        Object server = server();
        if (server == null) {
            return -1;
        }
        try {
            return ((Number) server.getClass().getMethod("getPlayerCount").invoke(server)).intValue();
        } catch (Throwable t) {
            return -1;
        }
    }

    /** 解包 InvocationTargetException/反射链的最底层消息。 */
    static String rootMessage(Throwable t) {
        Throwable cur = t;
        while (cur.getCause() != null) {
            cur = cur.getCause();
        }
        return cur.toString();
    }
}
