package dev.ryanhcode.sable.sublevel.transfer;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class CrossLevelTransferSnapshotEvidenceProviderTest {
    @Test
    void verifiedMatchingEnvelopeProducesSnapshotEvidence() {
        final CrossLevelTransferTransactionState expected = state(
                UUID.randomUUID(),
                UUID.randomUUID(),
                1,
                CrossLevelTransferPhase.SNAPSHOT_WRITTEN
        );
        final CrossLevelTransferSnapshotEnvelope envelope = envelope(expected);

        final CrossLevelTransferAuthoritativePreflightEvidence evidence =
                CrossLevelTransferSnapshotEvidenceProvider.enrich(
                        new CrossLevelTransferAuthoritativePreflightEvidence(true, true, false),
                        envelope,
                        expected
                );

        assertTrue(evidence.targetUuidUnique());
        assertTrue(evidence.transactionOwned());
        assertTrue(evidence.snapshotVerified());
    }

    @Test
    void phaseAdvancementDoesNotInvalidateSnapshotIdentity() {
        final UUID transactionId = UUID.randomUUID();
        final UUID subLevelId = UUID.randomUUID();
        final CrossLevelTransferTransactionState snapshotWritten = state(
                transactionId,
                subLevelId,
                1,
                CrossLevelTransferPhase.SNAPSHOT_WRITTEN
        );
        final CrossLevelTransferTransactionState targetReserved = state(
                transactionId,
                subLevelId,
                1,
                CrossLevelTransferPhase.TARGET_RESERVED
        );

        assertTrue(CrossLevelTransferSnapshotEvidenceProvider.enrich(
                CrossLevelTransferAuthoritativePreflightEvidence.unverified(),
                envelope(snapshotWritten),
                targetReserved
        ).snapshotVerified());
    }

    @Test
    void anyImmutableMetadataMismatchFailsClosed() {
        final UUID transactionId = UUID.randomUUID();
        final UUID subLevelId = UUID.randomUUID();
        final CrossLevelTransferTransactionState expected = state(
                transactionId,
                subLevelId,
                1,
                CrossLevelTransferPhase.SNAPSHOT_WRITTEN
        );
        final CrossLevelTransferSnapshotEnvelope envelope = envelope(expected);

        assertFalse(verified(envelope, state(
                UUID.randomUUID(),
                subLevelId,
                1,
                CrossLevelTransferPhase.SNAPSHOT_WRITTEN
        )));
        assertFalse(verified(envelope, state(
                transactionId,
                UUID.randomUUID(),
                1,
                CrossLevelTransferPhase.SNAPSHOT_WRITTEN
        )));
        assertFalse(verified(envelope, state(
                transactionId,
                subLevelId,
                2,
                CrossLevelTransferPhase.SNAPSHOT_WRITTEN
        )));
    }

    @Test
    void existingNonSnapshotEvidenceIsPreserved() {
        final CrossLevelTransferTransactionState expected = state(
                UUID.randomUUID(),
                UUID.randomUUID(),
                1,
                CrossLevelTransferPhase.SNAPSHOT_WRITTEN
        );

        final CrossLevelTransferAuthoritativePreflightEvidence evidence =
                CrossLevelTransferSnapshotEvidenceProvider.enrich(
                        new CrossLevelTransferAuthoritativePreflightEvidence(false, true, true),
                        envelope(expected),
                        expected
                );

        assertFalse(evidence.targetUuidUnique());
        assertTrue(evidence.transactionOwned());
        assertTrue(evidence.snapshotVerified());
    }

    @Test
    void nullContractsAreRejected() {
        final CrossLevelTransferTransactionState expected = state(
                UUID.randomUUID(),
                UUID.randomUUID(),
                1,
                CrossLevelTransferPhase.SNAPSHOT_WRITTEN
        );
        final CrossLevelTransferSnapshotEnvelope envelope = envelope(expected);
        final CrossLevelTransferAuthoritativePreflightEvidence evidence =
                CrossLevelTransferAuthoritativePreflightEvidence.unverified();

        assertThrows(NullPointerException.class, () ->
                CrossLevelTransferSnapshotEvidenceProvider.enrich(null, envelope, expected));
        assertThrows(NullPointerException.class, () ->
                CrossLevelTransferSnapshotEvidenceProvider.enrich(evidence, null, expected));
        assertThrows(NullPointerException.class, () ->
                CrossLevelTransferSnapshotEvidenceProvider.enrich(evidence, envelope, null));
    }

    private static boolean verified(
            final CrossLevelTransferSnapshotEnvelope envelope,
            final CrossLevelTransferTransactionState expected
    ) {
        return CrossLevelTransferSnapshotEvidenceProvider.enrich(
                CrossLevelTransferAuthoritativePreflightEvidence.unverified(),
                envelope,
                expected
        ).snapshotVerified();
    }

    private static CrossLevelTransferSnapshotEnvelope envelope(
            final CrossLevelTransferTransactionState state
    ) {
        return CrossLevelTransferSnapshotEnvelope.create(
                state.transactionId(),
                state.subLevelId(),
                state.snapshotFormatVersion(),
                "snapshot".getBytes(StandardCharsets.UTF_8)
        );
    }

    private static CrossLevelTransferTransactionState state(
            final UUID transactionId,
            final UUID subLevelId,
            final int snapshotFormatVersion,
            final CrossLevelTransferPhase phase
    ) {
        return new CrossLevelTransferTransactionState(
                transactionId,
                subLevelId,
                "minecraft:overworld",
                "starlance:space",
                1,
                2,
                phase,
                snapshotFormatVersion
        );
    }
}
