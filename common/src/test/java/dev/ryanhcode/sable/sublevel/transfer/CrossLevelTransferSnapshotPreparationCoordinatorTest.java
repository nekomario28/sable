package dev.ryanhcode.sable.sublevel.transfer;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class CrossLevelTransferSnapshotPreparationCoordinatorTest {
    private static final int MAX_PAYLOAD_BYTES = 1024 * 1024;
    private static final int MAX_FILE_BYTES = 2 * 1024 * 1024;

    @TempDir
    Path temporaryDirectory;

    @Test
    void durableSnapshotPrecedesJournalAdvancement() {
        final CrossLevelTransferTransactionState expected = preparing();
        final CrossLevelTransferJournalController controller = ownedController(expected);
        final CrossLevelTransferAtomicSnapshotStore store = store();
        final CrossLevelTransferSnapshotEnvelope envelope = envelope(expected, "snapshot");

        assertEquals(
                CrossLevelTransferSnapshotPreparationStatus.PREPARED,
                CrossLevelTransferSnapshotPreparationCoordinator.prepare(
                        expected,
                        envelope,
                        controller,
                        store
                )
        );
        assertEquals(
                CrossLevelTransferPhase.SNAPSHOT_WRITTEN,
                controller.state(expected.transactionId()).orElseThrow().phase()
        );
        assertEquals(envelope, store.read(expected.transactionId()).orElseThrow());
    }

    @Test
    void repeatedPreparationIsIdempotent() {
        final CrossLevelTransferTransactionState expected = preparing();
        final CrossLevelTransferJournalController controller = ownedController(expected);
        final CrossLevelTransferAtomicSnapshotStore store = store();
        final CrossLevelTransferSnapshotEnvelope envelope = envelope(expected, "snapshot");

        assertEquals(
                CrossLevelTransferSnapshotPreparationStatus.PREPARED,
                CrossLevelTransferSnapshotPreparationCoordinator.prepare(expected, envelope, controller, store)
        );
        assertEquals(
                CrossLevelTransferSnapshotPreparationStatus.ALREADY_PREPARED,
                CrossLevelTransferSnapshotPreparationCoordinator.prepare(expected, envelope, controller, store)
        );
        assertEquals(1, controller.size());
    }

    @Test
    void mismatchedEnvelopeNeverTouchesStoreOrJournal() {
        final CrossLevelTransferTransactionState expected = preparing();
        final CrossLevelTransferJournalController controller = ownedController(expected);
        final CrossLevelTransferAtomicSnapshotStore store = store();
        final CrossLevelTransferSnapshotEnvelope mismatch = CrossLevelTransferSnapshotEnvelope.create(
                UUID.randomUUID(),
                expected.subLevelId(),
                expected.snapshotFormatVersion(),
                payload("snapshot")
        );

        assertEquals(
                CrossLevelTransferSnapshotPreparationStatus.ENVELOPE_MISMATCH,
                CrossLevelTransferSnapshotPreparationCoordinator.prepare(
                        expected,
                        mismatch,
                        controller,
                        store
                )
        );
        assertEquals(
                CrossLevelTransferPhase.PREPARING,
                controller.state(expected.transactionId()).orElseThrow().phase()
        );
        assertFalse(java.nio.file.Files.exists(store.pathFor(expected.transactionId())));
    }

    @Test
    void ownershipMismatchNeverWritesSnapshot() {
        final CrossLevelTransferTransactionState expected = preparing();
        final CrossLevelTransferJournalController controller = controller();
        final CrossLevelTransferAtomicSnapshotStore store = store();

        assertEquals(
                CrossLevelTransferSnapshotPreparationStatus.OWNERSHIP_MISMATCH,
                CrossLevelTransferSnapshotPreparationCoordinator.prepare(
                        expected,
                        envelope(expected, "snapshot"),
                        controller,
                        store
                )
        );
        assertFalse(java.nio.file.Files.exists(store.pathFor(expected.transactionId())));
    }

    @Test
    void conflictingExistingSnapshotPreservesPreparingPhase() {
        final CrossLevelTransferTransactionState expected = preparing();
        final CrossLevelTransferJournalController controller = ownedController(expected);
        final CrossLevelTransferAtomicSnapshotStore store = store();
        final CrossLevelTransferSnapshotEnvelope existing = envelope(expected, "existing");
        final CrossLevelTransferSnapshotEnvelope requested = envelope(expected, "requested");
        assertEquals(CrossLevelTransferSnapshotStoreWriteStatus.WRITTEN, store.write(existing));

        assertEquals(
                CrossLevelTransferSnapshotPreparationStatus.SNAPSHOT_CONFLICT,
                CrossLevelTransferSnapshotPreparationCoordinator.prepare(
                        expected,
                        requested,
                        controller,
                        store
                )
        );
        assertEquals(existing, store.read(expected.transactionId()).orElseThrow());
        assertEquals(
                CrossLevelTransferPhase.PREPARING,
                controller.state(expected.transactionId()).orElseThrow().phase()
        );
    }

    @Test
    void storeFailurePreservesPreparingPhase() {
        final CrossLevelTransferTransactionState expected = preparing();
        final CrossLevelTransferJournalController controller = ownedController(expected);
        final CrossLevelTransferAtomicSnapshotStore tooSmallStore =
                new CrossLevelTransferAtomicSnapshotStore(this.temporaryDirectory, 1, MAX_FILE_BYTES);

        assertEquals(
                CrossLevelTransferSnapshotPreparationStatus.SNAPSHOT_STORE_FAILURE,
                CrossLevelTransferSnapshotPreparationCoordinator.prepare(
                        expected,
                        envelope(expected, "snapshot"),
                        controller,
                        tooSmallStore
                )
        );
        assertEquals(
                CrossLevelTransferPhase.PREPARING,
                controller.state(expected.transactionId()).orElseThrow().phase()
        );
    }

    @Test
    void laterPhaseIsNotRewrittenByPreparation() {
        final CrossLevelTransferTransactionState expected = preparing();
        final CrossLevelTransferJournalController controller = ownedController(expected);
        controller.advance(expected.transactionId(), CrossLevelTransferPhase.SNAPSHOT_WRITTEN);
        controller.advance(expected.transactionId(), CrossLevelTransferPhase.TARGET_RESERVED);

        assertEquals(
                CrossLevelTransferSnapshotPreparationStatus.TRANSACTION_NOT_PREPARING,
                CrossLevelTransferSnapshotPreparationCoordinator.prepare(
                        expected,
                        envelope(expected, "snapshot"),
                        controller,
                        store()
                )
        );
        assertEquals(
                CrossLevelTransferPhase.TARGET_RESERVED,
                controller.state(expected.transactionId()).orElseThrow().phase()
        );
    }

    @Test
    void atomicOwnedAdvanceRejectsIdentityMismatch() {
        final CrossLevelTransferTransactionState expected = preparing();
        final CrossLevelTransferJournalController controller = ownedController(expected);
        final CrossLevelTransferTransactionState mismatch = new CrossLevelTransferTransactionState(
                expected.transactionId(),
                UUID.randomUUID(),
                expected.sourceDimension(),
                expected.targetDimension(),
                expected.localPlotX(),
                expected.localPlotZ(),
                expected.phase(),
                expected.snapshotFormatVersion()
        );

        assertTrue(controller.advanceOwned(
                mismatch,
                CrossLevelTransferPhase.SNAPSHOT_WRITTEN
        ).isEmpty());
        assertEquals(
                CrossLevelTransferPhase.PREPARING,
                controller.state(expected.transactionId()).orElseThrow().phase()
        );
    }

    @Test
    void nullContractsAreRejected() {
        final CrossLevelTransferTransactionState expected = preparing();
        final CrossLevelTransferJournalController controller = ownedController(expected);
        final CrossLevelTransferSnapshotEnvelope envelope = envelope(expected, "snapshot");
        final CrossLevelTransferAtomicSnapshotStore store = store();

        assertThrows(NullPointerException.class, () ->
                CrossLevelTransferSnapshotPreparationCoordinator.prepare(null, envelope, controller, store));
        assertThrows(NullPointerException.class, () ->
                CrossLevelTransferSnapshotPreparationCoordinator.prepare(expected, null, controller, store));
        assertThrows(NullPointerException.class, () ->
                CrossLevelTransferSnapshotPreparationCoordinator.prepare(expected, envelope, null, store));
        assertThrows(NullPointerException.class, () ->
                CrossLevelTransferSnapshotPreparationCoordinator.prepare(expected, envelope, controller, null));
        assertThrows(NullPointerException.class, () ->
                controller.advanceOwned(null, CrossLevelTransferPhase.SNAPSHOT_WRITTEN));
        assertThrows(NullPointerException.class, () ->
                controller.advanceOwned(expected, null));
    }

    private CrossLevelTransferAtomicSnapshotStore store() {
        return new CrossLevelTransferAtomicSnapshotStore(
                this.temporaryDirectory,
                MAX_PAYLOAD_BYTES,
                MAX_FILE_BYTES
        );
    }

    private static CrossLevelTransferJournalController ownedController(
            final CrossLevelTransferTransactionState expected
    ) {
        final CrossLevelTransferJournalController controller = controller();
        assertEquals(CrossLevelTransferOwnershipResult.ACQUIRED, controller.acquire(expected));
        return controller;
    }

    private static CrossLevelTransferJournalController controller() {
        return new CrossLevelTransferJournalController(CrossLevelTransferJournalSavedData.createEmpty());
    }

    private static CrossLevelTransferTransactionState preparing() {
        return new CrossLevelTransferTransactionState(
                UUID.randomUUID(),
                UUID.randomUUID(),
                "minecraft:overworld",
                "starlance:space",
                1,
                2,
                CrossLevelTransferPhase.PREPARING,
                CrossLevelTransferTransactionState.CURRENT_SNAPSHOT_FORMAT_VERSION
        );
    }

    private static CrossLevelTransferSnapshotEnvelope envelope(
            final CrossLevelTransferTransactionState expected,
            final String text
    ) {
        return CrossLevelTransferSnapshotEnvelope.create(
                expected.transactionId(),
                expected.subLevelId(),
                expected.snapshotFormatVersion(),
                payload(text)
        );
    }

    private static byte[] payload(final String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }
}
