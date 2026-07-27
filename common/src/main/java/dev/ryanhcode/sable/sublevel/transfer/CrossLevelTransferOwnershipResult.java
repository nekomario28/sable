package dev.ryanhcode.sable.sublevel.transfer;

/**
 * Result of attempting to acquire in-memory transfer ownership.
 */
public enum CrossLevelTransferOwnershipResult {
    ACQUIRED,
    ALREADY_OWNED,
    TRANSACTION_ID_CONFLICT,
    SUB_LEVEL_CONFLICT,
    TARGET_SLOT_CONFLICT;

    /**
     * @return whether the caller owns the requested transfer after this result
     */
    public boolean ownsTransfer() {
        return this == ACQUIRED || this == ALREADY_OWNED;
    }
}
