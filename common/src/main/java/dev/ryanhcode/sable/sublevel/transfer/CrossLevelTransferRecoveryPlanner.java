package dev.ryanhcode.sable.sublevel.transfer;

import java.util.Objects;

/**
 * Side-effect-free restart recovery planner.
 *
 * <p>The planner never performs a world mutation. It selects an explicit action
 * only when durable phase and observed authority are sufficient; otherwise it
 * fails closed to manual recovery.</p>
 */
public final class CrossLevelTransferRecoveryPlanner {
    private CrossLevelTransferRecoveryPlanner() {
    }

    public static CrossLevelTransferRecoveryDecision plan(final CrossLevelTransferRecoveryFacts facts) {
        Objects.requireNonNull(facts, "facts");

        return switch (facts.state().phase()) {
            case PREPARING -> planPreparing(facts);
            case SNAPSHOT_WRITTEN, TARGET_RESERVED -> planSourceAuthoritative(facts, false);
            case TARGET_LOADED -> planTargetLoaded(facts);
            case SOURCE_REMOVED -> planSourceRemoved(facts);
            case COMMITTED -> planCommitted(facts);
            case ROLLED_BACK -> planSourceAuthoritative(facts, true);
        };
    }

    private static CrossLevelTransferRecoveryDecision planPreparing(
            final CrossLevelTransferRecoveryFacts facts
    ) {
        if (!facts.sourceValid()) {
            return CrossLevelTransferRecoveryDecision.MANUAL_RECOVERY_REQUIRED;
        }
        return facts.targetPresent() ?
                CrossLevelTransferRecoveryDecision.REMOVE_TARGET_AND_ROLL_BACK_TO_SOURCE :
                CrossLevelTransferRecoveryDecision.ROLL_BACK_TO_SOURCE;
    }

    private static CrossLevelTransferRecoveryDecision planSourceAuthoritative(
            final CrossLevelTransferRecoveryFacts facts,
            final boolean alreadyRolledBack
    ) {
        if (facts.sourceValid()) {
            if (facts.targetPresent()) {
                return CrossLevelTransferRecoveryDecision.REMOVE_TARGET_AND_ROLL_BACK_TO_SOURCE;
            }
            return alreadyRolledBack ?
                    CrossLevelTransferRecoveryDecision.CLEAN_UP_ROLLED_BACK_TRANSACTION :
                    CrossLevelTransferRecoveryDecision.ROLL_BACK_TO_SOURCE;
        }

        if (facts.sourcePresent() || !facts.snapshotAvailable()) {
            return CrossLevelTransferRecoveryDecision.MANUAL_RECOVERY_REQUIRED;
        }

        return facts.targetPresent() ?
                CrossLevelTransferRecoveryDecision.REMOVE_TARGET_AND_RESTORE_SOURCE_FROM_SNAPSHOT :
                CrossLevelTransferRecoveryDecision.RESTORE_SOURCE_FROM_SNAPSHOT;
    }

    private static CrossLevelTransferRecoveryDecision planTargetLoaded(
            final CrossLevelTransferRecoveryFacts facts
    ) {
        if (facts.sourceValid()) {
            return facts.targetPresent() ?
                    CrossLevelTransferRecoveryDecision.REMOVE_TARGET_AND_ROLL_BACK_TO_SOURCE :
                    CrossLevelTransferRecoveryDecision.ROLL_BACK_TO_SOURCE;
        }
        if (facts.sourcePresent()) {
            return CrossLevelTransferRecoveryDecision.MANUAL_RECOVERY_REQUIRED;
        }

        if (facts.targetValid()) {
            return CrossLevelTransferRecoveryDecision.FINALIZE_TARGET;
        }
        if (!facts.snapshotAvailable()) {
            return CrossLevelTransferRecoveryDecision.MANUAL_RECOVERY_REQUIRED;
        }

        return facts.targetPresent() ?
                CrossLevelTransferRecoveryDecision.REMOVE_TARGET_AND_RESTORE_SOURCE_FROM_SNAPSHOT :
                CrossLevelTransferRecoveryDecision.RESTORE_SOURCE_FROM_SNAPSHOT;
    }

    private static CrossLevelTransferRecoveryDecision planSourceRemoved(
            final CrossLevelTransferRecoveryFacts facts
    ) {
        if (!facts.targetValid()) {
            return CrossLevelTransferRecoveryDecision.MANUAL_RECOVERY_REQUIRED;
        }

        return facts.sourcePresent() ?
                CrossLevelTransferRecoveryDecision.REMOVE_SOURCE_REMNANT_AND_FINALIZE_TARGET :
                CrossLevelTransferRecoveryDecision.FINALIZE_TARGET;
    }

    private static CrossLevelTransferRecoveryDecision planCommitted(
            final CrossLevelTransferRecoveryFacts facts
    ) {
        if (!facts.targetValid()) {
            return CrossLevelTransferRecoveryDecision.MANUAL_RECOVERY_REQUIRED;
        }

        return facts.sourcePresent() ?
                CrossLevelTransferRecoveryDecision.REMOVE_SOURCE_REMNANT_AND_FINALIZE_TARGET :
                CrossLevelTransferRecoveryDecision.CLEAN_UP_COMMITTED_TRANSACTION;
    }
}
