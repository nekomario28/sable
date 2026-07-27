package dev.ryanhcode.sable.sublevel.transfer;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class CrossLevelTransferOwnershipRestoreTest {
    @Test
    void snapshotRestoresEveryOwnershipIndexAndPhase() {
        final CrossLevelTransferTransactionState snapshotWritten = state(
                UUID.fromString("00000000-0000-0000-0000-000000000001"),
                UUID.randomUUID(),
                1,
                2,
                CrossLevelTransferPhase.SNAPSHOT_WRITTEN
        );
        final CrossLevelTransferTransactionState targetLoaded = state(
                UUID.fromString("00000000-0000-0000-0000-000000000002"),
                UUID.randomUUID(),
                3,
                4,
                CrossLevelTransferPhase.TARGET_LOADED
        );
        final CrossLevelTransferOwnershipRegistry sourceRegistry = new CrossLevelTransferOwnershipRegistry();
        sourceRegistry.restore(CrossLevelTransferJournalSnapshot.of(List.of(targetLoaded, snapshotWritten)));

        final CrossLevelTransferJournalSnapshot encodedState = sourceRegistry.snapshot();
        final CrossLevelTransferOwnershipRegistry restoredRegistry = new CrossLevelTransferOwnershipRegistry();
        restoredRegistry.restore(encodedState);

        assertEquals(encodedState, restoredRegistry.snapshot());
        assertEquals(2, restoredRegistry.size());
        assertEquals(snapshotWritten, restoredRegistry.state(snapshotWritten.transactionId()).orElseThrow());
        assertEquals(targetLoaded, restoredRegistry.state(targetLoaded.transactionId()).orElseThrow());
        assertEquals(
                targetLoaded.transactionId(),
                restoredRegistry.ownerOfSubLevel(targetLoaded.subLevelId()).orElseThrow()
        );
        assertEquals(
                snapshotWritten.transactionId(),
                restoredRegistry.ownerOfTargetSlot(CrossLevelTransferTargetSlot.from(snapshotWritten)).orElseThrow()
        );
    }

    @Test
    void restoredOwnershipRejectsNewConflictingTransactions() {
        final CrossLevelTransferTransactionState restored = state(
                UUID.randomUUID(),
                UUID.randomUUID(),
                7,
                8,
                CrossLevelTransferPhase.TARGET_RESERVED
        );
        final CrossLevelTransferOwnershipRegistry registry = new CrossLevelTransferOwnershipRegistry();
        registry.restore(CrossLevelTransferJournalSnapshot.of(List.of(restored)));

        assertEquals(
                CrossLevelTransferOwnershipResult.SUB_LEVEL_CONFLICT,
                registry.acquire(state(
                        UUID.randomUUID(),
                        restored.subLevelId(),
                        11,
                        12,
                        CrossLevelTransferPhase.PREPARING
                ))
        );
        assertEquals(
                CrossLevelTransferOwnershipResult.TARGET_SLOT_CONFLICT,
                registry.acquire(state(
                        UUID.randomUUID(),
                        UUID.randomUUID(),
                        restored.localPlotX(),
                        restored.localPlotZ(),
                        CrossLevelTransferPhase.PREPARING
                ))
        );
        assertEquals(1, registry.size());
    }

    @Test
    void restoreRequiresAnEmptyRegistry() {
        final CrossLevelTransferOwnershipRegistry registry = new CrossLevelTransferOwnershipRegistry();
        final CrossLevelTransferTransactionState live = state(
                UUID.randomUUID(),
                UUID.randomUUID(),
                1,
                1,
                CrossLevelTransferPhase.PREPARING
        );
        assertTrue(registry.acquire(live).ownsTransfer());

        final CrossLevelTransferJournalSnapshot durable = CrossLevelTransferJournalSnapshot.of(List.of(
                state(UUID.randomUUID(), UUID.randomUUID(), 2, 2, CrossLevelTransferPhase.SNAPSHOT_WRITTEN)
        ));

        assertThrows(IllegalStateException.class, () -> registry.restore(durable));
        assertEquals(CrossLevelTransferJournalSnapshot.of(List.of(live)), registry.snapshot());
    }

    @Test
    void restoredTerminalTransactionCanBeReleased() {
        final CrossLevelTransferTransactionState rolledBack = state(
                UUID.randomUUID(),
                UUID.randomUUID(),
                -1,
                -2,
                CrossLevelTransferPhase.ROLLED_BACK
        );
        final CrossLevelTransferOwnershipRegistry registry = new CrossLevelTransferOwnershipRegistry();
        registry.restore(CrossLevelTransferJournalSnapshot.of(List.of(rolledBack)));

        assertTrue(registry.release(rolledBack.transactionId()));
        assertTrue(registry.snapshot().isEmpty());
    }

    @Test
    void emptySnapshotRestoresAsEmpty() {
        final CrossLevelTransferOwnershipRegistry registry = new CrossLevelTransferOwnershipRegistry();
        registry.restore(CrossLevelTransferJournalSnapshot.empty());
        assertTrue(registry.snapshot().isEmpty());
        assertEquals(0, registry.size());
    }

    private static CrossLevelTransferTransactionState state(
            final UUID transactionId,
            final UUID subLevelId,
            final int localPlotX,
            final int localPlotZ,
            final CrossLevelTransferPhase phase
    ) {
        return new CrossLevelTransferTransactionState(
                transactionId,
                subLevelId,
                "minecraft:overworld",
                "starlance:space",
                localPlotX,
                localPlotZ,
                phase,
                CrossLevelTransferTransactionState.CURRENT_SNAPSHOT_FORMAT_VERSION
        );
    }
}
