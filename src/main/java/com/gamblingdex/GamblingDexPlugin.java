package com.gamblingdex;

import com.gamblingdex.economy.GdxEconomy;
import com.gamblingdex.economy.TokenPayout;
import com.gamblingdex.economy.TokenManager;
import com.gamblingdex.economy.VaultEconomyHook;
import com.gamblingdex.config.Messages;
import com.gamblingdex.games.rouletteworld.RouletteStatsManager;
import com.gamblingdex.games.rouletteworld.WorldRouletteManager;
import com.gamblingdex.games.blackjack.BlackjackManager;
import com.gamblingdex.games.poker.PokerManager;
import com.gamblingdex.games.slots.SlotsController;
import com.gamblingdex.games.slots.SlotsStatsManager;
import com.gamblingdex.gui.ExchangeMenu;
import com.gamblingdex.gui.TokenTypeMenu;
import com.gamblingdex.gui.TokenSellMenu;
import com.gamblingdex.items.GameItemManager;
import com.gamblingdex.listeners.GameItemInteractListener;
import com.gamblingdex.listeners.RouletteBetMenuListener;
import com.gamblingdex.listeners.SlotsMenuListener;
import com.gamblingdex.listeners.StationInteractListener;
import com.gamblingdex.listeners.StationHologramInteractListener;
import com.gamblingdex.listeners.TokenRedeemListener;
import com.gamblingdex.listeners.ExchangeMenuListener;
import com.gamblingdex.listeners.WorldRouletteInteractListener;
import com.gamblingdex.listeners.TokenMenuListener;
import com.gamblingdex.listeners.BlackjackInteractListener;
import com.gamblingdex.stations.StationManager;
import com.gamblingdex.util.PlayerIndex;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

public class GamblingDexPlugin extends JavaPlugin {

    private static GamblingDexPlugin instance;
    private GdxEconomy economy;
    private TokenManager tokenManager;
    private TokenPayout tokenPayout;
    private com.gamblingdex.economy.PendingPayouts pendingPayouts;
    private ExchangeMenu exchangeMenu;
    private VaultEconomyHook vaultEconomy;

    private GameItemManager gameItemManager;
    private SlotsController slotsController;
    private SlotsStatsManager slotsStatsManager;
    private RouletteStatsManager rouletteStatsManager;

    private WorldRouletteManager worldRouletteManager;

    private BlackjackManager blackjackManager;

    private PokerManager pokerManager;

    private com.gamblingdex.modules.ModuleManager moduleManager;

    private StationManager stationManager;

    private TokenTypeMenu tokenTypeMenu;
    private TokenSellMenu tokenSellMenu;

    private PlayerIndex playerIndex;

    private Messages messages;

    /**
     * Revisa config.yml y messages/*.yml antes de recargar. Si un YAML tiene un
     * error de sintaxis, Bukkit lo carga VACÍO y el plugin usa los valores por
     * defecto en silencio (parece que el reload "no funca").
     *
     * @return lista de errores (vacía si todo está bien)
     */
    public java.util.List<String> validateYamlFiles() {
        java.util.List<java.io.File> files = new java.util.ArrayList<>();
        files.add(new java.io.File(getDataFolder(), "config.yml"));
        java.io.File[] msgs = new java.io.File(getDataFolder(), "messages")
                .listFiles((d, n) -> n.toLowerCase(java.util.Locale.ROOT).endsWith(".yml"));
        if (msgs != null)
            files.addAll(java.util.Arrays.asList(msgs));
        java.io.File[] games = new java.io.File(getDataFolder(), "modules")
                .listFiles((d, n) -> n.toLowerCase(java.util.Locale.ROOT).endsWith(".yml"));
        if (games != null)
            files.addAll(java.util.Arrays.asList(games));

        java.util.List<String> errors = new java.util.ArrayList<>();
        for (java.io.File f : files) {
            if (!f.exists())
                continue;
            String parent = f.getParentFile().getName();
            String name = parent.equals("messages") || parent.equals("modules") ? parent + "/" + f.getName() : f.getName();
            try {
                new org.bukkit.configuration.file.YamlConfiguration().load(f);
            } catch (org.bukkit.configuration.InvalidConfigurationException e) {
                String msg = e.getMessage() == null ? "" : e.getMessage();
                java.util.regex.Matcher m = java.util.regex.Pattern.compile("line (\\d+), column (\\d+)").matcher(msg);
                String where = m.find() ? " (línea " + m.group(1) + ", columna " + m.group(2) + ")" : "";
                String first = msg.lines().map(String::trim).filter(s -> !s.isEmpty()).findFirst().orElse("error de sintaxis");
                errors.add(name + where + ": " + first);
            } catch (java.io.IOException e) {
                errors.add(name + ": " + e.getMessage());
            }
        }
        return errors;
    }

    /** config.yml + la config de cada juego (modules/blackjack.yml, poker.yml, ruleta.yml, slots.yml). */
    @Override
    public void reloadConfig() {
        super.reloadConfig();
        com.gamblingdex.config.GameConfigFiles.merge(this, getConfig());
    }

    /**
     * Reloads config.yml and all plugin-managed YAML files.
     */
    public void reloadAll() {
        reloadConfig();

        if (messages != null) {
            messages.reload();
        }

        boolean debug = getConfig().getBoolean("exchange.debug", false);
        if (debug) {
            getLogger()
                    .info("[Exchange] Config recargada: use_vault=" + getConfig().getBoolean("exchange.use_vault", true)
                            + " money_per_unit=" + getConfig().getDouble("exchange.money_per_unit", 1.0));
        }

        if (economy != null)
            economy.reload();
        if (stationManager != null)
            stationManager.reload();
        if (worldRouletteManager != null)
            worldRouletteManager.reload();
        if (blackjackManager != null)
            blackjackManager.reload();
        if (pokerManager != null)
            pokerManager.reload();
        if (moduleManager != null)
            moduleManager.reloadAll();

        if (playerIndex != null)
            playerIndex.load();

        // Re-hook Vault provider in case economy plugin changed/reloaded.
        if (vaultEconomy != null)
            vaultEconomy.setup();

        if (debug && vaultEconomy != null) {
            String provider = vaultEconomy.getProviderName();
            if (!provider.isBlank()) {
                getLogger().info(
                        "[Exchange] Vault provider: " + provider + " (" + vaultEconomy.getProviderClassName() + ")");
            } else {
                getLogger().warning("[Exchange] Vault provider no disponible.");
            }
        }
    }

    @Override
    public void onEnable() {
        instance = this;
        getLogger().info("GamblingDex habilitado.");

        saveDefaultConfig();

        this.messages = new Messages(this);
        this.messages.reload();

        boolean debug = getConfig().getBoolean("exchange.debug", false);
        if (debug) {
            getLogger().info("[Exchange] Debug ON. use_vault=" + getConfig().getBoolean("exchange.use_vault", true)
                    + " money_per_unit=" + getConfig().getDouble("exchange.money_per_unit", 1.0));
        }

        this.economy = new GdxEconomy(this);
        this.tokenManager = new TokenManager(this);
        this.tokenPayout = new TokenPayout(this.tokenManager);
        this.pendingPayouts = new com.gamblingdex.economy.PendingPayouts(this);
        this.exchangeMenu = new ExchangeMenu(this);
        this.vaultEconomy = new VaultEconomyHook();
        this.vaultEconomy.setup();

        // Some economy providers (e.g., Essentials) may register after plugins enable.
        // Re-try shortly after startup so Vault provider is picked up.
        getServer().getScheduler().runTaskLater(this, () -> {
            if (vaultEconomy != null) {
                vaultEconomy.setup();
                if (vaultEconomy.isAvailable()) {
                    getLogger().info("Economía detectada vía Vault.");
                    if (getConfig().getBoolean("exchange.debug", false)) {
                        String provider = vaultEconomy.getProviderName();
                        getLogger().info("[Exchange] Vault provider: " + (provider.isBlank() ? "<unknown>" : provider)
                                + " (" + vaultEconomy.getProviderClassName() + ")");
                    }
                } else {
                    getLogger().warning(
                            "No se detectó economía vía Vault. La mesa de cambio usará comandos configurados (exchange.buy/sell.command) si están presentes.");
                }
            }
        }, 20L);

        this.gameItemManager = new GameItemManager(this);
        this.slotsController = new SlotsController(this);
        this.slotsStatsManager = new SlotsStatsManager(this);

        this.rouletteStatsManager = new RouletteStatsManager(this);

        this.playerIndex = new PlayerIndex(this);

        this.worldRouletteManager = new WorldRouletteManager(this);

        this.blackjackManager = new BlackjackManager(this);

        this.pokerManager = new PokerManager(this);

        this.stationManager = new StationManager(this);

        this.tokenTypeMenu = new com.gamblingdex.gui.TokenTypeMenu(tokenManager);
        this.tokenSellMenu = new com.gamblingdex.gui.TokenSellMenu(tokenManager);

        registerCommands();
        registerListeners();

        // Minijuegos (cada uno en com.gamblingdex.modules.<juego>, se detectan solos).
        this.moduleManager = new com.gamblingdex.modules.ModuleManager(this);
        this.moduleManager.enableAll();

        syncTableNames();
    }

    @Override
    public void onDisable() {
        // Devolver fichas de rondas en curso (si no, se pierden al apagar/reiniciar).
        try {
            if (blackjackManager != null)
                blackjackManager.abortAllRounds();
        } catch (Throwable ignored) {
        }
        try {
            if (worldRouletteManager != null)
                worldRouletteManager.abortAllRounds();
        } catch (Throwable ignored) {
        }
        try {
            if (pokerManager != null)
                pokerManager.shutdown();
        } catch (Throwable ignored) {
        }
        try {
            if (moduleManager != null)
                moduleManager.disableAll();
        } catch (Throwable ignored) {
        }
        try {
            if (slotsStatsManager != null)
                slotsStatsManager.save();
        } catch (Throwable ignored) {
        }
        try {
            if (rouletteStatsManager != null)
                rouletteStatsManager.save();
        } catch (Throwable ignored) {
        }
        try {
            if (playerIndex != null)
                playerIndex.save();
        } catch (Throwable ignored) {
        }
        getLogger().info("GamblingDex deshabilitado.");
    }

    private void registerCommands() {
        getCommand("gamblingdex").setExecutor(new com.gamblingdex.commands.GamblingDexCommand());
        // getCommand("gdx") NO se debe registrar aquí, es alias de gamblingdex
    }

    private void registerListeners() {
        org.bukkit.plugin.PluginManager pm = getServer().getPluginManager();
        pm.registerEvents(new com.gamblingdex.listeners.PlayerJoinListener(this), this);
        pm.registerEvents(new TokenRedeemListener(this), this);
        pm.registerEvents(new GameItemInteractListener(this), this);
        pm.registerEvents(new StationInteractListener(this), this);
        pm.registerEvents(new StationHologramInteractListener(this), this);
        pm.registerEvents(new WorldRouletteInteractListener(this), this);
        pm.registerEvents(new BlackjackInteractListener(this), this);
        pm.registerEvents(new com.gamblingdex.listeners.BlackjackMenuListener(this), this);
        pm.registerEvents(new ExchangeMenuListener(this), this);
        pm.registerEvents(new RouletteBetMenuListener(this), this);
        pm.registerEvents(new SlotsMenuListener(this), this);
        pm.registerEvents(new com.gamblingdex.listeners.PokerListener(this), this);
        pm.registerEvents(new com.gamblingdex.listeners.TokenMenuListener(this, tokenManager), this);
    }

    public static GamblingDexPlugin getInstance() {
        return instance;
    }

    public GdxEconomy getEconomy() {
        return economy;
    }

    public TokenManager getTokenManager() {
        return tokenManager;
    }

    public TokenPayout getTokenPayout() {
        return tokenPayout;
    }

    public com.gamblingdex.modules.ModuleManager getModuleManager() {
        return moduleManager;
    }

    /** El .jar del plugin (para que ModuleManager encuentre los minijuegos). */
    public java.io.File getPluginJar() {
        return getFile();
    }

    public com.gamblingdex.economy.PendingPayouts getPendingPayouts() {
        return pendingPayouts;
    }

    public GameItemManager getGameItemManager() {
        return gameItemManager;
    }

    public SlotsController getSlotsController() {
        return slotsController;
    }

    public SlotsStatsManager getSlotsStatsManager() {
        return slotsStatsManager;
    }

    public RouletteStatsManager getRouletteStatsManager() {
        return rouletteStatsManager;
    }

    public WorldRouletteManager getWorldRouletteManager() {
        return worldRouletteManager;
    }

    public BlackjackManager getBlackjackManager() {
        return blackjackManager;
    }

    public PokerManager getPokerManager() {
        return pokerManager;
    }

    public StationManager getStationManager() {
        return stationManager;
    }

    public ExchangeMenu getExchangeMenu() {
        return exchangeMenu;
    }

    public VaultEconomyHook getVaultEconomy() {
        return vaultEconomy;
    }

    public TokenTypeMenu getTokenTypeMenu() {
        if (tokenTypeMenu == null) {
            tokenTypeMenu = new com.gamblingdex.gui.TokenTypeMenu(tokenManager);
        }
        return tokenTypeMenu;
    }

    public TokenSellMenu getTokenSellMenu() {
        if (tokenSellMenu == null) {
            tokenSellMenu = new com.gamblingdex.gui.TokenSellMenu(tokenManager);
        }
        return tokenSellMenu;
    }

    public PlayerIndex getPlayerIndex() {
        return playerIndex;
    }

    public Messages getMessages() {
        return messages;
    }

    /**
     * Give server money to player (Vault preferred, else optional console command).
     */
    public boolean exchangeGiveMoney(Player player, double money) {
        if (player == null)
            return false;
        if (money <= 0)
            return true;

        boolean useVault = getConfig().getBoolean("exchange.use_vault", true);
        if (useVault && vaultEconomy != null && vaultEconomy.isAvailable()) {
            boolean debug = getConfig().getBoolean("exchange.debug", false);
            double before = debug ? vaultEconomy.getBalance(player) : 0.0;
            boolean ok = vaultEconomy.deposit(player, money);
            if (debug) {
                double after = vaultEconomy.getBalance(player);
                String provider = vaultEconomy.getProviderName();
                getLogger().info("[Exchange][SELL][Vault] player=" + player.getName()
                        + " provider=" + (provider.isBlank() ? "<unknown>" : provider)
                        + " amount=" + money
                        + " before=" + before
                        + " after=" + after
                        + " ok=" + ok);
                if (ok && before == after) {
                    getLogger().warning("[Exchange][SELL] Vault reportó éxito pero el balance no cambió. ProviderClass="
                            + vaultEconomy.getProviderClassName());
                }
            }
            return ok;
        }

        boolean debug = getConfig().getBoolean("exchange.debug", false);
        if (debug && useVault) {
            getLogger().warning("[Exchange][SELL] Vault no disponible, intentando fallback por comando.");
        }

        String cmd = getConfig().getString("exchange.sell.command", "");
        if (cmd == null || cmd.isBlank()) {
            if (debug) {
                getLogger().warning(
                        "[Exchange][SELL] Sin comando fallback configurado (exchange.sell.command). amount=" + money);
            }
            return false;
        }
        cmd = cmd.replace("{player}", player.getName()).replace("{money}", plainMoney(money));
        if (debug) {
            getLogger().info("[Exchange][SELL][CMD] " + cmd);
        }
        getServer().dispatchCommand(getServer().getConsoleSender(), cmd);
        return true;
    }

    /**
     * Take server money from player (Vault preferred, else optional console
     * command).
     */
    public boolean exchangeTakeMoney(Player player, double money) {
        if (player == null)
            return false;
        if (money <= 0)
            return true;

        boolean useVault = getConfig().getBoolean("exchange.use_vault", true);
        if (useVault && vaultEconomy != null && vaultEconomy.isAvailable()) {
            boolean debug = getConfig().getBoolean("exchange.debug", false);
            double before = vaultEconomy.getBalance(player);
            if (before < money)
                return false;
            boolean ok = vaultEconomy.withdraw(player, money);
            if (debug) {
                double after = vaultEconomy.getBalance(player);
                String provider = vaultEconomy.getProviderName();
                getLogger().info("[Exchange][BUY][Vault] player=" + player.getName()
                        + " provider=" + (provider.isBlank() ? "<unknown>" : provider)
                        + " amount=" + money
                        + " before=" + before
                        + " after=" + after
                        + " ok=" + ok);
                if (ok && before == after) {
                    getLogger().warning("[Exchange][BUY] Vault reportó éxito pero el balance no cambió. ProviderClass="
                            + vaultEconomy.getProviderClassName());
                }
            }
            return ok;
        }

        // Sin Vault NO se puede verificar el saldo: un comando de consola tipo
        // "eco take" puede fallar y aun así el jugador recibiría las fichas gratis.
        // Por eso la compra solo funciona con Vault.
        getLogger().warning("[Exchange][BUY] Compra rechazada: Vault no disponible (no se puede verificar el saldo de "
                + player.getName() + ").");
        return false;
    }

    /** Monto sin notación científica (1.0E7 → 10000000) para comandos de consola. */
    private static String plainMoney(double money) {
        return java.math.BigDecimal.valueOf(money).setScale(2, java.math.RoundingMode.HALF_UP)
                .stripTrailingZeros().toPlainString();
    }

    /**
     * Remove ALL tokens from inventory and return total units removed.
     */
    public long exchangeTakeAllTokens(Player player) {
        if (player == null)
            return 0L;

        long totalUnits = 0L;
        var inv = player.getInventory();
        for (int i = 0; i < inv.getSize(); i++) {
            ItemStack it = inv.getItem(i);
            Integer v = tokenManager.getTokenValue(it);
            if (v == null)
                continue;

            int amt = it.getAmount();
            totalUnits += (long) v * (long) amt;
            inv.setItem(i, null);
        }
        return totalUnits;
    }

    /**
     * Give tokens that total the given units.
     */
    public void exchangeGiveTokens(Player player, long units) {
        if (player == null || units <= 0)
            return;
        for (ItemStack stack : tokenManager.createTokensForValue(units)) {
            var leftover = player.getInventory().addItem(stack);
            if (!leftover.isEmpty()) {
                for (ItemStack lf : leftover.values()) {
                    player.getWorld().dropItemNaturally(player.getLocation(), lf);
                }
            }
        }
    }

    private static final java.util.regex.Pattern HEX_COLOR = java.util.regex.Pattern.compile("&#([0-9a-fA-F]{6})");

    /** Colores con & (ej. &a, &l) y hexadecimales con &#RRGGBB (ej. &#FF8800). */
    public String color(String text) {
        if (text == null)
            return "";
        java.util.regex.Matcher m = HEX_COLOR.matcher(text);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            StringBuilder hex = new StringBuilder("§x");
            for (char c : m.group(1).toCharArray())
                hex.append('§').append(c);
            m.appendReplacement(sb, java.util.regex.Matcher.quoteReplacement(hex.toString()));
        }
        m.appendTail(sb);
        return sb.toString().replace('&', '§');
    }

    /**
     * Agrega a modules/blackjack.yml y modules/poker.yml (sección table_names)
     * cada mesa que todavía no esté, con su nombre como título. Así el admin ve
     * ahí la lista de mesas y solo cambia el título.
     */
    public void syncTableNames() {
        java.util.List<String> bj = new java.util.ArrayList<>();
        if (blackjackManager != null)
            bj.addAll(blackjackManager.getTableNames());
        java.util.List<String> pk = new java.util.ArrayList<>();
        if (pokerManager != null)
            for (com.gamblingdex.games.poker.PokerTable t : pokerManager.getTables())
                pk.add(t.getName());
        addTableNames("blackjack", bj);
        addTableNames("poker", pk);
    }

    private void addTableNames(String game, java.util.List<String> tables) {
        java.io.File f = new java.io.File(new java.io.File(getDataFolder(), "modules"), game + ".yml");
        if (!f.exists() || tables.isEmpty())
            return;
        org.bukkit.configuration.file.YamlConfiguration yml = org.bukkit.configuration.file.YamlConfiguration
                .loadConfiguration(f);
        org.bukkit.configuration.ConfigurationSection sec = yml.getConfigurationSection("table_names");
        boolean changed = false;
        for (String t : tables) {
            if (t == null || t.isBlank())
                continue;
            boolean found = false;
            if (sec != null)
                for (String k : sec.getKeys(false))
                    if (k.equalsIgnoreCase(t))
                        found = true;
            if (!found) {
                yml.set("table_names." + t, t);
                getConfig().set(game + ".table_names." + t, t);
                changed = true;
            }
        }
        if (changed) {
            try {
                yml.save(f);
            } catch (java.io.IOException e) {
                getLogger().warning("No se pudo actualizar modules/" + game + ".yml: " + e.getMessage());
            }
        }
    }

    /**
     * Nombre bonito de una mesa desde {@code <juego>.table_names.<mesa>} en
     * modules/&lt;juego&gt;.yml (sin importar mayúsculas), ya con colores. Null si no hay.
     */
    public String tableNameFromConfig(String game, String table) {
        if (table == null)
            return null;
        org.bukkit.configuration.ConfigurationSection sec = getConfig().getConfigurationSection(game + ".table_names");
        if (sec == null)
            return null;
        for (String k : sec.getKeys(false)) {
            if (k.equalsIgnoreCase(table)) {
                String v = sec.getString(k);
                return v == null || v.isBlank() ? null : color(v);
            }
        }
        return null;
    }

}
