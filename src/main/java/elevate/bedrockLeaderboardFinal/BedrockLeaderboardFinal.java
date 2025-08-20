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

public final class BedrockLeaderboardFinal extends JavaPlugin implements Listener
{
    private final Map<String, BukkitTask> activeEffects = new HashMap<>();
    private File dataFile;
    private FileConfiguration dataConfig;
    private Location leaderboardBlockLocation;
    private final Map<String, String> lastTopByType = new HashMap<>();

    //passive effect
    private interface Effect
    {
        void tick(World w, Location origin, double t);
    }

    private final Map<String, Effect> EFFECTS = new HashMap<>();

    //gives each unique leaderboard their own tag
    private String tagFor(String type)
    {
        return type + "_leaderboard";
    }

    private void setLocFor(String type, Location loc)
    {
        dataConfig.set("leaderboards." + type + ".loc", serializeLoc(loc));
    }

    //delete soon. no usages, old method
    private String getLocStringFor(String type)
    {
        return dataConfig.getString("leaderboards." + type + ".loc", null);
    }

    private float getYawFor(String type)
    {
        return normalizeYaw((float) dataConfig.getDouble("leaderboards." + type + ".yaw", 0.0));
    }

    //returns true if an armor stand has any _leaderboard tag
    private boolean isLeaderboardEntity(Entity e)
    {
        Set<String> tags = e.getScoreboardTags();

        // for old build, can probably delete soon
        if (tags.contains("diamond_leaderboard"))
            return true;

        for (String t : tags) if (t.endsWith("_leaderboard"))
            return true;

        return false;
    }

    @Override
    public void onEnable()
    {
        Bukkit.getPluginManager().registerEvents(this, this);
        loadData();
        registerEffects();
        startArmorStandVisibilityTask();

        //LEADERBOARD TYPES//
        final List<String> TYPES = Arrays.asList("diamond", "elytra", "reinforced");

        //COMMAND// setleaderboard <type>
        Objects.requireNonNull(getCommand("setleaderboard")).setExecutor((sender, command, label, args) ->
        {
            if (!(sender instanceof Player p))
            {
                sender.sendMessage(ChatColor.RED + "Players only");
                return true;
            }

            if (!p.isOp() && !p.hasPermission("leaderboard.set"))
            {
                p.sendMessage(ChatColor.RED + "You don't have permission to do that");
                return true;
            }

            if (args.length < 1)
            {
                p.sendMessage(ChatColor.RED + "Usage: /setleaderboard <type>");
                p.sendMessage(ChatColor.GRAY + "Types: " + String.join(", ", TYPES));
                return true;
            }

            String type = args[0].toLowerCase(Locale.ROOT);
            if (!TYPES.contains(type))
            {
                p.sendMessage(ChatColor.RED + "Unknown type: " + ChatColor.AQUA + type);
                p.sendMessage(ChatColor.GRAY + "Types: " + String.join(", ", TYPES));
                return true;
            }

            Block target = p.getTargetBlockExact(6);

            if (target == null)
            {
                p.sendMessage(ChatColor.RED + "Look at a bedrock block within 6 blocks.");
                return true;
            }

            if (target.getType() != Material.BEDROCK)
            {
                p.sendMessage(ChatColor.RED + "That's not bedrock silly"); return true;
            }

            //save location/yaw per type
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
        Objects.requireNonNull(getCommand("setleaderboard")).setTabCompleter((sender, command, alias, args) ->
        {
            if (args.length == 1)
            {
                return TYPES.stream().map(String::toLowerCase).filter(t -> t.startsWith(args[0].toLowerCase(Locale.ROOT))).collect(Collectors.toList());
            }
            return Collections.emptyList();
        });

        //COMMAND// refreshleaderboard
        Objects.requireNonNull(getCommand("refreshleaderboard")).setExecutor((sender, cmd, label, args) ->
        {
            if (!(sender instanceof Player p))
            {
                sender.sendMessage(ChatColor.RED + "Players only");
                return true;
            }

            if (!p.isOp() && !p.hasPermission("leaderboard.refresh"))
            {
                p.sendMessage(ChatColor.RED + "You don't have permission to do that");
                return true;
            }

            Block target = p.getTargetBlockExact(6);
            if (target == null || target.getType() != Material.BEDROCK)
            {
                p.sendMessage(ChatColor.RED + "Look at the leaderboard bedrock within 6 blocks");
                return true;
            }

            Location loc = target.getLocation();
            Optional<String> typeOpt = findLeaderboardTypeByLocation(loc);

            if (typeOpt.isEmpty())
            {
                p.sendMessage(ChatColor.GRAY + "No leaderboard is assigned to this bedrock");
                return true;
            }

            String type = typeOpt.get();
            updateLeaderboardDisplay(type);
            startBedrockEffect(loc.clone(), type);
            p.sendMessage(ChatColor.GREEN + "Refreshed the " + ChatColor.AQUA + type + ChatColor.GREEN + " leaderboard");
            return true;
        });

        //COMMAND// rotateleaderboard [degrees]
        Objects.requireNonNull(getCommand("rotateleaderboard")).setExecutor((sender, cmd, label, args) ->
        {
            if (!(sender instanceof Player player))
            {
                sender.sendMessage(ChatColor.RED + "Players only");
                return true;
            }

            if (!player.isOp() && !player.hasPermission("leaderboard.rotate"))
            {
                player.sendMessage(ChatColor.RED + "You don't have permission to do that");
                return true;
            }

            float deltaYaw = 90f;

            if (args.length >= 1)
            {
                try
                {
                    deltaYaw = Float.parseFloat(args[0]);
                }

                catch (NumberFormatException nfe)
                {
                    player.sendMessage(ChatColor.RED + "Usage: /rotateleaderboard [degrees]");
                    return true;
                }
            }

            Block target = player.getTargetBlockExact(6);

            if (target == null || target.getType() != Material.BEDROCK)
            {
                player.sendMessage(ChatColor.RED + "Look at the leaderboard bedrock within 6 blocks");
                return true;
            }

            Optional<String> typeOpt = findLeaderboardTypeByLocation(target.getLocation());

            if (typeOpt.isEmpty())
            {
                player.sendMessage(ChatColor.GRAY + "No leaderboard is assigned to this bedrock"); return true;
            }

            String type = typeOpt.get();
            float storedYaw = (float) dataConfig.getDouble("leaderboards." + type + ".yaw", 0.0);
            float newStored = normalizeYaw(storedYaw + deltaYaw);
            dataConfig.set("leaderboards." + type + ".yaw", newStored);
            saveData();

            //rotate existing nearby lines
            Location base = target.getLocation().add(0.5, 1.0, 0.5);
            int rotated = 0;
            for (Entity e : base.getWorld().getNearbyEntities(base, 1.5, 3.0, 1.5))
            {
                if (!isLeaderboardEntity(e))
                    continue;

                try
                {
                    if (e instanceof TextDisplay td)
                    {
                        try
                        {
                            td.setRotation(newStored, 0f);
                        }

                        catch (Throwable ignored) {} rotated++;

                        continue;
                    }
                }

                catch (Throwable ignored) {}

                if (e instanceof ArmorStand as)
                {
                    Location l = as.getLocation().clone();
                    l.setYaw(newStored);
                    as.teleport(l);
                    rotated++;
                }
            }

            player.sendMessage(ChatColor.GREEN + "Rotation set to " + ChatColor.AQUA + newStored + "°" + ChatColor.GREEN + " for " + ChatColor.AQUA + type + ChatColor.GREEN + " (" + rotated + " line(s) updated).");

            if (rotated == 0)
                player.sendMessage(ChatColor.YELLOW + "No leaderboard text found near that bedrock");

            return true;
        });

        //COMMAND// refreshloot (reload data.yml from plugin's folder)
        Objects.requireNonNull(getCommand("refreshloot")).setExecutor((sender, cmd, label, args) ->
        {
            if (!(sender instanceof Player p))
            {
                sender.sendMessage(ChatColor.RED + "Players only"); return true;
            }

            if (!p.isOp() && !p.hasPermission("leaderboard.reload"))
            {
                p.sendMessage(ChatColor.RED + "You don't have permission to do that"); return true;
            }

            long start = System.currentTimeMillis();
            reloadLeaderboardData();
            long ms = System.currentTimeMillis() - start;
            p.sendMessage(ChatColor.GREEN + "Leaderboard data reloaded from disk " + ChatColor.GRAY + "(" + ms + " ms)" + ChatColor.GREEN + ".");
            return true;
        });

        //COMMAND// clearleaderboard
        Objects.requireNonNull(getCommand("clearleaderboard")).setExecutor((sender, cmd, label, args) ->
        {
            if (!(sender instanceof Player p))
            {
                sender.sendMessage(ChatColor.RED + "Players only"); return true;
            }

            if (!p.isOp() && !p.hasPermission("leaderboard.clear"))
            {
                p.sendMessage(ChatColor.RED + "You don't have permission to do that"); return true;
            }

            Block target = p.getTargetBlockExact(6);
            if (target == null || target.getType() != Material.BEDROCK)
            {
                p.sendMessage(ChatColor.RED + "Look at a bedrock block within 6 blocks"); return true;
            }

            Location loc = target.getLocation();
            Optional<String> typeOpt = findLeaderboardTypeByLocation(loc);

            if (typeOpt.isEmpty())
            {
                p.sendMessage(ChatColor.GRAY + "No leaderboard is assigned to this bedrock"); return true;
            }

            String type = typeOpt.get();

            //stop effect for only this type
            stopBedrockEffect(type);

            //remove tagged entities nearby
            loc.getWorld().getNearbyEntities(loc, 1.5, 10, 1.5).stream().filter(this::isLeaderboardEntity).forEach(Entity::remove);

            //clear persisted keys for this type
            dataConfig.set("leaderboards." + type, null);
            saveData();

            p.sendMessage(ChatColor.YELLOW + "Cleared the " + ChatColor.AQUA + type + ChatColor.YELLOW + " leaderboard.");
            return true;
        });
    }

    @Override
    public void onDisable()
    {
        stopAllBedrockEffects();
        saveData();
    }

    @EventHandler
    public void onPlayerInteract(PlayerInteractEvent event)
    {
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

        switch (type.toLowerCase(Locale.ROOT))
        {
            case "diamond":
            {
                if (item.getType() != Material.DIAMOND)
                {
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

            case "elytra":
            {
                if (item.getType() != Material.PHANTOM_MEMBRANE)
                {
                    player.sendMessage(ChatColor.RED + "Hold a " + ChatColor.AQUA + "phantom membrane" + ChatColor.RED + " to submit");
                    return;
                }

                //consume 1 membrane
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

            case "reinforced":
            {
                if (item.getType() != Material.GOLD_INGOT)
                {
                    player.sendMessage(ChatColor.RED + "Hold a " + ChatColor.GOLD + "gold ingot" + ChatColor.RED + " to submit");
                    return;
                }

                //consume 1 ingot
                if (item.getAmount() > 1) item.setAmount(item.getAmount() - 1);
                else player.getInventory().setItemInMainHand(null);

                //vanilla stat
                int used = player.getStatistic(org.bukkit.Statistic.USE_ITEM, Material.REINFORCED_DEEPSLATE);
                String base = "players." + player.getUniqueId();
                dataConfig.set(base + ".reinforced_placed", used);
                dataConfig.set(base + ".name", player.getName());
                saveData();
                player.sendMessage(ChatColor.GREEN + "Submitted lootboxes opened: " + ChatColor.AQUA + used);
                updateLeaderboardDisplay("reinforced");
                break;
            }
        }

        //place this leaderboard and re/start passive effect
        updateLeaderboardDisplay(type);
        startBedrockEffect(loc.clone(), type);
    }

    private void updateLeaderboardDisplay(String type)
    {
        Location lbLoc = getLocFor(type);
        if (lbLoc == null) return;
        float yaw = getYawFor(type);

        //remove existing display entities near the targeted leaderboard
        lbLoc.getWorld().getNearbyEntities(lbLoc, 1.5, 10, 1.5).stream().filter(this::isLeaderboardEntity).forEach(Entity::remove);

        //effects for specific leaderboard type
        Location effectLoc = lbLoc.clone().add(0.5, 1.0, 0.5);
        playRefreshFx(type, effectLoc);

        //build leaderboard by type
        Map<String, Integer> totals = new HashMap<>();
        if (dataConfig.isConfigurationSection("players"))
        {
            for (String uuid : dataConfig.getConfigurationSection("players").getKeys(false))
            {
                String name = dataConfig.getString("players." + uuid + ".name", uuid);
                int value = 0;

                if ("diamond".equalsIgnoreCase(type))
                {
                    value = dataConfig.getInt("players." + uuid + ".diamonds", 0);

                }

                else if ("elytra".equalsIgnoreCase(type))
                {
                    value = dataConfig.getInt("players." + uuid + ".elytra_flown_cm", 0);
                }

                else if ("reinforced".equalsIgnoreCase(type))
                {
                    value = dataConfig.getInt("players." + uuid + ".reinforced_placed", 0);
                }

                if (value > 0) totals.put(name, value);
            }
        }

        List<Map.Entry<String, Integer>> top = totals.entrySet().stream().sorted(Map.Entry.<String, Integer>comparingByValue().reversed()).limit(5).collect(Collectors.toList());

        //check if #1 spot changes, then play audio/broadcast
        if (!top.isEmpty())
        {
            String currentTop = top.get(0).getKey();
            String prevTop = lastTopByType.get(type);

            if (prevTop != null && !prevTop.equals(currentTop))
            {
                for (Player p : Bukkit.getOnlinePlayers())
                {
                    p.playSound(p.getLocation(), Sound.EVENT_RAID_HORN, 10.0f, 1.0f);
                }

                String crownLabel;

                if ("elytra".equalsIgnoreCase(type))
                {
                    crownLabel = "Sun Chaser";
                }

                else if ("reinforced".equalsIgnoreCase(type))
                {
                    crownLabel = "High Roller";
                }

                else
                {
                    crownLabel = "Diamond Killer";
                }

                Bukkit.broadcastMessage(ChatColor.RED + "A new " + crownLabel + " has been crowned!");
            }

            lastTopByType.put(type, currentTop);
        }

        //title + lines
        Location base = lbLoc.clone().add(0.5, 1.2, 0.5);
        String title = type.equals("elytra") ? ChatColor.AQUA + "Distance by Elytra" : type.equals("reinforced") ? ChatColor.DARK_AQUA + "Lootboxes Opened" : ChatColor.RED + "" + ChatColor.MAGIC + "a" + ChatColor.AQUA + "Diamond Killers" + ChatColor.RED + "" + ChatColor.MAGIC + "a";

        spawnTextLine(type, base.clone(), yaw, title);
        spawnTextLine(type, base.clone().add(0, 0.25, 0), yaw, " ");

        ChatColor[] rankColors = {
                ChatColor.GOLD, ChatColor.RED, ChatColor.DARK_PURPLE, ChatColor.BLUE, ChatColor.WHITE
        };

        for (int i = 0; i < top.size(); i++)
        {
            Map.Entry<String, Integer> entry = top.get(i);
            ChatColor color = (i < rankColors.length) ? rankColors[i] : ChatColor.WHITE;
            String name = entry.getKey();
            double yOffset = (top.size() - i + 1) * 0.25; //#1 on top
            String valueText;

            if ("elytra".equalsIgnoreCase(type))
            {
                valueText = formatDistance(entry.getValue());
            }

            else
            {
                valueText = String.valueOf(entry.getValue());
            }

            String line = "" + color + (i + 1) + ". " + name + " - " + valueText + ChatColor.RESET;
            spawnTextLine(type, base.clone().add(0, yOffset, 0), yaw, line);
        }
    }

    private void spawnArmorStand(String type, Location loc, float yaw, String text)
    {
        ArmorStand stand = loc.getWorld().spawn(loc, ArmorStand.class);
        stand.setInvisible(true);
        stand.setInvulnerable(true);
        stand.setGravity(false);
        stand.setCustomName(text);
        stand.setCustomNameVisible(true);
        stand.addScoreboardTag(tagFor(type));
        stand.addScoreboardTag("diamond_leaderboard");
        stand.setMarker(true);

        //teleport with yaw orientation
        Location l = stand.getLocation();
        l.setYaw(yaw);
        stand.teleport(l);
    }

    private void startBedrockEffect(Location loc, String type)
    {
        //stop any old task for this type first, this stops things from fucking up which is a great thing
        stopBedrockEffect(type);

        BukkitTask task = new BukkitRunnable()
        {
            double t = 0;

            @Override
            public void run()
            {
                //if that block is no longer bedrock then stop
                if (loc == null || loc.getBlock().getType() != Material.BEDROCK)
                {
                    cancel();
                    activeEffects.remove(type);
                    return;
                }

                World world = loc.getWorld();
                t += Math.PI / 16;

                switch (type.toLowerCase(Locale.ROOT))
                {
                    case "elytra":
                    {
                        //elytra passive effects
                        for (double angle = 0; angle < 2 * Math.PI; angle += Math.PI / 10)
                        {
                            double r = 0.5;
                            double x = r * Math.cos(angle + t);
                            double z = r * Math.sin(angle + t);
                            Location p = loc.clone().add(0.5 + x, 0.70 + (Math.sin(t + angle) * 0.12), 0.5 + z);
                            world.spawnParticle(Particle.CLOUD, p, 0, 0, 0.02, 0, 0.0);
                        }

                        world.spawnParticle(Particle.END_ROD, loc.clone().add(0.5, 0.85, 0.5), 2, 0.22, 0.25, 0.22, 0.0);

                        if (((int) (t * 8)) % 16 == 0)
                        {
                            world.spawnParticle(Particle.SWEEP_ATTACK, loc.clone().add(0.5, 0.85, 0.5), 1, 0, 0, 0, 0);
                        }
                        break;
                    }

                    case "diamond":
                    default:
                    {
                        //diamond killer passive effects
                        for (double angle = 0; angle < 2 * Math.PI; angle += Math.PI / 8)
                        {
                            double x = 0.5 * Math.cos(angle + t);
                            double z = 0.5 * Math.sin(angle + t);
                            Location flameLoc = loc.clone().add(0.5 + x, 1.0, 0.5 + z);
                            world.spawnParticle(Particle.FLAME, flameLoc, 1, 0, 0, 0, 0);
                        }

                        Location center = loc.clone().add(0.5, 1.0, 0.5);
                        world.spawnParticle(Particle.REVERSE_PORTAL, center, 5, 0.3, 0.5, 0.3, 0.01);
                        break;
                    }

                    case "reinforced":
                    {
                        //sculk aura
                        for (double a = 0; a < 2 * Math.PI; a += Math.PI / 10)
                        {
                            double r = 0.45;
                            double x = r * Math.cos(a + t);
                            double z = r * Math.sin(a + t);
                            Location p = loc.clone().add(0.5 + x, 0.88 + (Math.sin(t + a) * 0.08), 0.5 + z);
                            world.spawnParticle(Particle.SCULK_SOUL, p, 0, 0, 0.01, 0, 0.0);
                        }

                        //shimmer near center
                        world.spawnParticle(Particle.SOUL_FIRE_FLAME, loc.clone().add(0.5, 0.98, 0.5), 1, 0.08, 0.05, 0.08, 0.0);
                        break;
                    }
                }
            }
        }.runTaskTimer(this, 0L, 5L);
        activeEffects.put(type, task);
    }

    //stop leaderboard <type> effects
    private void stopBedrockEffect(String type)
    {
        BukkitTask task = activeEffects.remove(type);
        if (task != null) task.cancel();
    }

    //stop all current effects
    private void stopAllBedrockEffects()
    {
        for (BukkitTask t : activeEffects.values())
        {
            if (t != null) t.cancel();
        }
        activeEffects.clear();
    }

    private void saveData()
    {
        try
        {
            dataConfig.save(dataFile);
        }
        catch (IOException e)
        {
            getLogger().warning("Failed to save data.yml ");
        }
    }

    private String serializeLoc(Location loc)
    {
        return loc.getWorld().getName() + "," + loc.getBlockX() + "," + loc.getBlockY() + "," + loc.getBlockZ();
    }

    private Location deserializeLoc(String str)
    {
        String[] parts = str.split(",");
        return new Location(Bukkit.getWorld(parts[0]), Integer.parseInt(parts[1]), Integer.parseInt(parts[2]), Integer.parseInt(parts[3]));
    }

    @EventHandler
    public void onBlockBreak(BlockBreakEvent event)
    {
        Block block = event.getBlock();
        if (block.getType() != Material.BEDROCK) return;
        Location loc = block.getLocation();
        Optional<String> typeOpt = findLeaderboardTypeByLocation(loc);
        if (typeOpt.isEmpty()) return;
        String type = typeOpt.get();

        //stop only this leaderboard's effects
        stopBedrockEffect(type);

        //remove tagged entities near that block
        loc.getWorld().getNearbyEntities(loc, 1.5, 10, 1.5).stream().filter(this::isLeaderboardEntity).forEach(Entity::remove);

        //clear persisted keys for that type
        dataConfig.set("leaderboards." + type, null);
        saveData();
        event.getPlayer().sendMessage(ChatColor.RED + "Removed " + ChatColor.AQUA + type + ChatColor.RED + " leaderboard block.");
    }

    private void startArmorStandVisibilityTask()
    {
        new BukkitRunnable()
        {
            @Override
            public void run()
            {
                if (leaderboardBlockLocation == null) return;

                List<Player> players = new ArrayList<>(Bukkit.getOnlinePlayers());

                //scan nearby entities around the leaderboard
                leaderboardBlockLocation.getWorld().getNearbyEntities(leaderboardBlockLocation, 20, 20, 20).forEach(e ->
                {
                            Set<String> tags = e.getScoreboardTags();
                            boolean isLb = tags.contains("diamond_leaderboard") || tags.stream().anyMatch(t -> t.endsWith("_leaderboard"));

                            if (!isLb) return;

                            boolean shouldShow = players.stream().anyMatch(player -> player.getWorld().equals(e.getWorld()) && player.getLocation().distance(e.getLocation()) <= 10);

                            if (e instanceof TextDisplay td)
                            {
                                try
                                {
                                    td.setTextOpacity((byte) (shouldShow ? 255 : 0));
                                }
                                catch (Throwable ignored) {}
                            }

                            else if (e instanceof ArmorStand as)
                            {
                                as.setCustomNameVisible(shouldShow);
                            }
                });
            }
        }.runTaskTimer(this, 0L, 20L); // every 20 ticks = 1 second
    }

    //textDisplay spawner
    private void spawnTextLine(String type, Location loc, float yaw, String coloredText)
    {
        try
        {
            TextDisplay display = loc.getWorld().spawn(loc, TextDisplay.class);
            display.setText(coloredText);

            try { display.setBillboard(Display.Billboard.FIXED); } catch (Throwable ignored) {}
            try { display.setAlignment(TextDisplay.TextAlignment.CENTER); } catch (Throwable ignored) {}
            try { display.setShadowed(true); } catch (Throwable ignored) {}
            try { display.setSeeThrough(false); } catch (Throwable ignored) {}
            try { display.setLineWidth(2048); } catch (Throwable ignored) {}
            try { display.setViewRange(12.0f); } catch (Throwable ignored) {}
            try { display.setRotation(yaw, 0f); } catch (Throwable ignored) {}

            //tags for clear/visibility
            display.addScoreboardTag(tagFor(type));
            display.addScoreboardTag("diamond_leaderboard");
        }

        catch (Throwable t)
        {
            //fallback to ArmorStand if TextDisplay unavailable
            spawnArmorStand(type, loc, yaw, coloredText);
        }
    }


    private float normalizeYaw(float yaw)
    {
        yaw %= 360f;
        if (yaw < 0f) yaw += 360f;
        return yaw;
    }

    private void reloadLeaderboardData()
    {
        //stop all current effects first
        stopAllBedrockEffects();

        //remove existing displays around any known locations
        if (dataConfig.isConfigurationSection("leaderboards"))
        {
            for (String type : dataConfig.getConfigurationSection("leaderboards").getKeys(false))
            {
                Location loc = getLocFor(type);
                if (loc != null)
                {
                    loc.getWorld().getNearbyEntities(loc, 1.5, 10, 1.5).stream().filter(this::isLeaderboardEntity).forEach(Entity::remove);
                }
            }
        }

        //re-read/check data.yml
        if (dataFile == null) dataFile = new File(getDataFolder(), "data.yml");
        dataConfig = YamlConfiguration.loadConfiguration(dataFile);

        //rebuild any leaderboards where chunks are loaded again
        if (dataConfig.isConfigurationSection("leaderboards"))
        {
            for (String type : dataConfig.getConfigurationSection("leaderboards").getKeys(false))
            {
                Location loc = getLocFor(type);
                if (loc == null) continue;
                World w = loc.getWorld();
                if (w == null) continue;
                int cx = loc.getBlockX() >> 4, cz = loc.getBlockZ() >> 4;

                if (w.isChunkLoaded(cx, cz) && loc.getBlock().getType() == Material.BEDROCK)
                {
                    Bukkit.getScheduler().runTask(this, () ->
                    {
                        updateLeaderboardDisplay(type);
                        startBedrockEffect(loc.clone(), type);
                    });
                }
            }
        }
    }

    private String formatDistance(int cm)
    {
        // Show as km
        double meters = cm / 100.0;
        if (meters >= 1000.0)
        {
            double km = meters / 1000.0;
            return String.format(java.util.Locale.ROOT, "%.2f km", km);
        }

        if (meters >= 10.0)
        {
            return String.format(java.util.Locale.ROOT, "%.0f m", Math.floor(meters));
        }

        return String.format(java.util.Locale.ROOT, "%.1f m", meters);
    }

    private String getTypeAtLocationOrDefault(Location loc, String deflt)
    {
        return findLeaderboardTypeByLocation(loc).orElse(deflt);
    }

    private Optional<String> findLeaderboardTypeByLocation(Location loc)
    {
        if (!dataConfig.isConfigurationSection("leaderboards")) return Optional.empty();

        for (String type : dataConfig.getConfigurationSection("leaderboards").getKeys(false))
        {
            String s = dataConfig.getString("leaderboards." + type + ".loc", null);
            if (s == null) continue;

            try
            {
                Location saved = deserializeLoc(s);
                if (saved != null && Objects.equals(saved.getWorld(), loc.getWorld()) && saved.getBlockX() == loc.getBlockX() && saved.getBlockY() == loc.getBlockY() && saved.getBlockZ() == loc.getBlockZ())
                {
                    return Optional.of(type);
                }

            } catch (Exception ignored) {}
        }
        return Optional.empty();
    }

    private void playRefreshFx(String type, Location effectLoc)
    {
        World world = effectLoc.getWorld();

        switch (type.toLowerCase(java.util.Locale.ROOT))
        {
            case "elytra":
            {
                World w = effectLoc.getWorld();

                //vertical offset down for the burst
                Location base = effectLoc.clone().add(0, -0.15, 0);

                //puff of clouds
                for (int i = 0; i < 24; i++)
                {
                    double ang = (i / 24.0) * (Math.PI * 2);
                    double r = 0.9;
                    double x = r * Math.cos(ang);
                    double z = r * Math.sin(ang);
                    w.spawnParticle(Particle.CLOUD, base.clone().add(x, 0.08, z), 2, 0.06, 0.02, 0.06, 0.0);
                }

                //soft sparkles
                w.spawnParticle(Particle.END_ROD, base.clone().add(0, 0.18, 0), 10, 0.30, 0.22, 0.30, 0.0);

                //quick chime
                for (Player p : Bukkit.getOnlinePlayers())
                {
                    if (!p.getWorld().equals(w) || p.getLocation().distance(base) > 16) continue;

                    //amethyst bell/xp twinkle
                    try
                    {
                        p.playSound(base, Sound.BLOCK_AMETHYST_BLOCK_CHIME, 0.9f, 1.20f);
                    }
                    catch (Throwable ignored) {}

                    p.playSound(base, Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 0.6f, 1.55f);

                    //delay for second twinkle
                    Bukkit.getScheduler().runTaskLater(this, () -> p.playSound(base, Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 0.5f, 1.75f), 4L);
                }
                break;
            }

            case "diamond":
            default:
            {
                //fire burst
                for (Player p : Bukkit.getOnlinePlayers())
                {
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

            case "reinforced":
            {
                //sculk burst (no explosion)
                world.spawnParticle(Particle.SCULK_SOUL, effectLoc, 30, 0.45, 0.28, 0.45, 0.0);
                world.spawnParticle(Particle.SOUL_FIRE_FLAME, effectLoc, 10, 0.28, 0.18, 0.28, 0.01);
                for (Player p : Bukkit.getOnlinePlayers())
                {
                    if (p.getWorld().equals(world) && p.getLocation().distance(effectLoc) <= 16)
                    {
                        p.playSound(effectLoc, Sound.BLOCK_SCULK_SENSOR_CLICKING, 0.7f, 1.25f);
                    }
                }
                break;
            }
        }
    }


    private void startBedrockEffect(Location loc)
    {
        String lbType = findLeaderboardTypeByLocation(loc).orElse("diamond");
        startBedrockEffect(loc, lbType);
    }

    private void registerEffects()
    {
        //default fallback if a type has no entry
        EFFECTS.put("default", (w, loc, t) ->
        {
            //portal shimmer
            Location c = loc.clone().add(0.5, 1.0, 0.5);
            w.spawnParticle(Particle.REVERSE_PORTAL, c, 5, 0.25, 0.35, 0.25, 0.01);
        });

        //fiery orbit + purple shimmer
        EFFECTS.put("diamond", (w, loc, t) ->
        {
            for (double a = 0; a < 2 * Math.PI; a += Math.PI / 8)
            {
                double x = 0.5 * Math.cos(a + t);
                double z = 0.5 * Math.sin(a + t);
                Location p = loc.clone().add(0.5 + x, 1.0, 0.5 + z);
                w.spawnParticle(Particle.FLAME, p, 1, 0, 0, 0, 0);
            }
            w.spawnParticle(Particle.REVERSE_PORTAL, loc.clone().add(0.5, 1.0, 0.5), 4, 0.25, 0.35, 0.25, 0.01);
        });

        //updraft + sparkles // lower Y axis? (offset used for effect radius)
        EFFECTS.put("elytra", (w, loc, t) ->
        {
            for (double a = 0; a < 2 * Math.PI; a += Math.PI / 10)
            {
                double r = 0.5;
                double x = r * Math.cos(a + t);
                double z = r * Math.sin(a + t);
                Location p = loc.clone().add(0.5 + x, 0.80 + (Math.sin(t + a) * 0.12), 0.5 + z);
                w.spawnParticle(Particle.CLOUD, p, 0, 0, 0.02, 0, 0.0);
            }

            w.spawnParticle(Particle.END_ROD, loc.clone().add(0.5, 1.00, 0.5), 2, 0.22, 0.22, 0.22, 0.0);
        });

        EFFECTS.put("reinforced", (w, loc, t) ->
        {
            double baseY = 0.95;
            for (double a = 0; a < Math.PI * 2; a += Math.PI / 12)
            {
                double r = 1.2 + 0.1 * Math.sin(t * 0.75 + a * 2);
                double x = r * Math.cos(a + t * 0.25);
                double z = r * Math.sin(a + t * 0.25);

                //shimmering ring
                w.spawnParticle(Particle.PORTAL, loc.clone().add(0.5 + x, baseY, 0.5 + z), 2, 0.03, 0.03, 0.03, 0.0);

                //occasional spark
                if (((int)(t * 8 + a * 10)) % 28 == 0)
                {
                    w.spawnParticle(Particle.TOTEM_OF_UNDYING, loc.clone().add(0.5 + x * 0.9, baseY + 0.15, 0.5 + z * 0.9), 2, 0.02, 0.02, 0.02, 0.0);
                }
            }
        });
    }

    private Location getLocFor(String type)
    {
        String locStr = dataConfig.getString("leaderboards." + type + ".loc", null);

        if (locStr == null) return null;

        try
        {
            return deserializeLoc(locStr);
        }
        catch (Exception ex)
        {
            getLogger().warning("Failed to parse location for leaderboard type '" + type + "': " + locStr);
            ex.printStackTrace();
            return null;
        }
    }

    //load data.yml where leaderboard locations and player stats will be
    private void loadData()
    {
        //check if plugin data folder and/or data file exists
        dataFile = new File(getDataFolder(), "data.yml");
        if (!dataFile.exists())
        {
            dataFile.getParentFile().mkdirs();
            try
            {
                dataFile.createNewFile();
            }
            catch (IOException e)
            {
                getLogger().severe("Failed to create data.yml");
                e.printStackTrace();
            }
        }

        //load config
        dataConfig = YamlConfiguration.loadConfiguration(dataFile);

        //old location data, can probably delete soon
        if (dataConfig.contains("leaderboard-block") && !dataConfig.contains("leaderboards.diamond.loc"))
        {
            String old = dataConfig.getString("leaderboard-block");
            if (old != null)
            {
                dataConfig.set("leaderboards.diamond.loc", old);
            }
        }

        // old leaderboards.diamond string, delete soon
        if (dataConfig.contains("leaderboards.diamond") && !dataConfig.isConfigurationSection("leaderboards.diamond"))
        {
            String flat = dataConfig.getString("leaderboards.diamond", null);

            if (flat != null)
            {
                dataConfig.set("leaderboards.diamond.loc", flat);
            }
            dataConfig.set("leaderboards.diamond", null);
        }

        //yaw check when I had the old yml, delete soon
        if (!dataConfig.contains("leaderboards.diamond.yaw"))
        {
            dataConfig.set("leaderboards.diamond.yaw", 0.0f);
        }

        saveData();

        //boot any leaderboards whose chunks are already loaded
        if (dataConfig.isConfigurationSection("leaderboards"))
        {
            for (String type : dataConfig.getConfigurationSection("leaderboards").getKeys(false))
            {
                Location loc = getLocFor(type);
                if (loc == null) continue;
                World w = loc.getWorld();
                if (w == null) continue;
                int cx = loc.getBlockX() >> 4, cz = loc.getBlockZ() >> 4;
                if (w.isChunkLoaded(cx, cz) && loc.getBlock().getType() == Material.BEDROCK)
                {
                    //run on the main tick after load
                    Bukkit.getScheduler().runTask(this, () ->
                    {
                        updateLeaderboardDisplay(type);
                        startBedrockEffect(loc.clone(), type);
                    });
                }
            }
        }
    }

    //consume exactly 1 item from the player's main hand if it matches the target material.
    private boolean consumeOneMainHand(Player p, Material mat)
    {
        ItemStack stack = p.getInventory().getItemInMainHand();
        if (stack == null || stack.getType() != mat) return false;
        int amt = stack.getAmount();

        if (amt <= 1)
        {
            p.getInventory().setItemInMainHand(null);
        }

        else
        {
            stack.setAmount(amt - 1);
        }

        return true;
    }

    //will be used in the future to consume all diamonds in hand for diamond killers, using old code for now.
    private boolean consumeAllMainHand(Player p, Material mat)
    {
        ItemStack stack = p.getInventory().getItemInMainHand();
        if (stack == null || stack.getType() != mat) return false;
        p.getInventory().setItemInMainHand(null);
        return true;
    }

    @EventHandler(ignoreCancelled = true)
    public void onBlockPlace(BlockPlaceEvent e)
    {
        if (e.getBlockPlaced().getType() != Material.REINFORCED_DEEPSLATE) return;
        Player p = e.getPlayer();
        String base = "players." + p.getUniqueId();
        int total = dataConfig.getInt(base + ".reinforced_placed_total", 0) + 1;
        dataConfig.set(base + ".reinforced_placed_total", total);
        dataConfig.set(base + ".name", p.getName());
        saveData();
    }

    //going to use this for future leaderboards when a user uses an item to submit
    private void playSubmitFx(String type, Location effectLoc)
    {
        World w = effectLoc.getWorld();
        switch (type.toLowerCase(java.util.Locale.ROOT))
        {
            case "reinforced":
            {
                w.spawnParticle(Particle.SCULK_SOUL, effectLoc, 42, 0.5, 0.3, 0.5, 0.0);
                w.spawnParticle(Particle.SOUL_FIRE_FLAME, effectLoc, 16, 0.35, 0.25, 0.35, 0.01);

                for (Player p : Bukkit.getOnlinePlayers())
                {
                    if (p.getWorld().equals(w) && p.getLocation().distance(effectLoc) <= 16)
                    {
                        p.playSound(effectLoc, Sound.BLOCK_SCULK_SHRIEKER_SHRIEK, 0.7f, 1.0f);
                        p.playSound(effectLoc, Sound.BLOCK_SCULK_SENSOR_CLICKING, 0.8f, 1.35f);
                    }
                }
                break;
            }

            default: break;
        }
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e)
    {
        Player p = e.getPlayer();
        String base = "players." + p.getUniqueId();

        //only seed once on player join, to grab the data before plugin was initialized
        if (!dataConfig.getBoolean(base + ".reinforced_seeded", false))
        {
            int vanilla = 0;

            try
            {
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

    @EventHandler
    public void onChunkLoad(org.bukkit.event.world.ChunkLoadEvent event)
    {
        if (leaderboardBlockLocation == null) return;
        if (!event.getWorld().equals(leaderboardBlockLocation.getWorld())) return;

        if (event.getChunk().getX() == (leaderboardBlockLocation.getBlockX() >> 4) && event.getChunk().getZ() == (leaderboardBlockLocation.getBlockZ() >> 4))
        {

            //starts effect once the chunk with the bedrock is ready, if leaderboard isn't in spawn (it should be tho for my specific case)
            Bukkit.getScheduler().runTask(this, () ->
            {
                if (leaderboardBlockLocation.getBlock().getType() == Material.BEDROCK)
                {
                    String lbType = findLeaderboardTypeByLocation(leaderboardBlockLocation).orElse("diamond");
                    updateLeaderboardDisplay(lbType);
                    startBedrockEffect(leaderboardBlockLocation.clone());
                }
            });
        }
    }
}