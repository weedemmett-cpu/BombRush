package com.example.bombrush;

import net.md_5.bungee.api.ChatMessageType;
import net.md_5.bungee.api.chat.TextComponent;
import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.BlockData;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.PluginCommand;
import org.bukkit.command.TabExecutor;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.*;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.*;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.*;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.PotionMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Transformation;
import org.bukkit.util.Vector;
import org.joml.AxisAngle4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.ThreadLocalRandom;

public class BombRushPlugin extends JavaPlugin implements Listener, TabExecutor {

    private static final String DEFUSE_GUI_TITLE = ChatColor.DARK_RED + "" + ChatColor.BOLD + "C4 Wiring Defusal (10s)";

    public enum WeaponSlotType {
        PRIMARY,
        SECONDARY,
        UTILITY
    }

    public enum WeaponId {
        // Starter guns
        ASSAULT_RIFLE("assault_rifle", "Assault Rifle", WeaponSlotType.PRIMARY, true, 0, Material.CROSSBOW, "iron_axe", 30, 90, 44),
        PUMP_SHOTGUN("pump_shotgun", "Pump Shotgun", WeaponSlotType.PRIMARY, true, 0, Material.BOW, "netherite_horse_armor", 6, 24, 50),
        GLOCK("glock", "Glock", WeaponSlotType.SECONDARY, true, 0, Material.CROSSBOW, "netherite_hoe", 15, 60, 30),
        DEAGLE("deagle", "Deagle", WeaponSlotType.SECONDARY, true, 0, Material.MACE, "iron_horse_armor", 7, 28, 36),

        // Unlockable roster guns (no musket, flintlock, or grenade launcher)
        SNIPER("sniper", "Sniper", WeaponSlotType.PRIMARY, false, 150, Material.SPYGLASS, "spyglass", 5, 15, 56),
        TEC_9("tec_9", "Tec-9", WeaponSlotType.SECONDARY, false, 100, Material.CROSSBOW, "repeater", 24, 72, 34),
        SHORTY("shorty", "Shorty", WeaponSlotType.PRIMARY, false, 110, Material.BOW, "diamond_axe", 2, 12, 36),
        BURST_RIFLE("burst_rifle", "Burst Rifle", WeaponSlotType.PRIMARY, false, 140, Material.CROSSBOW, "trident", 24, 72, 42),
        REVOLVER("revolver", "Revolver", WeaponSlotType.SECONDARY, false, 125, Material.MACE, "iron_hoe", 6, 24, 40),
        HARPOON_GUN("harpoon_gun", "Harpoon Gun", WeaponSlotType.PRIMARY, false, 160, Material.CROSSBOW, "fishing_rod", 1, 8, 32),

        // Utilities (1 throwable per round)
        GRENADE("grenade", "Frag Grenade", WeaponSlotType.UTILITY, true, 0, Material.SNOWBALL, "ender_eye", 0, 0, 0),
        SMOKE_GRENADE("smoke_grenade", "Smoke Grenade", WeaponSlotType.UTILITY, true, 0, Material.SNOWBALL, "clay_ball", 0, 0, 0),
        FLASHBANG("flashbang", "Flashbang", WeaponSlotType.UTILITY, true, 0, Material.SNOWBALL, "ink_sac", 0, 0, 0),
        MOLOTOV("molotov", "Molotov", WeaponSlotType.UTILITY, true, 0, Material.POTION, null, 0, 0, 0),
        KNIFE("knife", "Combat Knife", WeaponSlotType.UTILITY, true, 0, Material.IRON_SWORD, null, 0, 0, 0);

        public final String id;
        public final String displayName;
        public final WeaponSlotType slotType;
        public final boolean starter;
        public final int cost;
        public final Material baseMaterial;
        public final String uniqueItemModel;
        public final int magSize;
        public final int reserveAmmo;
        public final int reloadTicks;

        WeaponId(String id, String displayName, WeaponSlotType slotType, boolean starter, int cost,
                 Material baseMaterial, String uniqueItemModel, int magSize, int reserveAmmo, int reloadTicks) {
            this.id = id;
            this.displayName = displayName;
            this.slotType = slotType;
            this.starter = starter;
            this.cost = cost;
            this.baseMaterial = baseMaterial;
            this.uniqueItemModel = uniqueItemModel;
            this.magSize = magSize;
            this.reserveAmmo = reserveAmmo;
            this.reloadTicks = reloadTicks;
        }

        public boolean isGun() {
            return slotType == WeaponSlotType.PRIMARY || slotType == WeaponSlotType.SECONDARY;
        }

        public boolean isGrenade() {
            return this == GRENADE || this == SMOKE_GRENADE || this == FLASHBANG || this == MOLOTOV;
        }

        public static WeaponId fromId(String id) {
            if (id == null) return null;
            for (WeaponId w : values()) {
                if (w.id.equalsIgnoreCase(id)) return w;
            }
            return null;
        }
    }

    private static class Bounds {
        Location min;
        Location max;

        boolean contains(Location loc) {
            if (min == null || max == null || loc == null || loc.getWorld() == null) return false;
            if (!loc.getWorld().equals(min.getWorld())) return false;
            double x = loc.getX(), y = loc.getY(), z = loc.getZ();
            double minX = Math.min(min.getX(), max.getX()), maxX = Math.max(min.getX(), max.getX());
            double minY = Math.min(min.getY(), max.getY()), maxY = Math.max(min.getY(), max.getY());
            double minZ = Math.min(min.getZ(), max.getZ()), maxZ = Math.max(min.getZ(), max.getZ());
            return x >= minX && x <= maxX && y >= minY && y <= maxY && z >= minZ && z <= maxZ;
        }

        Location randomPoint(double yOffset) {
            if (min == null || max == null || min.getWorld() == null) return null;
            double minX = Math.min(min.getX(), max.getX()), maxX = Math.max(min.getX(), max.getX());
            double minY = Math.min(min.getY(), max.getY()), maxY = Math.max(min.getY(), max.getY());
            double minZ = Math.min(min.getZ(), max.getZ()), maxZ = Math.max(min.getZ(), max.getZ());
            return new Location(
                    min.getWorld(),
                    ThreadLocalRandom.current().nextDouble(minX + 0.5, Math.max(minX + 0.6, maxX - 0.5)),
                    minY + yOffset,
                    ThreadLocalRandom.current().nextDouble(minZ + 0.5, Math.max(minZ + 0.6, maxZ - 0.5))
            );
        }
    }

    private static class BombMap {
        final String name;
        Bounds plantersSpawn = new Bounds();
        Bounds defusersSpawn = new Bounds();
        final List<Bounds> bombSites = new ArrayList<>();

        BombMap(String name) {
            this.name = name;
        }

        boolean isReady() {
            return plantersSpawn.min != null && plantersSpawn.max != null
                    && defusersSpawn.min != null && defusersSpawn.max != null
                    && !bombSites.isEmpty();
        }
    }

    private static class PlayerProfile {
        int coins = 100;
        final Set<WeaponId> unlockedGuns = new LinkedHashSet<>();
        boolean tracerParticles = true;
        boolean hitSound = true;

        PlayerProfile() {
            for (WeaponId w : WeaponId.values()) {
                if (w.starter && w.isGun()) {
                    unlockedGuns.add(w);
                }
            }
        }
    }

    private static class DefuseSession {
        final UUID playerId;
        final int targetWireSlot;
        final long readyToCutAtMillis;
        boolean unlocked = false;

        DefuseSession(UUID playerId, int targetWireSlot, long readyToCutAtMillis) {
            this.playerId = playerId;
            this.targetWireSlot = targetWireSlot;
            this.readyToCutAtMillis = readyToCutAtMillis;
        }
    }

    private static class PrimedGrenade {
        final UUID ownerId;
        final WeaponId type;
        final long detonateAtMillis;
        Snowball thrownEntity;
        Location landedLoc;
        boolean landed = false;
        boolean thrown = false;
        boolean detonated = false;

        PrimedGrenade(UUID ownerId, WeaponId type, long detonateAtMillis) {
            this.ownerId = ownerId;
            this.type = type;
            this.detonateAtMillis = detonateAtMillis;
        }
    }

    private final Map<String, BombMap> maps = new LinkedHashMap<>();
    private final Map<UUID, PlayerProfile> profiles = new HashMap<>();
    private final Set<UUID> buildModePlayers = new HashSet<>();

    private final Map<UUID, String> playerTeam = new HashMap<>();
    private UUID bombCarrierId;
    private boolean roundActive = false;
    private int roundNumber = 0;
    private BombMap currentMap;

    private boolean bombPlanted = false;
    private Location plantedBombLoc;
    private UUID plantedBombDisplayId;
    private int bombFuseSecondsLeft = 0;
    private BukkitRunnable bombFuseTask;
    private final Map<UUID, DefuseSession> activeDefuses = new HashMap<>();
    private final Set<UUID> currentlyPlanting = new HashSet<>();

    private final Map<UUID, Long> fireCooldowns = new HashMap<>();
    private final Set<UUID> reloadingPlayers = new HashSet<>();
    private final Map<UUID, String> projKind = new HashMap<>();
    private final Map<UUID, PrimedGrenade> primedByPlayer = new HashMap<>();
    private final Map<UUID, PrimedGrenade> primedByEntity = new HashMap<>();
    private final Set<Location> molotovFireBlocks = new HashSet<>();

    private NamespacedKey keyWeaponId;
    private NamespacedKey keyGunMag;
    private NamespacedKey keyGunReserve;
    private NamespacedKey keyBombItem;
    private NamespacedKey keyBombDisplay;

    private File dataFile;
    private YamlConfiguration dataConfig;

    @Override
    public void onEnable() {
        keyWeaponId = new NamespacedKey(this, "br_weapon");
        keyGunMag = new NamespacedKey(this, "br_gun_mag");
        keyGunReserve = new NamespacedKey(this, "br_gun_reserve");
        keyBombItem = new NamespacedKey(this, "br_c4_item");
        keyBombDisplay = new NamespacedKey(this, "br_c4_display");

        loadData();

        PluginCommand br = getCommand("br");
        if (br != null) {
            br.setExecutor(this);
            br.setTabCompleter(this);
        }

        getServer().getPluginManager().registerEvents(this, this);

        // Live action-bar ammo & reload HUD task
        new BukkitRunnable() {
            @Override
            public void run() {
                for (Player p : Bukkit.getOnlinePlayers()) {
                    ItemStack main = p.getInventory().getItemInMainHand();
                    WeaponId w = getWeapon(main);
                    if (w != null && w.isGun()) {
                        int mag = getMag(main, w);
                        int res = getReserve(main, w);
                        if (reloadingPlayers.contains(p.getUniqueId())) {
                            sendActionBar(p, ChatColor.YELLOW + "RELOADING " + w.displayName + "... " + ChatColor.GRAY + "(" + mag + "/" + w.magSize + " | " + res + ")");
                        } else {
                            ChatColor magCol = (mag == 0) ? ChatColor.RED : (mag <= Math.max(1, w.magSize / 4) ? ChatColor.GOLD : ChatColor.GREEN);
                            sendActionBar(p, ChatColor.AQUA + w.displayName + ChatColor.DARK_GRAY + " | "
                                    + magCol + mag + ChatColor.GRAY + "/" + w.magSize
                                    + ChatColor.DARK_GRAY + " | " + ChatColor.YELLOW + "Reserve: " + res
                                    + ChatColor.GRAY + " [F to Reload]");
                        }
                    }
                }
            }
        }.runTaskTimer(this, 5L, 5L);

        getLogger().info("BombRush 26.2 enabled!");
    }

    @Override
    public void onDisable() {
        endRoundCleanup(true);
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (p.getGameMode() == GameMode.CREATIVE && buildModePlayers.contains(p.getUniqueId())) {
                p.setGameMode(GameMode.SURVIVAL);
            }
        }
        saveData();
    }

    private void sendActionBar(Player p, String msg) {
        p.spigot().sendMessage(ChatMessageType.ACTION_BAR, TextComponent.fromLegacyText(msg));
    }

    // ================= DATA PERSISTENCE =================

    private void loadData() {
        if (!getDataFolder().exists()) getDataFolder().mkdirs();
        dataFile = new File(getDataFolder(), "bombrush_data.yml");
        if (!dataFile.exists()) return;
        dataConfig = YamlConfiguration.loadConfiguration(dataFile);

        ConfigurationSection mSec = dataConfig.getConfigurationSection("maps");
        if (mSec != null) {
            for (String key : mSec.getKeys(false)) {
                ConfigurationSection sub = mSec.getConfigurationSection(key);
                if (sub == null) continue;
                BombMap bm = new BombMap(key.toLowerCase(Locale.ROOT));
                bm.plantersSpawn = readBounds(sub.getConfigurationSection("planters_spawn"));
                bm.defusersSpawn = readBounds(sub.getConfigurationSection("defusers_spawn"));
                ConfigurationSection sitesSec = sub.getConfigurationSection("bomb_sites");
                if (sitesSec != null) {
                    for (String idx : sitesSec.getKeys(false)) {
                        Bounds b = readBounds(sitesSec.getConfigurationSection(idx));
                        if (b.min != null && b.max != null) bm.bombSites.add(b);
                    }
                }
                maps.put(bm.name, bm);
            }
        }

        ConfigurationSection pSec = dataConfig.getConfigurationSection("players");
        if (pSec != null) {
            for (String uuidStr : pSec.getKeys(false)) {
                try {
                    UUID uid = UUID.fromString(uuidStr);
                    PlayerProfile prof = new PlayerProfile();
                    prof.coins = pSec.getInt(uuidStr + ".coins", 100);
                    prof.tracerParticles = pSec.getBoolean(uuidStr + ".tracers", true);
                    prof.hitSound = pSec.getBoolean(uuidStr + ".hitsound", true);
                    List<String> gunList = pSec.getStringList(uuidStr + ".unlocked_guns");
                    for (String g : gunList) {
                        WeaponId w = WeaponId.fromId(g);
                        if (w != null && w.isGun()) prof.unlockedGuns.add(w);
                    }
                    profiles.put(uid, prof);
                } catch (Exception ignored) {
                }
            }
        }
    }

    private void saveData() {
        if (!getDataFolder().exists()) getDataFolder().mkdirs();
        if (dataFile == null) dataFile = new File(getDataFolder(), "bombrush_data.yml");
        dataConfig = new YamlConfiguration();

        ConfigurationSection mSec = dataConfig.createSection("maps");
        for (BombMap bm : maps.values()) {
            ConfigurationSection sub = mSec.createSection(bm.name);
            writeBounds(sub.createSection("planters_spawn"), bm.plantersSpawn);
            writeBounds(sub.createSection("defusers_spawn"), bm.defusersSpawn);
            ConfigurationSection sitesSec = sub.createSection("bomb_sites");
            for (int i = 0; i < bm.bombSites.size(); i++) {
                writeBounds(sitesSec.createSection(String.valueOf(i)), bm.bombSites.get(i));
            }
        }

        ConfigurationSection pSec = dataConfig.createSection("players");
        for (Map.Entry<UUID, PlayerProfile> e : profiles.entrySet()) {
            ConfigurationSection sub = pSec.createSection(e.getKey().toString());
            sub.set("coins", e.getValue().coins);
            sub.set("tracers", e.getValue().tracerParticles);
            sub.set("hitsound", e.getValue().hitSound);
            List<String> gunNames = new ArrayList<>();
            for (WeaponId w : e.getValue().unlockedGuns) gunNames.add(w.id);
            sub.set("unlocked_guns", gunNames);
        }

        try {
            dataConfig.save(dataFile);
        } catch (IOException ex) {
            getLogger().warning("Could not save bombrush_data.yml: " + ex.getMessage());
        }
    }

    private void writeBounds(ConfigurationSection sec, Bounds b) {
        if (b.min == null || b.max == null || b.min.getWorld() == null) return;
        sec.set("world", b.min.getWorld().getName());
        sec.set("x1", b.min.getX());
        sec.set("y1", b.min.getY());
        sec.set("z1", b.min.getZ());
        sec.set("x2", b.max.getX());
        sec.set("y2", b.max.getY());
        sec.set("z2", b.max.getZ());
    }

    private Bounds readBounds(ConfigurationSection sec) {
        Bounds b = new Bounds();
        if (sec == null) return b;
        String wName = sec.getString("world");
        World w = (wName != null) ? Bukkit.getWorld(wName) : null;
        if (w == null) return b;
        b.min = new Location(w, sec.getDouble("x1"), sec.getDouble("y1"), sec.getDouble("z1"));
        b.max = new Location(w, sec.getDouble("x2"), sec.getDouble("y2"), sec.getDouble("z2"));
        return b;
    }

    private PlayerProfile profile(UUID uid) {
        return profiles.computeIfAbsent(uid, k -> new PlayerProfile());
    }

    // ================= COMMANDS =================

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0 || args[0].equalsIgnoreCase("help")) {
            sender.sendMessage(ChatColor.GOLD + "=== BombRush Commands (No Lobby) ===");
            sender.sendMessage(ChatColor.YELLOW + "/br round" + ChatColor.GRAY + " - Start a new random-map round (OP)");
            sender.sendMessage(ChatColor.YELLOW + "/br stop" + ChatColor.GRAY + " - Stop current round (OP)");
            sender.sendMessage(ChatColor.YELLOW + "/br build" + ChatColor.GRAY + " - Toggle Creative build mode (OP only)");
            sender.sendMessage(ChatColor.YELLOW + "/br map <create|setplanters|setdefusers|addsite|list> <map>" + ChatColor.GRAY + " - Set spawns & bomb sites via WorldEdit (OP)");
            sender.sendMessage(ChatColor.YELLOW + "/br shop" + ChatColor.GRAY + " - List unlockable guns & coin prices");
            sender.sendMessage(ChatColor.YELLOW + "/br buy <gun>" + ChatColor.GRAY + " - Unlock a gun with coins for your round pool");
            sender.sendMessage(ChatColor.YELLOW + "/br coins" + ChatColor.GRAY + " - Check your coins");
            sender.sendMessage(ChatColor.YELLOW + "/br setting <tracers|hitsound> <on|off>" + ChatColor.GRAY + " - Edit personal settings");
            return true;
        }

        String sub = args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "build" -> {
                if (!(sender instanceof Player p) || !p.isOp()) {
                    sender.sendMessage(ChatColor.RED + "Only OPs can enter build mode!");
                    return true;
                }
                UUID uid = p.getUniqueId();
                if (buildModePlayers.remove(uid)) {
                    p.setGameMode(GameMode.SURVIVAL);
                    p.sendMessage(ChatColor.YELLOW + "Exited Build Mode (Survival).");
                } else {
                    buildModePlayers.add(uid);
                    p.setGameMode(GameMode.CREATIVE);
                    p.sendMessage(ChatColor.GREEN + "Entered OP Build Mode (Creative)! Select regions with WorldEdit and use /br map ...");
                }
            }
            case "map" -> handleMapCommand(sender, args);
            case "round" -> {
                if (!sender.isOp()) {
                    sender.sendMessage(ChatColor.RED + "OP required to start a round.");
                    return true;
                }
                startRandomRound();
            }
            case "stop" -> {
                if (!sender.isOp()) {
                    sender.sendMessage(ChatColor.RED + "OP required to stop a round.");
                    return true;
                }
                endRoundCleanup(true);
                Bukkit.broadcastMessage(ChatColor.RED + "[BombRush] Round stopped.");
            }
            case "coins" -> {
                if (sender instanceof Player p) {
                    p.sendMessage(ChatColor.GOLD + "Coins: " + ChatColor.YELLOW + profile(p.getUniqueId()).coins);
                }
            }
            case "shop" -> {
                if (!(sender instanceof Player p)) return true;
                PlayerProfile prof = profile(p.getUniqueId());
                p.sendMessage(ChatColor.GOLD + "=== BombRush Gun Shop (Coins: " + prof.coins + ") ===");
                for (WeaponId w : WeaponId.values()) {
                    if (w.starter || !w.isGun()) continue;
                    boolean owned = prof.unlockedGuns.contains(w);
                    String status = owned ? (ChatColor.GREEN + "[UNLOCKED]") : ("" + ChatColor.YELLOW + w.cost + " coins");
                    String tag = w.slotType == WeaponSlotType.PRIMARY ? "[Primary]" : "[Secondary]";
                    p.sendMessage(ChatColor.AQUA + w.id + ChatColor.GRAY + " (" + w.displayName + " " + tag + ") - " + status);
                }
                p.sendMessage(ChatColor.GRAY + "Use " + ChatColor.YELLOW + "/br buy <gun>" + ChatColor.GRAY + " to add it to your round gun pool.");
            }
            case "buy" -> {
                if (!(sender instanceof Player p)) return true;
                if (args.length < 2) {
                    p.sendMessage(ChatColor.RED + "Usage: /br buy <gun>");
                    return true;
                }
                WeaponId w = WeaponId.fromId(args[1]);
                if (w == null || w.starter || !w.isGun()) {
                    p.sendMessage(ChatColor.RED + "Unknown or non-purchasable gun.");
                    return true;
                }
                PlayerProfile prof = profile(p.getUniqueId());
                if (prof.unlockedGuns.contains(w)) {
                    p.sendMessage(ChatColor.GREEN + "You already unlocked " + w.displayName + "!");
                    return true;
                }
                if (prof.coins < w.cost) {
                    p.sendMessage(ChatColor.RED + "Not enough coins! Need " + w.cost + ", have " + prof.coins + ".");
                    return true;
                }
                prof.coins -= w.cost;
                prof.unlockedGuns.add(w);
                saveData();
                p.sendMessage(ChatColor.GREEN + "Unlocked " + w.displayName + "! Added to your " + w.slotType.name().toLowerCase(Locale.ROOT) + " round pool.");
            }
            case "setting" -> {
                if (!(sender instanceof Player p)) return true;
                if (args.length < 3) {
                    p.sendMessage(ChatColor.YELLOW + "Usage: /br setting <tracers|hitsound> <on|off>");
                    return true;
                }
                boolean val = args[2].equalsIgnoreCase("on") || args[2].equalsIgnoreCase("true");
                PlayerProfile prof = profile(p.getUniqueId());
                if (args[1].equalsIgnoreCase("tracers")) {
                    prof.tracerParticles = val;
                    saveData();
                    p.sendMessage(ChatColor.GREEN + "Tracer particles set to: " + (val ? "ON" : "OFF"));
                } else if (args[1].equalsIgnoreCase("hitsound")) {
                    prof.hitSound = val;
                    saveData();
                    p.sendMessage(ChatColor.GREEN + "Hit sound set to: " + (val ? "ON" : "OFF"));
                } else {
                    p.sendMessage(ChatColor.RED + "Unknown setting. Use tracers or hitsound.");
                }
            }
            default -> sender.sendMessage(ChatColor.RED + "Unknown command. Use /br help.");
        }
        return true;
    }

    private void handleMapCommand(CommandSender sender, String[] args) {
        if (!(sender instanceof Player p) || !p.isOp()) {
            sender.sendMessage(ChatColor.RED + "Only OPs can configure maps!");
            return;
        }
        if (args.length < 2) {
            p.sendMessage(ChatColor.YELLOW + "Usage: /br map <create|setplanters|setdefusers|addsite|list> <name>");
            return;
        }
        String action = args[1].toLowerCase(Locale.ROOT);
        if (action.equals("list")) {
            p.sendMessage(ChatColor.GOLD + "Maps (" + maps.size() + "): " + String.join(", ", maps.keySet()));
            return;
        }
        if (args.length < 3) {
            p.sendMessage(ChatColor.RED + "Specify a map name.");
            return;
        }
        String mapName = args[2].toLowerCase(Locale.ROOT);
        BombMap bm = maps.computeIfAbsent(mapName, BombMap::new);

        if (action.equals("create")) {
            saveData();
            p.sendMessage(ChatColor.GREEN + "Created map '" + mapName + "'. Select regions with WorldEdit and set planters/defusers spawns & bomb sites.");
            return;
        }

        Bounds sel = getWorldEditSelection(p);
        if (sel == null) {
            p.sendMessage(ChatColor.RED + "No WorldEdit selection found! Use //wand and select a region first.");
            return;
        }

        switch (action) {
            case "setplanters" -> {
                bm.plantersSpawn = sel;
                saveData();
                p.sendMessage(ChatColor.GREEN + "Saved Planters spawn area for '" + mapName + "' from WorldEdit!");
            }
            case "setdefusers" -> {
                bm.defusersSpawn = sel;
                saveData();
                p.sendMessage(ChatColor.GREEN + "Saved Defusers spawn area for '" + mapName + "' from WorldEdit!");
            }
            case "addsite" -> {
                bm.bombSites.add(sel);
                saveData();
                p.sendMessage(ChatColor.GREEN + "Added Bomb Site #" + bm.bombSites.size() + " to '" + mapName + "' from WorldEdit!");
            }
            default -> p.sendMessage(ChatColor.YELLOW + "Usage: /br map <create|setplanters|setdefusers|addsite|list> <name>");
        }
    }

    private Bounds getWorldEditSelection(Player player) {
        Plugin we = Bukkit.getPluginManager().getPlugin("WorldEdit");
        if (we == null) we = Bukkit.getPluginManager().getPlugin("FastAsyncWorldEdit");
        if (we == null) return null;
        try {
            Class<?> adapterCls = Class.forName("com.sk89q.worldedit.bukkit.BukkitAdapter");
            Object wePlayer = adapterCls.getMethod("adapt", Player.class).invoke(null, player);
            Object weWorld = adapterCls.getMethod("adapt", World.class).invoke(null, player.getWorld());

            Class<?> weCls = Class.forName("com.sk89q.worldedit.WorldEdit");
            Object weInstance = weCls.getMethod("getInstance").invoke(null);
            Object sessionMgr = weCls.getMethod("getSessionManager").invoke(weInstance);
            Object session = sessionMgr.getClass().getMethod("getIfPresent", Class.forName("com.sk89q.worldedit.session.SessionOwner")).invoke(sessionMgr, wePlayer);
            if (session == null) return null;

            Object selection = session.getClass().getMethod("getSelection", Class.forName("com.sk89q.worldedit.world.World")).invoke(session, weWorld);
            if (selection == null) return null;

            Object minPt = selection.getClass().getMethod("getMinimumPoint").invoke(selection);
            Object maxPt = selection.getClass().getMethod("getMaximumPoint").invoke(selection);
            Location minLoc = (Location) adapterCls.getMethod("adapt", World.class, Class.forName("com.sk89q.worldedit.math.BlockVector3")).invoke(null, player.getWorld(), minPt);
            Location maxLoc = (Location) adapterCls.getMethod("adapt", World.class, Class.forName("com.sk89q.worldedit.math.BlockVector3")).invoke(null, player.getWorld(), maxPt);

            Bounds b = new Bounds();
            b.min = minLoc;
            b.max = maxLoc;
            return b;
        } catch (Exception ignored) {
            return null;
        }
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            return Arrays.asList("round", "stop", "build", "map", "shop", "buy", "coins", "setting", "help");
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("map")) {
            return Arrays.asList("create", "setplanters", "setdefusers", "addsite", "list");
        }
        if (args.length == 3 && args[0].equalsIgnoreCase("map")) {
            return new ArrayList<>(maps.keySet());
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("buy")) {
            List<String> out = new ArrayList<>();
            for (WeaponId w : WeaponId.values()) {
                if (!w.starter && w.isGun()) {
                    out.add(w.id);
                }
            }
            return out;
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("setting")) {
            return Arrays.asList("tracers", "hitsound");
        }
        if (args.length == 3 && args[0].equalsIgnoreCase("setting")) {
            return Arrays.asList("on", "off");
        }
        return Collections.emptyList();
    }

    // ================= RANDOM ROUND & LOADOUTS =================

    public void startRandomRound() {
        endRoundCleanup(false);

        List<BombMap> readyMaps = new ArrayList<>();
        for (BombMap bm : maps.values()) {
            if (bm.isReady()) readyMaps.add(bm);
        }
        if (readyMaps.isEmpty()) {
            Bukkit.broadcastMessage(ChatColor.RED + "[BombRush] No ready maps found! Use /br build and /br map to set planters spawn, defusers spawn, and at least one bomb site.");
            return;
        }

        List<Player> participants = new ArrayList<>();
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (!buildModePlayers.contains(p.getUniqueId())) {
                participants.add(p);
            }
        }
        if (participants.isEmpty()) {
            Bukkit.broadcastMessage(ChatColor.RED + "[BombRush] No non-building players online to start a round.");
            return;
        }

        Collections.shuffle(participants);
        currentMap = readyMaps.get(ThreadLocalRandom.current().nextInt(readyMaps.size()));
        roundActive = true;
        roundNumber++;
        bombPlanted = false;
        plantedBombLoc = null;
        bombFuseSecondsLeft = 40;

        playerTeam.clear();
        bombCarrierId = null;

        List<Player> planters = new ArrayList<>();
        List<Player> defusers = new ArrayList<>();

        for (int i = 0; i < participants.size(); i++) {
            Player p = participants.get(i);
            p.getInventory().clear();
            p.setHealth(20.0);
            p.setFoodLevel(20);

            if (i % 2 == 0) {
                playerTeam.put(p.getUniqueId(), "PLANTERS");
                planters.add(p);
                Location sp = currentMap.plantersSpawn.randomPoint(0.2);
                if (sp != null) p.teleport(sp);
            } else {
                playerTeam.put(p.getUniqueId(), "DEFUSERS");
                defusers.add(p);
                Location sp = currentMap.defusersSpawn.randomPoint(0.2);
                if (sp != null) p.teleport(sp);
            }
        }

        if (planters.isEmpty()) {
            Player promoted = defusers.remove(0);
            playerTeam.put(promoted.getUniqueId(), "PLANTERS");
            planters.add(promoted);
            Location sp = currentMap.plantersSpawn.randomPoint(0.2);
            if (sp != null) promoted.teleport(sp);
        }
        Player carrier = planters.get(ThreadLocalRandom.current().nextInt(planters.size()));
        bombCarrierId = carrier.getUniqueId();

        List<WeaponId> throwables = Arrays.asList(
                WeaponId.GRENADE,
                WeaponId.SMOKE_GRENADE,
                WeaponId.FLASHBANG,
                WeaponId.MOLOTOV
        );

        for (Player p : participants) {
            PlayerProfile prof = profile(p.getUniqueId());

            List<WeaponId> ownedPrimaries = new ArrayList<>();
            List<WeaponId> ownedSecondaries = new ArrayList<>();
            for (WeaponId g : prof.unlockedGuns) {
                if (g.slotType == WeaponSlotType.PRIMARY) ownedPrimaries.add(g);
                else if (g.slotType == WeaponSlotType.SECONDARY) ownedSecondaries.add(g);
            }

            if (ownedPrimaries.isEmpty()) {
                for (WeaponId w : WeaponId.values()) {
                    if (w.starter && w.slotType == WeaponSlotType.PRIMARY) ownedPrimaries.add(w);
                }
            }
            if (ownedSecondaries.isEmpty()) {
                for (WeaponId w : WeaponId.values()) {
                    if (w.starter && w.slotType == WeaponSlotType.SECONDARY) ownedSecondaries.add(w);
                }
            }

            WeaponId chosenPrimary = ownedPrimaries.get(ThreadLocalRandom.current().nextInt(ownedPrimaries.size()));
            WeaponId chosenSecondary = ownedSecondaries.get(ThreadLocalRandom.current().nextInt(ownedSecondaries.size()));
            WeaponId chosenThrowable = throwables.get(ThreadLocalRandom.current().nextInt(throwables.size()));

            p.getInventory().addItem(buildWeaponItem(chosenPrimary));
            p.getInventory().addItem(buildWeaponItem(chosenSecondary));
            p.getInventory().addItem(buildWeaponItem(WeaponId.KNIFE));
            p.getInventory().addItem(buildWeaponItem(chosenThrowable));

            if (p.getUniqueId().equals(bombCarrierId)) {
                p.getInventory().addItem(buildBombItem());
                p.sendMessage(ChatColor.RED + "" + ChatColor.BOLD + "YOU HAVE THE C4 BOMB! Right-click a surface in a Bomb Site (5s plant)!");
            }

            String team = playerTeam.get(p.getUniqueId());
            ChatColor tColor = "PLANTERS".equals(team) ? ChatColor.RED : ChatColor.AQUA;
            p.sendMessage(ChatColor.GOLD + "Round " + roundNumber + " on " + ChatColor.YELLOW + currentMap.name
                    + ChatColor.GOLD + " | Team: " + tColor + team
                    + ChatColor.GRAY + " (" + chosenPrimary.displayName + " + " + chosenSecondary.displayName + " + " + chosenThrowable.displayName + ")");
        }

        Bukkit.broadcastMessage(ChatColor.GOLD + "[BombRush] Round " + roundNumber + " started on random map '" + currentMap.name + "'!");
    }

    private ItemStack buildWeaponItem(WeaponId w) {
        ItemStack stack = new ItemStack(w.baseMaterial, 1);
        ItemMeta meta = stack.getItemMeta();
        if (meta != null) {
            if (w.isGun()) {
                meta.setDisplayName(ChatColor.YELLOW + w.displayName + ChatColor.GRAY + " (" + w.magSize + "/" + w.magSize + ")");
            } else {
                meta.setDisplayName(ChatColor.YELLOW + w.displayName);
            }

            if (w == WeaponId.MOLOTOV && meta instanceof PotionMeta pm) {
                pm.setColor(Color.fromRGB(16, 78, 32));
            } else if (w.uniqueItemModel != null) {
                meta.setItemModel(NamespacedKey.minecraft(w.uniqueItemModel));
            }

            List<String> lore = new ArrayList<>();
            if (w.isGun()) {
                lore.add(ChatColor.GRAY + (w.slotType == WeaponSlotType.PRIMARY ? "Primary Gun" : "Secondary Gun"));
                lore.add(ChatColor.GRAY + "Press F (Swap Hands) or Left-Click to Reload");
                meta.getPersistentDataContainer().set(keyGunMag, PersistentDataType.INTEGER, w.magSize);
                meta.getPersistentDataContainer().set(keyGunReserve, PersistentDataType.INTEGER, w.reserveAmmo);
            } else if (w.isGrenade()) {
                lore.add(ChatColor.GRAY + "Right-click to prime (5s fuse), click again to throw (1 per round)");
            } else {
                lore.add(ChatColor.GRAY + "Melee Weapon");
            }
            if (w == WeaponId.SNIPER) lore.add(ChatColor.AQUA + "Right-click to ADS (Spyglass), Left-click to Shoot");
            if (w == WeaponId.HARPOON_GUN) lore.add(ChatColor.AQUA + "Crossbow Harpoon Launcher");
            meta.setLore(lore);
            meta.getPersistentDataContainer().set(keyWeaponId, PersistentDataType.STRING, w.id);
            stack.setItemMeta(meta);
        }
        return stack;
    }

    private ItemStack buildBombItem() {
        ItemStack stack = new ItemStack(Material.GREEN_GLAZED_TERRACOTTA, 1);
        ItemMeta meta = stack.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(ChatColor.RED + "" + ChatColor.BOLD + "C4 Charge");
            meta.setItemModel(NamespacedKey.minecraft("green_glazed_terracotta"));
            meta.setLore(Collections.singletonList(ChatColor.GRAY + "Right-click a floor or wall inside a Bomb Site (5s to plant)"));
            meta.getPersistentDataContainer().set(keyBombItem, PersistentDataType.BYTE, (byte) 1);
            stack.setItemMeta(meta);
        }
        return stack;
    }

    // ================= AMMO & RELOAD HELPERS =================

    private int getMag(ItemStack item, WeaponId w) {
        if (item == null || !item.hasItemMeta()) return w.magSize;
        Integer val = item.getItemMeta().getPersistentDataContainer().get(keyGunMag, PersistentDataType.INTEGER);
        return (val != null) ? val : w.magSize;
    }

    private int getReserve(ItemStack item, WeaponId w) {
        if (item == null || !item.hasItemMeta()) return w.reserveAmmo;
        Integer val = item.getItemMeta().getPersistentDataContainer().get(keyGunReserve, PersistentDataType.INTEGER);
        return (val != null) ? val : w.reserveAmmo;
    }

    private void setAmmo(ItemStack item, WeaponId w, int mag, int reserve) {
        if (item == null || !item.hasItemMeta()) return;
        ItemMeta meta = item.getItemMeta();
        PersistentDataContainer pdc = meta.getPersistentDataContainer();
        pdc.set(keyGunMag, PersistentDataType.INTEGER, Math.max(0, mag));
        pdc.set(keyGunReserve, PersistentDataType.INTEGER, Math.max(0, reserve));
        meta.setDisplayName(ChatColor.YELLOW + w.displayName + ChatColor.GRAY + " (" + Math.max(0, mag) + "/" + w.magSize + ")");
        item.setItemMeta(meta);
    }

    private void startReload(Player p, ItemStack gunItem, WeaponId w) {
        if (gunItem == null || w == null || !w.isGun()) return;
        UUID uid = p.getUniqueId();
        if (reloadingPlayers.contains(uid)) return;

        int curMag = getMag(gunItem, w);
        int curRes = getReserve(gunItem, w);

        if (curMag >= w.magSize) {
            p.sendMessage(ChatColor.GRAY + w.displayName + " magazine is already full!");
            return;
        }
        if (curRes <= 0) {
            p.playSound(p.getLocation(), Sound.BLOCK_DISPENSER_FAIL, 1.0f, 1.5f);
            p.sendMessage(ChatColor.RED + "No reserve ammo left for " + w.displayName + "!");
            return;
        }

        reloadingPlayers.add(uid);
        int heldSlot = p.getInventory().getHeldItemSlot();
        p.playSound(p.getLocation(), Sound.ITEM_ARMOR_EQUIP_IRON, 1.0f, 1.2f);
        p.sendMessage(ChatColor.YELLOW + "Reloading " + w.displayName + " (" + String.format("%.1fs", w.reloadTicks / 20.0) + ")...");

        new BukkitRunnable() {
            int elapsed = 0;

            @Override
            public void run() {
                if (!p.isOnline() || !reloadingPlayers.contains(uid)) {
                    reloadingPlayers.remove(uid);
                    cancel();
                    return;
                }
                // Cancel reload if player switched slots
                if (p.getInventory().getHeldItemSlot() != heldSlot) {
                    reloadingPlayers.remove(uid);
                    p.sendMessage(ChatColor.RED + "Reload cancelled (switched item).");
                    cancel();
                    return;
                }
                elapsed += 2;
                if (elapsed >= w.reloadTicks) {
                    reloadingPlayers.remove(uid);
                    ItemStack current = p.getInventory().getItem(heldSlot);
                    if (current != null && getWeapon(current) == w) {
                        int m = getMag(current, w);
                        int r = getReserve(current, w);
                        int needed = w.magSize - m;
                        int toLoad = Math.min(needed, r);
                        setAmmo(current, w, m + toLoad, r - toLoad);
                        p.playSound(p.getLocation(), Sound.BLOCK_IRON_TRAPDOOR_CLOSE, 1.0f, 1.4f);
                        p.sendMessage(ChatColor.GREEN + w.displayName + " reloaded! (" + (m + toLoad) + "/" + w.magSize + " | Reserve: " + (r - toLoad) + ")");
                    }
                    cancel();
                }
            }
        }.runTaskTimer(this, 2L, 2L);
    }

    @EventHandler
    public void onSwapHandsReload(PlayerSwapHandItemsEvent event) {
        Player p = event.getPlayer();
        ItemStack main = event.getOffHandItem(); // Bukkit swaps before event completes
        WeaponId w = getWeapon(main);
        if (w != null && w.isGun()) {
            event.setCancelled(true);
            startReload(p, p.getInventory().getItemInMainHand(), w);
        }
    }

    // ================= C4 BOMB (GREEN GLAZED TERRACOTTA) & 5s PLANT / 10s DEFUSE =================

    private void spawnC4BlockDisplay(Location surfaceCenter, BlockFace face) {
        World w = surfaceCenter.getWorld();
        if (w == null) return;
        BlockData c4Data = Bukkit.createBlockData(Material.GREEN_GLAZED_TERRACOTTA);
        BlockDisplay display = (BlockDisplay) w.spawnEntity(surfaceCenter, EntityType.BLOCK_DISPLAY);
        display.setBlock(c4Data);

        Vector3f scale = new Vector3f(0.65f, 0.22f, 0.45f);
        Vector3f translation;
        Quaternionf rotation = new Quaternionf();

        switch (face) {
            case UP -> translation = new Vector3f(-scale.x / 2f, 0.0f, -scale.z / 2f);
            case DOWN -> translation = new Vector3f(-scale.x / 2f, -scale.y, -scale.z / 2f);
            case NORTH -> {
                rotation.rotateX((float) (-Math.PI / 2.0));
                translation = new Vector3f(-scale.x / 2f, scale.z / 2f, -scale.y);
            }
            case SOUTH -> {
                rotation.rotateX((float) (Math.PI / 2.0));
                translation = new Vector3f(-scale.x / 2f, -scale.z / 2f, 0.0f);
            }
            case WEST -> {
                rotation.rotateZ((float) (Math.PI / 2.0));
                translation = new Vector3f(0.0f, -scale.x / 2f, -scale.z / 2f);
            }
            case EAST -> {
                rotation.rotateZ((float) (-Math.PI / 2.0));
                translation = new Vector3f(-scale.y, -scale.x / 2f, -scale.z / 2f);
            }
            default -> translation = new Vector3f(-scale.x / 2f, 0.0f, -scale.z / 2f);
        }

        display.setTransformation(new Transformation(
                translation,
                rotation,
                scale,
                new Quaternionf()
        ));
        display.getPersistentDataContainer().set(keyBombDisplay, PersistentDataType.BYTE, (byte) 1);
        plantedBombDisplayId = display.getUniqueId();
    }

    private boolean isInsideAnyBombSite(Location loc) {
        if (currentMap == null) return false;
        for (Bounds site : currentMap.bombSites) {
            if (site.contains(loc)) return true;
        }
        return false;
    }

    private void startPlantingC4(Player planter, Block clickedBlock, BlockFace clickedFace) {
        if (bombPlanted || !roundActive) return;
        UUID uid = planter.getUniqueId();
        if (currentlyPlanting.contains(uid)) return;

        Location targetSurface;
        BlockFace finalFace;
        if (clickedBlock != null && clickedFace != null && clickedBlock.getType().isSolid()) {
            finalFace = clickedFace;
            targetSurface = clickedBlock.getLocation().add(
                    0.5 + clickedFace.getModX() * 0.5,
                    0.5 + clickedFace.getModY() * 0.5,
                    0.5 + clickedFace.getModZ() * 0.5
            );
        } else {
            finalFace = BlockFace.UP;
            Block below = planter.getLocation().getBlock().getRelative(BlockFace.DOWN);
            double groundY = below.getType().isSolid() ? (below.getY() + 1.0) : planter.getLocation().getY();
            targetSurface = new Location(planter.getWorld(), planter.getLocation().getX(), groundY, planter.getLocation().getZ());
        }

        if (!isInsideAnyBombSite(planter.getLocation()) && !isInsideAnyBombSite(targetSurface)) {
            planter.sendMessage(ChatColor.RED + "You must be inside a Bomb Site to plant the C4!");
            return;
        }

        currentlyPlanting.add(uid);
        Location startLoc = planter.getLocation().clone();
        planter.sendMessage(ChatColor.GOLD + "Planting C4... Hold still for 5 seconds!");
        planter.playSound(planter.getLocation(), Sound.BLOCK_NOTE_BLOCK_HAT, 1.2f, 1.0f);

        new BukkitRunnable() {
            int ticks = 0;

            @Override
            public void run() {
                if (!roundActive || bombPlanted || !planter.isOnline() || planter.isDead()) {
                    currentlyPlanting.remove(uid);
                    cancel();
                    return;
                }
                if (planter.getLocation().distanceSquared(startLoc) > 2.25) {
                    currentlyPlanting.remove(uid);
                    planter.sendMessage(ChatColor.RED + "Plant cancelled! Stay still while planting C4.");
                    cancel();
                    return;
                }
                if (!isBombItem(planter.getInventory().getItemInMainHand())) {
                    currentlyPlanting.remove(uid);
                    planter.sendMessage(ChatColor.RED + "Plant cancelled! Keep the C4 in your hand.");
                    cancel();
                    return;
                }

                ticks += 5;
                if (ticks % 20 == 0) {
                    int secLeft = Math.max(0, (100 - ticks) / 20);
                    planter.sendMessage(ChatColor.YELLOW + "Planting C4... " + secLeft + "s");
                    planter.playSound(planter.getLocation(), Sound.BLOCK_NOTE_BLOCK_HAT, 1.0f, 1.2f);
                }

                if (ticks >= 100) {
                    currentlyPlanting.remove(uid);
                    finishPlantC4(planter, targetSurface, finalFace);
                    cancel();
                }
            }
        }.runTaskTimer(this, 5L, 5L);
    }

    private void finishPlantC4(Player planter, Location surfaceLoc, BlockFace face) {
        if (bombPlanted || !roundActive) return;

        for (ItemStack item : planter.getInventory().getContents()) {
            if (isBombItem(item)) {
                item.setAmount(item.getAmount() - 1);
                break;
            }
        }

        bombPlanted = true;
        plantedBombLoc = surfaceLoc.clone();
        spawnC4BlockDisplay(plantedBombLoc, face);
        rewardCoins(planter.getUniqueId(), 50, "Planting C4");

        Bukkit.broadcastMessage(ChatColor.RED + "" + ChatColor.BOLD + "[BombRush] C4 PLANTED by " + planter.getName() + "! Defusers have " + bombFuseSecondsLeft + "s!");
        plantedBombLoc.getWorld().playSound(plantedBombLoc, Sound.BLOCK_NOTE_BLOCK_PLING, 2.0f, 1.8f);

        bombFuseTask = new BukkitRunnable() {
            @Override
            public void run() {
                if (!roundActive || !bombPlanted) {
                    cancel();
                    return;
                }
                bombFuseSecondsLeft--;
                if (plantedBombLoc != null && plantedBombLoc.getWorld() != null) {
                    plantedBombLoc.getWorld().playSound(plantedBombLoc, Sound.BLOCK_NOTE_BLOCK_BIT, 1.2f, bombFuseSecondsLeft <= 5 ? 2.0f : 1.0f);
                    plantedBombLoc.getWorld().spawnParticle(Particle.FLAME, plantedBombLoc.clone().add(0, 0.25, 0), 4, 0.08, 0.08, 0.08, 0.01);
                }
                if (bombFuseSecondsLeft <= 0) {
                    detonateC4();
                    cancel();
                }
            }
        };
        bombFuseTask.runTaskTimer(this, 20L, 20L);
    }

    private void detonateC4() {
        if (!bombPlanted) return;
        bombPlanted = false;
        if (plantedBombLoc != null && plantedBombLoc.getWorld() != null) {
            plantedBombLoc.getWorld().createExplosion(plantedBombLoc, 4.5f, false, false);
        }
        awardTeamCoins("PLANTERS", 75, "C4 Detonation Win");
        Bukkit.broadcastMessage(ChatColor.RED + "" + ChatColor.BOLD + "[BombRush] C4 DETONATED! PLANTERS WIN THE ROUND!");
        endRoundCleanup(true);
    }

    private void openWiringDefusalGui(Player defuser) {
        if (!bombPlanted || !"DEFUSERS".equals(playerTeam.get(defuser.getUniqueId()))) return;
        if (plantedBombLoc != null && defuser.getLocation().distanceSquared(plantedBombLoc) > 25.0) {
            defuser.sendMessage(ChatColor.RED + "Get closer to the planted C4 to defuse!");
            return;
        }

        Inventory inv = Bukkit.createInventory(null, 27, DEFUSE_GUI_TITLE);
        ChatColor[] colors = {ChatColor.RED, ChatColor.BLUE, ChatColor.YELLOW};
        Material[] mats = {Material.RED_WOOL, Material.BLUE_WOOL, Material.YELLOW_WOOL};
        int[] slots = {11, 13, 15};
        int chosenIdx = ThreadLocalRandom.current().nextInt(slots.length);

        for (int i = 0; i < slots.length; i++) {
            ItemStack wire = new ItemStack(mats[i], 1);
            ItemMeta meta = wire.getItemMeta();
            if (meta != null) {
                meta.setDisplayName(colors[i] + "Cut " + mats[i].name().replace("_WOOL", "") + " Wire");
                meta.setLore(Collections.singletonList(ChatColor.GRAY + "Wait for the 10s wiring scan, then click!"));
                wire.setItemMeta(meta);
            }
            inv.setItem(slots[i], wire);
        }

        long readyAt = System.currentTimeMillis() + 10_000L;
        DefuseSession session = new DefuseSession(defuser.getUniqueId(), slots[chosenIdx], readyAt);
        activeDefuses.put(defuser.getUniqueId(), session);

        updateDefusePromptItem(inv, colors[chosenIdx], mats[chosenIdx], 10);
        defuser.openInventory(inv);

        new BukkitRunnable() {
            @Override
            public void run() {
                if (!bombPlanted || !defuser.isOnline() || !activeDefuses.containsKey(defuser.getUniqueId())) {
                    cancel();
                    return;
                }
                if (!defuser.getOpenInventory().getTitle().equals(DEFUSE_GUI_TITLE)) {
                    activeDefuses.remove(defuser.getUniqueId());
                    cancel();
                    return;
                }
                long remMs = session.readyToCutAtMillis - System.currentTimeMillis();
                int remSec = (int) Math.max(0, Math.ceil(remMs / 1000.0));
                updateDefusePromptItem(inv, colors[chosenIdx], mats[chosenIdx], remSec);
                if (remMs <= 0) {
                    session.unlocked = true;
                    defuser.playSound(defuser.getLocation(), Sound.BLOCK_NOTE_BLOCK_PLING, 1.2f, 1.7f);
                    cancel();
                }
            }
        }.runTaskTimer(this, 10L, 10L);
    }

    private void updateDefusePromptItem(Inventory inv, ChatColor wireColor, Material wireMat, int secondsLeft) {
        ItemStack prompt = new ItemStack(secondsLeft > 0 ? Material.CLOCK : Material.COMPARATOR, 1);
        ItemMeta pMeta = prompt.getItemMeta();
        if (pMeta != null) {
            if (secondsLeft > 0) {
                pMeta.setDisplayName(ChatColor.RED + "Tracing Circuit... " + secondsLeft + "s left");
                pMeta.setLore(Collections.singletonList(ChatColor.GRAY + "Do not cut before the timer reaches 0s!"));
            } else {
                pMeta.setDisplayName(ChatColor.GOLD + "READY! Cut Wire: " + wireColor + wireMat.name().replace("_WOOL", ""));
                pMeta.setLore(Collections.singletonList(ChatColor.GREEN + "Click the matching wire now!"));
            }
            prompt.setItemMeta(pMeta);
        }
        inv.setItem(4, prompt);
    }

    @EventHandler
    public void onDefuseGuiClick(InventoryClickEvent event) {
        if (!event.getView().getTitle().equals(DEFUSE_GUI_TITLE)) return;
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player p)) return;

        DefuseSession session = activeDefuses.get(p.getUniqueId());
        if (session == null || !bombPlanted) {
            p.closeInventory();
            return;
        }

        int slot = event.getRawSlot();
        if (slot != 11 && slot != 13 && slot != 15) return;

        if (!session.unlocked && System.currentTimeMillis() < session.readyToCutAtMillis) {
            p.sendMessage(ChatColor.RED + "Wait until the 10-second circuit trace finishes before cutting a wire!");
            p.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, 1.0f, 0.6f);
            return;
        }

        activeDefuses.remove(p.getUniqueId());
        p.closeInventory();

        if (slot == session.targetWireSlot) {
            bombPlanted = false;
            if (bombFuseTask != null) bombFuseTask.cancel();
            rewardCoins(p.getUniqueId(), 60, "Defusing C4");
            awardTeamCoins("DEFUSERS", 75, "C4 Defused Win");
            if (plantedBombLoc != null && plantedBombLoc.getWorld() != null) {
                plantedBombLoc.getWorld().playSound(plantedBombLoc, Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 1.5f, 1.4f);
            }
            Bukkit.broadcastMessage(ChatColor.AQUA + "" + ChatColor.BOLD + "[BombRush] C4 DEFUSED by " + p.getName() + "! DEFUSERS WIN THE ROUND!");
            endRoundCleanup(true);
        } else {
            p.sendMessage(ChatColor.RED + "Wrong wire! Spark shock!");
            p.damage(4.0);
            p.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, 1.2f, 0.5f);
        }
    }

    @EventHandler
    public void onDefuseGuiDrag(InventoryDragEvent event) {
        if (event.getView().getTitle().equals(DEFUSE_GUI_TITLE)) {
            event.setCancelled(true);
        }
    }

    // ================= WEAPON & GRENADE COMBAT =================

    private WeaponId getWeapon(ItemStack item) {
        if (item == null || !item.hasItemMeta()) return null;
        String id = item.getItemMeta().getPersistentDataContainer().get(keyWeaponId, PersistentDataType.STRING);
        return WeaponId.fromId(id);
    }

    private boolean isBombItem(ItemStack item) {
        if (item == null || !item.hasItemMeta()) return false;
        return item.getItemMeta().getPersistentDataContainer().has(keyBombItem, PersistentDataType.BYTE);
    }

    @EventHandler
    public void onBlockPlace(BlockPlaceEvent event) {
        if (isBombItem(event.getItemInHand())) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onEntityInteract(PlayerInteractEntityEvent event) {
        if (event.getRightClicked() instanceof BlockDisplay bd
                && bd.getPersistentDataContainer().has(keyBombDisplay, PersistentDataType.BYTE)) {
            event.setCancelled(true);
            openWiringDefusalGui(event.getPlayer());
        }
    }

    @EventHandler
    public void onPlayerInteract(PlayerInteractEvent event) {
        Player p = event.getPlayer();
        Action act = event.getAction();
        ItemStack hand = event.getItem();

        if (bombPlanted && plantedBombLoc != null && "DEFUSERS".equals(playerTeam.get(p.getUniqueId()))
                && (act == Action.RIGHT_CLICK_AIR || act == Action.RIGHT_CLICK_BLOCK)
                && p.getLocation().distanceSquared(plantedBombLoc) <= 20.0) {
            openWiringDefusalGui(p);
            return;
        }

        if (isBombItem(hand) && (act == Action.RIGHT_CLICK_AIR || act == Action.RIGHT_CLICK_BLOCK)) {
            event.setCancelled(true);
            startPlantingC4(p, event.getClickedBlock(), event.getBlockFace());
            return;
        }

        WeaponId w = getWeapon(hand);
        if (w == null) return;

        // Grenades: 1st right-click starts 5s timer; 2nd right-click throws; never explodes on impact
        if (w.isGrenade()) {
            if (act == Action.RIGHT_CLICK_AIR || act == Action.RIGHT_CLICK_BLOCK) {
                event.setCancelled(true);
                handleGrenadeInteract(p, hand, w);
            }
            return;
        }

        // Left-click reloads non-sniper guns; for Sniper, left-click shoots (or reloads if mag is 0)
        if (act == Action.LEFT_CLICK_AIR || act == Action.LEFT_CLICK_BLOCK) {
            if (w == WeaponId.SNIPER) {
                if (reloadingPlayers.contains(p.getUniqueId())) return;
                int mag = getMag(hand, w);
                if (mag <= 0) {
                    startReload(p, hand, w);
                    return;
                }
                long now = System.currentTimeMillis();
                long cd = fireCooldowns.getOrDefault(p.getUniqueId(), 0L);
                if (now < cd) return;
                fireCooldowns.put(p.getUniqueId(), now + 950L);
                setAmmo(hand, w, mag - 1, getReserve(hand, w));
                fireHitscan(p, w, 24.0, 90, 0.002);
                if (mag - 1 == 0 && getReserve(hand, w) > 0) {
                    startReload(p, hand, w);
                }
            } else if (w.isGun()) {
                startReload(p, hand, w);
            }
            return;
        }

        // Sniper right-click = ADS via spyglass
        if (w == WeaponId.SNIPER) {
            if (act == Action.RIGHT_CLICK_AIR || act == Action.RIGHT_CLICK_BLOCK) {
                p.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, 25, 2, false, false));
                p.playSound(p.getLocation(), Sound.ITEM_SPYGLASS_USE, 0.9f, 1.1f);
            }
            return;
        }

        if (hand.getType() == Material.SPYGLASS) {
            event.setCancelled(true);
            return;
        }

        if (act != Action.RIGHT_CLICK_AIR && act != Action.RIGHT_CLICK_BLOCK) return;
        if (!w.isGun()) return;

        if (reloadingPlayers.contains(p.getUniqueId())) return;

        long now = System.currentTimeMillis();
        long cd = fireCooldowns.getOrDefault(p.getUniqueId(), 0L);
        if (now < cd) return;

        int mag = getMag(hand, w);
        int res = getReserve(hand, w);
        if (mag <= 0) {
            startReload(p, hand, w);
            return;
        }

        switch (w) {
            case ASSAULT_RIFLE -> {
                fireCooldowns.put(p.getUniqueId(), now + 140L);
                setAmmo(hand, w, mag - 1, res);
                fireHitscan(p, w, 6.5, 55, 0.025);
            }
            case GLOCK -> {
                fireCooldowns.put(p.getUniqueId(), now + 190L);
                setAmmo(hand, w, mag - 1, res);
                fireHitscan(p, w, 5.0, 45, 0.02);
            }
            case DEAGLE -> {
                fireCooldowns.put(p.getUniqueId(), now + 420L);
                setAmmo(hand, w, mag - 1, res);
                fireHitscan(p, w, 12.5, 50, 0.015);
            }
            case TEC_9 -> {
                fireCooldowns.put(p.getUniqueId(), now + 110L);
                setAmmo(hand, w, mag - 1, res);
                fireHitscan(p, w, 4.5, 40, 0.045);
            }
            case REVOLVER -> {
                fireCooldowns.put(p.getUniqueId(), now + 480L);
                setAmmo(hand, w, mag - 1, res);
                fireHitscan(p, w, 13.5, 50, 0.012);
            }
            case BURST_RIFLE -> {
                fireCooldowns.put(p.getUniqueId(), now + 450L);
                int burstCount = Math.min(3, mag);
                setAmmo(hand, w, mag - burstCount, res);
                new BukkitRunnable() {
                    int shots = 0;

                    @Override
                    public void run() {
                        if (shots++ >= burstCount || !p.isOnline()) {
                            cancel();
                            return;
                        }
                        fireHitscan(p, w, 6.0, 55, 0.015);
                    }
                }.runTaskTimer(this, 0L, 2L);
            }
            case PUMP_SHOTGUN -> {
                fireCooldowns.put(p.getUniqueId(), now + 750L);
                setAmmo(hand, w, mag - 1, res);
                for (int i = 0; i < 7; i++) {
                    fireHitscan(p, w, 3.8, 22, 0.095);
                }
            }
            case SHORTY -> {
                fireCooldowns.put(p.getUniqueId(), now + 650L);
                setAmmo(hand, w, mag - 1, res);
                for (int i = 0; i < 5; i++) {
                    fireHitscan(p, w, 3.5, 16, 0.12);
                }
            }
            case HARPOON_GUN -> {
                fireCooldowns.put(p.getUniqueId(), now + 700L);
                setAmmo(hand, w, mag - 1, res);
                launchHarpoon(p);
            }
            default -> {
            }
        }

        int updatedMag = getMag(hand, w);
        if (updatedMag <= 0 && getReserve(hand, w) > 0) {
            startReload(p, hand, w);
        }
    }

    private void handleGrenadeInteract(Player p, ItemStack hand, WeaponId util) {
        PrimedGrenade existing = primedByPlayer.get(p.getUniqueId());

        if (existing == null || existing.detonated) {
            long detonateAt = System.currentTimeMillis() + 5000L;
            PrimedGrenade pg = new PrimedGrenade(p.getUniqueId(), util, detonateAt);
            pg.landedLoc = p.getLocation().clone();
            primedByPlayer.put(p.getUniqueId(), pg);

            p.sendMessage(ChatColor.RED + util.displayName + " primed! 5s fuse — interact again to throw!");
            p.playSound(p.getLocation(), Sound.BLOCK_TRIPWIRE_CLICK_ON, 1.2f, 1.4f);

            new BukkitRunnable() {
                @Override
                public void run() {
                    if (pg.detonated) {
                        cancel();
                        return;
                    }
                    if (!pg.thrown) {
                        Player owner = Bukkit.getPlayer(pg.ownerId);
                        if (owner != null && owner.isOnline()) {
                            pg.landedLoc = owner.getLocation().clone();
                        }
                    } else if (!pg.landed && pg.thrownEntity != null && pg.thrownEntity.isValid()) {
                        pg.landedLoc = pg.thrownEntity.getLocation().clone();
                    }

                    long remainingMs = pg.detonateAtMillis - System.currentTimeMillis();
                    if (remainingMs <= 0) {
                        triggerGrenadeEffect(pg);
                        cancel();
                    } else if (pg.landedLoc != null && pg.landedLoc.getWorld() != null) {
                        pg.landedLoc.getWorld().spawnParticle(Particle.SMOKE, pg.landedLoc.clone().add(0, 0.2, 0), 2, 0.06, 0.06, 0.06, 0.01);
                    }
                }
            }.runTaskTimer(this, 0L, 2L);
            return;
        }

        if (!existing.thrown && existing.type == util) {
            existing.thrown = true;
            if (p.getGameMode() != GameMode.CREATIVE && hand != null) {
                hand.setAmount(Math.max(0, hand.getAmount() - 1));
            }
            p.getWorld().playSound(p.getLocation(), Sound.ENTITY_SNOWBALL_THROW, 1.0f, 1.1f);
            Snowball sb = p.launchProjectile(Snowball.class, p.getEyeLocation().getDirection().normalize().multiply(1.35));
            existing.thrownEntity = sb;
            existing.landedLoc = sb.getLocation().clone();
            primedByEntity.put(sb.getUniqueId(), existing);
            p.sendMessage(ChatColor.YELLOW + "Threw " + util.displayName + "! Detonates when the 5s timer finishes.");
        }
    }

    private void triggerGrenadeEffect(PrimedGrenade pg) {
        if (pg.detonated) return;
        pg.detonated = true;
        primedByPlayer.remove(pg.ownerId, pg);

        if (pg.thrownEntity != null) {
            primedByEntity.remove(pg.thrownEntity.getUniqueId());
            if (pg.thrownEntity.isValid()) {
                pg.landedLoc = pg.thrownEntity.getLocation().clone();
                pg.thrownEntity.remove();
            }
        }

        Location loc = pg.landedLoc;
        if (loc == null || loc.getWorld() == null) return;
        World w = loc.getWorld();

        switch (pg.type) {
            case GRENADE -> w.createExplosion(loc, 3.2f, false, false);
            case SMOKE_GRENADE -> new BukkitRunnable() {
                int ticks = 0;

                @Override
                public void run() {
                    if (ticks++ > 180) {
                        cancel();
                        return;
                    }
                    w.spawnParticle(Particle.CAMPFIRE_COSY_SMOKE, loc.clone().add(0, 1.2, 0), 55, 2.8, 1.6, 2.8, 0.005);
                    w.spawnParticle(Particle.LARGE_SMOKE, loc.clone().add(0, 1.0, 0), 35, 2.5, 1.4, 2.5, 0.01);
                }
            }.runTaskTimer(this, 0L, 2L);
            case FLASHBANG -> {
                w.spawnParticle(Particle.FLASH, loc.clone().add(0, 0.5, 0), 12, 0.4, 0.4, 0.4, 0);
                w.playSound(loc, Sound.ENTITY_FIREWORK_ROCKET_BLAST_FAR, 2.0f, 1.9f);
                for (Entity e : w.getNearbyEntities(loc, 10.0, 6.0, 10.0)) {
                    if (e instanceof Player p) {
                        p.sendTitle(ChatColor.WHITE + "" + ChatColor.BOLD + "██████████", ChatColor.GRAY + "FLASHED!", 0, 45, 20);
                        p.addPotionEffect(new PotionEffect(PotionEffectType.BLINDNESS, 20 * 4, 2, false, false, false));
                        p.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, 20 * 3, 2, false, false, false));
                        p.playSound(p.getLocation(), Sound.BLOCK_BEACON_DEACTIVATE, 1.5f, 2.0f);
                    }
                }
            }
            case MOLOTOV -> {
                List<Location> spawned = new ArrayList<>();
                for (int i = 0; i < 10; i++) {
                    Location fl = loc.clone().add(
                            ThreadLocalRandom.current().nextDouble(-2.2, 2.2),
                            0,
                            ThreadLocalRandom.current().nextDouble(-2.2, 2.2)
                    );
                    Block b = fl.getBlock();
                    if (b.getType().isAir() || b.getType() == Material.FIRE) {
                        b.setType(Material.SOUL_FIRE);
                        Location bLoc = b.getLocation();
                        molotovFireBlocks.add(bLoc);
                        spawned.add(bLoc);
                    }
                }
                w.playSound(loc, Sound.ITEM_FIRECHARGE_USE, 1.4f, 0.9f);

                // Soul-fire burn damage (2.0 HP every 0.5s, matching soul fire) for 7 seconds
                new BukkitRunnable() {
                    int ticks = 0;

                    @Override
                    public void run() {
                        if (ticks++ >= 14) {
                            for (Location bLoc : spawned) {
                                molotovFireBlocks.remove(bLoc);
                                if (bLoc.getBlock().getType() == Material.SOUL_FIRE) {
                                    bLoc.getBlock().setType(Material.AIR);
                                }
                            }
                            cancel();
                            return;
                        }
                        for (Location bLoc : spawned) {
                            for (Entity e : w.getNearbyEntities(bLoc.clone().add(0.5, 0.5, 0.5), 0.7, 1.0, 0.7)) {
                                if (e instanceof LivingEntity le && !(e instanceof Display)) {
                                    le.setFireTicks(60);
                                    le.damage(2.0);
                                }
                            }
                        }
                    }
                }.runTaskTimer(this, 5L, 10L);
            }
            default -> {
            }
        }
    }

    private void fireHitscan(Player shooter, WeaponId w, double damage, int range, double spread) {
        PlayerProfile prof = profile(shooter.getUniqueId());
        Location eye = shooter.getEyeLocation();
        Vector dir = eye.getDirection().normalize();
        dir.add(new Vector(
                ThreadLocalRandom.current().nextDouble(-spread, spread),
                ThreadLocalRandom.current().nextDouble(-spread, spread),
                ThreadLocalRandom.current().nextDouble(-spread, spread)
        )).normalize();

        shooter.getWorld().playSound(eye, Sound.ENTITY_FIREWORK_ROCKET_BLAST, 0.9f, 1.4f);

        LivingEntity hitTarget = null;
        Location endLoc = eye.clone().add(dir.clone().multiply(range));

        for (double d = 0.5; d <= range; d += 0.5) {
            Location pt = eye.clone().add(dir.clone().multiply(d));
            if (pt.getBlock().getType().isSolid()) {
                endLoc = pt;
                break;
            }
            for (Entity e : pt.getWorld().getNearbyEntities(pt, 0.65, 0.9, 0.65)) {
                if (e.equals(shooter)) continue;
                if (e instanceof LivingEntity le && !(e instanceof Display)) {
                    hitTarget = le;
                    endLoc = pt;
                    break;
                }
            }
            if (hitTarget != null) break;
        }

        if (prof.tracerParticles && shooter.getWorld() != null) {
            double dist = eye.distance(endLoc);
            for (double d = 1.0; d < dist; d += 1.2) {
                Location tp = eye.clone().add(dir.clone().multiply(d));
                shooter.getWorld().spawnParticle(Particle.CRIT, tp, 1, 0, 0, 0, 0);
            }
        }

        if (hitTarget != null) {
            hitTarget.damage(damage, shooter);
            if (prof.hitSound) {
                shooter.playSound(shooter.getLocation(), Sound.ENTITY_ARROW_HIT_PLAYER, 1.0f, 1.3f);
            }
        }
    }

    private void launchHarpoon(Player shooter) {
        Location eye = shooter.getEyeLocation();
        Vector vel = eye.getDirection().normalize().multiply(2.8);
        shooter.getWorld().playSound(eye, Sound.ITEM_CROSSBOW_SHOOT, 1.2f, 0.85f);

        Arrow harpoon = shooter.launchProjectile(Arrow.class, vel);
        harpoon.setPickupStatus(AbstractArrow.PickupStatus.DISALLOWED);
        harpoon.setDamage(14.0);
        projKind.put(harpoon.getUniqueId(), "harpoon");
    }

    @EventHandler
    public void onProjectileHit(ProjectileHitEvent event) {
        PrimedGrenade pg = primedByEntity.remove(event.getEntity().getUniqueId());
        if (pg != null) {
            // Lock landing spot where it hit so it never snaps back to the player
            Location hitLoc = event.getEntity().getLocation().clone();
            if (event.getHitBlock() != null && event.getHitBlockFace() != null) {
                hitLoc = event.getHitBlock().getRelative(event.getHitBlockFace()).getLocation().add(0.5, 0.1, 0.5);
            }
            pg.landedLoc = hitLoc;
            pg.landed = true;
            pg.thrownEntity = null;
            event.getEntity().remove();
            return;
        }

        String kind = projKind.remove(event.getEntity().getUniqueId());
        if (kind == null) return;

        if ("harpoon".equals(kind)) {
            if (event.getHitEntity() instanceof LivingEntity target && event.getEntity().getShooter() instanceof Player shooter) {
                Vector pull = shooter.getLocation().toVector().subtract(target.getLocation().toVector()).normalize().multiply(1.3).setY(0.45);
                target.setVelocity(pull);
            }
            event.getEntity().remove();
        }
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onDamage(EntityDamageByEntityEvent event) {
        if (event.getDamager() instanceof Player attacker && event.getEntity() instanceof Player victim) {
            String t1 = playerTeam.get(attacker.getUniqueId());
            String t2 = playerTeam.get(victim.getUniqueId());
            if (t1 != null && t1.equals(t2)) {
                event.setCancelled(true);
                return;
            }
            WeaponId w = getWeapon(attacker.getInventory().getItemInMainHand());
            if (w == WeaponId.KNIFE) {
                event.setDamage(9.5);
            }
        }
    }

    @EventHandler
    public void onFireworkExplode(FireworkExplodeEvent event) {
    }

    @EventHandler
    public void onPlayerDeath(PlayerDeathEvent event) {
        Player dead = event.getEntity();
        Player killer = dead.getKiller();
        if (killer != null && !killer.equals(dead)) {
            rewardCoins(killer.getUniqueId(), 25, "Eliminating " + dead.getName());
        }

        if (dead.getUniqueId().equals(bombCarrierId) && !bombPlanted) {
            event.getDrops().removeIf(this::isBombItem);
            if (dead.getWorld() != null) {
                dead.getWorld().dropItemNaturally(dead.getLocation(), buildBombItem());
            }
            bombCarrierId = null;
        }
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player p = event.getPlayer();
        profile(p.getUniqueId());
        if (p.getGameMode() == GameMode.CREATIVE && !p.isOp()) {
            p.setGameMode(GameMode.SURVIVAL);
        }
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        UUID uid = event.getPlayer().getUniqueId();
        buildModePlayers.remove(uid);
        activeDefuses.remove(uid);
        currentlyPlanting.remove(uid);
        reloadingPlayers.remove(uid);
        PrimedGrenade pg = primedByPlayer.remove(uid);
        if (pg != null) pg.detonated = true;
        saveData();
    }

    private void rewardCoins(UUID uid, int amount, String reason) {
        PlayerProfile prof = profile(uid);
        prof.coins += amount;
        Player p = Bukkit.getPlayer(uid);
        if (p != null && p.isOnline()) {
            p.sendMessage(ChatColor.GOLD + "+" + amount + " Coins " + ChatColor.GRAY + "(" + reason + ")");
        }
    }

    private void awardTeamCoins(String team, int amount, String reason) {
        for (Map.Entry<UUID, String> e : playerTeam.entrySet()) {
            if (team.equals(e.getValue())) {
                rewardCoins(e.getKey(), amount, reason);
            }
        }
    }

    private void endRoundCleanup(boolean save) {
        roundActive = false;
        bombPlanted = false;
        bombCarrierId = null;
        playerTeam.clear();
        activeDefuses.clear();
        currentlyPlanting.clear();
        reloadingPlayers.clear();
        projKind.clear();

        for (Location bLoc : molotovFireBlocks) {
            if (bLoc.getWorld() != null && bLoc.getBlock().getType() == Material.SOUL_FIRE) {
                bLoc.getBlock().setType(Material.AIR);
            }
        }
        molotovFireBlocks.clear();

        for (PrimedGrenade pg : primedByPlayer.values()) {
            pg.detonated = true;
            if (pg.thrownEntity != null && pg.thrownEntity.isValid()) {
                pg.thrownEntity.remove();
            }
        }
        primedByPlayer.clear();
        primedByEntity.clear();

        if (bombFuseTask != null) {
            bombFuseTask.cancel();
            bombFuseTask = null;
        }

        if (plantedBombDisplayId != null) {
            Entity e = Bukkit.getEntity(plantedBombDisplayId);
            if (e != null && e.isValid()) e.remove();
            plantedBombDisplayId = null;
        }

        if (plantedBombLoc != null && plantedBombLoc.getWorld() != null) {
            for (Entity e : plantedBombLoc.getWorld().getNearbyEntities(plantedBombLoc, 3, 3, 3)) {
                if (e instanceof BlockDisplay && e.getPersistentDataContainer().has(keyBombDisplay, PersistentDataType.BYTE)) {
                    e.remove();
                }
            }
        }
        plantedBombLoc = null;

        if (save) {
            saveData();
        }
    }
}
