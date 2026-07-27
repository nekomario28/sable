package dev.ryanhcode.sable.sublevel.transfer;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class CrossLevelTransferStateTest {
    @Test
    void phaseTransitionsAreStrictAndTerminal() {
        assertTrue(CrossLevelTransferPhase.PREPARING.canAdvanceTo(CrossLevelTransferPhase.SNAPSHOT_WRITTEN));
        assertFalse(CrossLevelTransferPhase.PREPARING.canAdvanceTo(CrossLevelTransferPhase.TARGET_LOADED));
        assertFalse(CrossLevelTransferPhase.PREPARING.canAdvanceTo(CrossLevelTransferPhase.PREPARING));
        assertTrue(CrossLevelTransferPhase.TARGET_LOADED.canAdvanceTo(CrossLevelTransferPhase.ROLLED_BACK));
        assertTrue(CrossLevelTransferPhase.COMMITTED.isTerminal());
        assertTrue(CrossLevelTransferPhase.ROLLED_BACK.isTerminal());
        assertFalse(CrossLevelTransferPhase.COMMITTED.canAdvanceTo(CrossLevelTransferPhase.ROLLED_BACK));
        assertFalse(CrossLevelTransferPhase.ROLLED_BACK.canAdvanceTo(CrossLevelTransferPhase.COMMITTED));
    }

    @Test
    void validationResultsAreImmutableAndDeduplicated() {
        final CrossLevelTransferValidation accepted = CrossLevelTransferValidation.accepted();
        assertTrue(accepted.isAccepted());
        assertTrue(accepted.failures().isEmpty());

        final CrossLevelTransferValidation rejected = CrossLevelTransferValidation.rejected(List.of(
                CrossLevelTransferFailure.SAME_LEVEL,
                CrossLevelTransferFailure.TARGET_SLOT_OCCUPIED,
                CrossLevelTransferFailure.SAME_LEVEL
        ));

        assertFalse(rejected.isAccepted());
        assertEquals(2, rejected.failures().size());
        assertTrue(rejected.hasFailure(CrossLevelTransferFailure.SAME_LEVEL));
        assertThrows(UnsupportedOperationException.class, rejected.failures()::clear);
        assertThrows(IllegalArgumentException.class, () -> CrossLevelTransferValidation.rejected(List.of()));
    }

    @Test
    void transactionAdvancementReturnsANewState() {
        final CrossLevelTransferTransactionState preparing = new CrossLevelTransferTransactionState(
                UUID.randomUUID(),
                UUID.randomUUID(),
                "minecraft:overworld",
                "starlance:space",
                4,
                7,
                CrossLevelTransferPhase.PREPARING,
                CrossLevelTransferTransactionState.CURRENT_SNAPSHOT_FORMAT_VERSION
        );

        final CrossLevelTransferTransactionState snapshotWritten = preparing.advanceTo(
                CrossLevelTransferPhase.SNAPSHOT_WRITTEN
        );

        assertEquals(CrossLevelTransferPhase.PREPARING, preparing.phase());
        assertEquals(CrossLevelTransferPhase.SNAPSHOT_WRITTEN, snapshotWritten.phase());
        assertEquals(preparing.transactionId(), snapshotWritten.transactionId());
        assertEquals(preparing.subLevelId(), snapshotWritten.subLevelId());
        assertThrows(IllegalStateException.class, () -> snapshotWritten.advanceTo(CrossLevelTransferPhase.COMMITTED));
    }

    @Test
    void transactionStateRejectsInvalidIdentityData() {
        assertThrows(IllegalArgumentException.class, () -> new CrossLevelTransferTransactionState(
                UUID.randomUUID(),
                UUID.randomUUID(),
                "minecraft:overworld",
                "minecraft:overworld",
                0,
                0,
                CrossLevelTransferPhase.PREPARING,
                1
        ));

        assertThrows(IllegalArgumentException.class, () -> new CrossLevelTransferTransactionState(
                UUID.randomUUID(),
                UUID.randomUUID(),
                "minecraft:overworld",
                "starlance:space",
                0,
                0,
                CrossLevelTransferPhase.PREPARING,
                0
        ));
    }
}
