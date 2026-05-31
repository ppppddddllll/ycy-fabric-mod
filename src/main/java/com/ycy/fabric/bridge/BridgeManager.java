package com.ycy.fabric.bridge;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.ycy.fabric.YcyModClient;
import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ServerHandshake;

import java.io.*;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class BridgeManager {
    private static final BridgeManager INSTANCE = new BridgeManager();
    private static final String PORT = "18790";
    private static final String DEFAULT_WS = "ws://localhost:" + PORT;
    private static final Gson GSON = new Gson();

    private Process bridgeProcess;
    private Path bridgeDir;
    private ModWsClient wsClient;
    private ScheduledExecutorService heartbeat;

    private String wsUrl = DEFAULT_WS;
    private boolean bridgeAlive;
    private boolean wsOpen;
    private boolean loggedIn;
    private int retry;
    private long lastTick;
    private String lastError = "";

    private Runnable onReady;
    private Runnable onDisconnect;

    public static BridgeManager instance() { return INSTANCE; }

    // ========== Init ==========

    public void initialize(Path configDir) {
        this.bridgeDir = configDir.resolve("bridge");
        try {
            Files.createDirectories(bridgeDir);
            copyFromJar("server.js");
            copyFromJar("package.json");
        } catch (Exception e) {
            YcyModClient.LOGGER.error("[YCY] extract failed", e);
        }
        Path nm = bridgeDir.resolve("node_modules");
        if (Files.exists(nm)) {
            startBridge();
        } else {
            lastError = "首次启动，后台安装中...";
            installDeps();
        }
    }

    private void copyFromJar(String name) throws IOException {
        try (InputStream in = getClass().getClassLoader().getResourceAsStream("ycy-bridge/" + name)) {
            if (in == null) return;
            Files.copy(in, bridgeDir.resolve(name), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private void installDeps() {
        YcyModClient.LOGGER.info("[YCY] Running npm install...");
        new Thread(() -> {
            try {
                if (new ProcessBuilder("node", "--version").start().waitFor() != 0) {
                    lastError = "未安装 Node.js, 请访问 nodejs.org";
                    return;
                }
                Process p = new ProcessBuilder("npm", "install", "--no-audit", "--no-fund")
                        .directory(bridgeDir.toFile()).redirectErrorStream(true).start();
                String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
                if (p.waitFor() == 0) {
                    lastError = "";
                    startBridge();
                } else {
                    lastError = "npm install 失败";
                    YcyModClient.LOGGER.error("[YCY] npm install failed: {}", out);
                }
            } catch (Exception e) {
                lastError = "安装失败: " + e.getMessage();
            }
        }, "YCY-NpmInstall").start();
    }

    // ========== Bridge Process ==========

    private void startBridge() {
        if (bridgeProcess != null && bridgeProcess.isAlive()) { connectWs(); return; }
        YcyModClient.LOGGER.info("[YCY] Starting node server.js...");
        try {
            ProcessBuilder pb = new ProcessBuilder("node", "server.js")
                    .directory(bridgeDir.toFile()).redirectErrorStream(true);
            bridgeProcess = pb.start();
            bridgeAlive = true;
            lastError = "";

            new Thread(() -> {
                try (BufferedReader br = new BufferedReader(
                        new InputStreamReader(bridgeProcess.getInputStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = br.readLine()) != null) YcyModClient.LOGGER.info("[Bridge] {}", line);
                } catch (IOException ignored) {}
            }, "YCY-Bridge-IO").start();

            Thread.sleep(1500);
            connectWs();
        } catch (Exception e) {
            lastError = "桥接启动失败";
            YcyModClient.LOGGER.error("[YCY] Bridge start failed", e);
        }
    }

    // ========== WebSocket ==========

    public void setWsUrl(String url) { this.wsUrl = url; }

    private synchronized void connectWs() {
        if (wsClient != null) { try { wsClient.close(); } catch (Exception ignored) {} wsClient = null; }
        YcyModClient.LOGGER.info("[YCY] WS connecting to {}", wsUrl);
        try {
            wsClient = new ModWsClient(URI.create(wsUrl));
            wsClient.connect();
        } catch (Exception e) {
            lastError = "WS连接失败";
        }
    }

    public void login(String uid, String token) {
        if (!isWsOpen()) { connectWs(); return; }
        JsonObject j = new JsonObject();
        j.addProperty("type", "login");
        j.addProperty("uid", uid);
        j.addProperty("token", token);
        send(j.toString());
    }

    public void reconnect() {
        lastError = "";
        if (wsClient != null) { try { wsClient.close(); } catch (Exception ignored) {} wsClient = null; }
        wsOpen = false;
        loggedIn = false;
        this.wsUrl = YcyModClient.getConfigManager().loadUrl();
        connectWs();
    }

    public void sendCommand(String id) {
        if (!loggedIn) return;
        JsonObject j = new JsonObject();
        j.addProperty("type", "sendCommand");
        j.addProperty("commandId", id);
        send(j.toString());
    }

    public void stopAll() { sendCommand("_stop_all"); }

    private void send(String msg) {
        if (wsClient != null && wsClient.isOpen()) wsClient.send(msg);
    }

    public void setOnReady(Runnable cb) { onReady = cb; }
    public void setOnDisconnect(Runnable cb) { onDisconnect = cb; }
    public boolean isConnected() { return loggedIn; }
    public boolean isWsOpen() { return wsClient != null && wsClient.isOpen(); }
    public boolean isBridgeRunning() { return bridgeAlive && bridgeProcess != null && bridgeProcess.isAlive(); }
    public String getUrl() { return wsUrl; }
    public String getLastError() { return lastError; }

    // ========== Tick ==========

    public void tick() {
        long now = System.currentTimeMillis();
        if (now - lastTick < 3000) return;
        lastTick = now;
        if (bridgeAlive && bridgeProcess != null && !bridgeProcess.isAlive()) {
            bridgeAlive = false; wsOpen = false; loggedIn = false;
            if (retry < 3) { retry++; startBridge(); }
            return;
        }
        if (bridgeAlive && !isWsOpen() && retry < 5) { retry++; connectWs(); }
    }

    // ========== Heartbeat ==========

    private void startHeartbeat() {
        if (heartbeat != null) heartbeat.shutdownNow();
        heartbeat = Executors.newSingleThreadScheduledExecutor();
        heartbeat.scheduleAtFixedRate(() -> send("{\"type\":\"ping\"}"), 30, 30, TimeUnit.SECONDS);
    }

    private void stopHeartbeat() {
        if (heartbeat != null) { heartbeat.shutdownNow(); heartbeat = null; }
    }

    public void shutdown() {
        stopHeartbeat();
        if (wsClient != null) { try { wsClient.close(); } catch (Exception ignored) {} }
        if (bridgeProcess != null && bridgeProcess.isAlive()) bridgeProcess.destroyForcibly();
        wsOpen = false; loggedIn = false; bridgeAlive = false;
    }

    // ========== Inner WS Client ==========

    private class ModWsClient extends WebSocketClient {
        ModWsClient(URI uri) { super(uri); }

        @Override
        public void onOpen(ServerHandshake h) {
            wsOpen = true; retry = 0; lastError = "";
            startHeartbeat();
            YcyModClient.LOGGER.info("[YCY] WS connected");
            String uid = YcyModClient.getConfigManager().loadUid();
            String token = YcyModClient.getConfigManager().loadToken();
            if (!uid.isEmpty() && !token.isEmpty()) login(uid, token);
        }

        @Override
        public void onMessage(String msg) {
            try {
                JsonObject j = GSON.fromJson(msg, JsonObject.class);
                if (!j.has("type")) return;
                switch (j.get("type").getAsString()) {
                    case "status":
                        boolean ready = j.has("isReady") && j.get("isReady").getAsBoolean()
                                || j.has("data") && j.getAsJsonObject("data").has("isReady")
                                && j.getAsJsonObject("data").get("isReady").getAsBoolean();
                        if (ready) { loggedIn = true; if (onReady != null) onReady.run(); }
                        break;
                    case "loginResult":
                        if (j.has("success") && j.get("success").getAsBoolean()) {
                            loggedIn = true;
                            if (onReady != null) onReady.run();
                        } else {
                            lastError = j.has("message") ? j.get("message").getAsString() : "登录失败";
                        }
                        break;
                    case "error":
                        lastError = j.has("message") ? j.get("message").getAsString() : "unknown";
                        break;
                }
            } catch (Exception ignored) {}
        }

        @Override
        public void onClose(int code, String reason, boolean remote) {
            wsOpen = false; loggedIn = false; stopHeartbeat();
            if (onDisconnect != null) onDisconnect.run();
        }

        @Override
        public void onError(Exception e) {
            lastError = "WS错误: " + e.getMessage();
        }
    }
}
