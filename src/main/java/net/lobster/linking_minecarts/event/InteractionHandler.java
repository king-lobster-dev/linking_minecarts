package net.lobster.linking_minecarts.event;

import net.lobster.linking_minecarts.util.LinkManager;

import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.vehicle.AbstractMinecart;
import net.minecraft.world.item.*;

import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

import java.util.UUID;

public class InteractionHandler {

    private static final String SELECTED = "SelectedCart";

    @SubscribeEvent
    public void onInteract(PlayerInteractEvent.EntityInteract event) {

        if (!(event.getTarget() instanceof AbstractMinecart cart)) return;

        Player player = event.getEntity();
        if (player.level().isClientSide()) return;
        if (!(player.level() instanceof ServerLevel level)) return;

        ItemStack item = event.getItemStack();
        var data = player.getPersistentData();

        // ================= CHAIN — link tool =================
        if (item.getItem() == Items.CHAIN) {

            // Shift-click clears selection
            if (player.isShiftKeyDown()) {
                data.remove(SELECTED);
                send(player, "Selection cleared");
                event.setCanceled(true);
                return;
            }

            // First click — select this cart as chain head
            if (!data.hasUUID(SELECTED)) {
                data.putUUID(SELECTED, cart.getUUID());
                send(player, "Cart selected");
                event.setCanceled(true);
                return;
            }

            // Second click — link from selected cart to this one
            UUID firstId = data.getUUID(SELECTED);
            data.remove(SELECTED);

            var firstEntity = level.getEntity(firstId);

            if (!(firstEntity instanceof AbstractMinecart start)) {
                send(player, "Invalid selection");
                event.setCanceled(true);
                return;
            }

            if (start == cart) {
                send(player, "Cannot link a cart to itself");
                event.setCanceled(true);
                return;
            }

            int linked = LinkManager.linkSeries(start, cart, level);

            send(player,
                    linked > 0
                            ? "Linked " + (linked + 1) + " carts"
                            : "Could not link carts — are they close enough and on rails?"
            );

            event.setCanceled(true);
        }

        // ================= PICKAXE — unlink tool =================
        if (item.getItem() instanceof PickaxeItem && player.isShiftKeyDown()) {
            LinkManager.unlinkAll(cart, level);
            send(player, "Cart unlinked");
            event.setCanceled(true);
        }
    }

    private void send(Player player, String msg) {
        if (player instanceof ServerPlayer sp) {
            sp.displayClientMessage(Component.literal(msg), true);
        }
    }
}