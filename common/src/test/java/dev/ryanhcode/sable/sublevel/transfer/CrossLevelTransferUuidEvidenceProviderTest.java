package dev.ryanhcode.sable.sublevel.transfer;

import dev.ryanhcode.sable.sublevel.storage.holding.GlobalSavedSubLevelPointer;
import net.minecraft.world.level.ChunkPos;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class CrossLevelTransferUuidEvidenceProviderTest {
    @Test
    void completeIndexAtExactSourceLocationProvesTargetUniqueness() {
        final CrossLevelTransferTransactionState expected = state(1, 2);
        final CrossLevelTransferUuidLocationIndex index = completeIndex(
                expected.subLevelId(),
                new CrossLevelTransferSubLevelLocation.Loaded(
                        expected.sourceDimension(),
                        expected.localPlotX(),
                        expected.localPlotZ()
                )
        );

        final CrossLevelTransferAuthoritativePreflightEvidence evidence =
                CrossLevelTransferUuidEvidenceProvider.enrich(
                        new CrossLevelTransferAuthoritativePreflightEvidence(false, true, true),
                        index,
                        expected
                );

        assertTrue(evidence.targetUuidUnique());
        assertTrue(evidence.transactionOwned());
        assertTrue(evidence.snapshotVerified());
    }

    @Test
    void buildingIndexCannotProveTargetUniqueness() {
        final CrossLevelTransferTransactionState expected = state(3, 4);
        final CrossLevelTransferUuidLocationIndex index = new CrossLevelTransferUuidLocationIndex();
        index.register(
                expected.subLevelId(),
                new CrossLevelTransferSubLevelLocation.Loaded(
                        expected.sourceDimension(),
                        expected.localPlotX(),
                        expected.localPlotZ()
                )
        );

        final CrossLevelTransferAuthoritativePreflightEvidence evidence =
                CrossLevelTransferUuidEvidenceProvider.enrich(
                        new CrossLevelTransferAuthoritativePreflightEvidence(true, true, true),
                        index,
                        expected
                );

        assertFalse(evidence.targetUuidUnique());
        assertTrue(evidence.transactionOwned());
        assertTrue(evidence.snapshotVerified());
    }

    @Test
    void differentLoadedOrStoredLocationFailsClosed() {
        final CrossLevelTransferTransactionState expected = state(5, 6);

        final CrossLevelTransferUuidLocationIndex differentSlot = completeIndex(
                expected.subLevelId(),
                new CrossLevelTransferSubLevelLocation.Loaded(expected.sourceDimension(), 6, 5)
        );
        assertFalse(CrossLevelTransferUuidEvidenceProvider.enrich(
                CrossLevelTransferAuthoritativePreflightEvidence.unverified(),
                differentSlot,
                expected
        ).targetUuidUnique());

        final CrossLevelTransferUuidLocationIndex targetDimension = completeIndex(
                expected.subLevelId(),
                new CrossLevelTransferSubLevelLocation.Loaded(
                        expected.targetDimension(),
                        expected.localPlotX(),
                        expected.localPlotZ()
                )
        );
        assertFalse(CrossLevelTransferUuidEvidenceProvider.enrich(
                CrossLevelTransferAuthoritativePreflightEvidence.unverified(),
                targetDimension,
                expected
        ).targetUuidUnique());

        final CrossLevelTransferUuidLocationIndex stored = completeIndex(
                expected.subLevelId(),
                new CrossLevelTransferSubLevelLocation.Stored(
                        expected.sourceDimension(),
                        new GlobalSavedSubLevelPointer(new ChunkPos(7, 8), (short) 1, (short) 2)
                )
        );
        assertFalse(CrossLevelTransferUuidEvidenceProvider.enrich(
                CrossLevelTransferAuthoritativePreflightEvidence.unverified(),
                stored,
                expected
        ).targetUuidUnique());
    }

    @Test
    void missingUuidFailsClosed() {
        final CrossLevelTransferTransactionState expected = state(9, 10);
        final CrossLevelTransferUuidLocationIndex index = new CrossLevelTransferUuidLocationIndex();
        index.complete();

        assertFalse(CrossLevelTransferUuidEvidenceProvider.enrich(
                new CrossLevelTransferAuthoritativePreflightEvidence(true, false, true),
                index,
                expected
        ).targetUuidUnique());
    }

    @Test
    void nullContractsAreRejected() {
        final CrossLevelTransferTransactionState expected = state(11, 12);
        final CrossLevelTransferUuidLocationIndex index = new CrossLevelTransferUuidLocationIndex();
        index.complete();
        final CrossLevelTransferAuthoritativePreflightEvidence evidence =
                CrossLevelTransferAuthoritativePreflightEvidence.unverified();

        assertThrows(NullPointerException.class, () ->
                CrossLevelTransferUuidEvidenceProvider.enrich(null, index, expected));
        assertThrows(NullPointerException.class, () ->
                CrossLevelTransferUuidEvidenceProvider.enrich(evidence, null, expected));
        assertThrows(NullPointerException.class, () ->
                CrossLevelTransferUuidEvidenceProvider.enrich(evidence, index, null));
    }

    private static CrossLevelTransferUuidLocationIndex completeIndex(
            final UUID subLevelId,
            final CrossLevelTransferSubLevelLocation location
    ) {
        final CrossLevelTransferUuidLocationIndex index = new CrossLevelTransferUuidLocationIndex();
        index.register(subLevelId, location);
        index.complete();
        return index;
    }

    private static CrossLevelTransferTransactionState state(
            final int localPlotX,
            final int localPlotZ
    ) {
        return new CrossLevelTransferTransactionState(
                UUID.randomUUID(),
                UUID.randomUUID(),
                "minecraft:overworld",
                "starlance:space",
                localPlotX,
                localPlotZ,
                CrossLevelTransferPhase.PREPARING,
                CrossLevelTransferTransactionState.CURRENT_SNAPSHOT_FORMAT_VERSION
        );
    }
}
