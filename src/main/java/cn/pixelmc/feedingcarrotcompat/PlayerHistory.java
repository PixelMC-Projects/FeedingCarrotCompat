package cn.pixelmc.feedingcarrotcompat;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.mojang.logging.LogUtils;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.GZIPInputStream;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraftforge.common.util.FakePlayer;

final class PlayerHistory {
    private static final Pattern LOGIN = Pattern.compile("PlayerList/\\]: ([A-Za-z0-9_]{1,16})\\[([^\\]]+)\\] logged in with entity id");
    private static final Pattern PROFILE = Pattern.compile("UUID of player ([A-Za-z0-9_]{1,16}) is ([a-fA-F0-9-]{36})");
    private static final Set<String> BOT_CLASSES = Set.of(
            "dev.dubhe.curtain.features.player.patches.EntityPlayerMPFake",
            "carpet.patches.EntityPlayerMPFake");

    static boolean isFake(ServerPlayer player) {
        if (player instanceof FakePlayer) return true;
        for (Class<?> type = player.getClass(); type != null; type = type.getSuperclass()) {
            if (BOT_CLASSES.contains(type.getName())) return true;
        }
        return false;
    }

    static void observe(ServerPlayer player) {
        FeedingPreferences data = FeedingPreferences.get(player.server);
        if (isFake(player)) data.rememberFake(player.getUUID());
        else data.remember(player.getUUID(), player.getGameProfile().getName(), true);
    }

    static void bootstrap(MinecraftServer server) {
        FeedingPreferences data = FeedingPreferences.get(server);
        if (!data.historyImported()) importHistory(server, data);
        server.getPlayerList().getPlayers().forEach(PlayerHistory::observe);
    }

    private static void importHistory(MinecraftServer server, FeedingPreferences data) {
        Map<String, UUID> ids = new HashMap<>();
        Map<UUID, String> names = new HashMap<>();
        Set<String> humanNames = new HashSet<>(), botNames = new HashSet<>();
        Path playerData = server.getWorldPath(LevelResource.PLAYER_DATA_DIR);
        Path logs = server.getFile("logs").toPath();
        if (Files.isDirectory(logs)) {
            try (var files = Files.list(logs)) {
                for (Path file : files.filter(p -> p.getFileName().toString().matches(".*\\.log(\\.gz)?"))
                        .sorted(Comparator.comparing(p -> p.getFileName().toString())).toList()) {
                    readLog(file, ids, names, humanNames, botNames);
                }
            } catch (IOException error) {
                LogUtils.getLogger().warn("[Feeding] Could not read historical login logs", error);
            }
        }
        // Cache entries alone can contain bots or lookups that never joined.
        // Require a saved player file and use retained connection logs to classify bots.
        Path cache = server.getFile("usercache.json").toPath();
        if (Files.isRegularFile(cache)) {
            try (var reader = Files.newBufferedReader(cache)) {
                for (JsonElement entry : JsonParser.parseReader(reader).getAsJsonArray()) {
                    var profile = entry.getAsJsonObject();
                    UUID id = UUID.fromString(profile.get("uuid").getAsString());
                    String name = profile.get("name").getAsString();
                    ids.put(name.toLowerCase(Locale.ROOT), id);
                    names.put(id, name);
                }
            } catch (IOException | RuntimeException error) {
                LogUtils.getLogger().warn("[Feeding] Could not import usercache", error);
            }
        }
        Path residents = server.getWorldPath(LevelResource.ROOT).resolve("fake_player.gca.json");
        if (Files.isRegularFile(residents)) {
            try (var reader = Files.newBufferedReader(residents)) {
                JsonParser.parseReader(reader).getAsJsonObject().keySet()
                        .forEach(name -> botNames.add(name.toLowerCase(Locale.ROOT)));
            } catch (IOException | RuntimeException error) {
                LogUtils.getLogger().warn("[Feeding] Could not read Curtain resident names", error);
            }
        }
        for (String name : botNames) {
            UUID id = ids.get(name);
            if (id != null && !humanNames.contains(name)) data.rememberFake(id);
        }
        if (Files.isDirectory(playerData)) {
            try (var files = Files.list(playerData)) {
                for (Path file : files.filter(p -> p.getFileName().toString().endsWith(".dat")).toList()) {
                    try {
                        String filename = file.getFileName().toString();
                        UUID id = UUID.fromString(filename.substring(0, filename.length() - 4));
                        String name = names.getOrDefault(id, id.toString());
                        data.remember(id, name, humanNames.contains(name.toLowerCase(Locale.ROOT)));
                    } catch (IllegalArgumentException ignored) { /* Not a UUID player file. */ }
                }
            } catch (IOException error) {
                LogUtils.getLogger().warn("[Feeding] Could not enumerate saved players", error);
            }
        }
        data.finishImport();
        LogUtils.getLogger().info("[Feeding] Imported {} historical real/unknown player profiles; excluded {} bot names",
                data.players().size(), botNames.stream().filter(name -> !humanNames.contains(name)).count());
    }

    private static void readLog(Path file, Map<String, UUID> ids, Map<UUID, String> names,
            Set<String> humans, Set<String> bots) {
        try (InputStream input = Files.newInputStream(file);
             InputStream decoded = file.toString().endsWith(".gz") ? new GZIPInputStream(input) : input;
             BufferedReader reader = new BufferedReader(new InputStreamReader(decoded, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                Matcher profile = PROFILE.matcher(line);
                if (profile.find()) {
                    String name = profile.group(1);
                    UUID id = UUID.fromString(profile.group(2));
                    ids.put(name.toLowerCase(Locale.ROOT), id);
                    names.put(id, name);
                }
                Matcher login = LOGIN.matcher(line);
                if (login.find()) {
                    String name = login.group(1).toLowerCase(Locale.ROOT);
                    String address = login.group(2);
                    if (address.equals("local") || address.equals("null") || address.contains("embedded")) bots.add(name);
                    else humans.add(name);
                }
            }
        } catch (IOException | RuntimeException error) {
            LogUtils.getLogger().warn("[Feeding] Could not import login history from {}", file.getFileName(), error);
        }
    }

    private PlayerHistory() {}
}
