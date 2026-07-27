package dev.ryanhcode.sable.sublevel.transfer;

/**
 * One recovery action selected from durable phase and observed source/target facts.
 */
public enum CrossLevelTransferRecoveryDecision {
    ROLL_BACK_TO_SOURCE(false),
    REMOVE_TARGET_AND_ROLL_BACK_TO_SOURCE(true),
    RESTORE_SOURCE_FROM_SNAPSHOT(true),
    REMOVE_TARGET_AND_RESTORE_SOURCE_FROM_SNAPSHOT(true),
    FINALIZE_TARGET(false),
    REMOVE_SOURCE_REMNANT_AND_FINALIZE_TARGET(true),
    CLEAN_UP_COMMITTED_TRANSACTION(false),
    CLEAN_UP_ROLLED_BACK_TRANSACTION(false),
    MANUAL_RECOVERY_REQUIRED(false);

    private final boolean worldMutationRequired;

    CrossLevelTransferRecoveryDecision(final boolean worldMutationRequired) {
        this.worldMutationRequired = worldMutationRequired;
    }

    public boolean requiresWorldMutation() {
        return this.worldMutationRequired;
    }

    public boolean requiresManualRecovery() {
        return this == MANUAL_RECOVERY_REQUIRED;
    }
}
