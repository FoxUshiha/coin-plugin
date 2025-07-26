package com.foxsrv.coin;

import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.util.Arrays;
import java.util.Collections;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

public class UserDataManager {
    private final JavaPlugin plugin;
    private final File usersDir;

    public UserDataManager(JavaPlugin plugin) {
        this.plugin = plugin;
        this.usersDir = new File(plugin.getDataFolder(), "users");
        if (!usersDir.exists()) {
            usersDir.mkdirs();
        }
    }

    private File getUserFile(String playerName) {
        return new File(usersDir, playerName + ".yml");
    }

    public boolean isLoggedIn(String playerName) {
        return getUserFile(playerName).exists();
    }

    /**
     * Lê o config do usuário, ou retorna null se não existir
     */
    public FileConfiguration getUserConfig(String playerName) {
        File f = getUserFile(playerName);
        if (!f.exists()) return null;
        return YamlConfiguration.loadConfiguration(f);
    }

    /**
     * Carrega um config vazio ou com dados se existir
     */
    public FileConfiguration loadUserConfig(String playerName) {
        File f = getUserFile(playerName);
        return YamlConfiguration.loadConfiguration(f);
    }

    public File getUserConfigFile(String playerName) {
        return getUserFile(playerName);
    }

    public void saveUserConfig(String playerName, FileConfiguration cfg) {
        try {
            cfg.save(getUserFile(playerName));
        } catch (IOException e) {
            plugin.getLogger().severe("Could not save user file for " + playerName);
        }
    }

    public void deleteUser(String playerName) {
        File f = getUserFile(playerName);
        if (f.exists()) f.delete();
    }

    /**
     * Retorna todos os nomes de usuários (sem extensão) que têm arquivo .yml em usersDir
     */
    public Set<String> getAllUserNames() {
        if (!usersDir.exists() || !usersDir.isDirectory()) {
            return Collections.emptySet();
        }
        return Arrays.stream(Objects.requireNonNull(usersDir.listFiles()))
                     .filter(f -> f.getName().endsWith(".yml"))
                     .map(f -> f.getName().replaceFirst("\\.yml$", ""))
                     .collect(Collectors.toSet());
    }
}
