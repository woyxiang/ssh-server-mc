package io.github.woyxiang.sshserver;

import java.lang.reflect.Method;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
/**
 * MC 服务端桥：mod 环境下反射访问 MinecraftServer，standalone（java -cp 直接运行）时优雅降级。
 * 全反射的原因：编译期不引入 MC 类依赖（javac 只需要 loader 的 @Mod 与 MINA），
 * 且 NeoForge 的 MinecraftServer 符号不在 universal jar 里，引入会显著加重构建。
 */
public final class McBridge {
    private static volatile Path gamedir;

    private McBridge() {}

    private static final Logger LOG = LoggerFactory.getLogger("sshserver");

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
        Prepared p = prepare(command);
        if (p == null) {
            return false;
        }
        Runnable task = () -> {
            try {
                p.perform().invoke(p.owner(), p.src(), p.cmd());
            } catch (Throwable t) {
                LOG.error("[sshserver] dispatch failed: {}", rootMessage(t));
            }
        };
        try {
            p.execute().invoke(p.server(), task);
        } catch (Throwable t) {
            LOG.error("[sshserver] dispatch submit failed: {}", rootMessage(t));
            return false;
        }
        return true;
    }

    /** exec 模式结果：kind 决定状态与退出码；code 为 performPrefixedCommand 的返回值。 */
    public static final class ExecOutcome {
        public enum Kind { COMPLETED, REJECTED, FAILED, TIMED_OUT, NOT_READY }

        public final Kind kind;
        public final int code;
        public final String detail;

        ExecOutcome(Kind kind, int code, String detail) {
            this.kind = kind;
            this.code = code;
            this.detail = detail;
        }
    }

    /**
     * 提交命令并等待游戏线程执行结果（调用方是 SSH 通道线程；不阻塞游戏线程）。
     * 等待最多 timeoutMs；超时不取消已入队任务——游戏线程操作不可安全取消，
     * 任务可能在超时后才执行，迟到输出会丢失或不完整。
     */
    public static ExecOutcome dispatchSync(String command, long timeoutMs) {
        Prepared p = prepare(command);
        if (p == null) {
            return new ExecOutcome(ExecOutcome.Kind.NOT_READY, -1, "服务器未就绪或命令提交失败");
        }
        CountDownLatch latch = new CountDownLatch(1);
        int[] result = { -1 };
        Throwable[] failure = { null };
        Runnable task = () -> {
            try {
                Object r = p.perform().invoke(p.owner(), p.src(), p.cmd());
                result[0] = (r instanceof Integer) ? (Integer) r : 1;
            } catch (Throwable t) {
                failure[0] = t;
            } finally {
                latch.countDown();
            }
        };
        try {
            p.execute().invoke(p.server(), task);
        } catch (Throwable t) {
            return new ExecOutcome(ExecOutcome.Kind.NOT_READY, -1, "提交失败: " + rootMessage(t));
        }
        try {
            if (!latch.await(timeoutMs, TimeUnit.MILLISECONDS)) {
                return new ExecOutcome(ExecOutcome.Kind.TIMED_OUT, -1,
                        "游戏线程未在 " + timeoutMs + "ms 内执行（任务可能稍后执行，结果未知）");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new ExecOutcome(ExecOutcome.Kind.TIMED_OUT, -1, "等待执行结果时线程被中断");
        }
        if (failure[0] != null) {
            return new ExecOutcome(ExecOutcome.Kind.FAILED, -1, rootMessage(failure[0]));
        }
        if (result[0] > 0) {
            return new ExecOutcome(ExecOutcome.Kind.COMPLETED, result[0], null);
        }
        return new ExecOutcome(ExecOutcome.Kind.REJECTED, result[0],
                "命令返回 " + result[0] + "（命令不存在或执行失败，详见服务器日志）");
    }

    /** 派发前置：解析服务端、命令栈与 perform 方法；失败返回 null（未就绪/反射失败）。 */
    private static Prepared prepare(String command) {
        Object server = server();
        if (server == null) {
            return null;
        }
        try {
            Class<?> srvClass = server.getClass();
            Object src = srvClass.getMethod("createCommandSourceStack").invoke(server);
            // 1.21+：performPrefixedCommand 在 Commands 上；老版本在 MinecraftServer 上，做兜底
            Object commands = srvClass.getMethod("getCommands").invoke(server);
            Method perform = resolvePerform(server, commands, src.getClass());
            Object owner = perform.getDeclaringClass().isInstance(commands) ? commands : server;
            Method execute = srvClass.getMethod("execute", Runnable.class);
            return new Prepared(server, execute, perform, owner, src, command);
        } catch (Throwable t) {
            LOG.error("[sshserver] dispatch prepare failed: {}", rootMessage(t));
            return null;
        }
    }

    private record Prepared(Object server, Method execute, Method perform, Object owner, Object src, String cmd) {}

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
