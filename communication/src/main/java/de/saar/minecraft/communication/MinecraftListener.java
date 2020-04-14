package de.saar.minecraft.communication;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import org.apache.commons.io.FileUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.bukkit.Bukkit;
import org.bukkit.Difficulty;
import org.bukkit.GameMode;
import org.bukkit.GameRule;
import org.bukkit.inventory.ItemStack;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.WorldCreator;
import org.bukkit.block.Biome;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockDamageEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.PlayerGameModeChangeEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.server.BroadcastMessageEvent;
import org.bukkit.event.weather.WeatherChangeEvent;
import org.bukkit.event.world.StructureGrowEvent;
import org.bukkit.event.world.WorldLoadEvent;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.Vector;

public class MinecraftListener implements Listener {
    private static Logger logger = LogManager.getLogger(MinecraftListener.class);
    private DefaultPlugin plugin;
    Client client;
    WorldCreator creator;
    HashMap<String, World> activeWorlds = new HashMap<>();
    int worldCounter = 0;

    // Materials that can neither be placed or removed by the player
    Set<Material> fixedMaterials = Set.of(Material.BEDROCK, Material.GRASS, Material.GRASS_BLOCK,
        Material.DIRT, Material.COARSE_DIRT, Material.TNT, Material.LAVA);

    MinecraftListener(Client client, DefaultPlugin plugin) {
        super();
        if (client == null) {
            throw new RuntimeException("No client was passed to the Listener");
        }
        this.client = client;
        this.plugin = plugin;

        // remove all potentially existing player worlds
        File directory = Paths.get(".").toAbsolutePath().normalize().toFile();
        logger.debug(directory.getAbsolutePath());
        for (File f : Objects.requireNonNull(directory.listFiles())) {
            if (f.getName().startsWith("playerworld_")) {
                logger.info("File {} to be deleted", f.getName());
                boolean deleted = f.delete();
                if (!deleted) {
                    logger.error("File {} was not deleted.", f.getName());
                }
            }
        }
        // Prepare initial world where players join
        World baseWorld = Bukkit.getWorld("world");
        prepareWorld(baseWorld);
        baseWorld.getWorldBorder().setSize(100);
        baseWorld.setSpawnLocation(0, 66,0);
    }

    /**
     * Notifies the client when a player joins and prepares a new world for this player.
     * Loads the structure that the broker selects for this player.
     * Preloads the next player world.
     */
    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        new BukkitRunnable() {
            @Override
            public void run() {
                handlePlayerJoin(event);
            }
        }.runTaskAsynchronously(plugin);
    }

    /**
     * Executes task in the main thread and blocks until the task has finished.
     */
    private void execSync(Runnable task) {
        var scheduler = plugin.getServer().getScheduler();
        try {
            scheduler.callSyncMethod(plugin, Executors.callable(task)).get();
        } catch (InterruptedException e) {
            throw new RuntimeException("Interrupted exception happened");
        } catch (ExecutionException e) {
            throw new RuntimeException("Execution exception happened");
        }
    }

    /**
     * Executes the runnable in the main thread but does not wait for the result.
     */
    private void execLater(Runnable task) {
        execLater(task, 0);
    }

    private void execLater(Runnable task, long delayTicks) {
        var scheduler = plugin.getServer().getScheduler();
        scheduler.scheduleSyncDelayedTask(plugin, task, delayTicks);
    }

    /**
     * This method runs in its own thread.  Calls to bukkit need to be
     * scheduled with the Minecraft server.
     */
    private void handlePlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        String playerName = player.getDisplayName();
        execLater(() -> {
                player.sendMessage("Welcome to the server, " + playerName);
                player.sendMessage("We will teleport you to your own world shortly");
                player.sendTitle("Welcome",
                                 "to MC-Saar-Instruct!"
                                 ,10, 120,20
                                 );
            });
        execLater(() ->
                player.sendMessage("you can move around with w,a,s,d and look around with your mouse."),
                50);
        execLater(() ->
                player.sendMessage("Place blocks with the RIGHT mouse button, delete with LEFT mouse button."),
                100);
        execLater(() ->
                player.sendMessage("press spacebar twice to fly and shift to dive."),
                150);
        String playerIp = "";
        InetSocketAddress address = player.getAddress();
        if (address != null) {
            playerIp = address.toString();
        }
        logger.info("Player ip full {}", playerIp);
        String structureFile;
        try {
            structureFile = client.registerGame(playerName, playerIp);
        } catch (UnknownHostException e) {
            execLater(() -> player.sendMessage("You could not connect to the experiment server"));
            logger.error("Player {} could not connect: {}", playerName, e);
            return;
        }
        int gameId = client.getGameIdForPlayer(playerName);

        // Get correct structure file
        String filename = String.format("/de/saar/minecraft/worlds/%s.csv", structureFile);
        InputStream in = MinecraftListener.class.getResourceAsStream(filename);

        World world;
        try {
            world = plugin.getServer().getScheduler().callSyncMethod(plugin, () -> {
                // pre-populate the next world for the next player
                String worldName = "playerworld_" + ++worldCounter;
                creator = new WorldCreator(worldName);
                creator.generator(new FlatChunkGenerator());
                creator.generateStructures(false);
                return creator.createWorld();
            }).get();
        } catch (InterruptedException e) {
            throw new RuntimeException("World creation interrupted");
        } catch (ExecutionException e) {
            throw new RuntimeException("World creation interrupted");
        }

        if (in != null) {
            BufferedReader reader = new BufferedReader(new InputStreamReader(in));
            execSync(() -> {
                // First, populate the world
                try {
                    loadPrebuiltStructure(reader, world);
                    logger.info("Loaded structure: {}", filename);
                } catch (IOException e) {
                    logger.error("World file could not be loaded: {} {}", filename, e);
                    client.sendWorldFileError(
                            gameId, "World file could not be loaded " + filename);
                    player.sendMessage("World file could not be loaded");
                }

            });
        } else {
            logger.error("World file could not be found: {}", filename);
            client.sendWorldFileError(gameId, "World file could not be found " + filename);
            execSync(() -> player.sendMessage("World file could not be found"));
        }
        execSync(() -> {
                Location teleportLocation = world.getSpawnLocation();
                teleportLocation.setDirection(new Vector(-70,0,-70));
                boolean worked = player.teleport(teleportLocation);
                if (!worked) {
                    logger.error("Teleportation failed");
                    client.sendMinecraftServerError(
                                                    gameId,
                                                    String.format("Player is in wrong world: %s instead of %s",
                                                                  player.getWorld().getName(), world.getName()));
                    player.sendMessage("Teleportation failed");
                }
                logger.info("Now in world {}", player.getWorld().getName());
                logger.debug("Now at block type: {}", teleportLocation.getBlock().getType());
            });
        execLater(() -> {
                // Add world to active worlds
                activeWorlds.put(world.getName(), world);
                player.setGameMode(GameMode.CREATIVE);
                // put a stone into the player's hand
                var inventory = player.getInventory();
                inventory.clear();
                inventory.setItem(0, new ItemStack(Material.STONE));
            });
        client.playerReady(gameId);
    }

    /**
     * Sets all world settings to peaceful.
     * @param world a Minecraft World for a player
     */
    private void prepareWorld(World world) {
        // Only positive coordinates with chunk size 16
        world.getWorldBorder().setCenter(world.getSpawnLocation());
        world.getWorldBorder().setSize(32);
        world.setThundering(false);
        world.setSpawnFlags(false, false);
        world.setDifficulty(Difficulty.PEACEFUL);
        world.setTime(1200);
        world.setGameRule(GameRule.DO_DAYLIGHT_CYCLE, false);
        world.setGameRule(GameRule.DO_MOB_SPAWNING, false);
        world.setGameRule(GameRule.DO_WEATHER_CYCLE, false);
        world.setGameRule(GameRule.NATURAL_REGENERATION, false);
        world.setGameRule(GameRule.ANNOUNCE_ADVANCEMENTS, false);
        Location location = world.getSpawnLocation();
        world.setBiome(location.getBlockX(), location.getBlockY(), location.getBlockZ(), Biome.PLAINS);
    }

    /**
     * Notifies the client when a player leaves and deletes their former world.
     */
    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        event.getQuitMessage();
        int gameId = client.getGameIdForPlayer(event.getPlayer().getName());
        client.finishGame(gameId);

        Player player = event.getPlayer();
        World world = player.getWorld();
        deleteWorld(world);

        activeWorlds.remove(world.getName());
        logger.info("Active worlds {}", activeWorlds.toString());
        logger.info("worlds bukkit {}", Bukkit.getWorlds().toString());
    }

    /**
     * Delete a playerworld from both Minecraft and disk.
     * @param world a Minecraft World of a player
     * @return true if the passed world could be deleted completely
     */
    public boolean deleteWorld(World world) {
        // Check unloading preconditions
        if (world == null) {
            return false;
        }

        if (world.getPlayers().size() > 0) {
            World baseWorld = Bukkit.getWorld("world");
            assert baseWorld != null;
            Location baseLocation = baseWorld.getSpawnLocation();
            // Teleport player away so the world can be unloaded now;
            // Alternative: only unload the world after the PlayerQuit Event is executed
            for (Player player: world.getPlayers()) {
                player.teleport(baseLocation);
                player.sendMessage("Your world was deleted. Please log out.");
            }
        }
        // unload world
        logger.debug("Entities {}", world.getEntities().toString());
        boolean isUnloaded = Bukkit.unloadWorld(world, false);
        logger.info("World {} is unloaded: {}", world.getName(), isUnloaded);
        if (!isUnloaded) {
            return false;
        }

        // Delete files from disk
        String dirName = world.getName();
        logger.info("world dir {}", dirName);
        File f = new File(dirName);
        logger.info("Path {}", f.getAbsolutePath());
        try {
            FileUtils.deleteDirectory(f);
            logger.info("deleted");
        } catch (IOException e) {
            logger.error(e.getMessage());
            return false;
        }
        return true;
    }

    /**
     * Notifies the client if a new block is placed.
     * Prevents the placement of blocks from fixedMaterials.
     */
    @EventHandler
    public void onBlockPlaced(BlockPlaceEvent event) {
        Block block = event.getBlock();
        Player player = event.getPlayer();
        if (block.getWorld().getName().equals("world")) {
            player.sendMessage("Please wait until the experiment begins");
            event.setCancelled(true);
            return;
        }

        if (fixedMaterials.contains(block.getType())) {
            player.sendMessage("You cannot place blocks of this type");
            event.setCancelled(true);
            return;
        }
        logger.info("Block was placed with type {} {}",
            block.getType().name(),
            block.getType().ordinal());

        int gameId = client.getGameIdForPlayer(player.getName());
        logger.debug("gameId {} coordinates {}-{}-{}",
            gameId, block.getX(),
            block.getY(),
            block.getZ());
        client.sendBlockPlaced(gameId, block.getX(), block.getY(), block.getZ(),
            block.getType().ordinal());
    }

    /**
     * Notifies the client if a block is broken.
     * Prevents breaking of blocks from fixedMaterials.
     */
    @EventHandler
    public void onBlockDestroyed(BlockBreakEvent event) {
        Block block = event.getBlock();
        Player player = event.getPlayer();
        if (block.getWorld().getName().equals("world")) {
            player.sendMessage("Please wait until the experiment begins");
            event.setCancelled(true);
            return;
        }
        if (fixedMaterials.contains(block.getType())) {
            event.setCancelled(true);
            player.sendMessage("You cannot destroy this");
            return;
        }
        logger.info("Block was destroyed with type {} {}",
            block.getType().name(), block.getType().ordinal());
        
        int gameId = client.getGameIdForPlayer(player.getName());
        client.sendBlockDestroyed(
            gameId, block.getX(), block.getY(), block.getZ(), block.getType().ordinal());
    }

    // TODO: what if block is not broken but just damaged?
    @EventHandler
    public void onBlockDamaged(BlockDamageEvent event) {
        event.setCancelled(true);
    }

    /**
     * Calls prepareWorld when a new world was loaded.
     */
    @EventHandler
    public void onWorldLoadEvent(WorldLoadEvent event) {
        World world = event.getWorld();
        prepareWorld(world);
        logger.info("World was loaded {}", world.getName());
    }

    /**
     * Prevents weather changes to raining, thunder is already disabled.
     */
    @EventHandler
    public void onWeatherChange(WeatherChangeEvent event) {
        logger.info("Attempted Weather Change to {}", event.toWeatherState());
        if (event.toWeatherState()) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onStructureGrow(StructureGrowEvent event) {
        event.setCancelled(true);
    }

    @EventHandler
    public void onPlayerGameModeChangeEvent(PlayerGameModeChangeEvent event) {
        event.getPlayer().setGameMode(GameMode.CREATIVE);
    }

    /**
     * Forwards chat messages from the player to the broker.
     */
    @EventHandler
    public void onPlayerChat(AsyncPlayerChatEvent event) {
        String playerName = event.getPlayer().getName();
        int gameId = client.getGameIdForPlayer(playerName);
        client.sendTextMessage(gameId, event.getMessage());
        event.setCancelled(true);  // Prevent that other players see the message
    }


    public void onBroadcast(BroadcastMessageEvent event) {
        event.setCancelled(true);
    }

    /**
     * Reads blocks from a file and creates them in the given world.
     * @param reader BufferedReader for a csv-file of the line structure: x,y,z,block type name
     * @param world the world where the structure should be built
     * @throws IOException if the structure file is missing or contains formatting errors
     */
    private void loadPrebuiltStructure(BufferedReader reader, World world) throws IOException {
        try (reader) {
            String line;
            while ((line = reader.readLine()) != null) {
                // skip comments
                if (line.startsWith("#")) {
                    continue;
                }
                // use comma as separator
                String[] blockInfo = line.split(",");
                int x = Integer.parseInt(blockInfo[0]);
                int y = Integer.parseInt(blockInfo[1]);
                int z = Integer.parseInt(blockInfo[2]);
                String typeName = blockInfo[3];

                Location location = new Location(world, x, y, z);
                Material newMaterial = Material.getMaterial(typeName);
                if (newMaterial == null) {
                    throw new IOException(typeName + " is not a valid Material.");
                } else {
                    location.getBlock().setType(newMaterial);
                }
            }

        } catch (IOException e) {
            logger.error(e.getMessage());
            throw e;
        }
    }
}
