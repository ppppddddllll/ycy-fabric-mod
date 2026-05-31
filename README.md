# YCY Link — 役次元 × Minecraft Fabric 联动模组

[![License](https://img.shields.io/badge/license-MIT-green)](LICENSE)
[![Minecraft](https://img.shields.io/badge/Minecraft-1.20.1-blue)](https://www.minecraft.net)
[![Fabric](https://img.shields.io/badge/Fabric--Loader-%3E%3D0.15.0-orange)](https://fabricmc.net)

让 Minecraft 中的游戏事件**实时联动役次元玩具设备**。受伤时电击、死亡时强刺激、击杀怪物时触发——全部可在 GUI 里自由配置。

本模组移植自 [tzwgoo/Minecraft-YCY-Link](https://github.com/tzwgoo/Minecraft-YCY-Link) 的 Forge 版，针对 Fabric 生态重写并增强。

---

## 目录

- [功能特性](#功能特性)
- [前置要求](#前置要求)
- [安装](#安装)
- [快速上手](#快速上手)
- [役次元 App 端配置](#役次元-app-端配置)
- [模组 GUI 配置](#模组-gui-配置)
- [指令系统](#指令系统)
- [快捷键](#快捷键)
- [预设事件列表](#预设事件列表)
- [自定义事件](#自定义事件)
- [架构说明](#架构说明)
- [开发构建](#开发构建)
- [故障排查](#故障排查)
- [参考项目](#参考项目)
- [许可证](#许可证)

---

## 功能特性

- **16 种预设事件**：受伤、死亡、击杀、方块破坏、放置、低血量、着火、溺水、爆炸等
- **游戏内 GUI**：按 `Y` 键打开配置界面，双页面设计（连接设置 + 事件开关）
- **事件 JSON 驱动**：所有事件配置从 `events.json` 读取，用户可自由增删改
- **内嵌桥接服务**：一键启动，无需手动配置 Node.js 服务或 API-Bridge
- **WebSocket 通信**：实时双向通信，自动心跳保活、断线重连
- **中文界面**：GUI 与游戏内消息全中文
- **冷却机制**：每个事件独立冷却时间，防止重复触发

---

## 前置要求

| 组件 | 版本 | 说明 |
|------|------|------|
| Minecraft | **1.20.1** | |
| Fabric Loader | **>= 0.15.0** | [下载](https://fabricmc.net/use/) |
| Fabric API | **最新版** | [CurseForge](https://www.curseforge.com/minecraft/mc-mods/fabric-api) |
| Cloth Config API | **>= 11.0.0** | [CurseForge](https://www.curseforge.com/minecraft/mc-mods/cloth-config) 或 [Modrinth](https://modrinth.com/mod/cloth-config) |
| Mod Menu | **>= 7.0.0** (推荐) | 提供 Mod 列表入口 [下载](https://modrinth.com/mod/modmenu) |
| Node.js | **>= 18** | [nodejs.org](https://nodejs.org) 下载安装即可，模组自动调用 |
| 役次元 App | **最新版** | 用于创建游戏、获取连接码 |

---

## 安装

1. 安装 [Fabric Loader](https://fabricmc.net/use/) (选择 Minecraft 1.20.1)
2. 将 `Fabric API`、`Cloth Config API`、`Mod Menu` 放入 `.minecraft/mods/`
3. 将 `ycy-link-1.0.0.jar` 放入 `.minecraft/mods/`
4. 安装 [Node.js](https://nodejs.org) (>= 18)
5. 启动 Minecraft

> **注意**：首次启动时，模组会自动在 `config/ycy-link/bridge/` 下解压桥接文件并执行 `npm install`（后台进行，不阻塞游戏）。需要保持网络连接。

---

## 快速上手

### 1. 在役次元 App 获取连接码

1. 打开役次元 App
2. 进入「联动游戏」→ 扫描游戏二维码（或直接选择已保存的游戏）
3. 点击「启动游戏」
4. 复制 **连接码**（格式：`5 OWs4sZtTP...`）

### 2. 在游戏中连接

打开游戏后，有两种方式：

**方式一：快捷键 `Y`**

按 `Y` → 打开配置界面 → 粘贴 UID（连接码空格前）和 Token（连接码空格后） → 点「连接」

**方式二：指令**

```
/ycy login 5 OWs4sZtTP...
```

> 连接码格式：**UID + 空格 + Token**。UID 是纯数字，Token 是一长串字符。

### 3. 开始游玩

连接成功后，状态栏会显示绿色「已登录」。之后所有预设事件会自动触发玩具。你可以：

- 按 `Y` → 切到「事件设置」页 → 开启/关闭单个事件
- 按 `N` 紧急停止所有设备
- `/ycy events` 查看所有事件状态

---

## 役次元 App 端配置

作为开发者（不是玩家），需要先在役次元 App 中创建游戏并配置指令。

### 创建游戏

1. 役次元 App → 联动游戏 → 新建游戏 → **开发游戏**
2. 填写基本信息：

| 字段 | 建议值 |
|------|--------|
| 游戏唯一码 | `com.ycy.minecraft` |
| 游戏名称 | `我的世界联动` |
| 游戏教程 | 你的教程文档链接 |
| 游戏作者 | 你的名字 |
| 启动地址 | 留空 |

### 添加指令

在「指令配置」中，根据 `events.json` 中的 `command_id` 添加对应指令：

| 指令名称 | 指令ID |
|---------|--------|
| 玩家受伤 | `player_damage` |
| 玩家死亡 | `player_death` |
| 击杀实体 | `entity_killed` |
| 玩家加入 | `player_join` |
| 玩家离开 | `player_leave` |
| 玩家聊天 | `player_chat` |
| 破坏方块 | `block_break` |
| 放置方块 | `block_place` |
| 左键挖方块 | `block_attack` |
| 使用物品 | `item_use` |
| 低血量预警 | `player_low_hp` |
| 玩家着火 | `player_on_fire` |
| 玩家溺水 | `player_drown` |
| 附近爆炸 | `explosion_nearby` |
| 中毒状态 | `player_poisoned` |
| 附近有怪物 | `mob_nearby` |

每个指令绑定对应的玩具设备，电击强度建议 15～40。

### 分享给玩家

保存后用 App 生成二维码，玩家扫描即可一键获取配置。

---

## 模组 GUI 配置

按 `Y` 键打开配置界面，包含两个页面：

### 页面 1：连接设置

```
┌──────────────────────────────────┐
│         役次元联动配置            │
│           连接设置                │
│                                  │
│  WebSocket URL                   │
│  ws://localhost:18790            │
│                                  │
│  UID (纯数字)                    │
│  5                               │
│                                  │
│  Token                           │
│  OWs4sZtTP...                    │
│                                  │
│  [连接]        [保存]            │
│  [        测试连接       ]       │
│  [     已启用 / 已禁用   ]       │
│                                  │
│  状态: 未连接 / 已连接 / 已登录  │
│        [ 事件设置 → ]            │
│          按 ESC 关闭              │
└──────────────────────────────────┘
```

- **WebSocket URL**：默认 `ws://localhost:18790`，通常不需要改
- **UID**：输入连接码中空格前面的纯数字部分
- **Token**：输入连接码中空格后面的长字符串
- **连接**：保存输入内容并连接役次元
- **保存**：仅保存不连接
- **测试连接**：发送一条测试指令验证通信正常

### 页面 2：事件设置

```
┌──────────────────────────────────┐
│           事件设置               │
│    启用/禁用各项游戏事件          │
│        (1-12 / 16) 滚轮翻页      │
│                                  │
│  玩家受伤: 启用 / 禁用           │
│  玩家死亡: 启用 / 禁用           │
│  击杀实体: 启用 / 禁用           │
│  ...                             │
│        ↕ 鼠标滚轮                │
│  附近有怪物: 启用 / 禁用         │
│                                  │
│        [ ← 连接设置 ]            │
│          按 ESC 关闭              │
└──────────────────────────────────┘
```

- **鼠标滚轮**：当事件超过一屏时，用滚轮翻页
- **点击按钮**：切换单个事件的启用/禁用状态
- **即时生效**：不需要额外保存

---

## 指令系统

| 指令 | 说明 |
|------|------|
| `/ycy gui` | 打开配置界面 |
| `/ycy login <uid> <token>` | 直接登录（跳过 GUI） |
| `/ycy status` | 查看连接状态 |
| `/ycy stop` | 紧急停止所有设备 |
| `/ycy toggle` | 启用/停用模组 |
| `/ycy events` | 在聊天栏列出所有事件 |

---

## 快捷键

| 按键 | 功能 |
|------|------|
| **Y** | 打开役次元配置界面 |
| **N** | 紧急停止所有设备 |

可在 设置 → 按键绑定 → "役次元联动" 分类中自定义。

---

## 预设事件列表

共 16 种预设事件，默认全部启用：

| 事件ID | 中文名 | 指令ID | 冷却 |
|--------|--------|--------|------|
| `player_damage` | 玩家受伤 | `player_damage` | 2s |
| `player_death` | 玩家死亡 | `player_death` | 5s |
| `entity_killed` | 击杀实体 | `entity_killed` | 1s |
| `player_join` | 玩家加入 | `player_join` | 3s |
| `player_leave` | 玩家离开 | `player_leave` | 3s |
| `player_chat` | 玩家聊天 | `player_chat` | 1s |
| `block_break` | 破坏方块 | `block_break` | 0.5s |
| `block_place` | 放置方块 | `block_place` | 0.5s |
| `block_attack` | 左键挖方块 | `block_attack` | 0.5s |
| `item_use` | 使用物品 | `item_use` | 0.5s |
| `player_low_hp` | 低血量预警 | `player_low_hp` | 3s |
| `player_on_fire` | 玩家着火 | `player_on_fire` | 1.5s |
| `player_drown` | 玩家溺水 | `player_drown` | 2s |
| `explosion_nearby` | 附近爆炸 | `explosion_nearby` | 5s |
| `player_poisoned` | 中毒状态 | `player_poisoned` | 3s |
| `mob_nearby` | 附近有怪物 | `mob_nearby` | 5s |

---

## 自定义事件

编辑 `config/ycy-link/events.json`，添加一行即可：

```json
{
    "event_id": "my_custom_event",
    "command_id": "my_custom_cmd",
    "cooldown_ms": 2000,
    "enabled": true,
    "display_name": "我的自定义事件"
}
```

修改后重启游戏生效。`event_id` 必须在 `GameEventType.java` 中有对应的 Fabric 事件监听，或通过 Mixin 自行注入。

---

## 架构说明

```
┌──────────────────────────────────────────────┐
│              用户可见层                       │
│  ┌────────────────┐  ┌──────────────────┐    │
│  │  YcyConfigScreen│  │ /ycy 指令        │    │
│  │  (按 Y 键打开)  │  │ login/stop/...   │    │
│  └────────┬───────┘  └────────┬─────────┘    │
│           │                   │              │
│           │    ┌──────────────┘              │
│           ▼    ▼                             │
│  ┌─────────────────────────────┐             │
│  │     BridgeManager.java      │             │
│  │   WebSocket 客户端 + 进程管理  │            │
│  │   ┌── 自动提取桥接文件         │            │
│  │   ├── npm install (后台)      │            │
│  │   ├── ProcessBuilder 启动     │            │
│  │   └── 30s 心跳检测           │            │
│  └─────────────┬───────────────┘             │
│                │ ws://localhost:18790        │
├────────────────┼──────────────────────────────┤
│                ▼          后台服务层          │
│  ┌─────────────────────────────┐             │
│  │    Node.js Bridge Server    │             │
│  │  ├─ WebSocket ↔ IM 协议转换  │            │
│  │  ├─ @tencentcloud/chat      │            │
│  │  └─ Tencent IM 登录/收发     │            │
│  └─────────────┬───────────────┘             │
│                │                              │
├────────────────┼──────────────────────────────┤
│                ▼          外部服务层          │
│  ┌─────────────────────────────┐             │
│  │    Tencent Cloud IM         │             │
│  │    (腾讯云即时通讯)           │             │
│  └─────────────┬───────────────┘             │
│                │                              │
│  ┌─────────────▼───────────────┐             │
│  │  役次元 App + 玩具设备       │            │
│  └─────────────────────────────┘             │
└──────────────────────────────────────────────┘
```

### 通信流程

```
Minecraft 事件触发
    │
    ▼
EventHandlers.java
    │ EventRegistry.findTriggerable()
    ▼
BridgeManager.sendCommand("player_hurt")
    │ WebSocket JSON: {"type":"sendCommand","commandId":"player_hurt"}
    ▼
Node.js Bridge Server
    │ 构造 IM 消息: {code:"game_cmd", id:"player_hurt", token:"..."}
    ▼
Tencent IM → 役次元 App → 玩具设备触发
```

---

## 开发构建

```bash
git clone https://github.com/ppppddddllll/ycy-fabric-mod.git
cd ycy-fabric-mod
./gradlew build
```

构建产物：
- `build/libs/ycy-link-1.0.0.jar` — 模组 JAR
- `build/libs/ycy-link-1.0.0-sources.jar` — 源码 JAR

运行测试客户端：
```bash
./gradlew runClient
```

---

## 故障排查

| 问题 | 原因 | 解决 |
|------|------|------|
| 连接失败，状态显示红色 | 未登录或连接断开 | 检查 UID/Token 是否正确，按「连接」重试 |
| 连接后状态黄色（已连接未登录） | WebSocket 通但 IM 未登录 | 检查 Token 是否过期，重新获取连接码 |
| 模组无反应 | 事件未启用或模组已禁用 | `/ycy events` 查看，确保事件开关为绿色 |
| 桥接启动失败 | Node.js 未安装 | [下载 Node.js](https://nodejs.org) |
| npm install 失败 | 网络问题 | 手动进入 `config/ycy-link/bridge/` 目录执行 `npm install` |
| 按 Y 键无反应 | 按键冲突 | 去 设置 → 按键绑定 中修改 |
| 方块事件不触发 | 仅在单机/局域网服务器生效 | Fabric 事件需服务端支持，连远程服务器时部分事件不可用 |

---

## 参考项目

| 项目 | 说明 |
|------|------|
| [tzwgoo/Minecraft-YCY-Link](https://github.com/tzwgoo/Minecraft-YCY-Link) | Forge 版原始实现，本项目的 UI 与事件模型来源 |
| [YCY-YOKONEX/YCY-YOKONEX-OpenSource](https://github.com/YCY-YOKONEX/YCY-YOKONEX-OpenSource) | 役次元官方开源通信协议（蓝牙 + IM + WebSocket） |
| [YCY-YOKONEX/API-bridge](https://github.com/YCY-YOKONEX/API-bridge) | HTTP/WebSocket → IM 桥接服务（内嵌版本的原型） |

## 许可证

[MIT License](LICENSE)
