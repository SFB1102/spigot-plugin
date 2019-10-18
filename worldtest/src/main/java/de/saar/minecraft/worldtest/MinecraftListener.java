package de.saar.minecraft.worldtest;

import org.bukkit.*;
import org.bukkit.block.Biome;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.*;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockDamageEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.weather.WeatherChangeEvent;
import org.bukkit.event.world.StructureGrowEvent;
import org.bukkit.event.world.WorldInitEvent;

import java.util.HashMap;


public class MinecraftListener implements Listener {
    WorldCreator creator;
    World nextWorld;  // Preloaded world for the next joining player

    HashMap<String, World> activeWorlds = new HashMap<String, World>();

    public MinecraftListener(){
        super();

        creator = new WorldCreator("playerworld0");
        creator.generator(new FlatChunkGenerator());
        nextWorld = creator.createWorld();


    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        String playerName = event.getPlayer().getDisplayName();
        System.out.println("First world " + event.getPlayer().getWorld().getName());

        Location teleportLocation = nextWorld.getSpawnLocation();
        boolean worked = event.getPlayer().teleport(teleportLocation);
        System.out.format("Teleportation worked %b", worked);
        System.out.println("Second world " + event.getPlayer().getWorld().getName());
        Bukkit.broadcastMessage("Welcome to the server, " + playerName);

        // Add world to active worlds
        activeWorlds.put(nextWorld.getName(), nextWorld);

        // Create new preloaded world for the next player
        String worldName = "playerworld" + activeWorlds.size();
        creator = new WorldCreator(worldName);
        creator.generator(new FlatChunkGenerator());
        nextWorld = creator.createWorld();
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event){
        event.getQuitMessage();
    }

    @EventHandler
    public void onBlockPlaced(BlockPlaceEvent event){

    }

    @EventHandler
    public void onBlockDestroyed(BlockDamageEvent event){

    }

    @EventHandler
    public void onWorldInitEvent​(WorldInitEvent event){
        Bukkit.broadcastMessage("World was initialized");
        // disable thunder
        event.getWorld().setThundering(false);
    }

    @EventHandler
    public void onWeatherChange(WeatherChangeEvent event){
        if (event.toWeatherState()) {  // would change to raining, TODO: does thunder count as raining
            event.setCancelled(true);
        }

        //event.getWorld().setWeatherDuration(0);
        //
    }

    @EventHandler
    public void onStructureGrow(StructureGrowEvent event){
        event.setCancelled(true);
    }
}

