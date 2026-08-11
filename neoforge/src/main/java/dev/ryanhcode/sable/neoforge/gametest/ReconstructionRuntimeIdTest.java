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

        final SubLevelReconstructionRuntimeIdSupport.RuntimeIdReservation reservation =
                support.reserveReconstructionRuntimeId();
        final int reservedId = reservation.runtimeId();
        try {
            final ServerSubLevel detached = support.withReservedRuntimeId(
                    reservation,
                    () -> new ServerSubLevel(level, 30_000, 30_001, new Pose3d())
            );
            if (detached.getRuntimeId() != reservedId) {
                helper.fail("Detached ServerSubLevel did not adopt the reserved runtime ID");
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
}
