package org.potatodesigns.potatopedestals;

import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.ItemFrame;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Event;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.inventory.CraftItemEvent;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.PrepareItemCraftEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerInteractAtEntityEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.CraftingInventory;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.Material;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.util.Transformation;
import org.bukkit.util.io.BukkitObjectInputStream;
import org.bukkit.util.io.BukkitObjectOutputStream;
import org.joml.AxisAngle4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import java.util.List;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Map;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Base64;

public final class PotatoPedestals extends JavaPlugin implements Listener, TabCompleter {
    private final MiniMessage mini = MiniMessage.miniMessage();
    private MessageCatalog messageCatalog;
    private NamespacedKey displayKey, baseKey, displayLocationKey;
    private NamespacedKey pedestalItemKey;
    private NamespacedKey shelfDisplayKey, shelfOwnerKey, shelfGuiMarkerKey, guiActionKey;
    private NamespacedKey shelfIndexKey;
    private final Set<UUID> furnitureInteractionsThisTick = new HashSet<>();
    private final Set<UUID> guiRemovingFurniture = new HashSet<>();
    private WorldGuardHook worldGuardHook;

    @Override public void onEnable() {
        saveDefaultConfig();
        saveResource("messages.yml", false);
        new ConfigMigration(this).migrate();
        reloadConfig();
        messageCatalog = new MessageCatalog(this);
        messageCatalog.synchronize();
        worldGuardHook = new WorldGuardHook(this, this::startupWarn);
        displayKey = new NamespacedKey(this, "display");
        baseKey = new NamespacedKey(this, "pedestal_base");
        displayLocationKey = new NamespacedKey(this, "display_location");
        pedestalItemKey = new NamespacedKey(this, "pedestal_id");
        shelfDisplayKey = new NamespacedKey(this, "shelf_display");
        shelfOwnerKey = new NamespacedKey(this, "shelf_owner");
        shelfGuiMarkerKey = new NamespacedKey(this, "shelf_gui_marker");
        guiActionKey = new NamespacedKey(this, "gui_action");
        shelfIndexKey = new NamespacedKey(this, "shelf_index");
        Bukkit.getPluginManager().registerEvents(this, this);
        registerItemsAdderFurnitureHook();
        if (getCommand("ppedestals") != null) getCommand("ppedestals").setTabCompleter(this);
        validateStartupConfiguration();
        startupLog("PotatoPedestals " + getDescription().getVersion() + " enabled.");
    }

    private void validateStartupConfiguration() {
        startupLog("Checking configuration, optional hooks, and pedestal definitions...");
        validateHook("ItemsAdder", "dev.lone.itemsadder.api.CustomBlock");
        validateHook("Nexo", "com.nexomc.nexo.api.NexoBlocks");
        validateHook("CraftEngine", "net.momirealms.craftengine.bukkit.api.CraftEngineBlocks");
        validateHook("WorldGuard", "com.sk89q.worldguard.WorldGuard");

        var pedestals = getConfig().getConfigurationSection("pedestals");
        if (pedestals == null || pedestals.getKeys(false).isEmpty()) {
            startupWarn("No pedestal definitions are configured.");
            return;
        }
        int valid = 0;
        for (String id : pedestals.getKeys(false)) {
            String path = "pedestals." + id;
            String provider = getConfig().getString(path + ".provider", "vanilla").toLowerCase(java.util.Locale.ROOT);
            String type = getConfig().getString(path + ".type", "block").toLowerCase(java.util.Locale.ROOT);
            String customId = getConfig().getString(path + ".id", "");
            boolean entryValid = true;
            if (!List.of("vanilla", "itemsadder", "nexo", "craftengine").contains(provider)) {
                startupWarn("Pedestal '" + id + "' has unsupported provider '" + provider + "'."); entryValid = false;
            }
            if (!List.of("block", "furniture", "shelf").contains(type)) {
                startupWarn("Pedestal '" + id + "' has unsupported type '" + type + "'."); entryValid = false;
            }
            if (provider.equals("vanilla")) {
                String materialName = getConfig().getString(path + ".material", "");
                Material material = Material.matchMaterial(materialName);
                if (material == null || !material.isBlock()) {
                    startupWarn("Pedestal '" + id + "' uses invalid block material '" + materialName + "'."); entryValid = false;
                }
            } else {
                if (customId.isBlank()) { startupWarn("Pedestal '" + id + "' is missing its custom id."); entryValid = false; }
                String hookName = switch (provider) { case "itemsadder" -> "ItemsAdder"; case "nexo" -> "Nexo"; case "craftengine" -> "CraftEngine"; default -> ""; };
                var hook = Bukkit.getPluginManager().getPlugin(hookName);
                if (hook == null || !hook.isEnabled()) {
                    startupWarn("Pedestal '" + id + "' needs " + hookName + "; custom-id recognition and giving require that provider.");
                } else if (!customId.isBlank() && !customIdRegistered(provider, type, customId)) {
                    startupWarn("Custom " + type + " id '" + customId + "' for pedestal '" + id + "' was not found in " + hookName + ".");
                    entryValid = false;
                }
                if (type.equals("shelf")) {
                    if (!provider.equals("itemsadder")) {
                        startupWarn("Shelf pedestal '" + id + "' currently requires provider 'itemsadder'.");
                        entryValid = false;
                    }
                int displayCount = getConfig().getInt(path + ".display-count", 3);
                if (displayCount < 1 || displayCount > 4) {
                    startupWarn("Shelf pedestal '" + id + "' display-count must be between 1 and 4.");
                    entryValid = false;
                }
                }
            }
            String materialName = getConfig().getString(path + ".material", "");
            if (!materialName.isBlank()) {
                Material material = Material.matchMaterial(materialName);
                if (material == null || !material.isBlock()) { startupWarn("Pedestal '" + id + "' has invalid fallback material '" + materialName + "'."); entryValid = false; }
            }
            for (String carrier : getConfig().getStringList(path + ".carrier-materials")) {
                Material material = Material.matchMaterial(carrier);
                if (material == null || !material.isBlock()) { startupWarn("Pedestal '" + id + "' has invalid carrier material '" + carrier + "'."); entryValid = false; }
            }
            String style = getConfig().getString(path + ".tooltip-style", "");
            if (!style.isBlank() && NamespacedKey.fromString(style) == null) { startupWarn("Pedestal '" + id + "' has invalid tooltip-style key '" + style + "'."); entryValid = false; }
            if (entryValid) valid++;
        }
        saveConfig();
        startupLog("Validated " + valid + " of " + pedestals.getKeys(false).size() + " configured pedestal(s).");
    }

    private void validateHook(String name, String apiClass) {
        var plugin = Bukkit.getPluginManager().getPlugin(name);
        if (plugin == null || !plugin.isEnabled()) {
            startupLog(name + " hook: not installed (optional).");
            return;
        }
        try {
            Class.forName(apiClass, false, plugin.getClass().getClassLoader());
            startupLog(name + " hook: enabled; API detected.");
        } catch (ClassNotFoundException | LinkageError exception) {
            startupWarn(name + " is enabled, but its API class is unavailable; custom-id detection is disabled.");
        }
    }

    @SuppressWarnings("unchecked")
    private void registerItemsAdderFurnitureHook() {
        var itemsAdder = Bukkit.getPluginManager().getPlugin("ItemsAdder");
        if (itemsAdder == null || !itemsAdder.isEnabled()) return;
        try {
            Class<?> eventClass = Class.forName("dev.lone.itemsadder.api.Events.FurnitureInteractEvent", true, itemsAdder.getClass().getClassLoader());
            if (!Event.class.isAssignableFrom(eventClass)) throw new IllegalStateException("FurnitureInteractEvent is not a Bukkit event");
            Bukkit.getPluginManager().registerEvent((Class<? extends Event>) eventClass, this, EventPriority.HIGH,
                    (listener, event) -> handleItemsAdderFurnitureEvent(event), this, false);
            Class<?> breakEventClass = Class.forName("dev.lone.itemsadder.api.Events.FurnitureBreakEvent", true, itemsAdder.getClass().getClassLoader());
            if (!Event.class.isAssignableFrom(breakEventClass)) throw new IllegalStateException("FurnitureBreakEvent is not a Bukkit event");
            Bukkit.getPluginManager().registerEvent((Class<? extends Event>) breakEventClass, this, EventPriority.HIGH,
                    (listener, event) -> handleItemsAdderFurnitureBreakEvent(event), this, false);
            startupLog("ItemsAdder furniture interaction hook: registered.");
            startupLog("ItemsAdder furniture break hook: registered.");
        } catch (ReflectiveOperationException | LinkageError | IllegalStateException exception) {
            startupWarn("Could not register ItemsAdder furniture interaction hook: " + exception.getMessage());
        }
    }

    private void handleItemsAdderFurnitureEvent(Event event) {
        try {
            Object action = event.getClass().getMethod("getAction").invoke(event);
            if (action != Action.RIGHT_CLICK_BLOCK && action != Action.RIGHT_CLICK_AIR) return;
            Object namespacedId = event.getClass().getMethod("getNamespacedID").invoke(event);
            Object bukkitEntity = event.getClass().getMethod("getBukkitEntity").invoke(event);
            if (!(namespacedId instanceof String customId) || !(bukkitEntity instanceof Entity entity)) return;
            String pedestalId = itemsAdderFurniturePedestal(customId);
            if (pedestalId == null) return;
            event.getClass().getMethod("setCancelled", boolean.class).invoke(event, true);
            Player player = (Player) event.getClass().getMethod("getPlayer").invoke(event);
            if (!worldGuardHook.allows(player, entity.getLocation(), true)) { send(player, "region-protected"); return; }
            if (getConfig().getString("pedestals." + pedestalId + ".type", "").equalsIgnoreCase("shelf")) {
                if (markFurnitureInteraction(entity)) openShelf(player, entity, pedestalId);
            }
            else handleFurnitureInteractionOnce(player, entity, entity.getLocation().add(0, .75, 0), pedestalId);
        } catch (ReflectiveOperationException | ClassCastException exception) {
            startupWarn("Failed handling ItemsAdder furniture interaction: " + exception.getMessage());
        }
    }

    private void handleItemsAdderFurnitureBreakEvent(Event event) {
        try {
            Object namespacedId = event.getClass().getMethod("getNamespacedID").invoke(event);
            Object bukkitEntity = event.getClass().getMethod("getBukkitEntity").invoke(event);
            if (!(namespacedId instanceof String customId) || !(bukkitEntity instanceof Entity entity)) return;
            String pedestalId = itemsAdderFurniturePedestal(customId);
            if (pedestalId == null) return;
            if (guiRemovingFurniture.contains(entity.getUniqueId())) return;

            event.getClass().getMethod("setCancelled", boolean.class).invoke(event, true);
            Player player = (Player) event.getClass().getMethod("getPlayer").invoke(event);
            if (!worldGuardHook.allows(player, entity.getLocation(), false)) { send(player, "region-protected"); return; }
            send(player, "remove-from-gui");
        } catch (ReflectiveOperationException | ClassCastException exception) {
            startupWarn("Failed handling ItemsAdder furniture break: " + exception.getMessage());
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onFurnitureDamage(EntityDamageByEntityEvent event) {
        if (!(event.getDamager() instanceof Player player)) return;
        Entity hit = event.getEntity();
        // Vanilla frames commonly sit directly above/next to pedestals. Never treat damage
        // to the frame as a click on the nearby furniture pedestal.
        if (hit instanceof ItemFrame) return;
        Entity furnitureEntity = null;
        String pedestalId = null;
        List<Entity> candidates = new ArrayList<>();
        candidates.add(hit);
        candidates.addAll(hit.getNearbyEntities(1.0, 1.0, 1.0));
        for (Entity candidate : candidates) {
            var definitions = getConfig().getConfigurationSection("pedestals");
            if (definitions == null) break;
            for (String id : definitions.getKeys(false)) {
                String path = "pedestals." + id;
                String candidateProvider = getConfig().getString(path + ".provider", "");
                String type = getConfig().getString(path + ".type", "");
                if (!List.of("furniture", "shelf").contains(type.toLowerCase(java.util.Locale.ROOT))) continue;
                String customId = furnitureId(candidate, player, candidateProvider);
                if (matchesCustomId(getConfig().getString(path + ".id", ""), customId)) {
                    pedestalId = id;
                    furnitureEntity = candidate;
                    break;
                }
            }
            if (pedestalId != null) break;
        }
        if (pedestalId == null || furnitureEntity == null) return;
        if (guiRemovingFurniture.contains(furnitureEntity.getUniqueId())) return;
        event.setCancelled(true);
        if (!worldGuardHook.allows(player, furnitureEntity.getLocation(), false)) { send(player, "region-protected"); return; }
        boolean shelf = getConfig().getString("pedestals." + pedestalId + ".type", "").equalsIgnoreCase("shelf");
        if (shelf) {
            List<ItemDisplay> displays = shelfDisplays(furnitureEntity);
            if (displays.isEmpty()) send(player, "remove-from-gui");
            else { displays.forEach(this::rotateHorizontal); send(player, "rotated"); }
        } else {
            ItemDisplay display = findDisplayAtPedestal(furnitureEntity.getLocation().add(0, .75, 0), pedestalId);
            if (display == null) send(player, "remove-from-gui");
            else { rotateHorizontal(display); send(player, "rotated"); }
        }
    }

    private String itemsAdderFurniturePedestal(String customId) {
        var pedestals = getConfig().getConfigurationSection("pedestals");
        if (pedestals == null) return null;
        for (String id : pedestals.getKeys(false)) {
            String path = "pedestals." + id;
            if (getConfig().getString(path + ".provider", "").equalsIgnoreCase("itemsadder")
                    && List.of("furniture", "shelf").contains(getConfig().getString(path + ".type", "").toLowerCase(java.util.Locale.ROOT))
                    && matchesCustomId(getConfig().getString(path + ".id", ""), customId)) return id;
        }
        return null;
    }

    private boolean matchesCustomId(String configured, String actual) {
        if (configured == null || actual == null || configured.isBlank()) return false;
        if (configured.equalsIgnoreCase(actual)) return true;
        int separator = actual.indexOf(':');
        return !configured.contains(":") && separator >= 0 && configured.equalsIgnoreCase(actual.substring(separator + 1));
    }

    private void handleFurnitureInteractionOnce(Player player, Entity furniture, org.bukkit.Location displayLocation, String pedestalId) {
        if (!markFurnitureInteraction(furniture)) return;
        interactAt(player, displayLocation, pedestalId, furniture);
    }

    private boolean markFurnitureInteraction(Entity furniture) {
        UUID entityId = furniture.getUniqueId();
        if (!furnitureInteractionsThisTick.add(entityId)) return false;
        Bukkit.getScheduler().runTaskLater(this, () -> furnitureInteractionsThisTick.remove(entityId), 1L);
        return true;
    }

    private boolean customIdRegistered(String provider, String type, String id) {
        try {
            if (provider.equals("itemsadder")) {
                String className = type.equals("block") ? "dev.lone.itemsadder.api.CustomBlock" : "dev.lone.itemsadder.api.CustomFurniture";
                Class<?> api = Class.forName(className);
                return Boolean.TRUE.equals(api.getMethod("isInRegistry", String.class).invoke(null, id));
            }
            if (provider.equals("nexo")) {
                Class<?> api = Class.forName(type.equals("block") ? "com.nexomc.nexo.api.NexoBlocks" : "com.nexomc.nexo.api.NexoFurniture");
                String methodName = type.equals("block") ? "customBlockMechanic" : "furnitureMechanic";
                return api.getMethod(methodName, String.class).invoke(null, id) != null;
            }
            if (provider.equals("craftengine")) {
                Class<?> api = Class.forName(type.equals("block") ? "net.momirealms.craftengine.bukkit.api.CraftEngineBlocks" : "net.momirealms.craftengine.bukkit.api.CraftEngineFurniture");
                if (type.equals("block")) return api.getMethod("byId", String.class).invoke(null, id) != null;
                Class<?> keyClass = Class.forName("net.momirealms.craftengine.core.util.Key");
                Object key = keyClass.getMethod("of", String.class).invoke(null, id);
                return api.getMethod("byId", keyClass).invoke(null, key) != null;
            }
        } catch (ReflectiveOperationException | LinkageError exception) {
            startupWarn("Could not validate custom pedestal id '" + id + "': " + exception.getClass().getSimpleName());
        }
        return false;
    }

    private void startupLog(String message) { getComponentLogger().info(logComponent(message)); }
    private void startupWarn(String message) { getComponentLogger().warn(logComponent("Warning: " + message)); }
    private net.kyori.adventure.text.Component logComponent(String message) {
        String prefix = getConfig().getString("prefix", "<gradient:#ffd700:#b8860b>[P-Pedestals]</gradient>");
        return mini.deserialize(prefix + " " + message);
    }

    @EventHandler public void onInteract(PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND || event.getClickedBlock() == null || (event.getAction() != Action.RIGHT_CLICK_BLOCK && event.getAction() != Action.LEFT_CLICK_BLOCK)) return;
        var block = event.getClickedBlock();
        if (!isPedestal(block)) return;
        // Let vanilla handle an item frame attached to the face the player actually clicked.
        if (hasVanillaFrameOnFace(block, event.getBlockFace())) return;
        if (event.getAction() == Action.LEFT_CLICK_BLOCK) {
            event.setCancelled(true);
            if (!worldGuardHook.allows(event.getPlayer(), block.getLocation(), false)) { send(event.getPlayer(), "region-protected"); return; }
            ItemDisplay display = findDisplay(block);
            if (display != null) { rotateHorizontal(display); send(event.getPlayer(), "rotated"); }
            else send(event.getPlayer(), "remove-from-gui");
            return;
        }
        if (!worldGuardHook.allows(event.getPlayer(), block.getLocation(), true)) {
            event.setCancelled(true);
            send(event.getPlayer(), "region-protected");
            return;
        }
        Player player = event.getPlayer();
        event.setCancelled(true);
        openPedestalDisplayGui(player, block.getLocation().add(.5, 1.02, .5), pedestalId(block), block, null);
    }

    private boolean hasVanillaFrameOnFace(org.bukkit.block.Block block, org.bukkit.block.BlockFace clickedFace) {
        org.bukkit.Location center = block.getLocation().add(.5, .5, .5);
        for (Entity nearby : block.getWorld().getNearbyEntities(center, 1.5, 1.5, 1.5)) {
            if (!(nearby instanceof ItemFrame frame)) continue;
            org.bukkit.block.Block frameBlock = frame.getLocation().getBlock();
            if (!frameBlock.equals(block.getRelative(clickedFace))) continue;
            org.bukkit.block.Block attached = frameBlock.getRelative(frame.getAttachedFace());
            if (attached.equals(block) || frameBlock.getRelative(frame.getAttachedFace().getOppositeFace()).equals(block)) return true;
        }
        return false;
    }

    private void spawnDisplay(ItemStack shown, org.bukkit.Location location, String pedestalId) {
        ItemStack placed = shown.clone();
        placed.setAmount(1);
        location.getWorld().spawn(location, ItemDisplay.class, display -> {
            display.setItemStack(placed);
            // FIXED applies the same item model transform used by an item frame.
            display.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.FIXED);
            display.setBillboard(Display.Billboard.FIXED);
            display.setPersistent(true);
            display.getPersistentDataContainer().set(displayKey, PersistentDataType.BYTE, (byte) 1);
            display.getPersistentDataContainer().set(baseKey, PersistentDataType.STRING, pedestalId);
            display.getPersistentDataContainer().set(displayLocationKey, PersistentDataType.STRING, displayLocationId(location));
            display.setRotation(location.getYaw(), location.getPitch());
            float scale = (float) getConfig().getDouble("pedestals." + pedestalId + ".display-scale", 1.0);
            if (!Float.isFinite(scale) || scale <= 0 || scale > 4) scale = 1.0f;
            display.setTransformation(new Transformation(new Vector3f(), new AxisAngle4f(0, 0, 1, 0), new Vector3f(scale), new AxisAngle4f(0, 0, 1, 0)));
        });
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true) public void onPedestalBreak(BlockBreakEvent event) {
        String pedestalId = pedestalId(event.getBlock());
        if (pedestalId == null) return;
        Player player = event.getPlayer();
        event.setCancelled(true);
        if (!worldGuardHook.allows(player, event.getBlock().getLocation(), false)) send(player, "region-protected");
        else send(player, "remove-from-gui");
    }

    @EventHandler public void onCraftPrepare(PrepareItemCraftEvent event) {
        if (containsPedestalItem(event.getInventory().getMatrix())) event.getInventory().setResult(null);
    }

    @EventHandler public void onCraft(CraftItemEvent event) {
        if (event.getInventory() instanceof CraftingInventory inventory && containsPedestalItem(inventory.getMatrix())) event.setCancelled(true);
    }

    private boolean containsPedestalItem(ItemStack[] items) {
        if (!getConfig().getBoolean("behavior.prevent-pedestal-crafting", true)) return false;
        for (ItemStack item : items) {
            if (item == null || item.getType().isAir() || !item.hasItemMeta()) continue;
            if (item.getItemMeta().getPersistentDataContainer().has(pedestalItemKey, PersistentDataType.STRING)) return true;
        }
        return false;
    }

    private boolean isPedestal(org.bukkit.block.Block block) {
        return pedestalId(block) != null;
    }
    private String pedestalId(org.bukkit.block.Block block) {
        String mat = block.getType().name();
        var section = getConfig().getConfigurationSection("pedestals");
        if (section == null) return null;
        for (String id : section.getKeys(false)) {
            String path = "pedestals." + id;
            if (getConfig().getString(path + ".material", "").equalsIgnoreCase(mat)
                    || getConfig().getStringList(path + ".carrier-materials").stream().anyMatch(x -> x.equalsIgnoreCase(mat))) return id;
            String provider = getConfig().getString(path + ".provider", "vanilla");
            if (getConfig().getString(path + ".type", "block").equalsIgnoreCase("block")) {
                String found = customBlockId(block, provider);
                if (found != null && found.equalsIgnoreCase(getConfig().getString(path + ".id", ""))) return id;
            }
        }
        return null;
    }
    private String customBlockId(org.bukkit.block.Block block, String provider) {
        try {
            String apiName = switch (provider.toLowerCase(java.util.Locale.ROOT)) {
                case "itemsadder" -> "dev.lone.itemsadder.api.CustomBlock";
                case "nexo" -> "com.nexomc.nexo.api.NexoBlocks";
                case "craftengine" -> "net.momirealms.craftengine.bukkit.api.CraftEngineBlocks";
                default -> null;
            };
            if (apiName == null) return null;
            Class<?> api = Class.forName(apiName);
            if (provider.equalsIgnoreCase("itemsadder")) {
            Object wrapper = api.getMethod("byAlreadyPlaced", org.bukkit.block.Block.class).invoke(null, block);
            if (wrapper == null) return null;
                return readId(wrapper);
            }
            for (var method : api.getMethods()) {
                if (!java.lang.reflect.Modifier.isStatic(method.getModifiers()) || method.getParameterCount() != 1
                        || !(method.getName().toLowerCase(java.util.Locale.ROOT).contains("block") || method.getName().toLowerCase(java.util.Locale.ROOT).contains("state"))) continue;
                Class<?> parameter = method.getParameterTypes()[0];
                Object argument = parameter.isAssignableFrom(block.getClass()) ? block
                        : parameter.isAssignableFrom(block.getBlockData().getClass()) ? block.getBlockData() : null;
                if (argument == null) continue;
                Object result = method.invoke(null, argument);
                String id = readId(result);
                if (id != null && !id.equalsIgnoreCase("null")) return id;
            }
        } catch (ReflectiveOperationException | LinkageError ignored) { return null; }
        return null;
    }
    private String readId(Object wrapper) {
        return readId(wrapper, 0);
    }
    private String readId(Object wrapper, int depth) {
        if (wrapper == null) return null;
        if (wrapper instanceof java.util.Optional<?> optional) return optional.map(value -> readId(value, depth)).orElse(null);
        for (String getter : List.of("getNamespacedID", "getId", "id", "getBlockId", "getItemID", "getItemId")) try {
            Object value = wrapper.getClass().getMethod(getter).invoke(wrapper);
            if (value instanceof java.util.Optional<?> optional) value = optional.orElse(null);
            if (value != null) return String.valueOf(value);
        } catch (ReflectiveOperationException ignored) { }
        if (depth < 2) for (String getter : List.of("owner", "getOwner", "definition", "getDefinition", "customBlockState", "getCustomBlockState")) try {
            Object nested = wrapper.getClass().getMethod(getter).invoke(wrapper);
            String id = readId(nested, depth + 1);
            if (id != null) return id;
        } catch (ReflectiveOperationException ignored) { }
        return null;
    }

    @EventHandler public void onFurnitureInteract(PlayerInteractAtEntityEvent event) {
        var section = getConfig().getConfigurationSection("pedestals");
        if (section == null) return;
        for (String id : section.getKeys(false)) {
            String path = "pedestals." + id;
            String provider = getConfig().getString(path + ".provider", "");
            String key = furnitureId(event.getRightClicked(), event.getPlayer(), provider);
            String type = getConfig().getString(path + ".type", "");
            if (key != null && List.of("furniture", "shelf").contains(type.toLowerCase(java.util.Locale.ROOT))
                    && matchesCustomId(getConfig().getString(path + ".id", ""), key)) {
                event.setCancelled(true);
                Entity target = event.getRightClicked();
                if (!worldGuardHook.allows(event.getPlayer(), target.getLocation(), true)) { send(event.getPlayer(), "region-protected"); return; }
                if (type.equalsIgnoreCase("shelf")) {
                    if (markFurnitureInteraction(target)) openShelf(event.getPlayer(), target, id);
                } else handleFurnitureInteractionOnce(event.getPlayer(), target, target.getLocation().add(0, .75, 0), id);
                return;
            }
        }
    }

    private void openPedestalDisplayGui(Player player, org.bukkit.Location displayLocation, String pedestalId, org.bukkit.block.Block pedestalBlock, Entity furniture) {
        String path = "pedestals." + pedestalId;
        String title = applyPlaceholders(player, getConfig().getString(path + ".gui-title", "<gold>Pedestal</gold>"));
        int configuredSlot = getConfig().getInt(path + ".gui-slot", 4);
        int guiSlot = configuredSlot >= 0 && configuredSlot < 9 ? configuredSlot : 4;
        PedestalDisplayHolder holder = new PedestalDisplayHolder(pedestalId, adjustedDisplayLocation(displayLocation, pedestalId), guiSlot, pedestalBlock, furniture);
        Inventory gui = Bukkit.createInventory(holder, 9, mini.deserialize(title));
        holder.setInventory(gui);
        renderPedestalDisplayGui(gui, holder);
        player.openInventory(gui);
    }

    private void renderPedestalDisplayGui(Inventory gui, PedestalDisplayHolder holder) {
        ItemStack locked = shelfPane(Material.RED_STAINED_GLASS_PANE, "<red>Unavailable</red>");
        for (int slot = 0; slot < gui.getSize(); slot++) gui.setItem(slot, locked.clone());
        ItemDisplay display = findDisplay(holder.displayLocation);
        ItemStack shown = display == null ? null : display.getItemStack();
        gui.setItem(holder.guiSlot, shown == null || shown.getType().isAir()
                ? shelfPane(Material.GREEN_STAINED_GLASS_PANE, "<green>Available</green>") : shown.clone());
        renderGuiButtons(gui, List.of(holder.guiSlot), holder.pedestalId);
    }

    private void renderGuiButtons(Inventory gui, List<Integer> occupiedSlots, String pedestalId) {
        int[] choices = new int[2];
        int found = 0;
        for (int slot = 0; slot < gui.getSize() && found < choices.length; slot++) {
            if (!occupiedSlots.contains(slot)) choices[found++] = slot;
        }
        if (found < 3) return;
        gui.setItem(choices[0], guiButton(Material.ARROW, "<aqua>Rotate: left-click sideways, right-click clockwise</aqua>", "rotate"));
        gui.setItem(choices[1], guiButton(Material.BARRIER, "<red>Remove pedestal</red>", "remove-pedestal"));
    }

    private ItemStack guiButton(Material material, String name, String action) {
        ItemStack button = new ItemStack(material);
        ItemMeta meta = button.getItemMeta();
        meta.displayName(mini.deserialize(name));
        button.setItemMeta(meta);
        button.editPersistentDataContainer(container -> container.set(guiActionKey, PersistentDataType.STRING, action));
        return button;
    }

    private String guiAction(ItemStack item) {
        if (item == null || item.getType().isAir()) return null;
        return item.getPersistentDataContainer().get(guiActionKey, PersistentDataType.STRING);
    }

    private void handleDisplayGuiButton(Player player, String action, PedestalDisplayHolder holder, ItemStack cursor, ClickType click) {
        if (action.equals("remove-pedestal")) {
            removePedestalFromGui(player, holder, cursor, null);
            return;
        }
        ItemDisplay display = findDisplay(holder.displayLocation);
        if (display == null) return;
        if (action.equals("rotate")) rotateForClick(display, click);
        else return;
        send(player, "rotated");
    }

    private void handleShelfGuiButton(Player player, String action, ShelfHolder holder, ItemStack cursor, ClickType click) {
        if (action.equals("remove-pedestal")) {
            removePedestalFromGui(player, null, cursor, holder);
            return;
        }
        List<ItemDisplay> displays = shelfDisplays(holder.furniture);
        if (action.equals("rotate")) displays.forEach(display -> rotateForClick(display, click));
        else return;
        if (!displays.isEmpty()) send(player, "rotated");
    }

    private List<ItemDisplay> shelfDisplays(Entity furniture) {
        List<ItemDisplay> displays = new ArrayList<>();
        String owner = furniture.getUniqueId().toString();
        for (Entity nearby : furniture.getNearbyEntities(3.0, 2.5, 3.0)) {
            if (nearby instanceof ItemDisplay display
                    && display.getPersistentDataContainer().has(shelfDisplayKey, PersistentDataType.BYTE)
                    && owner.equals(display.getPersistentDataContainer().get(shelfOwnerKey, PersistentDataType.STRING))) displays.add(display);
        }
        return displays;
    }

    private ItemDisplay shelfDisplayAt(Entity furniture, int slotIndex) {
        String owner = furniture.getUniqueId().toString();
        for (Entity nearby : furniture.getNearbyEntities(3.0, 2.5, 3.0)) {
            if (nearby instanceof ItemDisplay display
                    && display.getPersistentDataContainer().has(shelfDisplayKey, PersistentDataType.BYTE)
                    && owner.equals(display.getPersistentDataContainer().get(shelfOwnerKey, PersistentDataType.STRING))
                    && display.getPersistentDataContainer().getOrDefault(shelfIndexKey, PersistentDataType.INTEGER, -1) == slotIndex) return display;
        }
        return null;
    }

    private void removePedestalFromGui(Player player, PedestalDisplayHolder displayHolder, ItemStack cursor, ShelfHolder shelfHolder) {
        String pedestalId = displayHolder != null ? displayHolder.pedestalId : shelfHolder.pedestalId;
        Entity furniture = displayHolder != null ? displayHolder.furniture : shelfHolder.furniture;
        org.bukkit.block.Block block = displayHolder == null ? null : displayHolder.pedestalBlock;
        org.bukkit.Location location = furniture != null ? furniture.getLocation()
                : block != null ? block.getLocation() : null;
        if (location == null) { send(player, "remove-failed"); return; }
        if (!worldGuardHook.allows(player, location, false)) { send(player, "region-protected"); return; }
        if (cursor != null && !cursor.getType().isAir()) { send(player, "clear-cursor"); return; }

        ItemStack pedestalItem = createPedestalItem(pedestalId, 1);
        if (pedestalItem == null) { send(player, "pedestal-item-unavailable"); return; }
        List<ItemStack> returns = new ArrayList<>();
        returns.add(pedestalItem);
        List<ItemDisplay> displaysToRemove = new ArrayList<>();
        if (shelfHolder != null) {
            for (int index = 0; index < 4; index++) {
                ItemStack stored = loadShelfItem(furniture, index);
                if (stored != null) returns.add(stored);
            }
            displaysToRemove.addAll(shelfDisplays(furniture));
        } else {
            ItemDisplay display = findDisplay(displayHolder.displayLocation);
            if (display != null) {
                displaysToRemove.add(display);
                if (display.getItemStack() != null && !display.getItemStack().getType().isAir()) returns.add(display.getItemStack().clone());
            }
        }

        Inventory simulated = Bukkit.createInventory(null, 36);
        simulated.setStorageContents(player.getInventory().getStorageContents());
        if (!simulated.addItem(returns.toArray(ItemStack[]::new)).isEmpty()) { send(player, "inventory-full"); return; }

        if (furniture != null) guiRemovingFurniture.add(furniture.getUniqueId());
        boolean removed = furniture != null ? removeFurnitureBase(furniture, pedestalId, player) : removeBlockBase(block, pedestalId, player);
        if (!removed) {
            if (furniture != null) guiRemovingFurniture.remove(furniture.getUniqueId());
            send(player, "remove-failed");
            return;
        }

        displaysToRemove.forEach(Entity::remove);
        if (shelfHolder != null) {
            for (int index = 0; index < 4; index++) furniture.getPersistentDataContainer().remove(shelfItemKey(index));
        }
        player.getInventory().setStorageContents(simulated.getStorageContents());
        player.closeInventory();
        if (furniture != null) Bukkit.getScheduler().runTaskLater(this, () -> guiRemovingFurniture.remove(furniture.getUniqueId()), 1L);
    }

    private boolean removeBlockBase(org.bukkit.block.Block block, String pedestalId, Player player) {
        String provider = getConfig().getString("pedestals." + pedestalId + ".provider", "vanilla");
        if (!provider.equalsIgnoreCase("vanilla")) {
            String apiName = switch (provider.toLowerCase(java.util.Locale.ROOT)) {
                case "itemsadder" -> "dev.lone.itemsadder.api.CustomBlock";
                case "nexo" -> "com.nexomc.nexo.api.NexoBlocks";
                case "craftengine" -> "net.momirealms.craftengine.bukkit.api.CraftEngineBlocks";
                default -> null;
            };
            if (apiName != null) invokeProviderRemoval(apiName, block, player);
        }
        if (!block.getType().isAir()) block.setType(Material.AIR, false);
        return block.getType().isAir();
    }

    private boolean removeFurnitureBase(Entity furniture, String pedestalId, Player player) {
        String provider = getConfig().getString("pedestals." + pedestalId + ".provider", "itemsadder");
        if (provider.equalsIgnoreCase("itemsadder")) {
            try {
                Class<?> api = Class.forName("dev.lone.itemsadder.api.CustomFurniture");
                Object wrapper = api.getMethod("byAlreadySpawned", Entity.class).invoke(null, furniture);
                if (wrapper != null) {
                    wrapper.getClass().getMethod("remove", boolean.class).invoke(wrapper, false);
                    return true;
                }
            } catch (ReflectiveOperationException | LinkageError exception) {
                startupWarn("ItemsAdder furniture removal API failed: " + exception.getMessage());
            }
        } else {
            String apiName = provider.equalsIgnoreCase("nexo") ? "com.nexomc.nexo.api.NexoFurniture"
                    : "net.momirealms.craftengine.bukkit.api.CraftEngineFurniture";
            if (invokeProviderRemoval(apiName, furniture, player)) return true;
        }
        furniture.remove();
        return true;
    }

    private boolean invokeProviderRemoval(String className, Object target, Player player) {
        try {
            Class<?> api = Class.forName(className);
            for (var method : api.getMethods()) {
                if (!java.lang.reflect.Modifier.isStatic(method.getModifiers()) || !method.getName().equalsIgnoreCase("remove")) continue;
                Class<?>[] parameters = method.getParameterTypes();
                if (parameters.length == 0 || !parameters[0].isAssignableFrom(target.getClass())) continue;
                Object[] arguments = new Object[parameters.length];
                arguments[0] = target;
                boolean supported = true;
                for (int index = 1; index < parameters.length; index++) {
                    if (Player.class.isAssignableFrom(parameters[index])) arguments[index] = player;
                    else if (parameters[index] == boolean.class || parameters[index] == Boolean.class) arguments[index] = false;
                    else { supported = false; break; }
                }
                if (!supported) continue;
                Object result = method.invoke(null, arguments);
                return !(result instanceof Boolean value) || value;
            }
        } catch (ReflectiveOperationException | LinkageError exception) {
            startupWarn("Could not remove custom pedestal through " + className + ": " + exception.getMessage());
        }
        return false;
    }

    @EventHandler public void onPedestalDisplayGuiClick(InventoryClickEvent event) {
        if (!(event.getView().getTopInventory().getHolder() instanceof PedestalDisplayHolder holder)) return;
        Inventory top = event.getView().getTopInventory();
        if (event.getClickedInventory() != top) {
            if (event.getClickedInventory() == null) { event.setCancelled(true); return; }
            if (event.getClick() == ClickType.SHIFT_LEFT || event.getClick() == ClickType.SHIFT_RIGHT) {
                event.setCancelled(true);
                ItemStack source = event.getCurrentItem();
                if (source == null || source.getType().isAir() || findDisplay(holder.displayLocation) != null) return;
                if (!(event.getWhoClicked() instanceof Player player)) return;
                spawnDisplay(source, holder.displayLocation, holder.pedestalId);
                if (source.getAmount() <= 1) event.getClickedInventory().setItem(event.getSlot(), null);
                else { source.setAmount(source.getAmount() - 1); event.getClickedInventory().setItem(event.getSlot(), source); }
                send(player, "placed");
                renderPedestalDisplayGui(top, holder);
                return;
            }
            if (event.getClick() != ClickType.LEFT && event.getClick() != ClickType.RIGHT) event.setCancelled(true);
            return;
        }

        event.setCancelled(true);
        String action = guiAction(top.getItem(event.getRawSlot()));
        if (action != null && event.getWhoClicked() instanceof Player player) {
            handleDisplayGuiButton(player, action, holder, event.getCursor(), event.getClick());
            return;
        }
        if (event.getRawSlot() != holder.guiSlot) return;
        ItemDisplay display = findDisplay(holder.displayLocation);
        if (event.getClick() == ClickType.SHIFT_LEFT || event.getClick() == ClickType.SHIFT_RIGHT) {
            if (display == null || !(event.getWhoClicked() instanceof Player player)) return;
            ItemStack shown = display.getItemStack();
            if (shown == null || shown.getType().isAir()) return;
            if (!player.getInventory().addItem(shown.clone()).isEmpty()) {
                send(player, "inventory-full");
                return;
            }
            display.remove();
            send(player, "removed");
            renderPedestalDisplayGui(top, holder);
            return;
        }
        if (event.getClick() != ClickType.LEFT && event.getClick() != ClickType.RIGHT) return;

        ItemStack cursor = event.getCursor();
        boolean cursorEmpty = cursor == null || cursor.getType().isAir();
        if (display == null && !cursorEmpty) {
            spawnDisplay(cursor, holder.displayLocation, holder.pedestalId);
            ItemStack remainder = cursor.clone();
            remainder.setAmount(remainder.getAmount() - 1);
            event.setCursor(remainder.getAmount() > 0 ? remainder : null);
            send(event.getWhoClicked(), "placed");
            renderPedestalDisplayGui(top, holder);
        } else if (display != null && cursorEmpty) {
            ItemStack shown = display.getItemStack();
            if (shown == null || shown.getType().isAir()) return;
            rotateForClick(display, event.getClick());
            send(event.getWhoClicked(), "rotated");
            renderPedestalDisplayGui(top, holder);
        }
    }

    private void openShelf(Player player, Entity furniture, String pedestalId) {
        List<Integer> slots = shelfGuiSlots(pedestalId);
        String title = getConfig().getString("pedestals." + pedestalId + ".gui-title", "<gold>Collectible Shelf</gold>");
        title = applyPlaceholders(player, title);
        ShelfHolder holder = new ShelfHolder(pedestalId, furniture, slots);
        Inventory gui = Bukkit.createInventory(holder, 9, mini.deserialize(title));
        holder.setInventory(gui);
        renderShelfInventory(gui, holder);
        player.openInventory(gui);
    }

    private String applyPlaceholders(Player player, String text) {
        var placeholderApi = Bukkit.getPluginManager().getPlugin("PlaceholderAPI");
        if (placeholderApi == null || !placeholderApi.isEnabled()) return text;
        try {
            Class<?> api = Class.forName("me.clip.placeholderapi.PlaceholderAPI", true, placeholderApi.getClass().getClassLoader());
            return (String) api.getMethod("setPlaceholders", Player.class, String.class).invoke(null, player, text);
        } catch (ReflectiveOperationException | LinkageError exception) {
            startupWarn("PlaceholderAPI is enabled but GUI title placeholders could not be applied: " + exception.getMessage());
            return text;
        }
    }

    private void renderShelfInventory(Inventory gui, ShelfHolder holder) {
        ItemStack locked = shelfPane(Material.RED_STAINED_GLASS_PANE, "<red>Unavailable</red>");
        for (int slot = 0; slot < gui.getSize(); slot++) gui.setItem(slot, locked.clone());
        for (int index = 0; index < shelfDisplayCount(holder.pedestalId); index++) {
            ItemStack item = loadShelfItem(holder.furniture, index);
            int slot = holder.guiSlots.get(index);
            gui.setItem(slot, item != null ? item : shelfPane(Material.GREEN_STAINED_GLASS_PANE, "<green>Available</green>"));
        }
        renderGuiButtons(gui, holder.guiSlots, holder.pedestalId);
    }

    private ItemStack shelfPane(Material material, String name) {
        ItemStack pane = new ItemStack(material);
        ItemMeta meta = pane.getItemMeta();
        meta.displayName(mini.deserialize(name));
        pane.setItemMeta(meta);
        pane.editPersistentDataContainer(container -> container.set(shelfGuiMarkerKey, PersistentDataType.BYTE, (byte) 1));
        return pane;
    }

    private List<Integer> shelfGuiSlots(String pedestalId) {
        int count = shelfDisplayCount(pedestalId);
        List<Integer> slots = getConfig().getIntegerList("pedestals." + pedestalId + ".gui-slots");
        if (slots.size() == count && slots.stream().allMatch(slot -> slot >= 0 && slot < 9) && slots.stream().distinct().count() == count) return slots;
        return switch (count) {
            case 1 -> List.of(4);
            case 2 -> List.of(3, 5);
            case 3 -> List.of(2, 4, 6);
            case 4 -> List.of(1, 3, 5, 7);
            default -> List.of(2, 4, 6);
        };
    }

    private int shelfDisplayCount(String pedestalId) {
        return Math.max(1, Math.min(4, getConfig().getInt("pedestals." + pedestalId + ".display-count", 3)));
    }

    @EventHandler public void onShelfClick(InventoryClickEvent event) {
        if (!(event.getView().getTopInventory().getHolder() instanceof ShelfHolder holder)) return;
        Inventory top = event.getView().getTopInventory();
        if (event.getClickedInventory() != top) {
            if (event.getClickedInventory() == null) { event.setCancelled(true); return; }
            if (event.getClick() == ClickType.SHIFT_LEFT || event.getClick() == ClickType.SHIFT_RIGHT) {
                event.setCancelled(true);
                ItemStack source = event.getCurrentItem();
                if (source == null || source.getType().isAir()) return;
                if (!isAllowedShelfItem(holder.pedestalId, source)) {
                    send(event.getWhoClicked(), "shelf-item-not-allowed");
                    return;
                }
                int targetIndex = -1;
                for (int index = 0; index < shelfDisplayCount(holder.pedestalId); index++) if (loadShelfItem(holder.furniture, index) == null) { targetIndex = index; break; }
                if (targetIndex < 0) return;
                ItemStack placed = source.clone();
                placed.setAmount(1);
                saveShelfItem(holder.furniture, targetIndex, placed);
                if (source.getAmount() <= 1) event.getClickedInventory().setItem(event.getSlot(), null);
                else { source.setAmount(source.getAmount() - 1); event.getClickedInventory().setItem(event.getSlot(), source); }
                updateShelfDisplays(holder.furniture, holder.pedestalId);
                renderShelfInventory(top, holder);
                return;
            }
            if (event.getClick() != ClickType.LEFT && event.getClick() != ClickType.RIGHT) event.setCancelled(true);
            return;
        }
        event.setCancelled(true);
        String action = guiAction(top.getItem(event.getRawSlot()));
        if (action != null && event.getWhoClicked() instanceof Player player) {
            handleShelfGuiButton(player, action, holder, event.getCursor(), event.getClick());
            return;
        }
        int slotIndex = holder.guiSlots.indexOf(event.getRawSlot());
        if (event.getClick() == ClickType.SHIFT_LEFT || event.getClick() == ClickType.SHIFT_RIGHT) {
            if (slotIndex < 0) return;
            ItemStack stored = loadShelfItem(holder.furniture, slotIndex);
            if (stored == null || !(event.getWhoClicked() instanceof Player player)) return;
            if (!player.getInventory().addItem(stored.clone()).isEmpty()) {
                send(player, "inventory-full");
                return;
            }
            saveShelfItem(holder.furniture, slotIndex, null);
            updateShelfDisplays(holder.furniture, holder.pedestalId);
            renderShelfInventory(top, holder);
            return;
        }
        if (slotIndex < 0 || (event.getClick() != ClickType.LEFT && event.getClick() != ClickType.RIGHT)) return;

        ItemStack current = top.getItem(event.getRawSlot());
        ItemStack cursor = event.getCursor();
        boolean currentEmpty = current == null || current.getType().isAir() || current.getPersistentDataContainer().has(shelfGuiMarkerKey, PersistentDataType.BYTE);
        boolean cursorEmpty = cursor == null || cursor.getType().isAir();
        if (currentEmpty && !cursorEmpty) {
            if (!isAllowedShelfItem(holder.pedestalId, cursor)) {
                send(event.getWhoClicked(), "shelf-item-not-allowed");
                return;
            }
            ItemStack placed = cursor.clone();
            placed.setAmount(1);
            ItemStack remainder = cursor.clone();
            remainder.setAmount(remainder.getAmount() - 1);
            event.setCursor(remainder.getAmount() > 0 ? remainder : null);
            saveShelfItem(holder.furniture, slotIndex, placed);
            updateShelfDisplays(holder.furniture, holder.pedestalId);
            renderShelfInventory(top, holder);
        } else if (!currentEmpty && cursorEmpty) {
            ItemDisplay display = shelfDisplayAt(holder.furniture, slotIndex);
            if (display == null) return;
            rotateForClick(display, event.getClick());
            send(event.getWhoClicked(), "rotated");
        }
    }

    @EventHandler public void onShelfDrag(InventoryDragEvent event) {
        Object holder = event.getView().getTopInventory().getHolder();
        if (!(holder instanceof ShelfHolder) && !(holder instanceof PedestalDisplayHolder)) return;
        if (event.getRawSlots().stream().anyMatch(slot -> slot < event.getView().getTopInventory().getSize())) event.setCancelled(true);
    }

    @EventHandler public void onShelfClose(InventoryCloseEvent event) {
        if (event.getView().getTopInventory().getHolder() instanceof ShelfHolder holder
                && !guiRemovingFurniture.contains(holder.furniture.getUniqueId()))
            updateShelfDisplays(holder.furniture, holder.pedestalId);
    }

    private boolean isAllowedShelfItem(String pedestalId, ItemStack item) {
        String itemId = itemsAdderItemId(item);
        if (itemId == null) return false;
        String path = "pedestals." + pedestalId + ".allowed-items";
        List<String> allowedItems = getConfig().isConfigurationSection(path)
                ? getConfig().getStringList(path + ".items")
                : getConfig().getStringList(path); // Legacy flat-list support.
        boolean listed = allowedItems.stream().anyMatch(allowed -> matchesCustomId(allowed, itemId));
        String mode = getConfig().getString(path + ".mode", "whitelist");
        return mode.equalsIgnoreCase("blacklist") ? !listed : listed;
    }

    private String itemsAdderItemId(ItemStack item) {
        try {
            var itemsAdder = Bukkit.getPluginManager().getPlugin("ItemsAdder");
            if (itemsAdder == null || !itemsAdder.isEnabled()) return null;
            Class<?> api = Class.forName("dev.lone.itemsadder.api.CustomStack", true, itemsAdder.getClass().getClassLoader());
            Object stack = api.getMethod("byItemStack", ItemStack.class).invoke(null, item);
            return readId(stack);
        } catch (ReflectiveOperationException | LinkageError ignored) { return null; }
    }

    private boolean hasShelfContents(Entity furniture, String pedestalId) {
        // Check all four storage keys so lowering display-count never makes hidden items breakable.
        for (int index = 0; index < 4; index++) if (loadShelfItem(furniture, index) != null) return true;
        for (Entity nearby : furniture.getNearbyEntities(3.0, 2.0, 3.0)) {
            if (nearby instanceof ItemDisplay display
                    && display.getPersistentDataContainer().has(shelfDisplayKey, PersistentDataType.BYTE)
                    && furniture.getUniqueId().toString().equals(display.getPersistentDataContainer().get(shelfOwnerKey, PersistentDataType.STRING))) return true;
        }
        return false;
    }

    private ItemStack loadShelfItem(Entity furniture, int index) {
        String encoded = furniture.getPersistentDataContainer().get(shelfItemKey(index), PersistentDataType.STRING);
        if (encoded == null || encoded.isBlank()) return null;
        try (BukkitObjectInputStream input = new BukkitObjectInputStream(new ByteArrayInputStream(Base64.getDecoder().decode(encoded)))) {
            Object value = input.readObject();
            return value instanceof ItemStack item && !item.getType().isAir() ? item : null;
        } catch (IOException | ClassNotFoundException | IllegalArgumentException exception) {
            startupWarn("Could not load saved shelf item " + index + ": " + exception.getMessage());
            return null;
        }
    }

    private void saveShelfItem(Entity furniture, int index, ItemStack item) {
        if (item == null || item.getType().isAir()) {
            furniture.getPersistentDataContainer().remove(shelfItemKey(index));
            return;
        }
        try (ByteArrayOutputStream bytes = new ByteArrayOutputStream(); BukkitObjectOutputStream output = new BukkitObjectOutputStream(bytes)) {
            output.writeObject(item);
            output.flush();
            furniture.getPersistentDataContainer().set(shelfItemKey(index), PersistentDataType.STRING, Base64.getEncoder().encodeToString(bytes.toByteArray()));
        } catch (IOException exception) {
            startupWarn("Could not save shelf item " + index + ": " + exception.getMessage());
        }
    }

    private NamespacedKey shelfItemKey(int index) { return new NamespacedKey(this, "shelf_item_" + index); }

    private void updateShelfDisplays(Entity furniture, String pedestalId) {
        UUID ownerId = furniture.getUniqueId();
        org.bukkit.Location anchor = furniture.getLocation();
        Map<Integer, int[]> savedRotations = new java.util.HashMap<>();
        for (Entity nearby : furniture.getNearbyEntities(3.0, 2.5, 3.0)) {
            if (nearby instanceof ItemDisplay display
                    && display.getPersistentDataContainer().has(shelfDisplayKey, PersistentDataType.BYTE)
                    && ownerId.toString().equals(display.getPersistentDataContainer().get(shelfOwnerKey, PersistentDataType.STRING))) {
                int displayIndex = display.getPersistentDataContainer().getOrDefault(shelfIndexKey, PersistentDataType.INTEGER, -1);
                if (displayIndex >= 0) {
                    int horizontal = display.getPersistentDataContainer().getOrDefault(new NamespacedKey(this, "horizontal_rotation"), PersistentDataType.INTEGER, 0);
                    int clockwise = display.getPersistentDataContainer().getOrDefault(new NamespacedKey(this, "clockwise_rotation"), PersistentDataType.INTEGER, 0);
                    savedRotations.put(displayIndex, new int[]{horizontal, clockwise});
                }
                display.remove();
            }
        }

        List<String> offsets = getConfig().getStringList("pedestals." + pedestalId + ".display-offsets");
        int displayCount = shelfDisplayCount(pedestalId);
        // The X distribution is generated from count and gap so its group is always centered.
        if (offsets.isEmpty()) offsets = List.of("0.5,-0.01,0.35");
        double displayHeightOffset = getConfig().getDouble("pedestals." + pedestalId + ".display-height-offset", 0.0);
        double displaySpacing = getConfig().getDouble("pedestals." + pedestalId + ".display-spacing", 0.0);
        double displayCenterOffset = getConfig().getDouble("pedestals." + pedestalId + ".display-center-offset", 0.0);
        double displayDepthOffset = getConfig().getDouble("pedestals." + pedestalId + ".display-depth-offset", 0.0);
        if (!Double.isFinite(displayHeightOffset)) displayHeightOffset = 0.0;
        if (!Double.isFinite(displaySpacing)) displaySpacing = 0.0;
        if (!Double.isFinite(displayCenterOffset) || Math.abs(displayCenterOffset) > 2) displayCenterOffset = 0.0;
        if (!Double.isFinite(displayDepthOffset) || Math.abs(displayDepthOffset) > 2) displayDepthOffset = 0.0;
        final double heightOffset = displayHeightOffset;
        double defaultSpacing = displayCount == 1 ? 0.0 : 1.5 / (displayCount - 1) - 0.1;
        final double spacing = Math.max(0.0, Math.min(2.0, defaultSpacing + displaySpacing));
        final double centerOffset = displayCenterOffset;
        final double depthOffset = displayDepthOffset;
        float scale = (float) getConfig().getDouble("pedestals." + pedestalId + ".display-scale", 1.4);
        if (scale <= 0 || scale > 3) scale = 1.4f;
        final float displayScale = scale;
        float yaw = anchor.getYaw();
        double radians = Math.toRadians(yaw);
        for (int index = 0; index < displayCount; index++) {
            ItemStack item = loadShelfItem(furniture, index);
            if (item == null) continue;
            String[] xyz = offsets.get(Math.min(index, offsets.size() - 1)).split(",");
            if (xyz.length != 3) continue;
            try {
                double localX = 0.5 + centerOffset + (index - (displayCount - 1) / 2.0) * spacing;
                double localY = Double.parseDouble(xyz[1].trim()) + heightOffset;
                double localZ = Double.parseDouble(xyz[2].trim()) + depthOffset;
                // Bukkit yaw increases clockwise from south. Rotate local furniture offsets
                // in the same direction as the furniture, rather than mirroring them behind it.
                double worldX = localX * Math.cos(radians) - localZ * Math.sin(radians);
                double worldZ = localX * Math.sin(radians) + localZ * Math.cos(radians);
                org.bukkit.Location location = anchor.clone().add(worldX, localY, worldZ);
                location.setYaw(yaw);
                location.setPitch(0);
            final int displayIndex = index;
                location.getWorld().spawn(location, ItemDisplay.class, display -> {
                    display.setItemStack(item);
                    display.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.GROUND);
                    display.setBillboard(Display.Billboard.FIXED);
                    display.setPersistent(true);
                    display.getPersistentDataContainer().set(displayKey, PersistentDataType.BYTE, (byte) 1);
                    display.getPersistentDataContainer().set(shelfDisplayKey, PersistentDataType.BYTE, (byte) 1);
                    display.getPersistentDataContainer().set(shelfOwnerKey, PersistentDataType.STRING, ownerId.toString());
                    display.getPersistentDataContainer().set(shelfIndexKey, PersistentDataType.INTEGER, displayIndex);
                    display.getPersistentDataContainer().set(baseKey, PersistentDataType.STRING, pedestalId);
                    int[] rotations = savedRotations.get(displayIndex);
                    if (rotations != null) {
                        display.getPersistentDataContainer().set(new NamespacedKey(this, "horizontal_rotation"), PersistentDataType.INTEGER, rotations[0]);
                        display.getPersistentDataContainer().set(new NamespacedKey(this, "clockwise_rotation"), PersistentDataType.INTEGER, rotations[1]);
                    }
                    display.setTransformation(new Transformation(new Vector3f(), new AxisAngle4f(0, 0, 1, 0), new Vector3f(displayScale), new AxisAngle4f(0, 0, 1, 0)));
                    if (rotations != null) applyDisplayRotations(display);
                });
            } catch (NumberFormatException exception) {
                startupWarn("Invalid display-offset for shelf '" + pedestalId + "': " + offsets.get(index));
            }
        }
    }
    private String furnitureId(Entity entity, Player player, String provider) {
        try {
            if (provider.equalsIgnoreCase("itemsadder")) {
                Class<?> api = Class.forName("dev.lone.itemsadder.api.CustomFurniture");
                Object wrapper = api.getMethod("byAlreadySpawned", Entity.class).invoke(null, entity);
                if (wrapper == null) wrapper = api.getMethod("byAlreadySpawned", org.bukkit.block.Block.class).invoke(null, entity.getLocation().getBlock());
                return readId(wrapper);
            }
            if (provider.equalsIgnoreCase("nexo")) {
                Class<?> api = Class.forName("com.nexomc.nexo.api.NexoFurniture");
                Object mechanic = api.getMethod("furnitureMechanic", Entity.class).invoke(null, entity);
                if (mechanic == null) {
                    Object base = api.getMethod("findTargetFurniture", Player.class).invoke(null, player);
                    if (base instanceof Entity baseEntity) mechanic = api.getMethod("furnitureMechanic", Entity.class).invoke(null, baseEntity);
                }
                return readId(mechanic);
            }
            if (provider.equalsIgnoreCase("craftengine")) {
                Class<?> api = Class.forName("net.momirealms.craftengine.bukkit.api.CraftEngineFurniture");
                for (var method : api.getMethods()) {
                    if (!java.lang.reflect.Modifier.isStatic(method.getModifiers()) || method.getParameterCount() != 1
                            || !method.getParameterTypes()[0].isAssignableFrom(entity.getClass())
                            || !method.getName().toLowerCase(java.util.Locale.ROOT).contains("furniture")) continue;
                    String found = readId(method.invoke(null, entity));
                    if (found != null) return found;
                }
            }
        } catch (ReflectiveOperationException | LinkageError ignored) { return null; }
        return null;
    }
    private void interactAt(Player player, org.bukkit.Location location, String id, Entity furniture) {
        ItemDisplay display = findDisplayAtPedestal(location, id);
        if (display != null) {
            openPedestalDisplayGui(player, location, id, null, furniture);
            return;
        }
        openPedestalDisplayGui(player, location, id, null, furniture);
    }
    private ItemDisplay findDisplay(org.bukkit.block.Block block) {
        String pedestalId = pedestalId(block);
        org.bukkit.Location anchor = block.getLocation().add(.5, 1.02, .5);
        return findDisplay(pedestalId == null ? anchor : adjustedDisplayLocation(anchor, pedestalId));
    }
    private org.bukkit.Location adjustedDisplayLocation(org.bukkit.Location anchor, String pedestalId) {
        org.bukkit.Location location = anchor.clone();
        double heightOffset = getConfig().getDouble("pedestals." + pedestalId + ".display-height-offset", 0.0);
        if (Double.isFinite(heightOffset)) location.add(0, heightOffset, 0);
        return location;
    }
    private ItemDisplay findDisplayAtPedestal(org.bukkit.Location anchor, String pedestalId) {
        return findDisplay(adjustedDisplayLocation(anchor, pedestalId));
    }
    private ItemDisplay findDisplay(org.bukkit.Location center) {
        String expectedLocation = displayLocationId(center);
        for (Entity entity : center.getWorld().getNearbyEntities(center, .5, .75, .5)) {
            if (!(entity instanceof ItemDisplay display)
                    || !display.getPersistentDataContainer().has(displayKey, PersistentDataType.BYTE)
                    || display.getPersistentDataContainer().has(shelfDisplayKey, PersistentDataType.BYTE)) continue;
            String storedLocation = display.getPersistentDataContainer().get(displayLocationKey, PersistentDataType.STRING);
            if (expectedLocation.equals(storedLocation)) return display;
            // Continue to recognize displays created before location ownership was recorded,
            // but only when they are almost exactly at this pedestal's display point.
            if (storedLocation == null && display.getLocation().distanceSquared(center) <= .04) return display;
        }
        return null;
    }
    private String displayLocationId(org.bukkit.Location location) {
        return location.getWorld().getUID() + ":" + Math.round(location.getX() * 10000) + ":"
                + Math.round(location.getY() * 10000) + ":" + Math.round(location.getZ() * 10000);
    }
    private void rotate(ItemDisplay display) {
        rotateHorizontal(display);
    }

    private void rotateForClick(ItemDisplay display, ClickType click) {
        if (click == ClickType.RIGHT || click == ClickType.SHIFT_RIGHT) rotateClockwise(display);
        else rotateHorizontal(display);
    }

    private void rotateHorizontal(ItemDisplay display) {
        NamespacedKey key = new NamespacedKey(this, "horizontal_rotation");
        int current = display.getPersistentDataContainer().getOrDefault(key, PersistentDataType.INTEGER, 0);
        display.getPersistentDataContainer().set(key, PersistentDataType.INTEGER, (current + 1) % 8);
        applyDisplayRotations(display);
    }

    private void rotateClockwise(ItemDisplay display) {
        NamespacedKey key = new NamespacedKey(this, "clockwise_rotation");
        int current = display.getPersistentDataContainer().getOrDefault(key, PersistentDataType.INTEGER, 0);
        display.getPersistentDataContainer().set(key, PersistentDataType.INTEGER, (current + 1) % 8);
        applyDisplayRotations(display);
    }

    private void applyDisplayRotations(ItemDisplay display) {
        int horizontal = display.getPersistentDataContainer().getOrDefault(new NamespacedKey(this, "horizontal_rotation"), PersistentDataType.INTEGER, 0);
        int clockwise = display.getPersistentDataContainer().getOrDefault(new NamespacedKey(this, "clockwise_rotation"), PersistentDataType.INTEGER, 0);
        Quaternionf rotation = new Quaternionf().rotationY((float) Math.toRadians(horizontal * 45.0))
                .mul(new Quaternionf().rotationZ((float) Math.toRadians(-clockwise * 45.0)));
        Transformation old = display.getTransformation();
        display.setTransformation(new Transformation(old.getTranslation(), rotation, old.getScale(), old.getRightRotation()));
    }
    private void send(CommandSender sender, String key) {
        var cfg = getConfig(); String prefix = cfg.getString("prefix", "<gradient:#ffd700:#b8860b>[P-Pedestals]</gradient>");
        String message = messageCatalog == null ? "<red>Missing message: " + key + "</red>" : messageCatalog.get(key);
        sender.sendMessage(mini.deserialize(prefix + " " + message));
    }
    private ItemStack createPedestalItem(String pedestalId, int amount) {
        String path = "pedestals." + pedestalId;
        if (!getConfig().isConfigurationSection(path)) return null;
        String provider = getConfig().getString(path + ".provider", "vanilla");
        String materialName = getConfig().getString(path + ".material", "");
        ItemStack item = null;
        try {
            if (provider.equalsIgnoreCase("vanilla")) item = new ItemStack(Material.valueOf(materialName.toUpperCase(java.util.Locale.ROOT)));
            else if (provider.equalsIgnoreCase("itemsadder")) {
                String apiName = getConfig().getString(path + ".type", "block").equalsIgnoreCase("block")
                        ? "dev.lone.itemsadder.api.CustomBlock" : "dev.lone.itemsadder.api.CustomFurniture";
                Class<?> api = Class.forName(apiName);
                Object wrapper = api.getMethod("getInstance", String.class).invoke(null, getConfig().getString(path + ".id"));
                if (wrapper != null) item = (ItemStack) api.getMethod("getItemStack").invoke(wrapper);
            } else if (provider.equalsIgnoreCase("nexo")) {
                Class<?> api = Class.forName("com.nexomc.nexo.api.NexoItems");
                Object builder = api.getMethod("itemFromId", String.class).invoke(null, getConfig().getString(path + ".id"));
                if (builder != null) item = (ItemStack) builder.getClass().getMethod("build").invoke(builder);
            } else if (provider.equalsIgnoreCase("craftengine")) {
                Class<?> api = Class.forName("net.momirealms.craftengine.bukkit.api.CraftEngineItems");
                Object definition = api.getMethod("byId", String.class).invoke(null, getConfig().getString(path + ".id"));
                if (definition != null) item = (ItemStack) definition.getClass().getMethod("buildBukkitItem").invoke(definition);
            }
            if (item == null && !materialName.isBlank()) item = new ItemStack(Material.valueOf(materialName.toUpperCase(java.util.Locale.ROOT)));
        } catch (ReflectiveOperationException | IllegalArgumentException ex) {
            startupWarn("Could not create pedestal item '" + pedestalId + "': " + ex.getMessage());
        }
        if (item == null || item.getType().isAir()) return null;
        item.setAmount(amount);
        ItemMeta meta = item.getItemMeta();
        String customName = getConfig().getString(path + ".name");
        if (customName != null && !customName.isBlank()) meta.displayName(mini.deserialize(customName));
        List<String> configuredLore = getConfig().getStringList(path + ".lore");
        if (!configuredLore.isEmpty()) meta.lore(configuredLore.stream().map(mini::deserialize).toList());
        String tooltipStyle = getConfig().getString(path + ".tooltip-style");
        if (tooltipStyle != null && !tooltipStyle.isBlank()) {
            NamespacedKey styleKey = NamespacedKey.fromString(tooltipStyle);
            if (styleKey == null) startupWarn("Invalid tooltip-style '" + tooltipStyle + "' in " + path);
            else meta.setTooltipStyle(styleKey);
        }
        meta.getPersistentDataContainer().set(pedestalItemKey, PersistentDataType.STRING, pedestalId);
        item.setItemMeta(meta);
        return item;
    }

    @Override public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("potatopedestals.admin")) { send(sender, "no-permission"); return true; }
        if (args.length == 1 && args[0].equalsIgnoreCase("reload")) {
            reloadConfig();
            new ConfigMigration(this).migrate();
            reloadConfig();
            messageCatalog.synchronize();
            validateStartupConfiguration();
            send(sender,"reloaded");
            return true;
        }
        if (args.length >= 2 && args[0].equalsIgnoreCase("give")) {
            Player target = sender instanceof Player p ? p : null;
            String pedestalId = args[1];
            int amount = 1;
            if (args.length >= 3) {
                target = Bukkit.getPlayerExact(args[2]);
                if (target == null) { send(sender,"player-not-found"); return true; }
            }
            if (target == null) { send(sender,"usage-give"); return true; }
            if (args.length >= 4) try { amount = Math.max(1, Math.min(64, Integer.parseInt(args[3]))); }
            catch (NumberFormatException ignored) { send(sender,"usage-give"); return true; }
            if (!getConfig().isConfigurationSection("pedestals." + pedestalId)) { send(sender,"pedestal-not-found"); return true; }
            ItemStack item = createPedestalItem(pedestalId, amount);
            if (item == null || item.getType().isAir()) { send(sender,"pedestal-item-unavailable"); return true; }
            Map<Integer, ItemStack> leftover = target.getInventory().addItem(item);
            if (!leftover.isEmpty()) target.getWorld().dropItemNaturally(target.getLocation(), leftover.values().iterator().next());
            send(sender,"given");
            return true;
        }
        send(sender,"usage-give");
        return false;
    }

    @Override public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) return partial(List.of("give", "reload"), args[0]);
        if (args.length == 2 && args[0].equalsIgnoreCase("give")) return partial(pedestalIds(), args[1]);
        if (args.length == 3 && args[0].equalsIgnoreCase("give")) return partial(Bukkit.getOnlinePlayers().stream().map(Player::getName).toList(), args[2]);
        if (args.length == 4 && args[0].equalsIgnoreCase("give")) return partial(List.of("1", "16", "64"), args[3]);
        return Collections.emptyList();
    }
    private List<String> pedestalIds() {
        var section = getConfig().getConfigurationSection("pedestals");
        return section == null ? List.of() : new ArrayList<>(section.getKeys(false));
    }
    private List<String> partial(List<String> options, String input) {
        String prefix = input.toLowerCase(java.util.Locale.ROOT);
        return options.stream().filter(s -> s.toLowerCase(java.util.Locale.ROOT).startsWith(prefix)).distinct().collect(Collectors.toList());
    }
}
