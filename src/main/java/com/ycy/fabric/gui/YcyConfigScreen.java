package com.ycy.fabric.gui;

import com.ycy.fabric.YcyModClient;
import com.ycy.fabric.bridge.BridgeManager;
import com.ycy.fabric.config.EventMapping;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 配置界面 - 完全参考 tzwgoo/Minecraft-YCY-Link Forge 版 GUI
 * 页面0: 连接设置 (URL, UID, Token, 状态)
 * 页面1: 事件开关列表
 */
public class YcyConfigScreen extends Screen {
    private static final int LABEL_COLOR = 0xA0A0A0;

    private int currentPage;

    // Page 0
    private TextFieldWidget urlField;
    private TextFieldWidget uidField;
    private TextFieldWidget tokenField;

    // Page 1
    private final Map<String, ButtonWidget> eventButtons = new LinkedHashMap<>();
    private List<EventMapping> events;
    private int scrollOffset;

    public YcyConfigScreen() {
        super(Text.literal("役次元联动配置"));
        this.currentPage = 0;
    }

    @Override
    protected void init() {
        this.events = YcyModClient.getEventRegistry().getMappings();
        this.scrollOffset = Math.min(scrollOffset, Math.max(0, events.size() - visibleSlots()));

        if (currentPage == 0) {
            initConnectionPage();
        } else {
            initEventsPage();
        }

        // Page switch button (bottom center, like Forge version)
        int pageBtnW = 200;
        this.addDrawableChild(ButtonWidget.builder(
                Text.literal(currentPage == 0 ? "事件设置 →" : "← 连接设置"),
                btn -> switchPage()
        ).dimensions(this.width / 2 - pageBtnW / 2, this.height - 27, pageBtnW, 20).build());
    }

    // ==================== Page 0: Connection ====================

    private void initConnectionPage() {
        int cx = this.width / 2;
        int fw = 300;
        int left = cx - fw / 2;
        int y = 30;

        // URL
        this.urlField = new TextFieldWidget(this.textRenderer, left, y, fw, 20, Text.literal(""));
        this.urlField.setMaxLength(256);
        this.urlField.setText(YcyModClient.getConfigManager().loadUrl());
        this.addDrawableChild(this.urlField);

        // UID
        this.uidField = new TextFieldWidget(this.textRenderer, left, y + 40, fw, 20, Text.literal(""));
        this.uidField.setMaxLength(128);
        this.uidField.setText(YcyModClient.getConfigManager().loadUid());
        this.addDrawableChild(this.uidField);

        // Token
        this.tokenField = new TextFieldWidget(this.textRenderer, left, y + 80, fw, 20, Text.literal(""));
        this.tokenField.setMaxLength(512);
        this.tokenField.setText(YcyModClient.getConfigManager().loadToken());
        this.addDrawableChild(this.tokenField);

        // Connect + Save (side by side, like Forge)
        int halfW = 145;
        this.addDrawableChild(ButtonWidget.builder(
                Text.literal("连接"), btn -> onConnectClick()
        ).dimensions(left, y + 120, halfW, 20).build());

        this.addDrawableChild(ButtonWidget.builder(
                Text.literal("保存"), btn -> onSaveClick()
        ).dimensions(left + halfW + 10, y + 120, halfW, 20).build());

        // Test button (full width)
        this.addDrawableChild(ButtonWidget.builder(
                Text.literal("测试连接"), btn -> onTestClick()
        ).dimensions(left, y + 145, fw, 20).build());

        // Enabled/disabled toggle (full width)
        this.addDrawableChild(ButtonWidget.builder(
                Text.literal(YcyModClient.isModEnabled() ? "已启用" : "已禁用"),
                btn -> onEnabledClick()
        ).dimensions(left, y + 170, fw, 20).build());

        // UID / Token hint
        this.addDrawableChild(ButtonWidget.builder(
                Text.literal("连接码 = UID + 空格 + Token").formatted(Formatting.DARK_GRAY),
                b -> {}
        ).dimensions(left, y + 195, fw, 20).build());

        this.setInitialFocus(this.urlField);
    }

    // ==================== Page 1: Events (scrollable) ====================

    private int eventTop() { return 30; }
    private int eventBottom() { return this.height - 40; }
    private int visibleSlots() {
        return Math.max(1, (eventBottom() - eventTop()) / 22);
    }

    private void initEventsPage() {
        int cx = this.width / 2;
        int fw = 300;
        int btnH = 20;
        int spacing = 22;
        int visible = visibleSlots();

        eventButtons.clear();

        for (int i = 0; i < visible && (scrollOffset + i) < events.size(); i++) {
            final int idx = scrollOffset + i;
            EventMapping ev = events.get(idx);
            String displayName = ev.getDisplayName() != null ? ev.getDisplayName() : ev.getEventId();
            int y = eventTop() + i * spacing;

            ButtonWidget btn = ButtonWidget.builder(
                    Text.literal(displayName + ": " + (ev.isEnabled() ? "§a启用" : "§c禁用")),
                    b -> onEventToggle(idx)
            ).dimensions(cx - fw / 2, y, fw, btnH).build();

            eventButtons.put(ev.getEventId(), btn);
            this.addDrawableChild(btn);
        }
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double amount) {
        if (currentPage == 1) {
            int old = scrollOffset;
            scrollOffset -= (int) amount;
            int max = Math.max(0, events.size() - visibleSlots());
            if (scrollOffset > max) scrollOffset = max;
            if (scrollOffset < 0) scrollOffset = 0;
            if (scrollOffset != old) {
                clearChildren();
                init();
            }
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, amount);
    }

    // ==================== Actions - Page 0 ====================

    private void onConnectClick() {
        // Save current values before connecting
        YcyModClient.getConfigManager().saveUrl(this.urlField.getText().trim());
        YcyModClient.getConfigManager().saveUid(this.uidField.getText().trim());
        YcyModClient.getConfigManager().saveToken(this.tokenField.getText().trim());
        BridgeManager.instance().reconnect();
    }

    private void onSaveClick() {
        YcyModClient.getConfigManager().saveUrl(this.urlField.getText().trim());
        YcyModClient.getConfigManager().saveUid(this.uidField.getText().trim());
        YcyModClient.getConfigManager().saveToken(this.tokenField.getText().trim());
        YcyModClient.getConfigManager().saveEvents(YcyModClient.getEventRegistry());
        YcyModClient.LOGGER.info("[YCY] Config + events saved");
    }

    private void onTestClick() {
        BridgeManager.instance().sendCommand("test_command");
    }

    private void onEnabledClick() {
        boolean ns = !YcyModClient.isModEnabled();
        YcyModClient.setModEnabled(ns);
        // Rebuild page to refresh button text
        clearChildren();
        init();
    }

    // ==================== Actions - Page 1 ====================

    private void onEventToggle(int idx) {
        EventMapping ev = events.get(idx);
        ev.setEnabled(!ev.isEnabled());
        clearChildren();
        init();
    }

    private void switchPage() {
        currentPage = 1 - currentPage;
        scrollOffset = 0;
        clearChildren();
        init();
    }

    // ==================== Render ====================

    @Override
    public void render(DrawContext ctx, int mouseX, int mouseY, float delta) {
        this.renderBackground(ctx);

        // Title
        String title = currentPage == 0 ? "连接设置" : "事件设置";
        ctx.drawCenteredTextWithShadow(this.textRenderer, Text.literal(title), this.width / 2, 15, 0xFFFFFF);

        if (currentPage == 0) {
            renderConnectionPage(ctx, mouseX, mouseY);
        } else {
            renderEventsPage(ctx);
        }

        // Bottom hint
        ctx.drawCenteredTextWithShadow(this.textRenderer,
                Text.literal("§7按 ESC 关闭"), this.width / 2, this.height - 12, 0x808080);

        super.render(ctx, mouseX, mouseY, delta);
    }

    private void renderConnectionPage(DrawContext ctx, int mouseX, int mouseY) {
        int cx = this.width / 2;
        int fw = 300;
        int left = cx - fw / 2;
        int y = 30;

        // Labels + fields rendered by widgets, just draw status
        ctx.drawTextWithShadow(this.textRenderer,
                Text.literal("WebSocket URL").formatted(Formatting.GRAY), left, y - 12, LABEL_COLOR);
        ctx.drawTextWithShadow(this.textRenderer,
                Text.literal("UID (纯数字)").formatted(Formatting.GRAY), left, y + 28, LABEL_COLOR);
        ctx.drawTextWithShadow(this.textRenderer,
                Text.literal("Token").formatted(Formatting.GRAY), left, y + 68, LABEL_COLOR);

        // Connection status
        String statusText;
        int statusColor;
        try {
            BridgeManager bm = BridgeManager.instance();
            if (!bm.isWsOpen()) {
                statusText = "未连接";
                statusColor = 0xFF5555;
            } else if (bm.isConnected()) {
                statusText = "已登录";
                statusColor = 0x55FF55;
            } else {
                statusText = "已连接(未登录)";
                statusColor = 0xFFFF55;
            }
        } catch (Exception e) {
            statusText = "WebSocket 不可用";
            statusColor = 0xFF5555;
        }
        ctx.drawTextWithShadow(this.textRenderer,
                Text.literal("状态: " + statusText), left, y + 195, statusColor);
    }

    private void renderEventsPage(DrawContext ctx) {
        int cx = this.width / 2;
        ctx.drawCenteredTextWithShadow(this.textRenderer,
                Text.literal("§7启用/禁用各项游戏事件"), cx, eventTop() - 10, 0xFFFFFF);

        // Scroll indicator
        int visible = visibleSlots();
        if (events.size() > visible) {
            String info = String.format("(%d-%d / %d)  滚轮翻页",
                    scrollOffset + 1,
                    Math.min(scrollOffset + visible, events.size()),
                    events.size());
            ctx.drawCenteredTextWithShadow(this.textRenderer,
                    Text.literal(info).formatted(Formatting.DARK_GRAY), cx, eventTop() - 3, 0x888888);
        }
    }

    @Override
    public void tick() {
        super.tick();
        if (this.urlField != null) this.urlField.tick();
        if (this.uidField != null) this.uidField.tick();
        if (this.tokenField != null) this.tokenField.tick();
    }

    @Override
    public boolean shouldPause() {
        return false;
    }

    @Override
    public void close() {
        super.close();
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (keyCode == 256) { // ESC
            this.close();
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }
}
