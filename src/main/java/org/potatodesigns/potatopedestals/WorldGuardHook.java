package org.potatodesigns.potatopedestals;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.lang.reflect.Array;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.function.Consumer;

/** Optional, reflection-only WorldGuard integration. */
final class WorldGuardHook {
    private final JavaPlugin plugin;
    private final Consumer<String> warning;
    private boolean warned;

    WorldGuardHook(JavaPlugin plugin, Consumer<String> warning) {
        this.plugin = plugin;
        this.warning = warning;
    }

    boolean allows(Player player, Location location, boolean use) {
        if (!plugin.getConfig().getBoolean("integrations.worldguard.enabled", true)) return true;
        var worldGuardPlugin = Bukkit.getPluginManager().getPlugin("WorldGuard");
        if (worldGuardPlugin == null || !worldGuardPlugin.isEnabled()) return true;
        if (use && !plugin.getConfig().getBoolean("integrations.worldguard.check-use", true)) return true;
        if (!use && !plugin.getConfig().getBoolean("integrations.worldguard.check-break", true)) return true;

        try {
            ClassLoader loader = worldGuardPlugin.getClass().getClassLoader();
            Class<?> worldGuardClass = Class.forName("com.sk89q.worldguard.WorldGuard", true, loader);
            Object worldGuard = worldGuardClass.getMethod("getInstance").invoke(null);
            Object platform = worldGuardClass.getMethod("getPlatform").invoke(worldGuard);

            Class<?> localPlayerClass = Class.forName("com.sk89q.worldguard.LocalPlayer", true, loader);
            Class<?> pluginApi = Class.forName("com.sk89q.worldguard.bukkit.WorldGuardPlugin", true, loader);
            Object worldGuardBukkit = pluginApi.getMethod("inst").invoke(null);
            Object localPlayer = invokeOneArg(pluginApi, worldGuardBukkit, "wrapPlayer", player);

            Class<?> adapter = Class.forName("com.sk89q.worldedit.bukkit.BukkitAdapter", true, loader);
            Object worldEditLocation = invokeStaticOneArg(adapter, "adapt", location);
            Object worldEditWorld = invokeStaticOneArg(adapter, "adapt", player.getWorld());

            Object sessionManager = platform.getClass().getMethod("getSessionManager").invoke(platform);
            Class<?> sessionManagerApi = Class.forName("com.sk89q.worldguard.session.SessionManager", true, loader);
            Class<?> worldEditWorldClass = Class.forName("com.sk89q.worldedit.world.World", true, loader);
            Method bypassMethod = sessionManagerApi.getMethod("hasBypass", localPlayerClass, worldEditWorldClass);
            if (Boolean.TRUE.equals(bypassMethod.invoke(sessionManager, localPlayer, worldEditWorld))) return true;

            Object container = platform.getClass().getMethod("getRegionContainer").invoke(platform);
            Class<?> containerApi = Class.forName("com.sk89q.worldguard.protection.regions.RegionContainer", true, loader);
            Object query = containerApi.getMethod("createQuery").invoke(container);
            Class<?> flagsClass = Class.forName("com.sk89q.worldguard.protection.flags.Flags", true, loader);
            Class<?> stateFlagClass = Class.forName("com.sk89q.worldguard.protection.flags.StateFlag", true, loader);
            String[] flagNames = use ? new String[]{"USE", "INTERACT"} : new String[]{"BUILD", "BLOCK_BREAK"};
            Object flags = Array.newInstance(stateFlagClass, flagNames.length);
            for (int index = 0; index < flagNames.length; index++)
                Array.set(flags, index, flagsClass.getField(flagNames[index]).get(null));

            Class<?> queryApi = Class.forName("com.sk89q.worldguard.protection.regions.RegionQuery", true, loader);
            Class<?> worldEditLocationClass = Class.forName("com.sk89q.worldedit.util.Location", true, loader);
            Method testState = queryApi.getMethod("testState", worldEditLocationClass, localPlayerClass, flags.getClass());
            return Boolean.TRUE.equals(testState.invoke(query, worldEditLocation, localPlayer, flags));
        } catch (ReflectiveOperationException | LinkageError exception) {
            if (!warned) {
                warned = true;
                warning.accept("WorldGuard API check failed; pedestal use and break are denied to avoid bypassing region protection: " + exception.getMessage());
            }
            return false;
        }
    }

    private Object invokeStaticOneArg(Class<?> type, String name, Object argument) throws ReflectiveOperationException {
        for (Method method : type.getMethods()) {
            if (Modifier.isStatic(method.getModifiers()) && method.getName().equals(name)
                    && method.getParameterCount() == 1 && method.getParameterTypes()[0].isInstance(argument))
                return method.invoke(null, argument);
        }
        throw new NoSuchMethodException(type.getName() + "." + name);
    }

    private Object invokeOneArg(Class<?> type, Object receiver, String name, Object argument) throws ReflectiveOperationException {
        for (Method method : type.getMethods()) {
            if (!Modifier.isStatic(method.getModifiers()) && method.getName().equals(name)
                    && method.getParameterCount() == 1 && method.getParameterTypes()[0].isInstance(argument))
                return method.invoke(receiver, argument);
        }
        throw new NoSuchMethodException(type.getName() + "." + name);
    }
}
