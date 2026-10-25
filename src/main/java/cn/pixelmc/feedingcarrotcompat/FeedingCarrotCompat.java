package cn.pixelmc.feedingcarrotcompat;

import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.common.Mod;

@Mod(FeedingCarrotCompat.ID)
public final class FeedingCarrotCompat {
    public static final String ID = "feedingcarrotcompat";

    public FeedingCarrotCompat() {
        FeedNetwork.register();
        MinecraftForge.EVENT_BUS.register(new FeedingServer());
        MinecraftForge.EVENT_BUS.register(new FeedingSettingsServer());
    }
}
