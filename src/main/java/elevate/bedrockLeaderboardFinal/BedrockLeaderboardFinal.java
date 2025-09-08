package elevate.bedrockLeaderboardFinal;

import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.inventory.ItemStack;
import org.bukkit.event.EventHandler;
import org.bukkit.entity.TextDisplay;
import org.bukkit.entity.ArmorStand;
import java.util.stream.Collectors;
import org.bukkit.event.Listener;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.block.Block;
import org.bukkit.Statistic;
import java.io.IOException;
import org.bukkit.Particle;
import org.bukkit.Sound;
import java.io.File;
import org.bukkit.*;
import java.util.*;

public final class BedrockLeaderboardFinal extends JavaPlugin implements Listener {
    private final Map<String, BukkitTask> activeEffects = new HashMap<>();
    private File dataFile;
    private FileConfiguration dataConfig;
    private Location leaderboardBlockLocation;
    private final Map<String, String> lastTopByType = new HashMap<>();

    //passive effects
    private interface Effect {
        void tick(World w, Location origin, double t);
    }

    private final Map<String, Effect> EFFECTS = new HashMap<>();

    //give each leaderboard their own tag
    private String tagFor(String type) {
        return type + "_leaderboard";
    }

    private void setLocFor(String type, Location loc) {
        dataConfig.set("leaderboards." + type + ".loc", serializeLoc(loc));
    }

    private float getYawFor(String type) {
        return normalizeYaw((float) dataConfig.getDouble("leaderboards." + type + ".yaw", 0.0));
    }

    //returns true if an armor stand has any _leaderboard tag
    private boolean isLeaderboardEntity(Entity e) {
        Set<String> tags = e.getScoreboardTags();

        //for old build, can probably delete soon
        if (tags.contains("diamond_leaderboard"))
            return true;
        for (String t : tags) if (t.endsWith("_leaderboard"))
            return true;
        return false;
    }

    @Override
    public void onEnable() {
        Bukkit.getPluginManager().registerEvents(this, this);
        loadData();
        registerEffects();
        startArmorStandVisibilityTask();

        //LEADERBOARD TYPES//
        final List<String> TYPES = Arrays.asList("diamond", "elytra", "reinforced", "onfoot", "mined", "trades");

        //COMMAND// setleaderboard <type>
        Objects.requireNonNull(getCommand("setleaderboard")).setExecutor((sender, command, label, args) -> {
            if (!(sender instanceof Player p)) {
                sender.sendMessage(ChatColor.RED + "Players only");
                return true;
            }

            if (!p.isOp() && !p.hasPermission("leaderboard.set")) {
                p.sendMessage(ChatColor.RED + "You don't have permission to do that");
                return true;
            }

            if (args.length < 1) {
                p.sendMessage(ChatColor.RED + "Usage: /setleaderboard <type>");
                p.sendMessage(ChatColor.GRAY + "Types: " + String.join(", ", TYPES));
                return true;
            }

            String type = args[0].toLowerCase(Locale.ROOT);
            if (!TYPES.contains(type)) {
                p.sendMessage(ChatColor.RED + "Unknown type: " + ChatColor.AQUA + type);
                p.sendMessage(ChatColor.GRAY + "Types: " + String.join(", ", TYPES));
                return true;
            }

            Block target = p.getTargetBlockExact(6);

            if (target == null) {
                p.sendMessage(ChatColor.RED + "Look at a bedrock block within 6 blocks.");
                return true;
            }

            if (target.getType() != Material.BEDROCK) {
                p.sendMessage(ChatColor.RED + "That's not bedrock silly");
                return true;
            }

            //save location/rotation
            Location loc = target.getLocation();
            setLocFor(type, loc);
            dataConfig.set("leaderboards." + type + ".yaw", normalizeYaw(p.getLocation().getYaw()));

            //might not need the old line below anymore
            dataConfig.set("leaderboard-block", serializeLoc(loc));
            saveData();

            //starts up the specific leaderboard type at only this location
            updateLeaderboardDisplay(type);
            startBedrockEffect(loc.clone(), type);
            p.sendMessage(ChatColor.GREEN + type.substring(0,1).toUpperCase() + type.substring(1) + " leaderboard set at " + ChatColor.AQUA + loc.getBlockX() + "," + loc.getBlockY() + "," + loc.getBlockZ() + ChatColor.GREEN + " in " + ChatColor.AQUA + loc.getWorld().getName());
            return true;
        });

        //auto-complete for /setleaderboard
        Objects.requireNonNull(getCommand("setleaderboard")).setTabCompleter((sender, command, alias, args) -> {
            if (args.length == 1) {
                return TYPES.stream().map(String::toLowerCase).filter(t -> t.startsWith(args[0].toLowerCase(Locale.ROOT))).collect(Collectors.toList());
            }
            return Collections.emptyList();
        });

        //COMMAND// refreshleaderboard
        Objects.requireNonNull(getCommand("refreshleaderboard")).setExecutor((sender, cmd, label, args) -> {
            if (!(sender instanceof Player p)) {
                sender.sendMessage(ChatColor.RED + "Players only");
                return true;
            }

            if (!p.isOp() && !p.hasPermission("leaderboard.refresh")) {
                p.sendMessage(ChatColor.RED + "You don't have permission to do that");
                return true;
            }

            Block target = p.getTargetBlockExact(6);

            if (target == null || target.getType() != Material.BEDROCK) {
                p.sendMessage(ChatColor.RED + "Look at the leaderboard bedrock within 6 blocks");
                return true;
            }

            Location loc = target.getLocation();
            Optional<String> typeOpt = findLeaderboardTypeByLocation(loc);

            if (typeOpt.isEmpty()) {
                p.sendMessage(ChatColor.GRAY + "No leaderboard is assigned to this bedrock");
                return true;
            }

            String type = typeOpt.get();
            updateLeaderboardDisplay(type);
            startBedrockEffect(loc.clone(), type);
            p.sendMessage(ChatColor.GREEN + "Refreshed the " + ChatColor.AQUA + type + ChatColor.GREEN + " leaderboard");
            return true;
        });

        //COMMAND// /rotateleaderboard <degrees>
        Objects.requireNonNull(getCommand("rotateleaderboard")).setExecutor((sender, cmd, label, args) -> {
            if (!(sender instanceof Player player)) {
                sender.sendMessage(ChatColor.RED + "Players only");
                return true;
            }

            if (!player.isOp() && !player.hasPermission("leaderboard.rotate")) {
                player.sendMessage(ChatColor.RED + "You don't have permission to do that");
                return true;
            }

            Block target = player.getTargetBlockExact(6);

            if (target == null || target.getType() != Material.BEDROCK) {
                player.sendMessage(ChatColor.RED + "Look at the leaderboard bedrock within 6 blocks");
                return true;
            }

            Optional<String> typeOpt = findLeaderboardTypeByLocation(target.getLocation());

            if (typeOpt.isEmpty()) {
                player.sendMessage(ChatColor.GRAY + "No leaderboard is assigned to this bedrock");
                return true;
            }

            String type = typeOpt.get();

            //Show current rotation
            if (args.length == 0) {
                float current = (float) dataConfig.getDouble("leaderboards." + type + ".yaw", 0.0);
                player.sendMessage(ChatColor.GREEN + "Current rotation for " + ChatColor.AQUA + type + ChatColor.GREEN + " is " + ChatColor.AQUA + current + "°");
                player.sendMessage(ChatColor.GRAY + "Use: /rotateleaderboard <degrees | +deg | -deg>");
                return true;
            }

            String arg = args[0].trim();
            boolean relative = arg.startsWith("+") || arg.startsWith("-");
            float inputDeg;

            try {
                inputDeg = Float.parseFloat(arg);
            }

            catch (NumberFormatException e) {
                player.sendMessage(ChatColor.RED + "Usage: /rotateleaderboard <degrees | +deg | -deg>");
                return true;
            }

            float storedYaw = (float) dataConfig.getDouble("leaderboards." + type + ".yaw", 0.0f);
            float newYaw = relative ? normalizeYaw(storedYaw + inputDeg) : normalizeYaw(inputDeg);

            dataConfig.set("leaderboards." + type + ".yaw", newYaw);
            saveData();

            //rotate lines
            Location base = target.getLocation().add(0.5, 1.0, 0.5);
            int rotated = 0;

            for (Entity e : base.getWorld().getNearbyEntities(base, 1.5, 3.0, 1.5)) {
                if (!isLeaderboardEntity(e)) continue;

                try {

                    if (e instanceof TextDisplay td) {
                        try { td.setRotation(newYaw, 0f); } catch (Throwable ignored) {}
                        rotated++;
                        continue;
                    }
                }

                catch (Throwable ignored) {}

                if (e instanceof ArmorStand as) {
                    Location l = as.getLocation().clone();
                    l.setYaw(newYaw);
                    as.teleport(l);
                    rotated++;
                }
            }

            player.sendMessage(ChatColor.GREEN + (relative ? "Rotated by " : "Set rotation to ") + ChatColor.AQUA + (relative ? inputDeg : newYaw) + "°" + ChatColor.GREEN + " for " + ChatColor.AQUA + type + ChatColor.GREEN + " (" + rotated + " line(s) updated).");

            if (rotated == 0) {
                player.sendMessage(ChatColor.YELLOW + "No leaderboard text found near that bedrock");
            }
            return true;
        });

        Objects.requireNonNull(getCommand("rotateleaderboard")).setTabCompleter((sender, cmd, alias, args) -> {
            if (args.length == 1) {
                return Arrays.asList("0", "90", "180", "270", "+15", "+45", "-15", "-45");
            }
            return Collections.emptyList();
        });

        //COMMAND// refreshloot (reload data.yml from plugin's folder)
        Objects.requireNonNull(getCommand("refreshloot")).setExecutor((sender, cmd, label, args) -> {
            if (!(sender instanceof Player p)) {
                sender.sendMessage(ChatColor.RED + "Players only"); return true;
            }

            if (!p.isOp() && !p.hasPermission("leaderboard.reload")) {
                p.sendMessage(ChatColor.RED + "You don't have permission to do that"); return true;
            }

            long start = System.currentTimeMillis();
            reloadLeaderboardData();
            long ms = System.currentTimeMillis() - start;
            p.sendMessage(ChatColor.GREEN + "Leaderboard data reloaded from disk " + ChatColor.GRAY + "(" + ms + " ms)" + ChatColor.GREEN + ".");
            return true;
        });

        //COMMAND// clearleaderboard
        Objects.requireNonNull(getCommand("clearleaderboard")).setExecutor((sender, cmd, label, args) -> {
            if (!(sender instanceof Player p)) {
                sender.sendMessage(ChatColor.RED + "Players only"); return true;
            }

            if (!p.isOp() && !p.hasPermission("leaderboard.clear")) {
                p.sendMessage(ChatColor.RED + "You don't have permission to do that"); return true;
            }

            Block target = p.getTargetBlockExact(6);

            if (target == null || target.getType() != Material.BEDROCK) {
                p.sendMessage(ChatColor.RED + "Look at a bedrock block within 6 blocks"); return true;
            }

            Location loc = target.getLocation();
            Optional<String> typeOpt = findLeaderboardTypeByLocation(loc);

            if (typeOpt.isEmpty()) {
                p.sendMessage(ChatColor.GRAY + "No leaderboard is assigned to this bedrock"); return true;
            }

            String type = typeOpt.get();
            stopBedrockEffect(type);

            //remove tagged entities nearby
            loc.getWorld().getNearbyEntities(loc, 1.5, 10, 1.5).stream().filter(this::isLeaderboardEntity).forEach(Entity::remove);

            dataConfig.set("leaderboards." + type, null);
            saveData();
            p.sendMessage(ChatColor.YELLOW + "Cleared the " + ChatColor.AQUA + type + ChatColor.YELLOW + " leaderboard.");
            return true;
        });
    }

    @Override
    public void onDisable() {
        stopAllBedrockEffects();
        saveData();
    }

    @EventHandler
    public void onPlayerInteract(PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND)
            return;

        if (event.getClickedBlock() == null)
            return;

        if (event.getClickedBlock().getType() != Material.BEDROCK)
            return;

        final Location loc = event.getClickedBlock().getLocation();
        final Optional<String> typeOpt = findLeaderboardTypeByLocation(loc);

        if (typeOpt.isEmpty())
            return;

        final String type = typeOpt.get();
        final Player player = event.getPlayer();
        final ItemStack item = player.getInventory().getItemInMainHand();

        switch (type.toLowerCase(Locale.ROOT)) {
            case "diamond": {
                if (item.getType() != Material.DIAMOND) {
                    player.sendMessage(ChatColor.RED + "Hold " + ChatColor.AQUA + "diamonds" + ChatColor.RED + " to submit");
                    return;
                }

                int count = item.getAmount();
                player.getInventory().setItemInMainHand(null);
                String base = "players." + player.getUniqueId();
                int newTotal = dataConfig.getInt(base + ".diamonds", 0) + count;
                dataConfig.set(base + ".diamonds", newTotal);
                dataConfig.set(base + ".name", player.getName());
                saveData();
                player.sendMessage(ChatColor.RED + "You sacrificed " + ChatColor.AQUA + count + ChatColor.RED + " diamond(s)");
                break;
            }

            case "elytra": {
                if (item.getType() != Material.PHANTOM_MEMBRANE) {
                    player.sendMessage(ChatColor.RED + "Hold a " + ChatColor.AQUA + "phantom membrane" + ChatColor.RED + " to submit");
                    return;
                }

                if (item.getAmount() > 1) item.setAmount(item.getAmount() - 1);
                else player.getInventory().setItemInMainHand(null);

                int flownCm = player.getStatistic(org.bukkit.Statistic.AVIATE_ONE_CM);
                String base = "players." + player.getUniqueId();
                int best = Math.max(dataConfig.getInt(base + ".elytra_flown_cm", 0), flownCm);
                dataConfig.set(base + ".elytra_flown_cm", best);
                dataConfig.set(base + ".name", player.getName());
                saveData();
                player.sendMessage(ChatColor.GREEN + "Submitted: " + ChatColor.AQUA + formatDistance(best));
                break;
            }

            case "reinforced": {
                if (item.getType() != Material.GOLD_INGOT) {
                    player.sendMessage(ChatColor.RED + "Hold a " + ChatColor.GOLD + "gold ingot" + ChatColor.RED + " to submit");
                    return;
                }

                if (item.getAmount() > 1) item.setAmount(item.getAmount() - 1);
                else player.getInventory().setItemInMainHand(null);

                //grab vanilla stats
                int used = player.getStatistic(org.bukkit.Statistic.USE_ITEM, Material.REINFORCED_DEEPSLATE);
                String base = "players." + player.getUniqueId();
                dataConfig.set(base + ".reinforced_placed", used);
                dataConfig.set(base + ".name", player.getName());
                saveData();
                player.sendMessage(ChatColor.GREEN + "Submitted lootboxes opened: " + ChatColor.AQUA + used);
                updateLeaderboardDisplay("reinforced");
                break;
            }

            case "onfoot": {
                if (item.getType() != Material.LEATHER) {
                    player.sendMessage(ChatColor.RED + "Hold " + ChatColor.GOLD + "Leather" + ChatColor.RED + " to submit");
                    return;
                }

                if (item.getAmount() > 1) item.setAmount(item.getAmount() - 1);
                else player.getInventory().setItemInMainHand(null);

                //grab vanilla stats in cm
                int walk = player.getStatistic(Statistic.WALK_ONE_CM);
                int sprint = 0;

                try {
                    sprint = player.getStatistic(Statistic.SPRINT_ONE_CM);
                }

                catch (Throwable ignore) {}

                int totalCm = walk + sprint;
                String base = "players." + player.getUniqueId();
                int best = Math.max(dataConfig.getInt(base + ".onfoot_cm", 0), totalCm);
                dataConfig.set(base + ".onfoot_cm", best);
                dataConfig.set(base + ".name", player.getName());
                saveData();
                player.sendMessage(ChatColor.GREEN + "Submitted on-foot distance: " + ChatColor.AQUA + formatDistance(best));
                updateLeaderboardDisplay("onfoot");
                break;
            }

            case "mined": {
                if (item.getType() != Material.GOLDEN_PICKAXE) {
                    player.sendMessage(ChatColor.RED + "Hold a " + ChatColor.AQUA + "Golden Pickaxe" + ChatColor.RED + " to submit.");
                    return;
                }

                if (item.getAmount() > 1) item.setAmount(item.getAmount() - 1);
                else player.getInventory().setItemInMainHand(null);

                int used = getTotalPickaxeUses(player);
                String base = "players." + player.getUniqueId();
                dataConfig.set(base + ".mined_blocks", used);
                dataConfig.set(base + ".name", player.getName());
                saveData();
                player.sendMessage(ChatColor.GREEN + "Submitted blocks mined: " + ChatColor.AQUA + used);
                updateLeaderboardDisplay("mined");
                break;
            }

            case "trades": {
                if (item.getType() != Material.EMERALD) {
                    player.sendMessage(ChatColor.RED + "Hold an " + ChatColor.GREEN + "Emerald" + ChatColor.RED + " to submit.");
                    return;
                }

                if (item.getAmount() > 1) item.setAmount(item.getAmount() - 1);
                else player.getInventory().setItemInMainHand(null);

                //grab vanilla stats
                int trades = 0;
                try {
                    trades = player.getStatistic(Statistic.TRADED_WITH_VILLAGER);
                }

                catch (Throwable ignored) {}

                String base = "players." + player.getUniqueId();
                dataConfig.set(base + ".trades", trades);
                dataConfig.set(base + ".name", player.getName());
                saveData();
                player.sendMessage(ChatColor.GREEN + "Submitted villager trades: " + ChatColor.AQUA + trades);
                updateLeaderboardDisplay("trades");
                break;
            }
        }
        updateLeaderboardDisplay(type);
        startBedrockEffect(loc.clone(), type);
    }

    private void updateLeaderboardDisplay(String type) {
        Location lbLoc = getLocFor(type);
        if (lbLoc == null) return;
        float yaw = getYawFor(type);

        //remove existing display entities near the targeted leaderboard
        lbLoc.getWorld().getNearbyEntities(lbLoc, 1.5, 10, 1.5).stream().filter(this::isLeaderboardEntity).forEach(Entity::remove);

        //effects for specific leaderboard type
        Location effectLoc = lbLoc.clone().add(0.5, 1.0, 0.5);
        playRefreshFx(type, effectLoc);

        //build leaderboard  type
        Map<String, Integer> totals = new HashMap<>();
        if (dataConfig.isConfigurationSection("players")) {
            for (String uuid : dataConfig.getConfigurationSection("players").getKeys(false)) {
                String name = dataConfig.getString("players." + uuid + ".name", uuid);
                int value = 0;

                if ("diamond".equalsIgnoreCase(type)) {
                    value = dataConfig.getInt("players." + uuid + ".diamonds", 0);

                }

                else if ("elytra".equalsIgnoreCase(type)) {
                    value = dataConfig.getInt("players." + uuid + ".elytra_flown_cm", 0);
                }

                else if ("reinforced".equalsIgnoreCase(type)) {
                    value = dataConfig.getInt("players." + uuid + ".reinforced_placed", 0);
                }

                else if ("onfoot".equalsIgnoreCase(type)) {
                    value = dataConfig.getInt("players." + uuid + ".onfoot_cm", 0);
                }

                else if ("mined".equalsIgnoreCase(type)) {
                    value = dataConfig.getInt("players." + uuid + ".mined_blocks", 0);
                }

                else if ("trades".equalsIgnoreCase(type)) {
                    value = dataConfig.getInt("players." + uuid + ".trades", 0);
                }
                if (value > 0) totals.put(name, value);
            }
        }

        List<Map.Entry<String, Integer>> top = totals.entrySet().stream().sorted(Map.Entry.<String, Integer>comparingByValue().reversed()).limit(5).collect(Collectors.toList());

        //check if #1 spot changes
        if (!top.isEmpty()) {
            String currentTop = top.get(0).getKey();
            String prevTop = lastTopByType.get(type);

            if (prevTop != null && !prevTop.equals(currentTop)) {
                for (Player p : Bukkit.getOnlinePlayers()) {
                    p.playSound(p.getLocation(), Sound.EVENT_RAID_HORN, 15.0f, 4.0f);
                }

                String crownLabel;
                if ("elytra".equalsIgnoreCase(type))          crownLabel = "Sun Chaser";
                else if ("reinforced".equalsIgnoreCase(type)) crownLabel = "High Roller";
                else if ("onfoot".equalsIgnoreCase(type))     crownLabel = "Trailblazer";
                else if ("mined".equalsIgnoreCase(type))      crownLabel = "Pickaxe Prodigy";
                else if ("trades".equalsIgnoreCase(type))     crownLabel = "Master of the Silk Road";
                else                                          crownLabel = "Diamond Killer";

                Bukkit.broadcastMessage(ChatColor.RED + "A new " + crownLabel + " has been crowned!");
            }
            lastTopByType.put(type, currentTop);
        }

        //title + lines
        Location base = lbLoc.clone().add(0.5, 1.2, 0.5);
        String title =
                        type.equals("elytra")     ? ChatColor.AQUA + "Distance by Elytra" :
                        type.equals("reinforced") ? ChatColor.DARK_AQUA + "Lootboxes Opened" :
                        type.equals("onfoot")     ? ChatColor.GREEN + "Distance on Foot" :
                        type.equals("mined")      ? ChatColor.YELLOW + "Blocks Mined" :
                        type.equals("trades")     ? ChatColor.GREEN + "Villager Trades" :
                        ChatColor.RED + "" + ChatColor.MAGIC + "a" + ChatColor.AQUA + "Diamond Killers" + ChatColor.RED + "" + ChatColor.MAGIC + "a";

        spawnTextLine(type, base.clone(), yaw, title);
        spawnTextLine(type, base.clone().add(0, 0.25, 0), yaw, " ");

        ChatColor[] rankColors = {
                ChatColor.GOLD, ChatColor.RED, ChatColor.DARK_PURPLE, ChatColor.BLUE, ChatColor.WHITE
        };

        for (int i = 0; i < top.size(); i++) {
            Map.Entry<String, Integer> entry = top.get(i);
            ChatColor color = (i < rankColors.length) ? rankColors[i] : ChatColor.WHITE;
            String name = entry.getKey();
            double yOffset = (top.size() - i + 1) * 0.25;
            String valueText;

            if ("elytra".equalsIgnoreCase(type) || "onfoot".equalsIgnoreCase(type)) {
                valueText = formatDistance(entry.getValue());
            }

            else {
                valueText = String.valueOf(entry.getValue());
            }

            String line = "" + color + (i + 1) + ". " + name + " - " + valueText + ChatColor.RESET;
            spawnTextLine(type, base.clone().add(0, yOffset, 0), yaw, line);
        }
    }

    private void spawnArmorStand(String type, Location loc, float yaw, String text) {
        ArmorStand stand = loc.getWorld().spawn(loc, ArmorStand.class);
        stand.setInvisible(true);
        stand.setInvulnerable(true);
        stand.setGravity(false);
        stand.setCustomName(text);
        stand.setCustomNameVisible(true);
        stand.addScoreboardTag(tagFor(type));
        stand.addScoreboardTag("diamond_leaderboard"); //change
        stand.setMarker(true);
        Location l = stand.getLocation();
        l.setYaw(yaw);
        stand.teleport(l);
    }

    private void startBedrockEffect(Location loc, String type) {
        stopBedrockEffect(type);

        BukkitTask task = new BukkitRunnable() {
            double t = 0;

            @Override
            public void run() {
                if (loc == null || loc.getBlock().getType() != Material.BEDROCK) {
                    cancel();
                    activeEffects.remove(type);
                    return;
                }

                World world = loc.getWorld();
                t += Math.PI / 16;
                Effect fx = EFFECTS.getOrDefault(type.toLowerCase(Locale.ROOT), EFFECTS.get("default"));
                if (fx != null) fx.tick(world, loc, t);
            }
        }.runTaskTimer(this, 0L, 5L);
        activeEffects.put(type, task);
    }

    private void stopBedrockEffect(String type) {
        BukkitTask task = activeEffects.remove(type);
        if (task != null) task.cancel();
    }

    private void stopAllBedrockEffects() {
        for (BukkitTask t : activeEffects.values()) {
            if (t != null) t.cancel();
        }
        activeEffects.clear();
    }

    private void saveData() {
        try {
            dataConfig.save(dataFile);
        }
        catch (IOException e) {
            getLogger().warning("Failed to save data.yml ");
        }
    }

    private String serializeLoc(Location loc) {
        return loc.getWorld().getName() + "," + loc.getBlockX() + "," + loc.getBlockY() + "," + loc.getBlockZ();
    }

    private Location deserializeLoc(String str) {
        String[] parts = str.split(",");
        return new Location(Bukkit.getWorld(parts[0]), Integer.parseInt(parts[1]), Integer.parseInt(parts[2]), Integer.parseInt(parts[3]));
    }

    @EventHandler
    public void onBlockBreak(BlockBreakEvent event) {
        Block block = event.getBlock();
        if (block.getType() != Material.BEDROCK) return;
        Location loc = block.getLocation();
        Optional<String> typeOpt = findLeaderboardTypeByLocation(loc);
        if (typeOpt.isEmpty()) return;
        String type = typeOpt.get();
        stopBedrockEffect(type);

        //remove tagged entities near that block
        loc.getWorld().getNearbyEntities(loc, 1.5, 10, 1.5).stream().filter(this::isLeaderboardEntity).forEach(Entity::remove);

        //clear persisted keys for that type
        dataConfig.set("leaderboards." + type, null);
        saveData();
        event.getPlayer().sendMessage(ChatColor.RED + "Removed " + ChatColor.AQUA + type + ChatColor.RED + " leaderboard block.");
    }

    private void startArmorStandVisibilityTask() {
        new BukkitRunnable() {
            @Override
            public void run() {
                if (leaderboardBlockLocation == null) return;

                List<Player> players = new ArrayList<>(Bukkit.getOnlinePlayers());

                //look for nearby entities around the leaderboard
                leaderboardBlockLocation.getWorld().getNearbyEntities(leaderboardBlockLocation, 20, 20, 20).forEach(e -> {
                    Set<String> tags = e.getScoreboardTags();
                    boolean isLb = tags.contains("diamond_leaderboard") || tags.stream().anyMatch(t -> t.endsWith("_leaderboard"));

                    if (!isLb) return;

                    //checks if a player is within 15 blocks of the leaderboard
                    boolean shouldShow = players.stream().anyMatch(player -> player.getWorld().equals(e.getWorld()) && player.getLocation().distance(e.getLocation()) <= 15);

                    if (e instanceof TextDisplay td) {
                        try {
                            td.setTextOpacity((byte) (shouldShow ? 255 : 0));
                        }
                        catch (Throwable ignored) {}
                    }

                    else if (e instanceof ArmorStand as) {
                        as.setCustomNameVisible(shouldShow);
                    }
                });
            }
        }.runTaskTimer(this, 0L, 20L);
    }

    //textDisplay spawner
    private void spawnTextLine(String type, Location loc, float yaw, String coloredText) {
        try {
            TextDisplay display = loc.getWorld().spawn(loc, TextDisplay.class);
            display.setText(coloredText);
            try { display.setBillboard(Display.Billboard.FIXED); } catch (Throwable ignored) {}
            try { display.setAlignment(TextDisplay.TextAlignment.CENTER); } catch (Throwable ignored) {}
            try { display.setShadowed(true); } catch (Throwable ignored) {}
            try { display.setSeeThrough(false); } catch (Throwable ignored) {}
            try { display.setLineWidth(2048); } catch (Throwable ignored) {}
            try { display.setViewRange(12.0f); } catch (Throwable ignored) {}
            try { display.setRotation(yaw, 0f); } catch (Throwable ignored) {}
            display.addScoreboardTag(tagFor(type));
            display.addScoreboardTag("diamond_leaderboard");
        }

        catch (Throwable t) {
            //fallback to ArmorStand if TextDisplay unavailable
            spawnArmorStand(type, loc, yaw, coloredText);
        }
    }

    private float normalizeYaw(float yaw) {
        yaw %= 360f;
        if (yaw < 0f) yaw += 360f;
        return yaw;
    }

    private void reloadLeaderboardData() {
        stopAllBedrockEffects();

        //remove existing displays around any saved locations
        if (dataConfig.isConfigurationSection("leaderboards")) {
            for (String type : dataConfig.getConfigurationSection("leaderboards").getKeys(false)) {
                Location loc = getLocFor(type);
                if (loc != null) {
                    loc.getWorld().getNearbyEntities(loc, 1.5, 10, 1.5).stream().filter(this::isLeaderboardEntity).forEach(Entity::remove);
                }
            }
        }

        //re-read/check data.yml
        if (dataFile == null) dataFile = new File(getDataFolder(), "data.yml");
        dataConfig = YamlConfiguration.loadConfiguration(dataFile);

        //rebuild any leaderboards where chunks are loaded again
        if (dataConfig.isConfigurationSection("leaderboards")) {
            for (String type : dataConfig.getConfigurationSection("leaderboards").getKeys(false)) {
                Location loc = getLocFor(type);
                if (loc == null) continue;
                World w = loc.getWorld();
                if (w == null) continue;
                int cx = loc.getBlockX() >> 4, cz = loc.getBlockZ() >> 4;

                if (w.isChunkLoaded(cx, cz) && loc.getBlock().getType() == Material.BEDROCK) {
                    Bukkit.getScheduler().runTask(this, () -> {
                        updateLeaderboardDisplay(type);
                        startBedrockEffect(loc.clone(), type);
                    });
                }
            }
        }
    }

    private String formatDistance(int cm) {
        double meters = cm / 100.0;
        if (meters >= 1000.0) {
            double km = meters / 1000.0;
            return String.format(java.util.Locale.ROOT, "%.2f km", km);
        }

        if (meters >= 10.0) {
            return String.format(java.util.Locale.ROOT, "%.0f m", Math.floor(meters));
        }
        return String.format(java.util.Locale.ROOT, "%.1f m", meters);
    }

    private Optional<String> findLeaderboardTypeByLocation(Location loc) {
        if (!dataConfig.isConfigurationSection("leaderboards")) return Optional.empty();

        for (String type : dataConfig.getConfigurationSection("leaderboards").getKeys(false)) {
            String s = dataConfig.getString("leaderboards." + type + ".loc", null);

            if (s == null) continue;

            try {
                Location saved = deserializeLoc(s);
                if (saved != null && Objects.equals(saved.getWorld(), loc.getWorld()) && saved.getBlockX() == loc.getBlockX() && saved.getBlockY() == loc.getBlockY() && saved.getBlockZ() == loc.getBlockZ()) {
                    return Optional.of(type);
                }
            } catch (Exception ignored) {}
        }
        return Optional.empty();
    }

    private void playRefreshFx(String type, Location effectLoc) {
        World world = effectLoc.getWorld();

        switch (type.toLowerCase(java.util.Locale.ROOT)) {
            case "elytra": {
                World w = effectLoc.getWorld();
                Location base = effectLoc.clone().add(0, -0.15, 0);

                for (int i = 0; i < 24; i++) {
                    double ang = (i / 24.0) * (Math.PI * 2);
                    double r = 0.9;
                    double x = r * Math.cos(ang);
                    double z = r * Math.sin(ang);
                    w.spawnParticle(Particle.CLOUD, base.clone().add(x, 0.08, z), 2, 0.06, 0.02, 0.06, 0.0);
                }

                w.spawnParticle(Particle.END_ROD, base.clone().add(0, 0.18, 0), 10, 0.30, 0.22, 0.30, 0.0);

                for (Player p : Bukkit.getOnlinePlayers()) {
                    if (!p.getWorld().equals(w) || p.getLocation().distance(base) > 16) continue;

                    try {
                        p.playSound(base, Sound.BLOCK_AMETHYST_BLOCK_CHIME, 0.9f, 1.20f);
                    }

                    catch (Throwable ignored) {}
                    p.playSound(base, Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 0.6f, 1.55f);
                    Bukkit.getScheduler().runTaskLater(this, () -> p.playSound(base, Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 0.5f, 1.75f), 4L);
                }
                break;
            }

            case "diamond":
            default: {

                for (Player p : Bukkit.getOnlinePlayers()) {
                    if (p.getWorld().equals(world) && p.getLocation().distance(effectLoc) <= 16) {
                        p.playSound(effectLoc, Sound.ENTITY_GENERIC_EXPLODE, 1.0f, 1.0f);
                        p.playSound(effectLoc, Sound.BLOCK_FIRE_EXTINGUISH, 0.7f, 1.2f);
                    }
                }

                world.spawnParticle(Particle.FLAME, effectLoc, 40, 0.3, 0.5, 0.3, 0.01);
                world.spawnParticle(Particle.LAVA, effectLoc, 8, 0.2, 0.2, 0.2, 0.01);
                world.spawnParticle(Particle.SMOKE, effectLoc, 6, 0.4, 0.2, 0.4, 0.01);
                world.spawnParticle(Particle.EXPLOSION, effectLoc, 1);
                break;
            }

            case "reinforced": {
                world.spawnParticle(Particle.SCULK_SOUL, effectLoc, 30, 0.45, 0.28, 0.45, 0.0);
                world.spawnParticle(Particle.SOUL_FIRE_FLAME, effectLoc, 10, 0.28, 0.18, 0.28, 0.01);
                for (Player p : Bukkit.getOnlinePlayers()) {
                    if (p.getWorld().equals(world) && p.getLocation().distance(effectLoc) <= 16) {
                        p.playSound(effectLoc, Sound.BLOCK_SCULK_SENSOR_CLICKING, 0.7f, 1.25f);
                    }
                }
                break;
            }

            case "onfoot": {
                world.spawnParticle(Particle.CLOUD, effectLoc, 28, 0.45, 0.10, 0.45, 0.0);
                world.spawnParticle(Particle.ASH,   effectLoc, 16, 0.35, 0.06, 0.35, 0.0);

                for (Player p : Bukkit.getOnlinePlayers()) {
                    if (!p.getWorld().equals(world) || p.getLocation().distance(effectLoc) > 16) continue;

                    p.playSound(effectLoc, Sound.BLOCK_GRASS_STEP, 0.8f, 1.1f);
                    p.playSound(effectLoc, Sound.BLOCK_GRAVEL_STEP, 0.5f, 1.0f);
                }
                break;
            }

            case "mined": {
                try {
                    org.bukkit.block.data.BlockData stone = Bukkit.createBlockData(Material.STONE);
                    world.spawnParticle(Particle.BLOCK_CRUMBLE, effectLoc, 50, 0.45, 0.25, 0.45, 0.0, stone);
                }

                catch (Throwable ignored) {
                    world.spawnParticle(Particle.ASH, effectLoc, 30, 0.45, 0.20, 0.45, 0.0);
                }

                for (Player p : Bukkit.getOnlinePlayers()) {
                    if (!p.getWorld().equals(world) || p.getLocation().distance(effectLoc) > 16) continue;

                    p.playSound(effectLoc, Sound.BLOCK_STONE_BREAK, 0.9f, 1.05f);
                    p.playSound(effectLoc, Sound.ITEM_AXE_SCRAPE, 0.5f, 1.2f); // metallic scrape “clink”
                }
                break;
            }

            case "trades": {
                world.spawnParticle(Particle.HAPPY_VILLAGER, effectLoc, 24, 0.45, 0.35, 0.45, 0.0);
                world.spawnParticle(Particle.END_ROD,        effectLoc, 8,  0.25, 0.20, 0.25, 0.0);

                for (Player p : Bukkit.getOnlinePlayers()) {
                    if (!p.getWorld().equals(world) || p.getLocation().distance(effectLoc) > 16) continue;

                    p.playSound(effectLoc, Sound.ENTITY_VILLAGER_YES, 0.9f, 1.1f);

                    try {
                        p.playSound(effectLoc, Sound.BLOCK_AMETHYST_BLOCK_CHIME, 0.5f, 1.3f);
                    }
                    catch (Throwable ignored) {}
                }
                break;
            }
        }
    }


    private void startBedrockEffect(Location loc) {
        String lbType = findLeaderboardTypeByLocation(loc).orElse("diamond");
        startBedrockEffect(loc, lbType);
    }

    private void registerEffects() {

        EFFECTS.put("default", (w, loc, t) -> {
            Location c = loc.clone().add(0.5, 1.0, 0.5);
            w.spawnParticle(Particle.REVERSE_PORTAL, c, 5, 0.25, 0.35, 0.25, 0.01);
        });

        EFFECTS.put("diamond", (w, loc, t) -> {
            for (double a = 0; a < 2 * Math.PI; a += Math.PI / 8) {
                double x = 0.5 * Math.cos(a + t);
                double z = 0.5 * Math.sin(a + t);
                Location p = loc.clone().add(0.5 + x, 1.0, 0.5 + z);
                w.spawnParticle(Particle.FLAME, p, 1, 0, 0, 0, 0);
            }
            w.spawnParticle(Particle.REVERSE_PORTAL, loc.clone().add(0.5, 1.0, 0.5), 4, 0.25, 0.35, 0.25, 0.01);
        });

        EFFECTS.put("elytra", (w, loc, t) -> {
            for (double a = 0; a < 2 * Math.PI; a += Math.PI / 10) {
                double r = 0.5;
                double x = r * Math.cos(a + t);
                double z = r * Math.sin(a + t);
                Location p = loc.clone().add(0.5 + x, 0.80 + (Math.sin(t + a) * 0.12), 0.5 + z);
                w.spawnParticle(Particle.CLOUD, p, 0, 0, 0, 0, 0.0);
            }
            w.spawnParticle(Particle.END_ROD, loc.clone().add(0.5, .90, 0.5), 2, 0.22, 0.15, 0.22, 0.0);
        });

        EFFECTS.put("reinforced", (w, loc, t) -> {
            Location c = loc.clone().add(0.5, 1, 0.5);
            int points = 16;
            double radius = 0.48;
            double speed = 0.6;
            double wobble = 0.01 * Math.sin(t * 0.8);

            for (int i = 0; i < points; i++) {
                double a = i * (2 * Math.PI / points) + t * speed;
                double x = radius * Math.cos(a);
                double z = radius * Math.sin(a);
                w.spawnParticle(Particle.REVERSE_PORTAL, c.clone().add(x, wobble, z), 1, 0.02, 0.02, 0.02, 0.0);

                if (i % 4 == 0) {
                    w.spawnParticle(Particle.SCULK_SOUL, c.clone().add(x, 0.02 + wobble, z), 1, 0, 0, 0, 0.0);
                }
            }

            if (((int) (t * 8)) % 24 == 0) {
                w.spawnParticle(Particle.SOUL_FIRE_FLAME, c, 5, 0.18, 0.04, 0.18, 0.0);
            }
        });

        EFFECTS.put("onfoot", (w, loc, t) -> {
            Location center = loc.clone().add(0.5, 1.0, 0.5);

            if (((int) (t * 4)) % 2 != 0) return;

            double a  = t * 2.0;
            double rx = 0.55, rz = 0.45;
            double cx = rx * Math.cos(a);
            double cz = rz * Math.sin(a);
            double stepSep = 0.18;
            double nx = Math.cos(a);
            double nz = Math.sin(a);
            boolean left = (((int) (t * 4)) % 4) < 2;
            double sx = left ? +nx * stepSep : -nx * stepSep;
            double sz = left ? +nz * stepSep : -nz * stepSep;
            Location stepLoc = center.clone().add(cx + sx, 0.02, cz + sz);

            try {
                org.bukkit.block.data.BlockData dirt = Bukkit.createBlockData(Material.DIRT);
                w.spawnParticle(Particle.FALLING_DUST, stepLoc, 2, 0.04, 0.01, 0.04, 0.0, dirt);
            }

            catch (Throwable ignored) {
                w.spawnParticle(Particle.ASH, stepLoc, 2, 0.04, 0.01, 0.04, 0.0);
            }

            Location heel = stepLoc.clone().add(-nx * 0.10, 0.06, -nz * 0.10);
            w.spawnParticle(Particle.CLOUD, heel, 1, 0.02, 0.00, 0.02, 0.0);

            if (((int) (t * 8)) % 16 == 0) {
                w.spawnParticle(Particle.CRIT, stepLoc.clone().add(0, 0.06, 0), 1, 0.01, 0.01, 0.01, 0.0);
            }

            if (((int) (t * 8)) % 10 == 0) {
                int points = 10;
                double r = 0.42 + 0.02 * Math.sin(t * 0.5);

                for (int i = 0; i < points; i++) {
                    double ang = i * (2 * Math.PI / points);
                    double x = r * Math.cos(ang);
                    double z = r * Math.sin(ang);
                    w.spawnParticle(Particle.ASH, center.clone().add(x, 0.03, z), 1, 0.05, 0.01, 0.05, 0.0);
                }
            }
        });

        EFFECTS.put("mined", (w, loc, t) -> {
            Location center = loc.clone().add(0.5, 1.05, 0.5);

            if (((int)(t * 6)) % 2 != 0) return;

            int points = 8;
            double radius = 0.50 + 0.03 * Math.sin(t * 0.6);

            for (int i = 0; i < points; i++) {
                double a = i * (2 * Math.PI / points) + t * 0.35;
                double x = radius * Math.cos(a);
                double z = radius * Math.sin(a);
                Location p = center.clone().add(x, 0.02, z);
                org.bukkit.block.data.BlockData payload = null;

                try {
                    switch ((i + ((int)(t * 4))) % 3) {
                        case 0 -> payload = org.bukkit.Bukkit.createBlockData(org.bukkit.Material.STONE);
                        case 1 -> payload = org.bukkit.Bukkit.createBlockData(org.bukkit.Material.COBBLESTONE);
                        default -> payload = org.bukkit.Bukkit.createBlockData(org.bukkit.Material.DEEPSLATE);
                    }
                }

                catch (Throwable ignored) {}
                boolean usedDust = false;

                if (payload != null) {
                    try {
                        w.spawnParticle(org.bukkit.Particle.FALLING_DUST, p, 2, 0.05, 0.01, 0.05, 0.0, payload);
                        usedDust = true;
                    }

                    catch (Throwable ignored) {
                        try {
                            w.spawnParticle(org.bukkit.Particle.BLOCK, p, 2, 0.05, 0.01, 0.05, 0.0, payload);
                            usedDust = true;
                        }
                        catch (Throwable ignored2) {}
                    }
                }

                if (!usedDust) {
                    w.spawnParticle(org.bukkit.Particle.ASH, p, 2, 0.05, 0.01, 0.05, 0.0);
                }
            }

            if (((int)(t * 8)) % 12 == 0) {
                Location c = center.clone().add(0, 0.05, 0);

                try {
                    org.bukkit.block.data.BlockData stone = org.bukkit.Bukkit.createBlockData(org.bukkit.Material.STONE);

                    try {
                        w.spawnParticle(org.bukkit.Particle.FALLING_DUST, c, 3, 0.10, 0.03, 0.10, 0.0, stone);
                    }

                    catch (Throwable ignored) {
                        w.spawnParticle(org.bukkit.Particle.BLOCK, c, 3, 0.10, 0.03, 0.10, 0.0, stone);
                    }
                }

                catch (Throwable ignored) {
                    w.spawnParticle(org.bukkit.Particle.ASH, c, 4, 0.10, 0.03, 0.10, 0.0);
                }
            }
        });

        EFFECTS.put("trades", (w, loc, t) -> {
            Location base = loc.clone().add(0.5, 0.95, 0.5);
            int points = 8;
            double radius = 0.48 + 0.03 * Math.sin(t * 0.5);

            for (int i = 0; i < points; i++) {
                double a = i * (2 * Math.PI / points) + t * 0.2;
                double x = radius * Math.cos(a);
                double z = radius * Math.sin(a);
                w.spawnParticle(Particle.HAPPY_VILLAGER, base.clone().add(x, 0.02, z), 1, 0.02, 0.02, 0.02, 0.0);
            }

            if (((int)(t * 8)) % 20 == 0) {
                w.spawnParticle(Particle.END_ROD, base.clone().add(0, 0.25, 0), 2, 0.15, 0.04, 0.15, 0.0);
            }
        });
    }

    private Location getLocFor(String type) {
        String locStr = dataConfig.getString("leaderboards." + type + ".loc", null);

        if (locStr == null) return null;

        try {
            return deserializeLoc(locStr);
        }

        catch (Exception ex) {
            getLogger().warning("Failed to parse location for leaderboard type '" + type + "': " + locStr);
            ex.printStackTrace();
            return null;
        }
    }

    private void loadData() {
        dataFile = new File(getDataFolder(), "data.yml");
        if (!dataFile.exists()) {
            dataFile.getParentFile().mkdirs();
            try {
                dataFile.createNewFile();
            }

            catch (IOException e) {
                getLogger().severe("Failed to create data.yml");
                e.printStackTrace();
            }
        }

        dataConfig = YamlConfiguration.loadConfiguration(dataFile);

        //old location data, can probably delete soon
        if (dataConfig.contains("leaderboard-block") && !dataConfig.contains("leaderboards.diamond.loc")) {
            String old = dataConfig.getString("leaderboard-block");
            if (old != null) {
                dataConfig.set("leaderboards.diamond.loc", old);
            }
        }

        //old leaderboards.diamond string, delete soon
        if (dataConfig.contains("leaderboards.diamond") && !dataConfig.isConfigurationSection("leaderboards.diamond")) {
            String flat = dataConfig.getString("leaderboards.diamond", null);

            if (flat != null) {
                dataConfig.set("leaderboards.diamond.loc", flat);
            }
            dataConfig.set("leaderboards.diamond", null);
        }

        //yaw check when I had the old yml, delete soon
        if (!dataConfig.contains("leaderboards.diamond.yaw")) {
            dataConfig.set("leaderboards.diamond.yaw", 0.0f);
        }

        saveData();

        //boot any leaderboards whose chunks are already loaded
        if (dataConfig.isConfigurationSection("leaderboards")) {
            for (String type : dataConfig.getConfigurationSection("leaderboards").getKeys(false)) {
                Location loc = getLocFor(type);
                if (loc == null) continue;
                World w = loc.getWorld();
                if (w == null) continue;
                int cx = loc.getBlockX() >> 4, cz = loc.getBlockZ() >> 4;
                if (w.isChunkLoaded(cx, cz) && loc.getBlock().getType() == Material.BEDROCK) {
                    Bukkit.getScheduler().runTask(this, () -> {
                        updateLeaderboardDisplay(type);
                        startBedrockEffect(loc.clone(), type);
                    });
                }
            }
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onBlockPlace(BlockPlaceEvent e) {
        if (e.getBlockPlaced().getType() != Material.REINFORCED_DEEPSLATE) return;
        Player p = e.getPlayer();
        String base = "players." + p.getUniqueId();
        int total = dataConfig.getInt(base + ".reinforced_placed_total", 0) + 1;
        dataConfig.set(base + ".reinforced_placed_total", total);
        dataConfig.set(base + ".name", p.getName());
        saveData();
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        Player p = e.getPlayer();
        String base = "players." + p.getUniqueId();

        if (!dataConfig.getBoolean(base + ".reinforced_seeded", false)) {
            int vanilla = 0;

            try {
                vanilla = p.getStatistic(Statistic.USE_ITEM, Material.REINFORCED_DEEPSLATE);
            }

            catch (Throwable ignored) {}
            int current = dataConfig.getInt(base + ".reinforced_placed_total", 0);
            int seeded = Math.max(current, vanilla); //keep whatever is larger
            dataConfig.set(base + ".reinforced_placed_total", seeded);
            dataConfig.set(base + ".name", p.getName());
            dataConfig.set(base + ".reinforced_seeded", true);
            saveData();
        }
    }

    private int getTotalPickaxeUses(Player p) {
        int total = 0;
        Material[] picks = {
                Material.WOODEN_PICKAXE,
                Material.STONE_PICKAXE,
                Material.IRON_PICKAXE,
                Material.GOLDEN_PICKAXE,
                Material.DIAMOND_PICKAXE,
                Material.NETHERITE_PICKAXE
        };

        for (Material m : picks) {
            try {
                total += p.getStatistic(Statistic.USE_ITEM, m);
            }
            catch (Throwable ignored) {}
        }
        return total;
    }

    @EventHandler
    public void onChunkLoad(org.bukkit.event.world.ChunkLoadEvent event) {
        if (leaderboardBlockLocation == null) return;
        if (!event.getWorld().equals(leaderboardBlockLocation.getWorld())) return;
        if (event.getChunk().getX() == (leaderboardBlockLocation.getBlockX() >> 4) && event.getChunk().getZ() == (leaderboardBlockLocation.getBlockZ() >> 4)) {
            Bukkit.getScheduler().runTask(this, () -> {
                if (leaderboardBlockLocation.getBlock().getType() == Material.BEDROCK) {
                    String lbType = findLeaderboardTypeByLocation(leaderboardBlockLocation).orElse("diamond");
                    updateLeaderboardDisplay(lbType);
                    startBedrockEffect(leaderboardBlockLocation.clone());
                }
            });
        }
    }
}