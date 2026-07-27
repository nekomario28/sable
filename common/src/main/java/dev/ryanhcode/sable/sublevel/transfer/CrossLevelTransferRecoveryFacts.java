package dev.ryanhcode.sable.sublevel.transfer;

import java.util.Objects;

/**
 * Immutable observations used to select a restart recovery action.
 *
 * @param state durable transaction state
 * @param sourcePresent whether the source sub-level UUID is present in the source level
 * @param sourceValid whether the present source passed structural validation
 * @param targetPresent whether the target sub-level UUID is present in the target level
 * @param targetValid whether the present target passed structural validation
 * @param snapshotAvailable whether a verified immutable source snapshot is available
 */
public record CrossLevelTransferRecoveryFacts(
        CrossLevelTransferTransactionState state,
        boolean sourcePresent,
        boolean sourceValid,
        boolean targetPresent,
        boolean targetValid,
        boolean snapshotAvailable
) {
    public CrossLevelTransferRecoveryFacts {
        Objects.requireNonNull(state, "state");
        if (sourceValid && !sourcePresent) {
            throw new IllegalArgumentException("A valid source must be present");
        }
        if (targetValid && !targetPresent) {
            throw new IllegalArgumentException("A valid target must be present");
        }
    }
}
