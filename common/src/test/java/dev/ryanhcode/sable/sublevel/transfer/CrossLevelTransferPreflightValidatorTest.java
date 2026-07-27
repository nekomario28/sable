package dev.ryanhcode.sable.sublevel.transfer;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class CrossLevelTransferPreflightValidatorTest {
    @Test
    void omittedFactsFailClosed() {
        final CrossLevelTransferValidation result = CrossLevelTransferPreflightValidator.validate(
                CrossLevelTransferPreflightFacts.builder("minecraft:overworld", "starlance:space").build()
        );

        assertFalse(result.isAccepted());
        assertEquals(12, result.failures().size());
        assertTrue(result.hasFailure(CrossLevelTransferFailure.SOURCE_REMOVED));
        assertTrue(result.hasFailure(CrossLevelTransferFailure.TARGET_SLOT_OCCUPIED));
        assertTrue(result.hasFailure(CrossLevelTransferFailure.SNAPSHOT_UNAVAILABLE));
        assertFalse(result.hasFailure(CrossLevelTransferFailure.SAME_LEVEL));
    }

    @Test
    void explicitlySafeFactsAreAccepted() {
        assertTrue(CrossLevelTransferPreflightValidator.validate(safeFacts(
                "minecraft:overworld",
                "starlance:space"
        )).isAccepted());
    }

    @Test
    void sameDimensionIsReportedWithoutHidingOtherResults() {
        final CrossLevelTransferValidation sameDimension = CrossLevelTransferPreflightValidator.validate(
                safeFacts("minecraft:overworld", "minecraft:overworld")
        );

        assertFalse(sameDimension.isAccepted());
        assertEquals(1, sameDimension.failures().size());
        assertTrue(sameDimension.hasFailure(CrossLevelTransferFailure.SAME_LEVEL));

        final CrossLevelTransferPreflightFacts multipleBlockers = CrossLevelTransferPreflightFacts.builder(
                        "minecraft:overworld",
                        "minecraft:overworld"
                )
                .sourceRemoved(false)
                .sourceContainerAvailable(true)
                .targetContainerAvailable(true)
                .targetPhysicsAvailable(true)
                .duplicateTargetUuid(false)
                .dependenciesPresent(false)
                .activeKinematicContraption(false)
                .compatibleSectionLayout(true)
                .targetSlotOccupied(true)
                .entitiesPresent(false)
                .transactionConflict(false)
                .snapshotAvailable(false)
                .build();

        final CrossLevelTransferValidation multipleResult = CrossLevelTransferPreflightValidator.validate(multipleBlockers);
        assertEquals(3, multipleResult.failures().size());
        assertTrue(multipleResult.hasFailure(CrossLevelTransferFailure.SAME_LEVEL));
        assertTrue(multipleResult.hasFailure(CrossLevelTransferFailure.TARGET_SLOT_OCCUPIED));
        assertTrue(multipleResult.hasFailure(CrossLevelTransferFailure.SNAPSHOT_UNAVAILABLE));
    }

    @Test
    void nullFactsAreRejected() {
        assertThrows(NullPointerException.class, () -> CrossLevelTransferPreflightValidator.validate(null));
    }

    private static CrossLevelTransferPreflightFacts safeFacts(
            final String sourceDimension,
            final String targetDimension
    ) {
        return CrossLevelTransferPreflightFacts.builder(sourceDimension, targetDimension)
                .sourceRemoved(false)
                .sourceContainerAvailable(true)
                .targetContainerAvailable(true)
                .targetPhysicsAvailable(true)
                .duplicateTargetUuid(false)
                .dependenciesPresent(false)
                .activeKinematicContraption(false)
                .compatibleSectionLayout(true)
                .targetSlotOccupied(false)
                .entitiesPresent(false)
                .transactionConflict(false)
                .snapshotAvailable(true)
                .build();
    }
}
