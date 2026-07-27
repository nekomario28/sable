package dev.ryanhcode.sable.sublevel.transfer;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class CrossLevelTransferSnapshotEnvelopeTest {
    @Test
    void createdEnvelopeVerifiesAndMatchesTransactionIdentity() {
        final CrossLevelTransferTransactionState state = state(
                UUID.randomUUID(),
                UUID.randomUUID(),
                CrossLevelTransferTransactionState.CURRENT_SNAPSHOT_FORMAT_VERSION
        );
        final CrossLevelTransferSnapshotEnvelope envelope = CrossLevelTransferSnapshotEnvelope.create(
                state.transactionId(),
                state.subLevelId(),
                state.snapshotFormatVersion(),
                payload("snapshot")
        );

        assertTrue(envelope.verifyIntegrity());
        assertTrue(envelope.matches(state));
        assertEquals(CrossLevelTransferSnapshotEnvelope.SHA_256_LENGTH, envelope.digest().length);
    }

    @Test
    void payloadAndDigestAreDefensivelyCopied() {
        final byte[] original = payload("immutable");
        final CrossLevelTransferSnapshotEnvelope envelope = CrossLevelTransferSnapshotEnvelope.create(
                UUID.randomUUID(),
                UUID.randomUUID(),
                1,
                original
        );
        final byte[] expectedPayload = envelope.payload();
        final byte[] expectedDigest = envelope.digest();

        original[0] ^= 1;
        final byte[] exposedPayload = envelope.payload();
        final byte[] exposedDigest = envelope.digest();
        exposedPayload[0] ^= 1;
        exposedDigest[0] ^= 1;

        assertArrayEquals(expectedPayload, envelope.payload());
        assertArrayEquals(expectedDigest, envelope.digest());
        assertTrue(envelope.verifyIntegrity());
    }

    @Test
    void validStoredEnvelopeRestoresExactly() {
        final CrossLevelTransferSnapshotEnvelope created = CrossLevelTransferSnapshotEnvelope.create(
                UUID.randomUUID(),
                UUID.randomUUID(),
                1,
                payload("stored")
        );

        final CrossLevelTransferSnapshotEnvelope restored = CrossLevelTransferSnapshotEnvelope.restore(
                created.transactionId(),
                created.subLevelId(),
                created.snapshotFormatVersion(),
                created.payload(),
                created.digest()
        ).orElseThrow();

        assertEquals(created, restored);
        assertEquals(created.hashCode(), restored.hashCode());
    }

    @Test
    void tamperedStoredPayloadOrDigestFailsClosed() {
        final CrossLevelTransferSnapshotEnvelope created = CrossLevelTransferSnapshotEnvelope.create(
                UUID.randomUUID(),
                UUID.randomUUID(),
                1,
                payload("original")
        );

        final byte[] tamperedPayload = created.payload();
        tamperedPayload[0] ^= 1;
        assertTrue(CrossLevelTransferSnapshotEnvelope.restore(
                created.transactionId(),
                created.subLevelId(),
                created.snapshotFormatVersion(),
                tamperedPayload,
                created.digest()
        ).isEmpty());

        final byte[] tamperedDigest = created.digest();
        tamperedDigest[0] ^= 1;
        assertTrue(CrossLevelTransferSnapshotEnvelope.restore(
                created.transactionId(),
                created.subLevelId(),
                created.snapshotFormatVersion(),
                created.payload(),
                tamperedDigest
        ).isEmpty());
    }

    @Test
    void mismatchedTransactionMetadataDoesNotMatch() {
        final UUID transactionId = UUID.randomUUID();
        final UUID subLevelId = UUID.randomUUID();
        final CrossLevelTransferSnapshotEnvelope envelope = CrossLevelTransferSnapshotEnvelope.create(
                transactionId,
                subLevelId,
                1,
                payload("identity")
        );

        assertFalse(envelope.matches(state(UUID.randomUUID(), subLevelId, 1)));
        assertFalse(envelope.matches(state(transactionId, UUID.randomUUID(), 1)));
        assertFalse(envelope.matches(state(transactionId, subLevelId, 2)));
    }

    @Test
    void invalidCreationAndRestoreInputsFailClosed() {
        assertThrows(NullPointerException.class, () ->
                CrossLevelTransferSnapshotEnvelope.create(null, UUID.randomUUID(), 1, payload("x")));
        assertThrows(NullPointerException.class, () ->
                CrossLevelTransferSnapshotEnvelope.create(UUID.randomUUID(), null, 1, payload("x")));
        assertThrows(NullPointerException.class, () ->
                CrossLevelTransferSnapshotEnvelope.create(UUID.randomUUID(), UUID.randomUUID(), 1, null));
        assertThrows(IllegalArgumentException.class, () ->
                CrossLevelTransferSnapshotEnvelope.create(UUID.randomUUID(), UUID.randomUUID(), 0, payload("x")));
        assertThrows(IllegalArgumentException.class, () ->
                CrossLevelTransferSnapshotEnvelope.create(UUID.randomUUID(), UUID.randomUUID(), 1, new byte[0]));

        assertTrue(CrossLevelTransferSnapshotEnvelope.restore(
                UUID.randomUUID(),
                UUID.randomUUID(),
                1,
                payload("x"),
                new byte[1]
        ).isEmpty());
        assertTrue(CrossLevelTransferSnapshotEnvelope.restore(
                null,
                UUID.randomUUID(),
                1,
                payload("x"),
                new byte[CrossLevelTransferSnapshotEnvelope.SHA_256_LENGTH]
        ).isEmpty());
    }

    private static CrossLevelTransferTransactionState state(
            final UUID transactionId,
            final UUID subLevelId,
            final int snapshotFormatVersion
    ) {
        return new CrossLevelTransferTransactionState(
                transactionId,
                subLevelId,
                "minecraft:overworld",
                "starlance:space",
                1,
                2,
                CrossLevelTransferPhase.SNAPSHOT_WRITTEN,
                snapshotFormatVersion
        );
    }

    private static byte[] payload(final String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }
}
