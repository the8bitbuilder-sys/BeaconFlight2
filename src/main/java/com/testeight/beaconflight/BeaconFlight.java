package com.testeight.beaconflight;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.Beacon;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerGameModeChangeEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.Vector;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Beacon Flight v7 - plugin version of beaconflight_v6.sk
 * Tracked beacons, 96-block zone at any height, instant activation on movement,
 * /reacon to recalibrate, /scanbeacons and /listbeacons for ops.
 */
public final class BeaconFlight extends JavaPlugin implements Listener {

    private static final Set<Material> BASE = EnumSet.of(
            Material.IRON_BLOCK, Material.GOLD_BLOCK, Material.DIAMOND_BLOCK,
            Material.EMERALD_BLOCK, Material.NETHERITE_BLOCK);
    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.legacyAmpersand();

    /** A tracked beacon position. Stored by world name, like the Skript version. */
    private record BeaconPos(String world, int x, int y, int z) {
        static BeaconPos of(Block b) {
            return new BeaconPos(b.getWorld().getName(), b.getX(), b.getY(), b.getZ());
        }
        String serialize() {
            return world + ";" + x + ";" + y + ";" + z;
        }
        static BeaconPos parse(String s) {
            String[] p = s.split(";");
            if (p.length != 4) return null;
            try {
                return new BeaconPos(p[0], Integer.parseInt(p[1]), Integer.parseInt(p[2]), Integer.parseInt(p[3]));
            } catch (NumberFormatException e) {
                return null;
            }
        }
    }

    private final Set<BeaconPos> beacons = new HashSet<>();
    private final Set<UUID> flying = new HashSet<>();
    private int radius;
    private File dataFile;
    private boolean dirty;

    // ---------------- Startup / shutdown ----------------

    @Override
    public void onEnable() {
        saveDefaultConfig();
        radius = getConfig().getInt("beacon-radius", 96);
        int interval = Math.max(1, getConfig().getInt("check-interval-ticks", 20));

        dataFile = new File(getDataFolder(), "beacons.yml");
        loadBeacons();

        getServer().getPluginManager().registerEvents(this, this);
        Bukkit.getScheduler().runTaskTimer(this, this::periodicCheck, interval, interval);
        Bukkit.getScheduler().runTaskTimer(this, () -> { if (dirty) saveBeacons(); }, 1200L, 1200L);

        // Pick up beacons that already exist in loaded areas (old beacons, WorldEdit, etc.)
        Bukkit.getScheduler().runTask(this, () -> {
            int added = rescanAllLoaded();
            if (added > 0) getLogger().info("Registered " + added + " existing beacon(s) on startup.");
            for (Player p : Bukkit.getOnlinePlayers()) update(p, p.getLocation());
        });
        getLogger().info("Loaded " + beacons.size() + " tracked beacon(s).");
    }

    @Override
    public void onDisable() {
        for (UUID id : new ArrayList<>(flying)) {
            Player p = Bukkit.getPlayer(id);
            if (p != null) disableFlight(p, false);
        }
        flying.clear();
        saveBeacons();
    }

    private void loadBeacons() {
        beacons.clear();
        if (!dataFile.exists()) return;
        YamlConfiguration yml = YamlConfiguration.loadConfiguration(dataFile);
        for (String s : yml.getStringList("beacons")) {
            BeaconPos pos = BeaconPos.parse(s);
            if (pos != null) beacons.add(pos);
        }
    }

    private void saveBeacons() {
        YamlConfiguration yml = new YamlConfiguration();
        List<String> list = new ArrayList<>();
        for (BeaconPos pos : beacons) list.add(pos.serialize());
        yml.set("beacons", list);
        try {
            getDataFolder().mkdirs();
            yml.save(dataFile);
            dirty = false;
        } catch (IOException e) {
            getLogger().warning("Could not save beacons.yml: " + e.getMessage());
        }
    }

    // ---------------- Beacon checks ----------------

    private static boolean isBase(Block b) {
        return BASE.contains(b.getType());
    }

    private static boolean hasBase(Block beacon) {
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                if (!isBase(beacon.getRelative(dx, -1, dz))) return false;
            }
        }
        return true;
    }

    private static boolean isActive(Block b) {
        return b.getType() == Material.BEACON && hasBase(b);
    }

    private void track(Block b) {
        if (beacons.add(BeaconPos.of(b))) dirty = true;
    }

    private void untrack(Block b) {
        if (beacons.remove(BeaconPos.of(b))) dirty = true;
    }

    // ---------------- Beacon discovery (event-driven, no scanning) ----------------

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent e) {
        Block b = e.getBlockPlaced();
        if (b.getType() == Material.BEACON) {
            if (isActive(b)) track(b);
            return;
        }
        // A base block was placed: re-check any beacon sitting on this part of a base
        if (isBase(b)) {
            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    Block above = b.getRelative(dx, 1, dz);
                    if (above.getType() == Material.BEACON) {
                        if (isActive(above)) track(above); else untrack(above);
                    }
                }
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent e) {
        Block b = e.getBlock();
        if (b.getType() == Material.BEACON) {
            untrack(b);
            return;
        }
        if (isBase(b)) {
            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    Block above = b.getRelative(dx, 1, dz);
                    if (above.getType() == Material.BEACON) untrack(above);
                }
            }
        }
    }

    /** Registers every active beacon in one chunk. Returns how many were new. */
    private int registerChunk(Chunk chunk) {
        int added = 0;
        for (BlockState state : chunk.getTileEntities()) {
            if (!(state instanceof Beacon)) continue;
            Block b = state.getBlock();
            if (isActive(b) && beacons.add(BeaconPos.of(b))) {
                added++;
                dirty = true;
            }
        }
        return added;
    }

    /** Registers every active beacon in every loaded area of every world. */
    private int rescanAllLoaded() {
        int added = 0;
        for (World w : Bukkit.getWorlds()) {
            for (Chunk chunk : w.getLoadedChunks()) added += registerChunk(chunk);
        }
        return added;
    }

    /** Old beacons are picked up automatically whenever their area loads. */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onChunkLoad(ChunkLoadEvent e) {
        registerChunk(e.getChunk());
    }

    /** Right-clicking a beacon registers it (or explains why it can't). */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onBeaconClick(PlayerInteractEvent e) {
        if (e.getAction() != Action.RIGHT_CLICK_BLOCK || e.getHand() != EquipmentSlot.HAND) return;
        Block b = e.getClickedBlock();
        if (b == null || b.getType() != Material.BEACON) return;
        Player p = e.getPlayer();
        if (!hasBase(b)) {
            p.sendActionBar(LEGACY.deserialize("&cThis beacon needs a full 3x3 base of iron, gold, diamond, emerald or netherite blocks"));
            return;
        }
        if (beacons.add(BeaconPos.of(b))) {
            dirty = true;
            p.sendActionBar(LEGACY.deserialize("&a✦ Beacon registered for flight ✦"));
            recheckNextTick(p);
        }
    }

    private static Component rescanButton() {
        return Component.text("[Re-register all beacons]", NamedTextColor.AQUA)
                .clickEvent(ClickEvent.runCommand("/scanbeacons"))
                .hoverEvent(HoverEvent.showText(Component.text("Click to find every beacon in all loaded areas")));
    }

    /**
     * Every check-interval: re-validate tracked beacons (explosions, WorldEdit, pistons...)
     * and re-check players who currently have beacon flight.
     */
    private void periodicCheck() {
        Iterator<BeaconPos> it = beacons.iterator();
        while (it.hasNext()) {
            BeaconPos pos = it.next();
            World w = Bukkit.getWorld(pos.world());
            if (w == null || !w.isChunkLoaded(pos.x() >> 4, pos.z() >> 4)) continue; // can't check, keep it
            if (!isActive(w.getBlockAt(pos.x(), pos.y(), pos.z()))) {
                it.remove();
                dirty = true;
            }
        }
        for (UUID id : new ArrayList<>(flying)) {
            Player p = Bukkit.getPlayer(id);
            if (p != null) update(p, p.getLocation());
        }
    }

    // ---------------- Zone logic ----------------

    private boolean isNearBeacon(Location loc) {
        if (loc.getWorld() == null) return false;
        String world = loc.getWorld().getName();
        double r2 = (double) radius * radius;
        for (BeaconPos pos : beacons) {
            if (!pos.world().equals(world)) continue;
            double dx = pos.x() - loc.getX();
            double dz = pos.z() - loc.getZ();
            if (dx * dx + dz * dz <= r2) return true;
        }
        return false;
    }

    private static boolean ignored(Player p) {
        return p.getGameMode() == GameMode.CREATIVE || p.getGameMode() == GameMode.SPECTATOR;
    }

    private void update(Player p, Location loc) {
        if (ignored(p)) return;

        // Flight was switched off by something else (death, gamemode change, world change...)
        if (flying.contains(p.getUniqueId()) && !p.getAllowFlight()) {
            flying.remove(p.getUniqueId());
        }

        boolean near = isNearBeacon(loc);
        boolean has = flying.contains(p.getUniqueId());
        if (near && !has) {
            enableFlight(p, "&b✦ Beacon Zone ✦ Flight Enabled");
        } else if (!near && has) {
            disableFlight(p, true);
        }
    }

    private void enableFlight(Player p, String actionBar) {
        p.setAllowFlight(true);
        flying.add(p.getUniqueId());
        p.setVelocity(p.getVelocity().add(new Vector(0, 0.4, 0)));
        p.playSound(p.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 0.6f, 1.2f);
        p.sendActionBar(LEGACY.deserialize(actionBar));
    }

    private void disableFlight(Player p, boolean effects) {
        p.setFlying(false);
        p.setAllowFlight(false);
        flying.remove(p.getUniqueId());
        if (effects) {
            p.addPotionEffect(new PotionEffect(PotionEffectType.SLOW_FALLING, 60, 0));
            p.sendActionBar(LEGACY.deserialize("&7✧ Leaving Beacon Zone"));
        }
    }

    // ---------------- Instant activation on movement ----------------

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent e) {
        Location from = e.getFrom();
        Location to = e.getTo();
        if (from.getWorld() == to.getWorld()
                && from.getBlockX() == to.getBlockX()
                && from.getBlockZ() == to.getBlockZ()
                && flying.contains(e.getPlayer().getUniqueId()) == e.getPlayer().getAllowFlight()) {
            return; // only looked around / moved up or down - zone can't have changed
        }
        update(e.getPlayer(), to);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onTeleport(PlayerTeleportEvent e) {
        recheckNextTick(e.getPlayer());
    }

    @EventHandler
    public void onWorldChange(PlayerChangedWorldEvent e) {
        recheckNextTick(e.getPlayer());
    }

    @EventHandler
    public void onRespawn(PlayerRespawnEvent e) {
        recheckNextTick(e.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onGameMode(PlayerGameModeChangeEvent e) {
        recheckNextTick(e.getPlayer());
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        recheckNextTick(e.getPlayer());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        Player p = e.getPlayer();
        if (flying.remove(p.getUniqueId()) && !ignored(p)) {
            p.setFlying(false);
            p.setAllowFlight(false);
        }
    }

    private void recheckNextTick(Player p) {
        Bukkit.getScheduler().runTask(this, () -> {
            if (p.isOnline()) update(p, p.getLocation());
        });
    }

    // ---------------- Commands ----------------

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        switch (command.getName().toLowerCase()) {
            case "scanbeacons" -> scanBeacons(sender);
            case "listbeacons" -> listBeacons(sender);
            case "reacon" -> reacon(sender);
            default -> { return false; }
        }
        return true;
    }

    /** Re-register every active beacon in all loaded areas, in every world. */
    private void scanBeacons(CommandSender sender) {
        int added = rescanAllLoaded();
        saveBeacons();
        sender.sendMessage(LEGACY.deserialize("&aBeacon scan complete. Registered " + added
                + " new beacon(s). Total tracked: " + beacons.size() + "."));
        sender.sendMessage(rescanButton());
        for (Player p : Bukkit.getOnlinePlayers()) update(p, p.getLocation());
    }

    private void listBeacons(CommandSender sender) {
        for (BeaconPos pos : beacons) {
            sender.sendMessage(LEGACY.deserialize("&7- " + pos.world() + " " + pos.x() + ", " + pos.y() + ", " + pos.z()));
        }
        sender.sendMessage(LEGACY.deserialize("&aTotal tracked beacons: " + beacons.size()));
        sender.sendMessage(rescanButton());
    }

    /** Manual recalibration (fixes flight if it gets disabled by accident). */
    private void reacon(CommandSender sender) {
        if (!(sender instanceof Player p)) {
            sender.sendMessage(Component.text("Only players can use this."));
            return;
        }
        if (ignored(p)) {
            p.sendMessage(LEGACY.deserialize("&7You don't need to recalibrate in this gamemode."));
            return;
        }
        if (isNearBeacon(p.getLocation())) {
            enableFlight(p, "&b✦ Flight Recalibrated ✦");
        } else {
            p.sendMessage(LEGACY.deserialize("&cYou must be inside a beacon zone to recalibrate flight."));
        }
    }
}
