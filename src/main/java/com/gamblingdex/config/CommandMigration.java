package com.gamblingdex.config;

import org.bukkit.configuration.Configuration;
import org.bukkit.configuration.file.FileConfiguration;

import java.util.Locale;
import java.util.Set;

/**
 * Los comandos de admin pasaron a inglés (seat add, create, remove...). Los
 * archivos de mensajes que ya estaban en el servidor conservan los textos
 * viejos con los comandos en español; al cargar se reemplazan por el texto
 * nuevo del jar (solo esos mensajes, el resto de cambios del admin se respeta).
 */
public final class CommandMigration {

    private static final Set<String> SPANISH = Set.of("asiento", "asientos", "agregar", "añadir", "quitar", "crear",
            "lista", "listar", "ciegas", "torneo", "empezar", "cancelar", "construir", "borrar", "estacion",
            "estación", "mesa", "mesas", "recargar", "ayuda", "nombre");

    private CommandMigration() {
    }

    /** true si el texto muestra un /gdx ... con palabras de los comandos viejos. */
    public static boolean outdated(String s) {
        if (s == null)
            return false;
        String t = s.toLowerCase(Locale.ROOT);
        int i = t.indexOf("/gdx ");
        while (i >= 0) {
            String rest = t.substring(i + 5);
            int end = rest.length();
            for (char c : new char[] { '&', '§', '(', '.', ',', '"', '\n', '-', '|' }) {
                int k = rest.indexOf(c);
                if (k >= 0 && k < end)
                    end = k;
            }
            for (String w : rest.substring(0, end).trim().split("\\s+"))
                if (SPANISH.contains(w))
                    return true;
            i = t.indexOf("/gdx ", i + 5);
        }
        return false;
    }

    /** Reemplaza en {@code server} los textos viejos por los del jar. Devuelve true si cambió algo. */
    public static boolean migrate(FileConfiguration server, Configuration jar) {
        if (server == null || jar == null)
            return false;
        boolean changed = false;
        for (String path : server.getKeys(true)) {
            if (!server.isString(path))
                continue;
            String v = server.getString(path);
            if (!outdated(v))
                continue;
            String def = jar.getString(path);
            if (def != null && !def.equals(v) && !outdated(def)) {
                server.set(path, def);
                changed = true;
            }
        }
        return changed;
    }
}
