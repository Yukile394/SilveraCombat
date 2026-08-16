package com.silvera.combat;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public class SilveraCombat extends JavaPlugin implements Listener {

    private final Map<UUID, Long> combatMap = new HashMap<>();
    private final MiniMessage mm = MiniMessage.miniMessage();
    
    private int combatDuration;
    private String prefix;
    private String startMsg1;
    private String startMsg2;
    private String actionbarMsg;
    private String cmdBlockedMsg;
    private String combatLogMsg;
    private String combatEndMsg;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        loadConfigValues();
        getServer().getPluginManager().registerEvents(this, this);

        new BukkitRunnable() {
            @Override
            public void run() {
                long now = System.currentTimeMillis();
                combatMap.entrySet().removeIf(entry -> {
                    Player p = Bukkit.getPlayer(entry.getKey());
                    if (entry.getValue() <= now) {
                        if (p != null && p.isOnline()) {
                            p.sendActionBar(Component.empty());
                            if (!combatEndMsg.isEmpty()) {
                                p.sendMessage(mm.deserialize(prefix + combatEndMsg));
                            }
                        }
                        return true; 
                    } else {
                        if (p != null && p.isOnline()) {
                            long remaining = (entry.getValue() - now) / 1000 + 1;
                            p.sendActionBar(mm.deserialize(actionbarMsg.replace("%time%", String.valueOf(remaining))));
                        }
                        return false;
                    }
                });
            }
        }.runTaskTimer(this, 0L, 20L);
    }

    private void loadConfigValues() {
        FileConfiguration c = getConfig();
        combatDuration = c.getInt("settings.duration", 10) * 1000;
        prefix = c.getString("messages.prefix", "<#FF8AD8>Silvera <#A0A0A0>» ");
        startMsg1 = c.getString("messages.start_message_1", "<#FFFFFF>Bu oyuncuyla savasa girdin!");
        startMsg2 = c.getString("messages.start_message_2", "<#FFFFFF>Savas sirasinda <#FFB3E6>oyundan cikma!");
        actionbarMsg = c.getString("messages.actionbar", prefix + "<#FFFFFF>Savas suresi: <#FFB3E6>%time% saniye");
        cmdBlockedMsg = c.getString("messages.cmd_blocked", "<#FFFFFF>Savas sirasinda komut kullanamazsin!");
        combatLogMsg = c.getString("messages.combat_log", "<#E85BB5>%player% <#FFFFFF>savas sirasinda oyundan ciktigi icin olduruldu!");
        combatEndMsg = c.getString("messages.combat_end", "");
    }

    @EventHandler
    public void onDamage(EntityDamageByEntityEvent e) {
        if (!(e.getEntity() instanceof Player victim)) return;

        Player attacker = null;
        if (e.getDamager() instanceof Player) {
            attacker = (Player) e.getDamager();
        } else if (e.getDamager() instanceof Projectile proj && proj.getShooter() instanceof Player) {
            attacker = (Player) proj.getShooter(); 
        }

        if (attacker != null && victim != attacker) {
            long now = System.currentTimeMillis();
            long endTime = now + combatDuration;

            setCombat(victim, endTime);
            setCombat(attacker, endTime);
        }
    }

    private void setCombat(Player p, long endTime) {
        UUID uuid = p.getUniqueId();
        if (!combatMap.containsKey(uuid)) {
            p.sendMessage(mm.deserialize(prefix + startMsg1));
            p.sendMessage(mm.deserialize(prefix + startMsg2));
        }
        combatMap.put(uuid, endTime);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        Player p = e.getPlayer();
        if (combatMap.containsKey(p.getUniqueId())) {
            p.setHealth(0.0); 
            combatMap.remove(p.getUniqueId());
            if (!combatLogMsg.isEmpty()) {
                Bukkit.broadcast(mm.deserialize(prefix + combatLogMsg.replace("%player%", p.getName())));
            }
        }
    }

    @EventHandler
    public void onDeath(PlayerDeathEvent e) {
        Player p = e.getEntity();
        combatMap.remove(p.getUniqueId());
        p.sendActionBar(Component.empty());

        Player killer = p.getKiller();
        if (killer != null) {
            combatMap.remove(killer.getUniqueId());
            killer.sendActionBar(Component.empty());
        }
    }

    @EventHandler
    public void onCommand(PlayerCommandPreprocessEvent e) {
        if (combatMap.containsKey(e.getPlayer().getUniqueId())) {
            e.setCancelled(true);
            e.getPlayer().sendMessage(mm.deserialize(prefix + cmdBlockedMsg));
        }
    }
}