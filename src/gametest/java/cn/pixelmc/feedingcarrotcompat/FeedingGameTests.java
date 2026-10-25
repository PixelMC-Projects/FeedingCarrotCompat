package cn.pixelmc.feedingcarrotcompat;

import com.cazsius.solcarrot.tracking.FoodList;
import com.rinko1231.letmefeedyou.Config.FeedPlayerConfig;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.HashMap;
import java.util.UUID;
import java.util.function.Consumer;
import net.minecraft.commands.arguments.EntityAnchorArgument;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.network.Connection;
import net.minecraft.network.PacketSendListener;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.game.ClientboundCustomPayloadPacket;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.nbt.CompoundTag;
import io.netty.channel.embedded.EmbeddedChannel;
import com.mojang.authlib.GameProfile;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.entity.living.LivingEntityUseItemEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

@GameTestHolder(FeedingCarrotCompat.ID)
@PrefixGameTestTemplate(false)
public final class FeedingGameTests {
    private static final Map<UUID, List<FeedNetwork.Feedback>> FEEDBACK = new HashMap<>();

    private static void capture(UUID player, Packet<?> packet) {
        if (packet instanceof ClientboundCustomPayloadPacket payload
                && payload.getIdentifier().toString().equals("feedingcarrotcompat:feeding")) {
            FriendlyByteBuf buffer = new FriendlyByteBuf(payload.getData().duplicate());
            if (buffer.readVarInt() == 1) FEEDBACK.computeIfAbsent(player, ignored -> new ArrayList<>())
                    .add(FeedNetwork.Feedback.decode(buffer));
        }
    }
    private static List<ServerPlayer> players(GameTestHelper helper) {
        FeedPlayerConfig.canBeFedBelowHungerOf.set(Integer.MAX_VALUE);
        FeedPlayerConfig.foodBlacklist.set(List.of());
        List<ServerPlayer> players = new ArrayList<>();
        for (int i = 0; i < 2; ++i) {
            // Vanilla's helper supplies a connection with no Netty channel, which
            // Forge networking cannot use. Supply an in-memory channel instead.
            UUID profileId = UUID.randomUUID();
            ServerPlayer player = new ServerPlayer(helper.getLevel().getServer(), helper.getLevel(),
                    new GameProfile(profileId, "feed_" + profileId.toString().substring(0, 8)));
            Connection connection = new Connection(PacketFlow.SERVERBOUND) {
                private final EmbeddedChannel embedded = new EmbeddedChannel();
                @Override public io.netty.channel.Channel channel() { return embedded; }
                @Override public void send(Packet<?> packet) { capture(player.getUUID(), packet); }
                @Override public void send(Packet<?> packet, PacketSendListener listener) { capture(player.getUUID(), packet); }
                @Override public boolean isConnected() { return true; }
            };
            player.connection = new ServerGamePacketListenerImpl(helper.getLevel().getServer(), connection, player);
            player.setGameMode(GameType.SURVIVAL);
            player.setNoGravity(true);
            Vec3 pos = Vec3.atBottomCenterOf(helper.absolutePos(new net.minecraft.core.BlockPos(1, 1, 1 + i * 2)));
            player.teleportTo(pos.x, pos.y, pos.z);
            player.getFoodData().setFoodLevel(20);
            FoodList.get(player).clearFood();
            helper.getLevel().addNewPlayer(player);
            PlayerHistory.observe(player);
            players.add(player);
        }
        players.get(0).lookAt(EntityAnchorArgument.Anchor.EYES, players.get(1).getEyePosition());
        return players;
    }

    private static void clean(List<ServerPlayer> players) {
        for (ServerPlayer player : players) {
            FEEDBACK.remove(player.getUUID());
            player.serverLevel().removePlayerImmediately(player, Entity.RemovalReason.DISCARDED);
        }
    }

    @GameTest(template = "empty", timeoutTicks = 100)
    public static void feedRecordsRecipientAndPreservesResult(GameTestHelper helper) {
        List<ServerPlayer> players = players(helper);
        ServerPlayer feeder = players.get(0), target = players.get(1);
        List<LivingEntityUseItemEvent.Finish> events = new ArrayList<>();
        Consumer<LivingEntityUseItemEvent.Finish> listener = event -> {
            if (event.getEntity() == target) events.add(event);
        };
        MinecraftForge.EVENT_BUS.addListener(EventPriority.LOWEST, false, LivingEntityUseItemEvent.Finish.class, listener);
        try {
            feeder.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.APPLE, 3));
            helper.assertTrue(FeedingServer.canFeed(feeder, target), "Full-hunger feeding must be allowed by original config");
            FeedingServer.finishFeed(feeder, target);
            helper.assertTrue(events.size() == 1, "Exactly one Finish event per feed");
            helper.assertTrue(events.get(0).getItem().getCount() == 3, "Finish event needs pre-consumption stack");
            helper.assertTrue(FoodList.get(target).hasEaten(Items.APPLE), "Recipient must record apple");
            helper.assertTrue(FoodList.get(feeder).getEatenFoodCount() == 0, "Feeder must not get recipient progression");
            helper.assertTrue(feeder.getMainHandItem().getCount() == 2, "Exactly one apple consumed");
            FeedingServer.finishFeed(feeder, target);
            helper.assertTrue(FoodList.get(target).getEatenFoodCount() == 1, "Repeated food must deduplicate");
            feeder.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.MUSHROOM_STEW));
            FeedingServer.finishFeed(feeder, target);
            helper.assertTrue(feeder.getMainHandItem().is(Items.BOWL), "Bowl must return to feeder");
            for (var food : List.of(Items.CARROT, Items.BREAD, Items.BAKED_POTATO)) {
                feeder.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(food));
                FeedingServer.finishFeed(feeder, target);
            }
            helper.assertTrue(FoodList.get(target).getEatenFoodCount() == 5, "Five distinct foods must be tracked");
            helper.assertTrue(target.getMaxHealth() > 20, "SOL Carrot milestone must update recipient maximum health");
            feeder.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.APPLE, 3));
            feeder.setGameMode(GameType.CREATIVE);
            FeedingServer.finishFeed(feeder, target);
            helper.assertTrue(feeder.getMainHandItem().getCount() == 3, "Creative feeder stack must be preserved");
            helper.succeed();
        } finally {
            MinecraftForge.EVENT_BUS.unregister(listener);
            clean(players);
        }
    }

    @GameTest(template = "empty", timeoutTicks = 100)
    public static void fullHoldIsRequiredAndReleaseCancels(GameTestHelper helper) {
        List<ServerPlayer> players = players(helper);
        ServerPlayer feeder = players.get(0), target = players.get(1);
        feeder.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.APPLE, 3));
        FeedingServer.input(feeder, FeedNetwork.START, 1, target.getId());
        for (int n = 0; n < 1000; ++n) FeedingServer.input(feeder, FeedNetwork.PULSE, 1, target.getId());
        helper.assertTrue(feeder.getMainHandItem().getCount() == 3, "Packet spam cannot feed instantly");
        for (int tick = 1; tick <= 40; ++tick) {
            final int at = tick;
            helper.runAfterDelay(tick, () -> {
                FeedingServer.input(feeder, FeedNetwork.PULSE, 1, target.getId());
                if (at == 10) {
                    FeedingServer.input(feeder, FeedNetwork.STOP, 1, target.getId());
                    helper.assertTrue(feeder.getMainHandItem().getCount() == 3, "Early release cannot consume");
                }
                if (at == 40) {
                    helper.assertTrue(FoodList.get(target).getEatenFoodCount() == 0, "Cancelled hold must not record food");
                    FeedingServer.input(feeder, FeedNetwork.START, 2, target.getId());
                }
            });
        }
        for (int tick = 41; tick <= 76; ++tick) {
            final int at = tick;
            helper.runAfterDelay(tick, () -> {
                FeedingServer.input(feeder, FeedNetwork.PULSE, 2, target.getId());
                if (at == 76) {
                    try {
                        helper.assertTrue(feeder.getMainHandItem().getCount() == 2, "Full hold must consume exactly once");
                        helper.assertTrue(FoodList.get(target).hasEaten(Items.APPLE), "Full hold must reach SOL Carrot");
                        helper.succeed();
                    } finally { clean(players); }
                }
            });
        }
    }

    @GameTest(template = "empty", timeoutTicks = 5)
    public static void timerDoesNotTrustSpamOrStaleInput(GameTestHelper helper) {
        HoldTimer timer = new HoldTimer(100, 32);
        for (int i = 0; i < 1000; ++i) helper.assertFalse(timer.pulse(100), "Same-tick packet spam must not complete");
        helper.assertFalse(timer.pulse(111), "Timed-out hold cannot complete");
        HoldTimer continuous = new HoldTimer(100, 32);
        for (int tick = 101; tick < 132; ++tick) helper.assertFalse(continuous.pulse(tick), "Must wait full duration");
        helper.assertTrue(continuous.pulse(132), "Valid continuous hold completes at full duration");
        helper.assertTrue(new HoldTimer(0, 0).duration == 20, "Nonstandard food cannot bypass minimum hold");
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 90)
    public static void permissionsRejectImmediatelyAndCancelInProgress(GameTestHelper helper) {
        List<ServerPlayer> players = players(helper);
        ServerPlayer feeder = players.get(0), target = players.get(1);
        FeedingPreferences data = FeedingPreferences.get(target.server);
        feeder.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.APPLE, 3));
        helper.assertTrue(FeedingServer.permitted(feeder, target), "New players must default to allowed");
        FeedNetwork.PermissionAck ack = FeedingSettingsServer.change(target,
                new FeedNetwork.PermissionChange(10, feeder.getUUID(), false));
        helper.assertTrue(ack.accepted() && !ack.allowed(), "Recipient may revoke their own feeding permission");
        FeedingServer.input(feeder, FeedNetwork.START, 700, target.getId());
        var feedback = FEEDBACK.get(feeder.getUUID());
        helper.assertTrue(feedback != null && feedback.get(feedback.size() - 1).status() == FeedNetwork.DENIED,
                "START must immediately send DENIED, without waiting for the hold timer");
        helper.assertFalse(FeedingServer.finishFeed(feeder, target), "Completion cannot bypass refusal");
        helper.assertTrue(feeder.getMainHandItem().getCount() == 3, "Refusal must not consume food");
        helper.assertTrue(FoodList.get(target).getEatenFoodCount() == 0, "Refusal must not award food progression");
        FeedingPreferences restored = FeedingPreferences.load(data.save(new CompoundTag()));
        restored.remember(feeder.getUUID(), "new-player-name", true);
        helper.assertFalse(restored.allows(target.getUUID(), feeder.getUUID()), "Preference survives save/load and rename");
        helper.assertTrue(restored.allows(UUID.randomUUID(), feeder.getUUID()), "Other recipients have independent settings");
        helper.assertTrue(restored.allows(target.getUUID(), UUID.randomUUID()), "Future new players default to allowed");
        FeedingSettingsServer.change(target, new FeedNetwork.PermissionChange(11, feeder.getUUID(), true));
        FeedingServer.input(feeder, FeedNetwork.START, 701, target.getId());
        for (int tick = 1; tick <= 36; ++tick) {
            final int at = tick;
            helper.runAfterDelay(tick, () -> {
                if (at == 8) {
                    FeedingSettingsServer.change(target, new FeedNetwork.PermissionChange(12, feeder.getUUID(), false));
                    var replies = FEEDBACK.get(feeder.getUUID());
                    helper.assertTrue(replies.get(replies.size() - 1).status() == FeedNetwork.DENIED,
                            "Revocation must immediately cancel an in-progress hold");
                }
                FeedingServer.input(feeder, FeedNetwork.PULSE, 701, target.getId());
                if (at == 36) {
                    try {
                        helper.assertTrue(feeder.getMainHandItem().getCount() == 3, "Revoked hold cannot finish");
                        helper.assertTrue(FoodList.get(target).getEatenFoodCount() == 0, "Revoked hold cannot update SOL Carrot");
                        helper.succeed();
                    } finally { clean(players); }
                }
            });
        }
    }

    @GameTest(template = "empty", timeoutTicks = 10)
    public static void directoryFiltersForgeAndCurtainBots(GameTestHelper helper) {
        List<ServerPlayer> players = players(helper);
        ServerPlayer target = players.get(0), feeder = players.get(1);
        FeedingPreferences data = FeedingPreferences.get(target.server);
        try {
            UUID forgeId = UUID.randomUUID();
            var forgeBot = new net.minecraftforge.common.util.FakePlayer(helper.getLevel(), new GameProfile(forgeId, "forge_feed_bot"));
            data.remember(forgeId, "forge_feed_bot", false);
            PlayerHistory.observe(forgeBot);
            helper.assertFalse(data.knows(forgeId), "Forge FakePlayer must be removed from the directory");

            Class<?> type = Class.forName("dev.dubhe.curtain.features.player.patches.EntityPlayerMPFake");
            var constructor = type.getDeclaredConstructor(net.minecraft.server.MinecraftServer.class,
                    net.minecraft.server.level.ServerLevel.class, GameProfile.class, boolean.class);
            constructor.setAccessible(true);
            UUID curtainId = UUID.randomUUID();
            ServerPlayer curtainBot = (ServerPlayer) constructor.newInstance(target.server, helper.getLevel(),
                    new GameProfile(curtainId, "curtain_feed_bot"), false);
            data.remember(curtainId, "curtain_feed_bot", false);
            PlayerHistory.observe(curtainBot);
            helper.assertTrue(PlayerHistory.isFake(curtainBot), "Must recognize the actual installed Curtain implementation");
            helper.assertFalse(data.knows(curtainId), "Curtain bots must not be listed");
            data.remember(curtainId, "real_account", true);
            PlayerHistory.observe(curtainBot);
            helper.assertTrue(data.knows(curtainId), "A shadow must not erase a historical real account with the same UUID");
            FeedNetwork.SettingsPage page = FeedingSettingsServer.page(target, 1, 0, "");
            helper.assertTrue(page.players().stream().noneMatch(p -> p.id().equals(target.getUUID())), "Do not list the recipient themselves");
            helper.assertTrue(page.players().stream().noneMatch(p -> p.id().equals(forgeId)), "Bot must not leak into network pages");
            var feederPage = FeedingSettingsServer.page(target, 2, 0, feeder.getGameProfile().getName());
            helper.assertTrue(feederPage.players().stream().anyMatch(p -> p.id().equals(feeder.getUUID()) && p.allowed()), "Previously joined players default checked");
            FriendlyByteBuf buffer = new FriendlyByteBuf(io.netty.buffer.Unpooled.buffer());
            try {
                page.encode(buffer);
                helper.assertTrue(page.equals(FeedNetwork.SettingsPage.decode(buffer)), "Roster pages must survive actual packet encoding");
            } finally { buffer.release(); }
            helper.succeed();
        } catch (ReflectiveOperationException error) {
            throw new RuntimeException(error);
        } finally { clean(players); }
    }
}
