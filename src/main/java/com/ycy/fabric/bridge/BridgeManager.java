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

/**
 * Manages the embedded Node.js bridge server + WebSocket client
 */
public class BridgeManager {
    private static final BridgeManager INSTANCE = new BridgeManager();
    private static final String BRIDGE_PORT = "18790";
    private static final String DEFAULT_WS_URL = "ws://localhost:" + BRIDGE_PORT;
    private static final Gson GSON = new Gson();

    private Process bridgeProcess;
    private Path bridgeDir;
    private ModWsClient wsClient;
    private ScheduledExecutorService heartbeatExecutor;

    private String wsUrl = DEFAULT_WS_URL;
    private boolean bridgeLaunched = false;
    private boolean wsConnected = false;
    private boolean isLoggedIn = false;
    private int connectRetry = 0;
    private long lastTick = 0;
    private String lastError = "";

    private Runnable onReady;
    private Runnable onDisconnect;

    public static BridgeManager instance() { return INSTANCE; }

    // ==================== Bridge Process ====================

    public void initialize(Path configDir) {
        this.bridgeDir = configDir.resolve("bridge");
        extractBridgeFiles();

        Path nodeModules = bridgeDir.resolve("node_modules");
        if (Files.exists(nodeModules)) {
            startBridgeProcess();
        } else {
            installDependenciesAsync();
        }
    }

    private void extractBridgeFiles() {
        YcyModClient.LOGGER.info("[YCY] Extracting bridge files...");
        try {
            Files.createDirectories(bridgeDir);
            copyResource("server.js", bridgeDir.resolve("server.js"));
            copyResource("package.json", bridgeDir.resolve("package.json"));
        } catch (Exception e) {
            YcyModClient.LOGGER.error("[YCY] Extract failed", e);
        }
    }

    private void copyResource(String name, Path dest) throws IOException {
        try (InputStream in = getClass().getClassLoader().getResourceAsStream("ycy-bridge/" + name)) {
            if (in == null) { YcyModClient.LOGGER.warn("[YCY] Resource missing: {}", name); return; }
            Files.copy(in, dest, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private void installDependenciesAsync() {
        lastError = "正在后台安装依赖...";
        YcyModClient.LOGGER.info("[YCY] ==========================================");
        YcyModClient.LOGGER.info("[YCY] 首次启动，正在 npm install...");
        YcyModClient.LOGGER.info("[YCY] 路径: {}", bridgeDir);
        YcyModClient.LOGGER.info("[YCY] ==========================================");
        new Thread(() -> {
            try {
                // First verify node exists
                ProcessBuilder checkNode = new ProcessBuilder("node", "--version");
                checkNode.redirectErrorStream(true);
                Process check = checkNode.start();
                int nodeCheck = check.waitFor();
                if (nodeCheck != 0) {
                    lastError = "未找到 Node.js，请安装 https://nodejs.org";
                    YcyModClient.LOGGER.error("[YCY] node not found! exit={}", nodeCheck);
                    return;
                }

                ProcessBuilder pb = new ProcessBuilder("npm", "install", "--no-audit", "--no-fund")
                        .directory(bridgeDir.toFile()).redirectErrorStream(true);
                Process p = pb.start();
                String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
                int exit = p.waitFor();
                if (exit == 0) {
                    YcyModClient.LOGGER.info("[YCY] npm install OK");
                    lastError = "";
                    startBridgeProcess();
                } else {
                    YcyModClient.LOGGER.error("[YCY] npm install FAILED (exit {}): {}", exit, out);
                    lastError = "npm install 失败，请手动执行";
                }
            } catch (Exception e) {
                YcyModClient.LOGGER.error("[YCY] npm install error: {}", e.getMessage());
                lastError = "安装失败: " + e.getMessage();
            }
        }, "YCY-NpmInstall").start();
    }

    private void startBridgeProcess() {
        if (bridgeProcess != null && bridgeProcess.isAlive()) {
            connectWebSocket();
            return;
        }
        YcyModClient.LOGGER.info("[YCY] Starting Node.js bridge...");
        try {
            ProcessBuilder pb = new ProcessBuilder("node", "server.js")
                    .directory(bridgeDir.toFile()).redirectErrorStream(true);
            bridgeProcess = pb.start();
            bridgeLaunched = true;
            lastError = "";

            new Thread(() -> {
                try (BufferedReader br = new BufferedReader(
                        new InputStreamReader(bridgeProcess.getInputStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = br.readLine()) != null) { YcyModClient.LOGGER.info("[Bridge] {}", line); }
                } catch (IOException ignored) {}
            }, "YCY-Bridge-IO").start();

            Thread.sleep(1500);
            connectWebSocket();
        } catch (Exception e) {
            YcyModClient.LOGGER.error("[YCY] Bridge start failed", e);
            lastError = "桥接启动失败: " + e.getMessage();
        }
    }

    // ==================== WebSocket ====================

    public void setWsUrl(String url) { this.wsUrl = url; }

    private synchronized void connectWebSocket() {
        // Clean up old client first
        if (wsClient != null) {
            try { wsClient.close(); } catch (Exception ignored) {}
            wsClient = null;
        }

        YcyModClient.LOGGER.info("[YCY] WebSocket connecting to {}", wsUrl);
        try {
            wsClient = new ModWsClient(URI.create(wsUrl));
            wsClient.connect();
        } catch (Exception e) {
            YcyModClient.LOGGER.error("[YCY] WS connect failed: {}", e.getMessage());
            lastError = "WebSocket 连接失败";
        }
    }

    public void login(String uid, String token) {
        YcyModClient.LOGGER.info("[YCY] Login uid={}", uid);
        if (!isWsOpen()) {
            connectWebSocket();
            return;
        }
        JsonObject json = new JsonObject();
        json.addProperty("type", "login");
        json.addProperty("uid", uid);
        json.addProperty("token", token);
        sendWs(json.toString());
    }

    /**
     * Reconnect: close existing WebSocket and create new one.
     * Auto-login happens in onOpen() with saved credentials.
     */
    public void reconnect() {
        YcyModClient.LOGGER.info("[YCY] Reconnecting...");
        lastError = "";
        if (wsClient != null) {
            try { wsClient.close(); } catch (Exception ignored) {}
            wsClient = null;
        }
        wsConnected = false;
        isLoggedIn = false;
        this.wsUrl = YcyModClient.getConfigManager().loadUrl();
        connectWebSocket();
    }

    public void sendCommand(String commandId) {
        if (!isLoggedIn) return;
        JsonObject json = new JsonObject();
        json.addProperty("type", "sendCommand");
        json.addProperty("commandId", commandId);
        sendWs(json.toString());
    }

    public void stopAll() { sendCommand("_stop_all"); }

    private void sendWs(String message) {
        if (wsClient != null && wsClient.isOpen()) {
            wsClient.send(message);
        }
    }

    // Accessors
    public void setOnReady(Runnable cb) { this.onReady = cb; }
    public void setOnDisconnect(Runnable cb) { this.onDisconnect = cb; }
    public boolean isConnected() { return isLoggedIn; }
    public boolean isWsOpen() { return wsClient != null && wsClient.isOpen(); }
    public String getUrl() { return wsUrl; }
    public String getLastError() { return lastError; }
    public boolean isBridgeRunning() { return bridgeLaunched && bridgeProcess != null && bridgeProcess.isAlive(); }

    // ==================== Tick ====================

    public void tick() {
        long now = System.currentTimeMillis();
        if (now - lastTick < 3000) return;
        lastTick = now;

        // Monitor bridge process
        if (bridgeLaunched && bridgeProcess != null && !bridgeProcess.isAlive()) {
            bridgeLaunched = false;
            wsConnected = false;
            isLoggedIn = false;
            if (connectRetry < 3) { connectRetry++; startBridgeProcess(); }
            return;
        }

        // Reconnect WebSocket if bridge is up but WS is down
        if (bridgeLaunched && !isWsOpen()) {
            if (connectRetry < 5) { connectRetry++; connectWebSocket(); }
        }
    }

    // ==================== Heartbeat ====================

    private void startHeartbeat() {
        if (heartbeatExecutor != null) heartbeatExecutor.shutdownNow();
        heartbeatExecutor = Executors.newSingleThreadScheduledExecutor();
        heartbeatExecutor.scheduleAtFixedRate(() -> sendWs("{\"type\":\"ping\"}"), 30, 30, TimeUnit.SECONDS);
    }

    private void stopHeartbeat() {
        if (heartbeatExecutor != null) { heartbeatExecutor.shutdownNow(); heartbeatExecutor = null; }
    }

    // ==================== Shutdown ====================

    public void shutdown() {
        stopHeartbeat();
        if (wsClient != null) { try { wsClient.close(); } catch (Exception ignored) {} wsClient = null; }
        if (bridgeProcess != null && bridgeProcess.isAlive()) { bridgeProcess.destroyForcibly(); }
        wsConnected = false;
        isLoggedIn = false;
    }

    // ==================== Inner WebSocket Client ====================

    private class ModWsClient extends WebSocketClient {
        public ModWsClient(URI serverUri) { super(serverUri); }

        @Override
        public void onOpen(ServerHandshake handshake) {
            wsConnected = true;
            connectRetry = 0;
            lastError = "";
            startHeartbeat();
            YcyModClient.LOGGER.info("[YCY] WebSocket connected to bridge");

            // Auto-login with saved credentials
            String uid = YcyModClient.getConfigManager().loadUid();
            String token = YcyModClient.getConfigManager().loadToken();
            if (!uid.isEmpty() && !token.isEmpty()) {
                login(uid, token);
            }
        }

        @Override
        public void onMessage(String message) {
            try {
                JsonObject json = GSON.fromJson(message, JsonObject.class);
                if (!json.has("type")) return;
                String type = json.get("type").getAsString();

                switch (type) {
                    case "connected":
                        YcyModClient.LOGGER.info("[YCY] Bridge: connected OK");
                        break;

                    case "pong":
                        break;

                    case "status":
                        boolean ready = false;
                        if (json.has("isReady")) {
                            ready = json.get("isReady").getAsBoolean();
                        } else if (json.has("data") && json.get("data").isJsonObject()) {
                            JsonObject data = json.getAsJsonObject("data");
                            ready = data.has("isReady") && data.get("isReady").getAsBoolean();
                        }
                        if (ready && !isLoggedIn) {
                            isLoggedIn = true;
                            lastError = "";
                            YcyModClient.LOGGER.info("[YCY] IM ready, logged in");
                            if (onReady != null) onReady.run();
                        }
                        break;

                    case "loginResult":
                        boolean ok = json.has("success") && json.get("success").getAsBoolean();
                        YcyModClient.LOGGER.info("[YCY] Login result: {}", ok ? "OK" : "FAILED");
                        if (ok && !isLoggedIn) {
                            isLoggedIn = true;
                            lastError = "";
                            if (onReady != null) onReady.run();
                        } else {
                            String msg = json.has("message") ? json.get("message").getAsString() : "登录失败";
                            lastError = msg;
                            YcyModClient.LOGGER.warn("[YCY] Login failed: {}", msg);
                        }
                        break;

                    case "commandResult":
                        if (json.has("success") && !json.get("success").getAsBoolean()) {
                            String err = json.has("message") ? json.get("message").getAsString() : "unknown";
                            YcyModClient.LOGGER.warn("[YCY] Command failed: {}", err);
                        }
                        break;

                    case "error":
                        lastError = json.has("message") ? json.get("message").getAsString() : "unknown error";
                        YcyModClient.LOGGER.warn("[YCY] Server error: {}", lastError);
                        break;

                    default:
                        break;
                }
            } catch (Exception e) {
                YcyModClient.LOGGER.debug("[YCY] Raw: {}", message);
            }
        }

        @Override
        public void onClose(int code, String reason, boolean remote) {
            wsConnected = false;
            isLoggedIn = false;
            stopHeartbeat();
            YcyModClient.LOGGER.warn("[YCY] WS closed ({}): {}", code, reason);
            if (onDisconnect != null) onDisconnect.run();
        }

        @Override
        public void onError(Exception ex) {
            lastError = "WebSocket 错误: " + ex.getMessage();
            YcyModClient.LOGGER.error("[YCY] WS error: {}", ex.getMessage());
        }
    }
}
