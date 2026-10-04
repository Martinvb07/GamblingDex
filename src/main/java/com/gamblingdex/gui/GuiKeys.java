package com.gamblingdex.gui;

import com.gamblingdex.GamblingDexPlugin;
import org.bukkit.NamespacedKey;

public final class GuiKeys {
    private GuiKeys() {
    }

    public static NamespacedKey actionKey(GamblingDexPlugin plugin) {
        return new NamespacedKey(plugin, "gdx_gui_action");
    }

    public static NamespacedKey intKey(GamblingDexPlugin plugin) {
        return new NamespacedKey(plugin, "gdx_gui_int");
    }
}
