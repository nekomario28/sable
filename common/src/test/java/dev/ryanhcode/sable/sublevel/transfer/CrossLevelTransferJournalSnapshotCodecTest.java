package dev.ryanhcode.sable.sublevel.transfer;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class CrossLevelTransferJournalSnapshotCodecTest {
    private static final UUID TRANSACTION_ONE = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID TRANSACTION_TWO = UUID.fromString("00000000-0000-0000-0000-000000000002");

    @Test
    void journalRoundTripsInDeterministicTransactionOrder() {
        final CrossLevelTransferTransactionState second = state(
                TRANSACTION_TWO,
                UUID.randomUUID(),
                2,
                2,
                CrossLevelTransferPhase.TARGET_LOADED
        );
        final CrossLevelTransferTransactionState first = state(
                TRANSACTION_ONE,
                UUID.randomUUID(),
                1,
                1,
                CrossLevelTransferPhase.SNAPSHOT_WRITTEN
        );

        final CrossLevelTransferJournalSnapshot snapshot = CrossLevelTransferJournalSnapshot.of(List.of(second, first));
        assertEquals(List.of(first, second), snapshot.states());
        assertThrows(UnsupportedOperationException.class, () -> snapshot.states().clear());

        final CompoundTag encoded = CrossLevelTransferJournalSnapshotCodec.encode(snapshot);
        final CrossLevelTransferJournalSnapshot decoded =
                CrossLevelTransferJournalSnapshotCodec.decode(encoded).orElseThrow();

        assertEquals(snapshot, decoded);
        assertEquals(first, decoded.state(TRANSACTION_ONE).orElseThrow());
    }

    @Test
    void emptyJournalRoundTrips() {
        final CrossLevelTransferJournalSnapshot empty = CrossLevelTransferJournalSnapshot.empty();
        assertTrue(empty.isEmpty());
        assertEquals(0, empty.size());
        assertEquals(
                empty,
                CrossLevelTransferJournalSnapshotCodec.decode(
                        CrossLevelTransferJournalSnapshotCodec.encode(empty)
                ).orElseThrow()
        );
    }

    @Test
    void snapshotRejectsEveryOwnershipConflict() {
        final UUID sharedSubLevel = UUID.randomUUID();
        final CrossLevelTransferTransactionState base = state(
                TRANSACTION_ONE,
                sharedSubLevel,
                4,
                5,
                CrossLevelTransferPhase.PREPARING
        );

        assertThrows(IllegalArgumentException.class, () -> CrossLevelTransferJournalSnapshot.of(List.of(
                base,
                state(TRANSACTION_ONE, UUID.randomUUID(), 8, 9, CrossLevelTransferPhase.PREPARING)
        )));

        assertThrows(IllegalArgumentException.class, () -> CrossLevelTransferJournalSnapshot.of(List.of(
                base,
                state(TRANSACTION_TWO, sharedSubLevel, 8, 9, CrossLevelTransferPhase.PREPARING)
        )));

        assertThrows(IllegalArgumentException.class, () -> CrossLevelTransferJournalSnapshot.of(List.of(
                base,
                state(TRANSACTION_TWO, UUID.randomUUID(), 4, 5, CrossLevelTransferPhase.PREPARING)
        )));
    }

    @Test
    void decodeRejectsMalformedEntryAndWholeJournal() {
        final CrossLevelTransferJournalSnapshot snapshot = CrossLevelTransferJournalSnapshot.of(List.of(
                state(TRANSACTION_ONE, UUID.randomUUID(), 1, 1, CrossLevelTransferPhase.SNAPSHOT_WRITTEN)
        ));

        final CompoundTag malformedEntry = CrossLevelTransferJournalSnapshotCodec.encode(snapshot);
        malformedEntry.getList("transactions", Tag.TAG_COMPOUND).getCompound(0).remove("phase");
        assertTrue(CrossLevelTransferJournalSnapshotCodec.decode(malformedEntry).isEmpty());

        final CompoundTag duplicateEntry = CrossLevelTransferJournalSnapshotCodec.encode(snapshot);
        final ListTag transactions = duplicateEntry.getList("transactions", Tag.TAG_COMPOUND);
        transactions.add(transactions.getCompound(0).copy());
        assertTrue(CrossLevelTransferJournalSnapshotCodec.decode(duplicateEntry).isEmpty());
    }

    @Test
    void decodeRejectsUnsupportedVersionAndWrongTypes() {
        final CompoundTag futureVersion = CrossLevelTransferJournalSnapshotCodec.encode(
                CrossLevelTransferJournalSnapshot.empty()
        );
        futureVersion.putInt("codec_version", 2);
        assertTrue(CrossLevelTransferJournalSnapshotCodec.decode(futureVersion).isEmpty());

        final CompoundTag wrongTransactionsType = new CompoundTag();
        wrongTransactionsType.putInt("codec_version", 1);
        wrongTransactionsType.putString("transactions", "not-a-list");
        assertTrue(CrossLevelTransferJournalSnapshotCodec.decode(wrongTransactionsType).isEmpty());

        assertThrows(NullPointerException.class, () -> CrossLevelTransferJournalSnapshotCodec.encode(null));
        assertThrows(NullPointerException.class, () -> CrossLevelTransferJournalSnapshotCodec.decode(null));
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
