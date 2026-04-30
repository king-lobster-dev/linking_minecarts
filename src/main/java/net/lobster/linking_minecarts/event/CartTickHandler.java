package net.lobster.linking_minecarts.event;

import net.lobster.linking_minecarts.util.ChainPhysics;
import net.lobster.linking_minecarts.util.LinkManager;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.vehicle.AbstractMinecart;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

public class CartTickHandler {

    /**
     * Runs at the END of each server level tick, after vanilla has fully
     * processed every entity's tick for this game tick.

     * At this point each cart's deltaMovement reflects its final post-physics
     * state for this tick — friction applied, rail direction resolved, powered
     * rail boosts applied, player input applied. We read those values to compute
     * the authority speed and write corrected velocities back. Vanilla will
     * apply those on the next tick.

     * Processing order:
     *   1. Clean removed carts from the ACTIVE set
     *   2. For each cart in ACTIVE, process its chain (each chain processed once)
     */
    @SubscribeEvent
    public void onLevelTick(TickEvent.LevelTickEvent event) {

        if (event.phase != TickEvent.Phase.END) return;
        if (event.level.isClientSide()) return;
        if (!(event.level instanceof ServerLevel level)) return;

        // Remove carts that have been destroyed or unloaded
        LinkManager.cleanActive();

        // processedThisTick is local — no cleanup needed, fresh every tick
        Set<UUID> processedThisTick = new HashSet<>();

        for (AbstractMinecart cart : LinkManager.getActiveCarts()) {
            if (cart.isRemoved()) continue;
            ChainPhysics.processChain(cart, level, processedThisTick);
        }
    }
}