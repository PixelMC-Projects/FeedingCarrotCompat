package cn.pixelmc.feedingcarrotcompat;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.PacketDistributor;
import net.minecraftforge.network.simple.SimpleChannel;

public final class FeedNetwork {
    public static final int START = 0, PULSE = 1, STOP = 2;
    public static final int PROGRESS = 0, COMPLETE = 1, CANCEL = 2, DENIED = 3;
    private static final SimpleChannel CHANNEL = NetworkRegistry.newSimpleChannel(
            new ResourceLocation(FeedingCarrotCompat.ID, "feeding"), () -> "2", "2"::equals, "2"::equals);

    static void register() {
        CHANNEL.messageBuilder(Input.class, 0, NetworkDirection.PLAY_TO_SERVER)
                .encoder(Input::encode).decoder(Input::decode).consumerMainThread(Input::handle).add();
        CHANNEL.messageBuilder(Feedback.class, 1, NetworkDirection.PLAY_TO_CLIENT)
                .encoder(Feedback::encode).decoder(Feedback::decode).consumerMainThread(Feedback::handle).add();
        CHANNEL.messageBuilder(SettingsRequest.class, 2, NetworkDirection.PLAY_TO_SERVER)
                .encoder(SettingsRequest::encode).decoder(SettingsRequest::decode).consumerMainThread(SettingsRequest::handle).add();
        CHANNEL.messageBuilder(SettingsPage.class, 3, NetworkDirection.PLAY_TO_CLIENT)
                .encoder(SettingsPage::encode).decoder(SettingsPage::decode).consumerMainThread(SettingsPage::handle).add();
        CHANNEL.messageBuilder(PermissionChange.class, 4, NetworkDirection.PLAY_TO_SERVER)
                .encoder(PermissionChange::encode).decoder(PermissionChange::decode).consumerMainThread(PermissionChange::handle).add();
        CHANNEL.messageBuilder(PermissionAck.class, 5, NetworkDirection.PLAY_TO_CLIENT)
                .encoder(PermissionAck::encode).decoder(PermissionAck::decode).consumerMainThread(PermissionAck::handle).add();
    }

    public static void sendInput(int action, int sequence, int target) {
        CHANNEL.sendToServer(new Input(action, sequence, target));
    }

    static void requestSettings(int requestId, int page, String query) {
        CHANNEL.sendToServer(new SettingsRequest(requestId, page, query));
    }

    static void changePermission(int requestId, UUID feeder, boolean allowed) {
        CHANNEL.sendToServer(new PermissionChange(requestId, feeder, allowed));
    }

    static void settings(ServerPlayer player, SettingsPage page) {
        CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), page);
    }

    static void feedback(ServerPlayer player, int sequence, int status, int ticks, int duration) {
        if (player.connection != null) {
            CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), new Feedback(sequence, status, ticks, duration));
        }
    }

    public record Input(int action, int sequence, int target) {
        void encode(FriendlyByteBuf buf) { buf.writeVarInt(action); buf.writeVarInt(sequence); buf.writeVarInt(target); }
        static Input decode(FriendlyByteBuf buf) { return new Input(buf.readVarInt(), buf.readVarInt(), buf.readVarInt()); }
        void handle(Supplier<NetworkEvent.Context> ctx) {
            ServerPlayer sender = ctx.get().getSender();
            if (sender != null) FeedingServer.input(sender, action, sequence, target);
            ctx.get().setPacketHandled(true);
        }
    }

    public record Feedback(int sequence, int status, int ticks, int duration) {
        void encode(FriendlyByteBuf buf) {
            buf.writeVarInt(sequence); buf.writeVarInt(status); buf.writeVarInt(ticks); buf.writeVarInt(duration);
        }
        static Feedback decode(FriendlyByteBuf buf) {
            return new Feedback(buf.readVarInt(), buf.readVarInt(), buf.readVarInt(), buf.readVarInt());
        }
        void handle(Supplier<NetworkEvent.Context> ctx) {
            DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> FeedingClient.feedback(this));
            ctx.get().setPacketHandled(true);
        }
    }

    public record SettingsRequest(int requestId, int page, String query) {
        void encode(FriendlyByteBuf buf) { buf.writeVarInt(requestId); buf.writeVarInt(page); buf.writeUtf(query, 64); }
        static SettingsRequest decode(FriendlyByteBuf buf) {
            return new SettingsRequest(buf.readVarInt(), buf.readVarInt(), buf.readUtf(64));
        }
        void handle(Supplier<NetworkEvent.Context> ctx) {
            ServerPlayer sender = ctx.get().getSender();
            if (sender != null) FeedingSettingsServer.request(sender, this);
            ctx.get().setPacketHandled(true);
        }
    }

    public record PlayerEntry(UUID id, String name, boolean online, boolean allowed) {}

    public record SettingsPage(int requestId, int page, int pages, int total, String query, List<PlayerEntry> players) {
        void encode(FriendlyByteBuf buf) {
            buf.writeVarInt(requestId); buf.writeVarInt(page); buf.writeVarInt(pages); buf.writeVarInt(total);
            buf.writeUtf(query, 64); buf.writeVarInt(players.size());
            for (PlayerEntry player : players) {
                buf.writeUUID(player.id()); buf.writeUtf(player.name(), 64);
                buf.writeBoolean(player.online()); buf.writeBoolean(player.allowed());
            }
        }
        static SettingsPage decode(FriendlyByteBuf buf) {
            int requestId = buf.readVarInt(), page = buf.readVarInt(), pages = buf.readVarInt(), total = buf.readVarInt();
            String query = buf.readUtf(64);
            int count = buf.readVarInt();
            if (count < 0 || count > FeedingSettingsServer.PAGE_SIZE) throw new IllegalArgumentException("Invalid roster page");
            List<PlayerEntry> players = new ArrayList<>(count);
            for (int i = 0; i < count; ++i) {
                players.add(new PlayerEntry(buf.readUUID(), buf.readUtf(64), buf.readBoolean(), buf.readBoolean()));
            }
            return new SettingsPage(requestId, page, pages, total, query, List.copyOf(players));
        }
        void handle(Supplier<NetworkEvent.Context> ctx) {
            DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> FeedingClient.settings(this));
            ctx.get().setPacketHandled(true);
        }
    }

    // The recipient is deliberately absent: the authenticated packet sender owns the setting.
    public record PermissionChange(int requestId, UUID feeder, boolean allowed) {
        void encode(FriendlyByteBuf buf) { buf.writeVarInt(requestId); buf.writeUUID(feeder); buf.writeBoolean(allowed); }
        static PermissionChange decode(FriendlyByteBuf buf) {
            return new PermissionChange(buf.readVarInt(), buf.readUUID(), buf.readBoolean());
        }
        void handle(Supplier<NetworkEvent.Context> ctx) {
            ServerPlayer sender = ctx.get().getSender();
            if (sender != null) CHANNEL.send(PacketDistributor.PLAYER.with(() -> sender), FeedingSettingsServer.change(sender, this));
            ctx.get().setPacketHandled(true);
        }
    }

    public record PermissionAck(int requestId, UUID feeder, boolean allowed, boolean accepted) {
        void encode(FriendlyByteBuf buf) {
            buf.writeVarInt(requestId); buf.writeUUID(feeder); buf.writeBoolean(allowed); buf.writeBoolean(accepted);
        }
        static PermissionAck decode(FriendlyByteBuf buf) {
            return new PermissionAck(buf.readVarInt(), buf.readUUID(), buf.readBoolean(), buf.readBoolean());
        }
        void handle(Supplier<NetworkEvent.Context> ctx) {
            DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> FeedingClient.permission(this));
            ctx.get().setPacketHandled(true);
        }
    }

    private FeedNetwork() {}
}
