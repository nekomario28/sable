package dev.ryanhcode.sable.sublevel.transfer;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class CrossLevelTransferPreflightAssemblerTest {
    @Test
    void unverifiedEvidenceKeepsEveryAuthoritativeBlocker() {
        final CrossLevelTransferValidation validation = CrossLevelTransferPreflightAssembler.validate(
                safeRuntimeFacts(),
                CrossLevelTransferAuthoritativePreflightEvidence.unverified()
        );

        assertFalse(validation.isAccepted());
        assertEquals(3, validation.failures().size());
        assertTrue(validation.hasFailure(CrossLevelTransferFailure.DUPLICATE_TARGET_UUID));
        assertTrue(validation.hasFailure(CrossLevelTransferFailure.TRANSACTION_CONFLICT));
        assertTrue(validation.hasFailure(CrossLevelTransferFailure.SNAPSHOT_UNAVAILABLE));
    }

    @Test
    void completeEvidenceAllowsSafeRuntimeFacts() {
        final CrossLevelTransferAuthoritativePreflightEvidence evidence =
                new CrossLevelTransferAuthoritativePreflightEvidence(true, true, true);

        final CrossLevelTransferValidation validation = CrossLevelTransferPreflightAssembler.validate(
                safeRuntimeFacts(),
                evidence
        );

        assertTrue(validation.isAccepted());
        assertTrue(validation.failures().isEmpty());
    }

    @Test
    void eachMissingEvidenceRemainsTypedAndIndependent() {
        assertOnlyFailure(
                new CrossLevelTransferAuthoritativePreflightEvidence(false, true, true),
                CrossLevelTransferFailure.DUPLICATE_TARGET_UUID
        );
        assertOnlyFailure(
                new CrossLevelTransferAuthoritativePreflightEvidence(true, false, true),
                CrossLevelTransferFailure.TRANSACTION_CONFLICT
        );
        assertOnlyFailure(
                new CrossLevelTransferAuthoritativePreflightEvidence(true, true, false),
                CrossLevelTransferFailure.SNAPSHOT_UNAVAILABLE
        );
    }

    @Test
    void authoritativeEvidenceCannotEraseRuntimeBlockers() {
        final CrossLevelTransferPreflightFacts unsafeRuntime = CrossLevelTransferPreflightFacts.builder(
                        "minecraft:overworld",
                        "starlance:space"
                )
                .sourceRemoved(false)
                .sourceContainerAvailable(true)
                .targetContainerAvailable(true)
                .targetPhysicsAvailable(true)
                .dependenciesPresent(true)
                .activeKinematicContraption(false)
                .compatibleSectionLayout(true)
                .targetSlotOccupied(false)
                .entitiesPresent(false)
                .build();

        final CrossLevelTransferValidation validation = CrossLevelTransferPreflightAssembler.validate(
                unsafeRuntime,
                new CrossLevelTransferAuthoritativePreflightEvidence(true, true, true)
        );

        assertFalse(validation.isAccepted());
        assertEquals(1, validation.failures().size());
        assertTrue(validation.hasFailure(CrossLevelTransferFailure.DEPENDENCIES_PRESENT));
    }

    @Test
    void assembledFactsPreserveAllRuntimeDimensionsAndFlags() {
        final CrossLevelTransferPreflightFacts runtime = safeRuntimeFacts();
        final CrossLevelTransferPreflightFacts assembled = CrossLevelTransferPreflightAssembler.assemble(
                runtime,
                new CrossLevelTransferAuthoritativePreflightEvidence(true, true, true)
        );

        assertEquals(runtime.sourceDimension(), assembled.sourceDimension());
        assertEquals(runtime.targetDimension(), assembled.targetDimension());
        assertEquals(runtime.sourceRemoved(), assembled.sourceRemoved());
        assertEquals(runtime.sourceContainerAvailable(), assembled.sourceContainerAvailable());
        assertEquals(runtime.targetContainerAvailable(), assembled.targetContainerAvailable());
        assertEquals(runtime.targetPhysicsAvailable(), assembled.targetPhysicsAvailable());
        assertEquals(runtime.dependenciesPresent(), assembled.dependenciesPresent());
        assertEquals(runtime.activeKinematicContraption(), assembled.activeKinematicContraption());
        assertEquals(runtime.compatibleSectionLayout(), assembled.compatibleSectionLayout());
        assertEquals(runtime.targetSlotOccupied(), assembled.targetSlotOccupied());
        assertEquals(runtime.entitiesPresent(), assembled.entitiesPresent());
        assertFalse(assembled.duplicateTargetUuid());
        assertFalse(assembled.transactionConflict());
        assertTrue(assembled.snapshotAvailable());
    }

    @Test
    void nullContractsAreRejected() {
        assertThrows(
                NullPointerException.class,
                () -> CrossLevelTransferPreflightAssembler.assemble(null, CrossLevelTransferAuthoritativePreflightEvidence.unverified())
        );
        assertThrows(
                NullPointerException.class,
                () -> CrossLevelTransferPreflightAssembler.assemble(safeRuntimeFacts(), null)
        );
    }

    private static void assertOnlyFailure(
            final CrossLevelTransferAuthoritativePreflightEvidence evidence,
            final CrossLevelTransferFailure expected
    ) {
        final CrossLevelTransferValidation validation = CrossLevelTransferPreflightAssembler.validate(
                safeRuntimeFacts(),
                evidence
        );
        assertFalse(validation.isAccepted());
        assertEquals(1, validation.failures().size());
        assertTrue(validation.hasFailure(expected));
    }

    private static CrossLevelTransferPreflightFacts safeRuntimeFacts() {
        return CrossLevelTransferPreflightFacts.builder(
                        "minecraft:overworld",
                        "starlance:space"
                )
                .sourceRemoved(false)
                .sourceContainerAvailable(true)
                .targetContainerAvailable(true)
                .targetPhysicsAvailable(true)
                .dependenciesPresent(false)
                .activeKinematicContraption(false)
                .compatibleSectionLayout(true)
                .targetSlotOccupied(false)
                .entitiesPresent(false)
                .build();
    }
}
