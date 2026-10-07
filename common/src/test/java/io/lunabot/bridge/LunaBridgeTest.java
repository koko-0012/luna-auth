package io.lunabot.bridge;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LunaBridgeTest {
  @TempDir Path directory;

  static class Host implements LunaBridge.Host {
    int passwords, actions;
    String message = "";

    public String platform() {
      return "fabric";
    }

    public String version() {
      return "test";
    }

    public JsonArray players() {
      return new JsonArray();
    }

    public void execute(Runnable work) {
      work.run();
    }

    public void tell(UUID player, String text) {
      message = text;
    }

    public boolean authenticated(UUID player) {
      return false;
    }

    public void action(UUID player, String action) {
      actions++;
    }

    public void password(UUID player, String password) {
      passwords++;
    }

    public void policy(JsonObject settings) {}

    public void log(String message) {}
  }

  private JsonObject state() {
    JsonObject local = new JsonObject();
    local.addProperty("panelUrl", "https://example.com");
    local.addProperty("token", "");
    for (String name : List.of("players", "receipts", "completed", "resets", "inProgress"))
      local.add(name, new JsonObject());
    return local;
  }

  @Test
  void privateResetSurvivesRestartAndIsAccountBoundSingleUse() throws Exception {
    UUID player = UUID.randomUUID();
    String code = "private-code", password = "new-test-password";
    JsonObject job = new JsonObject();
    job.addProperty("id", UUID.randomUUID().toString());
    job.addProperty("uuid", player.toString());
    job.addProperty("expires", System.currentTimeMillis() + 60000);
    JsonObject payload = new JsonObject();
    payload.addProperty(
        "codeHash",
        HexFormat.of()
            .formatHex(
                MessageDigest.getInstance("SHA-256")
                    .digest(code.getBytes(StandardCharsets.UTF_8))));
    job.add("payload", payload);
    JsonObject local = state();
    local.getAsJsonObject("resets").add(player.toString(), job);
    Path file = directory.resolve("bridge.json");
    Files.writeString(file, local.toString());
    Host host = new Host();
    try (LunaBridge bridge = new LunaBridge(file, host)) {
      bridge.reset(UUID.randomUUID(), code, password);
      bridge.reset(player, "wrong-code", password);
      bridge.reset(player, code, "short");
      assertEquals(0, host.passwords);
      bridge.reset(player, code, password);
      assertEquals(1, host.passwords);
      bridge.reset(player, code, password);
      assertEquals(1, host.passwords);
    }
    try (LunaBridge bridge = new LunaBridge(file, host)) {
      bridge.reset(player, code, password);
      assertEquals(1, host.passwords);
    }
    assertFalse(Files.readString(file).contains(password));
    assertFalse(Files.readString(file).contains(code));
  }

  @Test
  void interruptedDestructiveActionIsNotReplayedAndCachedPolicyRemains() throws Exception {
    JsonObject local = state(), settings = new JsonObject();
    settings.addProperty("requireDiscordLink", true);
    settings.addProperty("requireOfflineLogin", false);
    local.add("settings", settings);
    JsonObject job = new JsonObject();
    String id = UUID.randomUUID().toString();
    job.addProperty("id", id);
    local.getAsJsonObject("inProgress").add(id, job);
    Path file = directory.resolve("bridge.json");
    Files.writeString(file, local.toString());
    Host host = new Host();
    try (LunaBridge bridge = new LunaBridge(file, host)) {
      assertTrue(bridge.requiresLink());
      assertTrue(bridge.requiresLink(false));
      assertFalse(bridge.requiresLink(true));
      assertFalse(bridge.offlineLogin());
      assertFalse(bridge.verifiedSession(UUID.randomUUID()));
      assertEquals(0, host.actions);
      JsonObject saved = JsonParser.parseString(Files.readString(file)).getAsJsonObject();
      assertFalse(saved.getAsJsonObject("receipts").getAsJsonObject(id).get("ok").getAsBoolean());
    }
  }

  @Test
  void officialLinkingRequiresExplicitOptIn() throws Exception {
    JsonObject local = state(), settings = new JsonObject();
    settings.addProperty("requireDiscordLink", true);
    settings.addProperty("requireOfficialDiscordLink", true);
    local.add("settings", settings);
    Path file = directory.resolve("official.json");
    Files.writeString(file, local.toString());
    try (LunaBridge bridge = new LunaBridge(file, new Host())) {
      assertTrue(bridge.requiresLink(true));
      assertTrue(bridge.requiresLink(false));
    }
  }

  @Test
  void codeConfirmationDoesNotInheritTheDmRequestCooldown() throws Exception {
    Host host = new Host();
    Path file = directory.resolve("cooldown-bridge.json");
    Files.writeString(file, state().toString());
    LunaBridge bridge = new LunaBridge(file, host);
    UUID player = UUID.randomUUID();
    try {
      assertTrue(bridge.beginLinkRequest(player, false, 1000));
      assertFalse(bridge.beginLinkRequest(player, true, 1100)); // One in-flight request.
      bridge.finishLinkRequest(player);
      assertFalse(bridge.beginLinkRequest(player, false, 1500)); // DM request still limited.
      assertTrue(bridge.beginLinkRequest(player, true, 1500)); // Confirm immediately after DM.
      bridge.finishLinkRequest(player);
      assertFalse(
          bridge.beginLinkRequest(player, true, 2000)); // Confirmation has its own short limit.
      assertTrue(bridge.beginLinkRequest(player, true, 3500));
      bridge.finishLinkRequest(player);
      bridge.leave(player);
      assertTrue(bridge.beginLinkRequest(player, false, 4000));
      bridge.finishLinkRequest(player);
    } finally {
      bridge.close();
    }
  }
}
