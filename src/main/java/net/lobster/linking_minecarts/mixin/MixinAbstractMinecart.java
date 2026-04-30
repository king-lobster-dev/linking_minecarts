package net.lobster.linking_minecarts.mixin;

import net.lobster.linking_minecarts.capability.CartLinkData;
import net.lobster.linking_minecarts.capability.ModCapabilities;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.vehicle.AbstractMinecart;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Optional;

@Mixin(AbstractMinecart.class)
public abstract class MixinAbstractMinecart {

    /**
     * Cancels vanilla's cart-on-cart repulsion impulse when the two carts
     * are directly linked. Without this, vanilla pushes linked carts apart
     * every tick, fighting the chain physics.

     * This is the only injection needed — chain physics is handled entirely
     * in CartTickHandler at Phase.END, after vanilla has finished all cart
     * ticks for the tick. That avoids the ordering problem where per-cart
     * injection velocities get overwritten by subsequent vanilla cart ticks.
     */
    @Inject(method = "push", at = @At("HEAD"), cancellable = true)
    private void linking_minecarts$onPush(Entity other, CallbackInfo ci) {

        if (!(other instanceof AbstractMinecart otherCart)) return;

        AbstractMinecart self = (AbstractMinecart) (Object) this;

        CartLinkData data = linking_minecarts$getCap(self).orElse(null);
        if (data == null) return;

        if (otherCart.getUUID().equals(data.getLeader())
                || otherCart.getUUID().equals(data.getFollower())) {
            ci.cancel();
        }
    }

    @Unique
    private static Optional<CartLinkData> linking_minecarts$getCap(AbstractMinecart cart) {
        return cart.getCapability(ModCapabilities.CART_LINK).resolve();
    }
}