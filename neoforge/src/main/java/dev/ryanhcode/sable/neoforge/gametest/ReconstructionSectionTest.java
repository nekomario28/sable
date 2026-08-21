package dev.ryanhcode.sable.neoforge.gametest;

import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.api.physics.PhysicsPipeline;
import dev.ryanhcode.sable.api.physics.SubLevelReconstructionPhysicsSupport;
import dev.ryanhcode.sable.api.physics.SubLevelReconstructionSectionSupport;
import dev.ryanhcode.sable.api.sublevel.ServerSubLevelContainer;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.companion.math.BoundingBox3i;
import dev.ryanhcode.sable.companion.math.BoundingBox3ic;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.sublevel.plot.LevelPlot;
import dev.ryanhcode.sable.sublevel.system.SubLevelPhysicsSystem;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import org.joml.Vector3d;

import static dev.ryanhcode.sable.neoforge.gametest.SableTestHelper.absolutePosition;
import static dev.ryanhcode.sable.neoforge.gametest.SableTestHelper.removeSubLevel;
import static dev.ryanhcode.sable.neoforge.gametest.SableTestHelper.spawnSingleBlockSubLevel;

@GameTestHolder(Sable.MOD_ID)
@PrefixGameTestTemplate(false)
public final class ReconstructionSectionTest {
    private ReconstructionSectionTest() {
    }

    @GameTest(template = "physicstest.gravity")
    public static void ownerAwareSectionAcquireVerifyRollback(final GameTestHelper helper) {
        final ServerLevel level = helper.getLevel();
        final ServerSubLevelContainer container = SubLevelContainer.getContainer(level);
        if (container == null) {
            helper.fail("Sub-level container is unavailable");
            return;
        }

        final SubLevelPhysicsSystem physicsSystem = container.physicsSystem();
        if (physicsSystem == null) {
            helper.fail("Sub-level physics system is unavailable");
            return;
        }

        final PhysicsPipeline pipeline = physicsSystem.getPipeline();
        if (!(pipeline instanceof final SubLevelReconstructionSectionSupport support)) {
            helper.fail("Physics pipeline does not expose reconstruction section support");
            return;
        }
        if (!(pipeline instanceof final SubLevelReconstructionPhysicsSupport physicsSupport)) {
            helper.fail("Physics pipeline does not expose reconstruction capability evidence");
            return;
        }
        final SubLevelReconstructionPhysicsSupport.Capabilities capabilities = physicsSupport.reconstructionCapabilities();
        if (!capabilities.exactSectionRollback()) {
            helper.fail("Rapier reconstruction exact section rollback capability is unavailable");
            return;
        }

        final ServerSubLevel subLevel = spawnSingleBlockSubLevel(
                container,
                absolutePosition(helper, new Vector3d(2.5, 4.0, 2.5)),
                Blocks.DIAMOND_BLOCK.defaultBlockState()
        );
        final LevelPlot plot = subLevel.getPlot();
        final BoundingBox3ic currentBounds = plot.getBoundingBox();
        final BoundingBox3i originalBounds = new BoundingBox3i(currentBounds);
        final SectionPos occupiedSection = SectionPos.of(BlockPos.containing(
                originalBounds.minX(),
                originalBounds.minY(),
                originalBounds.minZ()
        ));

        int targetSectionX = plot.getChunkMax().x;
        ChunkPos targetChunk = new ChunkPos(targetSectionX, occupiedSection.z());
        if (plot.getChunk(plot.toLocal(targetChunk)) != null) {
            targetSectionX = plot.getChunkMin().x;
            targetChunk = new ChunkPos(targetSectionX, occupiedSection.z());
        }
        if (plot.getChunk(plot.toLocal(targetChunk)) != null) {
            removeSubLevel(container, subLevel);
            helper.fail("No unloaded target chunk is available for reconstruction section test");
            return;
        }

        final SectionPos targetSection = SectionPos.of(
                targetSectionX,
                occupiedSection.y(),
                occupiedSection.z()
        );
        final BlockPos detachedSolid = new BlockPos(
                targetSection.minBlockX() + 1,
                targetSection.minBlockY() + 1,
                targetSection.minBlockZ() + 1
        );
        final BoundingBox3i expandedBounds = new BoundingBox3i(
                Math.min(originalBounds.minX(), targetSection.minBlockX()),
                Math.min(originalBounds.minY(), targetSection.minBlockY()),
                Math.min(originalBounds.minZ(), targetSection.minBlockZ()),
                Math.max(originalBounds.maxX(), targetSection.maxBlockX()),
                Math.max(originalBounds.maxY(), targetSection.maxBlockY()),
                Math.max(originalBounds.maxZ(), targetSection.maxBlockZ())
        );

        SubLevelReconstructionSectionSupport.ReconstructionSectionReservation reservation = null;
        try {
            plot.setBoundingBox(expandedBounds);
            pipeline.onStatsChanged(subLevel);

            reservation = support.acquireReconstructionSection(
                    subLevel,
                    targetSection,
                    (x, y, z) -> x == detachedSolid.getX()
                            && y == detachedSolid.getY()
                            && z == detachedSolid.getZ()
                            ? Blocks.DIAMOND_BLOCK.defaultBlockState()
                            : Blocks.AIR.defaultBlockState()
            );
            if (!reservation.open()
                    || reservation.ownerRuntimeId() != subLevel.getRuntimeId()
                    || !reservation.sectionPos().equals(targetSection)) {
                helper.fail("Reconstruction section reservation metadata is inconsistent");
                return;
            }

            reservation.verify();
            reservation.rollback();
            if (reservation.open()) {
                helper.fail("Rolled-back reconstruction section reservation remained open");
                return;
            }
        } finally {
            if (reservation != null && reservation.open()) {
                reservation.rollback();
            }
            plot.setBoundingBox(originalBounds);
            pipeline.onStatsChanged(subLevel);
            removeSubLevel(container, subLevel);
        }

        helper.succeed();
    }
}
