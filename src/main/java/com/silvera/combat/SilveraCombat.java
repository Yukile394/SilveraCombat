package com.silvera.combat;

import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
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
import org.bukkit.event.player.PlayerGameModeChangeEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

public class SilveraCombat extends JavaPlugin implements Listener {

    private final Map<UUID, Long> combatMap = new HashMap<>();
    private final Map<UUID, BossBar> bars = new HashMap<>();
    private final Map<UUID, Long> endingBars = new HashMap<>();
    private final MiniMessage mm = MiniMessage.miniMessage();

    private static final long END_BAR_MILLIS = 2500L;

    private long combatDuration;
    private String prefix;
    private String startMsg1;
    private String startMsg2;
    private String bossbarMsg;
    private String bossbarEndMsg;
    private String cmdBlockedMsg;
    private String combatLogMsg;
    private String statusInCombatMsg;
    private String statusNotInCombatMsg;
    private List<String> whitelistedCommands;

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

        // Boss bar guncelleyici (2 tick = akici bar)
        new BukkitRunnable() {
            @Override
            public void run() {
                tickBars();
            }
        }.runTaskTimer(this, 0L, 2L);

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

    @Override
    public void onDisable() {
        for (Map.Entry<UUID, BossBar> entry : bars.entrySet()) {
            Player p = Bukkit.getPlayer(entry.getKey());
            if (p != null) p.hideBossBar(entry.getValue());
        }
        bars.clear();
        endingBars.clear();
        combatMap.clear();
    }

    private void tickBars() {
        long now = System.currentTimeMillis();

        Iterator<Map.Entry<UUID, Long>> it = combatMap.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<UUID, Long> entry = it.next();
            UUID uuid = entry.getKey();
            Player p = Bukkit.getPlayer(uuid);

            if (p == null || !p.isOnline()) {
                it.remove();
                discardBar(uuid, null);
                continue;
            }

            long remainingMs = entry.getValue() - now;
            BossBar bar = getOrCreateBar(p);

            if (remainingMs <= 0) {
                it.remove();
                bar.name(mm.deserialize(bossbarEndMsg));
                bar.progress(0f);
                endingBars.put(uuid, now + END_BAR_MILLIS);
            } else {
                long seconds = (remainingMs + 999) / 1000;
                bar.name(mm.deserialize(bossbarMsg.replace("%time%", String.valueOf(seconds))));
                bar.progress(Math.max(0f, Math.min(1f, (float) remainingMs / (float) combatDuration)));
            }
        }

        Iterator<Map.Entry<UUID, Long>> endIt = endingBars.entrySet().iterator();
        while (endIt.hasNext()) {
            Map.Entry<UUID, Long> entry = endIt.next();
            if (entry.getValue() <= now) {
                endIt.remove();
                discardBar(entry.getKey(), Bukkit.getPlayer(entry.getKey()));
            }
        }
    }

    private BossBar getOrCreateBar(Player p) {
        BossBar bar = bars.get(p.getUniqueId());
        if (bar == null) {
            bar = BossBar.bossBar(mm.deserialize(bossbarMsg.replace("%time%", "0")),
                    1f, BossBar.Color.BLUE, BossBar.Overlay.PROGRESS);
            bars.put(p.getUniqueId(), bar);
            p.showBossBar(bar);
        }
        return bar;
    }

    private void discardBar(UUID uuid, Player p) {
        BossBar bar = bars.remove(uuid);
        endingBars.remove(uuid);
        if (bar != null) {
            Player target = p != null ? p : Bukkit.getPlayer(uuid);
            if (target != null) target.hideBossBar(bar);
        }
    }

    private void clearCombat(Player p) {
        combatMap.remove(p.getUniqueId());
        discardBar(p.getUniqueId(), p);
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
        combatDuration = Math.max(1, c.getInt("settings.duration", 10)) * 1000L;
        prefix = c.getString("messages.prefix", "<#FF8AD8>Silvera <#A0A0A0>» ");
        startMsg1 = c.getString("messages.start_message_1", "<#FFFFFF>Bu oyuncuyla savaşa girdin!");
        startMsg2 = c.getString("messages.start_message_2", "<#FFFFFF>Savaş sırasında <#FFB3E6>oyundan çıkma!");
        bossbarMsg = c.getString("messages.bossbar", "<blue><bold>Savaş (%time%)");
        bossbarEndMsg = c.getString("messages.bossbar_end", "<blue><bold>Savaştan çıktın");
        cmdBlockedMsg = c.getString("messages.cmd_blocked", "<#FFFFFF>Savaş sırasında komut kullanamazsın!");
        combatLogMsg = c.getString("messages.combat_log", "<#E85BB5>%player% <#FFFFFF>savaş sırasında oyundan çıktığı için öldürüldü!");
        statusInCombatMsg = c.getString("messages.status_in_combat", "<#FFFFFF>Savaştasın! Kalan süre: <#FFB3E6>%time% saniye");
        statusNotInCombatMsg = c.getString("messages.status_not_in_combat", "<#FFFFFF>Şu an savaşta değilsin.");
        whitelistedCommands = c.getStringList("settings.whitelisted_commands");

        cleanerPrefix = c.getString("messages.cleaner_prefix", "<#FF8AD8>Silvera <#A0A0A0>» ");
        cleanerWarningMsg = c.getString("messages.cleaner_warning", "<#FFFFFF>%time% saniye sonra yerdeki eşyalar silinecek!");
        cleanerDoneMsg = c.getString("messages.cleaner_done", "<#FFFFFF>Yerdeki Tüm Eşyalar Silindi!");
    }

    private boolean canFight(Player p) {
        GameMode gm = p.getGameMode();
        return gm != GameMode.CREATIVE && gm != GameMode.SPECTATOR;
    }

    // WorldGuard (/rg flag pvp deny) hasari iptal ederse ignoreCancelled sayesinde
    // bu event hic calismaz, yani o bolgelerde combat baslamaz.
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDamage(EntityDamageByEntityEvent e) {
        if (!(e.getEntity() instanceof Player victim)) return;
        if (e.getFinalDamage() <= 0.0) return;

        Player attacker = null;
        if (e.getDamager() instanceof Player) {
            attacker = (Player) e.getDamager();
        } else if (e.getDamager() instanceof Projectile proj && proj.getShooter() instanceof Player) {
            attacker = (Player) proj.getShooter();
        }

        if (attacker == null || victim == attacker) return;

        // Taraflardan biri creative/spectator ise combat hic baslamaz.
        if (!canFight(victim) || !canFight(attacker)) return;

        long endTime = System.currentTimeMillis() + combatDuration;
        setCombat(victim, endTime);
        setCombat(attacker, endTime);
    }

    private void setCombat(Player p, long endTime) {
        UUID uuid = p.getUniqueId();
        if (!combatMap.containsKey(uuid)) {
            p.sendMessage(mm.deserialize(prefix + startMsg1));
            p.sendMessage(mm.deserialize(prefix + startMsg2));
        }
        endingBars.remove(uuid);
        combatMap.put(uuid, endTime);
        getOrCreateBar(p);
    }

    @EventHandler
    public void onGameModeChange(PlayerGameModeChangeEvent e) {
        GameMode gm = e.getNewGameMode();
        if (gm == GameMode.CREATIVE || gm == GameMode.SPECTATOR) {
            clearCombat(e.getPlayer());
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        Player p = e.getPlayer();
        boolean wasInCombat = combatMap.containsKey(p.getUniqueId());
        clearCombat(p);

        if (wasInCombat && canFight(p)) {
            p.setHealth(0.0);
            if (!combatLogMsg.isEmpty()) {
                Bukkit.broadcast(mm.deserialize(prefix + combatLogMsg.replace("%player%", p.getName())));
            }
        }
    }

    @EventHandler
    public void onDeath(PlayerDeathEvent e) {
        Player p = e.getEntity();
        clearCombat(p);

        Player killer = p.getKiller();
        if (killer != null) {
            clearCombat(killer);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onCommand(PlayerCommandPreprocessEvent e) {
        Player p = e.getPlayer();
        if (!combatMap.containsKey(p.getUniqueId())) return;

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
