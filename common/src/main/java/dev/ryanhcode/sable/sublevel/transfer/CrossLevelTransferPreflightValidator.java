package dev.ryanhcode.sable.sublevel.transfer;

import java.util.EnumSet;
import java.util.Objects;

/**
 * Side-effect-free validator for cross-level transfer preflight facts.
 */
public final class CrossLevelTransferPreflightValidator {
    private CrossLevelTransferPreflightValidator() {
    }

    public static CrossLevelTransferValidation validate(final CrossLevelTransferPreflightFacts facts) {
        Objects.requireNonNull(facts, "facts");

        final EnumSet<CrossLevelTransferFailure> failures = EnumSet.noneOf(CrossLevelTransferFailure.class);

        if (facts.sourceDimension().equals(facts.targetDimension())) {
            failures.add(CrossLevelTransferFailure.SAME_LEVEL);
        }
        if (facts.sourceRemoved()) {
            failures.add(CrossLevelTransferFailure.SOURCE_REMOVED);
        }
        if (!facts.sourceContainerAvailable()) {
            failures.add(CrossLevelTransferFailure.SOURCE_CONTAINER_UNAVAILABLE);
        }
        if (!facts.targetContainerAvailable()) {
            failures.add(CrossLevelTransferFailure.TARGET_CONTAINER_UNAVAILABLE);
        }
        if (!facts.targetPhysicsAvailable()) {
            failures.add(CrossLevelTransferFailure.TARGET_PHYSICS_UNAVAILABLE);
        }
        if (facts.duplicateTargetUuid()) {
            failures.add(CrossLevelTransferFailure.DUPLICATE_TARGET_UUID);
        }
        if (facts.dependenciesPresent()) {
            failures.add(CrossLevelTransferFailure.DEPENDENCIES_PRESENT);
        }
        if (facts.activeKinematicContraption()) {
            failures.add(CrossLevelTransferFailure.ACTIVE_KINEMATIC_CONTRAPTION);
        }
        if (!facts.compatibleSectionLayout()) {
            failures.add(CrossLevelTransferFailure.INCOMPATIBLE_SECTION_LAYOUT);
        }
        if (facts.targetSlotOccupied()) {
            failures.add(CrossLevelTransferFailure.TARGET_SLOT_OCCUPIED);
        }
        if (facts.entitiesPresent()) {
            failures.add(CrossLevelTransferFailure.ENTITIES_PRESENT);
        }
        if (facts.transactionConflict()) {
            failures.add(CrossLevelTransferFailure.TRANSACTION_CONFLICT);
        }
        if (!facts.snapshotAvailable()) {
            failures.add(CrossLevelTransferFailure.SNAPSHOT_UNAVAILABLE);
        }

        return failures.isEmpty() ? CrossLevelTransferValidation.accepted() :
                CrossLevelTransferValidation.rejected(failures);
    }
}
