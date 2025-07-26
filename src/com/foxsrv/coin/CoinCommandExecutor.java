package com.foxsrv.coin;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

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

import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.Map;
import java.util.HashMap;
import java.util.ArrayList;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

public class CoinCommandExecutor implements CommandExecutor, TabCompleter {
    private final Main plugin;
    private final ConfigManager cfg;
    private final UserDataManager users;
    private final HttpApiClient api;

    private static final List<String> SUBCOMMANDS = Arrays.asList(
        "help", "register", "login", "logout", "bal", "baltop",
        "buy", "sell", "pay", "bill", "paybill", "bills", "history",
        "claim", "backup", "restore", "user", "reload"
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

private boolean doBaltop(CommandSender s) {
    if (!checkLogin(s)) return true;
    new BukkitRunnable() {
        @Override
        public void run() {
            try {
                FileConfiguration uc = users.getUserConfig(((Player) s).getName());
                String resp = api.get("/api/rank", uc.getString("session"));
                // Pattern para cada objeto {"userId":"...", "coins":...}
                Pattern pat = Pattern.compile("\\{\"userId\":\"(\\d+)\",\"coins\":([0-9.]+)\\}");
                Matcher m = pat.matcher(resp);

                StringBuilder sb = new StringBuilder("§bTop 25 Richest (by ID):\n");
                int i = 1;
                while (m.find() && i <= 25) {
                    sb.append(i).append(". ").append(m.group(1)).append("\n");
                    i++;
                }
                // Caso não haja dados:
                if (i == 1) {
                    sb.append("No data available.");
                }
                sendSync(s, sb.toString());
            } catch (Exception e) {
                plugin.getLogger().warning("Baltop: " + e);
                sendSync(s, "Error fetching toplist.");
            }
        }
    }.runTaskAsynchronously(plugin);
    return true;
}

    private boolean doBuy(CommandSender sender, String[] args) {
        // 0) Cooldown de 1,1 segundo
        if (sender instanceof Player) {
            Player playerCd = (Player) sender;
            UUID uuid = playerCd.getUniqueId();
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
                        double need = want - have;
                        sendSync(sender, "Low Coin balance, you need: " + fmt(need) + " coins.");
                        return;
                    }

                    // 3.2) Garante que o usuário tenha um cardCode
                    String card = uc.getString("card", "");
                    if (card.isEmpty()) {
                        String cardResp = api.post("/api/card", "{}", sessionToken);
                        card = parseStr(cardResp, "\"cardCode\"");
                        uc.set("card", card);
                        users.saveUserConfig(player.getName(), uc);
                    }

                    // 3.3) Executa a transferência de coins (para o owner)
                    String transferPayload = String.format(
                        "{\"cardCode\":\"%s\",\"toId\":\"%s\",\"amount\":%s}",
                        card,
                        cfg.getOwnerId(),
                        Double.toString(want)
                    );
                    String txResp = api.post("/api/transfer/card", transferPayload, null);
                    if (!parseBool(txResp, "\"success\"")) {
                        String err = parseStr(txResp, "\"error\"");
                        sendSync(sender,
                            err != null
                                ? "Purchase failed: " + err
                                : "Purchase failed."
                        );
                        return;
                    }
                    String txId = parseStr(txResp, "\"txId\"");

                    // 3.4) Calcula valor em Vault
                    double vaultAmt = want * cfg.getBuyRate();

                    // 3.5) Transação em Vault no thread principal
                    Bukkit.getScheduler().runTask(plugin, () -> {
                        Economy econ = Main.getEconomy();
                        OfflinePlayer owner = Bukkit.getOfflinePlayer(cfg.getOwner());

                        EconomyResponse withdraw = econ.withdrawPlayer(owner, vaultAmt);
                        if (!withdraw.transactionSuccess()) {
                            sendSync(sender, "Purchase failed: owner has insufficient Vault funds.");
                            return;
                        }

                        EconomyResponse deposit = econ.depositPlayer(player, vaultAmt);
                        if (!deposit.transactionSuccess()) {
                            sendSync(sender, "Purchase failed: could not deposit Vault funds.");
                            return;
                        }

                        // 3.6) Notifica o jogador do sucesso (após Vault atualizado)
                        sendSync(sender,
                            "You successfully bought " + fmt(vaultAmt) + ".",
                            "Here is your transaction ID: " + txId
                        );
                    });

                    // 3.7) Atualiza saldo local de coins
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
                    // 3.1) Verifica saldo no Vault
                    double vaultBal = Main.getEconomy().getBalance(player);
                    if (vaultBal < amtVault) {
                        double need = amtVault - vaultBal;
                        sendSync(sender, "Low vault balance, you need " + fmt(need) + ".");
                        return;
                    }

                    // 3.2) Sacar do jogador e depositar para o owner (no thread principal)
                    Bukkit.getScheduler().runTask(plugin, () -> {
                        Main.getEconomy().withdrawPlayer(player, amtVault);
                        OfflinePlayer owner = Bukkit.getOfflinePlayer(cfg.getOwner());
                        Main.getEconomy().depositPlayer(owner, amtVault);
                    });

                    // 3.3) Calcula quantos coins dar ao player
                    double rate  = cfg.getSellRate();
                    double coins = amtVault * rate;

                    // 3.4) Executa transferência via cartão (POST /api/transfer/card)
                    String payload = String.format(
                        "{\"cardCode\":\"%s\",\"toId\":\"%s\",\"amount\":%s}",
                        cfg.getOwnerCard(),
                        userId,
                        Double.toString(coins)
                    );
                    String cardResp = api.post("/api/transfer/card", payload, null);

                    if (!parseBool(cardResp, "\"success\"")) {
                        String err = parseStr(cardResp, "\"error\"");
                        sendSync(sender,
                            err != null
                                ? "Sell failed: " + err
                                : "Sell failed."
                        );
                        return;
                    }

                    // 3.5) Notifica o player e atualiza saldo local
                    sendSync(sender,
                        "You sold " + fmt(amtVault) + " vault for " + fmt(coins) + " coins."
                    );

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
    // 1) valida login e tipo
    if (!checkLogin(sender)) return true;
    if (!(sender instanceof Player)) {
        send(sender, "Only players can use this command.");
        return true;
    }
    Player player = (Player) sender;
    FileConfiguration uc = users.getUserConfig(player.getName());
    final String sessionToken = uc.getString("session");

    // 2) chama API de forma assíncrona
    new BukkitRunnable() {
        @Override
        public void run() {
            try {
                String resp = api.post("/api/bill/list", "{\"page\":1}", sessionToken);

                // 3) parseia JSON
                JsonObject root     = JsonParser.parseString(resp).getAsJsonObject();
                JsonArray toPay     = root.has("toPay")     ? root.getAsJsonArray("toPay")     : new JsonArray();
                JsonArray toReceive = root.has("toReceive") ? root.getAsJsonArray("toReceive") : new JsonArray();

                // 4) coleta todos os IDs de faturas
                List<String> ids = new ArrayList<>();
                for (JsonElement el : toPay)     ids.add(el.getAsJsonObject().get("billId").getAsString());
                for (JsonElement el : toReceive) ids.add(el.getAsJsonObject().get("billId").getAsString());

                // 5) formata e envia
                if (ids.isEmpty()) {
                    sendSync(sender, "ℹ️ You do not have any pending bills.");
                } else {
                    StringBuilder sb = new StringBuilder();
                    sb.append("📋 **Your bills (").append(ids.size()).append("):**\n");
                    for (int i = 0; i < ids.size(); i++) {
                        sb.append("**").append(i + 1).append(".** `").append(ids.get(i)).append("`\n");
                    }
                    sendSync(sender, sb.toString());
                }

            } catch (Exception e) {
                plugin.getLogger().warning("doListBills error: " + e.getMessage());
                sendSync(sender, "Error listing bills.");
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

    // 2) Chama a API de forma assíncrona
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

                // 5) Monta a mensagem com até 20 itens, formatados
                StringBuilder sb = new StringBuilder("Transaction History:\n\n");
                if (txs.isEmpty()) {
                    sb.append("No transactions found.\n");
                } else {
                    int count = Math.min(20, txs.size());
                    for (int i = 0; i < count; i++) {
                        JsonObject tx = txs.get(i).getAsJsonObject();

                        // Campos do objeto de transação
                        String txId   = tx.has("txId")   ? tx.get("txId").getAsString()   : "";
                        String from   = tx.has("from")   ? tx.get("from").getAsString()   : "";
                        String to     = tx.has("to")     ? tx.get("to").getAsString()     : "";
                        double amt    = tx.has("amount") ? tx.get("amount").getAsDouble() : 0;
                        String date   = tx.has("date")   ? tx.get("date").getAsString()   : "";

                        sb.append("ID: ").append(txId).append("\n")
                          .append("From: ").append(from).append("\n")
                          .append("To: ").append(to).append("\n")
                          .append("Amount: ").append(fmt(amt)).append("\n")
                          .append("Date: ").append(date).append("\n")
                          .append("-------------------------\n");
                    }
                }

                // 6) Envia no thread principal
                sendSync(sender, sb.toString());

            } catch (Exception e) {
                plugin.getLogger().warning("doHistory error: " + e.getMessage());
                sendSync(sender, "Error fetching transaction history.");
            }
        }
    }.runTaskAsynchronously(plugin);

    return true;
}

private boolean doClaim(CommandSender sender) {
    // 1) Valida login e tipo
    if (!checkLogin(sender)) return true;
    if (!(sender instanceof Player)) {
        send(sender, "Only players can use this command.");
        return true;
    }

    // 2) Obtém sessão
    FileConfiguration uc = users.getUserConfig(((Player)sender).getName());
    final String sessionToken = uc.getString("session");

    // 3) Executa claim de forma assíncrona
    new BukkitRunnable() {
        @Override
        public void run() {
            try {
                // 3.1) Tenta fazer o claim
                String resp = api.post("/api/claim", "{}", sessionToken);
                boolean success = parseBool(resp, "\"success\"");
                double got = parseNum(resp, "\"claimed\"");

                if (success && got > 0) {
                    // Claim bem‑sucedido
                    String unit = (got == 1.0) ? " coin!" : " coins!";
                    sendSync(sender, "You claimed " + fmt(got) + unit);
                } else {
                    // Não foi possível claimar (cooldown ou valor zero)
                    String status = api.get("/api/claim/status", sessionToken);
                    long ms = Long.parseLong(parseStr(status, "\"cooldownRemainingMs\""));
                    long totalSec = ms / 1000;
                    long hours   = totalSec / 3600;
                    long minutes = (totalSec % 3600) / 60;
                    long seconds = totalSec % 60;
                    String timeStr = hours + "h " + minutes + "m " + seconds + "s";

                    sendSync(sender, "Wait to claim again. Next claim in " + timeStr);
                }
            } catch (Exception e) {
                plugin.getLogger().warning("doClaim error: " + e.getMessage());
                sendSync(sender, "Error during claim. Please try again later.");
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

    // 2) Chama a API de forma assíncrona
    new BukkitRunnable() {
        @Override
        public void run() {
            try {
                // 3) Lista todos os backups
                String resp = api.post("/api/backup/list", "{}", sessionToken);

                // 4) Parseia JSON
                JsonObject root   = JsonParser.parseString(resp).getAsJsonObject();
                JsonArray backups = root.has("backups")
                                    ? root.getAsJsonArray("backups")
                                    : new JsonArray();

                // 5) Monta mensagem formatada
                if (backups.isEmpty()) {
                    sendSync(sender, "ℹ️ You have no backups.");
                } else {
                    StringBuilder sb = new StringBuilder();
                    sb.append("📋 **Your backups (").append(backups.size()).append("):**\n");
                    for (int i = 0; i < backups.size(); i++) {
                        JsonObject b = backups.get(i).getAsJsonObject();
                        String id = b.get("id").getAsString();
                        sb.append("**").append(i + 1).append(".** `").append(id).append("`\n");
                    }
                    sendSync(sender, sb.toString());
                }
            } catch (Exception e) {
                plugin.getLogger().warning("doBackup error: " + e.getMessage());
                sendSync(sender, "Error listing backups. Please try again later.");
            }
        }
    }.runTaskAsynchronously(plugin);

    return true;
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

    // 2) Executa fora da main thread
    new BukkitRunnable() {
        @Override
        public void run() {
            try {
                // 2.1) Pega saldo atualizado via API
                String balResp = api.get("/api/user/" + userId + "/balance", sessionToken);
                double balance = parseNum(balResp, "\"coins\"");
                uc.set("balance", balance);

                // 2.2) Garante que o usuário tenha um cardCode salvo
                String card = uc.getString("card", "");
                if (card == null || card.isEmpty()) {
                    String cardResp = api.post("/api/card", "{}", sessionToken);
                    card = parseStr(cardResp, "\"cardCode\"");
                    uc.set("card", card);
                }

                // 2.3) Salva config atualizada
                users.saveUserConfig(player.getName(), uc);

                // 2.4) Consulta cooldown de claim
                String status = api.get("/api/claim/status", sessionToken);
                long ms = Long.parseLong(parseStr(status, "\"cooldownRemainingMs\""));
                String cooldownMsg;
                if (ms <= 0) {
                    cooldownMsg = "Ready to claim!";
                } else {
                    long totalSec = ms / 1000;
                    long hours   = totalSec / 3600;
                    long minutes = (totalSec % 3600) / 60;
                    long seconds = totalSec % 60;
                    cooldownMsg = hours + "h " + minutes + "m " + seconds + "s";
                }

                // 2.5) Envia no thread principal
                sendSync(sender,
                    "Coin Username: " + uc.getString("username"),
                    "Coin Balance: "  + fmt(balance),
                    "Coin Card: "     + card,
                    "Claim Cooldown: "+ cooldownMsg
                );
            } catch (Exception e) {
                plugin.getLogger().warning("doUser error: " + e.getMessage());
                sendSync(sender, "Error fetching user info.");
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
