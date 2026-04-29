package net.lobster.linking_minecarts.util;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.vehicle.AbstractMinecart;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseRailBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.RailShape;
import net.minecraft.world.phys.Vec3;

/**
 * Traces backwards along rail blocks from a given cart to find the world
 * position that is exactly `distance` blocks of rail-path behind it.
 *
 * This is what makes cart spacing correct on curves and slopes —
 * rather than a straight-line Euclidean offset (which drifts on corners),
 * we walk the actual rail geometry block by block.
 */
public class RailPathTracer {

    // How finely we subdivide each block for distance accumulation.
    // 8 steps per block = 0.125-block precision. Higher = smoother but more CPU.
    private static final int STEPS_PER_BLOCK = 8;
    private static final double STEP_SIZE = 1.0 / STEPS_PER_BLOCK;

    // Maximum blocks to trace before giving up
    private static final int MAX_BLOCKS = 32;

    /**
     * Starting from `leader`'s current position and walking backward along
     * the rails (opposite to its movement direction), returns the world Vec3
     * that is exactly `distance` rail-units behind the leader.
     *
     * Returns null if the rail path cannot be traced that far
     * (e.g. missing rail, off-track).
     */
    public static Vec3 findPositionBehind(AbstractMinecart leader,
                                          double distance,
                                          Level level) {

        Vec3 leaderPos = leader.position();
        Vec3 leaderVel = leader.getDeltaMovement();

        // Determine the backward direction from the leader's velocity.
        // If stationary, we can't determine direction — return null and let
        // the caller use its fallback.
        if (leaderVel.lengthSqr() < 0.00001) return null;

        Vec3 backward = leaderVel.normalize().scale(-1);

        // Start tracing from the leader's position
        Vec3 cursor = leaderPos;
        double accumulated = 0.0;

        BlockPos currentBlock = BlockPos.containing(cursor);

        for (int block = 0; block < MAX_BLOCKS; block++) {

            BlockState state = level.getBlockState(currentBlock);

            if (!(state.getBlock() instanceof BaseRailBlock)) {
                // Try one block down — carts sit slightly above the rail block
                BlockPos below = currentBlock.below();
                BlockState belowState = level.getBlockState(below);
                if (belowState.getBlock() instanceof BaseRailBlock) {
                    currentBlock = below;
                    state = belowState;
                } else {
                    return null; // lost the rail
                }
            }

            RailShape shape = ((BaseRailBlock) state.getBlock())
                    .getRailDirection(state, level, currentBlock, null);

            // Get the two exit directions for this rail segment
            int[][] exits = getExitOffsets(shape);
            if (exits == null) return null;

            // Walk in fine steps through this block along the backward direction
            Vec3 blockCenter = Vec3.atBottomCenterOf(currentBlock).add(0, 0, 0);
            Vec3 entry = cursor;

            // Step along the rail segment in fine increments
            for (int step = 0; step < STEPS_PER_BLOCK; step++) {

                // Interpolate across this block segment toward the backward exit
                Vec3 exit = getExitPoint(currentBlock, exits, backward);
                if (exit == null) exit = cursor.add(backward.scale(STEP_SIZE));

                Vec3 stepVec = exit.subtract(entry).normalize().scale(STEP_SIZE);
                Vec3 nextPos = cursor.add(stepVec);

                accumulated += STEP_SIZE;

                if (accumulated >= distance) {
                    // We've gone far enough — interpolate back to exact distance
                    double overshoot = accumulated - distance;
                    return nextPos.subtract(stepVec.normalize().scale(overshoot));
                }

                cursor = nextPos;
            }

            // Move to the next block in the backward direction
            BlockPos nextBlock = getNextBlock(currentBlock, shape, backward);
            if (nextBlock == null || nextBlock.equals(currentBlock)) return null;
            currentBlock = nextBlock;
        }

        return null; // exceeded max trace distance
    }

    /**
     * Returns the world-space exit point of a rail block in the given direction.
     * Each rail block has two ends; we pick the one closest to our travel direction.
     */
    private static Vec3 getExitPoint(BlockPos block, int[][] exits, Vec3 direction) {

        Vec3 center = Vec3.atCenterOf(block);

        Vec3 exit0 = center.add(exits[0][0] * 0.5, exits[0][1] * 0.5, exits[0][2] * 0.5);
        Vec3 exit1 = center.add(exits[1][0] * 0.5, exits[1][1] * 0.5, exits[1][2] * 0.5);

        Vec3 dir0 = exit0.subtract(center).normalize();
        Vec3 dir1 = exit1.subtract(center).normalize();

        // Pick the exit whose direction most closely matches our travel direction
        return dir0.dot(direction) >= dir1.dot(direction) ? exit0 : exit1;
    }

    /**
     * Determines the next BlockPos along the rail path in the given direction.
     */
    private static BlockPos getNextBlock(BlockPos current, RailShape shape, Vec3 direction) {

        int[][] exits = getExitOffsets(shape);
        if (exits == null) return null;

        Vec3 center = Vec3.atCenterOf(current);

        // Pick whichever exit is more aligned with our travel direction
        double dot0 = direction.dot(
                new Vec3(exits[0][0], exits[0][1], exits[0][2]).normalize());
        double dot1 = direction.dot(
                new Vec3(exits[1][0], exits[1][1], exits[1][2]).normalize());

        int[] chosen = dot0 >= dot1 ? exits[0] : exits[1];

        return current.offset(chosen[0], chosen[1], chosen[2]);
    }

    /**
     * Returns the two exit direction vectors [dx, dy, dz] for each rail shape.
     * These represent which two neighboring blocks a cart can travel between
     * when on this rail.
     *
     * Based on vanilla's RailShape geometry.
     */
    private static int[][] getExitOffsets(RailShape shape) {
        return switch (shape) {
            case NORTH_SOUTH      -> new int[][]{{ 0, 0, -1}, { 0, 0,  1}};
            case EAST_WEST        -> new int[][]{{ 1, 0,  0}, {-1, 0,  0}};
            case ASCENDING_NORTH  -> new int[][]{{ 0, 0, -1}, { 0,-1,  1}};
            case ASCENDING_SOUTH  -> new int[][]{{ 0, 0,  1}, { 0,-1, -1}};
            case ASCENDING_EAST   -> new int[][]{{ 1, 0,  0}, {-1,-1,  0}};
            case ASCENDING_WEST   -> new int[][]{{-1, 0,  0}, { 1,-1,  0}};
            case SOUTH_EAST       -> new int[][]{{ 0, 0,  1}, { 1, 0,  0}};
            case SOUTH_WEST       -> new int[][]{{ 0, 0,  1}, {-1, 0,  0}};
            case NORTH_WEST       -> new int[][]{{ 0, 0, -1}, {-1, 0,  0}};
            case NORTH_EAST       -> new int[][]{{ 0, 0, -1}, { 1, 0,  0}};
        };
    }
}