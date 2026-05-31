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
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

public class BridgeManager {
    private static final BridgeManager INSTANCE = new BridgeManager();
    private static final String PORT = "18790";
    private static final String DEFAULT_WS = "ws://localhost:" + PORT;
    private static final Gson GSON = new Gson();

    // Reconnect timing
    private static final long INITIAL_BACKOFF_MS = 1000;
    private static final long MAX_BACKOFF_MS = 30000;
    private static final long HEARTBEAT_INTERVAL_S = 15;
    private static final long HEARTBEAT_TIMEOUT_MS = 45000;

    private Process bridgeProcess;
    private Path bridgeDir;
    private ModWsClient wsClient;
    private ScheduledExecutorService scheduler;

    private String wsUrl = DEFAULT_WS;
    private boolean bridgeAlive;
    private boolean wsOpen;
    private boolean loggedIn;
    private int wsRetryCount;
    private int bridgeRetryCount;
    private long wsBackoffMs = INITIAL_BACKOFF_MS;
    private long bridgeBackoffMs = INITIAL_BACKOFF_MS;
    private long lastTick;
    private String lastError = "";
    private long lastPongTime;
    private ScheduledFuture<?> reconnectTask;

    private Runnable onReady;
    private Runnable onDisconnect;

    public static BridgeManager instance() { return INSTANCE; }

    // ========== Init ==========

    public void initialize(Path configDir) {
        this.bridgeDir = configDir.resolve("bridge");
        this.scheduler = Executors.newSingleThreadScheduledExecutor();
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
                String os = System.getProperty("os.name").toLowerCase();
                boolean isWin = os.contains("win");
                ProcessBuilder pb = isWin
                        ? new ProcessBuilder("cmd", "/c", "npm install --no-audit --no-fund")
                        : new ProcessBuilder("npm", "install", "--no-audit", "--no-fund");
                pb.directory(bridgeDir.toFile()).redirectErrorStream(true);
                Process p = pb.start();
                String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
                if (p.waitFor() == 0) {
                    lastError = "";
                    startBridge();
                } else {
                    lastError = "npm 安装失败";
                    YcyModClient.LOGGER.error("[YCY] npm failed: {}", out);
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
            boolean isWin = System.getProperty("os.name").toLowerCase().contains("win");
            ProcessBuilder pb = isWin
                    ? new ProcessBuilder("cmd", "/c", "node server.js")
                    : new ProcessBuilder("node", "server.js");
            pb.directory(bridgeDir.toFile()).redirectErrorStream(true);
            bridgeProcess = pb.start();
            bridgeAlive = true;
            bridgeRetryCount = 0;
            bridgeBackoffMs = INITIAL_BACKOFF_MS;
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
            scheduleBridgeRestart();
        }
    }

    /**
     * Called when bridge process dies — restart with exponential backoff.
     */
    private void scheduleBridgeRestart() {
        bridgeAlive = false;
        wsOpen = false;
        loggedIn = false;
        if (onDisconnect != null) onDisconnect.run();

        long delay = bridgeBackoffMs;
        bridgeRetryCount++;
        bridgeBackoffMs = Math.min(bridgeBackoffMs * 2, MAX_BACKOFF_MS);
        YcyModClient.LOGGER.warn("[YCY] Bridge restart in {}ms (attempt {})", delay, bridgeRetryCount);

        scheduler.schedule(() -> startBridge(), delay, TimeUnit.MILLISECONDS);
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
            scheduleWsReconnect();
        }
    }

    /**
     * Called when WebSocket disconnects or fails — reconnect with exponential backoff.
     */
    private void scheduleWsReconnect() {
        if (reconnectTask != null) { reconnectTask.cancel(false); reconnectTask = null; }
        wsOpen = false;
        loggedIn = false;

        long delay = wsBackoffMs;
        wsRetryCount++;
        wsBackoffMs = Math.min(wsBackoffMs * 2, MAX_BACKOFF_MS);
        YcyModClient.LOGGER.warn("[YCY] WS reconnect in {}ms (attempt {})", delay, wsRetryCount);

        reconnectTask = scheduler.schedule(() -> {
            reconnectTask = null;
            if (!shutdown) connectWs();
        }, delay, TimeUnit.MILLISECONDS);
    }

    public void login(String uid, String token) {
        if (!isWsOpen()) { connectWs(); return; }
        JsonObject j = new JsonObject();
        j.addProperty("type", "login");
        j.addProperty("uid", uid);
        j.addProperty("token", token);
        send(j.toString());
    }

    /**
     * User-triggered reconnect from GUI. Resets all backoff timers.
     */
    public void reconnect() {
        lastError = "";
        if (wsClient != null) { try { wsClient.close(); } catch (Exception ignored) {} wsClient = null; }
        if (reconnectTask != null) { reconnectTask.cancel(false); reconnectTask = null; }
        wsOpen = false;
        loggedIn = false;
        wsRetryCount = 0;
        wsBackoffMs = INITIAL_BACKOFF_MS;
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

        // Monitor bridge process
        if (bridgeAlive && bridgeProcess != null && !bridgeProcess.isAlive()) {
            YcyModClient.LOGGER.warn("[YCY] Bridge process died");
            scheduleBridgeRestart();
            return;
        }

        // Health check: if WebSocket is up but heartbeat timed out, reconnect
        if (wsOpen && loggedIn && lastPongTime > 0
                && (now - lastPongTime) > HEARTBEAT_TIMEOUT_MS) {
            YcyModClient.LOGGER.warn("[YCY] Heartbeat timeout, reconnecting...");
            lastPongTime = 0;
            scheduleWsReconnect();
        }
    }

    // ========== Heartbeat ==========

    private void startHeartbeat() {
        if (heartbeatFuture != null) { heartbeatFuture.cancel(false); }
        lastPongTime = System.currentTimeMillis();
        heartbeatFuture = scheduler.scheduleAtFixedRate(
                () -> send("{\"type\":\"ping\"}"),
                HEARTBEAT_INTERVAL_S, HEARTBEAT_INTERVAL_S, TimeUnit.SECONDS);
    }

    private void stopHeartbeat() {
        if (heartbeatFuture != null) { heartbeatFuture.cancel(false); heartbeatFuture = null; }
    }
    private ScheduledFuture<?> heartbeatFuture;

    private volatile boolean shutdown;

    public void shutdown() {
        shutdown = true;
        stopHeartbeat();
        if (reconnectTask != null) { reconnectTask.cancel(false); }
        if (scheduler != null) { scheduler.shutdownNow(); }
        if (wsClient != null) { try { wsClient.close(); } catch (Exception ignored) {} }
        if (bridgeProcess != null && bridgeProcess.isAlive()) bridgeProcess.destroyForcibly();
        wsOpen = false; loggedIn = false; bridgeAlive = false;
    }

    // ========== Inner WS Client ==========

    private class ModWsClient extends WebSocketClient {
        ModWsClient(URI uri) { super(uri); }

        @Override
        public void onOpen(ServerHandshake h) {
            wsOpen = true;
            wsRetryCount = 0;
            wsBackoffMs = INITIAL_BACKOFF_MS;
            lastError = "";
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
                    case "pong":
                        lastPongTime = System.currentTimeMillis();
                        break;
                    case "connected":
                    case "heartbeat":
                        lastPongTime = System.currentTimeMillis();
                        break;
                    case "status":
                        lastPongTime = System.currentTimeMillis();
                        boolean ready = j.has("isReady") && j.get("isReady").getAsBoolean()
                                || j.has("data") && j.getAsJsonObject("data").has("isReady")
                                && j.getAsJsonObject("data").get("isReady").getAsBoolean();
                        if (ready && !loggedIn) {
                            loggedIn = true;
                            lastError = "";
                            if (onReady != null) onReady.run();
                        }
                        // Don't set loggedIn=false on transient isReady=false.
                        // The bridge auto-reconnects in 5s. Only WS close/error can log out.
                        break;
                    case "loginResult":
                        lastPongTime = System.currentTimeMillis();
                        if (j.has("success") && j.get("success").getAsBoolean()) {
                            loggedIn = true;
                            lastError = "";
                            if (onReady != null) onReady.run();
                        } else {
                            lastError = j.has("message") ? j.get("message").getAsString() : "登录失败";
                        }
                        break;
                    case "error":
                        lastError = j.has("message") ? j.get("message").getAsString() : "unknown";
                        YcyModClient.LOGGER.warn("[YCY] Server error: {}", lastError);
                        break;
                    default:
                        lastPongTime = System.currentTimeMillis();
                }
            } catch (Exception ignored) {}
        }

        @Override
        public void onClose(int code, String reason, boolean remote) {
            YcyModClient.LOGGER.warn("[YCY] WS closed ({}): {} remote={}", code, reason, remote);
            wsOpen = false;
            loggedIn = false;
            stopHeartbeat();
            if (onDisconnect != null) onDisconnect.run();
            if (!shutdown) scheduleWsReconnect();
        }

        @Override
        public void onError(Exception e) {
            YcyModClient.LOGGER.error("[YCY] WS error: {}", e.getMessage());
            lastError = "WS错误: " + e.getMessage();
            // Error often precedes close — close will handle reconnect
        }
    }
}
