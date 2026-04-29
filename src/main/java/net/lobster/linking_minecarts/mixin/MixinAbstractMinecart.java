package net.lobster.linking_minecarts.mixin;

import net.lobster.linking_minecarts.capability.CartLinkData;
import net.lobster.linking_minecarts.capability.ModCapabilities;
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

    private static final double SPACING            = 1.5;
    private static final double SPRING             = 0.12;
    private static final double CORRECTION_THRESHOLD = 0.3;
    private static final double CATCHUP_THRESHOLD  = SPACING * 1.8;

    // -------------------------------------------------------
    // Tick tail
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

        linking_minecarts$propagate(self, serverLevel);
    }

    // -------------------------------------------------------
    // Speed propagation
    // -------------------------------------------------------

    /**
     * Propagates the leader's SPEED (not velocity vector) down the chain.
     
     * Each follower keeps its own velocity direction — the direction vanilla
     * computed for it based on whichever rail block it's currently on.
     * We only set how fast it moves along that direction.
     
     * This means each cart correctly navigates its own section of track
     * independently, so carts on a bend turn correctly while carts still
     * on the straight approach at the correct speed without being pulled
     * sideways by the leader's direction.
     
     * Spring correction adjusts the follower's speed slightly when spacing
     * drifts, expressed as a signed scalar along the follower's own travel axis.
     */
    @Unique
    private void linking_minecarts$propagate(AbstractMinecart leader,
                                             ServerLevel level) {

        AbstractMinecart current = leader;
        int steps = 0;

        while (steps++ < 64) {

            CartLinkData data = linking_minecarts$getCap(current).orElse(null);
            if (data == null || data.getFollower() == null) break;

            Entity entity = level.getEntity(data.getFollower());
            if (!(entity instanceof AbstractMinecart follower)) break;
            if (follower.isRemoved()) break;

            Vec3 leaderVel   = current.getDeltaMovement();
            Vec3 followerVel = follower.getDeltaMovement();
            Vec3 leaderPos   = current.position();
            Vec3 followerPos = follower.position();

            // The scalar speed of the leader (always positive — sign handled below)
            double leaderSpeed = leaderVel.length();

            // --- Determine follower's travel direction ---
            // Use the follower's current velocity direction if it's moving.
            // If stationary, derive direction from chain geometry (leader → follower axis).
            Vec3 followerDir;
            if (followerVel.lengthSqr() > 0.00001) {
                followerDir = followerVel.normalize();
            } else if (leaderSpeed > 0.00001) {
                // Follower is stopped but leader is moving — point away from leader
                // along the chain. Vanilla will correct this to the actual rail
                // direction on the next tick.
                Vec3 axis = followerPos.subtract(leaderPos);
                followerDir = axis.lengthSqr() > 0.00001
                        ? axis.normalize()
                        : leaderVel.normalize().scale(-1);
            } else {
                // Both stopped — nothing to do
                current = follower;
                continue;
            }

            // --- Spring correction ---
            // Compute signed distance error along the chain axis.
            // We project the separation vector onto the follower's travel direction
            // to get a signed scalar: positive means follower is ahead of ideal,
            // negative means it's behind.
            Vec3 separation = followerPos.subtract(leaderPos);
            double dist = separation.length();
            double error = dist - SPACING; // + = too far apart, - = too close

            double speedCorrection = 0.0;
            if (Math.abs(error) > CORRECTION_THRESHOLD) {
                double strength = SPRING;
                if (dist > CATCHUP_THRESHOLD) strength *= 2.0;

                // If too far (error > 0): slow down (negative correction)
                // If too close (error < 0): speed up (positive correction)
                // We flip sign because correction should oppose the error.
                speedCorrection = -error * strength;
            }

            // --- Apply speed to follower's own direction ---
            double newSpeed = leaderSpeed + speedCorrection;

            // Clamp to avoid negative speed (reversing direction due to correction).
            // Followers should never be driven backward by the spring alone.
            newSpeed = Math.max(0.0, newSpeed);

            follower.setDeltaMovement(followerDir.scale(newSpeed));

            current = follower;
        }
    }

    // -------------------------------------------------------
    // Suppress collision between linked carts
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