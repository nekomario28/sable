package dev.ryanhcode.sable.sublevel.plot;

import dev.ryanhcode.sable.api.physics.PhysicsPipeline;
import dev.ryanhcode.sable.api.physics.SubLevelReconstructionRuntimeIdSupport;
import dev.ryanhcode.sable.api.physics.mass.MassData;
import dev.ryanhcode.sable.api.sublevel.ServerSubLevelContainer;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.sublevel.system.SubLevelPhysicsSystem;
import net.minecraft.server.level.ServerLevel;
import org.jetbrains.annotations.ApiStatus;
import org.joml.Vector2i;

import java.util.Objects;

/**
 * Transaction-owned target object created before any target container or physics publication.
 *
 * <p>The ordinary {@link ServerSubLevel} constructor is used inside the target physics pipeline's
 * reserved-runtime-ID adoption scope. The serialized local target slot is converted back to the
 * global plot coordinates expected by the constructor. The target UUID and validated authoritative
 * self-mass are restored immediately, then the authoritative container baseline is re-verified to
 * prove detached initialization itself published nothing.</p>
 *
 * <p>Acquisition begins the reconstruction transaction and leaves it MATERIALIZING. The runtime-ID
 * reservation is registered as both a rollback action and the transaction's single final commit
 * seal. No container slot, UUID index, chunk, entity, platform callback, physics body or SavedData
 * is mutated by this class.</p>
 */
@ApiStatus.Internal
public final class SubLevelReconstructionDetachedTarget {
    public enum Failure {
        ATTEMPT_NOT_PREPARED,
        BASELINE_DRIFT,
        CONTAINER_UNAVAILABLE,
        PHYSICS_SYSTEM_UNAVAILABLE,
        RUNTIME_ID_SUPPORT_UNAVAILABLE,
        TARGET_COORDINATE_OVERFLOW,
        RUNTIME_ID_RESERVATION_FAILED,
        TARGET_CONSTRUCTION_FAILED,
        RUNTIME_ID_MISMATCH,
        UUID_MISMATCH,
        MASS_RESTORE_FAILED,
        MASS_RESTORE_MISMATCH,
        CONSTRUCTION_PUBLISHED_STATE,
        MASS_INSTALL_PUBLISHED_STATE
    }

    public sealed interface Acquisition permits Acquired, Rejected, RolledBack {
        boolean acquired();
    }

    public record Acquired(SubLevelReconstructionDetachedTarget target) implements Acquisition {
        public Acquired {
            Objects.requireNonNull(target, "target");
        }

        @Override
        public boolean acquired() {
            return true;
        }
    }

    /** Rejection before the transaction begins; no rollback is required. */
    public record Rejected(Failure failure) implements Acquisition {
        public Rejected {
            Objects.requireNonNull(failure, "failure");
        }

        @Override
        public boolean acquired() {
            return false;
        }
    }

    /** Failure after materialization begins; rollback evidence is always returned. */
    public record RolledBack(
            Failure failure,
            Throwable cause,
            SubLevelReconstructionTransaction.RollbackReport rollbackReport
    ) implements Acquisition {
        public RolledBack {
            Objects.requireNonNull(failure, "failure");
            Objects.requireNonNull(cause, "cause");
            Objects.requireNonNull(rollbackReport, "rollbackReport");
        }

        @Override
        public boolean acquired() {
            return false;
        }
    }

    private static final class AcquisitionFailure extends RuntimeException {
        private final Failure failure;

        private AcquisitionFailure(final Failure failure, final String message) {
            super(message);
            this.failure = failure;
        }

        private AcquisitionFailure(final Failure failure, final String message, final Throwable cause) {
            super(message, cause);
            this.failure = failure;
        }
    }

    private final SubLevelReconstructionAttempt attempt;
    private final ServerSubLevel subLevel;
    private final SubLevelReconstructionRuntimeIdSupport.RuntimeIdReservation runtimeIdReservation;

    private SubLevelReconstructionDetachedTarget(
            final SubLevelReconstructionAttempt attempt,
            final ServerSubLevel subLevel,
            final SubLevelReconstructionRuntimeIdSupport.RuntimeIdReservation runtimeIdReservation
    ) {
        this.attempt = Objects.requireNonNull(attempt, "attempt");
        this.subLevel = Objects.requireNonNull(subLevel, "subLevel");
        this.runtimeIdReservation = Objects.requireNonNull(runtimeIdReservation, "runtimeIdReservation");
    }

    /**
     * Begins materialization by acquiring only a detached Java target object and its exclusive
     * runtime-ID lease.
     */
    public static Acquisition acquire(final SubLevelReconstructionAttempt attempt) {
        Objects.requireNonNull(attempt, "attempt");
        if (attempt.state() != SubLevelReconstructionTransaction.State.PREPARED) {
            return new Rejected(Failure.ATTEMPT_NOT_PREPARED);
        }

        final ServerLevel targetLevel = attempt.targetLevel();
        if (!attempt.baseline().verify(targetLevel).exact()) {
            return new Rejected(Failure.BASELINE_DRIFT);
        }

        final ServerSubLevelContainer container = SubLevelContainer.getContainer(targetLevel);
        if (container == null) {
            return new Rejected(Failure.CONTAINER_UNAVAILABLE);
        }

        final SubLevelPhysicsSystem physicsSystem;
        try {
            physicsSystem = container.physicsSystem();
        } catch (final AssertionError unavailable) {
            return new Rejected(Failure.PHYSICS_SYSTEM_UNAVAILABLE);
        }
        if (physicsSystem == null) {
            return new Rejected(Failure.PHYSICS_SYSTEM_UNAVAILABLE);
        }

        final PhysicsPipeline pipeline = physicsSystem.getPipeline();
        if (!(pipeline instanceof final SubLevelReconstructionRuntimeIdSupport runtimeIdSupport)) {
            return new Rejected(Failure.RUNTIME_ID_SUPPORT_UNAVAILABLE);
        }

        final SubLevelReconstructionPreflight.TargetSlot targetSlot = attempt.plan().targetSlot();
        final Vector2i origin = container.getOrigin();
        final int globalPlotX;
        final int globalPlotZ;
        try {
            globalPlotX = Math.addExact(origin.x, targetSlot.plotX());
            globalPlotZ = Math.addExact(origin.y, targetSlot.plotZ());
        } catch (final ArithmeticException overflow) {
            return new Rejected(Failure.TARGET_COORDINATE_OVERFLOW);
        }

        final SubLevelReconstructionTransaction transaction = attempt.transaction();
        transaction.beginMaterialization();

        try {
            final SubLevelReconstructionRuntimeIdSupport.RuntimeIdReservation reservation;
            try {
                reservation = runtimeIdSupport.reserveReconstructionRuntimeId();
            } catch (final RuntimeException reservationFailure) {
                throw new AcquisitionFailure(
                        Failure.RUNTIME_ID_RESERVATION_FAILED,
                        "Failed to reserve reconstruction runtime ID",
                        reservationFailure
                );
            }

            transaction.registerRollback("runtime-id-reservation", () -> {
                if (reservation.open()) {
                    reservation.rollback();
                }
            });
            transaction.registerCommitSeal("runtime-id-reservation", () -> {
                if (!reservation.open()) {
                    throw new IllegalStateException("Runtime ID reservation closed before final commit seal");
                }
                reservation.commit();
            });

            final ServerSubLevel detached;
            try {
                detached = runtimeIdSupport.withReservedRuntimeId(
                        reservation,
                        () -> new ServerSubLevel(
                                targetLevel,
                                globalPlotX,
                                globalPlotZ,
                                attempt.plan().pose()
                        )
                );
            } catch (final RuntimeException | AssertionError constructionFailure) {
                throw new AcquisitionFailure(
                        Failure.TARGET_CONSTRUCTION_FAILED,
                        "Detached target construction failed",
                        constructionFailure
                );
            }

            if (detached.getRuntimeId() != reservation.runtimeId()) {
                throw new AcquisitionFailure(
                        Failure.RUNTIME_ID_MISMATCH,
                        "Detached target did not adopt the reserved runtime ID"
                );
            }

            detached.setUniqueId(attempt.plan().uuid());
            if (!attempt.plan().uuid().equals(detached.getUniqueId())) {
                throw new AcquisitionFailure(Failure.UUID_MISMATCH, "Detached target UUID restoration failed");
            }

            if (!attempt.baseline().verify(targetLevel).exact()) {
                throw new AcquisitionFailure(
                        Failure.CONSTRUCTION_PUBLISHED_STATE,
                        "Detached target construction changed authoritative container state"
                );
            }

            try {
                detached.restoreDetachedMassData(attempt.massSnapshot());
            } catch (final RuntimeException | AssertionError massFailure) {
                throw new AcquisitionFailure(
                        Failure.MASS_RESTORE_FAILED,
                        "Detached target authoritative mass restoration failed",
                        massFailure
                );
            }
            if (!massMatches(detached.getMassTracker(), attempt.massSnapshot())) {
                throw new AcquisitionFailure(
                        Failure.MASS_RESTORE_MISMATCH,
                        "Detached target mass does not exactly match the validated snapshot"
                );
            }
            if (!attempt.baseline().verify(targetLevel).exact()) {
                throw new AcquisitionFailure(
                        Failure.MASS_INSTALL_PUBLISHED_STATE,
                        "Detached target mass restoration changed authoritative container state"
                );
            }

            return new Acquired(new SubLevelReconstructionDetachedTarget(
                    attempt,
                    detached,
                    reservation
            ));
        } catch (final AcquisitionFailure failure) {
            return new RolledBack(failure.failure, failure, transaction.rollback(failure));
        } catch (final RuntimeException | AssertionError unexpectedFailure) {
            final AcquisitionFailure wrapped = new AcquisitionFailure(
                    Failure.TARGET_CONSTRUCTION_FAILED,
                    "Unexpected detached target acquisition failure",
                    unexpectedFailure
            );
            return new RolledBack(wrapped.failure, wrapped, transaction.rollback(wrapped));
        }
    }

    private static boolean massMatches(final MassData actual, final MassData expected) {
        if (actual == null || expected == null
                || actual.getMass() != expected.getMass()
                || actual.getInverseMass() != expected.getInverseMass()) {
            return false;
        }
        return actual.getCenterOfMass() != null
                && expected.getCenterOfMass() != null
                && actual.getCenterOfMass().equals(expected.getCenterOfMass(), 0.0)
                && actual.getInertiaTensor().equals(expected.getInertiaTensor(), 0.0)
                && actual.getInverseInertiaTensor().equals(expected.getInverseInertiaTensor(), 0.0);
    }

    @ApiStatus.Internal
    SubLevelReconstructionAttempt attempt() {
        return this.attempt;
    }

    @ApiStatus.Internal
    ServerSubLevel subLevel() {
        return this.subLevel;
    }

    @ApiStatus.Internal
    SubLevelReconstructionRuntimeIdSupport.RuntimeIdReservation runtimeIdReservation() {
        return this.runtimeIdReservation;
    }
}
