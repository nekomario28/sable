package dev.ryanhcode.sable.sublevel.plot;

import dev.ryanhcode.sable.api.physics.PhysicsPipeline;
import dev.ryanhcode.sable.api.physics.SubLevelReconstructionBodySupport;
import dev.ryanhcode.sable.api.physics.SubLevelReconstructionPhysicsSupport;
import dev.ryanhcode.sable.api.physics.SubLevelReconstructionRuntimeIdSupport;
import dev.ryanhcode.sable.api.physics.SubLevelReconstructionSectionSupport;
import dev.ryanhcode.sable.api.sublevel.ServerSubLevelContainer;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.sublevel.system.SubLevelPhysicsSystem;
import net.minecraft.server.level.ServerLevel;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Nullable;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;

/**
 * Mutation-free runtime capability gate for transactional SubLevel reconstruction.
 *
 * <p>Serialized-data validation alone is not enough: the target physics implementation must prove
 * that transaction-owned section state and the provisional target body can be restored exactly,
 * and must expose the operational runtime-ID, owner-aware section and provisional-body operations
 * that participate in rollback. Pipelines that only advertise capability booleans are rejected.</p>
 */
@ApiStatus.Experimental
public final class SubLevelReconstructionRuntimePreflight {
    private SubLevelReconstructionRuntimePreflight() {
    }

    public enum Failure {
        NOT_SERVER_THREAD,
        CONTAINER_UNAVAILABLE,
        PHYSICS_SYSTEM_UNAVAILABLE,
        RUNTIME_ID_RESERVATION_UNAVAILABLE,
        SECTION_OWNERSHIP_OPERATION_UNAVAILABLE,
        BODY_LIFECYCLE_OPERATION_UNAVAILABLE,
        EXACT_SECTION_ROLLBACK_UNAVAILABLE,
        PROVISIONAL_BODY_LIFECYCLE_UNAVAILABLE
    }

    public record Result(Set<Failure> failures) {
        public Result {
            Objects.requireNonNull(failures, "failures");
            final EnumSet<Failure> copy = EnumSet.noneOf(Failure.class);
            copy.addAll(failures);
            failures = Collections.unmodifiableSet(copy);
        }

        public boolean accepted() {
            return this.failures.isEmpty();
        }
    }

    /** Checks only current target runtime state and makes no world or physics mutation. */
    public static Result validate(final ServerLevel level) {
        Objects.requireNonNull(level, "level");
        if (!level.getServer().isSameThread()) {
            return rejected(Failure.NOT_SERVER_THREAD);
        }

        final ServerSubLevelContainer container = SubLevelContainer.getContainer(level);
        if (container == null) {
            return rejected(Failure.CONTAINER_UNAVAILABLE);
        }

        final SubLevelPhysicsSystem physicsSystem;
        try {
            physicsSystem = container.physicsSystem();
        } catch (final AssertionError unavailable) {
            return rejected(Failure.PHYSICS_SYSTEM_UNAVAILABLE);
        }
        if (physicsSystem == null) {
            return rejected(Failure.PHYSICS_SYSTEM_UNAVAILABLE);
        }

        final PhysicsPipeline pipeline = physicsSystem.getPipeline();
        final SubLevelReconstructionPhysicsSupport.Capabilities capabilities =
                pipeline instanceof final SubLevelReconstructionPhysicsSupport support
                        ? support.reconstructionCapabilities()
                        : null;
        final boolean runtimeIdReservationAvailable =
                pipeline instanceof SubLevelReconstructionRuntimeIdSupport;
        final boolean sectionOwnershipOperationAvailable =
                pipeline instanceof SubLevelReconstructionSectionSupport;
        final boolean bodyLifecycleOperationAvailable =
                pipeline instanceof SubLevelReconstructionBodySupport;
        return validateCapabilities(
                true,
                runtimeIdReservationAvailable,
                sectionOwnershipOperationAvailable,
                bodyLifecycleOperationAvailable,
                capabilities
        );
    }

    /**
     * Legacy package-private seam retained for the original capability matrix. It models both
     * newer operational interfaces as present; dedicated operation tests use explicit overloads.
     */
    static Result validateCapabilities(
            final boolean physicsSystemAvailable,
            final boolean runtimeIdReservationAvailable,
            @Nullable final SubLevelReconstructionPhysicsSupport.Capabilities capabilities
    ) {
        return validateCapabilities(
                physicsSystemAvailable,
                runtimeIdReservationAvailable,
                true,
                true,
                capabilities
        );
    }

    /**
     * Legacy section-operation seam retained for its existing test matrix. It models the body
     * operation as present; dedicated body-operation tests use the explicit overload.
     */
    static Result validateCapabilities(
            final boolean physicsSystemAvailable,
            final boolean runtimeIdReservationAvailable,
            final boolean sectionOwnershipOperationAvailable,
            @Nullable final SubLevelReconstructionPhysicsSupport.Capabilities capabilities
    ) {
        return validateCapabilities(
                physicsSystemAvailable,
                runtimeIdReservationAvailable,
                sectionOwnershipOperationAvailable,
                true,
                capabilities
        );
    }

    /** Package-private pure seam for executable operational capability tests. */
    static Result validateCapabilities(
            final boolean physicsSystemAvailable,
            final boolean runtimeIdReservationAvailable,
            final boolean sectionOwnershipOperationAvailable,
            final boolean bodyLifecycleOperationAvailable,
            @Nullable final SubLevelReconstructionPhysicsSupport.Capabilities capabilities
    ) {
        final EnumSet<Failure> failures = EnumSet.noneOf(Failure.class);
        if (!physicsSystemAvailable) {
            failures.add(Failure.PHYSICS_SYSTEM_UNAVAILABLE);
            return new Result(failures);
        }

        if (!runtimeIdReservationAvailable) {
            failures.add(Failure.RUNTIME_ID_RESERVATION_UNAVAILABLE);
        }
        if (!sectionOwnershipOperationAvailable) {
            failures.add(Failure.SECTION_OWNERSHIP_OPERATION_UNAVAILABLE);
        }
        if (!bodyLifecycleOperationAvailable) {
            failures.add(Failure.BODY_LIFECYCLE_OPERATION_UNAVAILABLE);
        }
        if (capabilities == null || !capabilities.exactSectionRollback()) {
            failures.add(Failure.EXACT_SECTION_ROLLBACK_UNAVAILABLE);
        }
        if (capabilities == null || !capabilities.provisionalBodyLifecycle()) {
            failures.add(Failure.PROVISIONAL_BODY_LIFECYCLE_UNAVAILABLE);
        }
        return new Result(failures);
    }

    private static Result rejected(final Failure failure) {
        return new Result(EnumSet.of(failure));
    }
}
