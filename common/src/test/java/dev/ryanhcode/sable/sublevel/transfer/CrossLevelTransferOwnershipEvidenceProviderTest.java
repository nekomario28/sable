package dev.ryanhcode.sable.sublevel.transfer;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class CrossLevelTransferOwnershipEvidenceProviderTest {
    @Test
    void exactAcquiredTransferProducesOwnershipEvidence() {
        final CrossLevelTransferTransactionState preparing = state(
                UUID.randomUUID(),
                UUID.randomUUID(),
                3,
                4,
                CrossLevelTransferPhase.PREPARING,
                1
        );
        final CrossLevelTransferJournalController controller = controller();

        assertEquals(CrossLevelTransferOwnershipResult.ACQUIRED, controller.acquire(preparing));
        assertTrue(controller.ownsTransfer(preparing));

        final CrossLevelTransferAuthoritativePreflightEvidence enriched =
                CrossLevelTransferOwnershipEvidenceProvider.enrich(
                        new CrossLevelTransferAuthoritativePreflightEvidence(true, false, true),
                        controller,
                        preparing
                );

        assertTrue(enriched.targetUuidUnique());
        assertTrue(enriched.transactionOwned());
        assertTrue(enriched.snapshotVerified());
    }

    @Test
    void phaseAdvancementDoesNotChangeImmutableOwnershipIdentity() {
        final CrossLevelTransferTransactionState preparing = state(
                UUID.randomUUID(),
                UUID.randomUUID(),
                5,
                6,
                CrossLevelTransferPhase.PREPARING,
                1
        );
        final CrossLevelTransferJournalController controller = controller();
        controller.acquire(preparing);

        controller.advance(preparing.transactionId(), CrossLevelTransferPhase.SNAPSHOT_WRITTEN);
        controller.advance(preparing.transactionId(), CrossLevelTransferPhase.TARGET_RESERVED);

        assertTrue(controller.ownsTransfer(preparing));
    }

    @Test
    void anyImmutableIdentityMismatchFailsClosed() {
        final UUID transactionId = UUID.randomUUID();
        final UUID subLevelId = UUID.randomUUID();
        final CrossLevelTransferTransactionState preparing = state(
                transactionId,
                subLevelId,
                7,
                8,
                CrossLevelTransferPhase.PREPARING,
                1
        );
        final CrossLevelTransferJournalController controller = controller();
        controller.acquire(preparing);

        assertFalse(controller.ownsTransfer(state(
                transactionId,
                UUID.randomUUID(),
                7,
                8,
                CrossLevelTransferPhase.PREPARING,
                1
        )));
        assertFalse(controller.ownsTransfer(state(
                transactionId,
                subLevelId,
                9,
                8,
                CrossLevelTransferPhase.PREPARING,
                1
        )));
        assertFalse(controller.ownsTransfer(state(
                transactionId,
                subLevelId,
                7,
                8,
                CrossLevelTransferPhase.PREPARING,
                2
        )));
    }

    @Test
    void releasedTerminalTransferNoLongerProducesEvidence() {
        final CrossLevelTransferTransactionState preparing = state(
                UUID.randomUUID(),
                UUID.randomUUID(),
                10,
                11,
                CrossLevelTransferPhase.PREPARING,
                1
        );
        final CrossLevelTransferJournalController controller = controller();
        controller.acquire(preparing);
        controller.advance(preparing.transactionId(), CrossLevelTransferPhase.SNAPSHOT_WRITTEN);
        controller.advance(preparing.transactionId(), CrossLevelTransferPhase.TARGET_RESERVED);
        controller.advance(preparing.transactionId(), CrossLevelTransferPhase.TARGET_LOADED);
        controller.advance(preparing.transactionId(), CrossLevelTransferPhase.SOURCE_REMOVED);
        controller.advance(preparing.transactionId(), CrossLevelTransferPhase.COMMITTED);
        assertTrue(controller.release(preparing.transactionId()));

        assertFalse(controller.ownsTransfer(preparing));
        final CrossLevelTransferAuthoritativePreflightEvidence evidence =
                CrossLevelTransferOwnershipEvidenceProvider.enrich(
                        new CrossLevelTransferAuthoritativePreflightEvidence(true, true, true),
                        controller,
                        preparing
                );
        assertFalse(evidence.transactionOwned());
    }

    @Test
    void nullContractsAreRejected() {
        final CrossLevelTransferJournalController controller = controller();
        final CrossLevelTransferTransactionState preparing = state(
                UUID.randomUUID(),
                UUID.randomUUID(),
                1,
                2,
                CrossLevelTransferPhase.PREPARING,
                1
        );

        assertThrows(NullPointerException.class, () -> controller.ownsTransfer(null));
        assertThrows(NullPointerException.class, () ->
                CrossLevelTransferOwnershipEvidenceProvider.enrich(null, controller, preparing));
        assertThrows(NullPointerException.class, () ->
                CrossLevelTransferOwnershipEvidenceProvider.enrich(
                        CrossLevelTransferAuthoritativePreflightEvidence.unverified(),
                        null,
                        preparing
                ));
        assertThrows(NullPointerException.class, () ->
                CrossLevelTransferOwnershipEvidenceProvider.enrich(
                        CrossLevelTransferAuthoritativePreflightEvidence.unverified(),
                        controller,
                        null
                ));
    }

    private static CrossLevelTransferJournalController controller() {
        return new CrossLevelTransferJournalController(CrossLevelTransferJournalSavedData.createEmpty());
    }

    private static CrossLevelTransferTransactionState state(
            final UUID transactionId,
            final UUID subLevelId,
            final int localPlotX,
            final int localPlotZ,
            final CrossLevelTransferPhase phase,
            final int snapshotFormatVersion
    ) {
        return new CrossLevelTransferTransactionState(
                transactionId,
                subLevelId,
                "minecraft:overworld",
                "starlance:space",
                localPlotX,
                localPlotZ,
                phase,
                snapshotFormatVersion
        );
    }
}
