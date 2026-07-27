package dev.ryanhcode.sable.sublevel.transfer;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * In-memory ownership registry that prevents concurrent or duplicate transfers
 * from claiming the same transaction, sub-level, or target slot.
 *
 * <p>This registry is not durable storage. It is intended to protect one running
 * server process while a later journal persists the same ownership facts.</p>
 */
public final class CrossLevelTransferOwnershipRegistry {
    private final Map<UUID, CrossLevelTransferTransactionState> statesByTransaction = new HashMap<>();
    private final Map<UUID, UUID> transactionsBySubLevel = new HashMap<>();
    private final Map<CrossLevelTransferTargetSlot, UUID> transactionsByTargetSlot = new HashMap<>();

    /**
     * Acquires ownership for a newly preparing transaction.
     *
     * <p>Repeating an identical request is idempotent. Reusing its transaction ID
     * with different state, or claiming an owned sub-level or target slot, is rejected.</p>
     *
     * @param state the preparing transaction state
     * @return the ownership result
     */
    public synchronized CrossLevelTransferOwnershipResult acquire(
            final CrossLevelTransferTransactionState state
    ) {
        Objects.requireNonNull(state, "state");
        if (state.phase() != CrossLevelTransferPhase.PREPARING) {
            throw new IllegalArgumentException("Ownership can only be acquired in PREPARING phase");
        }

        final CrossLevelTransferTransactionState existingState = this.statesByTransaction.get(state.transactionId());
        if (existingState != null) {
            return existingState.equals(state) ? CrossLevelTransferOwnershipResult.ALREADY_OWNED :
                    CrossLevelTransferOwnershipResult.TRANSACTION_ID_CONFLICT;
        }

        if (this.transactionsBySubLevel.containsKey(state.subLevelId())) {
            return CrossLevelTransferOwnershipResult.SUB_LEVEL_CONFLICT;
        }

        final CrossLevelTransferTargetSlot targetSlot = CrossLevelTransferTargetSlot.from(state);
        if (this.transactionsByTargetSlot.containsKey(targetSlot)) {
            return CrossLevelTransferOwnershipResult.TARGET_SLOT_CONFLICT;
        }

        this.statesByTransaction.put(state.transactionId(), state);
        this.transactionsBySubLevel.put(state.subLevelId(), state.transactionId());
        this.transactionsByTargetSlot.put(targetSlot, state.transactionId());
        return CrossLevelTransferOwnershipResult.ACQUIRED;
    }

    /**
     * Advances an owned transaction by one valid durable phase.
     *
     * @param transactionId transaction to advance
     * @param next next phase
     * @return the new immutable state
     */
    public synchronized CrossLevelTransferTransactionState advance(
            final UUID transactionId,
            final CrossLevelTransferPhase next
    ) {
        Objects.requireNonNull(transactionId, "transactionId");
        Objects.requireNonNull(next, "next");

        final CrossLevelTransferTransactionState current = this.statesByTransaction.get(transactionId);
        if (current == null) {
            throw new IllegalStateException("No owned transfer transaction: " + transactionId);
        }

        final CrossLevelTransferTransactionState advanced = current.advanceTo(next);
        this.statesByTransaction.put(transactionId, advanced);
        return advanced;
    }

    /**
     * Releases all ownership indexes for a terminal transaction.
     *
     * @param transactionId transaction to release
     * @return whether a transaction was released
     */
    public synchronized boolean release(final UUID transactionId) {
        Objects.requireNonNull(transactionId, "transactionId");

        final CrossLevelTransferTransactionState state = this.statesByTransaction.get(transactionId);
        if (state == null) {
            return false;
        }
        if (!state.phase().isTerminal()) {
            throw new IllegalStateException("Cannot release a non-terminal transfer transaction");
        }

        this.statesByTransaction.remove(transactionId);
        this.transactionsBySubLevel.remove(state.subLevelId(), transactionId);
        this.transactionsByTargetSlot.remove(CrossLevelTransferTargetSlot.from(state), transactionId);
        return true;
    }

    public synchronized Optional<CrossLevelTransferTransactionState> state(final UUID transactionId) {
        Objects.requireNonNull(transactionId, "transactionId");
        return Optional.ofNullable(this.statesByTransaction.get(transactionId));
    }

    public synchronized Optional<UUID> ownerOfSubLevel(final UUID subLevelId) {
        Objects.requireNonNull(subLevelId, "subLevelId");
        return Optional.ofNullable(this.transactionsBySubLevel.get(subLevelId));
    }

    public synchronized Optional<UUID> ownerOfTargetSlot(final CrossLevelTransferTargetSlot targetSlot) {
        Objects.requireNonNull(targetSlot, "targetSlot");
        return Optional.ofNullable(this.transactionsByTargetSlot.get(targetSlot));
    }

    public synchronized int size() {
        return this.statesByTransaction.size();
    }
}
