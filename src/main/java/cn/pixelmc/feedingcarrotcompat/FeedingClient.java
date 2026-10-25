package cn.pixelmc.feedingcarrotcompat;

import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.InputEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

@Mod.EventBusSubscriber(modid = FeedingCarrotCompat.ID, value = Dist.CLIENT)
public final class FeedingClient {
    private static int serial;
    private static Active active;
    private static int deniedTarget = -1;

    @SubscribeEvent
    public static void tick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        Minecraft mc = Minecraft.getInstance();
        while (FeedingKeys.OPEN.consumeClick()) {
            if (mc.player != null && mc.level != null && mc.screen == null && mc.isWindowActive()) {
                stop(mc);
                mc.setScreen(new FeedingSettingsScreen());
            }
        }
        Player target = target(mc);
        if (mc.player == null || mc.level == null || mc.screen != null || !mc.isWindowActive()
                || !mc.options.keyUse.isDown() || target == null || mc.player.isUsingItem()) {
            stop(mc);
            deniedTarget = -1;
            return;
        }
        if (deniedTarget == target.getId()) return;
        deniedTarget = -1;
        if (active != null && (active.target != target.getId() || active.slot != mc.player.getInventory().selected
                || !ItemStack.matches(active.food, mc.player.getMainHandItem()))) stop(mc);
        if (active == null) {
            active = new Active(++serial, target.getId(), mc.player.getInventory().selected,
                    mc.player.getMainHandItem().copy());
            FeedNetwork.sendInput(FeedNetwork.START, active.sequence, active.target);
        } else {
            FeedNetwork.sendInput(FeedNetwork.PULSE, active.sequence, active.target);
        }
    }

    // Prevent vanilla falling back to eating the held food while aiming at a player.
    @SubscribeEvent
    public static void use(InputEvent.InteractionKeyMappingTriggered event) {
        Minecraft mc = Minecraft.getInstance();
        if (event.isUseItem() && target(mc) != null) {
            event.setCanceled(true);
            event.setSwingHand(false);
        }
    }

    private static Player target(Minecraft mc) {
        if (mc.player == null || !mc.player.getMainHandItem().isEdible() || mc.player.isSpectator()) return null;
        return mc.hitResult instanceof EntityHitResult hit && hit.getEntity() instanceof Player player
                && player != mc.player ? player : null;
    }

    private static void stop(Minecraft mc) {
        if (active != null) {
            if (mc.getConnection() != null) FeedNetwork.sendInput(FeedNetwork.STOP, active.sequence, active.target);
            active = null;
            if (mc.player != null) mc.player.displayClientMessage(Component.empty(), true);
        }
    }

    static void feedback(FeedNetwork.Feedback packet) {
        if (active == null || packet.sequence() != active.sequence) return;
        Minecraft mc = Minecraft.getInstance();
        if (packet.status() == FeedNetwork.PROGRESS) {
            if (mc.player != null && packet.duration() > 0) {
                mc.player.displayClientMessage(Component.translatable("message.feedingcarrotcompat.progress",
                        Math.min(100, packet.ticks() * 100 / packet.duration())), true);
            }
        } else {
            if (packet.status() == FeedNetwork.DENIED) deniedTarget = active.target;
            active = null;
            if (mc.player != null) mc.player.displayClientMessage(packet.status() == FeedNetwork.COMPLETE
                    ? Component.translatable("message.feedingcarrotcompat.complete")
                    : packet.status() == FeedNetwork.DENIED ? Component.translatable("message.feedingcarrotcompat.denied")
                    : Component.empty(), true);
        }
    }

    static void settings(FeedNetwork.SettingsPage page) {
        if (Minecraft.getInstance().screen instanceof FeedingSettingsScreen screen) screen.receive(page);
    }

    static void permission(FeedNetwork.PermissionAck ack) {
        if (Minecraft.getInstance().screen instanceof FeedingSettingsScreen screen) screen.receive(ack);
    }

    private record Active(int sequence, int target, int slot, ItemStack food) {}
    private FeedingClient() {}
}
