package org.potatodesigns.potatopedestals;

import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Upgrades user configuration while retaining user values and YAML comments. */
final class ConfigMigration {
    static final int CURRENT_VERSION = 16;

    private final JavaPlugin plugin;
    private final MiniMessage mini = MiniMessage.miniMessage();

    ConfigMigration(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    void migrate() {
        File file = new File(plugin.getDataFolder(), "config.yml");
        YamlConfiguration user = YamlConfiguration.loadConfiguration(file);
        YamlConfiguration defaults = loadDefaults();
        int version = user.getInt("config-version", 0);

        if (version > CURRENT_VERSION) {
            log(true, "Config version " + version + " is newer than supported version " + CURRENT_VERSION + "; leaving its schema intact.");
            mergeDefaults(user, defaults);
            save(file, user);
            return;
        }

        boolean changed = false;
        if (version < 2) {
            changed |= migrateLegacyMaterialList(user);
            changed = true;
            version = 2;
        }
        if (version < 3) {
            // Version 3 adds pedestal break and crafting controls.
            version = 3;
            changed = true;
        }
        if (version < 4) {
            // Version 4 adds optional WorldGuard region checks.
            version = 4;
            changed = true;
        }
        if (version < 5) {
            // Version 5 adds ItemsAdder shelves with three GUI slots and display positions.
            version = 5;
            changed = true;
        }
        if (version < 6) {
            // Version 6 improves the default shelf scale, height, and yaw-relative offsets.
            ConfigurationSection pedestals = user.getConfigurationSection("pedestals");
            if (pedestals != null) for (String id : pedestals.getKeys(false)) {
                String path = "pedestals." + id;
                if (!user.getString(path + ".type", "").equalsIgnoreCase("shelf")) continue;
                List<String> offsets = user.getStringList(path + ".display-offsets");
                if (offsets.equals(List.of("0.25,1.05,0.5", "1.0,1.05,0.5", "1.75,1.05,0.5")))
                    user.set(path + ".display-offsets", List.of("0.5,0.5,0.5", "1.0,0.5,0.5", "1.5,0.5,0.5"));
                if (user.isSet(path + ".display-scale") && Math.abs(user.getDouble(path + ".display-scale") - 0.65) < 0.0001)
                    user.set(path + ".display-scale", 1.4);
            }
            version = 6;
            changed = true;
        }
        if (version < 7) {
            // Version 7 spaces the three default shelf displays across its full two-block length.
            ConfigurationSection pedestals = user.getConfigurationSection("pedestals");
            if (pedestals != null) for (String id : pedestals.getKeys(false)) {
                String path = "pedestals." + id;
                if (!user.getString(path + ".type", "").equalsIgnoreCase("shelf")) continue;
                List<String> offsets = user.getStringList(path + ".display-offsets");
                if (offsets.equals(List.of("0.5,0.5,0.5", "1.0,0.5,0.5", "1.5,0.5,0.5")))
                    user.set(path + ".display-offsets", List.of("0.25,0.5,0.5", "1.0,0.5,0.5", "1.75,0.5,0.5"));
            }
            version = 7;
            changed = true;
        }
        if (version < 8) {
            // Version 8 adds per-shelf vertical offset and inter-display spacing options.
            version = 8;
            changed = true;
        }
        if (version < 9) {
            // Version 9 adds shelf center offset, shift-click insertion, and richer GUI indicators.
            version = 9;
            changed = true;
        }
        if (version < 10) {
            // Version 10 bakes the tested shelf placement into its defaults and zeros the tuning offsets.
            ConfigurationSection pedestals = user.getConfigurationSection("pedestals");
            if (pedestals != null) for (String id : pedestals.getKeys(false)) {
                String path = "pedestals." + id;
                if (!user.getString(path + ".type", "").equalsIgnoreCase("shelf")) continue;
                List<String> offsets = user.getStringList(path + ".display-offsets");
                boolean defaultPositions = offsets.equals(List.of("0.25,0.5,0.5", "1.0,0.5,0.5", "1.75,0.5,0.5"));
                boolean testedSettings = Math.abs(user.getDouble(path + ".display-height-offset") + 0.51) < 0.0001
                        && Math.abs(user.getDouble(path + ".display-spacing") - 0.75) < 0.0001
                        && Math.abs(user.getDouble(path + ".display-center-offset") + 0.5) < 0.0001;
                if (defaultPositions && testedSettings) {
                    user.set(path + ".display-offsets", List.of("-0.25,-0.01,0.5", "0.5,-0.01,0.5", "1.25,-0.01,0.5"));
                    user.set(path + ".display-height-offset", 0.0);
                    user.set(path + ".display-spacing", 0.0);
                    user.set(path + ".display-center-offset", 0.0);
                }
            }
            version = 10;
            changed = true;
        }
        if (version < 11) {
            // Version 11 adds one-to-four display counts and front/back shelf positioning.
            version = 11;
            changed = true;
        }
        if (version < 12) {
            // Version 12 bakes the tested -0.15 depth and -0.1 spacing adjustments into shelf defaults.
            ConfigurationSection pedestals = user.getConfigurationSection("pedestals");
            if (pedestals != null) for (String id : pedestals.getKeys(false)) {
                String path = "pedestals." + id;
                if (!user.getString(path + ".type", "").equalsIgnoreCase("shelf")) continue;
                if (Math.abs(user.getDouble(path + ".display-spacing") + 0.1) < 0.0001)
                    user.set(path + ".display-spacing", 0.0);

                if (Math.abs(user.getDouble(path + ".display-depth-offset") + 0.15) < 0.0001) {
                    List<String> offsets = user.getStringList(path + ".display-offsets");
                    boolean defaultDepths = offsets.stream().allMatch(offset -> {
                        String[] xyz = offset.split(",");
                        if (xyz.length != 3) return false;
                        try { return Math.abs(Double.parseDouble(xyz[2].trim()) - 0.5) < 0.0001; }
                        catch (NumberFormatException exception) { return false; }
                    });
                    if (offsets.isEmpty() || defaultDepths) {
                        if (!offsets.isEmpty()) {
                            List<String> adjusted = new ArrayList<>();
                            for (String offset : offsets) {
                                String[] xyz = offset.split(",");
                                adjusted.add(xyz[0].trim() + "," + xyz[1].trim() + ",0.35");
                            }
                            user.set(path + ".display-offsets", adjusted);
                        }
                        user.set(path + ".display-depth-offset", 0.0);
                    }
                }
            }
            version = 12;
            changed = true;
        }
        if (version < 13) {
            // Version 13 moves allowed item IDs into a mode/items section while preserving legacy whitelist behavior.
            ConfigurationSection pedestals = user.getConfigurationSection("pedestals");
            if (pedestals != null) for (String id : pedestals.getKeys(false)) {
                String path = "pedestals." + id + ".allowed-items";
                Object configured = user.get(path);
                if (configured instanceof List<?> list) {
                    List<String> items = list.stream().map(String::valueOf).toList();
                    user.set(path, null);
                    ConfigurationSection allowedItems = user.createSection(path);
                    allowedItems.set("mode", "whitelist");
                    allowedItems.set("items", items);
                }
            }
            version = 13;
            changed = true;
        }
        if (version < 14) {
            // Version 14 applies display-height-offset to every pedestal type.
            version = 14;
            changed = true;
        }
        if (version < 15) {
            // Version 15 adds single-slot GUIs for block and furniture pedestal types.
            version = 15;
            changed = true;
        }
        if (version < 16) {
            // Version 16 adds per-pedestal display scale, rotation controls, and inventory removal buttons.
            user.set("behavior.allow-break-empty", null);
            user.set("behavior.drop-item-on-break", null);
            user.set("rotation-modes", null);
            version = 16;
            changed = true;
        }
        if (changed) {
            user.set("config-version", CURRENT_VERSION);
            log(false, "Migrated config.yml to version " + CURRENT_VERSION + ".");
        }
        changed |= mergeDefaults(user, defaults);
        if (changed) save(file, user);
    }

    private boolean migrateLegacyMaterialList(YamlConfiguration user) {
        Object oldValue = user.get("pedestals.materials");
        if (!(oldValue instanceof List<?>)) return false;

        List<String> materials = user.getStringList("pedestals.materials");
        user.set("pedestals", null);
        ConfigurationSection pedestals = user.createSection("pedestals");
        Set<String> ids = new HashSet<>();
        for (String materialName : materials) {
            Material material = Material.matchMaterial(materialName);
            if (material == null || !material.isBlock()) {
                log(true, "Skipping invalid legacy pedestal material '" + materialName + "'.");
                continue;
            }
            String baseId = material.name().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_-]", "_");
            String id = baseId;
            int suffix = 2;
            while (!ids.add(id)) id = baseId + "_" + suffix++;
            ConfigurationSection pedestal = pedestals.createSection(id);
            pedestal.set("provider", "vanilla");
            pedestal.set("type", "block");
            pedestal.set("material", material.name());
        }
        user.setComments("pedestals", List.of("Pedestal IDs are used by /ppedestals give."));
        return true;
    }

    private boolean mergeDefaults(ConfigurationSection user, ConfigurationSection defaults) {
        boolean changed = false;
        for (String key : defaults.getKeys(false)) {
            if (defaults.isConfigurationSection(key)) {
                if (!user.isConfigurationSection(key)) {
                    if (user.contains(key)) {
                        log(true, "Cannot merge defaults at '" + key + "' because its value is not a section.");
                        continue;
                    }
                    user.createSection(key);
                    changed = true;
                }
                changed |= mergeDefaults(user.getConfigurationSection(key), defaults.getConfigurationSection(key));
            } else if (!user.contains(key)) {
                user.set(key, defaults.get(key));
                changed = true;
            }

            if (user.getComments(key).isEmpty() && !defaults.getComments(key).isEmpty()) {
                user.setComments(key, defaults.getComments(key));
                changed = true;
            }
            if (user.getInlineComments(key).isEmpty() && !defaults.getInlineComments(key).isEmpty()) {
                user.setInlineComments(key, defaults.getInlineComments(key));
                changed = true;
            }
        }
        return changed;
    }

    private YamlConfiguration loadDefaults() {
        InputStream stream = plugin.getResource("config.yml");
        if (stream == null) throw new IllegalStateException("Embedded config.yml is missing");
        try (InputStreamReader reader = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
            return YamlConfiguration.loadConfiguration(reader);
        } catch (IOException exception) {
            throw new IllegalStateException("Could not read embedded config.yml", exception);
        }
    }

    private void save(File file, YamlConfiguration config) {
        try {
            config.save(file);
        } catch (IOException exception) {
            log(true, "Could not save migrated config.yml: " + exception.getMessage());
        }
    }

    private void log(boolean warning, String message) {
        String prefix = YamlConfiguration.loadConfiguration(new File(plugin.getDataFolder(), "config.yml"))
                .getString("prefix", "<gradient:#ffd700:#b8860b>[P-Pedestals]</gradient>");
        var component = mini.deserialize(prefix + " " + message);
        if (warning) plugin.getComponentLogger().warn(component);
        else plugin.getComponentLogger().info(component);
    }
}
