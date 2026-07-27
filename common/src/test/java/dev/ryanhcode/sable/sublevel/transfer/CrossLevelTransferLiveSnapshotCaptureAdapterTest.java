package dev.ryanhcode.sable.sublevel.transfer;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class CrossLevelTransferLiveSnapshotCaptureAdapterTest {
    @Test
    void localSlotComparisonUsesContainerOrigin() {
        assertTrue(CrossLevelTransferLiveSnapshotCaptureAdapter.matchesExpectedLocalSlot(
                10_003,
                10_004,
                10_000,
                10_000,
                3,
                4
        ));
        assertFalse(CrossLevelTransferLiveSnapshotCaptureAdapter.matchesExpectedLocalSlot(
                10_003,
                10_004,
                10_000,
                10_000,
                4,
                3
        ));
    }

    @Test
    void dependencyIdentityRequiresExactlyTheSourceObject() {
        final Object source = new Object();
        final Object equalButDifferent = new String("source");
        final Object expectedString = "source";

        assertTrue(CrossLevelTransferLiveSnapshotCaptureAdapter.containsOnlyIdentity(
                List.of(source),
                source
        ));
        assertFalse(CrossLevelTransferLiveSnapshotCaptureAdapter.containsOnlyIdentity(
                List.of(),
                source
        ));
        assertFalse(CrossLevelTransferLiveSnapshotCaptureAdapter.containsOnlyIdentity(
                List.of(source, new Object()),
                source
        ));
        assertFalse(CrossLevelTransferLiveSnapshotCaptureAdapter.containsOnlyIdentity(
                List.of(equalButDifferent),
                expectedString
        ));
    }

    @Test
    void capturedResultRequiresExactlyOneEnvelope() {
        final CrossLevelTransferSnapshotEnvelope envelope = CrossLevelTransferSnapshotEnvelope.create(
                UUID.randomUUID(),
                UUID.randomUUID(),
                1,
                "snapshot".getBytes(StandardCharsets.UTF_8)
        );
        final CrossLevelTransferSnapshotCaptureResult result =
                CrossLevelTransferSnapshotCaptureResult.captured(envelope);

        assertTrue(result.isCaptured());
        assertEquals(CrossLevelTransferSnapshotCaptureStatus.CAPTURED, result.status());
        assertEquals(envelope, result.envelope().orElseThrow());
        assertThrows(IllegalArgumentException.class, () ->
                new CrossLevelTransferSnapshotCaptureResult(
                        CrossLevelTransferSnapshotCaptureStatus.CAPTURED,
                        Optional.empty()
                ));
    }

    @Test
    void failedResultCannotContainOrPretendToBeCaptured() {
        final CrossLevelTransferSnapshotCaptureResult failed =
                CrossLevelTransferSnapshotCaptureResult.failed(
                        CrossLevelTransferSnapshotCaptureStatus.DEPENDENCIES_PRESENT
                );

        assertFalse(failed.isCaptured());
        assertTrue(failed.envelope().isEmpty());
        assertThrows(IllegalArgumentException.class, () ->
                CrossLevelTransferSnapshotCaptureResult.failed(
                        CrossLevelTransferSnapshotCaptureStatus.CAPTURED
                ));
        assertThrows(IllegalArgumentException.class, () ->
                new CrossLevelTransferSnapshotCaptureResult(
                        CrossLevelTransferSnapshotCaptureStatus.SOURCE_REMOVED,
                        Optional.of(CrossLevelTransferSnapshotEnvelope.create(
                                UUID.randomUUID(),
                                UUID.randomUUID(),
                                1,
                                new byte[]{1}
                        ))
                ));
    }

    @Test
    void nullContractsAreRejectedBeforeLiveInspection() {
        final CrossLevelTransferTransactionState expected = new CrossLevelTransferTransactionState(
                UUID.randomUUID(),
                UUID.randomUUID(),
                "minecraft:overworld",
                "starlance:space",
                1,
                2,
                CrossLevelTransferPhase.PREPARING,
                1
        );

        assertThrows(
                NullPointerException.class,
                () -> CrossLevelTransferLiveSnapshotCaptureAdapter.capture(null, expected)
        );
        assertThrows(
                NullPointerException.class,
                () -> CrossLevelTransferLiveSnapshotCaptureAdapter.containsOnlyIdentity(null, new Object())
        );
        assertThrows(
                NullPointerException.class,
                () -> CrossLevelTransferLiveSnapshotCaptureAdapter.containsOnlyIdentity(List.of(), null)
        );
        assertThrows(
                NullPointerException.class,
                () -> CrossLevelTransferSnapshotCaptureResult.failed(null)
        );
    }
}
