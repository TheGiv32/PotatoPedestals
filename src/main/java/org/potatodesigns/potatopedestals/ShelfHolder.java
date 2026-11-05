package org.potatodesigns.potatopedestals;

import org.bukkit.entity.Entity;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;

import java.util.List;

final class ShelfHolder implements InventoryHolder {
    final String pedestalId;
    final Entity furniture;
    final List<Integer> guiSlots;
    private Inventory inventory;

    ShelfHolder(String pedestalId, Entity furniture, List<Integer> guiSlots) {
        this.pedestalId = pedestalId;
        this.furniture = furniture;
        this.guiSlots = List.copyOf(guiSlots);
    }

    void setInventory(Inventory inventory) { this.inventory = inventory; }

    @Override public Inventory getInventory() { return inventory; }
}
