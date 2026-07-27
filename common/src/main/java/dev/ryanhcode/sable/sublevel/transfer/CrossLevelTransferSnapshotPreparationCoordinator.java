package dev.ryanhcode.sable.sublevel.transfer;

import java.util.Objects;
import java.util.Optional;

/**
 * Stores and verifies one immutable snapshot before marking the journal phase as
 * {@link CrossLevelTransferPhase#SNAPSHOT_WRITTEN}.
 *
 * <p>This does not force the SavedData journal itself to disk and therefore does
 * not authorize target allocation or any other world mutation.</p>
 */
public final class CrossLevelTransferSnapshotPreparationCoordinator {
    private CrossLevelTransferSnapshotPreparationCoordinator() {
    }

    public static CrossLevelTransferSnapshotPreparationStatus prepare(
            final CrossLevelTransferTransactionState expected,
            final CrossLevelTransferSnapshotEnvelope envelope,
            final CrossLevelTransferJournalController journalController,
            final CrossLevelTransferAtomicSnapshotStore snapshotStore
    ) {
        Objects.requireNonNull(expected, "expected");
        Objects.requireNonNull(envelope, "envelope");
        Objects.requireNonNull(journalController, "journalController");
        Objects.requireNonNull(snapshotStore, "snapshotStore");

        if (!envelope.verifyIntegrity() || !envelope.matches(expected)) {
            return CrossLevelTransferSnapshotPreparationStatus.ENVELOPE_MISMATCH;
        }
        if (!journalController.ownsTransfer(expected)) {
            return CrossLevelTransferSnapshotPreparationStatus.OWNERSHIP_MISMATCH;
        }

        final Optional<CrossLevelTransferTransactionState> currentState =
                journalController.state(expected.transactionId());
        if (currentState.isEmpty()) {
            return CrossLevelTransferSnapshotPreparationStatus.OWNERSHIP_MISMATCH;
        }

        final CrossLevelTransferPhase currentPhase = currentState.orElseThrow().phase();
        if (currentPhase == CrossLevelTransferPhase.SNAPSHOT_WRITTEN) {
            return snapshotStore.read(expected.transactionId())
                    .filter(envelope::equals)
                    .map(ignored -> CrossLevelTransferSnapshotPreparationStatus.ALREADY_PREPARED)
                    .orElse(CrossLevelTransferSnapshotPreparationStatus.SNAPSHOT_STORE_FAILURE);
        }
        if (currentPhase != CrossLevelTransferPhase.PREPARING) {
            return CrossLevelTransferSnapshotPreparationStatus.TRANSACTION_NOT_PREPARING;
        }

        final CrossLevelTransferSnapshotStoreWriteStatus writeStatus = snapshotStore.write(envelope);
        if (writeStatus == CrossLevelTransferSnapshotStoreWriteStatus.CONFLICTING_EXISTING_SNAPSHOT) {
            return CrossLevelTransferSnapshotPreparationStatus.SNAPSHOT_CONFLICT;
        }
        if (!writeStatus.isDurablyAvailable()) {
            return CrossLevelTransferSnapshotPreparationStatus.SNAPSHOT_STORE_FAILURE;
        }
        if (snapshotStore.read(expected.transactionId()).filter(envelope::equals).isEmpty()) {
            return CrossLevelTransferSnapshotPreparationStatus.SNAPSHOT_STORE_FAILURE;
        }

        try {
            return journalController.advanceOwned(expected, CrossLevelTransferPhase.SNAPSHOT_WRITTEN)
                    .map(ignored -> CrossLevelTransferSnapshotPreparationStatus.PREPARED)
                    .orElse(CrossLevelTransferSnapshotPreparationStatus.OWNERSHIP_MISMATCH);
        } catch (final RuntimeException exception) {
            return CrossLevelTransferSnapshotPreparationStatus.JOURNAL_ADVANCE_FAILURE;
        }
    }
}
