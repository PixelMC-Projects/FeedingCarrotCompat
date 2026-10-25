package cn.pixelmc.feedingcarrotcompat;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.ObjectSelectionList;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

/** Vanilla-style, scrollable directory; every checkbox is acknowledged by the server. */
public final class FeedingSettingsScreen extends Screen {
    private static int serial;
    private final Map<UUID, Integer> pending = new HashMap<>();
    private FeedNetwork.SettingsPage snapshot;
    private PlayerList list;
    private EditBox search;
    private Button previous, next, refresh;
    private int page, requestId, searchDelay = -1, waitTicks;
    private boolean loading;
    private String query = "";
    private Component status = Component.empty();

    public FeedingSettingsScreen() { super(text("title")); }
    private static Component text(String key, Object... args) {
        return Component.translatable("screen.feedingcarrotcompat." + key, args);
    }

    @Override
    protected void init() {
        int panel = Math.min(420, width - 24), left = (width - panel) / 2;
        search = addRenderableWidget(new EditBox(font, left, 46, panel - 74, 20, text("search")));
        search.setMaxLength(64);
        search.setHint(text("search"));
        search.setValue(query);
        search.setResponder(value -> {
            query = value;
            page = 0;
            searchDelay = 6;
        });
        refresh = addRenderableWidget(Button.builder(text("refresh"), button -> request())
                .bounds(left + panel - 68, 46, 68, 20).build());
        list = addRenderableWidget(new PlayerList(panel, 74, height - 76));
        list.setLeftPos(left);
        previous = addRenderableWidget(Button.builder(Component.literal("<"), button -> { --page; request(); })
                .bounds(left, height - 68, 30, 20).build());
        next = addRenderableWidget(Button.builder(Component.literal(">"), button -> { ++page; request(); })
                .bounds(left + panel - 30, height - 68, 30, 20).build());
        addRenderableWidget(Button.builder(text("done"), button -> onClose())
                .bounds(width / 2 - 60, height - 25, 120, 20).build());
        if (snapshot != null) fillList();
        request();
    }

    private void request() {
        requestId = ++serial;
        loading = true;
        waitTicks = 0;
        searchDelay = -1;
        status = text("loading");
        updateButtons();
        if (minecraft.getConnection() == null) {
            loading = false;
            status = text("timeout");
            updateButtons();
            return;
        }
        FeedNetwork.requestSettings(requestId, page, query);
    }

    void receive(FeedNetwork.SettingsPage packet) {
        if (packet.requestId() != requestId) return;
        snapshot = packet;
        page = packet.page();
        loading = false;
        status = Component.empty();
        fillList();
        updateButtons();
    }

    void receive(FeedNetwork.PermissionAck packet) {
        if (!Integer.valueOf(packet.requestId()).equals(pending.get(packet.feeder()))) return;
        pending.remove(packet.feeder());
        for (PlayerRow row : list.children()) {
            if (row.entry.id().equals(packet.feeder())) row.allowed = packet.allowed();
        }
        status = packet.accepted() ? text("saved") : text("rejected");
        updateButtons();
    }

    private void fillList() {
        double scroll = list.getScrollAmount();
        list.reset();
        if (snapshot != null) snapshot.players().forEach(entry -> list.append(new PlayerRow(entry)));
        list.setScrollAmount(scroll);
    }

    private void updateButtons() {
        if (previous == null) return;
        previous.active = !loading && page > 0;
        next.active = !loading && snapshot != null && page + 1 < snapshot.pages();
        refresh.active = !loading;
    }

    private void toggle(PlayerRow row) {
        if (loading || pending.containsKey(row.entry.id()) || minecraft.getConnection() == null) return;
        int changeId = ++serial;
        pending.put(row.entry.id(), changeId);
        waitTicks = 0;
        status = text("saving");
        FeedNetwork.changePermission(changeId, row.entry.id(), !row.allowed);
    }

    @Override
    public void tick() {
        search.tick();
        if (searchDelay > 0 && --searchDelay == 0) request();
        if ((loading || !pending.isEmpty()) && ++waitTicks > 200) {
            pending.clear();
            loading = false;
            status = text("timeout");
            updateButtons();
        }
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        renderBackground(graphics);
        int panel = Math.min(420, width - 24), left = (width - panel) / 2;
        graphics.fill(left, 73, left + panel, height - 75, 0x80202020);
        graphics.drawCenteredString(font, title, width / 2, 12, 0xFFFFFF);
        graphics.drawCenteredString(font, text("description"), width / 2, 29, 0xCCCCCC);
        super.render(graphics, mouseX, mouseY, partialTick);
        if (!loading && snapshot != null && snapshot.players().isEmpty()) {
            graphics.drawCenteredString(font, text("empty"), width / 2, 89, 0xAAAAAA);
        }
        Component footer = snapshot == null ? status : text("page", page + 1, snapshot.pages(), snapshot.total());
        graphics.drawCenteredString(font, footer, width / 2, height - 62, 0xDDDDDD);
        graphics.drawCenteredString(font, status.getString().isEmpty() ? text("defaults") : status,
                width / 2, height - 39, pending.isEmpty() ? 0xAACCBB : 0xFFD37A);
    }

    @Override public boolean isPauseScreen() { return false; }

    private final class PlayerList extends ObjectSelectionList<PlayerRow> {
        PlayerList(int panel, int top, int bottom) {
            super(FeedingSettingsScreen.this.minecraft, panel, FeedingSettingsScreen.this.height, top, bottom, 28);
            setRenderBackground(false);
            setRenderTopAndBottom(false);
        }
        void reset() { clearEntries(); }
        void append(PlayerRow row) { addEntry(row); }
        @Override public int getRowWidth() { return width - 14; }
        @Override protected int getScrollbarPosition() { return getRight() - 6; }
        @Override public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
            if ((keyCode == GLFW.GLFW_KEY_SPACE || keyCode == GLFW.GLFW_KEY_ENTER) && getSelected() != null) {
                toggle(getSelected());
                return true;
            }
            return super.keyPressed(keyCode, scanCode, modifiers);
        }
    }

    private final class PlayerRow extends ObjectSelectionList.Entry<PlayerRow> {
        final FeedNetwork.PlayerEntry entry;
        boolean allowed;

        PlayerRow(FeedNetwork.PlayerEntry entry) {
            this.entry = entry;
            allowed = entry.allowed();
        }

        @Override public Component getNarration() {
            return text("narration", entry.name(), text(allowed ? "allowed" : "denied"));
        }

        @Override
        public void render(GuiGraphics graphics, int index, int top, int left, int rowWidth, int rowHeight,
                int mouseX, int mouseY, boolean hovering, float partialTick) {
            boolean enabled = !loading && !pending.containsKey(entry.id());
            graphics.fill(left, top, left + rowWidth - 2, top + 25,
                    hovering ? 0x505B6670 : index % 2 == 0 ? 0x202F343B : 0x102F343B);
            graphics.fill(left + 5, top + 5, left + 21, top + 21, enabled ? 0xFFC4C4C4 : 0xFF777777);
            graphics.fill(left + 6, top + 6, left + 20, top + 20, 0xFF24282B);
            if (allowed) graphics.drawString(font, "✓", left + 8, top + 8, 0x8FE0AD, false);
            String name = font.plainSubstrByWidth(entry.name(), rowWidth - 100);
            graphics.drawString(font, name, left + 30, top + 9, 0xFFFFFF, false);
            Component presence = text(entry.online() ? "online" : "offline");
            graphics.drawString(font, presence, left + rowWidth - font.width(presence) - 12, top + 9,
                    entry.online() ? 0x91DCA5 : 0x929AA3, false);
        }

        @Override public boolean mouseClicked(double mouseX, double mouseY, int button) {
            if (button != 0) return false;
            list.setSelected(this);
            toggle(this);
            return true;
        }
    }
}
