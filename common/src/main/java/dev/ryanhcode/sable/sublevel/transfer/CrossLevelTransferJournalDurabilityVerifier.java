package dev.ryanhcode.sable.sublevel.transfer;

import net.minecraft.server.MinecraftServer;

import java.util.Objects;
import java.util.Optional;

/**
 * Explicitly saves the authoritative Overworld transfer journal and verifies it
 * through a fresh DataStorage cache.
 *
 * <p>This operation writes only SavedData. It performs no target allocation or
 * gameplay world mutation.</p>
 */
public final class CrossLevelTransferJournalDurabilityVerifier {
    private CrossLevelTransferJournalDurabilityVerifier() {
    }

    public static CrossLevelTransferJournalDurabilityStatus verify(
            final MinecraftServer server,
            final CrossLevelTransferTransactionState expected,
            final CrossLevelTransferSnapshotEnvelope envelope,
            final CrossLevelTransferJournalController journalController,
            final CrossLevelTransferAtomicSnapshotStore snapshotStore
    ) {
        Objects.requireNonNull(server, "server");
        Objects.requireNonNull(expected, "expected");
        Objects.requireNonNull(envelope, "envelope");
        Objects.requireNonNull(journalController, "journalController");
        Objects.requireNonNull(snapshotStore, "snapshotStore");

        if (!journalController.ownsTransfer(expected)) {
            return CrossLevelTransferJournalDurabilityStatus.TRANSACTION_STATE_MISMATCH;
        }
        final Optional<CrossLevelTransferTransactionState> current =
                journalController.state(expected.transactionId());
        if (current.isEmpty() || current.orElseThrow().phase() != CrossLevelTransferPhase.SNAPSHOT_WRITTEN) {
            return CrossLevelTransferJournalDurabilityStatus.TRANSACTION_STATE_MISMATCH;
        }
        if (!envelope.verifyIntegrity() || !envelope.matches(expected) ||
                snapshotStore.read(expected.transactionId()).filter(envelope::equals).isEmpty()) {
            return CrossLevelTransferJournalDurabilityStatus.SNAPSHOT_STORE_MISMATCH;
        }

        final CrossLevelTransferJournalSnapshot expectedJournal = journalController.snapshot();
        if (expectedJournal.isEmpty()) {
            return CrossLevelTransferJournalDurabilityStatus.LIVE_JOURNAL_MISMATCH;
        }

        final CrossLevelTransferJournalSavedData liveData;
        try {
            liveData = CrossLevelTransferJournalSavedData.getOrLoad(server);
        } catch (final RuntimeException exception) {
            return CrossLevelTransferJournalDurabilityStatus.LIVE_JOURNAL_MISMATCH;
        }
        if (!matchesExpectedJournal(expectedJournal, liveData)) {
            return CrossLevelTransferJournalDurabilityStatus.LIVE_JOURNAL_MISMATCH;
        }

        try {
            server.overworld().getChunkSource().getDataStorage().save();
        } catch (final RuntimeException exception) {
            return CrossLevelTransferJournalDurabilityStatus.SAVE_FAILURE;
        }

        final CrossLevelTransferJournalSavedData coldData;
        try {
            coldData = CrossLevelTransferJournalSavedData.loadFreshFromDisk(server);
        } catch (final RuntimeException exception) {
            return CrossLevelTransferJournalDurabilityStatus.COLD_READ_FAILURE;
        }
        return matchesExpectedJournal(expectedJournal, coldData) ?
                CrossLevelTransferJournalDurabilityStatus.DURABLE :
                CrossLevelTransferJournalDurabilityStatus.COLD_JOURNAL_MISMATCH;
    }

    static boolean matchesExpectedJournal(
            final CrossLevelTransferJournalSnapshot expected,
            final CrossLevelTransferJournalSavedData data
    ) {
        Objects.requireNonNull(expected, "expected");
        Objects.requireNonNull(data, "data");
        return data.loadStatus() == CrossLevelTransferJournalLoadStatus.READABLE &&
                data.snapshot().filter(expected::equals).isPresent();
    }
}
