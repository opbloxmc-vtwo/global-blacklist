package dev.blacklist.spigot;

import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class BlacklistCommand implements CommandExecutor, TabCompleter {

    private static final int PAGE_SIZE = 10;
    private static final int MAX_BULK_TARGETS = 100;
    private static final long PENDING_LIFETIME_MILLIS = 120_000;
    private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
            .withZone(ZoneId.systemDefault());

    private final BlacklistPlugin plugin;
    private final Map<String, PendingSubmission> pending = new ConcurrentHashMap<>();

    public BlacklistCommand(BlacklistPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (args.length == 0) {
            sendUsage(sender, label);
            return true;
        }

        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "add" -> add(sender, label, args);
            case "confirm" -> confirm(sender, label, args);
            case "check" -> check(sender, label, args);
            case "list" -> list(sender, label, args);
            default -> sendUsage(sender, label);
        }
        return true;
    }

    private void add(CommandSender sender, String label, String[] args) {
        if (!sender.hasPermission("blacklist.add")) {
            sender.sendMessage("§cNo permission.");
            return;
        }
        boolean dryRun = args.length > 1 && args[1].equalsIgnoreCase("--dry-run");
        int targetIndex = dryRun ? 2 : 1;
        if (args.length <= targetIndex) {
            sender.sendMessage("§eUsage: §f/" + label + " add [--dry-run] <player[,player2,...]> [reason]");
            return;
        }

        LinkedHashSet<String> targets = new LinkedHashSet<>();
        for (String target : args[targetIndex].split(",", -1)) {
            String trimmed = target.trim();
            if (trimmed.isEmpty()) {
                sender.sendMessage("§cTarget list contains an empty name.");
                return;
            }
            targets.add(trimmed);
        }
        if (targets.size() > MAX_BULK_TARGETS) {
            sender.sendMessage("§cYou can blacklist at most " + MAX_BULK_TARGETS + " players at once.");
            return;
        }

        int reasonIndex = targetIndex + 1;
        String reason = args.length > reasonIndex
                ? String.join(" ", Arrays.copyOfRange(args, reasonIndex, args.length))
                : "No reason provided";
        if (reason.length() > 255) {
            sender.sendMessage("§cReason must be 255 characters or fewer.");
            return;
        }

        Map<String, PlayerIdentityResolver.Identity> online = PlayerIdentityResolver.snapshotOnlinePlayers(plugin);
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin,
                () -> prepareSubmission(sender, targets, reason, dryRun, online));
    }

    private void prepareSubmission(CommandSender sender, LinkedHashSet<String> targets, String reason,
                                  boolean dryRun, Map<String, PlayerIdentityResolver.Identity> online) {
        List<PlayerIdentityResolver.Identity> identities = new ArrayList<>();
        List<String> invalid = new ArrayList<>();
        Map<String, PlayerIdentityResolver.Identity> unique = new LinkedHashMap<>();
        for (String target : targets) {
            try {
                PlayerIdentityResolver.Identity identity = PlayerIdentityResolver.resolve(target, online);
                unique.putIfAbsent(identity.key(), identity);
            } catch (Exception ex) {
                invalid.add(target + " (" + ex.getMessage() + ")");
            }
        }
        identities.addAll(unique.values());

        List<GithubBlacklistManager.Entry> githubEntries = plugin.getGithub().entries();
        List<GithubBlacklistManager.Entry> additions = new ArrayList<>();
        int existingCount = 0;
        for (PlayerIdentityResolver.Identity identity : identities) {
            if (isAlreadyListed(identity, githubEntries)) {
                existingCount++;
            } else {
                additions.add(identity.toEntry(reason, sender.getName()));
            }
        }

        reply(sender, "§6Bulk preview: §f" + identities.size() + " valid, " + invalid.size()
                + " invalid, " + existingCount + " already listed, " + additions.size() + " new.");
        for (PlayerIdentityResolver.Identity identity : identities) {
            boolean existing = isAlreadyListed(identity, githubEntries);
            String resolution = !identity.xuid().isBlank() ? "Bedrock XUID"
                    : identity.offlineUuid() ? "cracked/offline UUID" : "Java UUID";
            reply(sender, (existing ? "§e= " : "§a+ ") + identity.username()
                    + " §7(" + resolution + (existing ? ", already active" : ", proposed") + ")");
        }
        for (String problem : invalid) reply(sender, "§cInvalid: " + problem);

        if (!invalid.isEmpty()) {
            reply(sender, "§cNothing submitted. Correct invalid targets and try again.");
            return;
        }
        if (additions.isEmpty()) {
            reply(sender, "§eNo changes to propose.");
            return;
        }

        if (dryRun) {
            String code = UUID.randomUUID().toString().substring(0, 8);
            pending.put(pendingKey(sender, code), new PendingSubmission(
                    List.copyOf(additions), System.currentTimeMillis() + PENDING_LIFETIME_MILLIS));
            reply(sender, "§ePreview only. Confirm within 2 minutes with §f/blacklist confirm " + code);
        } else {
            submitPullRequest(sender, additions);
        }
    }

    private void confirm(CommandSender sender, String label, String[] args) {
        if (!sender.hasPermission("blacklist.add")) {
            sender.sendMessage("§cNo permission.");
            return;
        }
        if (args.length != 2) {
            sender.sendMessage("§eUsage: §f/" + label + " confirm <preview-code>");
            return;
        }
        PendingSubmission request = pending.remove(pendingKey(sender, args[1]));
        if (request == null || request.expiresAt() < System.currentTimeMillis()) {
            sender.sendMessage("§cPreview not found or expired. Run the dry-run again.");
            return;
        }
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                GithubBlacklistManager.PullRequest pull = plugin.getGithub()
                    .submit(request.entries(), sender.getName());
                reply(sender, "§aReview request opened: §f#" + pull.number() + " " + pull.url());
                reply(sender, "§eEntries become active only after the pull request is reviewed and merged.");
            } catch (Exception ex) {
                plugin.getLogger().warning("[Blacklist] GitHub submission failed: " + ex.getMessage());
                reply(sender, "§cCould not create the GitHub review request: " + ex.getMessage());
            }
        });
    }

    private void submitPullRequest(CommandSender sender, List<GithubBlacklistManager.Entry> additions) {
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                GithubBlacklistManager.PullRequest pull = plugin.getGithub()
                        .submit(additions, sender.getName());
                reply(sender, "§aReview request opened: §f#" + pull.number() + " " + pull.url());
                reply(sender, "§eEntries become active only after the pull request is reviewed and merged.");
            } catch (Exception ex) {
                plugin.getLogger().warning("[Blacklist] GitHub submission failed: " + ex.getMessage());
                reply(sender, "§cCould not create the GitHub review request: " + ex.getMessage());
            }
        });
    }

    private void check(CommandSender sender, String label, String[] args) {
        if (!sender.hasPermission("blacklist.view")) {
            sender.sendMessage("§cNo permission.");
            return;
        }
        if (args.length != 2) {
            sender.sendMessage("§eUsage: §f/" + label + " check <player|uuid:<uuid>|offline:<name>|xuid:<xuid>>");
            return;
        }
        Map<String, PlayerIdentityResolver.Identity> online = PlayerIdentityResolver.snapshotOnlinePlayers(plugin);
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                PlayerIdentityResolver.Identity identity = PlayerIdentityResolver.resolve(args[1], online);
                GithubBlacklistManager.Entry githubEntry = findGithubEntry(identity);
                if (githubEntry != null) {
                    sendEntry(sender, githubEntry, "GitHub approved");
                    return;
                }
                reply(sender, "§a" + identity.username() + " is not blacklisted.");
            } catch (Exception ex) {
                reply(sender, "§cCould not check that identity: " + ex.getMessage());
            }
        });
    }

    private void list(CommandSender sender, String label, String[] args) {
        if (!sender.hasPermission("blacklist.view")) {
            sender.sendMessage("§cNo permission.");
            return;
        }
        if (args.length > 2) {
            sender.sendMessage("§eUsage: §f/" + label + " list [page]");
            return;
        }
        int page = 1;
        if (args.length == 2) {
            try {
                page = Integer.parseInt(args[1]);
                if (page < 1) throw new NumberFormatException();
            } catch (NumberFormatException ex) {
                sender.sendMessage("§cPage must be a positive number.");
                return;
            }
        }
        int requestedPage = page;
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            List<GithubBlacklistManager.Entry> sorted = new ArrayList<>(plugin.getGithub().entries());
            sorted.sort(Comparator.comparing(GithubBlacklistManager.Entry::addedAt,
                    Comparator.nullsFirst(String::compareTo)).reversed());
            int total = sorted.size();
            int pages = Math.max(1, (total + PAGE_SIZE - 1) / PAGE_SIZE);
            if (requestedPage > pages) {
                reply(sender, "§cPage " + requestedPage + " does not exist. Total pages: " + pages + ".");
                return;
            }
            int start = Math.min((requestedPage - 1) * PAGE_SIZE, total);
            int end = Math.min(start + PAGE_SIZE, total);
            List<GithubBlacklistManager.Entry> pageEntries = sorted.subList(start, end);
            reply(sender, "§6Network blacklist §7(Page " + requestedPage + "/" + pages + ", " + total + " entries)");
            if (pageEntries.isEmpty()) {
                reply(sender, "§7No players are blacklisted.");
                return;
            }
            for (GithubBlacklistManager.Entry entry : pageEntries) sendEntry(sender, entry, entry.platform());
        });
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            List<String> subcommands = new ArrayList<>();
            if (sender.hasPermission("blacklist.add")) {
                subcommands.add("add");
                subcommands.add("confirm");
            }
            if (sender.hasPermission("blacklist.view")) {
                subcommands.add("check");
                subcommands.add("list");
            }
            return matching(subcommands, args[0]);
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("add")
                && sender.hasPermission("blacklist.add") && args[1].equalsIgnoreCase("--dry-run")) {
            return List.of();
        }
        if (args.length == 2 && (args[0].equalsIgnoreCase("add") || args[0].equalsIgnoreCase("check"))) {
            boolean permitted = args[0].equalsIgnoreCase("add")
                    ? sender.hasPermission("blacklist.add") : sender.hasPermission("blacklist.view");
            if (!permitted) return List.of();
            if (args[0].equalsIgnoreCase("add") && args[1].isEmpty()) {
                List<String> suggestions = new ArrayList<>(playerSuggestions(args[1]));
                suggestions.add("--dry-run");
                return suggestions;
            }
            return playerSuggestions(args[1]);
        }
        if (args.length == 3 && args[0].equalsIgnoreCase("add")
                && args[1].equalsIgnoreCase("--dry-run") && sender.hasPermission("blacklist.add")) {
            return playerSuggestions(args[2]);
        }
        return List.of();
    }

    private List<String> playerSuggestions(String current) {
        List<String> names = new ArrayList<>();
        String prefix = "";
        int comma = current.lastIndexOf(',');
        if (comma >= 0) {
            prefix = current.substring(0, comma + 1);
            current = current.substring(comma + 1);
        }
        for (Player player : plugin.getServer().getOnlinePlayers()) {
            if (player.getName().regionMatches(true, 0, current, 0, current.length())) {
                names.add(prefix + player.getName());
            }
        }
        for (String identityPrefix : List.of("uuid:", "offline:", "xuid:")) {
            if (identityPrefix.regionMatches(true, 0, current, 0, current.length())) {
                names.add(prefix + identityPrefix);
            }
        }
        return names;
    }

    private List<String> matching(List<String> options, String prefix) {
        return options.stream().filter(option -> option.regionMatches(true, 0, prefix, 0, prefix.length())).toList();
    }

    private boolean isAlreadyListed(PlayerIdentityResolver.Identity identity,
                                    List<GithubBlacklistManager.Entry> githubEntries) {
        String uuid = identity.uuid();
        String xuid = identity.xuid();
        return plugin.getGithub().isBlacklisted(uuid, xuid)
                || githubEntries.stream().anyMatch(entry ->
                        (!uuid.isBlank() && uuid.equalsIgnoreCase(entry.uuid()))
                                || (!xuid.isBlank() && xuid.equals(entry.xuid())));
    }

    private GithubBlacklistManager.Entry findGithubEntry(PlayerIdentityResolver.Identity identity) {
        return plugin.getGithub().entries().stream()
                .filter(entry -> (!identity.uuid().isBlank() && identity.uuid().equalsIgnoreCase(entry.uuid()))
                || (!identity.xuid().isBlank() && identity.xuid().equals(entry.xuid())))
                .findFirst().orElse(null);
    }

    private void sendEntry(CommandSender sender, GithubBlacklistManager.Entry entry, String source) {
        String date = entry.addedAt();
        try {
            date = DATE_FORMAT.format(Instant.parse(date));
        } catch (Exception ignored) {
        }
        reply(sender, "§e" + entry.username() + " §7[" + entry.platform() + "] - " + entry.reason()
                + " §8(by " + entry.addedBy() + ", " + date + "; " + source + ")");
    }

    private void reply(CommandSender sender, String message) {
        plugin.getServer().getScheduler().runTask(plugin, () -> sender.sendMessage(message));
    }

    private String pendingKey(CommandSender sender, String code) {
        String owner = sender instanceof Player player ? player.getUniqueId().toString() : "console";
        return owner + ":" + code.toLowerCase(Locale.ROOT);
    }

    private void sendUsage(CommandSender sender, String label) {
        if (sender.hasPermission("blacklist.add")) {
            sender.sendMessage("§eUsage: §f/" + label + " add [--dry-run] <player[,player2,...]> [reason]");
            sender.sendMessage("§eUsage: §f/" + label + " confirm <preview-code>");
        }
        if (sender.hasPermission("blacklist.view")) {
            sender.sendMessage("§eUsage: §f/" + label + " check <player|uuid:<uuid>|offline:<name>|xuid:<xuid>>");
            sender.sendMessage("§eUsage: §f/" + label + " list [page]");
        }
    }

    private record PendingSubmission(List<GithubBlacklistManager.Entry> entries, long expiresAt) {}
}