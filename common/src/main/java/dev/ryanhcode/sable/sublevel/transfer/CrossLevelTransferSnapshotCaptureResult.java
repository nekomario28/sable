package dev.ryanhcode.sable.sublevel.transfer;

import java.util.Objects;
import java.util.Optional;

/**
 * Immutable typed result of one live snapshot capture attempt.
 */
public record CrossLevelTransferSnapshotCaptureResult(
        CrossLevelTransferSnapshotCaptureStatus status,
        Optional<CrossLevelTransferSnapshotEnvelope> envelope
) {
    public CrossLevelTransferSnapshotCaptureResult {
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(envelope, "envelope");
        if (status.isCaptured() != envelope.isPresent()) {
            throw new IllegalArgumentException("Only CAPTURED results may contain an envelope");
        }
    }

    public static CrossLevelTransferSnapshotCaptureResult captured(
            final CrossLevelTransferSnapshotEnvelope envelope
    ) {
        return new CrossLevelTransferSnapshotCaptureResult(
                CrossLevelTransferSnapshotCaptureStatus.CAPTURED,
                Optional.of(Objects.requireNonNull(envelope, "envelope"))
        );
    }

    public static CrossLevelTransferSnapshotCaptureResult failed(
            final CrossLevelTransferSnapshotCaptureStatus status
    ) {
        Objects.requireNonNull(status, "status");
        if (status.isCaptured()) {
            throw new IllegalArgumentException("CAPTURED requires an envelope");
        }
        return new CrossLevelTransferSnapshotCaptureResult(status, Optional.empty());
    }

    public boolean isCaptured() {
        return this.status.isCaptured();
    }
}
