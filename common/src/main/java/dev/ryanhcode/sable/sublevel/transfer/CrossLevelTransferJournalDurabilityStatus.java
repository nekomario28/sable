package dev.ryanhcode.sable.sublevel.transfer;

/**
 * Typed result of saving and cold-reading the authoritative transfer journal.
 */
public enum CrossLevelTransferJournalDurabilityStatus {
    DURABLE,
    TRANSACTION_STATE_MISMATCH,
    SNAPSHOT_STORE_MISMATCH,
    LIVE_JOURNAL_MISMATCH,
    SAVE_FAILURE,
    COLD_READ_FAILURE,
    COLD_JOURNAL_MISMATCH;

    public boolean isDurable() {
        return this == DURABLE;
    }
}
