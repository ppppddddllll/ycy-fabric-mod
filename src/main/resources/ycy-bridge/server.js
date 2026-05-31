/* eslint-disable */
/**
 * YCY Bridge Server v2
 * HTTP + WebSocket bridge for YOKONEX IM protocol
 * Embedded in the Fabric mod JAR, auto-started by BridgeManager
 */

const http = require('http');
const WebSocket = require('ws');
const TencentCloudChat = require('@tencentcloud/chat');

// Node.js check: global WebSocket alias for Tencent SDK
if (typeof globalThis.WebSocket === 'undefined') {
    try {
        globalThis.WebSocket = require('ws');
    } catch (_) { /* Node 22+ has built-in */ }
}

const API_BASE = 'https://suo.jiushu1234.com/api.php';
const PORT = 18790;

// ===================== Logger =====================
function log(level, ...args) {
    console.log(`[${new Date().toISOString()}] [${level}]`, ...args);
}

// ===================== IM State =====================
let chat = null;
let isReady = false;
let currentConfig = { uid: null, token: null, userId: null, appId: null };
let imReconnectTimer = null;
let imReconnectDelay = 1000;        // Start at 1s, max 30s
const IM_MAX_RECONNECT_DELAY = 30000;

// ===================== IM Lifecycle =====================
async function getGameSign(uid, token) {
    log('INFO', `Requesting game_sign for ${uid}...`);
    const resp = await fetch(`${API_BASE}/user/game_sign`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ uid, token }),
    });
    if (!resp.ok) throw new Error(`game_sign HTTP ${resp.status}`);
    const payload = await resp.json();
    if (payload.code !== 1 || !payload.data) {
        throw new Error(`game_sign failed: ${JSON.stringify(payload)}`);
    }
    return { appId: payload.data.appid, userSig: payload.data.sign };
}

function scheduleIMReconnect() {
    if (imReconnectTimer) clearTimeout(imReconnectTimer);

    if (!currentConfig.uid || !currentConfig.token) {
        log('WARN', 'No credentials saved, cannot reconnect IM');
        return;
    }

    const delay = imReconnectDelay;
    imReconnectDelay = Math.min(imReconnectDelay * 2, IM_MAX_RECONNECT_DELAY);
    log('INFO', `IM reconnect scheduled in ${delay}ms`);

    imReconnectTimer = setTimeout(async () => {
        imReconnectTimer = null;
        try {
            await initIM(
                currentConfig.uid.replace(/^game_/, ''),
                currentConfig.token
            );
            // On success, schedule a status refresh
            broadcast({ type: 'status', isReady: true, uid: currentConfig.uid });
        } catch (e) {
            log('ERROR', 'IM reconnect failed:', e.message);
            scheduleIMReconnect();
        }
    }, delay);
}

async function initIM(uid, token) {
    if (chat) await destroyIM();
    const gameUid = uid.startsWith('game_') ? uid : `game_${uid}`;
    const rawUid = uid.replace(/^game_/, '');

    log('INFO', `Getting sign for ${gameUid}...`);
    const { appId, userSig } = await getGameSign(gameUid, token);

    currentConfig = { uid: gameUid, token, userId: rawUid, appId };
    log('INFO', `Creating IM client, appId: ${appId}`);

    chat = TencentCloudChat.create({ SDKAppID: Number(appId) });
    if (chat.setLogLevel) chat.setLogLevel(4);

    chat.on(TencentCloudChat.EVENT.SDK_READY, () => {
        isReady = true;
        imReconnectDelay = 1000;  // Reset backoff on success
        log('INFO', `IM SDK READY, user: ${chat.getLoginUser()}`);
        broadcast({ type: 'status', isReady: true, uid: gameUid, userId: rawUid });
    });

    chat.on(TencentCloudChat.EVENT.KICKED_OUT, (event) => {
        log('WARN', 'IM kicked out, type:', event?.data?.type || 'unknown');
        handleIMDisconnect();
    });

    chat.on(TencentCloudChat.EVENT.SDK_NOT_READY, () => {
        log('WARN', 'IM SDK not ready');
        if (isReady) {
            handleIMDisconnect();
        }
    });

    chat.on(TencentCloudChat.EVENT.NET_STATE_CHANGE, (event) => {
        const state = event?.data?.state;
        log('INFO', 'IM network state:', state);
        if (state === 'DISCONNECTED' || state === 'RECONNECTING') {
            if (isReady) {
                isReady = false;
                broadcast({ type: 'status', isReady: false });
            }
        }
    });

    chat.on(TencentCloudChat.EVENT.ERROR, (e) => {
        log('WARN', 'IM SDK error:', e?.message || e, 'code:', e?.code);
        // errorCode 2801 = request timeout, usually recovers
        if (e?.code !== 2801) {
            handleIMDisconnect();
        }
    });

    chat.on(TencentCloudChat.EVENT.MESSAGE_RECEIVED, (event) => {
        for (const msg of event.data) {
            try {
                const content = JSON.parse(msg.payload.text);
                log('INFO', 'Received IM message:', JSON.stringify(content));
                broadcast({ type: 'message', data: content });
            } catch {
                broadcast({ type: 'message', data: { text: msg.payload.text } });
            }
        }
    });

    log('INFO', `Logging in as ${gameUid}...`);
    const res = await chat.login({ userID: gameUid, userSig });
    if (res?.data?.repeatLogin) log('WARN', 'Repeat login:', res.data.errorInfo);

    await new Promise((resolve, reject) => {
        const timer = setTimeout(() => reject(new Error('SDK_READY timeout')), 30000);
        chat.on(TencentCloudChat.EVENT.SDK_READY, () => { clearTimeout(timer); resolve(); });
    });

    log('INFO', 'IM initialized successfully');
}

function handleIMDisconnect() {
    isReady = false;
    broadcast({ type: 'status', isReady: false });
    scheduleIMReconnect();
}

async function destroyIM() {
    if (imReconnectTimer) { clearTimeout(imReconnectTimer); imReconnectTimer = null; }
    if (chat) {
        try { await chat.logout(); await chat.destroy(); } catch (e) { log('WARN', 'Destroy IM error:', e.message); }
        chat = null;
    }
    isReady = false;
}

async function sendCommand(commandId) {
    if (!chat || !isReady) throw new Error('IM not ready');
    const message = chat.createTextMessage({
        to: currentConfig.userId,
        conversationType: TencentCloudChat.TYPES.CONV_C2C,
        payload: {
            text: JSON.stringify({
                code: 'game_cmd',
                id: commandId,
                token: currentConfig.token,
            }),
        },
    });
    const result = await chat.sendMessage(message);
    log('INFO', `Command sent: ${commandId}`);
    return result;
}

// ===================== WebSocket Server =====================
const server = http.createServer((req, res) => {
    // Minimal HTTP: health check endpoint
    if (req.url === '/health' && req.method === 'GET') {
        res.writeHead(200, { 'Content-Type': 'application/json' });
        res.end(JSON.stringify({ status: 'ok', imReady: isReady, uid: currentConfig.uid }));
        return;
    }
    res.writeHead(404);
    res.end();
});

const wss = new WebSocket.Server({ server });
const clients = new Set();

function broadcast(data) {
    const msg = typeof data === 'string' ? data : JSON.stringify(data);
    for (const ws of clients) {
        if (ws.readyState === WebSocket.OPEN) {
            ws.send(msg);
        }
    }
}

wss.on('connection', (ws) => {
    clients.add(ws);
    log('INFO', `WebSocket client connected (total: ${clients.size})`);

    ws.send(JSON.stringify({ type: 'connected', message: 'Bridge server connected' }));

    ws.on('message', async (raw) => {
        let data;
        try {
            data = JSON.parse(typeof raw === 'string' ? raw : raw.toString());
        } catch {
            ws.send(JSON.stringify({ type: 'error', message: 'Invalid JSON' }));
            return;
        }

        const { type } = data;

        try {
            switch (type) {
                case 'ping':
                    ws.send(JSON.stringify({ type: 'pong' }));
                    break;

                case 'getStatus':
                    ws.send(JSON.stringify({
                        type: 'status',
                        isReady,
                        config: {
                            uid: currentConfig.uid,
                            userId: currentConfig.userId,
                            appId: currentConfig.appId,
                            hasToken: !!currentConfig.token,
                        },
                    }));
                    break;

                case 'login': {
                    const { uid, token } = data;
                    if (!uid || !token) {
                        ws.send(JSON.stringify({ type: 'loginResult', success: false, message: 'Missing uid or token' }));
                        return;
                    }
                    try {
                        await initIM(uid, token);
                        ws.send(JSON.stringify({ type: 'loginResult', success: true, uid: currentConfig.uid }));
                    } catch (e) {
                        ws.send(JSON.stringify({ type: 'loginResult', success: false, message: e.message }));
                    }
                    break;
                }

                case 'sendCommand': {
                    const { commandId } = data;
                    if (!commandId) {
                        ws.send(JSON.stringify({ type: 'commandResult', success: false, message: 'Missing commandId' }));
                        return;
                    }
                    if (!isReady) {
                        ws.send(JSON.stringify({ type: 'commandResult', success: false, message: 'IM not ready' }));
                        return;
                    }
                    try {
                        await sendCommand(commandId);
                        ws.send(JSON.stringify({ type: 'commandResult', success: true, commandId }));
                    } catch (e) {
                        ws.send(JSON.stringify({ type: 'commandResult', success: false, message: e.message }));
                    }
                    break;
                }

                case 'reinit':
                    try {
                        if (currentConfig.uid && currentConfig.token) {
                            await initIM(currentConfig.uid.replace(/^game_/, ''), currentConfig.token);
                        }
                        ws.send(JSON.stringify({ type: 'reinitResult', success: true }));
                    } catch (e) {
                        ws.send(JSON.stringify({ type: 'reinitResult', success: false, message: e.message }));
                    }
                    break;

                default:
                    ws.send(JSON.stringify({ type: 'error', message: `Unknown type: ${type}` }));
            }
        } catch (e) {
            ws.send(JSON.stringify({ type: 'error', message: e.message }));
            log('ERROR', 'Message handler error:', e.message);
        }
    });

    ws.on('close', () => {
        clients.delete(ws);
        log('INFO', `WebSocket client disconnected (total: ${clients.size})`);
    });

    ws.on('error', (err) => {
        log('ERROR', 'WebSocket error:', err.message);
    });
});

// ===================== Heartbeat =====================
const HEARTBEAT_INTERVAL = setInterval(() => {
    broadcast({ type: 'heartbeat', imReady: isReady });
}, 30000);

// ===================== Start Server =====================
server.listen(PORT, () => {
    log('INFO', '============================================================');
    log('INFO', `  YCY Bridge Server v2 started`);
    log('INFO', `  WebSocket: ws://localhost:${PORT}`);
    log('INFO', `  HTTP:      http://localhost:${PORT}/health`);
    log('INFO', '============================================================');
});

// Graceful shutdown
async function shutdown() {
    log('INFO', 'Shutting down...');
    clearInterval(HEARTBEAT_INTERVAL);
    await destroyIM();
    wss.close();
    server.close(() => process.exit(0));
}

process.on('SIGTERM', shutdown);
process.on('SIGINT', shutdown);
