package dev.blacklist.spigot;

import org.bukkit.plugin.java.JavaPlugin;
import org.geysermc.floodgate.api.FloodgateApi;
import org.geysermc.floodgate.api.player.FloodgatePlayer;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

public final class BlacklistPlugin extends JavaPlugin {

    private GithubBlacklistManager github;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        github = new GithubBlacklistManager(
            getConfig().getString("github.owner", "opbloxmc-vtwo"),
            getConfig().getString("github.repository", "global-blacklist"),
            getConfig().getString("github.branch", "main"),
            getConfig().getString("github.file", "blacklist.json"),
            getConfig().getString("github.token", ""),
            getDataFolder().toPath().resolve("blacklist-cache.json"),
            getLogger());
        github.refresh();
        if (!github.isReady()) {
            getLogger().severe("GitHub blacklist unavailable and no saved cache exists; player logins will be denied.");
        }

        int refreshSeconds = Math.max(30, getConfig().getInt("github.refresh-seconds", 90));
        long refreshTicks = refreshSeconds * 20L;
        getServer().getScheduler().runTaskTimerAsynchronously(this, this::refreshAndEnforce,
            refreshTicks, refreshTicks);
        getServer().getPluginManager().registerEvents(new LoginListener(this), this);
        if (getCommand("blacklist") == null) {
            getLogger().severe("Command 'blacklist' is missing from plugin.yml; disabling.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        BlacklistCommand command = new BlacklistCommand(this);
        getCommand("blacklist").setExecutor(command);
        getCommand("blacklist").setTabCompleter(command);
        kickBlacklistedOnlinePlayers();

        getLogger().info("Enabled. GitHub-approved blacklist refreshes every " + refreshSeconds + "s.");
    }

    public GithubBlacklistManager getGithub() { return github; }

    public String getFloodgateXuid(UUID uuid) {
        try {
            FloodgateApi api = FloodgateApi.getInstance();
            if (api == null || !api.isFloodgatePlayer(uuid)) return "";
            FloodgatePlayer player = api.getPlayer(uuid);
            return player == null ? "" : player.getXuid();
        } catch (RuntimeException ex) {
            return "";
        }
    }

    private void refreshAndEnforce() {
        github.refresh();
        getServer().getScheduler().runTask(this, this::kickBlacklistedOnlinePlayers);
    }

    private void kickBlacklistedOnlinePlayers() {
        for (org.bukkit.entity.Player player : getServer().getOnlinePlayers()) {
            String xuid = getFloodgateXuid(player.getUniqueId());
            if (github.isBlacklisted(player.getUniqueId().toString(), xuid)) {
                player.kickPlayer(kickMessage());
            }
        }
    }

    public String kickMessage() {
        return "§cYou have been blacklisted from this network.";
    }
}
