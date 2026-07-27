package dev.ryanhcode.sable.sublevel.transfer;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class CrossLevelTransferPreflightCoordinatorTest {
    @Test
    void allRuntimeAndAuthoritativeEvidenceProducesAcceptedValidation() {
        final Fixture fixture = fixture();

        final CrossLevelTransferValidation validation = CrossLevelTransferPreflightCoordinator.validate(
                safeRuntimeFacts(),
                fixture.controller(),
                fixture.index(),
                fixture.envelope(),
                fixture.expected()
        );

        assertTrue(validation.isAccepted());
        assertTrue(validation.failures().isEmpty());
    }

    @Test
    void missingOwnershipProducesOnlyTransactionConflict() {
        final Fixture fixture = fixture();
        final CrossLevelTransferJournalController emptyController = controller();

        final CrossLevelTransferValidation validation = CrossLevelTransferPreflightCoordinator.validate(
                safeRuntimeFacts(),
                emptyController,
                fixture.index(),
                fixture.envelope(),
                fixture.expected()
        );

        assertFalse(validation.isAccepted());
        assertEquals(1, validation.failures().size());
        assertTrue(validation.hasFailure(CrossLevelTransferFailure.TRANSACTION_CONFLICT));
    }

    @Test
    void missingUuidLocationProducesOnlyDuplicateUuidFailure() {
        final Fixture fixture = fixture();
        final CrossLevelTransferUuidLocationIndex emptyCompleteIndex =
                new CrossLevelTransferUuidLocationIndex();
        emptyCompleteIndex.complete();

        final CrossLevelTransferValidation validation = CrossLevelTransferPreflightCoordinator.validate(
                safeRuntimeFacts(),
                fixture.controller(),
                emptyCompleteIndex,
                fixture.envelope(),
                fixture.expected()
        );

        assertFalse(validation.isAccepted());
        assertEquals(1, validation.failures().size());
        assertTrue(validation.hasFailure(CrossLevelTransferFailure.DUPLICATE_TARGET_UUID));
    }

    @Test
    void mismatchedSnapshotProducesOnlySnapshotFailure() {
        final Fixture fixture = fixture();
        final CrossLevelTransferSnapshotEnvelope mismatch = CrossLevelTransferSnapshotEnvelope.create(
                UUID.randomUUID(),
                fixture.expected().subLevelId(),
                fixture.expected().snapshotFormatVersion(),
                payload()
        );

        final CrossLevelTransferValidation validation = CrossLevelTransferPreflightCoordinator.validate(
                safeRuntimeFacts(),
                fixture.controller(),
                fixture.index(),
                mismatch,
                fixture.expected()
        );

        assertFalse(validation.isAccepted());
        assertEquals(1, validation.failures().size());
        assertTrue(validation.hasFailure(CrossLevelTransferFailure.SNAPSHOT_UNAVAILABLE));
    }

    @Test
    void runtimeBlockersCannotBeErasedByCompleteEvidence() {
        final Fixture fixture = fixture();
        final CrossLevelTransferPreflightFacts blocked = CrossLevelTransferPreflightFacts.builder(
                        fixture.expected().sourceDimension(),
                        fixture.expected().targetDimension()
                )
                .sourceRemoved(false)
                .sourceContainerAvailable(true)
                .targetContainerAvailable(true)
                .targetPhysicsAvailable(true)
                .dependenciesPresent(false)
                .activeKinematicContraption(true)
                .compatibleSectionLayout(true)
                .targetSlotOccupied(false)
                .entitiesPresent(true)
                .build();

        final CrossLevelTransferValidation validation = CrossLevelTransferPreflightCoordinator.validate(
                blocked,
                fixture.controller(),
                fixture.index(),
                fixture.envelope(),
                fixture.expected()
        );

        assertFalse(validation.isAccepted());
        assertTrue(validation.hasFailure(CrossLevelTransferFailure.ACTIVE_KINEMATIC_CONTRAPTION));
        assertTrue(validation.hasFailure(CrossLevelTransferFailure.ENTITIES_PRESENT));
        assertFalse(validation.hasFailure(CrossLevelTransferFailure.DUPLICATE_TARGET_UUID));
        assertFalse(validation.hasFailure(CrossLevelTransferFailure.TRANSACTION_CONFLICT));
        assertFalse(validation.hasFailure(CrossLevelTransferFailure.SNAPSHOT_UNAVAILABLE));
    }

    @Test
    void collectedEvidenceContainsAllThreeIndependentProofs() {
        final Fixture fixture = fixture();

        final CrossLevelTransferAuthoritativePreflightEvidence evidence =
                CrossLevelTransferPreflightCoordinator.collectEvidence(
                        fixture.controller(),
                        fixture.index(),
                        fixture.envelope(),
                        fixture.expected()
                );

        assertTrue(evidence.targetUuidUnique());
        assertTrue(evidence.transactionOwned());
        assertTrue(evidence.snapshotVerified());
    }

    @Test
    void nullContractsAreRejected() {
        final Fixture fixture = fixture();

        assertThrows(NullPointerException.class, () ->
                CrossLevelTransferPreflightCoordinator.validate(
                        null,
                        fixture.controller(),
                        fixture.index(),
                        fixture.envelope(),
                        fixture.expected()
                ));
        assertThrows(NullPointerException.class, () ->
                CrossLevelTransferPreflightCoordinator.collectEvidence(
                        null,
                        fixture.index(),
                        fixture.envelope(),
                        fixture.expected()
                ));
        assertThrows(NullPointerException.class, () ->
                CrossLevelTransferPreflightCoordinator.collectEvidence(
                        fixture.controller(),
                        null,
                        fixture.envelope(),
                        fixture.expected()
                ));
        assertThrows(NullPointerException.class, () ->
                CrossLevelTransferPreflightCoordinator.collectEvidence(
                        fixture.controller(),
                        fixture.index(),
                        null,
                        fixture.expected()
                ));
        assertThrows(NullPointerException.class, () ->
                CrossLevelTransferPreflightCoordinator.collectEvidence(
                        fixture.controller(),
                        fixture.index(),
                        fixture.envelope(),
                        null
                ));
    }

    private static Fixture fixture() {
        final CrossLevelTransferTransactionState preparing = new CrossLevelTransferTransactionState(
                UUID.randomUUID(),
                UUID.randomUUID(),
                "minecraft:overworld",
                "starlance:space",
                1,
                2,
                CrossLevelTransferPhase.PREPARING,
                CrossLevelTransferTransactionState.CURRENT_SNAPSHOT_FORMAT_VERSION
        );
        final CrossLevelTransferJournalController controller = controller();
        assertEquals(CrossLevelTransferOwnershipResult.ACQUIRED, controller.acquire(preparing));
        final CrossLevelTransferTransactionState expected = controller.advance(
                preparing.transactionId(),
                CrossLevelTransferPhase.SNAPSHOT_WRITTEN
        );

        final CrossLevelTransferUuidLocationIndex index = new CrossLevelTransferUuidLocationIndex();
        index.register(
                expected.subLevelId(),
                new CrossLevelTransferSubLevelLocation.Loaded(
                        expected.sourceDimension(),
                        expected.localPlotX(),
                        expected.localPlotZ()
                )
        );
        index.complete();

        final CrossLevelTransferSnapshotEnvelope envelope = CrossLevelTransferSnapshotEnvelope.create(
                expected.transactionId(),
                expected.subLevelId(),
                expected.snapshotFormatVersion(),
                payload()
        );
        return new Fixture(controller, index, envelope, expected);
    }

    private static CrossLevelTransferJournalController controller() {
        return new CrossLevelTransferJournalController(CrossLevelTransferJournalSavedData.createEmpty());
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

    private static byte[] payload() {
        return "snapshot".getBytes(StandardCharsets.UTF_8);
    }

    private record Fixture(
            CrossLevelTransferJournalController controller,
            CrossLevelTransferUuidLocationIndex index,
            CrossLevelTransferSnapshotEnvelope envelope,
            CrossLevelTransferTransactionState expected
    ) {
    }
}
