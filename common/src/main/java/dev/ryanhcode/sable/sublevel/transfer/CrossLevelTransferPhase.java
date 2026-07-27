package dev.ryanhcode.sable.sublevel.transfer;

import java.util.Objects;

/**
 * Durable lifecycle phases for a cross-level sub-level transfer transaction.
 */
public enum CrossLevelTransferPhase {
    PREPARING,
    SNAPSHOT_WRITTEN,
    TARGET_RESERVED,
    TARGET_LOADED,
    SOURCE_REMOVED,
    COMMITTED,
    ROLLED_BACK;

    /**
     * @return whether this phase is terminal
     */
    public boolean isTerminal() {
        return this == COMMITTED || this == ROLLED_BACK;
    }

    /**
     * Checks whether this phase can advance directly to the supplied phase.
     * Rewriting the same phase is intentionally not considered an advance.
     *
     * @param next the candidate next phase
     * @return whether the transition is valid
     */
    public boolean canAdvanceTo(final CrossLevelTransferPhase next) {
        Objects.requireNonNull(next, "next");

        if (next == ROLLED_BACK) {
            return !this.isTerminal();
        }

        return switch (this) {
            case PREPARING -> next == SNAPSHOT_WRITTEN;
            case SNAPSHOT_WRITTEN -> next == TARGET_RESERVED;
            case TARGET_RESERVED -> next == TARGET_LOADED;
            case TARGET_LOADED -> next == SOURCE_REMOVED;
            case SOURCE_REMOVED -> next == COMMITTED;
            case COMMITTED, ROLLED_BACK -> false;
        };
    }
}
