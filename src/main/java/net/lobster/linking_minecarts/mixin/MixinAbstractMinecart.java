package net.lobster.linking_minecarts.mixin;

import net.lobster.linking_minecarts.capability.CartLinkData;
import net.lobster.linking_minecarts.capability.ModCapabilities;
import net.lobster.linking_minecarts.util.RailPathTracer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.vehicle.AbstractMinecart;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Optional;
import java.util.UUID;

@Mixin(AbstractMinecart.class)
public abstract class MixinAbstractMinecart {

    // -------------------------------------------------------
    // Shadows — these must be abstract; Mixin resolves them
    // to the real methods in AbstractMinecart at load time.
    // -------------------------------------------------------

    @Shadow public abstract Level level();
    @Shadow public abstract UUID getUUID();
    @Shadow public abstract Vec3 getDeltaMovement();
    @Shadow public abstract void setDeltaMovement(Vec3 vel);
    @Shadow public abstract Vec3 position();
    @Shadow public abstract void setPos(double x, double y, double z);
    @Shadow public abstract boolean isRemoved();

    // -------------------------------------------------------
    // Tick tail — propagate head cart state down the chain
    // -------------------------------------------------------

    /**
     * Injected at the END of AbstractMinecart.tick().
     * Vanilla has fully processed this cart's movement for the tick by now —
     * rail snapping, slope, friction, block collisions — all done.
     * We read the final post-tick state and propagate it to the follower chain.
     * Only runs server-side and only on the HEAD of a chain (no leader).
     */
    @Inject(method = "tick", at = @At("TAIL"))
    private void linking_minecarts$onTickTail(CallbackInfo ci) {

        if (level().isClientSide()) return;
        if (!(level() instanceof ServerLevel serverLevel)) return;

        AbstractMinecart self = (AbstractMinecart) (Object) this;

        CartLinkData selfData = linking_minecarts$getCapData(self).orElse(null);
        if (selfData == null) return;
        if (selfData.getLeader() != null) return;   // not the head — skip
        if (selfData.getFollower() == null) return; // no followers — nothing to do

        linking_minecarts$propagateChain(self, getDeltaMovement(), serverLevel);
    }

    // -------------------------------------------------------
    // Chain propagation
    // -------------------------------------------------------

    /**
     * Walk the follower chain from the given leader.
     * Each follower is placed at exactly SPACING rail-distance behind its predecessor
     * and given the head cart's velocity.
     */
    @Unique
    private void linking_minecarts$propagateChain(AbstractMinecart leader,
                                                  Vec3 headVel,
                                                  ServerLevel serverLevel) {
        final double SPACING = 1.5;
        final int MAX_CHAIN = 64;

        AbstractMinecart current = leader;
        int steps = 0;

        while (steps++ < MAX_CHAIN) {

            CartLinkData data = linking_minecarts$getCapData(current).orElse(null);
            if (data == null || data.getFollower() == null) break;

            // ServerLevel.getEntity(UUID) is the correct call server-side
            Entity followerEntity = serverLevel.getEntity(data.getFollower());
            if (!(followerEntity instanceof AbstractMinecart follower)) break;
            if (follower.isRemoved()) break;

            Vec3 targetPos = RailPathTracer.findPositionBehind(current, SPACING, serverLevel);

            if (targetPos != null) {
                follower.setPos(targetPos.x, targetPos.y, targetPos.z);
            } else {
                // Fallback: no rail found — offset directly behind using head velocity
                Vec3 dir = headVel.lengthSqr() > 0.0001
                        ? headVel.normalize()
                        : current.position().subtract(follower.position()).lengthSqr() > 0.0001
                          ? current.position().subtract(follower.position()).normalize()
                          : Vec3.ZERO;

                if (dir.lengthSqr() > 0.0001) {
                    Vec3 fallback = current.position().subtract(dir.scale(SPACING));
                    follower.setPos(fallback.x, fallback.y, fallback.z);
                }
            }

            // All followers move at exactly the head cart's speed and direction
            follower.setDeltaMovement(headVel);

            current = follower;
        }
    }

    // -------------------------------------------------------
    // Push cancellation — suppress vanilla cart-on-cart collision
    // -------------------------------------------------------

    /**
     * Injected into AbstractMinecart.push(Entity).
     * Vanilla fires this when two entities overlap, applying a repulsion impulse.
     * We cancel it when the other cart is our direct leader or follower,
     * preventing the jitter and clipping between adjacent linked carts.
     */
    @Inject(method = "push", at = @At("HEAD"), cancellable = true)
    private void linking_minecarts$onPush(Entity other, CallbackInfo ci) {

        if (!(other instanceof AbstractMinecart otherCart)) return;

        AbstractMinecart self = (AbstractMinecart) (Object) this;

        CartLinkData data = linking_minecarts$getCapData(self).orElse(null);
        if (data == null) return;

        UUID otherUUID = otherCart.getUUID();

        if (otherUUID.equals(data.getLeader()) || otherUUID.equals(data.getFollower())) {
            ci.cancel();
        }
    }

    // -------------------------------------------------------
    // Helper
    // -------------------------------------------------------

    @Unique
    private static Optional<CartLinkData> linking_minecarts$getCapData(AbstractMinecart cart) {
        return cart.getCapability(ModCapabilities.CART_LINK).resolve();
    }
}