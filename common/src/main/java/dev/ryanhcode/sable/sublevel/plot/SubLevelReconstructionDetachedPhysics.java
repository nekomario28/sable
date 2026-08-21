package dev.ryanhcode.sable.sublevel.plot;

import dev.ryanhcode.sable.api.physics.PhysicsPipeline;
import dev.ryanhcode.sable.api.physics.SubLevelReconstructionBodySupport;
import dev.ryanhcode.sable.api.physics.SubLevelReconstructionRuntimeIdSupport;
import dev.ryanhcode.sable.api.physics.SubLevelReconstructionSectionSupport;
import dev.ryanhcode.sable.api.sublevel.ServerSubLevelContainer;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.companion.math.BoundingBox3i;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.sublevel.system.SubLevelPhysicsSystem;
import net.minecraft.core.SectionPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import org.jetbrains.annotations.ApiStatus;
import org.joml.Vector2i;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Transaction-owned reconstruction physics state that remains completely detached from the live
 * SubLevel container.
 *
 * <p>This is deliberately a rollback milestone, not a commit coordinator. It reserves the final
 * runtime ID, constructs the detached target, restores authoritative mass/bounds, acquires the
 * provisional body and every decoded native section, and verifies all of them without allocating a
 * live LevelChunk or publishing the target through the SubLevel container. The existing
 * reconstruction transaction owns every inverse in dependency-safe order.</p>
 */
@ApiStatus.Experimental
public final class SubLevelReconstructionDetachedPhysics {
    public static final class AcquisitionException extends RuntimeException {
        private final SubLevelReconstructionTransaction.RollbackReport rollbackReport;

        private AcquisitionException(
                final RuntimeException cause,
                final SubLevelReconstructionTransaction.RollbackReport rollbackReport
        ) {
            super("Detached reconstruction physics acquisition failed", cause);
            this.rollbackReport = Objects.requireNonNull(rollbackReport, "rollbackReport");
        }

        public SubLevelReconstructionTransaction.RollbackReport rollbackReport() {
            return this.rollbackReport;
        }
    }

    private static final class SectionSlot {
        private final SectionPos sectionPos;
        private SubLevelReconstructionSectionSupport.ReconstructionSectionReservation reservation;

        private SectionSlot(final SectionPos sectionPos) {
            this.sectionPos = Objects.requireNonNull(sectionPos, "sectionPos");
        }
    }

    private final SubLevelReconstructionAttempt attempt;
    private final ServerSubLevel target;
    private final SubLevelReconstructionRuntimeIdSupport.RuntimeIdReservation runtimeIdReservation;
    private final SubLevelReconstructionBodySupport.ReconstructionBodyReservation bodyReservation;
    private final List<SectionSlot> sections;

    private SubLevelReconstructionDetachedPhysics(
            final SubLevelReconstructionAttempt attempt,
            final ServerSubLevel target,
            final SubLevelReconstructionRuntimeIdSupport.RuntimeIdReservation runtimeIdReservation,
            final SubLevelReconstructionBodySupport.ReconstructionBodyReservation bodyReservation,
            final List<SectionSlot> sections
    ) {
        this.attempt = Objects.requireNonNull(attempt, "attempt");
        this.target = Objects.requireNonNull(target, "target");
        this.runtimeIdReservation = Objects.requireNonNull(runtimeIdReservation, "runtimeIdReservation");
        this.bodyReservation = Objects.requireNonNull(bodyReservation, "bodyReservation");
        this.sections = List.copyOf(Objects.requireNonNull(sections, "sections"));
    }

    static SubLevelReconstructionDetachedPhysics acquire(final SubLevelReconstructionAttempt attempt) {
        Objects.requireNonNull(attempt, "attempt");
        final ServerLevel level = attempt.targetLevel();
        if (!level.getServer().isSameThread()) {
            throw new IllegalStateException("Detached reconstruction physics must run on the owning server thread");
        }
        if (attempt.state() != SubLevelReconstructionTransaction.State.PREPARED) {
            throw new IllegalStateException("Reconstruction attempt is not prepared");
        }

        final SubLevelReconstructionContainerBaseline.Verification baselineBefore = attempt.baseline().verify(level);
        if (!baselineBefore.exact()) {
            throw new IllegalStateException("Reconstruction container baseline drifted before materialization: "
                    + baselineBefore.failures());
        }

        final ServerSubLevelContainer container = Objects.requireNonNull(
                SubLevelContainer.getContainer(level),
                "Sub-level container disappeared after reconstruction preflight"
        );
        final SubLevelPhysicsSystem physicsSystem = Objects.requireNonNull(
                container.physicsSystem(),
                "Sub-level physics system disappeared after reconstruction preflight"
        );
        final PhysicsPipeline pipeline = physicsSystem.getPipeline();
        if (!(pipeline instanceof final SubLevelReconstructionRuntimeIdSupport runtimeIdSupport)
                || !(pipeline instanceof final SubLevelReconstructionBodySupport bodySupport)
                || !(pipeline instanceof final SubLevelReconstructionSectionSupport sectionSupport)) {
            throw new IllegalStateException("Reconstruction physics operations disappeared after runtime preflight");
        }

        final SubLevelReconstructionDecodedBounds.Capture decodedBounds =
                SubLevelReconstructionDecodedBounds.compute(level, attempt.decodedPayload());
        if (!decodedBounds.accepted()) {
            throw new IllegalStateException("Decoded reconstruction bounds are unavailable: " + decodedBounds.failures());
        }
        final BoundingBox3i bounds = decodedBounds.bounds().orElseThrow();
        final SubLevelReconstructionDecodedBlockStateView blockStateView =
                SubLevelReconstructionDecodedBlockStateView.create(level, attempt.decodedPayload());
        final List<SectionSlot> sectionSlots = collectSectionSlots(level, attempt.decodedPayload());

        final SubLevelReconstructionTransaction transaction = attempt.transaction();
        transaction.beginMaterialization();
        transaction.registerRollback("container_baseline", () -> {
            final SubLevelReconstructionContainerBaseline.Verification verification = attempt.baseline().verify(level);
            if (!verification.exact()) {
                throw new IllegalStateException("Container baseline was not restored exactly: "
                        + verification.failures());
            }
        });

        final SubLevelReconstructionRuntimeIdSupport.RuntimeIdReservation[] runtimeHolder =
                new SubLevelReconstructionRuntimeIdSupport.RuntimeIdReservation[1];
        final SubLevelReconstructionBodySupport.ReconstructionBodyReservation[] bodyHolder =
                new SubLevelReconstructionBodySupport.ReconstructionBodyReservation[1];
        final boolean[] bodyAcquisitionCleanupFailed = new boolean[1];
        transaction.registerRollback("runtime_id", () -> {
            final SubLevelReconstructionRuntimeIdSupport.RuntimeIdReservation runtimeReservation = runtimeHolder[0];
            if (runtimeReservation == null || !runtimeReservation.open()) {
                return;
            }
            if (bodyAcquisitionCleanupFailed[0]) {
                throw new IllegalStateException(
                        "Cannot release reconstruction runtime ID after body acquisition cleanup failed"
                );
            }
            if (bodyHolder[0] != null && bodyHolder[0].open()) {
                throw new IllegalStateException(
                        "Cannot release reconstruction runtime ID while its provisional body remains open"
                );
            }
            for (final SectionSlot slot : sectionSlots) {
                if (slot.reservation != null && slot.reservation.open()) {
                    throw new IllegalStateException(
                            "Cannot release reconstruction runtime ID while a provisional section remains open at "
                                    + slot.sectionPos
                    );
                }
            }
            runtimeReservation.rollback();
        });

        transaction.registerRollback("body", () -> {
            if (bodyHolder[0] != null && bodyHolder[0].open()) {
                bodyHolder[0].rollback();
            }
        });

        for (final SectionSlot slot : sectionSlots) {
            transaction.registerRollback("section:" + slot.sectionPos.asLong(), () -> {
                if (slot.reservation != null && slot.reservation.open()) {
                    slot.reservation.rollback();
                }
            });
        }

        try {
            final SubLevelReconstructionRuntimeIdSupport.RuntimeIdReservation runtimeReservation =
                    runtimeIdSupport.reserveReconstructionRuntimeId();
            runtimeHolder[0] = runtimeReservation;

            final SubLevelReconstructionPreflight.TargetSlot targetSlot = attempt.plan().targetSlot();
            final Vector2i origin = container.getOrigin();
            final int globalPlotX = Math.addExact(origin.x, targetSlot.plotX());
            final int globalPlotZ = Math.addExact(origin.y, targetSlot.plotZ());
            final ServerSubLevel target = runtimeIdSupport.withReservedRuntimeId(
                    runtimeReservation,
                    () -> new ServerSubLevel(level, globalPlotX, globalPlotZ, attempt.plan().pose())
            );
            if (target.getRuntimeId() != runtimeReservation.runtimeId()) {
                throw new IllegalStateException("Detached target did not consume the reserved runtime ID");
            }
            target.setUniqueId(attempt.plan().uuid());
            target.restoreDetachedMassData(attempt.massSnapshot());
            target.getPlot().setBoundingBox(bounds);

            final SubLevelReconstructionBodySupport.ReconstructionBodyReservation bodyReservation;
            try {
                bodyReservation = bodySupport.acquireReconstructionBody(target);
            } catch (final RuntimeException | Error bodyAcquisitionFailure) {
                if (bodyAcquisitionFailure.getSuppressed().length != 0) {
                    bodyAcquisitionCleanupFailed[0] = true;
                }
                throw bodyAcquisitionFailure;
            }
            bodyHolder[0] = bodyReservation;

            for (final SectionSlot slot : sectionSlots) {
                slot.reservation = sectionSupport.acquireReconstructionSection(
                        target,
                        slot.sectionPos,
                        blockStateView
                );
            }

            final SubLevelReconstructionDetachedPhysics materialization =
                    new SubLevelReconstructionDetachedPhysics(
                            attempt,
                            target,
                            runtimeReservation,
                            bodyReservation,
                            sectionSlots
                    );
            materialization.verify();
            return materialization;
        } catch (final RuntimeException failure) {
            final SubLevelReconstructionTransaction.RollbackReport rollbackReport = transaction.rollback(failure);
            throw new AcquisitionException(failure, rollbackReport);
        } catch (final Error failure) {
            final SubLevelReconstructionTransaction.RollbackReport rollbackReport = transaction.rollback(failure);
            for (final SubLevelReconstructionTransaction.CleanupFailure cleanupFailure
                    : rollbackReport.cleanupFailures()) {
                failure.addSuppressed(cleanupFailure.cause());
            }
            throw failure;
        }
    }

    public ServerSubLevel target() {
        return this.target;
    }

    public int sectionCount() {
        return this.sections.size();
    }

    /** Read-only proof that every detached provider resource and the Java container baseline remain exact. */
    public void verify() {
        if (this.attempt.state() != SubLevelReconstructionTransaction.State.MATERIALIZING) {
            throw new IllegalStateException("Detached reconstruction physics is not materializing");
        }
        if (!this.runtimeIdReservation.open()
                || this.runtimeIdReservation.runtimeId() != this.target.getRuntimeId()) {
            throw new IllegalStateException("Detached reconstruction runtime-ID ownership is inconsistent");
        }
        if (!this.bodyReservation.open()
                || this.bodyReservation.ownerRuntimeId() != this.target.getRuntimeId()) {
            throw new IllegalStateException("Detached reconstruction body ownership is inconsistent");
        }
        this.bodyReservation.verify();
        for (final SectionSlot slot : this.sections) {
            final SubLevelReconstructionSectionSupport.ReconstructionSectionReservation reservation =
                    Objects.requireNonNull(slot.reservation, "decoded section reservation");
            if (!reservation.open()
                    || reservation.ownerRuntimeId() != this.target.getRuntimeId()
                    || !reservation.sectionPos().equals(slot.sectionPos)) {
                throw new IllegalStateException("Detached reconstruction section ownership is inconsistent at "
                        + slot.sectionPos);
            }
            reservation.verify();
        }

        final SubLevelReconstructionContainerBaseline.Verification baseline =
                this.attempt.baseline().verify(this.attempt.targetLevel());
        if (!baseline.exact()) {
            throw new IllegalStateException("Detached reconstruction unexpectedly changed container state: "
                    + baseline.failures());
        }
    }

    /** Rolls back every detached provider resource in dependency-safe reverse order. */
    public SubLevelReconstructionTransaction.RollbackReport rollback() {
        if (!this.attempt.targetLevel().getServer().isSameThread()) {
            throw new IllegalStateException("Detached reconstruction physics rollback must run on the owning server thread");
        }
        if (this.attempt.state() != SubLevelReconstructionTransaction.State.MATERIALIZING) {
            throw new IllegalStateException("Detached reconstruction physics is not materializing");
        }
        return this.attempt.transaction().rollback(
                new IllegalStateException("Detached reconstruction physics rollback requested")
        );
    }

    private static List<SectionSlot> collectSectionSlots(
            final ServerLevel level,
            final SubLevelReconstructionDecodedPayload payload
    ) {
        final List<SectionSlot> slots = new ArrayList<>();
        for (final SubLevelReconstructionDecodedPayload.DecodedChunk chunk : payload.chunks()) {
            final int chunkX = ChunkPos.getX(chunk.targetGlobalChunkKey());
            final int chunkZ = ChunkPos.getZ(chunk.targetGlobalChunkKey());
            for (final SubLevelReconstructionDecodedPayload.DecodedSection section : chunk.sections()) {
                final int sectionY = Math.addExact(level.getMinSection(), section.sectionIndex());
                slots.add(new SectionSlot(SectionPos.of(chunkX, sectionY, chunkZ)));
            }
        }
        return List.copyOf(slots);
    }
}
