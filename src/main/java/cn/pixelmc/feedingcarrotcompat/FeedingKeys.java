package cn.pixelmc.feedingcarrotcompat;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RegisterKeyMappingsEvent;
import net.minecraftforge.client.settings.KeyConflictContext;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

@Mod.EventBusSubscriber(modid = FeedingCarrotCompat.ID, value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.MOD)
public final class FeedingKeys {
    static final KeyMapping OPEN = new KeyMapping("key.feedingcarrotcompat.settings", KeyConflictContext.IN_GAME,
            InputConstants.Type.KEYSYM, InputConstants.UNKNOWN.getValue(), "key.categories.feedingcarrotcompat");

    @SubscribeEvent
    public static void register(RegisterKeyMappingsEvent event) { event.register(OPEN); }
    private FeedingKeys() {}
}
