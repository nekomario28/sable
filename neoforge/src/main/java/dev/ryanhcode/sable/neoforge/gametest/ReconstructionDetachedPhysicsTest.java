package dev.ryanhcode.sable.neoforge.gametest;

import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.api.sublevel.ServerSubLevelContainer;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.companion.math.Pose3d;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.sublevel.plot.PlotChunkHolder;
import dev.ryanhcode.sable.sublevel.plot.SubLevelReconstructionAttempt;
import dev.ryanhcode.sable.sublevel.plot.SubLevelReconstructionDetachedPhysics;
import dev.ryanhcode.sable.sublevel.plot.SubLevelReconstructionTransaction;
import dev.ryanhcode.sable.sublevel.storage.serialization.SubLevelData;
import dev.ryanhcode.sable.sublevel.storage.serialization.SubLevelSerializer;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

import static dev.ryanhcode.sable.neoforge.gametest.SableTestHelper.removeSubLevel;

@GameTestHolder(Sable.MOD_ID)
@PrefixGameTestTemplate(false)
public final class ReconstructionDetachedPhysicsTest {
    private ReconstructionDetachedPhysicsTest() {
    }

    @GameTest(template = "physicstest.gravity")
    public static void decodedPhysicsMaterializesAndRollsBackWithoutPublication(final GameTestHelper helper) {
        final ServerLevel targetLevel = helper.getLevel();
        final ServerLevel sourceLevel = targetLevel.getServer().getLevel(Level.NETHER);
        if (sourceLevel == null) {
            helper.fail("Source dimension is unavailable");
            return;
        }

        final ServerSubLevelContainer targetContainer = SubLevelContainer.getContainer(targetLevel);
        final ServerSubLevelContainer sourceContainer = SubLevelContainer.getContainer(sourceLevel);
        if (targetContainer == null || sourceContainer == null) {
            helper.fail("Source or target sub-level container is unavailable");
            return;
        }

        // Transit reconstructs across dimensions, therefore source and target belong to different
        // Rapier scenes. Use one local plot slot that is empty in both dimensions so the serialized
        // plot coordinates are unchanged while the target scene starts with no source-owned chunks.
        final int[] targetSlot = findCommonEmptySlotFromEnd(sourceContainer, targetContainer);
        if (targetSlot == null) {
            helper.fail("No plot slot is empty in both source and target dimensions");
            return;
        }
        final ServerSubLevel source = (ServerSubLevel) sourceContainer.allocateSubLevel(
                UUID.randomUUID(),
                targetSlot[0],
                targetSlot[1],
                new Pose3d()
        );
        final SubLevelData snapshot;
        final int targetPlotX;
        final int targetPlotZ;
        try {
            final ChunkPos centerChunk = source.getPlot().getCenterChunk();
            source.getPlot().newEmptyChunk(centerChunk);

            // A newly created plot chunk is owned by the PlotChunkHolder before the parent ServerLevel's
            // normal visible chunk map can resolve it. Populate that owned chunk directly, then run the
            // same holder-bounds and mass updates that ordinary block-change handling would perform.
            final BlockPos sourceBlock = source.getPlot().getCenterBlock();
            final PlotChunkHolder holder = Objects.requireNonNull(
                    source.getPlot().getChunkHolder(source.getPlot().toLocal(centerChunk)),
                    "source plot center chunk holder"
            );
            final LevelChunk chunk = holder.getChunk();
            final BlockState diamond = Blocks.DIAMOND_BLOCK.defaultBlockState();
            final BlockState previous = chunk.setBlockState(sourceBlock, diamond, false);
            if (previous == null || !previous.isAir()) {
                helper.fail("Source fixture did not start from an empty plot chunk");
                return;
            }
            holder.handleBlockChange(
                    sourceBlock.getX() & 15,
                    sourceBlock.getY(),
                    sourceBlock.getZ() & 15,
                    previous,
                    diamond
            );
            source.getPlot().updateBoundingBox();
            source.updateBoundingBox();
            source.forceUpdateGlobalBounds();
            sourceContainer.physicsSystem().updateMassDataFromBlockChange(
                    source,
                    sourceBlock,
                    previous,
                    diamond,
                    false
            );
            if (source.getSelfMassTracker().getCenterOfMass() == null) {
                helper.fail("Serialized source self-mass was unavailable after fixture mass update");
                return;
            }

            snapshot = SubLevelSerializer.toData(source, List.of());
            final CompoundTag plotTag = snapshot.fullTag().getCompound("plot");
            targetPlotX = plotTag.getInt("plot_x");
            targetPlotZ = plotTag.getInt("plot_z");
        } finally {
            removeSubLevel(sourceContainer, source);
        }

        if (targetPlotX != targetSlot[0]
                || targetPlotZ != targetSlot[1]
                || targetContainer.getSubLevel(targetPlotX, targetPlotZ) != null
                || targetContainer.getSubLevel(snapshot.uuid()) != null) {
            helper.fail("Serialized source target slot was not empty before reconstruction");
            return;
        }

        final SubLevelReconstructionAttempt firstAttempt = preparedAttempt(helper, targetLevel, snapshot);
        if (firstAttempt == null) {
            return;
        }
        final SubLevelReconstructionDetachedPhysics first = firstAttempt.materializeDetachedPhysics();
        final int firstRuntimeId = first.target().getRuntimeId();
        try {
            if (first.sectionCount() <= 0) {
                helper.fail("Detached physics materialization acquired no decoded sections");
                return;
            }
            if (!first.target().getUniqueId().equals(snapshot.uuid())
                    || targetContainer.getSubLevel(targetPlotX, targetPlotZ) != null
                    || targetContainer.getSubLevel(snapshot.uuid()) != null) {
                helper.fail("Detached physics materialization published Java container state");
                return;
            }
            first.verify();

            final Throwable[] offThreadFailure = new Throwable[1];
            final Thread offThreadRollback = new Thread(() -> {
                try {
                    first.rollback();
                } catch (final Throwable failure) {
                    offThreadFailure[0] = failure;
                }
            }, "sable-reconstruction-off-thread-rollback-test");
            offThreadRollback.start();
            try {
                offThreadRollback.join();
            } catch (final InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                helper.fail("Interrupted while testing off-thread rollback rejection");
                return;
            }
            if (!(offThreadFailure[0] instanceof IllegalStateException)
                    || firstAttempt.state() != SubLevelReconstructionTransaction.State.MATERIALIZING) {
                helper.fail("Off-thread rollback consumed detached reconstruction ownership");
                return;
            }
            first.verify();

            final SubLevelReconstructionTransaction.RollbackReport firstRollback = first.rollback();
            if (!firstRollback.successful()
                    || firstAttempt.state() != SubLevelReconstructionTransaction.State.ROLLED_BACK) {
                helper.fail("Detached physics rollback did not restore every owned resource: "
                        + firstRollback.cleanupFailures());
                return;
            }
            if (targetContainer.getSubLevel(targetPlotX, targetPlotZ) != null
                    || targetContainer.getSubLevel(snapshot.uuid()) != null) {
                helper.fail("Detached physics rollback changed Java container state");
                return;
            }
        } finally {
            rollbackIfMaterializing(firstAttempt, first);
        }

        final SubLevelReconstructionAttempt secondAttempt = preparedAttempt(helper, targetLevel, snapshot);
        if (secondAttempt == null) {
            return;
        }
        final SubLevelReconstructionDetachedPhysics second = secondAttempt.materializeDetachedPhysics();
        try {
            if (second.target().getRuntimeId() != firstRuntimeId) {
                helper.fail("Detached physics rollback did not restore the exact runtime-ID allocator position");
                return;
            }
            second.verify();
            final SubLevelReconstructionTransaction.RollbackReport secondRollback = second.rollback();
            if (!secondRollback.successful()
                    || secondAttempt.state() != SubLevelReconstructionTransaction.State.ROLLED_BACK) {
                helper.fail("Reacquired detached physics state did not roll back exactly: "
                        + secondRollback.cleanupFailures());
                return;
            }
            if (targetContainer.getSubLevel(targetPlotX, targetPlotZ) != null
                    || targetContainer.getSubLevel(snapshot.uuid()) != null) {
                helper.fail("Repeated detached physics lifecycle leaked Java container publication");
                return;
            }
        } finally {
            rollbackIfMaterializing(secondAttempt, second);
        }

        helper.succeed();
    }

    private static void rollbackIfMaterializing(
            final SubLevelReconstructionAttempt attempt,
            final SubLevelReconstructionDetachedPhysics materialized
    ) {
        if (attempt.state() != SubLevelReconstructionTransaction.State.MATERIALIZING) {
            return;
        }
        final SubLevelReconstructionTransaction.RollbackReport cleanup = materialized.rollback();
        if (!cleanup.successful()) {
            throw new IllegalStateException(
                    "Detached reconstruction GameTest cleanup failed: " + cleanup.cleanupFailures()
            );
        }
    }

    private static int[] findCommonEmptySlotFromEnd(
            final ServerSubLevelContainer first,
            final ServerSubLevelContainer second
    ) {
        final int sideLength = 1 << Math.min(first.getLogSideLength(), second.getLogSideLength());
        for (int x = sideLength - 1; x >= 0; x--) {
            for (int z = sideLength - 1; z >= 0; z--) {
                if (first.getSubLevel(x, z) == null && second.getSubLevel(x, z) == null) {
                    return new int[]{x, z};
                }
            }
        }
        return null;
    }

    private static SubLevelReconstructionAttempt preparedAttempt(
            final GameTestHelper helper,
            final ServerLevel level,
            final SubLevelData snapshot
    ) {
        final SubLevelReconstructionAttempt.Preparation preparation =
                SubLevelReconstructionAttempt.prepare(level, snapshot);
        if (!(preparation instanceof final SubLevelReconstructionAttempt.Prepared prepared)) {
            helper.fail("Reconstruction snapshot did not pass preparation: " + preparation);
            return null;
        }
        return prepared.attempt();
    }
}
