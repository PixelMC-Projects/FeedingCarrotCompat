package cn.pixelmc.feedingcarrotcompat;

import com.mojang.logging.LogUtils;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.UUID;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/** Isolated development run only; not included in either release JAR. */
@Mod.EventBusSubscriber(modid = FeedingCarrotCompat.ID, value = Dist.CLIENT)
public final class FeedingUiSmoke {
    private static FeedingSettingsScreen screen;
    private static int frames;
    private static java.util.concurrent.CompletableFuture<Void> languageReload;

    @SubscribeEvent
    public static void tick(TickEvent.ClientTickEvent event) throws Exception {
        if (event.phase != TickEvent.Phase.END || screen != null) return;
        Minecraft mc = Minecraft.getInstance();
        if (!(mc.screen instanceof TitleScreen) || mc.getOverlay() != null) return;
        if (!FeedingKeys.OPEN.isUnbound() || !FeedingKeys.OPEN.getDefaultKey().equals(com.mojang.blaze3d.platform.InputConstants.UNKNOWN))
            throw new IllegalStateException("Settings key must be unbound by default");
        if (languageReload == null) {
            mc.options.languageCode = "zh_cn";
            mc.getLanguageManager().setSelected("zh_cn");
            languageReload = mc.reloadResourcePacks();
            return;
        }
        if (!languageReload.isDone()) return;
        screen = new FeedingSettingsScreen();
        mc.setScreen(screen);
        var request = FeedingSettingsScreen.class.getDeclaredField("requestId");
        request.setAccessible(true);
        var players = new ArrayList<FeedNetwork.PlayerEntry>();
        String[] names = {"Fantastic_Dream", "ovo_Jiang", "TKnvr", "BXHeeee", "qian_v_qian", "Hsiao_0204", "Nuyoah_1101", "Shinoran", "ovofengfeng", "Styg10", "xiaozhouzbc", "NANAnnnny", "BuHuiQi555", "zhan_cpu"};
        for (int i = 0; i < names.length; ++i)
            players.add(new FeedNetwork.PlayerEntry(UUID.nameUUIDFromBytes(names[i].getBytes(java.nio.charset.StandardCharsets.UTF_8)), names[i], i % 3 == 0, i != 1));
        screen.receive(new FeedNetwork.SettingsPage(request.getInt(screen), 0, 1, players.size(), "", players));
    }

    @SubscribeEvent
    public static void rendered(TickEvent.RenderTickEvent event) throws Exception {
        if (event.phase != TickEvent.Phase.END || screen == null || ++frames != 30) return;
        Minecraft mc = Minecraft.getInstance();
        Path image = Path.of("../deployment/feeding-permissions-preview.png");
        Files.createDirectories(image.toAbsolutePath().getParent());
        try (var pixels = Screenshot.takeScreenshot(mc.getMainRenderTarget())) { pixels.writeToFile(image); }
        LogUtils.getLogger().info("FEEDING_UI_SMOKE_PASS: unbound key, Chinese permissions screen, checked/unchecked rows, screenshot {}", image.toAbsolutePath());
        mc.stop();
    }
}
