package io.lunabot.fabric;

import static com.mojang.brigadier.arguments.StringArgumentType.*;
import static net.minecraft.server.command.CommandManager.*;

import com.google.gson.*;
import io.lunabot.bridge.LunaBridge;
import java.util.*;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import xyz.nikitacartes.easyauth.EasyAuth;
import xyz.nikitacartes.easyauth.storage.PlayerEntryV1;
import xyz.nikitacartes.easyauth.utils.AuthHelper;
import xyz.nikitacartes.easyauth.utils.PlayerAuth;

public class LunaFabric implements ModInitializer, LunaBridge.Host {
  private MinecraftServer server;
  public static volatile LunaBridge bridge;
  private JsonObject policy = new JsonObject();

  public static boolean blocked(ServerPlayerEntity player) {
    if (bridge == null || player == null) return false;
    boolean offline = !((PlayerAuth) player).easyAuth$isUsingMojangAccount();
    return (bridge.requiresLink(!offline) && !bridge.linked(player.getUuid()))
        || (offline && !bridge.offlineLogin() && !bridge.verifiedSession(player.getUuid()));
  }

  public static boolean blocked(net.minecraft.entity.player.PlayerEntity player) {
    return player instanceof ServerPlayerEntity p && blocked(p);
  }

  @Override
  public void onInitialize() {
    ServerLifecycleEvents.SERVER_STARTED.register(
        s -> {
          server = s;
          try {
            bridge =
                new LunaBridge(
                    FabricLoader.getInstance().getConfigDir().resolve("luna-bridge.json"), this);
          } catch (Exception e) {
            log("Luna bridge failed to start; check config permissions.");
          }
        });
    ServerLifecycleEvents.SERVER_STOPPING.register(
        s -> {
          if (bridge != null) bridge.close();
        });
    ServerPlayConnectionEvents.JOIN.register(
        (handler, sender, s) -> {
          if (bridge != null && blocked(handler.player))
            tell(
                handler.player.getUuid(),
                "Verify Discord with /link <Discord ID>. Look for Luna’s private DM, then /link"
                    + " confirm <code>.");
        });
    ServerPlayConnectionEvents.DISCONNECT.register(
        (handler, s) -> {
          if (bridge != null) bridge.leave(handler.player.getUuid());
        });
    ServerTickEvents.END_SERVER_TICK.register(
        s -> {
          if (bridge == null) return;
          for (ServerPlayerEntity p : s.getPlayerManager().getPlayerList()) {
            PlayerAuth auth = (PlayerAuth) p;
            if (policy.has("allowOffline")
                && !policy.get("allowOffline").getAsBoolean()
                && !auth.easyAuth$isUsingMojangAccount())
              p.networkHandler.disconnect(
                  Text.literal("This server accepts official Minecraft accounts only."));
            if (!bridge.offlineLogin()
                && !auth.easyAuth$isUsingMojangAccount()
                && bridge.verifiedSession(p.getUuid())
                && !auth.easyAuth$isAuthenticated()) {
              auth.easyAuth$setAuthenticated(true);
              auth.easyAuth$restoreTrueLocation();
              p.setInvulnerable(false);
              p.setInvisible(false);
            }
          }
        });
    CommandRegistrationCallback.EVENT.register(
        (d, r, e) -> {
          d.register(
              literal("link")
                  .then(
                      literal("confirm")
                          .then(
                              argument("code", word())
                                  .executes(
                                      c -> {
                                        ServerPlayerEntity p = c.getSource().getPlayerOrThrow();
                                        if (bridge != null)
                                          bridge.link(p.getUuid(), getString(c, "code"), true);
                                        return 1;
                                      })))
                  .then(
                      argument("discordId", word())
                          .executes(
                              c -> {
                                ServerPlayerEntity p = c.getSource().getPlayerOrThrow();
                                if (bridge != null)
                                  bridge.link(p.getUuid(), getString(c, "discordId"), false);
                                return 1;
                              })));
          d.register(
              literal("luna")
                  .then(
                      literal("pair")
                          .requires(c -> c.getEntity() == null && c.hasPermissionLevel(4))
                          .then(
                              argument("key", word())
                                  .executes(
                                      c -> {
                                        if (bridge != null) bridge.pair(getString(c, "key"));
                                        return 1;
                                      })))
                  .then(
                      literal("status")
                          .executes(
                              c -> {
                                c.getSource()
                                    .sendMessage(
                                        Text.literal(
                                            bridge != null && bridge.connected()
                                                ? "Luna connected."
                                                : "Luna not connected."));
                                return 1;
                              }))
                  .then(
                      literal("reset")
                          .then(
                              argument("code", word())
                                  .then(
                                      argument("password", word())
                                          .executes(
                                              c -> {
                                                ServerPlayerEntity p =
                                                    c.getSource().getPlayerOrThrow();
                                                if (bridge != null)
                                                  bridge.reset(
                                                      p.getUuid(),
                                                      getString(c, "code"),
                                                      getString(c, "password"));
                                                return 1;
                                              })))));
        });
  }

  public String platform() {
    return "fabric";
  }

  public String version() {
    return "1.20-1.20.1 / "
        + FabricLoader.getInstance()
            .getModContainer("easyauth")
            .orElseThrow()
            .getMetadata()
            .getVersion()
            .getFriendlyString();
  }

  public void execute(Runnable work) {
    server.execute(work);
  }

  public void tell(UUID id, String text) {
    ServerPlayerEntity p = server.getPlayerManager().getPlayer(id);
    if (p != null) p.sendMessage(Text.literal(text), false);
  }

  public boolean authenticated(UUID id) {
    ServerPlayerEntity p = server.getPlayerManager().getPlayer(id);
    return p != null && ((PlayerAuth) p).easyAuth$isAuthenticated();
  }

  public JsonArray players() {
    JsonArray result = new JsonArray();
    for (ServerPlayerEntity p : server.getPlayerManager().getPlayerList()) {
      PlayerAuth auth = (PlayerAuth) p;
      JsonObject o = new JsonObject();
      o.addProperty("uuid", p.getUuidAsString());
      o.addProperty("name", p.getGameProfile().getName());
      o.addProperty("official", auth.easyAuth$isUsingMojangAccount());
      o.addProperty("online", true);
      o.addProperty("registered", !auth.easyAuth$getPlayerEntryV1().password.isEmpty());
      result.add(o);
    }
    return result;
  }

  private String name(UUID id) {
    ServerPlayerEntity p = server.getPlayerManager().getPlayer(id);
    if (p != null) return p.getGameProfile().getName();
    for (PlayerEntryV1 e : EasyAuth.DB.getAllData().values())
      if (id.equals(e.uuid)) return e.username;
    throw new IllegalArgumentException("Unknown player UUID");
  }

  public void action(UUID id, String action) {
    String name = name(id);
    PlayerEntryV1 entry = EasyAuth.DB.getUserData(name);
    if (entry == null) throw new IllegalStateException("No authentication entry");
    switch (action) {
      case "force-reset" -> {
        entry.password = AuthHelper.hashPassword(UUID.randomUUID().toString().toCharArray());
        entry.lastIp = "";
        entry.lastAuthenticatedDate = EasyAuth.getUnixZero();
        EasyAuth.DB.updateUserData(entry);
      }
      case "clear-session" -> {
        entry.lastIp = "";
        entry.lastAuthenticatedDate = EasyAuth.getUnixZero();
        EasyAuth.DB.updateUserData(entry);
      }
      case "delete-login" -> EasyAuth.DB.deleteUserData(name);
      case "unlink" -> {}
      default -> throw new IllegalArgumentException("Unsupported action");
    }
    ServerPlayerEntity p = server.getPlayerManager().getPlayer(id);
    if (p != null)
      p.networkHandler.disconnect(
          Text.literal(
              "Your authentication record was changed by a server administrator. Reconnect to"
                  + " continue."));
  }

  public void password(UUID id, String password) {
    String name = name(id);
    PlayerEntryV1 entry = EasyAuth.DB.getUserDataOrCreate(name);
    entry.password = AuthHelper.hashPassword(password.toCharArray());
    entry.lastIp = "";
    entry.lastAuthenticatedDate = EasyAuth.getUnixZero();
    EasyAuth.DB.updateUserData(entry);
    ServerPlayerEntity p = server.getPlayerManager().getPlayer(id);
    if (p != null) ((PlayerAuth) p).easyAuth$setPlayerEntryV1(entry);
  }

  public void policy(JsonObject settings) {
    policy = settings;
    EasyAuth.config.sessionTimeout = settings.get("rememberSessions").getAsBoolean() ? 86400 : 0;
  }

  public void log(String message) {
    org.slf4j.LoggerFactory.getLogger("LunaAuth").info(message);
  }
}
