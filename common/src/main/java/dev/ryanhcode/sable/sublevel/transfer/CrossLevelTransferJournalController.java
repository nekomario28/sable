package dev.ryanhcode.sable.sublevel.transfer;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Keeps in-memory transfer ownership and the SavedData journal snapshot consistent.
 *
 * <p>All compound operations are synchronized so an ownership mutation and its
 * corresponding SavedData replacement cannot interleave with another operation.</p>
 *
 * <p>This controller marks SavedData dirty but does not force a disk write. It is
 * therefore not yet a durability gate for source or target world mutation.</p>
 */
public final class CrossLevelTransferJournalController {
    private final CrossLevelTransferJournalSavedData journal;
    private final CrossLevelTransferOwnershipRegistry ownership;

    /**
     * Opens a controller from one readable authoritative journal.
     *
     * @param journal authoritative transfer journal
     * @throws IllegalStateException when the journal is corrupt and preserved
     */
    public CrossLevelTransferJournalController(final CrossLevelTransferJournalSavedData journal) {
        this.journal = Objects.requireNonNull(journal, "journal");
        final CrossLevelTransferJournalSnapshot snapshot = journal.snapshot()
                .orElseThrow(() -> new IllegalStateException("Cannot open a corrupt transfer journal"));

        this.ownership = new CrossLevelTransferOwnershipRegistry();
        this.ownership.restore(snapshot);
    }

    /**
     * Acquires a new transaction and updates the SavedData snapshot only when
     * ownership changed.
     */
    public synchronized CrossLevelTransferOwnershipResult acquire(
            final CrossLevelTransferTransactionState state
    ) {
        final CrossLevelTransferOwnershipResult result = this.ownership.acquire(state);
        if (result == CrossLevelTransferOwnershipResult.ACQUIRED) {
            this.synchronizeJournal();
        }
        return result;
    }

    /**
     * Advances one owned transaction and synchronizes the complete journal snapshot.
     */
    public synchronized CrossLevelTransferTransactionState advance(
            final UUID transactionId,
            final CrossLevelTransferPhase next
    ) {
        final CrossLevelTransferTransactionState state = this.ownership.advance(transactionId, next);
        this.synchronizeJournal();
        return state;
    }

    /**
     * Releases a terminal transaction and synchronizes the journal when it existed.
     */
    public synchronized boolean release(final UUID transactionId) {
        final boolean released = this.ownership.release(transactionId);
        if (released) {
            this.synchronizeJournal();
        }
        return released;
    }

    public synchronized CrossLevelTransferJournalSnapshot snapshot() {
        return this.ownership.snapshot();
    }

    public synchronized Optional<CrossLevelTransferTransactionState> state(final UUID transactionId) {
        return this.ownership.state(transactionId);
    }

    public synchronized Optional<UUID> ownerOfSubLevel(final UUID subLevelId) {
        return this.ownership.ownerOfSubLevel(subLevelId);
    }

    public synchronized Optional<UUID> ownerOfTargetSlot(final CrossLevelTransferTargetSlot targetSlot) {
        return this.ownership.ownerOfTargetSlot(targetSlot);
    }

    public synchronized int size() {
        return this.ownership.size();
    }

    private void synchronizeJournal() {
        this.journal.replaceSnapshot(this.ownership.snapshot());
    }
}
