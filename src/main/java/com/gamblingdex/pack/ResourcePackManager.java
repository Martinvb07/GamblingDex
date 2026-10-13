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
 * ModelEngine (los modelos 3D) + los de otros plugins ({@code resource_pack.extra_packs}),
 * para subir uno solo. Los archivos que tocan varios packs (modelos de ítems con
 * custom_model_data, fuentes, sonidos, atlas, idiomas) se combinan en vez de pisarse.</li>
 * </ul>
 * Si {@code resource_pack.send.url} está puesto, se manda a cada jugador al entrar.
 * <p>
 * Con {@code resource_pack.host.enabled} el plugin sirve el pack él mismo (un mini servidor
 * web en {@code host.port}): lo arma solo al arrancar y con /gdx pack, y se lo manda a cada
 * jugador al entrar (y a los conectados si cambió). No hay que subirlo a ninguna web.
 */
public class ResourcePackManager implements Listener {

    public static final String PACK_ZIP = "GamblingDex-pack.zip";
    public static final String MERGED_ZIP = "GamblingDex-merged.zip";

    private final GamblingDexPlugin plugin;
    /** Mini servidor web del pack (resource_pack.host). */
    private com.sun.net.httpserver.HttpServer http;
    private volatile byte[] hosted;
    private volatile String hostedSha1;

    public ResourcePackManager(GamblingDexPlugin plugin) {
        this.plugin = plugin;
    }

    // ------------------------------------------------------------------
    // Pack servido por el plugin (resource_pack.host)
    // ------------------------------------------------------------------

    private boolean hostEnabled() {
        return plugin.getConfig().getBoolean("resource_pack.host.enabled", false);
    }

    /** Arranca (o reinicia) el servidor del pack y lo arma un poco después (cuando ModelEngine ya hizo el suyo). */
    public void startHost() {
        stopHost();
        if (!hostEnabled())
            return;
        int port = plugin.getConfig().getInt("resource_pack.host.port", 8163);
        try {
            http = com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress(port), 0);
            http.createContext("/", ex -> {
                byte[] body = hosted;
                try (ex) {
                    if (body == null) {
                        ex.sendResponseHeaders(503, -1);
                        return;
                    }
                    ex.getResponseHeaders().set("Content-Type", "application/zip");
                    ex.sendResponseHeaders(200, body.length);
                    ex.getResponseBody().write(body);
                }
            });
            http.start();
            plugin.getLogger().info("[Pack] Sirviendo el resource pack en el puerto " + port + ".");
        } catch (IOException e) {
            http = null;
            plugin.getLogger().warning("[Pack] No se pudo abrir el puerto " + port + " para el resource pack: "
                    + e.getMessage() + " (¿está ocupado o tu hosting no lo deja?)");
            return;
        }
        if (hostAddress() == null)
            plugin.getLogger().warning("[Pack] Pon la IP pública o el dominio de tu server en resource_pack.host.address"
                    + " para que los jugadores puedan bajar el pack.");
        // ModelEngine arma su pack al arrancar: se espera un poco para incluirlo
        plugin.getServer().getScheduler().runTaskLaterAsynchronously(plugin, () -> {
            try {
                publish(build());
            } catch (IOException e) {
                plugin.getLogger().warning("[Pack] No se pudo armar el resource pack: " + e.getMessage());
            }
        }, 200L);
    }

    public void stopHost() {
        if (http != null) {
            http.stop(0);
            http = null;
        }
    }

    /** IP o dominio con el que los jugadores llegan al server (host.address o server-ip), o null. */
    private String hostAddress() {
        String a = plugin.getConfig().getString("resource_pack.host.address", "");
        if (a == null || a.isBlank())
            a = plugin.getServer().getIp();
        return a == null || a.isBlank() ? null : a.trim();
    }

    /** URL del pack servido (cambia con cada versión para que el cliente baje la nueva), o null. */
    private String hostedUrl() {
        String addr = hostAddress();
        if (http == null || hosted == null || addr == null)
            return null;
        return "http://" + addr + ":" + plugin.getConfig().getInt("resource_pack.host.port", 8163) + "/" + hostedSha1
                + ".zip";
    }

    /**
     * Después de armar el pack (al arrancar o con /gdx pack): si el plugin lo sirve, pasa a
     * servir el nuevo y se lo manda a los que están conectados si cambió. Se puede llamar desde
     * cualquier hilo.
     */
    public void publish(Result r) throws IOException {
        if (http == null)
            return;
        File f = r.merged() != null ? r.merged() : r.pack();
        String sha = r.merged() != null ? r.mergedSha1() : r.packSha1();
        boolean changed = !sha.equals(hostedSha1);
        hosted = Files.readAllBytes(f.toPath());
        hostedSha1 = sha;
        if (changed)
            plugin.getServer().getScheduler().runTask(plugin, () -> {
                for (Player p : plugin.getServer().getOnlinePlayers())
                    sendTo(p);
            });
    }

    /** ¿El plugin está sirviendo el pack? (para los mensajes de /gdx pack) */
    public String hostingUrl() {
        return hostedUrl();
    }

    /** ¿Los menús usan el fondo del resource pack? */
    public boolean customGui() {
        return plugin.getConfig().getBoolean("resource_pack.custom_gui", false);
    }

    /** ¿Se usan los sonidos del resource pack en vez de los de Minecraft? */
    public boolean customSounds() {
        return plugin.getConfig().getBoolean("resource_pack.custom_sounds", false);
    }

    /**
     * Resultado de {@link #build()}. merged = null si no hay pack de ModelEngine ni otros packs.
     * extras = los packs de otros plugins que se juntaron; missing = los de la config que no están.
     */
    public record Result(File pack, String packSha1, File merged, String mergedSha1, List<String> extras,
            List<String> missing) {
    }

    /** Genera los zips. Hace E/S de disco: llamar fuera del hilo principal. */
    public Result build() throws IOException {
        Map<String, byte[]> ours = bundled();
        File dir = plugin.getDataFolder();
        File pack = new File(dir, PACK_ZIP);
        writeZip(pack, ours);

        // Otros packs (p. ej. el de DT3 Tools): primero ellos, encima ModelEngine y GamblingDex,
        // combinando los archivos que comparten
        List<String> extras = new ArrayList<>(), missing = new ArrayList<>();
        Map<String, byte[]> all = new TreeMap<>();
        File server = plugin.getDataFolder().getParentFile().getParentFile();
        for (String path : plugin.getConfig().getStringList("resource_pack.extra_packs")) {
            if (path == null || path.isBlank())
                continue;
            File f = new File(path);
            if (!f.isAbsolute())
                f = new File(server, path);
            Map<String, byte[]> files = readPack(f);
            if (files.isEmpty()) {
                missing.add(path);
                continue;
            }
            extras.add(path);
            PackMerger.mergeInto(all, files);
        }
        Map<String, byte[]> me = modelEnginePack();
        PackMerger.mergeInto(all, me);
        File merged = null;
        if (!me.isEmpty() || !extras.isEmpty()) {
            PackMerger.mergeInto(all, ours);
            // pack.mcmeta y pack.png de GamblingDex (cubren más versiones)
            all.put("pack.mcmeta", ours.get("pack.mcmeta"));
            if (ours.containsKey("pack.png"))
                all.put("pack.png", ours.get("pack.png"));
            merged = new File(dir, MERGED_ZIP);
            writeZip(merged, all);
        }
        return new Result(pack, sha1(pack), merged, merged == null ? null : sha1(merged), extras, missing);
    }

    /** Lee un pack (carpeta o .zip). Si el zip tiene todo dentro de una carpeta, la quita. Vacío si no existe. */
    private static Map<String, byte[]> readPack(File f) throws IOException {
        Map<String, byte[]> out = new TreeMap<>();
        if (f.isDirectory()) {
            Path root = f.toPath();
            try (var walk = Files.walk(root)) {
                for (Path p : (Iterable<Path>) walk::iterator)
                    if (Files.isRegularFile(p))
                        out.put(root.relativize(p).toString().replace(File.separatorChar, '/'), Files.readAllBytes(p));
            }
        } else if (f.isFile() && f.getName().toLowerCase(Locale.ROOT).endsWith(".zip")) {
            try (ZipFile zip = new ZipFile(f)) {
                for (ZipEntry e : Collections.list(zip.entries()))
                    if (!e.isDirectory())
                        try (InputStream in = zip.getInputStream(e)) {
                            out.put(e.getName(), in.readAllBytes());
                        }
            }
        }
        // Zip con una carpeta adentro (MiPack/pack.mcmeta, MiPack/assets/...): se sube un nivel
        if (!out.containsKey("pack.mcmeta")) {
            String prefix = null;
            for (String k : out.keySet())
                if (k.endsWith("/pack.mcmeta") && k.indexOf('/') == k.length() - "/pack.mcmeta".length())
                    prefix = k.substring(0, k.length() - "pack.mcmeta".length());
            if (prefix != null) {
                Map<String, byte[]> flat = new TreeMap<>();
                for (Map.Entry<String, byte[]> e : out.entrySet())
                    if (e.getKey().startsWith(prefix))
                        flat.put(e.getKey().substring(prefix.length()), e.getValue());
                return flat;
            }
        }
        return out;
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
        // Fichas que ya tenía: con (o sin) el modelo 3D según resource_pack.custom_chips
        var tm = plugin.getTokenManager();
        if (tm != null) {
            tm.refreshChipModels(event.getPlayer().getInventory());
            tm.refreshChipModels(event.getPlayer().getEnderChest());
        }
        Player p = event.getPlayer();
        // Un poco después de entrar (algunos clientes ignoran el pack si llega en el mismo tick).
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            if (p.isOnline())
                sendTo(p);
        }, 20L);
    }

    /** Le manda el pack al jugador: el que sirve el plugin o el de resource_pack.send.url. */
    private void sendTo(Player p) {
        String url = hostedUrl();
        byte[] hash;
        if (url != null) {
            hash = hex(hostedSha1);
        } else {
            if (!plugin.getConfig().getBoolean("resource_pack.send.enabled", false))
                return;
            url = plugin.getConfig().getString("resource_pack.send.url", "");
            if (url == null || url.isBlank())
                return;
            hash = hex(plugin.getConfig().getString("resource_pack.send.sha1", ""));
        }
        String prompt = plugin.color(plugin.getConfig().getString("resource_pack.send.prompt",
                "&6&lDescarga el pack de texturas del casino\n&7Mesas 3D, cartas, fichas, menús y sonidos."));
        boolean required = plugin.getConfig().getBoolean("resource_pack.send.required", false);
        p.setResourcePack(url, hash, prompt, required);
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
