package com.foxsrv.coin;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class CoinPlaceholder extends PlaceholderExpansion {
    private final Main plugin;
    private final Map<UUID, String> balanceCache = new ConcurrentHashMap<>();

    public CoinPlaceholder(Main plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean persist() {
        // Mantém a expansão mesmo após /papi reload
        return true;
    }

    @Override
    public boolean canRegister() {
        return plugin.getServer()
                     .getPluginManager()
                     .isPluginEnabled("PlaceholderAPI");
    }

    @Override
    public String getIdentifier() {
        return "coin";
    }

    @Override
    public String getAuthor() {
        return String.join(",", plugin.getDescription().getAuthors());
    }

    @Override
    public String getVersion() {
        return plugin.getDescription().getVersion();
    }

    @Override
    public boolean register() {
        boolean registered = super.register();
        startBalanceUpdater();
        return registered;
    }

    private void startBalanceUpdater() {
        // Atualiza a cada 2 segundos (40 ticks)
        plugin.getServer().getScheduler().runTaskTimerAsynchronously(plugin, () -> {
            for (Player player : plugin.getServer().getOnlinePlayers()) {
                FileConfiguration uc = plugin.getUserDataManager().getUserConfig(player.getName());
                if (uc == null) continue;
                String sessionToken = uc.getString("session", "");
                String userId       = uc.getString("id", "");
                if (sessionToken.isEmpty() || userId.isEmpty()) continue;

                try {
                    // Chama GET /api/user/{id}/balance na API :contentReference[oaicite:3]{index=3}
                    String resp = plugin.getApiClient().get(
                        "/api/user/" + userId + "/balance",
                        sessionToken
                    );
                    JsonObject json = JsonParser.parseString(resp).getAsJsonObject();
                    double bal = json.has("coins") ? json.get("coins").getAsDouble() : 0.0;
                    String formatted = String.format("%.8f", bal);

                    // Armazena no cache e salva no config local
                    balanceCache.put(player.getUniqueId(), formatted);
                    uc.set("balance", bal);
                    plugin.getUserDataManager().saveUserConfig(player.getName(), uc);

                } catch (Exception e) {
                    plugin.getLogger().warning(
                        "CoinPlaceholder update error for " + player.getName() + ": " + e.getMessage()
                    );
                }
            }
        }, 0L, 40L);
    }

    @Override
    public String onPlaceholderRequest(Player player, String identifier) {
        if (player == null) return "";

        switch (identifier.toLowerCase()) {
            case "balance":
            case "balance_formatted":
                // Primeiro tenta o cache, senão cai no config salvo
                String cached = balanceCache.get(player.getUniqueId());
                if (cached != null) return cached;

                FileConfiguration uc = plugin.getUserDataManager().getUserConfig(player.getName());
                double bal = uc != null ? uc.getDouble("balance", 0.0) : 0.0;
                return String.format("%.8f", bal);

            case "username":
                FileConfiguration uc2 = plugin.getUserDataManager().getUserConfig(player.getName());
                return uc2 != null
                    ? uc2.getString("username", player.getName())
                    : player.getName();

            default:
                return null;
        }
    }
}
