package net.lobster.linking_minecarts.util;

import net.lobster.linking_minecarts.LinkingMinecarts;
import net.lobster.linking_minecarts.capability.CartLinkData;
import net.lobster.linking_minecarts.capability.ModCapabilities;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.vehicle.AbstractMinecart;
import net.minecraft.world.phys.AABB;

import java.util.*;

public class LinkManager {

    private static final double SEARCH_RADIUS = 3.0;

    // All carts that are part of at least one link.
    // CartTickHandler iterates this to find chains to process each tick.
    private static final Set<AbstractMinecart> ACTIVE = new HashSet<>();

    // =========================
    // PUBLIC API FOR TICK HANDLER
    // =========================

    /** Returns a snapshot of all currently linked carts. */
    public static Set<AbstractMinecart> getActiveCarts() {
        return Collections.unmodifiableSet(ACTIVE);
    }

    /** Removes carts that have been removed from the world. Called each tick. */
    public static void cleanActive() {
        ACTIVE.removeIf(c -> c == null || c.isRemoved());
    }

    // =========================
    // SERIES LINKING
    // =========================

    public static int linkSeries(AbstractMinecart start,
                                 AbstractMinecart end,
                                 ServerLevel level) {

        List<AbstractMinecart> path = findPath(start, end, level);
        if (path == null || path.size() < 2) return 0;

        int linked = 0;
        for (int i = 0; i < path.size() - 1; i++) {
            if (linkPair(path.get(i), path.get(i + 1))) linked++;
        }
        return linked;
    }

    // =========================
    // UNLINK
    // =========================

    public static void unlinkAll(AbstractMinecart cart, ServerLevel level) {

        get(cart).ifPresent(data -> {

            UUID leaderId = data.getLeader();
            if (leaderId != null) {
                var e = level.getEntity(leaderId);
                if (e instanceof AbstractMinecart leader) {
                    get(leader).ifPresent(d -> {
                        if (cart.getUUID().equals(d.getFollower())) d.setFollower(null);
                    });
                }
            }

            UUID followerId = data.getFollower();
            if (followerId != null) {
                var e = level.getEntity(followerId);
                if (e instanceof AbstractMinecart follower) {
                    get(follower).ifPresent(d -> {
                        if (cart.getUUID().equals(d.getLeader())) d.setLeader(null);
                    });
                }
            }

            data.clear();
            ACTIVE.remove(cart);
        });

        LinkingMinecarts.LOGGER.info("Unlinked cart: {}", cart.getUUID());
    }

    // =========================
    // PATH FINDING
    // =========================

    private static List<AbstractMinecart> findPath(AbstractMinecart start,
                                                   AbstractMinecart end,
                                                   ServerLevel level) {
        List<AbstractMinecart> path = new ArrayList<>();
        Set<UUID> visited = new HashSet<>();

        AbstractMinecart current = start;
        path.add(current);
        visited.add(current.getUUID());

        for (int steps = 0; steps < 64; steps++) {
            if (current.getUUID().equals(end.getUUID())) return path;

            AbstractMinecart next = findNearest(current, visited, level);
            if (next == null) return null;

            path.add(next);
            visited.add(next.getUUID());
            current = next;
        }

        return null;
    }

    private static AbstractMinecart findNearest(AbstractMinecart from,
                                                Set<UUID> visited,
                                                ServerLevel level) {
        AABB box = from.getBoundingBox().inflate(SEARCH_RADIUS);
        List<AbstractMinecart> candidates =
                level.getEntitiesOfClass(AbstractMinecart.class, box);

        AbstractMinecart best = null;
        double bestDist = Double.MAX_VALUE;

        for (AbstractMinecart c : candidates) {
            if (c == from || visited.contains(c.getUUID())) continue;

            double d = c.position().distanceTo(from.position());
            if (d < 0.1 || d > SEARCH_RADIUS) continue;

            if (d < bestDist) {
                bestDist = d;
                best = c;
            }
        }

        return best;
    }

    // =========================
    // HELPERS
    // =========================

    private static boolean linkPair(AbstractMinecart leader, AbstractMinecart follower) {

        boolean[] ok = {false};

        get(leader).ifPresent(dLeader -> get(follower).ifPresent(dFollower -> {

            if (dLeader.isFull() || dFollower.isFull()) return;
            if (follower.getUUID().equals(dLeader.getFollower())) return;

            dLeader.setFollower(follower.getUUID());
            dFollower.setLeader(leader.getUUID());

            ACTIVE.add(leader);
            ACTIVE.add(follower);

            ok[0] = true;

            LinkingMinecarts.LOGGER.info("Linked: {} -> {}", leader.getUUID(), follower.getUUID());
        }));

        return ok[0];
    }

    private static Optional<CartLinkData> get(AbstractMinecart cart) {
        return cart.getCapability(ModCapabilities.CART_LINK).resolve();
    }
}