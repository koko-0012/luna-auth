package io.lunabot.bridge;

import com.google.gson.*;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;

/** Outbound-only connector. No passwords, password hashes or IPs leave the Minecraft server. */
public final class LunaBridge implements AutoCloseable {
  public interface Host {
    String platform();

    String version();

    JsonArray players();

    void execute(Runnable work);

    void tell(UUID player, String message);

    boolean authenticated(UUID player);

    void action(UUID player, String action);

    void password(UUID player, String password);

    void policy(JsonObject settings);

    void log(String message);
  }

  private final Host host;
  private final Path file;
  private final Gson gson = new GsonBuilder().setPrettyPrinting().create();
  private final ScheduledExecutorService worker =
      Executors.newSingleThreadScheduledExecutor(
          r -> {
            Thread t = new Thread(r, "luna-bridge");
            t.setDaemon(true);
            return t;
          });
  private final HttpClient http =
      HttpClient.newBuilder()
          .connectTimeout(Duration.ofSeconds(8))
          .followRedirects(HttpClient.Redirect.NEVER)
          .build();
  private JsonObject local;
  private volatile JsonObject settings = new JsonObject();
  private volatile Map<UUID, String> links = Map.of();
  private final Set<UUID> sessionVerified = ConcurrentHashMap.newKeySet();
  private final Map<UUID, JsonObject> resets = new ConcurrentHashMap<>();
  private final Set<UUID> requests = ConcurrentHashMap.newKeySet();
  private final Set<String> runningJobs = ConcurrentHashMap.newKeySet();
  private final Map<UUID, Long> cooldowns = new ConcurrentHashMap<>();
  private final Map<UUID, Long> confirmationCooldowns = new ConcurrentHashMap<>();
  private volatile boolean connected;
  private long lastWarning;

  public LunaBridge(Path file, Host host) throws Exception {
    this.file = file;
    this.host = host;
    if (Files.exists(file))
      local = JsonParser.parseString(Files.readString(file)).getAsJsonObject();
    else {
      local = new JsonObject();
      local.addProperty("panelUrl", "https://luna.141.227.152.160.sslip.io");
      local.addProperty("token", "");
      local.add("players", new JsonObject());
      local.add("receipts", new JsonObject());
      local.add("completed", new JsonObject());
      save();
    }
    if (!local.has("players")) local.add("players", new JsonObject());
    if (!local.has("receipts")) local.add("receipts", new JsonObject());
    if (!local.has("completed")) local.add("completed", new JsonObject());
    if (!local.has("resets")) local.add("resets", new JsonObject());
    if (!local.has("inProgress")) local.add("inProgress", new JsonObject());
    for (var entry : new ArrayList<>(local.getAsJsonObject("inProgress").entrySet()))
      receipt(
          entry.getValue().getAsJsonObject(),
          false,
          "Server stopped during this action. Check the account locally before retrying.");
    local.add("inProgress", new JsonObject());
    for (var entry : local.getAsJsonObject("resets").entrySet()) {
      JsonObject reset = entry.getValue().getAsJsonObject();
      if (reset.get("expires").getAsLong() > System.currentTimeMillis())
        resets.put(UUID.fromString(entry.getKey()), reset);
    }
    if (local.has("settings")) settings = local.getAsJsonObject("settings").deepCopy();
    if (local.has("links")) readLinks(local.getAsJsonArray("links"));
    worker.scheduleWithFixedDelay(this::poll, 2, 15, TimeUnit.SECONDS);
  }

  private synchronized void save() throws Exception {
    Files.createDirectories(file.toAbsolutePath().getParent());
    Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
    Files.writeString(tmp, gson.toJson(local), StandardCharsets.UTF_8);
    if (Files.getFileStore(tmp).supportsFileAttributeView("posix"))
      Files.setPosixFilePermissions(
          tmp, java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"));
    try {
      Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    } catch (AtomicMoveNotSupportedException e) {
      Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
    }
  }

  private String token() {
    synchronized (this) {
      return local.get("token").getAsString();
    }
  }

  private JsonObject post(String path, JsonObject body, boolean authenticate) throws Exception {
    String base;
    synchronized (this) {
      base = local.get("panelUrl").getAsString();
    }
    URI uri = URI.create(base);
    if (!"https".equals(uri.getScheme())
        || uri.getUserInfo() != null
        || uri.getQuery() != null
        || uri.getFragment() != null
        || (uri.getPath() != null && !uri.getPath().isEmpty() && !uri.getPath().equals("/")))
      throw new IllegalArgumentException("panelUrl must be an HTTPS origin.");
    HttpRequest.Builder request =
        HttpRequest.newBuilder(URI.create(base.replaceAll("/$", "") + path))
            .timeout(Duration.ofSeconds(12))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(gson.toJson(body)));
    if (authenticate) request.header("Authorization", "Bearer " + token());
    HttpResponse<java.io.InputStream> response =
        http.send(request.build(), HttpResponse.BodyHandlers.ofInputStream());
    byte[] bytes;
    try (var stream = response.body()) {
      bytes = stream.readNBytes(524289);
    }
    if (bytes.length > 524288)
      throw new IllegalStateException("Luna response exceeded the connector limit.");
    JsonObject result =
        JsonParser.parseString(new String(bytes, StandardCharsets.UTF_8)).getAsJsonObject();
    if (response.statusCode() / 100 != 2)
      throw new IllegalStateException(
          result.has("message")
              ? result.get("message").getAsString()
              : "Luna request failed (HTTP " + response.statusCode() + ").");
    return result;
  }

  public void pair(String key) {
    if (!key.matches("[A-Za-z0-9_-]{43}")) {
      host.log("Invalid pairing key format.");
      return;
    }
    worker.execute(
        () -> {
          try {
            JsonObject b = new JsonObject();
            b.addProperty("key", key);
            b.addProperty("platform", host.platform());
            b.addProperty("version", host.version());
            JsonObject reply = post("/api/minecraft/bridge/pair", b, false);
            synchronized (this) {
              local.addProperty("token", reply.get("token").getAsString());
              local.addProperty("guildId", reply.get("guildId").getAsString());
              local.addProperty("serverId", reply.get("serverId").getAsString());
              save();
            }
            host.log("Luna paired. The panel will show this server on its next heartbeat.");
            poll();
          } catch (Exception e) {
            host.log("Luna pairing failed. Check the panel key, HTTPS address and connection.");
          }
        });
  }

  private void poll() {
    if (token().isEmpty()) return;
    try {
      CompletableFuture<JsonArray> snapshot = new CompletableFuture<>();
      host.execute(
          () -> {
            try {
              snapshot.complete(host.players());
            } catch (Throwable e) {
              snapshot.completeExceptionally(e);
            }
          });
      JsonArray now = snapshot.get(8, TimeUnit.SECONDS);
      JsonArray players = new JsonArray(), receipts = new JsonArray();
      synchronized (this) {
        JsonObject known = local.getAsJsonObject("players");
        for (var entry : known.entrySet())
          entry.getValue().getAsJsonObject().addProperty("online", false);
        for (JsonElement p : now) {
          JsonObject o = p.getAsJsonObject();
          String id = o.get("uuid").getAsString();
          if (known.size() < 5000 || known.has(id)) known.add(id, o);
        }
        local
            .getAsJsonObject("completed")
            .entrySet()
            .removeIf(e -> e.getValue().getAsLong() < System.currentTimeMillis() - 30L * 86400000);
        local
            .getAsJsonObject("resets")
            .entrySet()
            .removeIf(
                e ->
                    e.getValue().getAsJsonObject().get("expires").getAsLong()
                        < System.currentTimeMillis());
        // Rotate batches so large rosters still synchronise without large HTTP bodies.
        int offset = local.has("offset") ? local.get("offset").getAsInt() : 0,
            index = 0,
            total = known.size();
        for (var entry : known.entrySet()) {
          if (index++ >= offset && players.size() < 100) players.add(entry.getValue());
        }
        local.addProperty("offset", offset + 100 >= total ? 0 : offset + 100);
        for (var e : local.getAsJsonObject("receipts").entrySet())
          if (receipts.size() < 30) receipts.add(e.getValue());
      }
      JsonObject b = new JsonObject();
      b.add("players", players);
      b.add("receipts", receipts);
      JsonObject reply = post("/api/minecraft/bridge/sync", b, true);
      settings = reply.getAsJsonObject("settings");
      readLinks(reply.getAsJsonArray("links"));
      synchronized (this) {
        for (JsonElement e : receipts)
          local.getAsJsonObject("receipts").remove(e.getAsJsonObject().get("id").getAsString());
        local.add("settings", settings);
        local.add("links", reply.get("links"));
        save();
      }
      connected = true;
      host.execute(() -> host.policy(settings));
      for (JsonElement e : reply.getAsJsonArray("jobs")) runJob(e.getAsJsonObject());
    } catch (Exception e) {
      connected = false;
      if (System.currentTimeMillis() - lastWarning > 60000) {
        lastWarning = System.currentTimeMillis();
        host.log(
            "Luna connector is unavailable. Local authentication remains active; queued actions"
                + " will retry.");
      }
    }
  }

  private void readLinks(JsonArray data) {
    Map<UUID, String> next = new HashMap<>();
    for (JsonElement e : data) {
      JsonObject o = e.getAsJsonObject();
      next.put(UUID.fromString(o.get("uuid").getAsString()), o.get("discordId").getAsString());
    }
    links = Map.copyOf(next);
  }

  private void receipt(JsonObject job, boolean ok, String message) {
    synchronized (this) {
      try {
        JsonObject r = new JsonObject();
        r.addProperty("id", job.get("id").getAsString());
        r.addProperty("ok", ok);
        r.addProperty("message", message);
        local.getAsJsonObject("receipts").add(r.get("id").getAsString(), r);
        local
            .getAsJsonObject("completed")
            .addProperty(r.get("id").getAsString(), System.currentTimeMillis());
        save();
      } catch (Exception e) {
        host.log("Cannot persist Luna action receipt. Check disk permissions.");
      }
    }
  }

  private void runJob(JsonObject job) {
    String id = job.get("id").getAsString();
    synchronized (this) {
      if (local.getAsJsonObject("completed").has(id)) return;
    }
    if (job.get("expires").getAsLong() < System.currentTimeMillis()) return;
    UUID player = UUID.fromString(job.get("uuid").getAsString());
    String action = job.get("action").getAsString();
    if (action.equals("request-reset")) {
      resets.put(player, job);
      synchronized (this) {
        local.getAsJsonObject("resets").add(player.toString(), job);
      }
      receipt(job, true, "Private reset challenge installed until its expiry.");
      return;
    }
    if (!runningJobs.add(id)) return;
    synchronized (this) {
      try {
        local.getAsJsonObject("inProgress").add(id, job);
        save();
      } catch (Exception e) {
        runningJobs.remove(id);
        host.log("Cannot save Luna action intent; action skipped.");
        return;
      }
    }
    host.execute(
        () -> {
          try {
            host.action(player, action);
            if (action.equals("unlink")) {
              Map<UUID, String> next = new HashMap<>(links);
              next.remove(player);
              links = Map.copyOf(next);
              sessionVerified.remove(player);
            }
            receipt(job, true, "Action submitted to the local authentication provider.");
          } catch (Exception e) {
            receipt(job, false, "Account action failed; check the Minecraft server console.");
          } finally {
            synchronized (this) {
              local.getAsJsonObject("inProgress").remove(id);
              try {
                save();
              } catch (Exception ignored) {
              }
            }
            runningJobs.remove(id);
          }
        });
  }

  public boolean linked(UUID player) {
    return links.containsKey(player);
  }

  public boolean requiresLink() {
    return settings.has("requireDiscordLink") && settings.get("requireDiscordLink").getAsBoolean();
  }

  public boolean requiresLink(boolean official) {
    return requiresLink()
        && (!official
            || (settings.has("requireOfficialDiscordLink")
                && settings.get("requireOfficialDiscordLink").getAsBoolean()));
  }

  public boolean offlineLogin() {
    return !settings.has("requireOfflineLogin")
        || settings.get("requireOfflineLogin").getAsBoolean();
  }

  public boolean verifiedSession(UUID player) {
    return sessionVerified.contains(player);
  }

  public void leave(UUID player) {
    sessionVerified.remove(player);
    requests.remove(player);
    cooldowns.remove(player);
    confirmationCooldowns.remove(player);
  }

  public boolean connected() {
    return connected;
  }

  boolean beginLinkRequest(UUID player, boolean confirm, long now) {
    Map<UUID, Long> limits = confirm ? confirmationCooldowns : cooldowns;
    if (limits.getOrDefault(player, 0L) > now || !requests.add(player)) return false;
    limits.put(player, now + (confirm ? 2000 : 60000));
    return true;
  }

  void finishLinkRequest(UUID player) {
    requests.remove(player);
  }

  public void link(UUID player, String value, boolean confirm) {
    boolean passwordless = !offlineLogin();
    if (!confirm && !host.authenticated(player) && !passwordless) {
      host.tell(player, "Log in or register before linking Discord.");
      return;
    }
    long now = System.currentTimeMillis();
    if (!beginLinkRequest(player, confirm, now)) {
      host.tell(player, "Please wait before trying again.");
      return;
    }
    worker.execute(
        () -> {
          try {
            JsonObject b = new JsonObject();
            b.addProperty("uuid", player.toString());
            b.addProperty(confirm ? "code" : "discordId", value);
            JsonObject r =
                post(
                    confirm ? "/api/minecraft/bridge/confirm" : "/api/minecraft/bridge/link",
                    b,
                    true);
            if (r.has("joinRequired")) {
              host.execute(
                  () ->
                      host.tell(
                          player,
                          "Please join this Discord server: " + r.get("invite").getAsString()));
            } else if (confirm) {
              Map<UUID, String> next = new HashMap<>(links);
              next.put(player, r.get("discordId").getAsString());
              links = Map.copyOf(next);
              sessionVerified.add(player);
              host.execute(() -> host.tell(player, "Discord verified. Your account is linked."));
            } else
              host.execute(
                  () ->
                      host.tell(player, "Check your Discord DMs, then use /link confirm <code>."));
          } catch (Exception e) {
            host.execute(() -> host.tell(player, e.getMessage()));
          } finally {
            finishLinkRequest(player);
          }
        });
  }

  public void reset(UUID player, String code, String password) {
    JsonObject job = resets.get(player);
    if (job == null || job.get("expires").getAsLong() < System.currentTimeMillis()) {
      host.tell(player, "No active reset request. Ask a server administrator for a new reset DM.");
      return;
    }
    if (password.length() < 8 || password.length() > 128) {
      host.tell(player, "Use a password between 8 and 128 characters.");
      return;
    }
    try {
      String actual =
          HexFormat.of()
              .formatHex(
                  MessageDigest.getInstance("SHA-256")
                      .digest(code.getBytes(StandardCharsets.UTF_8)));
      if (!MessageDigest.isEqual(
          actual.getBytes(StandardCharsets.UTF_8),
          job.getAsJsonObject("payload")
              .get("codeHash")
              .getAsString()
              .getBytes(StandardCharsets.UTF_8))) {
        host.tell(player, "Incorrect reset code.");
        return;
      }
      host.password(player, password);
      resets.remove(player);
      synchronized (this) {
        local.getAsJsonObject("resets").remove(player.toString());
        save();
      }
      host.tell(player, "Password change requested locally. Use /login with your new password.");
    } catch (Exception e) {
      host.tell(player, "Password reset failed. Ask an administrator to check the server console.");
    }
  }

  public void close() {
    worker.shutdownNow();
  }
}
