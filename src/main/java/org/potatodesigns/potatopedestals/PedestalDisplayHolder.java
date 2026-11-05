package org.potatodesigns.potatopedestals;

import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.block.Block;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;

final class PedestalDisplayHolder implements InventoryHolder {
    final String pedestalId;
    final Location displayLocation;
    final int guiSlot;
    final Block pedestalBlock;
    final Entity furniture;
    private Inventory inventory;

    PedestalDisplayHolder(String pedestalId, Location displayLocation, int guiSlot, Block pedestalBlock, Entity furniture) {
        this.pedestalId = pedestalId;
        this.displayLocation = displayLocation.clone();
        this.guiSlot = guiSlot;
        this.pedestalBlock = pedestalBlock;
        this.furniture = furniture;
    }

    void setInventory(Inventory inventory) { this.inventory = inventory; }

    @Override public Inventory getInventory() { return inventory; }
}
