package dev.ryanhcode.sable.sublevel.transfer;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class CrossLevelTransferJournalSavedDataTest {
    @Test
    void newJournalStartsReadableAndRoundTripsAsEmpty() {
        final CrossLevelTransferJournalSavedData data = CrossLevelTransferJournalSavedData.createEmpty();

        assertEquals(CrossLevelTransferJournalLoadStatus.READABLE, data.loadStatus());
        assertEquals(CrossLevelTransferJournalSnapshot.empty(), data.snapshot().orElseThrow());
        assertFalse(data.isDirty());

        final CompoundTag saved = data.save(new CompoundTag(), null);
        assertEquals(
                CrossLevelTransferJournalSnapshot.empty(),
                CrossLevelTransferJournalSnapshotCodec.decode(saved).orElseThrow()
        );
    }

    @Test
    void replacingReadableSnapshotMarksDataDirty() {
        final CrossLevelTransferJournalSavedData data = CrossLevelTransferJournalSavedData.createEmpty();
        final CrossLevelTransferJournalSnapshot snapshot = CrossLevelTransferJournalSnapshot.of(List.of(
                state(CrossLevelTransferPhase.SNAPSHOT_WRITTEN, 3, 4)
        ));

        data.replaceSnapshot(snapshot);

        assertTrue(data.isDirty());
        assertEquals(snapshot, data.snapshot().orElseThrow());
        assertEquals(
                snapshot,
                CrossLevelTransferJournalSnapshotCodec.decode(data.save(new CompoundTag(), null)).orElseThrow()
        );
    }

    @Test
    void replacingWithEqualSnapshotDoesNotDirtyFreshData() {
        final CrossLevelTransferJournalSavedData data = CrossLevelTransferJournalSavedData.createEmpty();

        data.replaceSnapshot(CrossLevelTransferJournalSnapshot.empty());

        assertFalse(data.isDirty());
    }

    @Test
    void validPersistedJournalLoadsReadableWithoutBecomingDirty() {
        final CrossLevelTransferJournalSnapshot snapshot = CrossLevelTransferJournalSnapshot.of(List.of(
                state(CrossLevelTransferPhase.TARGET_RESERVED, 7, 8)
        ));

        final CrossLevelTransferJournalSavedData data = CrossLevelTransferJournalSavedData.load(
                CrossLevelTransferJournalSnapshotCodec.encode(snapshot)
        );

        assertEquals(CrossLevelTransferJournalLoadStatus.READABLE, data.loadStatus());
        assertEquals(snapshot, data.snapshot().orElseThrow());
        assertFalse(data.isDirty());
    }

    @Test
    void corruptJournalIsPreservedAndCannotBeMutated() {
        final CompoundTag corrupt = corruptTag("retain-me");
        final CrossLevelTransferJournalSavedData data = CrossLevelTransferJournalSavedData.load(corrupt);

        assertEquals(CrossLevelTransferJournalLoadStatus.CORRUPT_PRESERVED, data.loadStatus());
        assertTrue(data.snapshot().isEmpty());
        assertFalse(data.isDirty());
        assertThrows(
                IllegalStateException.class,
                () -> data.replaceSnapshot(CrossLevelTransferJournalSnapshot.empty())
        );
        assertEquals(corrupt, data.save(new CompoundTag(), null));
    }

    @Test
    void corruptJournalInputIsDefensivelyCopied() {
        final CompoundTag corrupt = corruptTag("original");
        final CrossLevelTransferJournalSavedData data = CrossLevelTransferJournalSavedData.load(corrupt);

        corrupt.putString("operator_note", "mutated-after-load");

        final CompoundTag saved = data.save(new CompoundTag(), null);
        assertEquals("original", saved.getString("operator_note"));
    }

    @Test
    void nullContractsAreRejected() {
        assertThrows(NullPointerException.class, () -> CrossLevelTransferJournalSavedData.load(null));
        assertThrows(
                NullPointerException.class,
                () -> CrossLevelTransferJournalSavedData.createEmpty().replaceSnapshot(null)
        );
        assertThrows(
                NullPointerException.class,
                () -> CrossLevelTransferJournalSavedData.createEmpty().save((CompoundTag) null, null)
        );
    }

    private static CompoundTag corruptTag(final String note) {
        final CompoundTag tag = new CompoundTag();
        tag.putInt("codec_version", Integer.MAX_VALUE);
        tag.put("transactions", new ListTag());
        tag.putString("operator_note", note);
        return tag;
    }

    private static CrossLevelTransferTransactionState state(
            final CrossLevelTransferPhase phase,
            final int plotX,
            final int plotZ
    ) {
        return new CrossLevelTransferTransactionState(
                UUID.randomUUID(),
                UUID.randomUUID(),
                "minecraft:overworld",
                "starlance:space",
                plotX,
                plotZ,
                phase,
                CrossLevelTransferTransactionState.CURRENT_SNAPSHOT_FORMAT_VERSION
        );
    }
}
