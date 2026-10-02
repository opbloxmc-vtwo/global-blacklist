package dev.blacklist.spigot;

import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;

public final class LoginListener implements Listener {

    private final BlacklistPlugin plugin;

    public LoginListener(BlacklistPlugin plugin) { 
        this.plugin = plugin; 
    }

    // AsyncPlayerPreLogin fires before the player is placed on the server - ideal for banning.
    @EventHandler(priority = EventPriority.LOWEST)
    public void onPreLogin(AsyncPlayerPreLoginEvent event) {
        if (!plugin.getGithub().isReady()) {
            event.disallow(AsyncPlayerPreLoginEvent.Result.KICK_OTHER,
                    "Blacklist data is unavailable. Please try again shortly.");
            return;
        }

        String uuid = event.getUniqueId().toString();
        String xuid = plugin.getFloodgateXuid(event.getUniqueId());
        if (plugin.getGithub().isBlacklisted(uuid, xuid)) {
            event.disallow(AsyncPlayerPreLoginEvent.Result.KICK_BANNED, plugin.kickMessage());
        }
    }
}
