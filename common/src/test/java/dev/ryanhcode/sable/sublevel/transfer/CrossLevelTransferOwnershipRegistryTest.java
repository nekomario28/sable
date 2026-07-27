package dev.ryanhcode.sable.sublevel.transfer;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.*;

class CrossLevelTransferOwnershipRegistryTest {
    @Test
    void identicalAcquisitionIsIdempotent() {
        final CrossLevelTransferOwnershipRegistry registry = new CrossLevelTransferOwnershipRegistry();
        final CrossLevelTransferTransactionState state = state(
                UUID.randomUUID(),
                UUID.randomUUID(),
                "starlance:space",
                3,
                5
        );

        assertEquals(CrossLevelTransferOwnershipResult.ACQUIRED, registry.acquire(state));
        assertEquals(CrossLevelTransferOwnershipResult.ALREADY_OWNED, registry.acquire(state));
        assertEquals(1, registry.size());
        assertEquals(state, registry.state(state.transactionId()).orElseThrow());
        assertEquals(state.transactionId(), registry.ownerOfSubLevel(state.subLevelId()).orElseThrow());
        assertEquals(
                state.transactionId(),
                registry.ownerOfTargetSlot(CrossLevelTransferTargetSlot.from(state)).orElseThrow()
        );
    }

    @Test
    void conflictingIdentityAndResourcesAreRejected() {
        final CrossLevelTransferOwnershipRegistry registry = new CrossLevelTransferOwnershipRegistry();
        final UUID transactionId = UUID.randomUUID();
        final UUID subLevelId = UUID.randomUUID();
        final CrossLevelTransferTransactionState owned = state(
                transactionId,
                subLevelId,
                "starlance:space",
                1,
                2
        );
        assertEquals(CrossLevelTransferOwnershipResult.ACQUIRED, registry.acquire(owned));

        final CrossLevelTransferTransactionState reusedTransactionId = state(
                transactionId,
                UUID.randomUUID(),
                "starlance:space",
                8,
                9
        );
        assertEquals(
                CrossLevelTransferOwnershipResult.TRANSACTION_ID_CONFLICT,
                registry.acquire(reusedTransactionId)
        );

        final CrossLevelTransferTransactionState reusedSubLevel = state(
                UUID.randomUUID(),
                subLevelId,
                "starlance:space",
                8,
                9
        );
        assertEquals(CrossLevelTransferOwnershipResult.SUB_LEVEL_CONFLICT, registry.acquire(reusedSubLevel));

        final CrossLevelTransferTransactionState reusedTargetSlot = state(
                UUID.randomUUID(),
                UUID.randomUUID(),
                "starlance:space",
                1,
                2
        );
        assertEquals(
                CrossLevelTransferOwnershipResult.TARGET_SLOT_CONFLICT,
                registry.acquire(reusedTargetSlot)
        );

        assertEquals(1, registry.size());
    }

    @Test
    void ownershipSurvivesAdvancementAndReleasesOnlyWhenTerminal() {
        final CrossLevelTransferOwnershipRegistry registry = new CrossLevelTransferOwnershipRegistry();
        final CrossLevelTransferTransactionState preparing = state(
                UUID.randomUUID(),
                UUID.randomUUID(),
                "starlance:space",
                -4,
                12
        );
        assertTrue(registry.acquire(preparing).ownsTransfer());

        assertThrows(IllegalStateException.class, () -> registry.release(preparing.transactionId()));

        final CrossLevelTransferTransactionState snapshot = registry.advance(
                preparing.transactionId(),
                CrossLevelTransferPhase.SNAPSHOT_WRITTEN
        );
        assertEquals(CrossLevelTransferPhase.SNAPSHOT_WRITTEN, snapshot.phase());
        assertEquals(preparing.transactionId(), registry.ownerOfSubLevel(preparing.subLevelId()).orElseThrow());

        final CrossLevelTransferTransactionState rolledBack = registry.advance(
                preparing.transactionId(),
                CrossLevelTransferPhase.ROLLED_BACK
        );
        assertTrue(rolledBack.phase().isTerminal());
        assertTrue(registry.release(preparing.transactionId()));
        assertFalse(registry.release(preparing.transactionId()));
        assertEquals(0, registry.size());
        assertTrue(registry.ownerOfSubLevel(preparing.subLevelId()).isEmpty());
        assertTrue(registry.ownerOfTargetSlot(CrossLevelTransferTargetSlot.from(preparing)).isEmpty());
    }

    @Test
    void onlyOneConcurrentTransactionCanClaimOneSubLevel() throws Exception {
        final CrossLevelTransferOwnershipRegistry registry = new CrossLevelTransferOwnershipRegistry();
        final UUID sharedSubLevelId = UUID.randomUUID();
        final int workerCount = 16;
        final CountDownLatch ready = new CountDownLatch(workerCount);
        final CountDownLatch start = new CountDownLatch(1);
        final ExecutorService executor = Executors.newFixedThreadPool(workerCount);
        final List<Future<CrossLevelTransferOwnershipResult>> futures = new ArrayList<>();

        try {
            for (int index = 0; index < workerCount; index++) {
                final int targetX = index;
                futures.add(executor.submit(() -> {
                    ready.countDown();
                    start.await();
                    return registry.acquire(state(
                            UUID.randomUUID(),
                            sharedSubLevelId,
                            "starlance:space",
                            targetX,
                            0
                    ));
                }));
            }

            ready.await();
            start.countDown();

            int acquired = 0;
            int subLevelConflicts = 0;
            for (final Future<CrossLevelTransferOwnershipResult> future : futures) {
                final CrossLevelTransferOwnershipResult result = future.get();
                if (result == CrossLevelTransferOwnershipResult.ACQUIRED) {
                    acquired++;
                } else if (result == CrossLevelTransferOwnershipResult.SUB_LEVEL_CONFLICT) {
                    subLevelConflicts++;
                }
            }

            assertEquals(1, acquired);
            assertEquals(workerCount - 1, subLevelConflicts);
            assertEquals(1, registry.size());
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void acquisitionRequiresPreparingState() {
        final CrossLevelTransferOwnershipRegistry registry = new CrossLevelTransferOwnershipRegistry();
        final CrossLevelTransferTransactionState preparing = state(
                UUID.randomUUID(),
                UUID.randomUUID(),
                "starlance:space",
                0,
                0
        );

        assertThrows(
                IllegalArgumentException.class,
                () -> registry.acquire(preparing.advanceTo(CrossLevelTransferPhase.SNAPSHOT_WRITTEN))
        );
        assertThrows(
                IllegalStateException.class,
                () -> registry.advance(UUID.randomUUID(), CrossLevelTransferPhase.SNAPSHOT_WRITTEN)
        );
    }

    private static CrossLevelTransferTransactionState state(
            final UUID transactionId,
            final UUID subLevelId,
            final String targetDimension,
            final int localPlotX,
            final int localPlotZ
    ) {
        return new CrossLevelTransferTransactionState(
                transactionId,
                subLevelId,
                "minecraft:overworld",
                targetDimension,
                localPlotX,
                localPlotZ,
                CrossLevelTransferPhase.PREPARING,
                CrossLevelTransferTransactionState.CURRENT_SNAPSHOT_FORMAT_VERSION
        );
    }
}
