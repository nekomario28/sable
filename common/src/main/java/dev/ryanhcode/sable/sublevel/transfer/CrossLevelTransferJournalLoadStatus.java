package dev.ryanhcode.sable.sublevel.transfer;

/**
 * Read status of the durable cross-dimension transfer journal.
 */
public enum CrossLevelTransferJournalLoadStatus {
    READABLE(true),
    CORRUPT_PRESERVED(false);

    private final boolean mutable;

    CrossLevelTransferJournalLoadStatus(final boolean mutable) {
        this.mutable = mutable;
    }

    public boolean canMutate() {
        return this.mutable;
    }
}
