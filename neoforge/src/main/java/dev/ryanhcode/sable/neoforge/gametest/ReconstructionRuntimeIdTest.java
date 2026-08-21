package dev.ryanhcode.sable.neoforge.gametest;

import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.api.physics.SubLevelReconstructionRuntimeIdSupport;
import dev.ryanhcode.sable.api.physics.mass.MassData;
import dev.ryanhcode.sable.api.sublevel.ServerSubLevelContainer;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.companion.math.BoundingBox3i;
import dev.ryanhcode.sable.companion.math.BoundingBox3ic;
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
public final class ReconstructionRuntimeIdTest {
    private ReconstructionRuntimeIdTest() {
    }

    @GameTest(template = "physicstest.gravity")
    public static void reservedRuntimeIdIsAdoptedWithoutSecondAllocation(final GameTestHelper helper) {
        final ServerLevel level = helper.getLevel();
        final ServerSubLevelContainer container = SubLevelContainer.getContainer(level);
        if (container == null) {
            helper.fail("Sub-level container is unavailable");
            return;
        }

        final SubLevelPhysicsSystem physicsSystem = container.physicsSystem();
        if (physicsSystem == null ||
                !(physicsSystem.getPipeline() instanceof final SubLevelReconstructionRuntimeIdSupport support)) {
            helper.fail("Physics pipeline does not expose runtime ID reconstruction support");
            return;
        }

        final int[] emptySlot = findEmptySlot(container);
        if (emptySlot == null) {
            helper.fail("No empty target plot available for detached construction test");
            return;
        }
        final int localPlotX = emptySlot[0];
        final int localPlotZ = emptySlot[1];
        final Vector2i origin = container.getOrigin();
        final int globalPlotX = origin.x + localPlotX;
        final int globalPlotZ = origin.y + localPlotZ;
        final int loadedBefore = container.getLoadedCount();
        final UUID detachedUuid = UUID.randomUUID();

        final SubLevelReconstructionRuntimeIdSupport.RuntimeIdReservation reservation =
                support.reserveReconstructionRuntimeId();
        final int reservedId = reservation.runtimeId();
        try {
            final ServerSubLevel detached = support.withReservedRuntimeId(
                    reservation,
                    () -> new ServerSubLevel(level, globalPlotX, globalPlotZ, new Pose3d())
            );
            detached.setUniqueId(detachedUuid);

            if (detached.getRuntimeId() != reservedId) {
                helper.fail("Detached ServerSubLevel did not adopt the reserved runtime ID");
                return;
            }
            if (!detachedUuid.equals(detached.getUniqueId())) {
                helper.fail("Detached ServerSubLevel did not retain the restored UUID");
                return;
            }

            final SubLevelReconstructionMassSnapshot mass = authoritativeMass();
            detached.restoreDetachedMassData(mass);
            if (!sameMass(detached.getMassTracker(), mass)) {
                helper.fail("Detached ServerSubLevel did not retain authoritative mass exactly");
                return;
            }
            boolean secondMassRestoreRejected = false;
            try {
                detached.restoreDetachedMassData(mass);
            } catch (final IllegalStateException expected) {
                secondMassRestoreRejected = true;
            }
            if (!secondMassRestoreRejected) {
                helper.fail("Detached ServerSubLevel accepted a second mass initialization");
                return;
            }

            final int minX = detached.getPlot().getChunkMin().getMinBlockX();
            final int minZ = detached.getPlot().getChunkMin().getMinBlockZ();
            final int y = level.getMinBuildHeight();
            final BoundingBox3i bounds = new BoundingBox3i(minX, y, minZ, minX + 1, y + 1, minZ + 1);
            detached.getPlot().setBoundingBox(bounds);
            if (!sameBounds(detached.getPlot().getBoundingBox(), bounds)) {
                helper.fail("Detached ServerSubLevel did not retain decoded plot bounds exactly");
                return;
            }

            if (container.getLoadedCount() != loadedBefore ||
                    container.getSubLevel(localPlotX, localPlotZ) != null ||
                    container.getSubLevel(detachedUuid) != null ||
                    !detached.getPlot().getLoadedChunks().isEmpty()) {
                helper.fail("Detached ServerSubLevel construction, mass or bounds restore published state");
                return;
            }

            boolean unrelatedAllocationRejected = false;
            try {
                physicsSystem.getNextRuntimeID();
            } catch (final IllegalStateException expected) {
                unrelatedAllocationRejected = true;
            }
            if (!unrelatedAllocationRejected) {
                helper.fail("Open reconstruction reservation allowed an unrelated runtime ID allocation");
                return;
            }

            boolean secondReservationRejected = false;
            try {
                support.reserveReconstructionRuntimeId();
            } catch (final IllegalStateException expected) {
                secondReservationRejected = true;
            }
            if (!secondReservationRejected) {
                helper.fail("Open reconstruction reservation allowed a second reservation");
                return;
            }
        } finally {
            if (reservation.open()) {
                reservation.rollback();
            }
        }

        final SubLevelReconstructionRuntimeIdSupport.RuntimeIdReservation reused =
                support.reserveReconstructionRuntimeId();
        try {
            if (reused.runtimeId() != reservedId) {
                helper.fail("Rolled-back runtime ID was not exactly reusable");
                return;
            }
        } finally {
            if (reused.open()) {
                reused.rollback();
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

    private static boolean sameMass(final MassData actual, final MassData expected) {
        return actual != null
                && actual.getMass() == expected.getMass()
                && actual.getInverseMass() == expected.getInverseMass()
                && actual.getCenterOfMass() != null
                && expected.getCenterOfMass() != null
                && actual.getCenterOfMass().equals(expected.getCenterOfMass(), 0.0)
                && actual.getInertiaTensor().equals(expected.getInertiaTensor(), 0.0)
                && actual.getInverseInertiaTensor().equals(expected.getInverseInertiaTensor(), 0.0);
    }

    private static boolean sameBounds(final BoundingBox3ic actual, final BoundingBox3ic expected) {
        return actual.minX() == expected.minX()
                && actual.minY() == expected.minY()
                && actual.minZ() == expected.minZ()
                && actual.maxX() == expected.maxX()
                && actual.maxY() == expected.maxY()
                && actual.maxZ() == expected.maxZ();
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
