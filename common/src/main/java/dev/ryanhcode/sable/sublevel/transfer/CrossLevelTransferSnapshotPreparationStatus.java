package dev.ryanhcode.sable.sublevel.transfer;

/**
 * Typed result of durably storing a snapshot before journal phase advancement.
 */
public enum CrossLevelTransferSnapshotPreparationStatus {
    PREPARED,
    ALREADY_PREPARED,
    TRANSACTION_NOT_PREPARING,
    OWNERSHIP_MISMATCH,
    ENVELOPE_MISMATCH,
    SNAPSHOT_CONFLICT,
    SNAPSHOT_STORE_FAILURE,
    JOURNAL_ADVANCE_FAILURE;

    public boolean isPrepared() {
        return this == PREPARED || this == ALREADY_PREPARED;
    }
}
