package com.gamblingdex.pack;

import com.google.gson.*;

import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * Junta varios resource packs en uno. Si dos packs traen el mismo archivo, el de encima
 * (el que se junta después) gana, menos en los que se pueden combinar:
 * <ul>
 * <li>Modelos de ítem ({@code assets/x/models/item/*.json}): se juntan los "overrides"
 * (custom_model_data de cada plugin), ordenados como pide Minecraft.</li>
 * <li>Ítems 1.21.4+ ({@code assets/x/items/*.json}): se juntan las entradas del
 * range_dispatch de custom_model_data.</li>
 * <li>Fuentes, atlas, sonidos e idiomas: se juntan sus listas o claves.</li>
 * </ul>
 */
public final class PackMerger {

    private PackMerger() {
    }

    /** Pone los archivos de {@code top} encima de {@code base}, combinando los que se puedan. */
    public static void mergeInto(Map<String, byte[]> base, Map<String, byte[]> top) {
        for (Map.Entry<String, byte[]> e : top.entrySet()) {
            String path = e.getKey();
            byte[] under = base.get(path);
            if (under == null || Arrays.equals(under, e.getValue()) || !path.endsWith(".json")) {
                base.put(path, e.getValue());
                continue;
            }
            byte[] merged = null;
            try {
                merged = mergeJson(path, under, e.getValue());
            } catch (RuntimeException ignored) {
                // JSON raro: gana el de encima
            }
            base.put(path, merged != null ? merged : e.getValue());
        }
    }

    private static byte[] mergeJson(String path, byte[] underBytes, byte[] topBytes) {
        JsonElement u = JsonParser.parseString(new String(underBytes, StandardCharsets.UTF_8));
        JsonElement t = JsonParser.parseString(new String(topBytes, StandardCharsets.UTF_8));
        if (!u.isJsonObject() || !t.isJsonObject())
            return null;
        JsonObject under = u.getAsJsonObject(), top = t.getAsJsonObject();
        JsonObject out;
        if (path.contains("/models/item/") || path.contains("/models/block/"))
            out = mergeOverrides(under, top);
        else if (path.matches("assets/[^/]+/items/.+"))
            out = mergeItemDefinition(under, top);
        else if (path.matches("assets/[^/]+/font/.+"))
            out = concatArray(under, top, "providers");
        else if (path.matches("assets/[^/]+/atlases/.+"))
            out = concatArray(under, top, "sources");
        else if (path.matches("assets/[^/]+/sounds\\.json") || path.matches("assets/[^/]+/lang/.+"))
            out = mergeKeys(under, top);
        else
            return null;
        return out == null ? null : new GsonBuilder().disableHtmlEscaping().create().toJson(out)
                .getBytes(StandardCharsets.UTF_8);
    }

    /** Modelo de ítem: el de encima, con los overrides de los dos (sin repetir), ordenados. */
    private static JsonObject mergeOverrides(JsonObject under, JsonObject top) {
        JsonArray a = under.has("overrides") ? under.getAsJsonArray("overrides") : new JsonArray();
        JsonArray b = top.has("overrides") ? top.getAsJsonArray("overrides") : new JsonArray();
        if (a.isEmpty())
            return null; // nada que conservar del de abajo
        JsonObject out = top.deepCopy();
        // Si el de encima no trae textura/padre propios (solo overrides), se usa la base del de abajo
        if (!top.has("parent") && under.has("parent"))
            out.add("parent", under.get("parent"));
        if (!top.has("textures") && under.has("textures"))
            out.add("textures", under.get("textures"));
        Map<String, JsonElement> byPredicate = new LinkedHashMap<>();
        for (JsonElement el : a)
            byPredicate.put(predicateKey(el), el);
        for (JsonElement el : b)
            byPredicate.put(predicateKey(el), el); // el de encima gana si es el mismo predicado
        List<JsonElement> all = new ArrayList<>(byPredicate.values());
        // Minecraft usa el ÚLTIMO override que cumple: orden de menor a mayor custom_model_data
        all.sort(Comparator.comparingDouble(PackMerger::cmdOf));
        JsonArray merged = new JsonArray();
        all.forEach(merged::add);
        out.add("overrides", merged);
        return out;
    }

    private static String predicateKey(JsonElement el) {
        return el.isJsonObject() && el.getAsJsonObject().has("predicate")
                ? el.getAsJsonObject().get("predicate").toString()
                : el.toString();
    }

    private static double cmdOf(JsonElement el) {
        try {
            JsonObject p = el.getAsJsonObject().getAsJsonObject("predicate");
            return p.has("custom_model_data") ? p.get("custom_model_data").getAsDouble() : -1;
        } catch (RuntimeException e) {
            return -1;
        }
    }

    /** Ítem 1.21.4+: junta las entradas del range_dispatch por custom_model_data. */
    private static JsonObject mergeItemDefinition(JsonObject under, JsonObject top) {
        JsonObject um = under.has("model") && under.get("model").isJsonObject() ? under.getAsJsonObject("model") : null;
        JsonObject tm = top.has("model") && top.get("model").isJsonObject() ? top.getAsJsonObject("model") : null;
        if (!isCmdDispatch(um))
            return null;
        if (!isCmdDispatch(tm))
            return under.deepCopy(); // el de encima no usa custom_model_data: se queda el que sí
        JsonObject out = top.deepCopy();
        JsonObject model = out.getAsJsonObject("model");
        Map<Double, JsonElement> byThreshold = new TreeMap<>();
        for (JsonElement el : um.getAsJsonArray("entries"))
            byThreshold.put(threshold(el), el);
        for (JsonElement el : tm.getAsJsonArray("entries"))
            byThreshold.put(threshold(el), el);
        JsonArray entries = new JsonArray();
        byThreshold.values().forEach(entries::add);
        model.add("entries", entries);
        return out;
    }

    private static boolean isCmdDispatch(JsonObject m) {
        return m != null && m.has("type") && m.get("type").getAsString().endsWith("range_dispatch")
                && m.has("property") && m.get("property").getAsString().endsWith("custom_model_data")
                && m.has("entries") && m.get("entries").isJsonArray()
                && (!m.has("index") || m.get("index").getAsInt() == 0);
    }

    private static double threshold(JsonElement el) {
        try {
            return el.getAsJsonObject().get("threshold").getAsDouble();
        } catch (RuntimeException e) {
            return Double.MAX_VALUE;
        }
    }

    private static JsonObject concatArray(JsonObject under, JsonObject top, String key) {
        if (!under.has(key) || !top.has(key) || !under.get(key).isJsonArray() || !top.get(key).isJsonArray())
            return null;
        JsonObject out = top.deepCopy();
        JsonArray arr = new JsonArray();
        Set<String> seen = new HashSet<>();
        for (JsonElement el : under.getAsJsonArray(key))
            if (seen.add(el.toString()))
                arr.add(el);
        for (JsonElement el : top.getAsJsonArray(key))
            if (seen.add(el.toString()))
                arr.add(el);
        out.add(key, arr);
        return out;
    }

    private static JsonObject mergeKeys(JsonObject under, JsonObject top) {
        JsonObject out = under.deepCopy();
        for (Map.Entry<String, JsonElement> e : top.entrySet())
            out.add(e.getKey(), e.getValue());
        return out;
    }
}
