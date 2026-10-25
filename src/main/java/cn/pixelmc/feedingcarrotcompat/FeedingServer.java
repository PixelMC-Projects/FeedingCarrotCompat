package cn.pixelmc.feedingcarrotcompat;

import com.rinko1231.letmefeedyou.Config.FeedPlayerConfig;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.event.ForgeEventFactory;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.event.server.ServerStoppedEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.registries.ForgeRegistries;

public final class FeedingServer {
    private static final Map<UUID, Session> SESSIONS = new HashMap<>();
    private static final Map<UUID, Long> TARGET_COOLDOWN = new HashMap<>();
    private static long clock;

    // Cancel the base mod's immediate interaction handler. All feeding below still
    // reads the base mod's actual config; no mixin or modification of its JAR.
    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public void interact(PlayerInteractEvent.EntityInteract event) {
        if (event.getTarget() instanceof Player && event.getEntity().getMainHandItem().isEdible()) {
            event.setCanceled(true);
            event.setCancellationResult(InteractionResult.SUCCESS);
        }
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public void interactSpecific(PlayerInteractEvent.EntityInteractSpecific event) {
        if (event.getTarget() instanceof Player && event.getEntity().getMainHandItem().isEdible()) {
            event.setCanceled(true);
            event.setCancellationResult(InteractionResult.SUCCESS);
        }
    }

    static void input(ServerPlayer feeder, int action, int sequence, int targetId) {
        if (action == FeedNetwork.STOP) {
            Session session = SESSIONS.get(feeder.getUUID());
            if (session != null && session.sequence == sequence) cancel(feeder, session);
            return;
        }
        if (action == FeedNetwork.START) {
            Entity entity = feeder.level().getEntity(targetId);
            if (entity instanceof ServerPlayer target && !permitted(feeder, target)) {
                SESSIONS.remove(feeder.getUUID());
                FeedNetwork.feedback(feeder, sequence, FeedNetwork.DENIED, 0, 0);
                return;
            }
            if (!(entity instanceof ServerPlayer target) || !canFeed(feeder, target)) {
                FeedNetwork.feedback(feeder, sequence, FeedNetwork.CANCEL, 0, 0);
                return;
            }
            Session session = new Session(feeder, target, sequence, clock);
            SESSIONS.put(feeder.getUUID(), session);
            FeedNetwork.feedback(feeder, sequence, FeedNetwork.PROGRESS, 0, session.timer.duration);
            return;
        }
        if (action != FeedNetwork.PULSE) return;
        Session session = SESSIONS.get(feeder.getUUID());
        if (session == null || session.sequence != sequence || session.target.getId() != targetId) return;
        if (session.timer.expired(clock) || !valid(feeder, session)) {
            cancel(feeder, session);
            return;
        }
        if (session.timer.pulse(clock)) {
            SESSIONS.remove(feeder.getUUID());
            // One recipient per second, matching the base mod's cooldown.
            if (clock >= TARGET_COOLDOWN.getOrDefault(session.target.getUUID(), 0L)) {
                if (finishFeed(feeder, session.target)) {
                    TARGET_COOLDOWN.put(session.target.getUUID(), clock + 20);
                    FeedNetwork.feedback(feeder, sequence, FeedNetwork.COMPLETE, session.timer.duration, session.timer.duration);
                } else FeedNetwork.feedback(feeder, sequence, FeedNetwork.DENIED, 0, 0);
            } else {
                FeedNetwork.feedback(feeder, sequence, FeedNetwork.CANCEL, 0, 0);
            }
        } else if (clock % 4 == 0) {
            FeedNetwork.feedback(feeder, sequence, FeedNetwork.PROGRESS, session.timer.elapsed(clock), session.timer.duration);
        }
    }

    static boolean canFeed(ServerPlayer feeder, ServerPlayer target) {
        ItemStack food = feeder.getMainHandItem();
        if (feeder == target || !feeder.isAlive() || !target.isAlive()
                || feeder.isSpectator() || target.isSpectator() || feeder.isUsingItem()
                || feeder.level() != target.level() || !food.isEdible() || !permitted(feeder, target)) return false;
        String id = String.valueOf(ForgeRegistries.ITEMS.getKey(food.getItem()));
        if (FeedPlayerConfig.foodBlacklist.get().contains(id)
                || target.getFoodData().getFoodLevel() >= FeedPlayerConfig.canBeFedBelowHungerOf.get()) return false;
        if (!feeder.canReach(target, 0) || !feeder.hasLineOfSight(target)) return false;
        Vec3 eye = feeder.getEyePosition();
        Vec3 end = eye.add(feeder.getLookAngle().scale(feeder.getEntityReach()));
        return target.getBoundingBox().inflate(0.1).contains(eye)
                || target.getBoundingBox().inflate(0.1).clip(eye, end).isPresent();
    }

    private static boolean valid(ServerPlayer feeder, Session session) {
        return feeder.getInventory().selected == session.slot
                && ItemStack.matches(session.food, feeder.getMainHandItem())
                && canFeed(feeder, session.target);
    }

    static boolean permitted(ServerPlayer feeder, ServerPlayer target) {
        return FeedingPreferences.get(target.server).allows(target.getUUID(), feeder.getUUID());
    }

    static boolean finishFeed(ServerPlayer feeder, ServerPlayer target) {
        if (!permitted(feeder, target)) return false;
        ItemStack held = feeder.getMainHandItem();
        ItemStack before = held.copy();
        // Use a copy for creative feeders so finishUsingItem cannot shrink their hand.
        ItemStack consumed = (feeder.getAbilities().instabuild ? held.copy() : held)
                .finishUsingItem(target.level(), target);
        // This is the normal Forge Finish event, with the RECIPIENT and the stack
        // from BEFORE consumption. SOL Carrot owns deduplication, HP and sync.
        ItemStack result = ForgeEventFactory.onItemUseFinish(target, before, 0, consumed);
        if (!feeder.getAbilities().instabuild) feeder.setItemInHand(InteractionHand.MAIN_HAND, result);
        target.displayClientMessage(Component.translatable("info.letmefeedyou.success",
                feeder.getDisplayName().getString(), before.getHoverName()), true);
        feeder.getInventory().setChanged();
        feeder.containerMenu.broadcastChanges();
        return true;
    }

    static void permissionsChanged(ServerPlayer recipient) {
        Iterator<Session> it = SESSIONS.values().iterator();
        while (it.hasNext()) {
            Session session = it.next();
            if (session.target.getUUID().equals(recipient.getUUID()) && !permitted(session.feeder, recipient)) {
                it.remove();
                FeedNetwork.feedback(session.feeder, session.sequence, FeedNetwork.DENIED, 0, 0);
            }
        }
    }

    private static void cancel(ServerPlayer feeder, Session session) {
        SESSIONS.remove(feeder.getUUID());
        FeedNetwork.feedback(feeder, session.sequence,
                permitted(feeder, session.target) ? FeedNetwork.CANCEL : FeedNetwork.DENIED, 0, 0);
    }

    @SubscribeEvent
    public void tick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        ++clock;
        Iterator<Session> it = SESSIONS.values().iterator();
        while (it.hasNext()) {
            Session session = it.next();
            if (session.timer.expired(clock) || !valid(session.feeder, session)) {
                it.remove();
                FeedNetwork.feedback(session.feeder, session.sequence,
                        permitted(session.feeder, session.target) ? FeedNetwork.CANCEL : FeedNetwork.DENIED, 0, 0);
            }
        }
        TARGET_COOLDOWN.entrySet().removeIf(entry -> entry.getValue() <= clock);
    }

    @SubscribeEvent
    public void logout(PlayerEvent.PlayerLoggedOutEvent event) {
        UUID id = event.getEntity().getUUID();
        SESSIONS.remove(id);
        SESSIONS.values().removeIf(session -> session.target.getUUID().equals(id));
        TARGET_COOLDOWN.remove(id);
    }

    @SubscribeEvent
    public void stopped(ServerStoppedEvent event) { SESSIONS.clear(); TARGET_COOLDOWN.clear(); clock = 0; }

    private static final class Session {
        final ServerPlayer feeder, target;
        final int sequence, slot;
        final ItemStack food;
        final HoldTimer timer;
        Session(ServerPlayer feeder, ServerPlayer target, int sequence, long now) {
            this.feeder = feeder;
            this.target = target;
            this.sequence = sequence;
            slot = feeder.getInventory().selected;
            food = feeder.getMainHandItem().copy();
            timer = new HoldTimer(now, food.getUseDuration());
        }
    }
}
