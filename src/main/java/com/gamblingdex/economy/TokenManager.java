package com.gamblingdex.economy;

import com.gamblingdex.GamblingDexPlugin;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.ArrayList;
import java.util.List;
import java.util.Comparator;
import java.util.stream.Collectors;

public class TokenManager {

    private final GamblingDexPlugin plugin;
    private final NamespacedKey tokenKey;
    private final NamespacedKey tokenValueKey;

    private static final Map<Material, Integer> DENOMS;
    static {
        Map<Material, Integer> m = new LinkedHashMap<>();
        m.put(Material.YELLOW_DYE, 1);
        m.put(Material.RED_DYE, 10);
        m.put(Material.BLUE_DYE, 50);
        m.put(Material.LIME_DYE, 100);
        m.put(Material.PURPLE_DYE, 500);
        m.put(Material.ORANGE_DYE, 1000);
        m.put(Material.PINK_DYE, 5000);
        m.put(Material.BLACK_DYE, 10000);
        m.put(Material.GRAY_DYE, 50000);
        m.put(Material.WHITE_DYE, 100000);
        DENOMS = Collections.unmodifiableMap(m);
    }

    public TokenManager(GamblingDexPlugin plugin) {
        this.plugin = plugin;
        this.tokenKey = new NamespacedKey(plugin, "gdx_token");
        this.tokenValueKey = new NamespacedKey(plugin, "gdx_token_value");
    }

    /**
     * Backwards-compatible: creates the lowest denomination (YELLOW_DYE = 1).
     */
    public ItemStack createToken(int amount) {
        return createToken(Material.YELLOW_DYE, amount);
    }

    /**
     * Creates a token item with a fixed currency value based on its dye material.
     * The value is stored in PDC so players can't craft dyes and redeem them.
     */
    public ItemStack createToken(Material material, int amount) {

        FileConfiguration config = plugin.getConfig();
        Integer denomValue = DENOMS.get(material);
        if (denomValue == null) {
            material = Material.YELLOW_DYE;
            denomValue = DENOMS.get(material);
        }

        int safeAmount = Math.max(1, Math.min(64, amount));
        ItemStack item = new ItemStack(material, safeAmount);

        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            boolean useTemplate = config.getBoolean("currency.token.use_template_format", false);

            if (useTemplate) {
                String currencyName = color(config.getString("currency.name", "&6Moneda GDX"));
                String displayNameTemplate = color(
                        config.getString("currency.token.display-name", "&eToken {value} {currency}"));
                if (displayNameTemplate != null
                        && !displayNameTemplate.contains("{value}")
                        && !displayNameTemplate.contains("{valor}")) {
                    // Safety fallback: if user removed placeholders, still show value.
                    displayNameTemplate = displayNameTemplate + " {value}";
                }

                String displayName = (displayNameTemplate == null ? "" : displayNameTemplate)
                        .replace("{value}", String.valueOf(denomValue))
                        .replace("{valor}", String.valueOf(denomValue))
                        .replace("{currency}", currencyName);
                meta.setDisplayName(displayName);

                List<String> loreCfg = config.getStringList("currency.token.lore");
                if (loreCfg != null && !loreCfg.isEmpty()) {
                    List<String> lore = new ArrayList<>();
                    for (String line : loreCfg) {
                        String rendered = color(line)
                                .replace("{value}", String.valueOf(denomValue))
                                .replace("{valor}", String.valueOf(denomValue))
                                .replace("{currency}", currencyName);
                        lore.add(rendered);
                    }
                    meta.setLore(lore);
                }
            } else {
                TokenStyle style = styleFor(material, denomValue);
                String nameColor = style.titleColor.equals("&0") ? "gray" : legacyToJsonColor(style.titleColor);
                String colorName = style.colorName;
                // DisplayName JSON
                String displayNameJson = "{\"text\":\"Ficha " + colorName + "\",\"color\":\"" + nameColor
                        + "\",\"bold\":true}";

                // Lore JSON: cada línea es un componente separado
                String[] loreLines = new String[] {
                        "{\"text\":\"Una ficha donde podras usarla en nuestras maquinas\",\"color\":\"gray\"}",
                        "{\"text\":\"Valor: \",\"color\":\"gray\",\"extra\":[{\"text\":\"" + denomValue
                                + " \",\"color\":\"gold\"},{\"text\":\"⛃\",\"color\":\"gold\"}]}",
                        "{\"text\":\"¡Usala en \",\"color\":\"green\",\"extra\":[{\"text\":\"/warp Casino\",\"color\":\"yellow\"},{\"text\":\"!\",\"color\":\"green\"}]}"
                };
                try {
                    net.kyori.adventure.text.Component displayName = net.kyori.adventure.text.serializer.gson.GsonComponentSerializer
                            .gson().deserialize(displayNameJson);
                    java.util.List<net.kyori.adventure.text.Component> lore = new ArrayList<>();
                    for (String line : loreLines) {
                        lore.add(net.kyori.adventure.text.serializer.gson.GsonComponentSerializer.gson()
                                .deserialize(line));
                    }
                    meta.displayName(displayName);
                    meta.lore(lore);
                } catch (Throwable t) {
                    meta.setDisplayName(color(style.titleColor + "Ficha " + style.colorName));
                    meta.setLore(List.of(
                            color("&7Una ficha donde podras usarla en nuestras maquinas"),
                            color("&7Valor: &6" + denomValue + " ⛃"),
                            color("&a¡Usala en &e/warp Casino&a!")));
                }
            }

            if (config.contains("currency.token.custom-model-data")) {
                int cmd = config.getInt("currency.token.custom-model-data", 0);
                if (cmd > 0)
                    meta.setCustomModelData(cmd);
            }

            boolean glow = config.getBoolean("currency.token.glow", true);
            if (glow) {
                // Por clave "unbreaking": funciona en 1.20.4 y en 1.20.5+ (donde
                // el nombre DURABILITY ya no existe).
                Enchantment glowEnchant = Enchantment.getByKey(NamespacedKey.minecraft("unbreaking"));
                if (glowEnchant != null) {
                    meta.addEnchant(glowEnchant, 1, true);
                }
                meta.addItemFlags(ItemFlag.HIDE_ENCHANTS);
            }

            PersistentDataContainer pdc = meta.getPersistentDataContainer();
            pdc.set(tokenKey, PersistentDataType.BYTE, (byte) 1);
            pdc.set(tokenValueKey, PersistentDataType.INTEGER, denomValue);

            item.setItemMeta(meta);
        }

        return item;
    }

    // Convierte un color legacy (&x) a nombre JSON ("red", "gray", etc)
    private static String legacyToJsonColor(String legacy) {
        return switch (legacy) {
            case "&0" -> "gray"; // negro se ve mal, usar gris
            case "&1" -> "dark_blue";
            case "&2" -> "dark_green";
            case "&3" -> "dark_aqua";
            case "&4" -> "dark_red";
            case "&5" -> "dark_purple";
            case "&6" -> "gold";
            case "&7" -> "gray";
            case "&8" -> "dark_gray";
            case "&9" -> "blue";
            case "&a" -> "green";
            case "&b" -> "aqua";
            case "&c" -> "red";
            case "&d" -> "light_purple";
            case "&e" -> "yellow";
            case "&f" -> "white";
            default -> "white";
        };
    }

    public boolean isToken(ItemStack item) {
        if (item == null || item.getType() == Material.AIR)
            return false;
        ItemMeta meta = item.getItemMeta();
        if (meta == null)
            return false;
        Byte value = meta.getPersistentDataContainer().get(tokenKey, PersistentDataType.BYTE);
        return value != null && value == (byte) 1;
    }

    /**
     * Returns the currency value of a token item (per item), or null if not a
     * token.
     */
    public Integer getTokenValue(ItemStack item) {
        if (!isToken(item))
            return null;
        ItemMeta meta = item.getItemMeta();
        if (meta == null)
            return null;

        Integer value = meta.getPersistentDataContainer().get(tokenValueKey, PersistentDataType.INTEGER);
        if (value != null && value > 0)
            return value;

        // Backwards-compat: old tokens had only the marker, treat as 1.
        return 1;
    }

    public Material parseDenomination(String input) {
        if (input == null)
            return Material.YELLOW_DYE;
        String s = input.trim().toLowerCase(Locale.ROOT);

        // allow numeric values
        try {
            int value = Integer.parseInt(s);
            for (Map.Entry<Material, Integer> e : DENOMS.entrySet()) {
                if (e.getValue() == value)
                    return e.getKey();
            }
        } catch (NumberFormatException ignored) {
        }

        // allow color names
        return switch (s) {
            case "yellow" -> Material.YELLOW_DYE;
            case "red" -> Material.RED_DYE;
            case "blue" -> Material.BLUE_DYE;
            case "lime", "green" -> Material.LIME_DYE;
            case "purple" -> Material.PURPLE_DYE;
            case "orange" -> Material.ORANGE_DYE;
            case "pink" -> Material.PINK_DYE;
            case "black" -> Material.BLACK_DYE;
            case "gray", "grey" -> Material.GRAY_DYE;
            case "white" -> Material.WHITE_DYE;
            default -> Material.YELLOW_DYE;
        };
    }

    public String getDenominationsHelp() {
        return "yellow=1, red=10, blue=50, lime=100, purple=500, orange=1000, pink=5000, black=10000, gray=50000, white=100000";
    }

    /**
     * Creates token ItemStacks whose total value (sum of denomValue * amount)
     * equals the given value.
     * Uses a greedy breakdown from highest denomination to lowest.
     */
    public List<ItemStack> createTokensForValue(long totalValue) {
        if (totalValue <= 0)
            return Collections.emptyList();

        List<Map.Entry<Material, Integer>> denomsDesc = DENOMS.entrySet().stream()
                .sorted(Comparator.comparingInt((Map.Entry<Material, Integer> e) -> e.getValue()).reversed())
                .collect(Collectors.toList());

        long remaining = totalValue;
        List<ItemStack> out = new ArrayList<>();

        for (Map.Entry<Material, Integer> e : denomsDesc) {
            int denom = e.getValue();
            if (denom <= 0)
                continue;
            long count = remaining / denom;
            if (count <= 0)
                continue;

            while (count > 0) {
                int stackAmount = (int) Math.min(64L, count);
                out.add(createToken(e.getKey(), stackAmount));
                count -= stackAmount;
            }

            remaining = remaining % denom;
            if (remaining == 0)
                break;
        }

        // If something weird happens, fall back to yellow tokens.
        while (remaining > 0) {
            int stackAmount = (int) Math.min(64L, remaining);
            out.add(createToken(Material.YELLOW_DYE, stackAmount));
            remaining -= stackAmount;
        }

        return out;
    }

    private static String color(String text) {
        if (text == null)
            return "";
        return text.replace('&', '§');
    }

    private static TokenStyle styleFor(Material material, int denomValue) {
        // Derive style from denomination material. If material is unknown, fall back to
        // value tiers.
        return switch (material) {
            case YELLOW_DYE -> new TokenStyle("&e", "Amarilla");
            case RED_DYE -> new TokenStyle("&c", "Roja");
            case BLUE_DYE -> new TokenStyle("&9", "Azul");
            case LIME_DYE -> new TokenStyle("&a", "Verde");
            case PURPLE_DYE -> new TokenStyle("&5", "Morada");
            case ORANGE_DYE -> new TokenStyle("&6", "Naranja");
            case PINK_DYE -> new TokenStyle("&d", "Rosa");
            case BLACK_DYE -> new TokenStyle("&7", "Negra");
            case GRAY_DYE -> new TokenStyle("&7", "Gris");
            case WHITE_DYE -> new TokenStyle("&f", "Blanca");
            default -> {
                if (denomValue >= 10000)
                    yield new TokenStyle("&7", "Negra");
                if (denomValue >= 5000)
                    yield new TokenStyle("&d", "Rosa");
                if (denomValue >= 1000)
                    yield new TokenStyle("&6", "Naranja");
                if (denomValue >= 500)
                    yield new TokenStyle("&5", "Morada");
                if (denomValue >= 100)
                    yield new TokenStyle("&a", "Verde");
                if (denomValue >= 50)
                    yield new TokenStyle("&9", "Azul");
                if (denomValue >= 10)
                    yield new TokenStyle("&c", "Roja");
                yield new TokenStyle("&e", "Amarilla");
            }
        };
    }

    private record TokenStyle(String titleColor, String colorName) {
    }

    public static Map<Material, Integer> getDenoms() {
        return DENOMS;
    }
}
