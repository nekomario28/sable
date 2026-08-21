package dev.ryanhcode.sable.neoforge.gametest;

import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.api.physics.SubLevelReconstructionBodySupport;
import dev.ryanhcode.sable.api.physics.SubLevelReconstructionPhysicsSupport;
import dev.ryanhcode.sable.api.physics.SubLevelReconstructionRuntimeIdSupport;
import dev.ryanhcode.sable.api.physics.mass.MassData;
import dev.ryanhcode.sable.api.sublevel.ServerSubLevelContainer;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.companion.math.BoundingBox3i;
import dev.ryanhcode.sable.companion.math.Pose3d;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.sublevel.storage.serialization.SubLevelReconstructionMassSnapshot;
import dev.ryanhcode.sable.sublevel.system.SubLevelPhysicsSystem;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import org.joml.Matrix3d;
import org.joml.Matrix3dc;
import org.joml.Vector2i;
import org.joml.Vector3d;
import org.joml.Vector3dc;

import java.util.UUID;

@GameTestHolder(Sable.MOD_ID)
@PrefixGameTestTemplate(false)
public final class ReconstructionBodyTest {
    private ReconstructionBodyTest() {
    }

    @GameTest(template = "physicstest.gravity")
    public static void provisionalBodyAcquireVerifyRollbackIsExact(final GameTestHelper helper) {
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
        if (!(physicsSystem.getPipeline() instanceof final SubLevelReconstructionRuntimeIdSupport runtimeIdSupport)
                || !(physicsSystem.getPipeline() instanceof final SubLevelReconstructionBodySupport bodySupport)
                || !(physicsSystem.getPipeline() instanceof final SubLevelReconstructionPhysicsSupport physicsSupport)) {
            helper.fail("Physics pipeline does not expose reconstruction body prerequisites");
            return;
        }
        final SubLevelReconstructionPhysicsSupport.Capabilities capabilities = physicsSupport.reconstructionCapabilities();
        if (!capabilities.exactSectionRollback() || capabilities.provisionalBodyLifecycle()) {
            helper.fail("Rapier reconstruction capability boundary changed before body commit is available");
            return;
        }

        final int[] emptySlot = findEmptySlot(container);
        if (emptySlot == null) {
            helper.fail("No empty target plot available for reconstruction body test");
            return;
        }
        final Vector2i origin = container.getOrigin();
        final int globalPlotX = origin.x + emptySlot[0];
        final int globalPlotZ = origin.y + emptySlot[1];
        final int loadedBefore = container.getLoadedCount();
        final UUID detachedUuid = UUID.randomUUID();

        final SubLevelReconstructionRuntimeIdSupport.RuntimeIdReservation runtimeReservation =
                runtimeIdSupport.reserveReconstructionRuntimeId();
        SubLevelReconstructionBodySupport.ReconstructionBodyReservation bodyReservation = null;
        try {
            final ServerSubLevel detached = runtimeIdSupport.withReservedRuntimeId(
                    runtimeReservation,
                    () -> new ServerSubLevel(level, globalPlotX, globalPlotZ, new Pose3d())
            );
            detached.setUniqueId(detachedUuid);
            detached.restoreDetachedMassData(authoritativeMass());

            final int minX = detached.getPlot().getChunkMin().getMinBlockX();
            final int minZ = detached.getPlot().getChunkMin().getMinBlockZ();
            final int minY = level.getMinBuildHeight();
            detached.getPlot().setBoundingBox(new BoundingBox3i(
                    minX,
                    minY,
                    minZ,
                    minX + 1,
                    minY + 1,
                    minZ + 1
            ));

            if (container.getLoadedCount() != loadedBefore
                    || container.getSubLevel(emptySlot[0], emptySlot[1]) != null
                    || container.getSubLevel(detachedUuid) != null
                    || !detached.getPlot().getLoadedChunks().isEmpty()) {
                helper.fail("Detached reconstruction target published state before body acquisition");
                return;
            }

            bodyReservation = bodySupport.acquireReconstructionBody(detached);
            if (!bodyReservation.open() || bodyReservation.ownerRuntimeId() != detached.getRuntimeId()) {
                helper.fail("Reconstruction body reservation metadata is inconsistent");
                return;
            }
            bodyReservation.verify();

            boolean commitRejected = false;
            try {
                bodyReservation.commit();
            } catch (final IllegalStateException expected) {
                commitRejected = true;
            }
            if (!commitRejected || !bodyReservation.open()) {
                helper.fail("Unavailable reconstruction body commit did not remain fail-closed and rollbackable");
                return;
            }
            bodyReservation.verify();

            bodyReservation.rollback();
            if (bodyReservation.open()) {
                helper.fail("Rolled-back reconstruction body reservation remained open");
                return;
            }

            bodyReservation = bodySupport.acquireReconstructionBody(detached);
            bodyReservation.verify();
            bodyReservation.rollback();
            if (bodyReservation.open()) {
                helper.fail("Reacquired reconstruction body did not roll back cleanly");
                return;
            }

            if (container.getLoadedCount() != loadedBefore
                    || container.getSubLevel(emptySlot[0], emptySlot[1]) != null
                    || container.getSubLevel(detachedUuid) != null
                    || !detached.getPlot().getLoadedChunks().isEmpty()) {
                helper.fail("Provisional body acquire/rollback published Java container state");
                return;
            }
        } finally {
            if (bodyReservation != null && bodyReservation.open()) {
                bodyReservation.rollback();
            }
            if (runtimeReservation.open()) {
                runtimeReservation.rollback();
            }
        }

        helper.succeed();
    }

    private static SubLevelReconstructionMassSnapshot authoritativeMass() {
        final Matrix3d inertia = new Matrix3d(
                2.0, 0.0, 0.0,
                0.0, 3.0, 0.0,
                0.0, 0.0, 4.0
        );
        final Matrix3d inverse = new Matrix3d(inertia).invert();
        return SubLevelReconstructionMassSnapshot.capture(new MassData() {
            @Override
            public double getMass() {
                return 4.0;
            }

            @Override
            public double getInverseMass() {
                return 0.25;
            }

            @Override
            public Matrix3dc getInertiaTensor() {
                return inertia;
            }

            @Override
            public Matrix3dc getInverseInertiaTensor() {
                return inverse;
            }

            @Override
            public Vector3dc getCenterOfMass() {
                return new Vector3d(0.5, 0.5, 0.5);
            }
        });
    }

    private static int[] findEmptySlot(final ServerSubLevelContainer container) {
        final int sideLength = 1 << container.getLogSideLength();
        for (int x = 0; x < sideLength; x++) {
            for (int z = 0; z < sideLength; z++) {
                if (container.getSubLevel(x, z) == null) {
                    return new int[]{x, z};
                }
            }
        }
        return null;
    }
}
