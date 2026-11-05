package org.potatodesigns.potatopedestals;

import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/** Keeps messages.yml aligned with the message keys currently used by the plugin. */
final class MessageCatalog {
    private final JavaPlugin plugin;
    private final MiniMessage mini = MiniMessage.miniMessage();
    private YamlConfiguration messages = new YamlConfiguration();

    MessageCatalog(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    void synchronize() {
        File file = new File(plugin.getDataFolder(), "messages.yml");
        YamlConfiguration defaults;
        YamlConfiguration user = new YamlConfiguration();
        try {
            defaults = loadEmbeddedDefaults();
            user.load(file);
        } catch (IOException | InvalidConfigurationException exception) {
            log(true, "Could not read messages.yml; leaving the file untouched: " + exception.getMessage());
            return;
        }

        int added = 0;
        int removed = 0;
        int repaired = 0;
        for (String key : new ArrayList<>(user.getKeys(false))) {
            if (!defaults.contains(key)) {
                user.set(key, null);
                removed++;
            }
        }
        for (String key : defaults.getKeys(false)) {
            if (!user.contains(key)) {
                user.set(key, defaults.get(key));
                added++;
            } else if (!(user.get(key) instanceof String)) {
                user.set(key, defaults.get(key));
                repaired++;
            }
            copyComments(user, defaults, key);
        }

        if (added + removed + repaired > 0) {
            try {
                user.save(file);
                log(false, "messages.yml synchronized: " + added + " added, " + removed + " obsolete removed, " + repaired + " invalid entries restored.");
            } catch (IOException exception) {
                log(true, "Could not save synchronized messages.yml: " + exception.getMessage());
            }
        } else {
            log(false, "messages.yml checked: " + defaults.getKeys(false).size() + " active message keys.");
        }
        messages = user;
    }

    String get(String key) {
        return messages.getString(key, "<red>Missing message: " + key + "</red>");
    }

    private YamlConfiguration loadEmbeddedDefaults() throws IOException, InvalidConfigurationException {
        InputStream stream = plugin.getResource("messages.yml");
        if (stream == null) throw new IOException("embedded messages.yml is missing");
        try (InputStreamReader reader = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
            YamlConfiguration defaults = new YamlConfiguration();
            defaults.load(reader);
            return defaults;
        }
    }

    private void copyComments(ConfigurationSection user, ConfigurationSection defaults, String key) {
        List<String> comments = defaults.getComments(key);
        if (user.getComments(key).isEmpty() && !comments.isEmpty()) user.setComments(key, comments);
        List<String> inline = defaults.getInlineComments(key);
        if (user.getInlineComments(key).isEmpty() && !inline.isEmpty()) user.setInlineComments(key, inline);
    }

    private void log(boolean warning, String message) {
        String prefix = plugin.getConfig().getString("prefix", "<gradient:#ffd700:#b8860b>[P-Pedestals]</gradient>");
        var component = mini.deserialize(prefix + " " + message);
        if (warning) plugin.getComponentLogger().warn(component);
        else plugin.getComponentLogger().info(component);
    }
}
