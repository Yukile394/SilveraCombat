package com.silvera.combat;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
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
    private String statusInCombatMsg;
    private String statusNotInCombatMsg;
    private List<String> whitelistedCommands;

    private static final String BYPASS_PERMISSION = "silvera.combat.bypass";

    // --- Item Cleaner ayarlari ---
    private static final long CLEANER_INTERVAL_SECONDS = 7 * 60; // 7 dakika
    private static final long CLEANER_WARNING_SECONDS = 30; // silinmeden 30 sn once uyari
    private long cleanerSecondsLeft = CLEANER_INTERVAL_SECONDS;
    private String cleanerPrefix;
    private String cleanerWarningMsg;
    private String cleanerDoneMsg;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        loadConfigValues();
        getServer().getPluginManager().registerEvents(this, this);

        if (getCommand("kombat") != null) {
            getCommand("kombat").setExecutor(this::onKombatCommand);
        }

        new BukkitRunnable() {
            @Override
            public void run() {
                long now = System.currentTimeMillis();
                combatMap.entrySet().removeIf(entry -> {
                    Player p = Bukkit.getPlayer(entry.getKey());
                    if (entry.getValue() <= now) {
                        if (p != null && p.isOnline()) {
                            p.sendActionBar(Component.empty());
                            if (combatEndMsg != null && !combatEndMsg.isEmpty()) {
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

        new BukkitRunnable() {
            @Override
            public void run() {
                cleanerSecondsLeft--;

                if (cleanerSecondsLeft == CLEANER_WARNING_SECONDS) {
                    Bukkit.broadcast(mm.deserialize(cleanerPrefix
                            + cleanerWarningMsg.replace("%time%", String.valueOf(CLEANER_WARNING_SECONDS))));
                }

                if (cleanerSecondsLeft <= 0) {
                    clearGroundItems();
                    Bukkit.broadcast(mm.deserialize(cleanerPrefix + cleanerDoneMsg));
                    cleanerSecondsLeft = CLEANER_INTERVAL_SECONDS;
                }
            }
        }.runTaskTimer(this, 20L, 20L);
    }

    private void clearGroundItems() {
        for (World world : Bukkit.getWorlds()) {
            for (Item item : world.getEntitiesByClass(Item.class)) {
                item.remove();
            }
        }
    }

    private void loadConfigValues() {
        FileConfiguration c = getConfig();
        combatDuration = c.getInt("settings.duration", 10) * 1000;
        prefix = c.getString("messages.prefix", "<#FF8AD8>Silvera <#A0A0A0>» ");
        startMsg1 = c.getString("messages.start_message_1", "<#FFFFFF>Bu oyuncuyla savaşa girdin!");
        startMsg2 = c.getString("messages.start_message_2", "<#FFFFFF>Savaş sırasında <#FFB3E6>oyundan çıkma!");
        actionbarMsg = c.getString("messages.actionbar", prefix + "<#FFFFFF>Savaş süresi: <#FFB3E6>%time% saniye");
        cmdBlockedMsg = c.getString("messages.cmd_blocked", "<#FFFFFF>Savaş sırasında komut kullanamazsın!");
        combatLogMsg = c.getString("messages.combat_log", "<#E85BB5>%player% <#FFFFFF>savaş sırasında oyundan çıktığı için öldürüldü!");
        combatEndMsg = c.getString("messages.combat_end", "<#FFFFFF>Silvera Savaş Sona Erdi!");
        statusInCombatMsg = c.getString("messages.status_in_combat", "<#FFFFFF>Savaştasın! Kalan süre: <#FFB3E6>%time% saniye");
        statusNotInCombatMsg = c.getString("messages.status_not_in_combat", "<#FFFFFF>Şu an savaşta değilsin.");
        whitelistedCommands = c.getStringList("settings.whitelisted_commands");

        cleanerPrefix = c.getString("messages.cleaner_prefix", "<#FF8AD8>Silvera <#A0A0A0>» ");
        cleanerWarningMsg = c.getString("messages.cleaner_warning", "<#FFFFFF>%time% saniye sonra yerdeki eşyalar silinecek!");
        cleanerDoneMsg = c.getString("messages.cleaner_done", "<#FFFFFF>Yerdeki Tüm Eşyalar Silindi!");
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDamage(EntityDamageByEntityEvent e) {
        if (!(e.getEntity() instanceof Player victim)) return;

        // WorldGuard veya baska bir plugin hasari iptal ettiyse (region flag pvp-deny vb.)
        // ya da gercek hasar 0 ise (blocking, absorption vs.) combat'a sokma.
        if (e.isCancelled() || e.getFinalDamage() <= 0.0) return;

        Player attacker = null;
        if (e.getDamager() instanceof Player) {
            attacker = (Player) e.getDamager();
        } else if (e.getDamager() instanceof Projectile proj && proj.getShooter() instanceof Player) {
            attacker = (Player) proj.getShooter();
        }

        if (attacker != null && victim != attacker) {
            long now = System.currentTimeMillis();
            long endTime = now + combatDuration;

            if (!victim.hasPermission(BYPASS_PERMISSION)) {
                setCombat(victim, endTime);
            }
            if (!attacker.hasPermission(BYPASS_PERMISSION)) {
                setCombat(attacker, endTime);
            }
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
        Player p = e.getPlayer();
        if (!combatMap.containsKey(p.getUniqueId())) return;
        if (p.hasPermission(BYPASS_PERMISSION)) return;

        String rawLabel = e.getMessage().substring(1).split(" ")[0].toLowerCase(Locale.ROOT);
        if (whitelistedCommands != null && whitelistedCommands.contains(rawLabel)) {
            return; 
        }

        e.setCancelled(true);
        p.sendMessage(mm.deserialize(prefix + cmdBlockedMsg));
    }

    private boolean onKombatCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (!(sender instanceof Player p)) {
            sender.sendMessage("Bu komut sadece oyuncular icindir.");
            return true;
        }

        Long endTime = combatMap.get(p.getUniqueId());
        if (endTime == null || endTime <= System.currentTimeMillis()) {
            p.sendMessage(mm.deserialize(prefix + statusNotInCombatMsg));
        } else {
            long remaining = (endTime - System.currentTimeMillis()) / 1000 + 1;
            p.sendMessage(mm.deserialize(prefix + statusInCombatMsg.replace("%time%", String.valueOf(remaining))));
        }
        return true;
    }

    /**
     * Diger pluginlerin (LoginX, duel sistemi vb.) bu oyuncunun
     * su an savasta olup olmadigini kontrol edebilmesi icin.
     */
    public boolean isInCombat(UUID uuid) {
        Long endTime = combatMap.get(uuid);
        return endTime != null && endTime > System.currentTimeMillis();
    }
}
