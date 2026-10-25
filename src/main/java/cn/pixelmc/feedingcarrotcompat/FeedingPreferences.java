package cn.pixelmc.feedingcarrotcompat;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;

/** UUID-based recipient preferences and a permanent, world-local player directory. */
public final class FeedingPreferences extends SavedData {
    static final String DATA_NAME = "feedingcarrotcompat_permissions";
    private final Map<UUID, String> players = new HashMap<>();
    private final Set<UUID> confirmedHumans = new HashSet<>();
    private final Set<UUID> fakeOnly = new HashSet<>();
    private final Map<UUID, Set<UUID>> denied = new HashMap<>();
    private boolean historyImported;

    static FeedingPreferences get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(
                FeedingPreferences::load, FeedingPreferences::new, DATA_NAME);
    }

    boolean historyImported() { return historyImported; }
    void finishImport() { historyImported = true; setDirty(); }
    Map<UUID, String> players() { return Map.copyOf(players); }
    boolean knows(UUID player) { return players.containsKey(player); }

    void remember(UUID id, String name, boolean confirmedHuman) {
        if (!confirmedHuman && fakeOnly.contains(id)) return;
        if (!name.equals(players.put(id, name))) setDirty();
        if (confirmedHuman) {
            if (confirmedHumans.add(id) | fakeOnly.remove(id)) setDirty();
        }
    }

    void rememberFake(UUID id) {
        // A Curtain shadow can share the UUID of an actual historical player.
        // Keep that real account, but never introduce a separate bot entry.
        if (confirmedHumans.contains(id)) return;
        if (players.remove(id) != null | fakeOnly.add(id)) setDirty();
    }

    boolean allows(UUID recipient, UUID feeder) {
        return !denied.getOrDefault(recipient, Set.of()).contains(feeder);
    }

    boolean setAllowed(UUID recipient, UUID feeder, boolean allowed) {
        if (recipient.equals(feeder) || !knows(feeder)) return false;
        if (allowed) {
            Set<UUID> entries = denied.get(recipient);
            if (entries != null && entries.remove(feeder)) {
                if (entries.isEmpty()) denied.remove(recipient);
                setDirty();
            }
        } else if (denied.computeIfAbsent(recipient, ignored -> new HashSet<>()).add(feeder)) {
            setDirty();
        }
        return true;
    }

    static FeedingPreferences load(CompoundTag root) {
        FeedingPreferences data = new FeedingPreferences();
        data.historyImported = root.getBoolean("HistoryImported");
        for (Tag value : root.getList("Players", Tag.TAG_COMPOUND)) {
            CompoundTag player = (CompoundTag) value;
            if (!player.hasUUID("UUID")) continue;
            UUID id = player.getUUID("UUID");
            String name = player.getString("Name");
            if (name.isBlank()) continue;
            data.players.put(id, name);
            if (player.getBoolean("ConfirmedHuman")) data.confirmedHumans.add(id);
        }
        for (Tag value : root.getList("FakeOnly", Tag.TAG_COMPOUND)) {
            CompoundTag entry = (CompoundTag) value;
            if (entry.hasUUID("UUID")) data.fakeOnly.add(entry.getUUID("UUID"));
        }
        for (Tag value : root.getList("Permissions", Tag.TAG_COMPOUND)) {
            CompoundTag permission = (CompoundTag) value;
            if (!permission.hasUUID("Recipient")) continue;
            Set<UUID> blocked = new HashSet<>();
            for (Tag feederValue : permission.getList("Denied", Tag.TAG_COMPOUND)) {
                CompoundTag feeder = (CompoundTag) feederValue;
                if (feeder.hasUUID("UUID")) blocked.add(feeder.getUUID("UUID"));
            }
            if (!blocked.isEmpty()) data.denied.put(permission.getUUID("Recipient"), blocked);
        }
        return data;
    }

    @Override
    public CompoundTag save(CompoundTag root) {
        root.putInt("Version", 1);
        root.putBoolean("HistoryImported", historyImported);
        ListTag profiles = new ListTag();
        players.forEach((id, name) -> {
            CompoundTag player = uuidTag(id);
            player.putString("Name", name);
            player.putBoolean("ConfirmedHuman", confirmedHumans.contains(id));
            profiles.add(player);
        });
        root.put("Players", profiles);
        ListTag bots = new ListTag();
        fakeOnly.forEach(id -> bots.add(uuidTag(id)));
        root.put("FakeOnly", bots);
        ListTag permissions = new ListTag();
        denied.forEach((recipient, blocked) -> {
            CompoundTag permission = new CompoundTag();
            permission.putUUID("Recipient", recipient);
            ListTag entries = new ListTag();
            blocked.forEach(id -> entries.add(uuidTag(id)));
            permission.put("Denied", entries);
            permissions.add(permission);
        });
        root.put("Permissions", permissions);
        return root;
    }

    private static CompoundTag uuidTag(UUID id) {
        CompoundTag tag = new CompoundTag();
        tag.putUUID("UUID", id);
        return tag;
    }
}
