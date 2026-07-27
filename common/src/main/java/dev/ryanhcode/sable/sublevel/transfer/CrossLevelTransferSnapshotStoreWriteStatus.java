package dev.ryanhcode.sable.sublevel.transfer;

/**
 * Typed result of one immutable snapshot store write.
 */
public enum CrossLevelTransferSnapshotStoreWriteStatus {
    WRITTEN,
    ALREADY_PRESENT,
    CONFLICTING_EXISTING_SNAPSHOT,
    ATOMIC_MOVE_UNAVAILABLE,
    IO_FAILURE,
    VERIFICATION_FAILURE;

    public boolean isDurablyAvailable() {
        return this == WRITTEN || this == ALREADY_PRESENT;
    }
}
