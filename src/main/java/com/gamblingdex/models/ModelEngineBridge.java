package com.gamblingdex.models;

import org.bukkit.Bukkit;
import org.bukkit.Location;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.logging.Logger;

/**
 * Acceso a ModelEngine R4 por reflexión: así el plugin no depende de su jar para
 * compilar ni para arrancar (ModelEngine es opcional). Todo lo que falle se
 * registra una sola vez y se trata como "no disponible".
 *
 * <p>
 * Lo usado de la API: {@code new Dummy<>()}, {@code Dummy#setLocation},
 * {@code ModelEngineAPI.createModeledEntity(BaseEntity)},
 * {@code ModelEngineAPI.createActiveModel(String)},
 * {@code ModeledEntity#addModel(ActiveModel, boolean)} /
 * {@code #destroy()} y {@code ActiveModel#getAnimationHandler()} con
 * {@code playAnimation(String, double, double, double, boolean)} /
 * {@code stopAnimation(String)}.
 */
public final class ModelEngineBridge {

    private static final String API = "com.ticxo.modelengine.api.ModelEngineAPI";
    private static final String DUMMY = "com.ticxo.modelengine.api.entity.Dummy";

    private final Logger log;
    private boolean warned;

    public ModelEngineBridge(Logger log) {
        this.log = log;
    }

    /** ¿Está ModelEngine instalado y activo? */
    public boolean present() {
        var p = Bukkit.getPluginManager().getPlugin("ModelEngine");
        if (p == null || !p.isEnabled())
            return false;
        try {
            Class.forName(API, false, p.getClass().getClassLoader());
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    /** ¿Está cargado el modelo (blueprint) con ese id? */
    public boolean hasModel(String id) {
        try {
            Object bp = callOn(api(), null, "getBlueprint", id);
            return bp != null;
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * Crea un modelo fijo (sin entidad de Minecraft detrás) en esa ubicación.
     * Devuelve null si ModelEngine no lo pudo crear (p. ej. aún cargando modelos).
     */
    public Handle spawn(String modelId, Location loc) {
        try {
            Class<?> dummyCls = Class.forName(DUMMY, true, apiLoader());
            Constructor<?> ctor = dummyCls.getConstructor();
            Object dummy = ctor.newInstance();
            call(dummy, "setLocation", loc);
            tryCall(dummy, "setDetectingPlayers", true);
            rotate(dummy, loc.getYaw());
            Object modeled = callOn(api(), null, "createModeledEntity", dummy);
            Object active = callOn(api(), null, "createActiveModel", modelId);
            if (modeled == null || active == null)
                return null;
            call(modeled, "addModel", active, true);
            tryCall(modeled, "setBaseEntityVisible", false);
            return new Handle(dummy, modeled, active);
        } catch (Throwable t) {
            warnOnce("No se pudo crear el modelo '" + modelId + "' con ModelEngine: " + root(t));
            return null;
        }
    }

    public void remove(Handle h) {
        if (h == null)
            return;
        tryCall(h.modeled, "destroy");
        tryCall(h.dummy, "setRemoved", true);
    }

    /** Reproduce una animación (force = reiniciarla aunque ya esté sonando). */
    public void play(Handle h, String anim, double lerpIn, double lerpOut, boolean force) {
        if (h == null)
            return;
        try {
            Object handler = call(h.active, "getAnimationHandler");
            if (force)
                tryCall(handler, "forceStopAnimation", anim);
            call(handler, "playAnimation", anim, lerpIn, lerpOut, 1.0, force);
        } catch (Throwable t) {
            warnOnce("No se pudo reproducir la animación '" + anim + "': " + root(t));
        }
    }

    public void stop(Handle h, String anim) {
        if (h == null)
            return;
        try {
            Object handler = call(h.active, "getAnimationHandler");
            if (!tryCall(handler, "forceStopAnimation", anim))
                tryCall(handler, "stopAnimation", anim);
        } catch (Throwable ignored) {
        }
    }

    public boolean isRemoved(Handle h) {
        if (h == null)
            return true;
        try {
            Object r = call(h.modeled, "isDestroyed");
            return r instanceof Boolean b && b;
        } catch (Throwable t) {
            return false;
        }
    }

    /** Lo que ModelEngine devuelve al crear un modelo; para animarlo y borrarlo. */
    public static final class Handle {
        final Object dummy, modeled, active;

        Handle(Object dummy, Object modeled, Object active) {
            this.dummy = dummy;
            this.modeled = modeled;
            this.active = active;
        }

        /** La "entidad base" del modelo (para reconocer los clicks de ModelEngine). */
        public Object base() {
            return dummy;
        }
    }

    // ------------------------------------------------------------------

    private void rotate(Object dummy, float yaw) {
        if (!tryCall(dummy, "setYBodyRot", yaw)) {
            Object rc = null;
            try {
                rc = call(dummy, "getBodyRotationController");
            } catch (Throwable ignored) {
            }
            if (rc != null)
                tryCall(rc, "setYBodyRot", yaw);
        }
        tryCall(dummy, "setYHeadRot", yaw);
    }

    private static ClassLoader apiLoader() {
        var p = Bukkit.getPluginManager().getPlugin("ModelEngine");
        return p == null ? ModelEngineBridge.class.getClassLoader() : p.getClass().getClassLoader();
    }

    private static Class<?> api() throws ClassNotFoundException {
        return Class.forName(API, true, apiLoader());
    }

    private static Object call(Object target, String name, Object... args) throws Exception {
        return callOn(target.getClass(), target, name, args);
    }

    /** Invoca el primer método público con ese nombre cuyos parámetros acepten los argumentos. */
    private static Object callOn(Class<?> cls, Object target, String name, Object... args) throws Exception {
        for (Method m : cls.getMethods()) {
            if (!m.getName().equals(name) || m.getParameterCount() != args.length)
                continue;
            if (!accepts(m.getParameterTypes(), args))
                continue;
            m.setAccessible(true);
            return m.invoke(target, coerce(m.getParameterTypes(), args));
        }
        throw new NoSuchMethodException(cls.getName() + "#" + name + "/" + args.length);
    }

    private static boolean tryCall(Object target, String name, Object... args) {
        if (target == null)
            return false;
        try {
            call(target, name, args);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    private static boolean accepts(Class<?>[] types, Object[] args) {
        for (int i = 0; i < types.length; i++) {
            Class<?> t = wrap(types[i]);
            Object a = args[i];
            if (a == null)
                continue;
            if (t.isInstance(a))
                continue;
            // float/double: se aceptan entre sí
            if ((t == Float.class || t == Double.class) && a instanceof Number)
                continue;
            return false;
        }
        return true;
    }

    /** Ajusta los números al tipo exacto del parámetro (invoke no convierte double → float). */
    private static Object[] coerce(Class<?>[] types, Object[] args) {
        Object[] out = args.clone();
        for (int i = 0; i < types.length; i++) {
            Class<?> t = wrap(types[i]);
            if (out[i] instanceof Number n) {
                if (t == Float.class)
                    out[i] = n.floatValue();
                else if (t == Double.class)
                    out[i] = n.doubleValue();
            }
        }
        return out;
    }

    private static Class<?> wrap(Class<?> c) {
        if (!c.isPrimitive())
            return c;
        if (c == boolean.class)
            return Boolean.class;
        if (c == int.class)
            return Integer.class;
        if (c == long.class)
            return Long.class;
        if (c == float.class)
            return Float.class;
        if (c == double.class)
            return Double.class;
        return c;
    }

    private void warnOnce(String msg) {
        if (warned)
            return;
        warned = true;
        log.warning("[Modelos] " + msg);
    }

    private static String root(Throwable t) {
        while (t.getCause() != null)
            t = t.getCause();
        return t.getClass().getSimpleName() + (t.getMessage() == null ? "" : ": " + t.getMessage());
    }
}
