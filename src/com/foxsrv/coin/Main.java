package com.foxsrv.coin;

import me.clip.placeholderapi.PlaceholderAPI;
import net.milkbowl.vault.economy.Economy;
import org.bukkit.Bukkit;
import org.bukkit.command.PluginCommand;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.command.ConsoleCommandSender;

import java.util.Arrays;

public class Main extends JavaPlugin {
    private static Economy econ;
    private ConfigManager configManager;
    private UserDataManager userDataManager;
    private HttpApiClient apiClient;

    @Override
    public void onEnable() {
        // Carrega config.yml padrão
        saveDefaultConfig();
        configManager   = new ConfigManager(this);
        userDataManager = new UserDataManager(this);
        apiClient       = new HttpApiClient(configManager.getApiUrl());

        // Inicializa Vault/Economy
        if (!setupEconomy()) {
            getLogger().severe("Vault not found! Disabling plugin.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        // Registra executor e tab‑completer do comando /coin
        CoinCommandExecutor exec = new CoinCommandExecutor(this);
        PluginCommand cmd = getCommand("coin");
        cmd.setExecutor(exec);
        cmd.setTabCompleter(exec);

        // Se PlaceholderAPI estiver presente, registra nossa expansão
        if (getServer().getPluginManager().isPluginEnabled("PlaceholderAPI")) {
            new CoinPlaceholder(this).register();
            getLogger().info("CoinPlaceholder registered!");
        }

        // Listener para suprimir log de "/coin login" no console
        final Main plugin = this;
        Bukkit.getPluginManager().registerEvents(new Listener() {
            @EventHandler
            public void onPlayerCommand(PlayerCommandPreprocessEvent e) {
                String msg = e.getMessage().toLowerCase();
                if (msg.startsWith("/coin login ")) {
                    // cancela o evento original (evita o log no console)
                    e.setCancelled(true);

                    // reconstrói args para passar ao executor
                    String[] parts = e.getMessage().substring(1).split(" ");
                    String[] args  = Arrays.copyOfRange(parts, 1, parts.length);

                    // chama diretamente o executor do comando, sem expor no console
                    plugin.getCommand("coin").execute(e.getPlayer(), "coin", args);
                }
            }
        }, this);
    }

    private boolean setupEconomy() {
        RegisteredServiceProvider<Economy> rsp =
            getServer().getServicesManager().getRegistration(Economy.class);
        if (rsp == null) return false;
        econ = rsp.getProvider();
        return econ != null;
    }

    /** Fornece a instância de Economy do Vault */
    public static Economy getEconomy() {
        return econ;
    }

    /** Acesso ao gerenciador de configurações do plugin */
    public ConfigManager getConfigManager() {
        return configManager;
    }

    /** Acesso ao gerenciador de dados de usuários */
    public UserDataManager getUserDataManager() {
        return userDataManager;
    }

    /** Acesso ao cliente HTTP da API de Coins */
    public HttpApiClient getApiClient() {
        return apiClient;
    }
}
