package com.ycy.fabric.command;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.ycy.fabric.YcyModClient;
import com.ycy.fabric.bridge.BridgeManager;
import com.ycy.fabric.config.EventMapping;
import com.ycy.fabric.event.EventRegistry;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.text.ClickEvent;
import net.minecraft.text.Style;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

import java.io.IOException;

/**
 * /ycy commands:
 *   /ycy gui                     Open config GUI
 *   /ycy login <uid> <token>     Login to YOKONEX
 *   /ycy status                  Show connection status
 *   /ycy stop                    Emergency stop all devices
 *   /ycy toggle                  Enable/disable mod
 *   /ycy events                  List all event mappings
 */
public class YcyCommand {

    public static void register() {
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> {
            var root = ClientCommandManager.literal("ycy");

            // /ycy gui
            root.then(ClientCommandManager.literal("gui").executes(ctx -> {
                ctx.getSource().getClient().send(() -> ctx.getSource().getClient().setScreen(
                        new com.ycy.fabric.gui.YcyConfigScreen()));
                return 1;
            }));

            // /ycy login <uid> <token>
            root.then(ClientCommandManager.literal("login")
                    .then(ClientCommandManager.argument("uid", StringArgumentType.word())
                    .then(ClientCommandManager.argument("token", StringArgumentType.greedyString())
                    .executes(ctx -> {
                        String uid = StringArgumentType.getString(ctx, "uid");
                        String token = StringArgumentType.getString(ctx, "token");
                        send(ctx.getSource(), Text.literal("正在连接...").formatted(Formatting.YELLOW));

                        YcyModClient.getConfigManager().saveUid(uid);
                        YcyModClient.getConfigManager().saveToken(token);
                        BridgeManager.instance().login(uid, token);
                        return 1;
                    }))));

            // /ycy status
            root.then(ClientCommandManager.literal("status").executes(ctx -> {
                boolean connected = BridgeManager.instance().isConnected();
                if (connected) {
                    send(ctx.getSource(), Text.literal("状态: 已连接").formatted(Formatting.GREEN));
                } else {
                    send(ctx.getSource(),
                            Text.literal("状态: 未连接").formatted(Formatting.RED)
                                    .append(Text.literal(" [去配置]").setStyle(
                                            Style.EMPTY.withClickEvent(
                                                    new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/ycy gui"))
                                                    .withColor(Formatting.AQUA))));
                }
                return 1;
            }));

            // /ycy stop
            root.then(ClientCommandManager.literal("stop").executes(ctx -> {
                BridgeManager.instance().stopAll();
                send(ctx.getSource(), Text.translatable("ycy.command.stop_sent").formatted(Formatting.RED));
                return 1;
            }));

            // /ycy toggle
            root.then(ClientCommandManager.literal("toggle").executes(ctx -> {
                boolean ns = !YcyModClient.isModEnabled();
                YcyModClient.setModEnabled(ns);
                send(ctx.getSource(), Text.translatable(ns ? "ycy.command.toggle_on" : "ycy.command.toggle_off")
                        .formatted(ns ? Formatting.GREEN : Formatting.GRAY));
                return 1;
            }));

            // /ycy events
            root.then(ClientCommandManager.literal("events").executes(ctx -> {
                EventRegistry registry = YcyModClient.getEventRegistry();
                send(ctx.getSource(), Text.literal("=== 事件配置 ===").formatted(Formatting.GOLD));
                for (EventMapping m : registry.getMappings()) {
                    String s = m.isEnabled() ? "§a[ON]" : "§c[OFF]";
                    String name = m.getDisplayName() != null ? m.getDisplayName() : m.getEventId();
                    ctx.getSource().sendFeedback(Text.literal(
                            String.format("%s %s → '%s' (%dms)",
                                    s, name, m.getCommandId(), m.getCooldownMs())));
                }
                return 1;
            }));

            // /ycy bridge — diagnostic info
            root.then(ClientCommandManager.literal("bridge").executes(ctx -> {
                BridgeManager bm = BridgeManager.instance();
                send(ctx.getSource(), Text.literal("=== 桥接诊断 ===").formatted(Formatting.GOLD));
                send(ctx.getSource(), Text.literal("URL: " + bm.getUrl()));
                send(ctx.getSource(), Text.literal("WS已开: " + bm.isWsOpen()));
                send(ctx.getSource(), Text.literal("已登录: " + bm.isConnected()));
                send(ctx.getSource(), Text.literal("桥接运行: " + bm.isBridgeRunning()));
                String err = bm.getLastError();
                if (!err.isEmpty()) {
                    send(ctx.getSource(), Text.literal("错误: " + err).formatted(Formatting.RED));
                }
                send(ctx.getSource(), Text.literal("已装Node: " + checkNodeInstalled()));
                return 1;
            }));

            dispatcher.register(root);
        });
    }

    private static void send(FabricClientCommandSource src, Text text) {
        src.sendFeedback(text);
    }

    private static String checkNodeInstalled() {
        try {
            boolean isWin = System.getProperty("os.name").toLowerCase().contains("win");
            ProcessBuilder pb;
            if (isWin) {
                pb = new ProcessBuilder("cmd", "/c", "node --version");
            } else {
                pb = new ProcessBuilder("node", "--version");
            }
            Process p = pb.start();
            String v = new String(p.getInputStream().readAllBytes()).trim();
            return p.waitFor() == 0 ? v : "未安装";
        } catch (Exception e) {
            return "未安装";
        }
    }
}
