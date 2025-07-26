package com.foxsrv.coin;

import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;

public class CoinPlaceholder extends PlaceholderExpansion {
    private final Main plugin;

    public CoinPlaceholder(Main plugin) {
        this.plugin = plugin;
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
    public String onPlaceholderRequest(Player player, String identifier) {
        if (player == null) return "";
        FileConfiguration uc = plugin
            .getUserDataManager()
            .getUserConfig(player.getName());
        if (uc == null) return "0.00000000";

        switch (identifier.toLowerCase()) {
            case "balance":
            case "balance_formatted":
                double bal = uc.getDouble("balance", 0.0);
                return String.format("%.8f", bal);
            case "username":
                return uc.getString("username", player.getName());
            default:
                return null;
        }
    }
}
