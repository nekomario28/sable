package dev.ryanhcode.sable.sublevel.transfer;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class CrossLevelTransferRecoveryPlannerTest {
    @Test
    void validSourceRemainsAuthoritativeBeforeSourceRemoval() {
        for (final CrossLevelTransferPhase phase : new CrossLevelTransferPhase[]{
                CrossLevelTransferPhase.PREPARING,
                CrossLevelTransferPhase.SNAPSHOT_WRITTEN,
                CrossLevelTransferPhase.TARGET_RESERVED,
                CrossLevelTransferPhase.TARGET_LOADED
        }) {
            assertEquals(
                    CrossLevelTransferRecoveryDecision.ROLL_BACK_TO_SOURCE,
                    plan(phase, true, true, false, false, phase != CrossLevelTransferPhase.PREPARING)
            );
            assertEquals(
                    CrossLevelTransferRecoveryDecision.REMOVE_TARGET_AND_ROLL_BACK_TO_SOURCE,
                    plan(phase, true, true, true, true, phase != CrossLevelTransferPhase.PREPARING)
            );
        }
    }

    @Test
    void verifiedSnapshotRestoresMissingSourceBeforeAuthorityChanges() {
        for (final CrossLevelTransferPhase phase : new CrossLevelTransferPhase[]{
                CrossLevelTransferPhase.SNAPSHOT_WRITTEN,
                CrossLevelTransferPhase.TARGET_RESERVED
        }) {
            assertEquals(
                    CrossLevelTransferRecoveryDecision.RESTORE_SOURCE_FROM_SNAPSHOT,
                    plan(phase, false, false, false, false, true)
            );
            assertEquals(
                    CrossLevelTransferRecoveryDecision.REMOVE_TARGET_AND_RESTORE_SOURCE_FROM_SNAPSHOT,
                    plan(phase, false, false, true, false, true)
            );
        }

        assertEquals(
                CrossLevelTransferRecoveryDecision.MANUAL_RECOVERY_REQUIRED,
                plan(CrossLevelTransferPhase.PREPARING, false, false, false, false, true)
        );
    }

    @Test
    void targetLoadedCrashWindowChoosesOneViableAuthority() {
        assertEquals(
                CrossLevelTransferRecoveryDecision.FINALIZE_TARGET,
                plan(CrossLevelTransferPhase.TARGET_LOADED, false, false, true, true, true)
        );
        assertEquals(
                CrossLevelTransferRecoveryDecision.RESTORE_SOURCE_FROM_SNAPSHOT,
                plan(CrossLevelTransferPhase.TARGET_LOADED, false, false, false, false, true)
        );
        assertEquals(
                CrossLevelTransferRecoveryDecision.REMOVE_TARGET_AND_RESTORE_SOURCE_FROM_SNAPSHOT,
                plan(CrossLevelTransferPhase.TARGET_LOADED, false, false, true, false, true)
        );
    }

    @Test
    void sourceRemovedAndCommittedPhasesKeepValidTargetAuthoritative() {
        assertEquals(
                CrossLevelTransferRecoveryDecision.FINALIZE_TARGET,
                plan(CrossLevelTransferPhase.SOURCE_REMOVED, false, false, true, true, true)
        );
        assertEquals(
                CrossLevelTransferRecoveryDecision.REMOVE_SOURCE_REMNANT_AND_FINALIZE_TARGET,
                plan(CrossLevelTransferPhase.SOURCE_REMOVED, true, true, true, true, true)
        );
        assertEquals(
                CrossLevelTransferRecoveryDecision.CLEAN_UP_COMMITTED_TRANSACTION,
                plan(CrossLevelTransferPhase.COMMITTED, false, false, true, true, true)
        );
        assertEquals(
                CrossLevelTransferRecoveryDecision.REMOVE_SOURCE_REMNANT_AND_FINALIZE_TARGET,
                plan(CrossLevelTransferPhase.COMMITTED, true, false, true, true, true)
        );
    }

    @Test
    void rolledBackPhaseKeepsSourceAuthoritative() {
        assertEquals(
                CrossLevelTransferRecoveryDecision.CLEAN_UP_ROLLED_BACK_TRANSACTION,
                plan(CrossLevelTransferPhase.ROLLED_BACK, true, true, false, false, true)
        );
        assertEquals(
                CrossLevelTransferRecoveryDecision.REMOVE_TARGET_AND_ROLL_BACK_TO_SOURCE,
                plan(CrossLevelTransferPhase.ROLLED_BACK, true, true, true, true, true)
        );
        assertEquals(
                CrossLevelTransferRecoveryDecision.RESTORE_SOURCE_FROM_SNAPSHOT,
                plan(CrossLevelTransferPhase.ROLLED_BACK, false, false, false, false, true)
        );
    }

    @Test
    void ambiguousOrUnrecoverableFactsFailClosed() {
        assertEquals(
                CrossLevelTransferRecoveryDecision.MANUAL_RECOVERY_REQUIRED,
                plan(CrossLevelTransferPhase.SNAPSHOT_WRITTEN, true, false, false, false, true)
        );
        assertEquals(
                CrossLevelTransferRecoveryDecision.MANUAL_RECOVERY_REQUIRED,
                plan(CrossLevelTransferPhase.SNAPSHOT_WRITTEN, false, false, false, false, false)
        );
        assertEquals(
                CrossLevelTransferRecoveryDecision.MANUAL_RECOVERY_REQUIRED,
                plan(CrossLevelTransferPhase.SOURCE_REMOVED, false, false, true, false, true)
        );
        assertEquals(
                CrossLevelTransferRecoveryDecision.MANUAL_RECOVERY_REQUIRED,
                plan(CrossLevelTransferPhase.COMMITTED, false, false, false, false, true)
        );
        assertEquals(
                CrossLevelTransferRecoveryDecision.MANUAL_RECOVERY_REQUIRED,
                plan(CrossLevelTransferPhase.TARGET_LOADED, false, false, true, false, false)
        );
    }

    @Test
    void factsRejectImpossibleValidityCombinations() {
        assertThrows(IllegalArgumentException.class, () -> facts(
                CrossLevelTransferPhase.PREPARING,
                false,
                true,
                false,
                false,
                false
        ));
        assertThrows(IllegalArgumentException.class, () -> facts(
                CrossLevelTransferPhase.PREPARING,
                true,
                true,
                false,
                true,
                false
        ));
        assertThrows(NullPointerException.class, () -> CrossLevelTransferRecoveryPlanner.plan(null));
    }

    @Test
    void decisionsExposeWorldMutationAndManualBoundaries() {
        assertTrue(CrossLevelTransferRecoveryDecision.REMOVE_TARGET_AND_ROLL_BACK_TO_SOURCE
                .requiresWorldMutation());
        assertFalse(CrossLevelTransferRecoveryDecision.ROLL_BACK_TO_SOURCE.requiresWorldMutation());
        assertTrue(CrossLevelTransferRecoveryDecision.MANUAL_RECOVERY_REQUIRED.requiresManualRecovery());
        assertFalse(CrossLevelTransferRecoveryDecision.FINALIZE_TARGET.requiresManualRecovery());
    }

    private static CrossLevelTransferRecoveryDecision plan(
            final CrossLevelTransferPhase phase,
            final boolean sourcePresent,
            final boolean sourceValid,
            final boolean targetPresent,
            final boolean targetValid,
            final boolean snapshotAvailable
    ) {
        return CrossLevelTransferRecoveryPlanner.plan(facts(
                phase,
                sourcePresent,
                sourceValid,
                targetPresent,
                targetValid,
                snapshotAvailable
        ));
    }

    private static CrossLevelTransferRecoveryFacts facts(
            final CrossLevelTransferPhase phase,
            final boolean sourcePresent,
            final boolean sourceValid,
            final boolean targetPresent,
            final boolean targetValid,
            final boolean snapshotAvailable
    ) {
        return new CrossLevelTransferRecoveryFacts(
                new CrossLevelTransferTransactionState(
                        UUID.randomUUID(),
                        UUID.randomUUID(),
                        "minecraft:overworld",
                        "starlance:space",
                        1,
                        2,
                        phase,
                        CrossLevelTransferTransactionState.CURRENT_SNAPSHOT_FORMAT_VERSION
                ),
                sourcePresent,
                sourceValid,
                targetPresent,
                targetValid,
                snapshotAvailable
        );
    }
}
