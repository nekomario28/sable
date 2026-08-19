package dev.ryanhcode.sable.physics.impl.rapier;

import dev.ryanhcode.sable.api.block.BlockWithSubLevelCollisionCallback;
import dev.ryanhcode.sable.api.physics.SubLevelReconstructionSectionSupport.ReconstructionBlockStateView;
import dev.ryanhcode.sable.physics.chunk.VoxelNeighborhoodState;
import dev.ryanhcode.sable.physics.impl.rapier.collider.PhysicsColliderBlockGetter;
import dev.ryanhcode.sable.physics.impl.rapier.collider.RapierVoxelColliderBakery;
import dev.ryanhcode.sable.physics.impl.rapier.collider.RapierVoxelColliderData;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunkSection;
import org.jetbrains.annotations.ApiStatus;

import java.util.Objects;

/**
 * Encodes one reconstruction section without consulting the live target world.
 *
 * <p>All block and neighbor reads come from the immutable reconstruction view. The collider bakery
 * is reused only for the provider's existing BlockState-local collider cache/shape encoding.</p>
 */
@ApiStatus.Internal
final class RapierReconstructionSectionEncoder {
    private RapierReconstructionSectionEncoder() {
    }

    static int[] encode(
            final SectionPos sectionPos,
            final ReconstructionBlockStateView blockStates,
            final RapierVoxelColliderBakery colliderBakery
    ) {
        Objects.requireNonNull(sectionPos, "sectionPos");
        Objects.requireNonNull(blockStates, "blockStates");
        Objects.requireNonNull(colliderBakery, "colliderBakery");

        final int[] encoded = new int[LevelChunkSection.SECTION_SIZE];
        final PhysicsColliderBlockGetter shapeContext = new PhysicsColliderBlockGetter(colliderBakery.getLevel());
        final BlockPos.MutableBlockPos position = new BlockPos.MutableBlockPos();

        for (int bx = 0; bx < 16; bx++) {
            for (int bz = 0; bz < 16; bz++) {
                for (int by = 0; by < 16; by++) {
                    final int globalX = sectionPos.minBlockX() + bx;
                    final int globalY = sectionPos.minBlockY() + by;
                    final int globalZ = sectionPos.minBlockZ() + bz;
                    final BlockState blockState = Objects.requireNonNull(
                            blockStates.stateAt(globalX, globalY, globalZ),
                            "Reconstruction block-state view returned null"
                    );
                    final VoxelNeighborhoodState neighborhood = neighborhoodState(
                            shapeContext,
                            blockStates,
                            position.set(globalX, globalY, globalZ),
                            blockState
                    );
                    final RapierVoxelColliderData colliderData = colliderBakery.getPhysicsDataForBlock(blockState);
                    final int colliderValue = colliderData == null ? 0 : colliderData.handle() + 1;
                    final int index = bx + (bz << 4) + (by << 8);
                    encoded[index] = packBlockState(neighborhood, colliderValue);
                }
            }
        }

        return encoded;
    }

    private static VoxelNeighborhoodState neighborhoodState(
            final PhysicsColliderBlockGetter shapeContext,
            final ReconstructionBlockStateView blockStates,
            final BlockPos position,
            final BlockState blockState
    ) {
        if (VoxelNeighborhoodState.isLiquid(blockState)
                || BlockWithSubLevelCollisionCallback.hasCallback(blockState)) {
            return VoxelNeighborhoodState.CORNER;
        }

        if (!isSolid(shapeContext, position, blockState)) {
            return VoxelNeighborhoodState.EMPTY;
        }

        if (!isFullBlock(shapeContext, position, blockState)) {
            return VoxelNeighborhoodState.CORNER;
        }

        boolean allSolid = true;
        boolean cornerSolid = true;
        int bothSidesCount = 0;

        for (final Direction.Axis axis : Direction.Axis.VALUES) {
            final BlockPos negativePos = position.relative(Direction.get(Direction.AxisDirection.NEGATIVE, axis));
            final BlockPos positivePos = position.relative(Direction.get(Direction.AxisDirection.POSITIVE, axis));
            final BlockState negativeState = stateAt(blockStates, negativePos);
            final BlockState positiveState = stateAt(blockStates, positivePos);
            final boolean negativeSolid = isSolid(shapeContext, negativePos, negativeState)
                    && isFullBlock(shapeContext, negativePos, negativeState);
            final boolean positiveSolid = isSolid(shapeContext, positivePos, positiveState)
                    && isFullBlock(shapeContext, positivePos, positiveState);

            if (!negativeSolid || !positiveSolid) {
                allSolid = false;
            }
            if (negativeSolid && positiveSolid) {
                cornerSolid = false;
                bothSidesCount++;
            }
        }

        if (allSolid) {
            return VoxelNeighborhoodState.INTERIOR;
        }
        if (bothSidesCount == 1) {
            return VoxelNeighborhoodState.EDGE;
        }
        if (cornerSolid) {
            return VoxelNeighborhoodState.CORNER;
        }
        return VoxelNeighborhoodState.FACE;
    }

    private static boolean isSolid(
            final PhysicsColliderBlockGetter shapeContext,
            final BlockPos position,
            final BlockState blockState
    ) {
        shapeContext.setup(blockState);
        try {
            return VoxelNeighborhoodState.isSolid(shapeContext, position, blockState);
        } finally {
            shapeContext.setup(Blocks.AIR.defaultBlockState());
        }
    }

    private static boolean isFullBlock(
            final PhysicsColliderBlockGetter shapeContext,
            final BlockPos position,
            final BlockState blockState
    ) {
        shapeContext.setup(blockState);
        try {
            return VoxelNeighborhoodState.isFullBlock(shapeContext, position, blockState);
        } finally {
            shapeContext.setup(Blocks.AIR.defaultBlockState());
        }
    }

    private static BlockState stateAt(
            final ReconstructionBlockStateView blockStates,
            final BlockPos position
    ) {
        return Objects.requireNonNull(
                blockStates.stateAt(position.getX(), position.getY(), position.getZ()),
                "Reconstruction block-state view returned null"
        );
    }

    private static int packBlockState(final VoxelNeighborhoodState state, final int colliderId) {
        return ((int) state.byteRepresentation()) | (colliderId << 16);
    }
}
