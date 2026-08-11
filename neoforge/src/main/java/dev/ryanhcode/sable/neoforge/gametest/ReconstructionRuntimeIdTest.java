package dev.ryanhcode.sable.neoforge.gametest;

import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.api.physics.SubLevelReconstructionRuntimeIdSupport;
import dev.ryanhcode.sable.api.sublevel.ServerSubLevelContainer;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.companion.math.Pose3d;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.sublevel.system.SubLevelPhysicsSystem;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import org.joml.Vector2i;

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
            if (container.getLoadedCount() != loadedBefore ||
                    container.getSubLevel(localPlotX, localPlotZ) != null ||
                    container.getSubLevel(detachedUuid) != null) {
                helper.fail("Detached ServerSubLevel construction published container state");
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
