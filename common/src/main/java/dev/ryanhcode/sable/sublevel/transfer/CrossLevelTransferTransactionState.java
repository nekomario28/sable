package dev.ryanhcode.sable.sublevel.transfer;

import java.util.Objects;
import java.util.UUID;

/**
 * Immutable durable state for one cross-level sub-level transfer transaction.
 */
public final class CrossLevelTransferTransactionState {
    public static final int CURRENT_SNAPSHOT_FORMAT_VERSION = 1;

    private final UUID transactionId;
    private final UUID subLevelId;
    private final String sourceDimension;
    private final String targetDimension;
    private final int localPlotX;
    private final int localPlotZ;
    private final CrossLevelTransferPhase phase;
    private final int snapshotFormatVersion;

    public CrossLevelTransferTransactionState(
            final UUID transactionId,
            final UUID subLevelId,
            final String sourceDimension,
            final String targetDimension,
            final int localPlotX,
            final int localPlotZ,
            final CrossLevelTransferPhase phase,
            final int snapshotFormatVersion
    ) {
        this.transactionId = Objects.requireNonNull(transactionId, "transactionId");
        this.subLevelId = Objects.requireNonNull(subLevelId, "subLevelId");
        this.sourceDimension = requireDimension(sourceDimension, "sourceDimension");
        this.targetDimension = requireDimension(targetDimension, "targetDimension");
        this.phase = Objects.requireNonNull(phase, "phase");

        if (this.sourceDimension.equals(this.targetDimension)) {
            throw new IllegalArgumentException("Source and target dimensions must differ");
        }
        if (snapshotFormatVersion < 1) {
            throw new IllegalArgumentException("Snapshot format version must be positive");
        }

        this.localPlotX = localPlotX;
        this.localPlotZ = localPlotZ;
        this.snapshotFormatVersion = snapshotFormatVersion;
    }

    private static String requireDimension(final String dimension, final String name) {
        Objects.requireNonNull(dimension, name);
        if (dimension.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return dimension;
    }

    public UUID transactionId() {
        return this.transactionId;
    }

    public UUID subLevelId() {
        return this.subLevelId;
    }

    public String sourceDimension() {
        return this.sourceDimension;
    }

    public String targetDimension() {
        return this.targetDimension;
    }

    public int localPlotX() {
        return this.localPlotX;
    }

    public int localPlotZ() {
        return this.localPlotZ;
    }

    public CrossLevelTransferPhase phase() {
        return this.phase;
    }

    public int snapshotFormatVersion() {
        return this.snapshotFormatVersion;
    }

    /**
     * Returns a new state advanced by exactly one valid lifecycle transition.
     *
     * @param next the next durable phase
     * @return the advanced immutable state
     */
    public CrossLevelTransferTransactionState advanceTo(final CrossLevelTransferPhase next) {
        if (!this.phase.canAdvanceTo(next)) {
            throw new IllegalStateException("Invalid transfer phase transition: " + this.phase + " -> " + next);
        }

        return new CrossLevelTransferTransactionState(
                this.transactionId,
                this.subLevelId,
                this.sourceDimension,
                this.targetDimension,
                this.localPlotX,
                this.localPlotZ,
                next,
                this.snapshotFormatVersion
        );
    }

    @Override
    public boolean equals(final Object object) {
        if (this == object) return true;
        if (!(object instanceof final CrossLevelTransferTransactionState that)) return false;
        return this.localPlotX == that.localPlotX &&
                this.localPlotZ == that.localPlotZ &&
                this.snapshotFormatVersion == that.snapshotFormatVersion &&
                this.transactionId.equals(that.transactionId) &&
                this.subLevelId.equals(that.subLevelId) &&
                this.sourceDimension.equals(that.sourceDimension) &&
                this.targetDimension.equals(that.targetDimension) &&
                this.phase == that.phase;
    }

    @Override
    public int hashCode() {
        return Objects.hash(
                this.transactionId,
                this.subLevelId,
                this.sourceDimension,
                this.targetDimension,
                this.localPlotX,
                this.localPlotZ,
                this.phase,
                this.snapshotFormatVersion
        );
    }

    @Override
    public String toString() {
        return "CrossLevelTransferTransactionState[" +
                "transactionId=" + this.transactionId +
                ", subLevelId=" + this.subLevelId +
                ", sourceDimension='" + this.sourceDimension + '\'' +
                ", targetDimension='" + this.targetDimension + '\'' +
                ", localPlotX=" + this.localPlotX +
                ", localPlotZ=" + this.localPlotZ +
                ", phase=" + this.phase +
                ", snapshotFormatVersion=" + this.snapshotFormatVersion +
                ']';
    }
}
