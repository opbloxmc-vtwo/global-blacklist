package dev.blacklist.spigot;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.bukkit.entity.Player;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

final class PlayerIdentityResolver {

    private PlayerIdentityResolver() {}

    static Map<String, Identity> snapshotOnlinePlayers(BlacklistPlugin plugin) {
        Map<String, Identity> online = new HashMap<>();
        for (Player player : plugin.getServer().getOnlinePlayers()) {
            String xuid = plugin.getFloodgateXuid(player.getUniqueId());
            String platform = xuid.isBlank() ? "JAVA" : "BEDROCK";
                online.put(player.getName().toLowerCase(Locale.ROOT),
                    new Identity(platform, player.getUniqueId().toString(), xuid, player.getName(), false));
        }
        return online;
    }

    static Identity resolve(String target, Map<String, Identity> online) throws IOException {
        String lowerTarget = target.toLowerCase(Locale.ROOT);
        if (online.containsKey(lowerTarget)) return online.get(lowerTarget);

        if (target.regionMatches(true, 0, "uuid:", 0, 5)) {
            UUID uuid = UUID.fromString(target.substring(5));
            return new Identity("JAVA", uuid.toString(), "", uuid.toString(), false);
        }
        if (target.regionMatches(true, 0, "offline:", 0, 8)) {
            String username = target.substring(8);
            if (!username.matches("[A-Za-z0-9_]{1,16}")) {
                throw new IllegalArgumentException("Invalid cracked/offline-mode player name.");
            }
            UUID uuid = UUID.nameUUIDFromBytes(("OfflinePlayer:" + username).getBytes(StandardCharsets.UTF_8));
            return new Identity("JAVA", uuid.toString(), "", username, true);
        }
        if (target.regionMatches(true, 0, "xuid:", 0, 5)) {
            String xuid = target.substring(5);
            if (!xuid.matches("[0-9]{1,20}")) throw new IllegalArgumentException("Invalid Bedrock XUID.");
            return new Identity("BEDROCK", "", xuid, "Bedrock XUID " + xuid, false);
        }

        if (!target.matches("[A-Za-z0-9_]{1,16}")) {
            throw new IllegalArgumentException("Use a Java name, uuid:<uuid>, or xuid:<digits>.");
        }

        UUID uuid = lookupMojangUuid(target);
        boolean cracked = uuid == null;
        if (uuid == null) {
            uuid = UUID.nameUUIDFromBytes(("OfflinePlayer:" + target).getBytes(StandardCharsets.UTF_8));
        }
        return new Identity("JAVA", uuid.toString(), "", target, cracked);
    }

    private static UUID lookupMojangUuid(String username) throws IOException {
        HttpURLConnection connection = null;
        try {
            URI uri = URI.create("https://api.mojang.com/users/profiles/minecraft/" + username);
            connection = (HttpURLConnection) uri.toURL().openConnection();
            connection.setConnectTimeout(4_000);
            connection.setReadTimeout(4_000);
            connection.setRequestProperty("User-Agent", "BlacklistSpigot/1.0");
            int responseCode = connection.getResponseCode();
            if (responseCode == HttpURLConnection.HTTP_NO_CONTENT) return null;
            if (responseCode != HttpURLConnection.HTTP_OK) {
                throw new IOException("Mojang profile lookup returned HTTP " + responseCode + ".");
            }
            try (var reader = new java.io.InputStreamReader(connection.getInputStream(), StandardCharsets.UTF_8)) {
                JsonObject profile = JsonParser.parseReader(reader).getAsJsonObject();
                String id = profile.get("id").getAsString();
                String dashed = id.replaceFirst(
                        "([0-9a-fA-F]{8})([0-9a-fA-F]{4})([0-9a-fA-F]{4})([0-9a-fA-F]{4})([0-9a-fA-F]{12})",
                        "$1-$2-$3-$4-$5");
                return UUID.fromString(dashed);
            }
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    record Identity(String platform, String uuid, String xuid, String username, boolean offlineUuid) {
        GithubBlacklistManager.Entry toEntry(String reason, String addedBy) {
            String entryPlatform = offlineUuid ? "CRACKED_JAVA" : platform;
            return new GithubBlacklistManager.Entry(entryPlatform, uuid, xuid, username,
                    reason, addedBy, java.time.Instant.now().toString());
        }

        String key() {
            return !xuid.isBlank() ? "bedrock:" + xuid : "java:" + uuid.toLowerCase(Locale.ROOT);
        }
    }
}