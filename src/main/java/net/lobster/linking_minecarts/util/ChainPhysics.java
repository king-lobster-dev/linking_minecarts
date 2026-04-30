package net.lobster.linking_minecarts.util;

import net.lobster.linking_minecarts.LinkingMinecarts;
import net.lobster.linking_minecarts.capability.CartLinkData;
import net.lobster.linking_minecarts.capability.ModCapabilities;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.vehicle.AbstractMinecart;
import net.minecraft.world.entity.vehicle.MinecartFurnace;
import net.minecraft.world.phys.Vec3;

import java.lang.reflect.Field;
import java.util.*;

public class ChainPhysics {

    private static final double SPACING              = 1.5;
    private static final double SPRING               = 0.10;
    private static final double CORRECTION_THRESHOLD = 0.25;

    // -------------------------------------------------------
    // Entry point
    // -------------------------------------------------------

    public static void processChain(AbstractMinecart cart,
                                    ServerLevel level,
                                    Set<UUID> processedThisTick) {

        List<AbstractMinecart> members = collectChain(cart, level);
        if (members.size() < 2) return;

        UUID canonical = members.stream()
                .map(Entity::getUUID)
                .min(UUID::compareTo)
                .orElseThrow();

        if (processedThisTick.contains(canonical)) return;
        processedThisTick.add(canonical);

        List<AbstractMinecart> ordered = toOrderedChain(members, level);
        if (ordered.size() < 2) return;

        tickChain(ordered);
    }

    // -------------------------------------------------------
    // Chain physics
    // -------------------------------------------------------

    private static void tickChain(List<AbstractMinecart> ordered) {
        AbstractMinecart furnace = findActiveFurnace(ordered);
        double authority = computeAuthority(ordered, furnace);
        applyAuthority(ordered, authority);
        applySpring(ordered);
    }

    /**
     * Authority speed:
     *   - Active furnace → use its current speed directly.
     *   - No furnace → average of all cart speeds. Friction, powered rails,
     *     walls, and player input all affect the whole chain proportionally.
     */
    private static double computeAuthority(List<AbstractMinecart> chain,
                                           AbstractMinecart furnace) {
        if (furnace != null) {
            return furnace.getDeltaMovement().length();
        }
        double total = 0.0;
        for (AbstractMinecart c : chain) total += c.getDeltaMovement().length();
        return total / chain.size();
    }

    /**
     * Sets every cart's speed to `authority`.

     * For moving carts: preserve vanilla's computed direction, replace magnitude.

     * For stationary carts: derive direction from chain geometry so they start
     * moving in the correct direction. Vanilla snaps this to the actual rail
     * direction on the next tick, so the direction only needs to be roughly correct.

     * Direction logic for stationary carts:
     *   - If the cart has a leader in the ordered list: point toward the leader.
     *     (follower carts chase their leader)
     *   - If no leader (head cart): point away from the follower.
     *     (head cart moves in the direction the chain extends from it)
     *   - Both: leader direction takes priority.
     */
    private static void applyAuthority(List<AbstractMinecart> ordered, double authority) {

        if (authority < 0.00001) {
            // Authority is zero — zero out all carts so they stop together.
            // Vanilla friction will handle gradual deceleration naturally;
            // we don't need to set explicitly unless we want instant stop.
            // Leave carts alone so momentum and friction work naturally.
            return;
        }

        for (int i = 0; i < ordered.size(); i++) {
            AbstractMinecart cart = ordered.get(i);
            Vec3 vel = cart.getDeltaMovement();

            Vec3 direction;

            if (vel.lengthSqr() > 0.00001) {
                // Cart is already moving — use vanilla's direction
                direction = vel.normalize();
            } else {
                // Cart is stationary — derive direction from chain geometry
                direction = getChainDirection(ordered, i);
            }

            if (direction != null) {
                cart.setDeltaMovement(direction.scale(authority));
            }
        }
    }

    /**
     * Returns the travel direction for a stationary cart based on its position
     * relative to its neighbors in the ordered chain.

     * For a follower (has a leader at index i-1): point toward the leader.
     * For the head cart (index 0): point away from the first follower.

     * Uses full 3D vectors so sloped track is handled correctly.
     * Vanilla rail physics will align the result to the actual rail on the next tick.
     */
    private static Vec3 getChainDirection(List<AbstractMinecart> ordered, int index) {

        AbstractMinecart current = ordered.get(index);

        // Prefer pointing toward leader (index - 1)
        if (index > 0) {
            AbstractMinecart leader = ordered.get(index - 1);
            Vec3 dir = leader.position().subtract(current.position());
            if (dir.lengthSqr() > 0.00001) return dir.normalize();
        }

        // Fallback: point away from follower (index + 1)
        if (index < ordered.size() - 1) {
            AbstractMinecart follower = ordered.get(index + 1);
            Vec3 dir = current.position().subtract(follower.position());
            if (dir.lengthSqr() > 0.00001) return dir.normalize();
        }

        return null;
    }

    /**
     * Bidirectional spring correction between each adjacent pair.

     * If gap > SPACING: leader slows slightly, follower speeds up slightly.
     * If gap < SPACING: leader speeds up slightly, follower slows slightly.

     * Split 50/50. Speed clamped to >= 0 so spring never reverses direction.
     * Only applied to moving carts — stationary carts have just been given
     * direction and speed by applyAuthority; spring runs after that.
     */
    private static void applySpring(List<AbstractMinecart> ordered) {

        for (int i = 0; i < ordered.size() - 1; i++) {

            AbstractMinecart a = ordered.get(i);
            AbstractMinecart b = ordered.get(i + 1);

            double dist  = a.position().distanceTo(b.position());
            double error = dist - SPACING;

            if (Math.abs(error) < CORRECTION_THRESHOLD) continue;

            double delta = error * SPRING * 0.5;

            Vec3 velA = a.getDeltaMovement();
            if (velA.lengthSqr() > 0.00001) {
                double newSpeed = Math.max(0.0, velA.length() - delta);
                a.setDeltaMovement(velA.normalize().scale(newSpeed));
            }

            Vec3 velB = b.getDeltaMovement();
            if (velB.lengthSqr() > 0.00001) {
                double newSpeed = Math.max(0.0, velB.length() + delta);
                b.setDeltaMovement(velB.normalize().scale(newSpeed));
            }
        }
    }

    // -------------------------------------------------------
    // Chain collection
    // -------------------------------------------------------

    private static List<AbstractMinecart> collectChain(AbstractMinecart start,
                                                       ServerLevel level) {
        List<AbstractMinecart> result = new ArrayList<>();
        Set<UUID> visited = new HashSet<>();
        Queue<AbstractMinecart> queue = new ArrayDeque<>();

        queue.add(start);
        visited.add(start.getUUID());

        while (!queue.isEmpty()) {
            AbstractMinecart current = queue.poll();
            result.add(current);

            CartLinkData data = getCap(current).orElse(null);
            if (data == null) continue;

            for (UUID id : new UUID[]{data.getLeader(), data.getFollower()}) {
                if (id == null || visited.contains(id)) continue;
                visited.add(id);
                Entity e = level.getEntity(id);
                if (e instanceof AbstractMinecart m && !m.isRemoved()) queue.add(m);
            }
        }

        return result;
    }

    private static List<AbstractMinecart> toOrderedChain(List<AbstractMinecart> members,
                                                         ServerLevel level) {
        Map<UUID, AbstractMinecart> byId = new HashMap<>();
        for (AbstractMinecart c : members) byId.put(c.getUUID(), c);

        AbstractMinecart head = null;
        for (AbstractMinecart c : members) {
            CartLinkData d = getCap(c).orElse(null);
            if (d == null) continue;
            if (d.getLeader() == null || !byId.containsKey(d.getLeader())) {
                head = c;
                break;
            }
        }
        if (head == null) return members;

        List<AbstractMinecart> ordered = new ArrayList<>();
        Set<UUID> visited = new HashSet<>();
        AbstractMinecart current = head;

        while (current != null && !visited.contains(current.getUUID())) {
            ordered.add(current);
            visited.add(current.getUUID());
            CartLinkData d = getCap(current).orElse(null);
            UUID followerId = (d != null) ? d.getFollower() : null;
            current = (followerId != null) ? byId.get(followerId) : null;
        }

        return ordered;
    }

    // -------------------------------------------------------
    // Active furnace detection
    // -------------------------------------------------------

    private static AbstractMinecart findActiveFurnace(List<AbstractMinecart> chain) {
        for (AbstractMinecart c : chain) {
            if (isActiveFurnace(c)) return c;
        }
        return null;
    }

    private static Field furnaceFuelField;
    private static boolean fuelFieldResolved = false;

    private static boolean isActiveFurnace(AbstractMinecart cart) {
        if (!(cart instanceof MinecartFurnace furnace)) return false;

        if (!fuelFieldResolved) {
            fuelFieldResolved = true;
            for (String name : new String[]{"fuel", "f_38486_"}) {
                try {
                    Field f = MinecartFurnace.class.getDeclaredField(name);
                    f.setAccessible(true);
                    furnaceFuelField = f;
                    break;
                } catch (NoSuchFieldException ignored) {}
            }
            if (furnaceFuelField == null) {
                LinkingMinecarts.LOGGER.warn(
                        "Could not find MinecartFurnace fuel field. " +
                                "Falling back to speed-based active detection.");
            }
        }

        if (furnaceFuelField != null) {
            try {
                return ((int) furnaceFuelField.get(furnace)) > 0;
            } catch (IllegalAccessException ignored) {}
        }

        return furnace.getDeltaMovement().length() > 0.02;
    }

    // -------------------------------------------------------
    // Helper
    // -------------------------------------------------------

    private static Optional<CartLinkData> getCap(AbstractMinecart cart) {
        return cart.getCapability(ModCapabilities.CART_LINK).resolve();
    }
}