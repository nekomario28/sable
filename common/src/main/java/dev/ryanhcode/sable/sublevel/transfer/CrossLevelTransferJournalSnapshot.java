package dev.ryanhcode.sable.sublevel.transfer;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Immutable, conflict-free snapshot of all durable transfer transactions.
 */
public final class CrossLevelTransferJournalSnapshot {
    private static final Comparator<CrossLevelTransferTransactionState> TRANSACTION_ORDER =
            Comparator.comparing(state -> state.transactionId().toString());

    private final List<CrossLevelTransferTransactionState> states;

    private CrossLevelTransferJournalSnapshot(final List<CrossLevelTransferTransactionState> states) {
        this.states = List.copyOf(states);
    }

    /**
     * Creates a deterministic snapshot and rejects ambiguous ownership.
     *
     * @param states transaction states to include
     * @return a conflict-free immutable snapshot
     */
    public static CrossLevelTransferJournalSnapshot of(
            final Collection<CrossLevelTransferTransactionState> states
    ) {
        Objects.requireNonNull(states, "states");

        final List<CrossLevelTransferTransactionState> copy = new ArrayList<>(states.size());
        final Set<UUID> transactionIds = new HashSet<>();
        final Set<UUID> subLevelIds = new HashSet<>();
        final Set<CrossLevelTransferTargetSlot> targetSlots = new HashSet<>();

        for (final CrossLevelTransferTransactionState state : states) {
            final CrossLevelTransferTransactionState checkedState = Objects.requireNonNull(state, "state");
            if (!transactionIds.add(checkedState.transactionId())) {
                throw new IllegalArgumentException("Duplicate transfer transaction ID: " + checkedState.transactionId());
            }
            if (!subLevelIds.add(checkedState.subLevelId())) {
                throw new IllegalArgumentException("Duplicate transfer sub-level ID: " + checkedState.subLevelId());
            }

            final CrossLevelTransferTargetSlot targetSlot = CrossLevelTransferTargetSlot.from(checkedState);
            if (!targetSlots.add(targetSlot)) {
                throw new IllegalArgumentException("Duplicate transfer target slot: " + targetSlot);
            }

            copy.add(checkedState);
        }

        copy.sort(TRANSACTION_ORDER);
        return new CrossLevelTransferJournalSnapshot(copy);
    }

    public static CrossLevelTransferJournalSnapshot empty() {
        return new CrossLevelTransferJournalSnapshot(List.of());
    }

    public List<CrossLevelTransferTransactionState> states() {
        return this.states;
    }

    public Optional<CrossLevelTransferTransactionState> state(final UUID transactionId) {
        Objects.requireNonNull(transactionId, "transactionId");
        return this.states.stream().filter(state -> state.transactionId().equals(transactionId)).findFirst();
    }

    public int size() {
        return this.states.size();
    }

    public boolean isEmpty() {
        return this.states.isEmpty();
    }

    @Override
    public boolean equals(final Object object) {
        if (this == object) return true;
        if (!(object instanceof final CrossLevelTransferJournalSnapshot that)) return false;
        return this.states.equals(that.states);
    }

    @Override
    public int hashCode() {
        return this.states.hashCode();
    }

    @Override
    public String toString() {
        return "CrossLevelTransferJournalSnapshot[states=" + this.states + ']';
    }
}
