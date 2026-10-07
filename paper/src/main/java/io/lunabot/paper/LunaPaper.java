package io.lunabot.paper;

import com.google.gson.*;
import io.lunabot.bridge.LunaBridge;
import java.lang.reflect.Method;
import java.util.*;
import org.bukkit.*;
import org.bukkit.command.*;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.block.*;
import org.bukkit.event.entity.*;
import org.bukkit.event.inventory.*;
import org.bukkit.event.player.*;
import org.bukkit.plugin.java.JavaPlugin;

public final class LunaPaper extends JavaPlugin implements Listener, LunaBridge.Host {
  private LunaBridge bridge;
  private Object auth;
  private volatile JsonObject policy = new JsonObject();
  private final Set<UUID> cleared = new HashSet<>();

  @Override
  public void onEnable() {
    try {
      auth =
          Class.forName("fr.xephi.authme.api.v3.AuthMeApi").getMethod("getInstance").invoke(null);
      for (String value : getConfig().getStringList("clearedSessions"))
        cleared.add(UUID.fromString(value));
      bridge = new LunaBridge(getDataFolder().toPath().resolve("luna-bridge.json"), this);
      getServer().getPluginManager().registerEvents(this, this);
      @SuppressWarnings("unchecked")
      Class<? extends Event> event =
          (Class<? extends Event>) Class.forName("fr.xephi.authme.events.RestoreSessionEvent");
      getServer()
          .getPluginManager()
          .registerEvent(
              event,
              this,
              EventPriority.HIGHEST,
              (listener, e) -> {
                try {
                  Player p = (Player) e.getClass().getMethod("getPlayer").invoke(e);
                  if (cleared.contains(p.getUniqueId())
                      || (policy.has("rememberSessions")
                          && !policy.get("rememberSessions").getAsBoolean()))
                    ((Cancellable) e).setCancelled(true);
                } catch (ReflectiveOperationException failure) {
                  ((Cancellable) e).setCancelled(true);
                }
              },
              this);
    } catch (Exception e) {
      getLogger()
          .severe("Luna cannot start. A compatible AuthMe API is required; plugin disabled.");
      getServer().getPluginManager().disablePlugin(this);
    }
  }

  @Override
  public void onDisable() {
    if (bridge != null) bridge.close();
  }

  private Object call(String method, Object... args) {
    try {
      for (Method m : auth.getClass().getMethods())
        if (m.getName().equals(method) && m.getParameterCount() == args.length) {
          Class<?>[] types = m.getParameterTypes();
          boolean matches = true;
          for (int i = 0; i < args.length; i++)
            if (!types[i].isInstance(args[i])
                && !(types[i] == boolean.class && args[i] instanceof Boolean)) matches = false;
          if (matches) return m.invoke(auth, args);
        }
      throw new IllegalStateException("Unsupported AuthMe API");
    } catch (Exception e) {
      throw new IllegalStateException("AuthMe action failed", e);
    }
  }

  private boolean blocked(Player p) {
    return bridge != null
        && ((bridge.requiresLink(Bukkit.getOnlineMode()) && !bridge.linked(p.getUniqueId()))
            || (!Bukkit.getOnlineMode()
                && !bridge.offlineLogin()
                && !bridge.verifiedSession(p.getUniqueId())));
  }

  @Override
  public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
    if (bridge == null) return true;
    if (label.equalsIgnoreCase("link")) {
      if (!(sender instanceof Player p)) return true;
      if (args.length == 2 && args[0].equalsIgnoreCase("confirm"))
        bridge.link(p.getUniqueId(), args[1], true);
      else if (args.length == 1) bridge.link(p.getUniqueId(), args[0], false);
      else sender.sendMessage("Use /link <Discord ID> or /link confirm <code>.");
      return true;
    }
    if (args.length == 2 && args[0].equalsIgnoreCase("pair")) {
      if (sender instanceof Player) {
        sender.sendMessage("Pairing is only available in the Minecraft server console.");
        return true;
      }
      bridge.pair(args[1]);
    } else if (args.length == 3 && args[0].equalsIgnoreCase("reset") && sender instanceof Player p)
      bridge.reset(p.getUniqueId(), args[1], args[2]);
    else
      sender.sendMessage(
          bridge.connected()
              ? "Luna connected."
              : "Luna not connected. Use luna pair <key> in the server console.");
    return true;
  }

  @EventHandler
  public void leave(PlayerQuitEvent e) {
    if (bridge != null) bridge.leave(e.getPlayer().getUniqueId());
  }

  @EventHandler(priority = EventPriority.HIGHEST)
  public void move(PlayerMoveEvent e) {
    if (blocked(e.getPlayer())
        && e.getTo() != null
        && (e.getFrom().getX() != e.getTo().getX()
            || e.getFrom().getY() != e.getTo().getY()
            || e.getFrom().getZ() != e.getTo().getZ())) e.setTo(e.getFrom());
  }

  @EventHandler(priority = EventPriority.HIGHEST)
  public void chat(AsyncPlayerChatEvent e) {
    if (blocked(e.getPlayer())) e.setCancelled(true);
  }

  @EventHandler(priority = EventPriority.HIGHEST)
  public void command(PlayerCommandPreprocessEvent e) {
    String c = e.getMessage().toLowerCase(Locale.ROOT);
    if (blocked(e.getPlayer())
        && !(c.startsWith("/link ")
            || c.startsWith("/luna reset ")
            || c.startsWith("/login ")
            || c.startsWith("/register ")
            || c.startsWith("/l ")
            || c.startsWith("/reg "))) e.setCancelled(true);
  }

  @EventHandler(priority = EventPriority.HIGHEST)
  public void interact(PlayerInteractEvent e) {
    if (blocked(e.getPlayer())) e.setCancelled(true);
  }

  @EventHandler(priority = EventPriority.HIGHEST)
  public void entity(PlayerInteractEntityEvent e) {
    if (blocked(e.getPlayer())) e.setCancelled(true);
  }

  @EventHandler(priority = EventPriority.HIGHEST)
  public void breakBlock(BlockBreakEvent e) {
    if (blocked(e.getPlayer())) e.setCancelled(true);
  }

  @EventHandler(priority = EventPriority.HIGHEST)
  public void place(BlockPlaceEvent e) {
    if (blocked(e.getPlayer())) e.setCancelled(true);
  }

  @EventHandler(priority = EventPriority.HIGHEST)
  public void drop(PlayerDropItemEvent e) {
    if (blocked(e.getPlayer())) e.setCancelled(true);
  }

  @EventHandler(priority = EventPriority.HIGHEST)
  public void pickup(EntityPickupItemEvent e) {
    if (e.getEntity() instanceof Player p && blocked(p)) e.setCancelled(true);
  }

  @EventHandler(priority = EventPriority.HIGHEST)
  public void inventory(InventoryClickEvent e) {
    if (e.getWhoClicked() instanceof Player p && blocked(p)) e.setCancelled(true);
  }

  @EventHandler(priority = EventPriority.HIGHEST)
  public void drag(InventoryDragEvent e) {
    if (e.getWhoClicked() instanceof Player p && blocked(p)) e.setCancelled(true);
  }

  @EventHandler(priority = EventPriority.HIGHEST)
  public void damage(EntityDamageByEntityEvent e) {
    if (e.getDamager() instanceof Player p && blocked(p)) e.setCancelled(true);
  }

  public String platform() {
    return "paper";
  }

  public String version() {
    return Bukkit.getBukkitVersion() + " / " + getDescription().getVersion();
  }

  public void execute(Runnable work) {
    Bukkit.getScheduler().runTask(this, work);
  }

  public void tell(UUID id, String text) {
    Player p = Bukkit.getPlayer(id);
    if (p != null) p.sendMessage(text);
  }

  public boolean authenticated(UUID id) {
    Player p = Bukkit.getPlayer(id);
    return p != null && (Bukkit.getOnlineMode() || Boolean.TRUE.equals(call("isAuthenticated", p)));
  }

  public JsonArray players() {
    JsonArray data = new JsonArray();
    for (Player p : Bukkit.getOnlinePlayers()) {
      if (!Bukkit.getOnlineMode()
          && policy.has("allowOffline")
          && !policy.get("allowOffline").getAsBoolean()) {
        p.kickPlayer("Offline accounts are disabled for this community.");
        continue;
      }
      if (!Bukkit.getOnlineMode()
          && bridge != null
          && !bridge.offlineLogin()
          && bridge.verifiedSession(p.getUniqueId())
          && !authenticated(p.getUniqueId())) {
        if (!Boolean.TRUE.equals(call("isRegistered", p.getName()))) {
          if (!Boolean.TRUE.equals(
              call("registerPlayer", p.getName(), UUID.randomUUID().toString()))) continue;
        }
        call("forceLogin", p);
      }
      JsonObject o = new JsonObject();
      o.addProperty("uuid", p.getUniqueId().toString());
      o.addProperty("name", p.getName());
      o.addProperty("official", Bukkit.getOnlineMode());
      o.addProperty("online", true);
      o.addProperty("registered", Boolean.TRUE.equals(call("isRegistered", p.getName())));
      data.add(o);
    }
    return data;
  }

  private String name(UUID id) {
    String value = Bukkit.getOfflinePlayer(id).getName();
    if (value == null) throw new IllegalStateException("Unknown player");
    return value;
  }

  private void rememberClear(UUID id) {
    cleared.add(id);
    getConfig().set("clearedSessions", cleared.stream().map(UUID::toString).toList());
    saveConfig();
  }

  public void action(UUID id, String action) {
    String name = name(id);
    switch (action) {
      case "force-reset" -> {
        call("changePassword", name, UUID.randomUUID().toString());
        rememberClear(id);
      }
      case "clear-session" -> rememberClear(id);
      case "delete-login" -> {
        call("forceUnregister", name);
        rememberClear(id);
      }
      case "unlink" -> {}
      default -> throw new IllegalArgumentException("Unsupported action");
    }
    Player p = Bukkit.getPlayer(id);
    if (p != null) {
      call("forceLogout", p);
      p.kickPlayer(
          "Your authentication record was changed by a server administrator. Reconnect to"
              + " continue.");
    }
  }

  public void password(UUID id, String password) {
    String name = name(id);
    if (Boolean.TRUE.equals(call("isRegistered", name))) call("changePassword", name, password);
    else {
      Player p = Bukkit.getPlayer(id);
      if (p == null) throw new IllegalStateException("Player offline");
      call("forceRegister", p, password, false);
    }
    rememberClear(id);
  }

  public void policy(JsonObject settings) {
    policy = settings;
  }

  public void log(String message) {
    getLogger().info(message);
  }
}
