# YCY Link - 役次元 × Minecraft Fabric 联动模组

Minecraft 游戏事件实时联动役次元玩具设备。Fabric 1.20.1 纯客户端模组。

## 架构

```
Minecraft (Fabric Mod)
  │
  ├─ YcyModClient (入口)
  │   ├─ BridgeManager      — 管理 Node.js 桥接进程 + WebSocket 客户端
  │   ├─ EventHandlers      — Fabric API 事件监听 (13种预设事件)
  │   ├─ EventRegistry      — 事件→指令映射注册表
  │   ├─ YcyCommand         — /ycy 指令 (6个子命令)
  │   └─ YcyConfigScreen    — 双页面 GUI
  │
  ├─ WebSocket Client (Java-WebSocket) → ws://localhost:18790
  │
  └─ 内嵌 Bridge Server (Node.js, auto-launched)
      ├─ WebSocket ↔ IM 协议转换
      └─ Tencent IM → 役次元 App → 玩具设备
```

## 前置要求

| 组件 | 版本 |
|------|------|
| Minecraft | 1.20.1 |
| Fabric Loader | >= 0.15.0 |
| Fabric API | 最新版 |
| Cloth Config API | >= 11.0.0 |
| Mod Menu | >= 7.0.0 (可选) |
| Node.js | >= 18 (自动启动桥接用) |

## 与现成 Forge 版的区别

本模组参考了 [tzwgoo/Minecraft-YCY-Link](https://github.com/tzwgoo/Minecraft-YCY-Link) 的 Forge 实现，将其移植到 Fabric 并做了以下改进：

| 特性 | Forge 版 | Fabric 版 (本项目) |
|------|---------|-------------------|
| WebSocket 客户端 | Java-WebSocket 1.5.4 | Java-WebSocket 1.5.4 (include 打包) |
| 桥接服务 | 需手动启动 API-Bridge | **内嵌自动启动** (ProcessBuilder) |
| GUI | 单页面双页签 | 单页面双页签 |
| 事件模型 | 10 种 (可开关) | 13 种 (可开关 + 自定义) |
| Mixin | 空实现 (模板残留) | 功能性 Mixin |
| 平台 | Forge 1.20.1 | **Fabric 1.20.1** |

## 快速使用

```
/ycy gui           — 打开配置界面
/ycy login <连接码> — 连接役次元
/ycy events         — 列出所有事件
/ycy stop           — 紧急停止
/ycy toggle         — 开关模组
/ycy status         — 连接状态
```

## 构建

```bash
./gradlew build
```

## 参考项目

- [tzwgoo/Minecraft-YCY-Link](https://github.com/tzwgoo/Minecraft-YCY-Link) - Forge 原始实现
- [YCY-YOKONEX/API-bridge](https://github.com/YCY-YOKONEX/API-bridge) - IM 转 WS/HTTP 桥接

## 许可证

MIT
