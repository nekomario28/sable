package dev.ryanhcode.sable.sublevel.transfer;

/**
 * Typed outcome of attempting one read-only live snapshot capture.
 */
public enum CrossLevelTransferSnapshotCaptureStatus {
    CAPTURED,
    TRANSACTION_NOT_PREPARING,
    SOURCE_REMOVED,
    SOURCE_ID_MISMATCH,
    SOURCE_DIMENSION_MISMATCH,
    SOURCE_CONTAINER_UNAVAILABLE,
    SOURCE_SLOT_MISMATCH,
    SOURCE_INSPECTION_FAILED,
    DEPENDENCIES_PRESENT,
    SERIALIZATION_FAILED,
    SERIALIZED_DATA_INVALID;

    public boolean isCaptured() {
        return this == CAPTURED;
    }
}
