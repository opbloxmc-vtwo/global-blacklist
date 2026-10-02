package dev.blacklist.spigot;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Logger;

public final class GithubBlacklistManager {

    private static final String API_ROOT = "https://api.github.com/repos/";
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private final String repository;
    private final String branch;
    private final String filePath;
    private final String token;
    private final Logger log;
    private final Path cacheFile;
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();
    private volatile List<Entry> cachedEntries = List.of();
    private volatile boolean ready;

    public GithubBlacklistManager(String owner, String repository, String branch,
                                  String filePath, String token, Path cacheFile, Logger log) {
        this.repository = owner + "/" + repository;
        this.branch = branch;
        this.filePath = filePath;
        this.token = token;
        this.cacheFile = cacheFile;
        this.log = log;
    }

    public List<Entry> entries() {
        return cachedEntries;
    }

    public Entry find(String identityKey) {
        return cachedEntries.stream()
                .filter(entry -> entry.identityKey().equalsIgnoreCase(identityKey))
                .findFirst()
                .orElse(null);
    }

    public boolean isBlacklisted(String uuid, String xuid) {
        return cachedEntries.stream().anyMatch(entry ->
                (!uuid.isBlank() && uuid.equalsIgnoreCase(entry.uuid()))
                        || (!xuid.isBlank() && xuid.equals(entry.xuid())));
    }

    public boolean isReady() {
        return ready;
    }

    public void refresh() {
        try {
            List<Entry> refreshed = List.copyOf(readFile(branch).entries());
            cachedEntries = refreshed;
            ready = true;
            try {
                Files.createDirectories(cacheFile.getParent());
                Files.writeString(cacheFile, GSON.toJson(new BlacklistFile(new ArrayList<>(refreshed))));
            } catch (IOException ex) {
                log.warning("[Blacklist] Could not save local GitHub cache: " + ex.getMessage());
            }
        } catch (Exception ex) {
            log.warning("[Blacklist] GitHub refresh failed; keeping cached entries: " + ex.getMessage());
            if (!ready) loadLocalCache();
        }
    }

    private void loadLocalCache() {
        try {
            if (!Files.isRegularFile(cacheFile)) return;
            BlacklistFile file = GSON.fromJson(Files.readString(cacheFile), BlacklistFile.class);
            if (file == null || file.entries == null) return;
            cachedEntries = List.copyOf(file.entries);
            ready = true;
            log.info("[Blacklist] Loaded " + cachedEntries.size() + " entries from the saved GitHub cache.");
        } catch (Exception ex) {
            log.warning("[Blacklist] Saved GitHub cache is unreadable: " + ex.getMessage());
        }
    }

        public PullRequest submit(List<Entry> requestedEntries, String submittedBy)
            throws IOException, InterruptedException {
        if (token.isBlank()) {
            throw new IllegalStateException("Set github.token in config.yml to submit a review request.");
        }

        RemoteFile current = readFile(branch);
        Map<String, Entry> merged = new LinkedHashMap<>();
        for (Entry entry : current.entries()) merged.put(entry.identityKey(), entry);
        int originalSize = merged.size();
        for (Entry entry : requestedEntries) merged.putIfAbsent(entry.identityKey(), entry);
        if (merged.size() == originalSize) {
            throw new IllegalStateException("All requested players are already listed in GitHub.");
        }

        String suffix = submittedBy.toLowerCase(java.util.Locale.ROOT).replaceAll("[^a-z0-9-]", "-");
        if (suffix.isBlank()) suffix = "staff";
        String headBranch = "blacklist/" + suffix + "-" + UUID.randomUUID().toString().substring(0, 8);
        JsonObject baseRef = request("GET", "git/ref/heads/" + encodePath(branch), null);
        String baseSha = baseRef.getAsJsonObject("object").get("sha").getAsString();

        JsonObject createRef = new JsonObject();
        createRef.addProperty("ref", "refs/heads/" + headBranch);
        createRef.addProperty("sha", baseSha);
        request("POST", "git/refs", createRef);

        JsonObject contentBody = new JsonObject();
        contentBody.addProperty("message", "Propose blacklist updates");
        contentBody.addProperty("content", Base64.getEncoder().encodeToString(
                GSON.toJson(new BlacklistFile(new ArrayList<>(merged.values())))
                        .getBytes(StandardCharsets.UTF_8)));
        contentBody.addProperty("sha", current.sha());
        contentBody.addProperty("branch", headBranch);
        request("PUT", "contents/" + encodePath(filePath), contentBody);

        JsonObject pullBody = new JsonObject();
        pullBody.addProperty("title", "Blacklist update submitted by " + submittedBy);
        pullBody.addProperty("head", headBranch);
        pullBody.addProperty("base", branch);
        pullBody.addProperty("body", "Review this proposed network blacklist update before merging. "
                + "Entries become active only after this pull request is merged.");
        JsonObject pull = request("POST", "pulls", pullBody);
        return new PullRequest(pull.get("html_url").getAsString(), pull.get("number").getAsInt());
    }

    private RemoteFile readFile(String ref) throws IOException, InterruptedException {
        JsonObject response = request("GET", "contents/" + encodePath(filePath)
            + "?ref=" + encodeQuery(ref), null);
        String encoded = response.get("content").getAsString().replaceAll("\\s", "");
        String content = new String(Base64.getMimeDecoder().decode(encoded), StandardCharsets.UTF_8);
        BlacklistFile file = GSON.fromJson(content, BlacklistFile.class);
        if (file == null || file.entries == null) file = new BlacklistFile(new ArrayList<>());
        return new RemoteFile(response.get("sha").getAsString(), file);
    }

    private JsonObject request(String method, String path, JsonObject body)
            throws IOException, InterruptedException {
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(API_ROOT + repository + "/" + path))
                .timeout(Duration.ofSeconds(20))
                .header("Accept", "application/vnd.github+json")
                .header("X-GitHub-Api-Version", "2022-11-28")
                .header("User-Agent", "BlacklistSpigot");
        if (!token.isBlank()) builder.header("Authorization", "Bearer " + token);
        if (body == null) {
            builder.method(method, HttpRequest.BodyPublishers.noBody());
        } else {
            builder.header("Content-Type", "application/json")
                    .method(method, HttpRequest.BodyPublishers.ofString(GSON.toJson(body)));
        }

        HttpResponse<String> response = http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            String details = response.body();
            if (details.length() > 400) details = details.substring(0, 400);
            throw new IOException("GitHub API returned HTTP " + response.statusCode() + ": " + details);
        }
        return JsonParser.parseString(response.body()).getAsJsonObject();
    }

    private static String encodePath(String value) {
        return Arrays.stream(value.split("/", -1))
                .map(part -> URLEncoder.encode(part, StandardCharsets.UTF_8).replace("+", "%20"))
                .collect(java.util.stream.Collectors.joining("/"));
    }

    private static String encodeQuery(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    public record Entry(String platform, String uuid, String xuid, String username,
                        String reason, String addedBy, String addedAt) {
        public String identityKey() {
            if (xuid != null && !xuid.isBlank()) return "bedrock:" + xuid;
            return "java:" + (uuid == null ? "" : uuid.toLowerCase(java.util.Locale.ROOT));
        }
    }

    public record PullRequest(String url, int number) {}

    private record RemoteFile(String sha, BlacklistFile file) {
        private List<Entry> entries() {
            return file.entries == null ? List.of() : file.entries;
        }
    }

    private static final class BlacklistFile {
        private List<Entry> entries;

        private BlacklistFile(List<Entry> entries) {
            this.entries = entries;
        }
    }
}