package com.gamblingdex.pack;

import com.gamblingdex.GamblingDexPlugin;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

/**
 * Resource pack del casino (fondo de los menús y sonidos propios). Va dentro del
 * jar en {@code pack/} (lo genera models/tools/build_pack.py) y con
 * {@code /gdx pack} se saca a plugins/GamblingDex:
 * <ul>
 * <li>{@code GamblingDex-pack.zip}: solo el de GamblingDex.</li>
 * <li>{@code GamblingDex-merged.zip}: el de GamblingDex + el que genera
 * ModelEngine (los modelos 3D), para subir uno solo.</li>
 * </ul>
 * Si {@code resource_pack.send.url} está puesto, se manda a cada jugador al entrar.
 */
public class ResourcePackManager implements Listener {

    public static final String PACK_ZIP = "GamblingDex-pack.zip";
    public static final String MERGED_ZIP = "GamblingDex-merged.zip";

    private final GamblingDexPlugin plugin;

    public ResourcePackManager(GamblingDexPlugin plugin) {
        this.plugin = plugin;
    }

    /** ¿Los menús usan el fondo del resource pack? */
    public boolean customGui() {
        return plugin.getConfig().getBoolean("resource_pack.custom_gui", false);
    }

    /** ¿Se usan los sonidos del resource pack en vez de los de Minecraft? */
    public boolean customSounds() {
        return plugin.getConfig().getBoolean("resource_pack.custom_sounds", false);
    }

    /** Resultado de {@link #build()}. merged = null si no se encontró el pack de ModelEngine. */
    public record Result(File pack, String packSha1, File merged, String mergedSha1) {
    }

    /** Genera los zips. Hace E/S de disco: llamar fuera del hilo principal. */
    public Result build() throws IOException {
        Map<String, byte[]> ours = bundled();
        File dir = plugin.getDataFolder();
        File pack = new File(dir, PACK_ZIP);
        writeZip(pack, ours);

        Map<String, byte[]> me = modelEnginePack();
        File merged = null;
        if (!me.isEmpty()) {
            Map<String, byte[]> all = new TreeMap<>(me);
            all.putAll(ours); // pack.mcmeta y pack.png de GamblingDex (cubren más versiones)
            merged = new File(dir, MERGED_ZIP);
            writeZip(merged, all);
        }
        return new Result(pack, sha1(pack), merged, merged == null ? null : sha1(merged));
    }

    /** Archivos del pack que van dentro del jar (lista en pack/files.txt). */
    private Map<String, byte[]> bundled() throws IOException {
        Map<String, byte[]> out = new TreeMap<>();
        try (InputStream list = plugin.getResource("pack/files.txt")) {
            if (list == null)
                throw new FileNotFoundException("pack/files.txt no está en el jar");
            for (String line : new String(list.readAllBytes(), StandardCharsets.UTF_8).split("\\R")) {
                String path = line.trim();
                if (path.isEmpty())
                    continue;
                try (InputStream in = plugin.getResource("pack/" + path)) {
                    if (in != null)
                        out.put(path, in.readAllBytes());
                }
            }
        }
        return out;
    }

    /**
     * El pack que genera ModelEngine con /meg reload: la carpeta "resource pack" o el
     * zip "resource pack.zip" en plugins/ModelEngine (el que esté). Vacío si no hay.
     */
    private Map<String, byte[]> modelEnginePack() throws IOException {
        Map<String, byte[]> out = new TreeMap<>();
        File meDir = new File(plugin.getDataFolder().getParentFile(), "ModelEngine");
        String custom = plugin.getConfig().getString("resource_pack.modelengine_pack", "");
        List<File> candidates = new ArrayList<>();
        if (custom != null && !custom.isBlank())
            candidates.add(new File(plugin.getDataFolder().getParentFile().getParentFile(), custom));
        candidates.add(new File(meDir, "resource pack"));
        candidates.add(new File(meDir, "resource pack.zip"));
        candidates.add(new File(meDir, "resource_pack"));
        candidates.add(new File(meDir, "resource_pack.zip"));
        for (File f : candidates) {
            if (f.isDirectory()) {
                Path root = f.toPath();
                try (var walk = Files.walk(root)) {
                    for (Path p : (Iterable<Path>) walk::iterator)
                        if (Files.isRegularFile(p))
                            out.put(root.relativize(p).toString().replace(File.separatorChar, '/'), Files.readAllBytes(p));
                }
            } else if (f.isFile() && f.getName().endsWith(".zip")) {
                try (ZipFile zip = new ZipFile(f)) {
                    for (ZipEntry e : Collections.list(zip.entries()))
                        if (!e.isDirectory())
                            try (InputStream in = zip.getInputStream(e)) {
                                out.put(e.getName(), in.readAllBytes());
                            }
                }
            }
            if (!out.isEmpty())
                return out;
        }
        return out;
    }

    private static void writeZip(File file, Map<String, byte[]> files) throws IOException {
        File tmp = new File(file.getParentFile(), file.getName() + ".tmp");
        try (ZipOutputStream zip = new ZipOutputStream(new FileOutputStream(tmp))) {
            for (Map.Entry<String, byte[]> e : new TreeMap<>(files).entrySet()) {
                ZipEntry ze = new ZipEntry(e.getKey());
                ze.setTime(0L); // mismo zip = mismo SHA-1
                zip.putNextEntry(ze);
                zip.write(e.getValue());
                zip.closeEntry();
            }
        }
        Files.move(tmp.toPath(), file.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
    }

    private static String sha1(File f) throws IOException {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-1");
            byte[] h = md.digest(Files.readAllBytes(f.toPath()));
            StringBuilder sb = new StringBuilder();
            for (byte b : h)
                sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IOException(e);
        }
    }

    // ------------------------------------------------------------------
    // Enviar el pack al entrar
    // ------------------------------------------------------------------

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        if (!plugin.getConfig().getBoolean("resource_pack.send.enabled", false))
            return;
        String url = plugin.getConfig().getString("resource_pack.send.url", "");
        if (url == null || url.isBlank())
            return;
        Player p = event.getPlayer();
        byte[] hash = hex(plugin.getConfig().getString("resource_pack.send.sha1", ""));
        String prompt = plugin.color(plugin.getConfig().getString("resource_pack.send.prompt",
                "&6GamblingDex &7usa un resource pack para los menús y sonidos del casino."));
        boolean required = plugin.getConfig().getBoolean("resource_pack.send.required", false);
        // Un poco después de entrar (algunos clientes ignoran el pack si llega en el mismo tick).
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            if (p.isOnline())
                p.setResourcePack(url, hash, prompt, required);
        }, 20L);
    }

    private static byte[] hex(String s) {
        if (s == null)
            return null;
        s = s.trim();
        if (s.length() != 40)
            return null;
        byte[] out = new byte[20];
        try {
            for (int i = 0; i < 20; i++)
                out[i] = (byte) Integer.parseInt(s.substring(i * 2, i * 2 + 2), 16);
        } catch (NumberFormatException e) {
            return null;
        }
        return out;
    }
}
