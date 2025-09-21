package com.foxsrv.coin;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.stream.JsonReader;

import me.clip.placeholderapi.PlaceholderAPI;

import net.milkbowl.vault.economy.Economy;
import net.milkbowl.vault.economy.EconomyResponse;

import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitRunnable;
import net.milkbowl.vault.economy.Economy;
import net.milkbowl.vault.economy.EconomyResponse;
import org.bukkit.command.ConsoleCommandSender;

import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Collections;
import java.util.function.Function;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.Map;
import java.util.HashMap;
import java.util.ArrayList;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.io.StringReader;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.io.File;
import java.util.List;
import java.util.ArrayList;
import java.util.Map;
import java.util.AbstractMap;

public class CoinCommandExecutor implements CommandExecutor, TabCompleter {
    private final Main plugin;
    private final ConfigManager cfg;
    private final UserDataManager users;
    private final HttpApiClient api;

    private static final List<String> SUBCOMMANDS = Arrays.asList(
        "help", "register", "login", "logout", "bal", "baltop",
        "buy", "sell", "pay", "bill", "paybill", "bills", "history",
        "claim", "backup", "restore", "user", "server", "reload"
    );

    // cooldown de 1,1s por jogador para os comandos buy e sell
    private static final Map<UUID, Long> buyCooldowns  = new HashMap<>();
    private static final Map<UUID, Long> sellCooldowns = new HashMap<>();

    public CoinCommandExecutor(Main plugin) {
        this.plugin = plugin;
        this.cfg    = plugin.getConfigManager();
        this.users  = plugin.getUserDataManager();
        this.api    = plugin.getApiClient();
    }

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (!cmd.getName().equalsIgnoreCase("coin")) return false;

        if (args.length == 0 || args[0].equalsIgnoreCase("help")) {
            send(sender,
                "§bCoin System API for Minecraft",
                "------------------------------",
                "Use /coin buy/sell <amount>",
                "------------------------------",
                "Commands:",
                "/coin register <user> <pass> <pass>",
                "/coin login <user> <pass>",
                "/coin logout",
                "/coin bal",
                "/coin baltop",
                "/coin buy <amt>",
                "/coin sell <amt>",
                "/coin pay <player> <amt>",
                "/coin bill <player> <amt>",
                "/coin paybill <id>",
                "/coin bills",
                "/coin history",
                "/coin claim",
                "/coin backup",
                "/coin restore <id>",
                "/coin user",
                "/coin server pay nick <amt>",
                "/coin server payid <id> <amt>",
                "/coin reload (admin)"
            );
            return true;
        }

        switch (args[0].toLowerCase()) {
            case "reload":   return doReload(sender);
            case "register": return doRegister(sender, args);
            case "login":    return doLogin(sender, args);
            case "logout":   return doLogout(sender);
            case "bal":      return doBalance(sender);
            case "baltop":   return doBaltop(sender);
            case "buy":      return doBuy(sender, args);
            case "sell":     return doSell(sender, args);
            case "pay":      return doPay(sender, args);
            case "bill":     return doBill(sender, args);
            case "paybill":  return doPayBill(sender, args);
            case "bills":    return doListBills(sender);
            case "history":  return doHistory(sender);
            case "claim":    return doClaim(sender);
            case "backup":   return doBackup(sender);
            case "restore":  return doRestore(sender, args);
            case "user":     return doUser(sender);
            case "server": // ⬅️ Aqui você trata os subcomandos: pay e payid
        if (args.length >= 2 && args[1].equalsIgnoreCase("pay")) {
            return doServerPay(sender, args);
        } else if (args.length >= 2 && args[1].equalsIgnoreCase("payid")) {
            return doServerPayId(sender, args);
        } else {
            send(sender, "Usage:");
            send(sender, "/coin server pay <player> <amount>");
            send(sender, "/coin server payid <coinId> <amount>");
            return true;
        }
            default:
                send(sender, "Unknown subcommand. See /coin help");
                return true;
        }
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command cmd,
                                      String alias, String[] args) {
        if (!cmd.getName().equalsIgnoreCase("coin")) return Collections.emptyList();

        if (args.length == 1) {
            String partial = args[0].toLowerCase();
            return SUBCOMMANDS.stream()
                .filter(s -> s.startsWith(partial))
                .collect(Collectors.toList());
        }

        if (args.length == 2) {
            String sub = args[0].toLowerCase();
            if (sub.equals("pay") || sub.equals("bill")) {
                return Bukkit.getOnlinePlayers().stream()
                    .map(Player::getName)
                    .filter(n -> n.toLowerCase().startsWith(args[1].toLowerCase()))
                    .collect(Collectors.toList());
            }
        }

        return Collections.emptyList();
    }


    private boolean doReload(CommandSender s) {
        if (!s.hasPermission("coin.admin")) {
            send(s,"§cNo permission.");
            return true;
        }
        plugin.reloadConfig();
        cfg.reload();
        send(s,"§aConfiguration reloaded.");
        return true;
    }

    private boolean doRegister(CommandSender s, String[] a) {
        if (!(s instanceof Player)) { send(s,"Only players."); return true; }
        if (a.length!=4) { send(s,"Usage: /coin register <user> <pass> <pass>"); return true; }
        if (!a[2].equals(a[3])) { send(s,"Passwords do not match."); return true; }
        Player p = (Player)s;
        new BukkitRunnable(){
            @Override public void run(){
                try {
                    String json = String.format(
                        "{\"username\":\"%s\",\"password\":\"%s\"}",
                        a[1],a[2]
                    );
                    String resp = api.post("/api/register", json, null);
                    if (!parseBool(resp,"\"success\"")) {
                        sendSync(s,"Registration failed.");
                        return;
                    }
                    String userId  = parseStr(resp,"\"userId\"");
                    String session = parseStr(resp,"\"sessionId\"");
                    FileConfiguration uc = users.loadUserConfig(p.getName());
                    uc.set("username",     a[1]);
                    uc.set("id",           userId);
                    uc.set("session",      session);
                    uc.set("passwordHash", sha256(a[2]));
                    uc.set("card",         null);
                    uc.set("balance",      0);
                    users.saveUserConfig(p.getName(),uc);
                    sendSync(s,"Registered & logged in.");
                } catch(Exception e){
                    plugin.getLogger().warning("Register: "+e);
                    sendSync(s,"Error during registration.");
                }
            }
        }.runTaskAsynchronously(plugin);
        return true;
    }

private boolean doLogin(CommandSender s, String[] a) {
    if (!(s instanceof Player)) {
        send(s, "Only players.");
        return true;
    }
    if (a.length != 3) {
        send(s, "Usage: /coin login <user> <pass>");
        return true;
    }
    Player p = (Player) s;
    new BukkitRunnable() {
        @Override
        public void run() {
            try {
                String json = String.format(
                    "{\"username\":\"%s\",\"password\":\"%s\"}",
                    a[1], a[2]
                );
                String resp = api.post("/api/login", json, null);
                if (!parseBool(resp, "\"sessionCreated\"")) {
                    sendSync(s, "Login failed.");
                    return;
                }
                String session = parseStr(resp, "\"sessionId\"");
                String userId  = parseStr(resp, "\"userId\"");

                String card = null;
                try {
                    String c = api.post("/api/card", "{}", session);
                    card = parseStr(c, "\"cardCode\"");
                } catch (Exception ignore) {}

                FileConfiguration uc = users.getUserConfig(p.getName());
                if (uc == null) {
                    uc = YamlConfiguration.loadConfiguration(
                        users.getUserConfigFile(p.getName())
                    );
                }
                uc.set("username",     a[1]);
                if (userId != null) uc.set("id", userId);
                uc.set("session",      session);
                uc.set("passwordHash", sha256(a[2]));
                if (card != null) uc.set("card", card);

                // busca e salva saldo imediatamente
                String balResp = api.get(
                    "/api/user/" + uc.getString("id") + "/balance",
                    session
                );
                double coins = parseNum(balResp, "\"coins\"");
                uc.set("balance", coins);
                users.saveUserConfig(p.getName(), uc);

                // envia mensagem já com placeholder resolvido
                String message = String.format("Logged in. Your balance: %%coin_balance%%");
                sendSync(s, PlaceholderAPI.setPlaceholders(p, message));
            } catch (Exception e) {
                plugin.getLogger().warning("Login: " + e);
                sendSync(s, "Error during login.");
            }
        }
    }.runTaskAsynchronously(plugin);
    return true;
}


    private boolean doLogout(CommandSender s) {
        if (!(s instanceof Player)) { send(s,"Only players."); return true; }
        Player p=(Player)s;
        if (!users.isLoggedIn(p.getName())) { send(s,"You are not logged in."); return true; }
        new BukkitRunnable(){
            @Override public void run(){
                try {
                    FileConfiguration uc = users.getUserConfig(p.getName());
                    api.post("/api/logout","{}",uc.getString("session"));
                } catch(Exception ignore){}
                users.deleteUser(p.getName());
                sendSync(s,"Logged out.");
            }
        }.runTaskAsynchronously(plugin);
        return true;
    }

    private boolean doBalance(CommandSender s) {
        if (!checkLogin(s)) return true;
        Player p=(Player)s;
        new BukkitRunnable(){
            @Override public void run(){
                try {
                    FileConfiguration uc = users.getUserConfig(p.getName());
                    String resp = api.get("/api/user/"+uc.getString("id")+"/balance",
                                          uc.getString("session"));
                    double coins = parseNum(resp,"\"coins\"");
                    uc.set("balance",coins);
                    users.saveUserConfig(p.getName(),uc);
                    sendSync(s,"Your balance: "+fmt(coins));
                } catch(Exception e){
                    plugin.getLogger().warning("Bal: "+e);
                    sendSync(s,"Error fetching balance.");
                }
            }
        }.runTaskAsynchronously(plugin);
        return true;
    }

private boolean doBaltop(CommandSender sender) {
    // 1) Valida login e tipo de sender
    if (!checkLogin(sender)) return true;
    if (!(sender instanceof Player)) {
        send(sender, "Only players can use this command.");
        return true;
    }

    // 2) Executa de forma assíncrona
    new BukkitRunnable() {
        @Override
        public void run() {
            try {
                // 3) Pasta "users" dentro da pasta do plugin
                File usersDir = new File(plugin.getDataFolder(), "users");
                if (!usersDir.exists() || !usersDir.isDirectory()) {
                    sendSync(sender, "§cNo user data directory found.");
                    return;
                }

                // 4) Carrega cada arquivo .yml e coleta username + balance
                List<Map.Entry<String, Double>> list = new ArrayList<>();
                for (File f : usersDir.listFiles((dir, name) -> name.endsWith(".yml"))) {
                    FileConfiguration cfg = YamlConfiguration.loadConfiguration(f);
                    String username = cfg.getString("username", f.getName().replace(".yml", ""));
                    double balance  = cfg.getDouble("balance", 0.0);
                    list.add(new AbstractMap.SimpleEntry<>(username, balance));
                }

                // 5) Ordena por balance desc e pega top 10
                list.sort((a, b) -> Double.compare(b.getValue(), a.getValue()));
                int max = Math.min(10, list.size());

                // 6) Monta a mensagem
                StringBuilder sb = new StringBuilder("§bTop ").append(max).append(" Richest Players:\n\n");
                if (max == 0) {
                    sb.append("§eNo player data available.");
                } else {
                    for (int i = 0; i < max; i++) {
                        Map.Entry<String, Double> entry = list.get(i);
                        sb.append("§e").append(i + 1).append(". §f")
                          .append(entry.getKey())
                          .append(" — ").append(fmt(entry.getValue()))
                          .append("\n");
                    }
                }

                // 7) Envia no chat principal
                sendSync(sender, sb.toString());

            } catch (Exception e) {
                plugin.getLogger().warning("doBaltop error: " + e.getMessage());
                sendSync(sender, "§cError fetching baltop.");
            }
        }
    }.runTaskAsynchronously(plugin);

    return true;
}


private boolean doBuy(CommandSender sender, String[] args) {
    // 0) Cooldown de 1,1 segundo
    if (sender instanceof Player) {
        Player p = (Player) sender;
        UUID uuid = p.getUniqueId();
        long now = System.currentTimeMillis();
        Long last = buyCooldowns.get(uuid);
        if (last != null && (now - last) < 1100L) {
            send(sender, "Wait 1s before using the command again!");
            return true;
        }
        buyCooldowns.put(uuid, now);
    }

    // 1) Validações iniciais
    if (!checkLogin(sender)) return true;
    if (!(sender instanceof Player)) {
        send(sender, "Only players can use this command.");
        return true;
    }
    if (args.length != 2) {
        send(sender, "Usage: /coin buy <amount>");
        return true;
    }

    Player player = (Player) sender;
    FileConfiguration uc = users.getUserConfig(player.getName());
    final String sessionToken = uc.getString("session");
    final String userId       = uc.getString("id");

    // 2) Parse e valida requested amount (coins to spend)
    double want;
    try {
        want = Double.parseDouble(args[1]);
        if (want <= 0) {
            send(sender, "Invalid amount. It must be greater than zero.");
            return true;
        }
    } catch (NumberFormatException ex) {
        send(sender, "Invalid amount. Usage: /coin buy <amount>");
        return true;
    }

    // 3) Executa todo o fluxo de compra de forma assíncrona
    new BukkitRunnable() {
        @Override
        public void run() {
            try {
                // 3.1) Atualiza saldo de coins pela API
                String balanceResp = api.get("/api/user/" + userId + "/balance", sessionToken);
                double have = parseNum(balanceResp, "\"coins\"");
                if (have < want) {
                    sendSync(sender, "Low Coin balance, you need: " + fmt(want - have) + " coins.");
                    return;
                }

                // 3.2) Calcula quanto deve em Vault
                double vaultAmt = want * cfg.getBuyRate();
                Economy econ = Main.getEconomy();
                OfflinePlayer owner = Bukkit.getOfflinePlayer(cfg.getOwner());

                // 3.3) Verifica saldo do owner no Vault antes de qualquer transferência
                if (econ.getBalance(owner) < vaultAmt) {
                    sendSync(sender, "Purchase failed: owner has insufficient Vault funds.");
                    return;
                }

                // 3.4) Garante que o usuário tenha um cardCode
                String card = uc.getString("card", "");
                if (card.isEmpty()) {
                    String cardResp = api.post("/api/card", "{}", sessionToken);
                    card = parseStr(cardResp, "\"cardCode\"");
                    uc.set("card", card);
                    users.saveUserConfig(player.getName(), uc);
                }

                // 3.5) Executa a transferência de coins via API (user → owner)
                String transferPayload = String.format(
                    "{\"cardCode\":\"%s\",\"toId\":\"%s\",\"amount\":%s}",
                    card,
                    cfg.getOwnerId(),
                    Double.toString(want)
                );
                String txResp = api.post("/api/transfer/card", transferPayload, sessionToken);
                JsonObject txJson = parseLenient(txResp);
                if (!txJson.has("success") || !txJson.get("success").getAsBoolean()) {
                    String err = txJson.has("error") ? txJson.get("error").getAsString() : null;
                    sendSync(sender,
                        err != null
                            ? "Purchase failed: " + err
                            : "Purchase failed."
                    );
                    return;
                }
                String txId = txJson.has("txId") ? txJson.get("txId").getAsString() : "–";

                // 3.6) Realiza a transação no Vault (owner → player)
                Bukkit.getScheduler().runTask(plugin, () -> {
                    EconomyResponse withdraw = econ.withdrawPlayer(owner, vaultAmt);
                    if (!withdraw.transactionSuccess()) {
                        sendSync(sender, "Purchase failed: error withdrawing Vault funds.");
                        return;
                    }
                    EconomyResponse deposit = econ.depositPlayer(player, vaultAmt);
                    if (!deposit.transactionSuccess()) {
                        sendSync(sender, "Purchase failed: error depositing Vault funds.");
                        return;
                    }
                    // 3.7) Notifica o jogador do sucesso (após Vault atualizado)
                    sendSync(sender,
                        "§aYou successfully bought " + fmt(vaultAmt) + "!",
                        "§eTransaction ID: §f" + txId
                    );
                });

                // 3.8) Atualiza saldo local de coins
                String newBalResp = api.get("/api/user/" + userId + "/balance", sessionToken);
                double newBalance = parseNum(newBalResp, "\"coins\"");
                uc.set("balance", newBalance);
                users.saveUserConfig(player.getName(), uc);

            } catch (Exception e) {
                plugin.getLogger().warning("doBuy error: " + e.getMessage());
                sendSync(sender, "Error during purchase. Please try again later.");
            }
        }
    }.runTaskAsynchronously(plugin);

    return true;
}





private boolean doSell(CommandSender sender, String[] args) {
    // 0) Cooldown de 1,1 segundo
    if (sender instanceof Player) {
        Player playerCd = (Player) sender;
        UUID uuid = playerCd.getUniqueId();
        long now = System.currentTimeMillis();
        Long last = sellCooldowns.get(uuid);
        if (last != null && (now - last) < 1100L) {
            send(sender, "Wait 1s before using the command again!");
            return true;
        }
        sellCooldowns.put(uuid, now);
    }

    // 1) Validações iniciais
    if (!checkLogin(sender)) return true;
    if (!(sender instanceof Player)) {
        send(sender, "Only players can use this command.");
        return true;
    }
    if (args.length != 2) {
        send(sender, "Usage: /coin sell <amount>");
        return true;
    }

    Player player = (Player) sender;
    FileConfiguration uc = users.getUserConfig(player.getName());
    final String sessionToken = uc.getString("session");
    final String userId       = uc.getString("id");

    // 2) Parse e valida amount do Vault
    double amtVault;
    try {
        amtVault = Double.parseDouble(args[1]);
        if (amtVault <= 0) {
            send(sender, "Invalid amount. It must be greater than zero.");
            return true;
        }
    } catch (NumberFormatException ex) {
        send(sender, "Invalid amount. Usage: /coin sell <amount>");
        return true;
    }

    // 3) Executa todo o fluxo de forma assíncrona
    new BukkitRunnable() {
        @Override
        public void run() {
            try {
                Economy econ = Main.getEconomy();
                OfflinePlayer owner = Bukkit.getOfflinePlayer(cfg.getOwner());

                // 3.1) Verifica saldo no Vault
                double vaultBal = econ.getBalance(player);
                if (vaultBal < amtVault) {
                    sendSync(sender, "Low vault balance, you need " + fmt(amtVault - vaultBal) + ".");
                    return;
                }

                // 3.2) Garante que o usuário tenha um cardCode
                String card = uc.getString("card", "");
                if (card.isEmpty()) {
                    String cardResp = api.post("/api/card", "{}", sessionToken);
                    JsonObject cardJson = JsonParser.parseString(cardResp).getAsJsonObject();
                    card = cardJson.has("cardCode") ? cardJson.get("cardCode").getAsString() : "";
                    uc.set("card", card);
                    users.saveUserConfig(player.getName(), uc);
                }

                // 3.3) Calcula quantos coins dar ao player
                double coins = amtVault * cfg.getSellRate();

                // 3.4) Executa transferência via API (owner → user)
                String payload = String.format(
                    "{\"cardCode\":\"%s\",\"toId\":\"%s\",\"amount\":%s}",
                    cfg.getOwnerCard(),
                    userId,
                    Double.toString(coins)
                );
                String txResp = api.post("/api/transfer/card", payload, sessionToken);
                JsonObject txJson = JsonParser.parseString(txResp).getAsJsonObject();
                if (!txJson.has("success") || !txJson.get("success").getAsBoolean()) {
                    String err = txJson.has("error") ? txJson.get("error").getAsString() : null;
                    sendSync(sender,
                        err != null
                            ? "Sell failed: " + err
                            : "Sell failed."
                    );
                    return;  // **não desconta do Vault**
                }

                // 3.5) Se API teve sucesso, realiza a transação no Vault (player → owner)
                Bukkit.getScheduler().runTask(plugin, () -> {
                    EconomyResponse withdraw = econ.withdrawPlayer(player, amtVault);
                    if (!withdraw.transactionSuccess()) {
                        sendSync(sender, "Sell failed: error withdrawing Vault funds.");
                        return;
                    }
                    EconomyResponse deposit = econ.depositPlayer(owner, amtVault);
                    if (!deposit.transactionSuccess()) {
                        sendSync(sender, "Sell failed: error depositing Vault funds.");
                        return;
                    }
                    // 3.6) Notifica o player do sucesso
                    sendSync(sender,
                        "§aYou sold " + fmt(amtVault) + " vault for " + fmt(coins) + " coins.",
                        "§eTransaction ID: §f" + txJson.get("txId").getAsString()
                    );
                });

                // 3.7) Atualiza saldo local de coins
                String balResp = api.get("/api/user/" + userId + "/balance", sessionToken);
                double newBal = parseNum(balResp, "\"coins\"");
                uc.set("balance", newBal);
                users.saveUserConfig(player.getName(), uc);

            } catch (Exception e) {
                plugin.getLogger().warning("doSell error: " + e.getMessage());
                sendSync(sender, "Error during sell. Please try again later.");
            }
        }
    }.runTaskAsynchronously(plugin);

    return true;
}


private boolean doPay(CommandSender sender, String[] args) {
    // 1) Checa login e permissões
    if (!checkLogin(sender)) return true;
    if (!(sender instanceof Player)) {
        send(sender, "Only players can use this command.");
        return true;
    }

    // 2) Valida argumentos
    if (args.length != 3) {
        send(sender, "Usage: /coin pay <player> <amount>");
        return true;
    }
    Player player = (Player) sender;
    String targetName = args[1];

    // 3) Valida valor
    double amount;
    try {
        amount = Double.parseDouble(args[2]);
        if (amount <= 0) {
            send(sender, "Invalid amount. It must be greater than zero.");
            return true;
        }
    } catch (NumberFormatException e) {
        send(sender, "Invalid amount. Usage: /coin pay <player> <amount>");
        return true;
    }

    // 4) Checa se o alvo existe e está logado
    if (!users.isLoggedIn(targetName)) {
        send(sender, "This user does not have an account.");
        return true;
    }

    // 5) Carrega configs locais
    FileConfiguration userCfg   = users.getUserConfig(player.getName());
    FileConfiguration targetCfg = users.getUserConfig(targetName);
    final String userId         = userCfg.getString("id");
    final String sessionToken   = userCfg.getString("session");
    final String targetId       = targetCfg.getString("id");

    // 6) Executa chamada assíncrona à API
    new BukkitRunnable() {
        @Override
        public void run() {
            try {
                // 6.1) Pega saldo atual
                String balanceResp = api.get("/api/user/" + userId + "/balance", sessionToken);
                double balance = parseNum(balanceResp, "\"coins\"");
                if (balance < amount) {
                    double need = amount - balance;
                    sendSync(sender, "Low balance: you need " + fmt(need) + " more coins.");
                    return;
                }

                // 6.2) Envia transferência
                String transferJson = String.format(
                    "{\"toId\":\"%s\",\"amount\":%s}",
                    targetId, Double.toString(amount)
                );
                String transferResp = api.post("/api/transfer", transferJson, sessionToken);
                if (!parseBool(transferResp, "\"success\"")) {
                    // tenta extrair mensagem de erro, se houver
                    String err = parseStr(transferResp, "\"error\"");
                    if (err != null) {
                        sendSync(sender, "Transfer failed: " + err);
                    } else {
                        sendSync(sender, "Transfer failed.");
                    }
                    return;
                }

                // 6.3) Notifica o remetente
                sendSync(sender, "You sent " + fmt(amount) + " coins to " + targetName + ".");

                // 6.4) Notifica o recebedor (se online)
                Player targetPlayer = Bukkit.getPlayerExact(targetName);
                if (targetPlayer != null && targetPlayer.isOnline()) {
                    sendSync(targetPlayer,
                        "You received " + fmt(amount) +
                        " coins from " + player.getName() + ".");
                }

                // 6.5) Atualiza saldo local
                String newBalResp = api.get("/api/user/" + userId + "/balance", sessionToken);
                double newBalance = parseNum(newBalResp, "\"coins\"");
                userCfg.set("balance", newBalance);
                users.saveUserConfig(player.getName(), userCfg);

            } catch (Exception e) {
                plugin.getLogger().warning("doPay error: " + e.getMessage());
                sendSync(sender,
                    "An error occurred during the transfer.",
                    "Please try again later.");
            }
        }
    }.runTaskAsynchronously(plugin);

    return true;
}



private boolean doBill(CommandSender sender, String[] args) {
    // 1) Checa login e contexto
    if (!checkLogin(sender)) return true;
    if (!(sender instanceof Player)) {
        send(sender, "Only players can use this command.");
        return true;
    }
    if (args.length != 3) {
        send(sender, "Usage: /coin bill <player> <amount>");
        return true;
    }

    Player player = (Player) sender;
    String targetName = args[1];

    // 2) Verifica conta do destino
    if (!users.isLoggedIn(targetName)) {
        send(sender, "This user does not have an account.");
        return true;
    }

    // 3) Carrega IDs e sessão
    FileConfiguration userCfg   = users.getUserConfig(player.getName());
    FileConfiguration targetCfg = users.getUserConfig(targetName);
    final String userId         = userCfg.getString("id");
    final String sessionToken   = userCfg.getString("session");
    final String targetId       = targetCfg.getString("id");

    // 4) Valida e parseia valor
    double amount;
    try {
        amount = Double.parseDouble(args[2]);
        if (amount <= 0) {
            send(sender, "Invalid amount. It must be greater than zero.");
            return true;
        }
    } catch (NumberFormatException e) {
        send(sender, "Invalid amount. Usage: /coin bill <player> <amount>");
        return true;
    }

    // 5) Executa criação de fatura de forma assíncrona
    new BukkitRunnable() {
        @Override
        public void run() {
            try {
                // 5.1) Monta JSON incluindo 'time' como timestamp atual
                long nowMs = System.currentTimeMillis();
                String billJson = String.format(
                    "{\"fromId\":\"%s\",\"toId\":\"%s\",\"amount\":%s,\"time\":%d}",
                    userId,
                    targetId,
                    Double.toString(amount),
                    nowMs
                );

                // 5.2) Chama o endpoint POST /api/bill/create
                String resp = api.post("/api/bill/create", billJson, sessionToken);

                // 5.3) Verifica sucesso
                if (!parseBool(resp, "\"success\"")) {
                    String errMsg = parseStr(resp, "\"error\"");
                    if (errMsg != null) {
                        sendSync(sender, "Failed to create bill: " + errMsg);
                    } else {
                        sendSync(sender, "Failed to create bill.");
                    }
                    return;
                }

                // 5.4) Extrai e notifica o criador
                String billId = parseStr(resp, "\"billId\"");
                sendSync(sender,
                    billId != null
                        ? "Bill created successfully. ID: " + billId
                        : "Bill created, but no ID returned."
                );

                // 5.5) Notifica o jogador faturado (se online)
                Player targetPlayer = Bukkit.getPlayerExact(targetName);
                if (targetPlayer != null && targetPlayer.isOnline()) {
                    sendSync(targetPlayer,
                        "You have a new bill (ID: " + billId + 
                        ") of " + fmt(amount) + " coins from " + 
                        player.getName() + ". Use /coin paybill " + billId + " to pay."
                    );
                }

            } catch (Exception e) {
                plugin.getLogger().warning("doBill error: " + e.getMessage());
                sendSync(sender,
                    "An error occurred while creating the bill.",
                    "Please try again later."
                );
            }
        }
    }.runTaskAsynchronously(plugin);

    return true;
}



    private boolean doPayBill(CommandSender s, String[] a) {
        if (!checkLogin(s)) return true;
        if (a.length!=2) { send(s,"Usage: /coin paybill <id>"); return true; }
        FileConfiguration uc = users.getUserConfig(((Player)s).getName());
        new BukkitRunnable(){
            @Override public void run(){
                try {
                    String tresp = api.post("/api/bill/pay",
                      String.format("{\"billId\":\"%s\"}",a[1]),
                      uc.getString("session")
                    );
                    if (!parseBool(tresp,"\"success\"")) {
                        sendSync(s,"Failed to pay bill.");
                        return;
                    }
                    sendSync(s,"Paid bill "+a[1]+".");
                } catch(Exception e){
                    plugin.getLogger().warning("PayBill: "+e);
                    sendSync(s,"Error paying bill.");
                }
            }
        }.runTaskAsynchronously(plugin);
        return true;
    }

private boolean doListBills(CommandSender sender) {
    // 1) Valida login e tipo de sender
    if (!checkLogin(sender)) return true;
    if (!(sender instanceof Player)) {
        send(sender, "Only players can use this command.");
        return true;
    }

    Player player = (Player) sender;
    FileConfiguration uc = users.getUserConfig(player.getName());
    final String sessionToken = uc.getString("session");

    // 2) Chama API de forma assíncrona
    new BukkitRunnable() {
        @Override
        public void run() {
            try {
                // 3) Lista faturas pendentes
                String resp = api.post("/api/bill/list", "{\"page\":1}", sessionToken);
                JsonObject root     = parseLenient(resp);
                JsonArray toPay     = root.has("toPay")     ? root.getAsJsonArray("toPay")     : new JsonArray();
                JsonArray toReceive = root.has("toReceive") ? root.getAsJsonArray("toReceive") : new JsonArray();

                // 4) Junta todos os registros em um só array
                JsonArray all = new JsonArray();
                toPay.forEach(all::add);
                toReceive.forEach(all::add);

                // 5) Formatter para data
                DateTimeFormatter df = DateTimeFormatter.ofPattern("dd/MM/yyyy");

                // 6) Monta a mensagem no estilo /coin history
                if (all.isEmpty()) {
                    sendSync(sender, "§eYou have no pending bills.");
                } else {
                    StringBuilder sb = new StringBuilder("§6Your Pending Bills:\n\n");
                    int count = Math.min(20, all.size());
                    for (int i = 0; i < count; i++) {
                        JsonObject bill = all.get(i).getAsJsonObject();

                        // Extrai o ID da fatura
                        String billId = bill.has("billId")   ? bill.get("billId").getAsString()
                                      : bill.has("id")       ? bill.get("id").getAsString()
                                      : "–";

                        // Extrai valor
                        double amount = bill.has("amount") ? bill.get("amount").getAsDouble() : 0;

                        // Extrai timestamp e formata para dd/MM/yyyy
                        String rawDate = bill.has("date") ? bill.get("date").getAsString() : null;
                        String date;
                        try {
                            long epochMs = Long.parseLong(rawDate);
                            LocalDateTime dt = LocalDateTime.ofInstant(Instant.ofEpochMilli(epochMs), ZoneId.systemDefault());
                            date = dt.format(df);
                        } catch (Exception ex) {
                            date = rawDate != null ? rawDate : "–";
                        }

                        sb.append("§eBill #").append(i + 1).append(":\n")
                          .append(" §fID: ").append(billId).append("\n")
                          .append(" §fAmount: ").append(fmt(amount)).append("\n")
                          .append(" §fDate: ").append(date).append("\n")
                          .append("§7-------------------------\n");
                    }
                    sendSync(sender, sb.toString());
                }

            } catch (Exception e) {
                plugin.getLogger().warning("doListBills error: " + e.getMessage());
                sendSync(sender, "§cError listing bills. Please try again later.");
            }
        }
    }.runTaskAsynchronously(plugin);

    return true;
}



/**
 * Helper para mapear um userId ao nome do jogador, lendo todos os configs.
 * Se sua classe users já fornecer esse método, pode usá‑lo diretamente.
 */
private String findUsernameById(String id) {
    Set<String> names = users.getAllUserNames(); // supondo método que retorna todos os nomes de usuário salvos
    for (String name : names) {
        FileConfiguration cfg = users.getUserConfig(name);
        if (id.equals(cfg.getString("id"))) {
            return name;
        }
    }
    return null;
}

private boolean doHistory(CommandSender sender) {
    // 1) Validações iniciais
    if (!checkLogin(sender)) return true;
    if (!(sender instanceof Player)) {
        send(sender, "Only players can use this command.");
        return true;
    }

    Player player = (Player) sender;
    FileConfiguration uc = users.getUserConfig(player.getName());
    final String sessionToken = uc.getString("session");

    // 2) Executa a chamada à API de forma assíncrona
    new BukkitRunnable() {
        @Override
        public void run() {
            try {
                // 3) GET /api/transactions?page=1
                String resp = api.get("/api/transactions?page=1", sessionToken);

                // 4) Parse do JSON
                JsonObject root = JsonParser.parseString(resp).getAsJsonObject();
                JsonArray txs = root.has("transactions")
                    ? root.getAsJsonArray("transactions")
                    : new JsonArray();

                // 5) Função auxiliar para extrair IDs
                Function<JsonElement, String> extractId = e -> {
                    if (e.isJsonPrimitive()) {
                        return e.getAsString();
                    } else if (e.isJsonObject()) {
                        JsonObject o = e.getAsJsonObject();
                        if (o.has("userId")) return o.get("userId").getAsString();
                        if (o.has("id"))     return o.get("id").getAsString();
                    }
                    return "–";
                };

                // 6) Monta a mensagem
                StringBuilder sb = new StringBuilder("§6Transaction History:\n\n");
                if (txs.isEmpty()) {
                    sb.append("§cNo transactions found.\n");
                } else {
                    int count = Math.min(20, txs.size());
                    for (int i = 0; i < count; i++) {
                        JsonObject tx = txs.get(i).getAsJsonObject();

                        // txId ou id
                        String txId = tx.has("txId")   ? tx.get("txId").getAsString()
                                     : tx.has("id")    ? tx.get("id").getAsString()
                                     : "–";

                        // from (camelCase, snake_case ou primitivo/objeto)
                        String fromId = tx.has("fromId")   ? extractId.apply(tx.get("fromId"))
                                      : tx.has("from_id")  ? extractId.apply(tx.get("from_id"))
                                      : tx.has("from")     ? extractId.apply(tx.get("from"))
                                      : "–";

                        // to
                        String toId = tx.has("toId")     ? extractId.apply(tx.get("toId"))
                                    : tx.has("to_id")    ? extractId.apply(tx.get("to_id"))
                                    : tx.has("to")       ? extractId.apply(tx.get("to"))
                                    : "–";

                        double amt  = tx.has("amount") ? tx.get("amount").getAsDouble() : 0;
                        String date = tx.has("date")   ? tx.get("date").getAsString()   : "–";

                        sb.append("§eID: ").append(txId).append("\n")
                          .append("§eFrom: ").append(fromId).append("\n")
                          .append("§eTo: ").append(toId).append("\n")
                          .append("§eAmount: ").append(fmt(amt)).append("\n")
                          .append("§eDate: ").append(date).append("\n")
                          .append("§7-------------------------\n");
                    }
                }

                // 7) Envia no thread principal
                sendSync(sender, sb.toString());

            } catch (Exception e) {
                plugin.getLogger().warning("doHistory error: " + e.getMessage());
                sendSync(sender, "§cError fetching transaction history.");
            }
        }
    }.runTaskAsynchronously(plugin);

    return true;
}


private boolean doClaim(CommandSender sender) {
    // 1) Valida login e tipo de sender
    if (!checkLogin(sender)) return true;
    if (!(sender instanceof Player)) {
        send(sender, "Only players can use this command.");
        return true;
    }

    // 2) Obtém sessão
    FileConfiguration uc = users.getUserConfig(((Player) sender).getName());
    final String sessionToken = uc.getString("session");

    // 3) Executa claim de forma assíncrona
    new BukkitRunnable() {
        @Override
        public void run() {
            try {
                // 3.1) Tenta fazer o claim
                String respRaw = api.post("/api/claim", "{}", sessionToken);
                JsonObject respJson = parseLenient(respRaw);

                boolean success = respJson.has("success") && respJson.get("success").getAsBoolean();
                double claimed   = respJson.has("claimed") ? respJson.get("claimed").getAsDouble() : 0;

                if (success && claimed > 0) {
                    // Claim bem‑sucedido
                    String unit = (claimed == 1.0) ? " Coin!" : " Coins!";
                    sendSync(sender, "§aYou successfully claimed " + fmt(claimed) + unit);
                } else {
                    // Em cooldown ou sem campo, busca status para saber quanto tempo falta
                    String statusRaw = api.get("/api/claim/status", sessionToken);
                    JsonObject statusJson = parseLenient(statusRaw);
                    long ms = statusJson.has("cooldownRemainingMs")
                              ? statusJson.get("cooldownRemainingMs").getAsLong()
                              : 0;

                    long totalSec = ms / 1000;
                    long hours   = totalSec / 3600;
                    long minutes = (totalSec % 3600) / 60;
                    long seconds = totalSec % 60;

                    String timeStr = hours + "h " + minutes + "m " + seconds + "s";
                    sendSync(sender, "§eWait more " + timeStr + " and try again!");
                }

            } catch (Exception e) {
                plugin.getLogger().warning("doClaim error: " + e.getMessage());
                sendSync(sender, "§cError during claim. Please try again later.");
            }
        }
    }.runTaskAsynchronously(plugin);

    return true;
}


private boolean doBackup(CommandSender sender) {
    // 1) Valida login e tipo de sender
    if (!checkLogin(sender)) return true;
    if (!(sender instanceof Player)) {
        send(sender, "Only players can use this command.");
        return true;
    }

    Player player = (Player) sender;
    FileConfiguration uc = users.getUserConfig(player.getName());
    final String sessionToken = uc.getString("session");

    new BukkitRunnable() {
        @Override
        public void run() {
            try {
                // 2) Cria um novo backup
                String createResp = api.post("/api/backup/create", "{}", sessionToken);
                JsonObject createJson = JsonParser.parseString(createResp).getAsJsonObject();
                boolean created = createJson.has("success") && createJson.get("success").getAsBoolean();
                String createdId = created && createJson.has("id")
                    ? createJson.get("id").getAsString()
                    : null;

                // 3) Busca lista de backups (pode vir como array puro ou objeto com "backups")
                String listResp = api.post("/api/backup/list", "{}", sessionToken);
                JsonElement parsed = JsonParser.parseString(listResp);
                JsonArray backups;
                if (parsed.isJsonObject() && parsed.getAsJsonObject().has("backups")) {
                    backups = parsed.getAsJsonObject().getAsJsonArray("backups");
                } else if (parsed.isJsonArray()) {
                    backups = parsed.getAsJsonArray();
                } else {
                    backups = new JsonArray();
                }

                // 4) Informa criação
                if (created) {
                    sendSync(sender, "§aBackup created!");
                } else {
                    sendSync(sender, "§cCould not create backup.");
                }

                // 5) Monta e envia lista no estilo /coin history
                if (backups.isEmpty()) {
                    sendSync(sender, "§eYou have no backups.");
                } else {
                    StringBuilder sb = new StringBuilder("§6Your backups:\n\n");
                    int count = Math.min(backups.size(), 20);
                    for (int i = 0; i < count; i++) {
                        JsonElement el = backups.get(i);
                        String id;
                        if (el.isJsonPrimitive()) {
                            id = el.getAsString();
                        } else {
                            JsonObject o = el.getAsJsonObject();
                            id = o.has("id") ? o.get("id").getAsString()
                               : o.has("backupId") ? o.get("backupId").getAsString()
                               : "–";
                        }
                        sb.append("§eID: ").append(id).append("\n")
                          .append("§7-------------------------\n");
                    }
                    sendSync(sender, sb.toString());
                }

            } catch (Exception e) {
                plugin.getLogger().warning("doBackup error: " + e.getMessage());
                sendSync(sender, "§cError creating or listing backups. Please try again later.");
            }
        }
    }.runTaskAsynchronously(plugin);

    return true;
}

/**
 * Parseia um JSON usando JsonReader leniente para aceitar pequenas
 * irregularidades no texto retornado pela API.
 */
private JsonObject parseLenient(String json) {
    JsonReader reader = new JsonReader(new StringReader(json));
    reader.setLenient(true);
    return JsonParser.parseReader(reader).getAsJsonObject();
}



private boolean doRestore(CommandSender sender, String[] args) {
    // 1) Validações iniciais
    if (!checkLogin(sender)) return true;
    if (!(sender instanceof Player)) {
        send(sender, "Only players can use this command.");
        return true;
    }
    if (args.length != 2) {
        send(sender, "Usage: /coin restore <backupId>");
        return true;
    }

    Player player = (Player) sender;
    FileConfiguration uc = users.getUserConfig(player.getName());
    final String sessionToken = uc.getString("session");
    final String backupId = args[1];

    // 2) Executa a restauração de forma assíncrona
    new BukkitRunnable() {
        @Override
        public void run() {
            try {
                String payload = String.format("{\"backupId\":\"%s\"}", backupId);
                String resp = api.post("/api/backup/restore", payload, sessionToken);

                if (parseBool(resp, "\"success\"")) {
                    // Sucesso!
                    sendSync(sender, "Restored backup " + backupId + " successfully.");
                } else {
                    // Falha – tenta extrair mensagem de erro
                    String err = parseStr(resp, "\"error\"");
                    if (err != null) {
                        sendSync(sender, "Restore failed: " + err);
                    } else {
                        sendSync(sender, "Restore failed.");
                    }
                }
            } catch (Exception e) {
                plugin.getLogger().warning("doRestore error: " + e.getMessage());
                sendSync(sender,
                    "Error during backup restore.",
                    "Please try again later."
                );
            }
        }
    }.runTaskAsynchronously(plugin);

    return true;
}


private boolean doUser(CommandSender sender) {
    // 1) Valida login e tipo de sender
    if (!checkLogin(sender)) return true;
    if (!(sender instanceof Player)) {
        send(sender, "Only players can use this command.");
        return true;
    }

    Player player = (Player) sender;
    FileConfiguration uc = users.getUserConfig(player.getName());
    final String sessionToken = uc.getString("session");
    final String userId       = uc.getString("id");
    final String username     = uc.getString("username", player.getName());

    new BukkitRunnable() {
        @Override
        public void run() {
            try {
                // 2.1) Balance
                String balResp = api.get("/api/user/" + userId + "/balance", sessionToken);
                JsonObject balJson = parseLenient(balResp);
                double balance = balJson.has("coins")
                                 ? balJson.get("coins").getAsDouble()
                                 : 0;

                // 2.2) CardCode
                String cardResp = api.post("/api/card", "{}", sessionToken);
                JsonObject cardJson = parseLenient(cardResp);
                String card = cardJson.has("cardCode")
                              ? cardJson.get("cardCode").getAsString()
                              : "–";

                // 2.3) Cooldown
                String statusResp = api.get("/api/claim/status", sessionToken);
                JsonObject statusJson = parseLenient(statusResp);
                long ms = statusJson.has("cooldownRemainingMs")
                          ? statusJson.get("cooldownRemainingMs").getAsLong()
                          : 0;
                String cooldownMsg;
                if (ms <= 0) {
                    cooldownMsg = "Ready to claim!";
                } else {
                    long sec = ms / 1000;
                    long h = sec / 3600;
                    long m = (sec % 3600) / 60;
                    long s = sec % 60;
                    cooldownMsg = h + "h " + m + "m " + s + "s to claim.";
                }

                // 2.4) Atualiza config local (opcional)
                uc.set("balance", balance);
                uc.set("card", card);
                users.saveUserConfig(player.getName(), uc);

                // 2.5) Envia “card” no chat
                sendSync(sender,
                    "§7-----------------------------------------",
                    "§eYour ID: §f"       + userId,
                    "§eUsername: §f"      + username,
                    "§eCard: §f"          + card,
                    "§eBalance: §f"       + fmt(balance),
                    "§eCooldown: §f"      + cooldownMsg,
                    "§7-----------------------------------------"
                );

            } catch (Exception e) {
                plugin.getLogger().warning("doUser error: " + e.getMessage());
                sendSync(sender, "§cError fetching user info.");
            }
        }
    }.runTaskAsynchronously(plugin);

    return true;
}




// Comando: /coin server pay <player> <amount>
private boolean doServerPay(CommandSender sender, String[] args) {
    if (!(sender instanceof ConsoleCommandSender || sender.isOp())) {
        send(sender, "§cOnly OPs or console can use this command.");
        return true;
    }

    if (args.length != 4 || !args[1].equalsIgnoreCase("pay")) {
        send(sender, "Usage: /coin server pay <player> <amount>");
        return true;
    }

    String targetName = args[2];
    String amountRaw = args[3];
    double amount;

    try {
        amount = Double.parseDouble(amountRaw);
        if (amount <= 0) {
            send(sender, "Invalid amount. Must be greater than 0.");
            return true;
        }
    } catch (NumberFormatException e) {
        send(sender, "Invalid amount format.");
        return true;
    }

    // Verifica se o usuário está logado
    if (!users.isLoggedIn(targetName)) {
        send(sender, "This user needs to login first!");
        Player target = Bukkit.getPlayerExact(targetName);
        if (target != null && target.isOnline()) {
            sendSync(target, "§cYou need to login with /coin login <username> <password> to being able to use coins in the server.");
        }
        return true;
    }

    final String serverCard = cfg.getOwnerCard();
    final String targetId = users.getUserConfig(targetName).getString("id");

    if (serverCard == null || serverCard.isEmpty()) {
        send(sender, "§cServer card is not configured in config.yml!");
        return true;
    }

    new BukkitRunnable() {
        @Override
        public void run() {
            try {
                String payload = String.format(
                    "{\"cardCode\":\"%s\",\"toId\":\"%s\",\"amount\":%s}",
                    serverCard, targetId, Double.toString(amount)
                );

                String resp = api.post("/api/transfer/card", payload, null);
                JsonObject txJson = parseLenient(resp);

                if (!txJson.has("success") || !txJson.get("success").getAsBoolean()) {
                    sendSync(sender, "No enough of funds.");
                    return;
                }

                String txId = txJson.has("txId") ? txJson.get("txId").getAsString() : "–";
                sendSync(sender, "§aSuccess! Transaction: §f" + txId);

            } catch (Exception e) {
                plugin.getLogger().warning("doServerPay error: " + e.getMessage());
                sendSync(sender, "§cError while sending coins from server.");
            }
        }
    }.runTaskAsynchronously(plugin);

    return true;
}


// Comando: /coin server payid <coinId> <amount>
private boolean doServerPayId(CommandSender sender, String[] args) {
    if (!(sender instanceof ConsoleCommandSender || sender.isOp())) {
        send(sender, "§cOnly OPs or console can use this command.");
        return true;
    }

    if (args.length != 4 || !args[1].equalsIgnoreCase("payid")) {
        send(sender, "Usage: /coin server payid <coinId> <amount>");
        return true;
    }

    String targetId = args[2];
    String amountRaw = args[3];
    double amount;

    try {
        amount = Double.parseDouble(amountRaw);
        if (amount <= 0) {
            send(sender, "Invalid amount. Must be greater than 0.");
            return true;
        }
    } catch (NumberFormatException e) {
        send(sender, "Invalid amount format.");
        return true;
    }

    final String serverCard = cfg.getOwnerCard();
    if (serverCard == null || serverCard.isEmpty()) {
        send(sender, "§cServer card is not configured in config.yml!");
        return true;
    }

    new BukkitRunnable() {
        @Override
        public void run() {
            try {
                String payload = String.format(
                    "{\"cardCode\":\"%s\",\"toId\":\"%s\",\"amount\":%s}",
                    serverCard, targetId, Double.toString(amount)
                );

                String resp = api.post("/api/transfer/card", payload, null);
                JsonObject txJson = parseLenient(resp);

                if (!txJson.has("success") || !txJson.get("success").getAsBoolean()) {
                    sendSync(sender, "No enough of funds.");
                    return;
                }

                String txId = txJson.has("txId") ? txJson.get("txId").getAsString() : "–";
                sendSync(sender, "§aSuccess! Transaction: §f" + txId);

            } catch (Exception e) {
                plugin.getLogger().warning("doServerPayId error: " + e.getMessage());
                sendSync(sender, "§cError while sending coins from server.");
            }
        }
    }.runTaskAsynchronously(plugin);

    return true;
}




    private boolean checkLogin(CommandSender s) {
        if (!(s instanceof Player)) { send(s,"Only players."); return false; }
        if (!users.isLoggedIn(((Player)s).getName())) {
            send(s,"Please login first: /coin login <user> <pass>");
            return false;
        }
        return true;
    }

    private void send(CommandSender s, String... lines) {
        for(String l: lines) s.sendMessage(l);
    }

    private void sendSync(CommandSender s, String... msgs) {
        Bukkit.getScheduler().runTask(plugin, ()-> {
            for(String m: msgs) s.sendMessage(m);
        });
    }

    private boolean parseBool(String j, String key) {
        Matcher m = Pattern.compile(key+"\\s*:\\s*(true|false)").matcher(j);
        return m.find() && Boolean.parseBoolean(m.group(1));
    }

    private String parseStr(String j, String key) {
        Matcher m = Pattern.compile(key+"\\s*:\\s*\"([^\"]*)\"").matcher(j);
        return m.find()? m.group(1): null;
    }

    private double parseNum(String j, String key) {
        Matcher m = Pattern.compile(key+"\\s*:\\s*([0-9.]+)").matcher(j);
        return m.find()? Double.parseDouble(m.group(1)): 0;
    }

    private double parseDouble(String s) {
        try { return Double.parseDouble(s);} catch(Exception e){return 0;}
    }

    private String fmt(double v) {
        return String.format("%.8f",v);
    }

    private String sha256(String in) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] b = md.digest(in.getBytes("UTF-8"));
            StringBuilder sb = new StringBuilder();
            for(byte x: b) sb.append(String.format("%02x",x));
            return sb.toString();
        } catch(Exception e){
            return "";
        }
    }

    private String human(long ms) {
        long s = ms/1000; long h = s/3600; s%=3600;
        long m = s/60;       s%=60;
        return h+"h "+m+"m "+s+"s";
    }
}
