package net.lobster.linking_minecarts.mixin;

import net.lobster.linking_minecarts.capability.CartLinkData;
import net.lobster.linking_minecarts.capability.ModCapabilities;
import net.lobster.linking_minecarts.util.RailPathTracer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.vehicle.AbstractMinecart;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Optional;

@Mixin(AbstractMinecart.class)
public abstract class MixinAbstractMinecart {

    // No @Shadow declarations at all.
    //
    // The methods we need (level, getUUID, getDeltaMovement, etc.) are all
    // inherited from Entity — the Mixin AP resolves them correctly in the tsrg
    // but doesn't write inherited shadows into the refmap, causing the load failure.
    //
    // Instead, we cast `this` to AbstractMinecart once and call everything
    // through that reference. Since this class IS AbstractMinecart at runtime,
    // the cast is always safe and the compiler resolves the calls normally
    // without any Mixin remapping involvement.

    // -------------------------------------------------------
    // Tick tail injection
    // -------------------------------------------------------

    @Inject(method = "tick", at = @At("TAIL"))
    private void linking_minecarts$onTickTail(CallbackInfo ci) {

        AbstractMinecart self = (AbstractMinecart) (Object) this;

        if (self.level().isClientSide()) return;
        if (!(self.level() instanceof ServerLevel serverLevel)) return;

        CartLinkData selfData = linking_minecarts$getCap(self).orElse(null);
        if (selfData == null) return;
        if (selfData.getLeader() != null) return;   // not the head
        if (selfData.getFollower() == null) return; // no chain

        linking_minecarts$propagate(self, self.getDeltaMovement(), serverLevel);
    }

    // -------------------------------------------------------
    // Chain propagation
    // -------------------------------------------------------

    @Unique
    private void linking_minecarts$propagate(AbstractMinecart leader,
                                             Vec3 headVel,
                                             ServerLevel level) {
        final double SPACING = 1.5;
        final int MAX_CHAIN = 64;

        AbstractMinecart current = leader;

        for (int i = 0; i < MAX_CHAIN; i++) {

            CartLinkData data = linking_minecarts$getCap(current).orElse(null);
            if (data == null || data.getFollower() == null) break;

            Entity entity = level.getEntity(data.getFollower());
            if (!(entity instanceof AbstractMinecart follower)) break;
            if (follower.isRemoved()) break;

            // Place follower at exact rail-distance SPACING behind current
            Vec3 targetPos = RailPathTracer.findPositionBehind(current, SPACING, level);

            if (targetPos != null) {
                follower.setPos(targetPos.x, targetPos.y, targetPos.z);
            } else {
                // Fallback: no rail data — offset straight behind using head velocity
                Vec3 dir;
                if (headVel.lengthSqr() > 0.0001) {
                    dir = headVel.normalize();
                } else {
                    Vec3 delta = current.position().subtract(follower.position());
                    dir = delta.lengthSqr() > 0.0001 ? delta.normalize() : Vec3.ZERO;
                }
                if (dir.lengthSqr() > 0.0001) {
                    Vec3 fallback = current.position().subtract(dir.scale(SPACING));
                    follower.setPos(fallback.x, fallback.y, fallback.z);
                }
            }

            // All followers move at exactly the head's velocity
            follower.setDeltaMovement(headVel);

            current = follower;
        }
    }

    // -------------------------------------------------------
    // Push cancellation
    // -------------------------------------------------------

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

    // -------------------------------------------------------
    // Helper
    // -------------------------------------------------------

    @Unique
    private static Optional<CartLinkData> linking_minecarts$getCap(AbstractMinecart cart) {
        return cart.getCapability(ModCapabilities.CART_LINK).resolve();
    }
}