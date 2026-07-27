package dev.ryanhcode.sable.sublevel.transfer;

import java.util.Objects;

/**
 * Combines read-only runtime observations with separately verified authoritative
 * evidence before invoking the normal typed preflight validator.
 */
public final class CrossLevelTransferPreflightAssembler {
    private CrossLevelTransferPreflightAssembler() {
    }

    /**
     * Produces complete preflight facts without weakening any runtime-observed blocker.
     *
     * @param runtimeFacts read-only runtime observations
     * @param evidence authoritative non-runtime evidence
     * @return complete immutable facts for validation
     */
    public static CrossLevelTransferPreflightFacts assemble(
            final CrossLevelTransferPreflightFacts runtimeFacts,
            final CrossLevelTransferAuthoritativePreflightEvidence evidence
    ) {
        Objects.requireNonNull(runtimeFacts, "runtimeFacts");
        Objects.requireNonNull(evidence, "evidence");

        return CrossLevelTransferPreflightFacts.builder(
                        runtimeFacts.sourceDimension(),
                        runtimeFacts.targetDimension()
                )
                .sourceRemoved(runtimeFacts.sourceRemoved())
                .sourceContainerAvailable(runtimeFacts.sourceContainerAvailable())
                .targetContainerAvailable(runtimeFacts.targetContainerAvailable())
                .targetPhysicsAvailable(runtimeFacts.targetPhysicsAvailable())
                .duplicateTargetUuid(!evidence.targetUuidUnique())
                .dependenciesPresent(runtimeFacts.dependenciesPresent())
                .activeKinematicContraption(runtimeFacts.activeKinematicContraption())
                .compatibleSectionLayout(runtimeFacts.compatibleSectionLayout())
                .targetSlotOccupied(runtimeFacts.targetSlotOccupied())
                .entitiesPresent(runtimeFacts.entitiesPresent())
                .transactionConflict(!evidence.transactionOwned())
                .snapshotAvailable(evidence.snapshotVerified())
                .build();
    }

    /**
     * Assembles and validates in one side-effect-free call.
     */
    public static CrossLevelTransferValidation validate(
            final CrossLevelTransferPreflightFacts runtimeFacts,
            final CrossLevelTransferAuthoritativePreflightEvidence evidence
    ) {
        return CrossLevelTransferPreflightValidator.validate(assemble(runtimeFacts, evidence));
    }
}
