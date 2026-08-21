package dev.ryanhcode.sable.neoforge.gametest;

import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.api.physics.SubLevelReconstructionBodySupport;
import dev.ryanhcode.sable.api.physics.SubLevelReconstructionPhysicsSupport;
import dev.ryanhcode.sable.api.physics.SubLevelReconstructionRuntimeIdSupport;
import dev.ryanhcode.sable.api.physics.SubLevelReconstructionSectionSupport;
import dev.ryanhcode.sable.api.physics.mass.MassData;
import dev.ryanhcode.sable.api.sublevel.ServerSubLevelContainer;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.companion.math.BoundingBox3i;
import dev.ryanhcode.sable.companion.math.Pose3d;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.sublevel.storage.serialization.SubLevelReconstructionMassSnapshot;
import dev.ryanhcode.sable.sublevel.system.SubLevelPhysicsSystem;
import net.minecraft.core.SectionPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import org.joml.Matrix3d;
import org.joml.Matrix3dc;
import org.joml.Quaterniond;
import org.joml.Quaterniondc;
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
    public static void provisionalBodyRollbackAndCommitAreExact(final GameTestHelper helper) {
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
        if (!capabilities.exactSectionRollback() || !capabilities.provisionalBodyLifecycle()) {
            helper.fail("Rapier reconstruction body lifecycle capability is unavailable");
            return;
        }

        final int[] emptySlot = findEmptySlotFromEnd(container, 0);
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
        ServerSubLevel detached = null;
        boolean committedBodyLive = false;
        try {
            detached = detachedTarget(
                    level,
                    runtimeIdSupport,
                    runtimeReservation,
                    globalPlotX,
                    globalPlotZ,
                    detachedUuid,
                    new Pose3d()
            );

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

            final ServerSubLevel provisionalTarget = detached;
            if (!rejects(() -> physicsSystem.getPipeline().add(provisionalTarget, provisionalTarget.logicalPose()))) {
                helper.fail("Open reconstruction body allowed publication through the normal live-body registry");
                return;
            }
            if (!rejects(() -> physicsSystem.getPipeline().remove(provisionalTarget))) {
                helper.fail("Open reconstruction body allowed removal through the normal live-body registry");
                return;
            }
            if (!rejects(() -> physicsSystem.getPipeline().onStatsChanged(provisionalTarget))) {
                helper.fail("Open reconstruction body allowed live mass/bounds mutation");
                return;
            }
            if (!rejects(() -> physicsSystem.getPipeline().teleport(
                    provisionalTarget,
                    new Vector3d(1.0, 2.0, 3.0),
                    new Quaterniond()
            ))) {
                helper.fail("Open reconstruction body allowed live teleport mutation");
                return;
            }
            if (!rejects(() -> physicsSystem.getPipeline().applyImpulse(
                    provisionalTarget,
                    new Vector3d(),
                    new Vector3d(1.0, 0.0, 0.0)
            ))) {
                helper.fail("Open reconstruction body allowed live impulse mutation");
                return;
            }
            if (!rejects(() -> physicsSystem.getPipeline().applyLinearAndAngularImpulse(
                    provisionalTarget,
                    new Vector3d(1.0, 0.0, 0.0),
                    new Vector3d(0.0, 1.0, 0.0),
                    true
            ))) {
                helper.fail("Open reconstruction body allowed live force/torque mutation");
                return;
            }
            if (!rejects(() -> physicsSystem.getPipeline().addLinearAndAngularVelocity(
                    provisionalTarget,
                    new Vector3d(1.0, 0.0, 0.0),
                    new Vector3d(0.0, 1.0, 0.0)
            ))) {
                helper.fail("Open reconstruction body allowed live velocity mutation");
                return;
            }
            if (!rejects(() -> physicsSystem.getPipeline().resetVelocity(provisionalTarget))) {
                helper.fail("Open reconstruction body allowed live velocity reset");
                return;
            }
            if (!rejects(() -> physicsSystem.getPipeline().wakeUp(provisionalTarget))) {
                helper.fail("Open reconstruction body allowed live wake mutation");
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
            bodyReservation.commit();
            if (bodyReservation.open()) {
                helper.fail("Committed reconstruction body reservation remained open");
                return;
            }
            committedBodyLive = true;

            physicsSystem.getPipeline().remove(detached);
            committedBodyLive = false;

            bodyReservation = bodySupport.acquireReconstructionBody(detached);
            bodyReservation.verify();
            bodyReservation.rollback();
            if (bodyReservation.open()) {
                helper.fail("Body could not be reacquired and rolled back after ordinary live removal");
                return;
            }

            if (container.getLoadedCount() != loadedBefore
                    || container.getSubLevel(emptySlot[0], emptySlot[1]) != null
                    || container.getSubLevel(detachedUuid) != null
                    || !detached.getPlot().getLoadedChunks().isEmpty()) {
                helper.fail("Reconstruction body lifecycle published Java container state");
                return;
            }
        } finally {
            if (bodyReservation != null && bodyReservation.open()) {
                bodyReservation.rollback();
            }
            if (committedBodyLive && detached != null) {
                physicsSystem.getPipeline().remove(detached);
            }
            if (runtimeReservation.open()) {
                runtimeReservation.rollback();
            }
        }

        helper.succeed();
    }

    @GameTest(template = "physicstest.gravity")
    public static void nonUnitOrientationIsNormalizedAtProviderBoundary(final GameTestHelper helper) {
        final ServerLevel level = helper.getLevel();
        final ServerSubLevelContainer container = SubLevelContainer.getContainer(level);
        if (container == null) {
            helper.fail("Sub-level container is unavailable");
            return;
        }

        final SubLevelPhysicsSystem physicsSystem = container.physicsSystem();
        if (physicsSystem == null
                || !(physicsSystem.getPipeline() instanceof final SubLevelReconstructionRuntimeIdSupport runtimeIdSupport)
                || !(physicsSystem.getPipeline() instanceof final SubLevelReconstructionBodySupport bodySupport)) {
            helper.fail("Physics pipeline does not expose reconstruction body prerequisites");
            return;
        }

        final int[] emptySlot = findEmptySlotFromEnd(container, 1);
        if (emptySlot == null) {
            helper.fail("No empty target plot available for reconstruction orientation test");
            return;
        }
        final Vector2i origin = container.getOrigin();
        final int globalPlotX = origin.x + emptySlot[0];
        final int globalPlotZ = origin.y + emptySlot[1];
        final SubLevelReconstructionRuntimeIdSupport.RuntimeIdReservation runtimeReservation =
                runtimeIdSupport.reserveReconstructionRuntimeId();
        SubLevelReconstructionBodySupport.ReconstructionBodyReservation bodyReservation = null;
        ServerSubLevel detached = null;
        boolean committedBodyLive = false;
        try {
            final Vector3d requestedPosition = new Vector3d(0.123456789, -0.234567891, 0.345678912);
            final Quaterniond requestedOrientation = new Quaterniond(0.3, -0.4, 0.2, 1.7);
            final Pose3d nonUnitPose = new Pose3d(
                    requestedPosition,
                    requestedOrientation,
                    new Vector3d(),
                    new Vector3d(1.0)
            );
            detached = detachedTarget(
                    level,
                    runtimeIdSupport,
                    runtimeReservation,
                    globalPlotX,
                    globalPlotZ,
                    UUID.randomUUID(),
                    nonUnitPose
            );
            if (Math.abs(detached.logicalPose().orientation().lengthSquared() - 1.0) <= 1.0E-6
                    || !sameVector(detached.logicalPose().position(), requestedPosition)) {
                helper.fail("Native-rounding fixture changed before reaching the provider boundary");
                return;
            }

            bodyReservation = bodySupport.acquireReconstructionBody(detached);
            bodyReservation.verify();
            bodyReservation.commit();
            if (bodyReservation.open()) {
                helper.fail("Normalized reconstruction body remained open after commit");
                return;
            }
            committedBodyLive = true;

            final Pose3d nativePose = new Pose3d();
            physicsSystem.getPipeline().readPose(detached, nativePose);
            if (sameVector(nativePose.position(), requestedPosition)) {
                helper.fail("Native-rounding fixture did not exercise Rapier Real precision narrowing");
                return;
            }
            if (!sameVector(detached.logicalPose().position(), nativePose.position())
                    || !sameVector(detached.lastPose().position(), nativePose.position())
                    || !sameOrientation(detached.logicalPose().orientation(), nativePose.orientation())
                    || !sameOrientation(detached.lastPose().orientation(), nativePose.orientation())) {
                helper.fail("Committed Java pose did not match the native-rounded Rapier pose");
                return;
            }
            final Vector3dc authoritativeCenter = detached.getMassTracker().getCenterOfMass();
            if (authoritativeCenter == null
                    || !sameVector(detached.logicalPose().rotationPoint(), authoritativeCenter)
                    || !sameVector(detached.lastPose().rotationPoint(), authoritativeCenter)) {
                helper.fail("Committed Java pose did not use the authoritative native center of mass");
                return;
            }

            physicsSystem.getPipeline().remove(detached);
            committedBodyLive = false;
        } finally {
            if (bodyReservation != null && bodyReservation.open()) {
                bodyReservation.rollback();
            }
            if (committedBodyLive && detached != null) {
                physicsSystem.getPipeline().remove(detached);
            }
            if (runtimeReservation.open()) {
                runtimeReservation.rollback();
            }
        }

        helper.succeed();
    }

    @GameTest(template = "physicstest.gravity")
    public static void openSectionOutsideBodyBoundsBlocksBodyRollback(final GameTestHelper helper) {
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
                || !(physicsSystem.getPipeline() instanceof final SubLevelReconstructionSectionSupport sectionSupport)) {
            helper.fail("Physics pipeline does not expose reconstruction body/section prerequisites");
            return;
        }

        final int[] emptySlot = findEmptySlotFromEnd(container, 2);
        if (emptySlot == null) {
            helper.fail("No empty target plot available for reconstruction body ordering test");
            return;
        }
        final Vector2i origin = container.getOrigin();
        final int globalPlotX = origin.x + emptySlot[0];
        final int globalPlotZ = origin.y + emptySlot[1];
        final UUID detachedUuid = UUID.randomUUID();

        final SubLevelReconstructionRuntimeIdSupport.RuntimeIdReservation runtimeReservation =
                runtimeIdSupport.reserveReconstructionRuntimeId();
        SubLevelReconstructionBodySupport.ReconstructionBodyReservation bodyReservation = null;
        SubLevelReconstructionSectionSupport.ReconstructionSectionReservation sectionReservation = null;
        try {
            final ServerSubLevel detached = detachedTarget(
                    level,
                    runtimeIdSupport,
                    runtimeReservation,
                    globalPlotX,
                    globalPlotZ,
                    detachedUuid,
                    new Pose3d()
            );
            bodyReservation = bodySupport.acquireReconstructionBody(detached);
            bodyReservation.verify();

            final SectionPos blocker = SectionPos.of(
                    detached.getPlot().getChunkMax().x,
                    level.getMaxSection() - 1,
                    detached.getPlot().getChunkMax().z
            );
            sectionReservation = sectionSupport.acquireReconstructionSection(
                    detached,
                    blocker,
                    (x, y, z) -> Blocks.AIR.defaultBlockState()
            );
            sectionReservation.verify();

            boolean rollbackRejected = false;
            try {
                bodyReservation.rollback();
            } catch (final IllegalStateException expected) {
                rollbackRejected = true;
            }
            if (!rollbackRejected || !bodyReservation.open() || !sectionReservation.open()) {
                helper.fail("Open reconstruction section did not keep body rollback fail-closed");
                return;
            }
            bodyReservation.verify();
            sectionReservation.verify();

            sectionReservation.rollback();
            if (sectionReservation.open()) {
                helper.fail("Blocking reconstruction section remained open after rollback");
                return;
            }
            bodyReservation.rollback();
            if (bodyReservation.open()) {
                helper.fail("Body did not roll back after the blocking section was removed");
                return;
            }
        } finally {
            if (sectionReservation != null && sectionReservation.open()) {
                sectionReservation.rollback();
            }
            if (bodyReservation != null && bodyReservation.open()) {
                bodyReservation.rollback();
            }
            if (runtimeReservation.open()) {
                runtimeReservation.rollback();
            }
        }

        helper.succeed();
    }

    private static boolean rejects(final Runnable operation) {
        try {
            operation.run();
            return false;
        } catch (final IllegalStateException expected) {
            return true;
        }
    }

    private static boolean sameOrientation(final Quaterniondc first, final Quaterniondc second) {
        final double tolerance = 1.0E-12;
        final boolean direct = Math.abs(first.x() - second.x()) <= tolerance
                && Math.abs(first.y() - second.y()) <= tolerance
                && Math.abs(first.z() - second.z()) <= tolerance
                && Math.abs(first.w() - second.w()) <= tolerance;
        final boolean negated = Math.abs(first.x() + second.x()) <= tolerance
                && Math.abs(first.y() + second.y()) <= tolerance
                && Math.abs(first.z() + second.z()) <= tolerance
                && Math.abs(first.w() + second.w()) <= tolerance;
        return direct || negated;
    }

    private static boolean sameVector(final Vector3dc first, final Vector3dc second) {
        return Math.abs(first.x() - second.x()) <= 1.0E-12
                && Math.abs(first.y() - second.y()) <= 1.0E-12
                && Math.abs(first.z() - second.z()) <= 1.0E-12;
    }

    private static ServerSubLevel detachedTarget(
            final ServerLevel level,
            final SubLevelReconstructionRuntimeIdSupport runtimeIdSupport,
            final SubLevelReconstructionRuntimeIdSupport.RuntimeIdReservation runtimeReservation,
            final int globalPlotX,
            final int globalPlotZ,
            final UUID detachedUuid,
            final Pose3d pose
    ) {
        final ServerSubLevel detached = runtimeIdSupport.withReservedRuntimeId(
                runtimeReservation,
                () -> new ServerSubLevel(level, globalPlotX, globalPlotZ, pose)
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
        return detached;
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

    private static int[] findEmptySlotFromEnd(
            final ServerSubLevelContainer container,
            final int ordinalFromEnd
    ) {
        if (ordinalFromEnd < 0) {
            throw new IllegalArgumentException("ordinalFromEnd must be non-negative");
        }
        final int sideLength = 1 << container.getLogSideLength();
        int remaining = ordinalFromEnd;
        for (int x = sideLength - 1; x >= 0; x--) {
            for (int z = sideLength - 1; z >= 0; z--) {
                if (container.getSubLevel(x, z) != null) {
                    continue;
                }
                if (remaining-- == 0) {
                    return new int[]{x, z};
                }
            }
        }
        return null;
    }
}
