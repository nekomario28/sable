package dev.ryanhcode.sable.sublevel.transfer;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class CrossLevelTransferAtomicSnapshotStoreTest {
    private static final int MAX_PAYLOAD_BYTES = 1024 * 1024;
    private static final int MAX_FILE_BYTES = 2 * 1024 * 1024;

    @TempDir
    Path temporaryDirectory;

    @Test
    void firstWriteIsAtomicAndRoundTrips() throws IOException {
        final CrossLevelTransferAtomicSnapshotStore store = store();
        final CrossLevelTransferSnapshotEnvelope envelope = envelope(UUID.randomUUID(), "snapshot");

        assertEquals(CrossLevelTransferSnapshotStoreWriteStatus.WRITTEN, store.write(envelope));
        assertEquals(envelope, store.read(envelope.transactionId()).orElseThrow());
        assertTrue(Files.isRegularFile(store.pathFor(envelope.transactionId())));
        assertEquals(1, Files.list(this.temporaryDirectory).count());
    }

    @Test
    void identicalWriteIsIdempotent() {
        final CrossLevelTransferAtomicSnapshotStore store = store();
        final CrossLevelTransferSnapshotEnvelope envelope = envelope(UUID.randomUUID(), "snapshot");

        assertEquals(CrossLevelTransferSnapshotStoreWriteStatus.WRITTEN, store.write(envelope));
        assertEquals(CrossLevelTransferSnapshotStoreWriteStatus.ALREADY_PRESENT, store.write(envelope));
        assertEquals(envelope, store.read(envelope.transactionId()).orElseThrow());
    }

    @Test
    void sameTransactionWithDifferentEnvelopeIsAConflict() {
        final CrossLevelTransferAtomicSnapshotStore store = store();
        final UUID transactionId = UUID.randomUUID();
        final CrossLevelTransferSnapshotEnvelope original = envelope(transactionId, "original");
        final CrossLevelTransferSnapshotEnvelope conflicting = envelope(transactionId, "conflicting");

        assertEquals(CrossLevelTransferSnapshotStoreWriteStatus.WRITTEN, store.write(original));
        assertEquals(
                CrossLevelTransferSnapshotStoreWriteStatus.CONFLICTING_EXISTING_SNAPSHOT,
                store.write(conflicting)
        );
        assertEquals(original, store.read(transactionId).orElseThrow());
    }

    @Test
    void corruptExistingFileFailsVerificationWithoutOverwrite() throws IOException {
        final CrossLevelTransferAtomicSnapshotStore store = store();
        final CrossLevelTransferSnapshotEnvelope envelope = envelope(UUID.randomUUID(), "snapshot");
        assertEquals(CrossLevelTransferSnapshotStoreWriteStatus.WRITTEN, store.write(envelope));

        final Path path = store.pathFor(envelope.transactionId());
        final byte[] corrupt = new byte[]{1, 2, 3};
        Files.write(path, corrupt);

        assertTrue(store.read(envelope.transactionId()).isEmpty());
        assertEquals(
                CrossLevelTransferSnapshotStoreWriteStatus.VERIFICATION_FAILURE,
                store.write(envelope)
        );
        assertArrayEquals(corrupt, Files.readAllBytes(path));
    }

    @Test
    void oversizedPayloadIsRejectedBeforeFileCreation() {
        final CrossLevelTransferAtomicSnapshotStore store = new CrossLevelTransferAtomicSnapshotStore(
                this.temporaryDirectory,
                4,
                MAX_FILE_BYTES
        );
        final CrossLevelTransferSnapshotEnvelope envelope = envelope(UUID.randomUUID(), "too-large");

        assertEquals(
                CrossLevelTransferSnapshotStoreWriteStatus.VERIFICATION_FAILURE,
                store.write(envelope)
        );
        assertFalse(Files.exists(store.pathFor(envelope.transactionId())));
    }

    @Test
    void copiedFileUnderDifferentTransactionNameIsRejected() throws IOException {
        final CrossLevelTransferAtomicSnapshotStore store = store();
        final CrossLevelTransferSnapshotEnvelope envelope = envelope(UUID.randomUUID(), "snapshot");
        assertEquals(CrossLevelTransferSnapshotStoreWriteStatus.WRITTEN, store.write(envelope));

        final UUID otherTransaction = UUID.randomUUID();
        Files.copy(
                store.pathFor(envelope.transactionId()),
                store.pathFor(otherTransaction)
        );

        assertTrue(store.read(otherTransaction).isEmpty());
        assertEquals(envelope, store.read(envelope.transactionId()).orElseThrow());
    }

    @Test
    void pathAndConstructorContractsAreStrict() {
        assertThrows(
                NullPointerException.class,
                () -> new CrossLevelTransferAtomicSnapshotStore(null, 1, 1)
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> new CrossLevelTransferAtomicSnapshotStore(this.temporaryDirectory, 0, 1)
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> new CrossLevelTransferAtomicSnapshotStore(this.temporaryDirectory, 2, 1)
        );

        final CrossLevelTransferAtomicSnapshotStore store = store();
        assertThrows(NullPointerException.class, () -> store.write(null));
        assertThrows(NullPointerException.class, () -> store.read(null));
        assertThrows(NullPointerException.class, () -> store.pathFor(null));
    }

    private CrossLevelTransferAtomicSnapshotStore store() {
        return new CrossLevelTransferAtomicSnapshotStore(
                this.temporaryDirectory,
                MAX_PAYLOAD_BYTES,
                MAX_FILE_BYTES
        );
    }

    private static CrossLevelTransferSnapshotEnvelope envelope(
            final UUID transactionId,
            final String payload
    ) {
        return CrossLevelTransferSnapshotEnvelope.create(
                transactionId,
                UUID.randomUUID(),
                1,
                payload.getBytes(StandardCharsets.UTF_8)
        );
    }
}
