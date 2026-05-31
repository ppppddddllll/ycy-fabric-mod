package com.ycy.fabric.event;

/**
 * Game event types aligned with YOKONEX protocol
 * Matches tzwgoo/Minecraft-YCY-Link event set
 */
public enum GameEventType {
    PLAYER_DAMAGE("player_damage", "玩家受伤"),
    PLAYER_DEATH("player_death", "玩家死亡"),
    ENTITY_KILLED("entity_killed", "击杀实体"),
    PLAYER_JOIN("player_join", "玩家加入"),
    PLAYER_LEAVE("player_leave", "玩家离开"),
    PLAYER_CHAT("player_chat", "玩家聊天"),
    BLOCK_BREAK("block_break", "破坏方块"),
    BLOCK_PLACE("block_place", "放置方块"),
    BLOCK_ATTACK("block_attack", "左键挖方块"),
    ITEM_USE("item_use", "使用物品"),
    PLAYER_LOW_HP("player_low_hp", "低血量预警"),
    PLAYER_ON_FIRE("player_on_fire", "玩家着火"),
    PLAYER_DROWN("player_drown", "玩家溺水"),
    EXPLOSION_NEARBY("explosion_nearby", "附近爆炸"),
    PLAYER_POISONED("player_poisoned", "中毒状态"),
    MOB_NEARBY("mob_nearby", "附近有怪物"),
    ;

    private final String id;
    private final String displayName;

    GameEventType(String id, String displayName) {
        this.id = id;
        this.displayName = displayName;
    }

    public String getId() { return id; }
    public String getDisplayName() { return displayName; }

    public static GameEventType fromId(String id) {
        for (GameEventType type : values()) {
            if (type.id.equals(id)) return type;
        }
        return null;
    }
}
