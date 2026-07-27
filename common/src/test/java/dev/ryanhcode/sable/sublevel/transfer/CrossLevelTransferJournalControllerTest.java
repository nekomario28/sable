package dev.ryanhcode.sable.sublevel.transfer;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.*;

class CrossLevelTransferJournalControllerTest {
    @Test
    void openingReadableJournalRestoresOwnershipWithoutDirtyingData() {
        final CrossLevelTransferTransactionState state = state(
                UUID.randomUUID(),
                UUID.randomUUID(),
                CrossLevelTransferPhase.TARGET_RESERVED,
                1,
                2
        );
        final CrossLevelTransferJournalSavedData data = loadedJournal(state);

        final CrossLevelTransferJournalController controller = new CrossLevelTransferJournalController(data);

        assertEquals(1, controller.size());
        assertEquals(state, controller.state(state.transactionId()).orElseThrow());
        assertEquals(state.transactionId(), controller.ownerOfSubLevel(state.subLevelId()).orElseThrow());
        assertEquals(
                state.transactionId(),
                controller.ownerOfTargetSlot(CrossLevelTransferTargetSlot.from(state)).orElseThrow()
        );
        assertFalse(data.isDirty());
    }

    @Test
    void acquisitionSynchronizesTheCompleteJournal() {
        final CrossLevelTransferJournalSavedData data = CrossLevelTransferJournalSavedData.createEmpty();
        final CrossLevelTransferJournalController controller = new CrossLevelTransferJournalController(data);
        final CrossLevelTransferTransactionState state = state(
                UUID.randomUUID(),
                UUID.randomUUID(),
                CrossLevelTransferPhase.PREPARING,
                3,
                4
        );

        assertEquals(CrossLevelTransferOwnershipResult.ACQUIRED, controller.acquire(state));

        assertTrue(data.isDirty());
        assertEquals(controller.snapshot(), data.snapshot().orElseThrow());
        assertEquals(state, controller.state(state.transactionId()).orElseThrow());
    }

    @Test
    void idempotentAndConflictingAcquisitionDoNotRewriteJournal() {
        final UUID subLevelId = UUID.randomUUID();
        final CrossLevelTransferTransactionState owned = state(
                UUID.randomUUID(),
                subLevelId,
                CrossLevelTransferPhase.PREPARING,
                5,
                6
        );
        final CrossLevelTransferJournalSavedData data = loadedJournal(owned);
        final CrossLevelTransferJournalController controller = new CrossLevelTransferJournalController(data);

        assertEquals(CrossLevelTransferOwnershipResult.ALREADY_OWNED, controller.acquire(owned));
        assertFalse(data.isDirty());

        final CrossLevelTransferTransactionState conflict = state(
                UUID.randomUUID(),
                subLevelId,
                CrossLevelTransferPhase.PREPARING,
                7,
                8
        );
        assertEquals(CrossLevelTransferOwnershipResult.SUB_LEVEL_CONFLICT, controller.acquire(conflict));
        assertFalse(data.isDirty());
        assertEquals(CrossLevelTransferJournalSnapshot.of(List.of(owned)), data.snapshot().orElseThrow());
    }

    @Test
    void advancementAndTerminalReleaseSynchronizeJournal() {
        final CrossLevelTransferTransactionState preparing = state(
                UUID.randomUUID(),
                UUID.randomUUID(),
                CrossLevelTransferPhase.PREPARING,
                9,
                10
        );
        final CrossLevelTransferJournalSavedData data = loadedJournal(preparing);
        final CrossLevelTransferJournalController controller = new CrossLevelTransferJournalController(data);

        final CrossLevelTransferTransactionState snapshotWritten = controller.advance(
                preparing.transactionId(),
                CrossLevelTransferPhase.SNAPSHOT_WRITTEN
        );

        assertEquals(CrossLevelTransferPhase.SNAPSHOT_WRITTEN, snapshotWritten.phase());
        assertEquals(controller.snapshot(), data.snapshot().orElseThrow());
        assertTrue(data.isDirty());

        controller.advance(preparing.transactionId(), CrossLevelTransferPhase.TARGET_RESERVED);
        controller.advance(preparing.transactionId(), CrossLevelTransferPhase.TARGET_LOADED);
        controller.advance(preparing.transactionId(), CrossLevelTransferPhase.SOURCE_REMOVED);
        controller.advance(preparing.transactionId(), CrossLevelTransferPhase.COMMITTED);

        assertTrue(controller.release(preparing.transactionId()));
        assertTrue(controller.snapshot().isEmpty());
        assertEquals(CrossLevelTransferJournalSnapshot.empty(), data.snapshot().orElseThrow());
    }

    @Test
    void invalidAdvancementLeavesJournalUnchanged() {
        final CrossLevelTransferTransactionState preparing = state(
                UUID.randomUUID(),
                UUID.randomUUID(),
                CrossLevelTransferPhase.PREPARING,
                11,
                12
        );
        final CrossLevelTransferJournalSavedData data = loadedJournal(preparing);
        final CrossLevelTransferJournalController controller = new CrossLevelTransferJournalController(data);

        assertThrows(
                IllegalArgumentException.class,
                () -> controller.advance(preparing.transactionId(), CrossLevelTransferPhase.COMMITTED)
        );

        assertFalse(data.isDirty());
        assertEquals(CrossLevelTransferJournalSnapshot.of(List.of(preparing)), data.snapshot().orElseThrow());
    }

    @Test
    void corruptJournalCannotOpenController() {
        final CompoundTag corrupt = new CompoundTag();
        corrupt.putInt("codec_version", Integer.MAX_VALUE);
        corrupt.put("transactions", new ListTag());

        final CrossLevelTransferJournalSavedData data = CrossLevelTransferJournalSavedData.load(corrupt);

        assertThrows(IllegalStateException.class, () -> new CrossLevelTransferJournalController(data));
    }

    @Test
    void concurrentClaimsKeepJournalAndOwnershipConsistent() throws Exception {
        final CrossLevelTransferJournalSavedData data = CrossLevelTransferJournalSavedData.createEmpty();
        final CrossLevelTransferJournalController controller = new CrossLevelTransferJournalController(data);
        final UUID subLevelId = UUID.randomUUID();
        final int claimCount = 16;
        final ExecutorService executor = Executors.newFixedThreadPool(claimCount);

        try {
            final List<Callable<CrossLevelTransferOwnershipResult>> claims = new ArrayList<>();
            for (int index = 0; index < claimCount; index++) {
                final int plot = index;
                claims.add(() -> controller.acquire(state(
                        UUID.randomUUID(),
                        subLevelId,
                        CrossLevelTransferPhase.PREPARING,
                        plot,
                        plot + 100
                )));
            }

            final List<Future<CrossLevelTransferOwnershipResult>> futures = executor.invokeAll(claims);
            int acquired = 0;
            for (final Future<CrossLevelTransferOwnershipResult> future : futures) {
                if (future.get() == CrossLevelTransferOwnershipResult.ACQUIRED) {
                    acquired++;
                }
            }

            assertEquals(1, acquired);
            assertEquals(1, controller.size());
            assertEquals(controller.snapshot(), data.snapshot().orElseThrow());
            assertEquals(1, data.snapshot().orElseThrow().size());
        } finally {
            executor.shutdownNow();
        }
    }

    private static CrossLevelTransferJournalSavedData loadedJournal(
            final CrossLevelTransferTransactionState state
    ) {
        return CrossLevelTransferJournalSavedData.load(
                CrossLevelTransferJournalSnapshotCodec.encode(
                        CrossLevelTransferJournalSnapshot.of(List.of(state))
                )
        );
    }

    private static CrossLevelTransferTransactionState state(
            final UUID transactionId,
            final UUID subLevelId,
            final CrossLevelTransferPhase phase,
            final int plotX,
            final int plotZ
    ) {
        return new CrossLevelTransferTransactionState(
                transactionId,
                subLevelId,
                "minecraft:overworld",
                "starlance:space",
                plotX,
                plotZ,
                phase,
                CrossLevelTransferTransactionState.CURRENT_SNAPSHOT_FORMAT_VERSION
        );
    }
}
