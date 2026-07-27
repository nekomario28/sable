package dev.ryanhcode.sable.sublevel.transfer;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class CrossLevelTransferJournalDurabilityVerifierTest {
    @Test
    void readableEqualJournalMatches() {
        final CrossLevelTransferJournalSnapshot expected = snapshot(1, 2);
        final CrossLevelTransferJournalSavedData data = CrossLevelTransferJournalSavedData.load(
                CrossLevelTransferJournalSnapshotCodec.encode(expected)
        );

        assertTrue(CrossLevelTransferJournalDurabilityVerifier.matchesExpectedJournal(expected, data));
    }

    @Test
    void differentReadableJournalDoesNotMatch() {
        final CrossLevelTransferJournalSnapshot expected = snapshot(3, 4);
        final CrossLevelTransferJournalSavedData different = CrossLevelTransferJournalSavedData.load(
                CrossLevelTransferJournalSnapshotCodec.encode(snapshot(5, 6))
        );

        assertFalse(CrossLevelTransferJournalDurabilityVerifier.matchesExpectedJournal(expected, different));
    }

    @Test
    void corruptPreservedJournalDoesNotMatch() {
        final CompoundTag corrupt = new CompoundTag();
        corrupt.putInt("codec_version", Integer.MAX_VALUE);
        corrupt.put("transactions", new ListTag());
        final CrossLevelTransferJournalSavedData data = CrossLevelTransferJournalSavedData.load(corrupt);

        assertEquals(CrossLevelTransferJournalLoadStatus.CORRUPT_PRESERVED, data.loadStatus());
        assertFalse(CrossLevelTransferJournalDurabilityVerifier.matchesExpectedJournal(snapshot(7, 8), data));
    }

    @Test
    void durabilityStatusExposesOnlyExactSuccess() {
        assertTrue(CrossLevelTransferJournalDurabilityStatus.DURABLE.isDurable());
        for (final CrossLevelTransferJournalDurabilityStatus status :
                CrossLevelTransferJournalDurabilityStatus.values()) {
            if (status != CrossLevelTransferJournalDurabilityStatus.DURABLE) {
                assertFalse(status.isDurable(), status.name());
            }
        }
    }

    @Test
    void nullContractsAreRejectedBeforeComparison() {
        final CrossLevelTransferJournalSnapshot expected = snapshot(9, 10);
        final CrossLevelTransferJournalSavedData data = CrossLevelTransferJournalSavedData.createEmpty();

        assertThrows(
                NullPointerException.class,
                () -> CrossLevelTransferJournalDurabilityVerifier.matchesExpectedJournal(null, data)
        );
        assertThrows(
                NullPointerException.class,
                () -> CrossLevelTransferJournalDurabilityVerifier.matchesExpectedJournal(expected, null)
        );
    }

    private static CrossLevelTransferJournalSnapshot snapshot(
            final int localPlotX,
            final int localPlotZ
    ) {
        return CrossLevelTransferJournalSnapshot.of(List.of(
                new CrossLevelTransferTransactionState(
                        UUID.randomUUID(),
                        UUID.randomUUID(),
                        "minecraft:overworld",
                        "starlance:space",
                        localPlotX,
                        localPlotZ,
                        CrossLevelTransferPhase.SNAPSHOT_WRITTEN,
                        CrossLevelTransferTransactionState.CURRENT_SNAPSHOT_FORMAT_VERSION
                )
        ));
    }
}
