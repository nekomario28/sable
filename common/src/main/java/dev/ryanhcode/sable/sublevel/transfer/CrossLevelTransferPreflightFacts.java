package dev.ryanhcode.sable.sublevel.transfer;

import java.util.Objects;

/**
 * Immutable facts consumed by cross-level transfer preflight validation.
 *
 * <p>The builder is fail-closed: omitted facts remain in their unsafe state and
 * validation rejects the transfer.</p>
 */
public final class CrossLevelTransferPreflightFacts {
    private final String sourceDimension;
    private final String targetDimension;
    private final boolean sourceRemoved;
    private final boolean sourceContainerAvailable;
    private final boolean targetContainerAvailable;
    private final boolean targetPhysicsAvailable;
    private final boolean duplicateTargetUuid;
    private final boolean dependenciesPresent;
    private final boolean activeKinematicContraption;
    private final boolean compatibleSectionLayout;
    private final boolean targetSlotOccupied;
    private final boolean entitiesPresent;
    private final boolean transactionConflict;
    private final boolean snapshotAvailable;

    private CrossLevelTransferPreflightFacts(final Builder builder) {
        this.sourceDimension = builder.sourceDimension;
        this.targetDimension = builder.targetDimension;
        this.sourceRemoved = builder.sourceRemoved;
        this.sourceContainerAvailable = builder.sourceContainerAvailable;
        this.targetContainerAvailable = builder.targetContainerAvailable;
        this.targetPhysicsAvailable = builder.targetPhysicsAvailable;
        this.duplicateTargetUuid = builder.duplicateTargetUuid;
        this.dependenciesPresent = builder.dependenciesPresent;
        this.activeKinematicContraption = builder.activeKinematicContraption;
        this.compatibleSectionLayout = builder.compatibleSectionLayout;
        this.targetSlotOccupied = builder.targetSlotOccupied;
        this.entitiesPresent = builder.entitiesPresent;
        this.transactionConflict = builder.transactionConflict;
        this.snapshotAvailable = builder.snapshotAvailable;
    }

    public static Builder builder(final String sourceDimension, final String targetDimension) {
        return new Builder(sourceDimension, targetDimension);
    }

    public String sourceDimension() {
        return this.sourceDimension;
    }

    public String targetDimension() {
        return this.targetDimension;
    }

    public boolean sourceRemoved() {
        return this.sourceRemoved;
    }

    public boolean sourceContainerAvailable() {
        return this.sourceContainerAvailable;
    }

    public boolean targetContainerAvailable() {
        return this.targetContainerAvailable;
    }

    public boolean targetPhysicsAvailable() {
        return this.targetPhysicsAvailable;
    }

    public boolean duplicateTargetUuid() {
        return this.duplicateTargetUuid;
    }

    public boolean dependenciesPresent() {
        return this.dependenciesPresent;
    }

    public boolean activeKinematicContraption() {
        return this.activeKinematicContraption;
    }

    public boolean compatibleSectionLayout() {
        return this.compatibleSectionLayout;
    }

    public boolean targetSlotOccupied() {
        return this.targetSlotOccupied;
    }

    public boolean entitiesPresent() {
        return this.entitiesPresent;
    }

    public boolean transactionConflict() {
        return this.transactionConflict;
    }

    public boolean snapshotAvailable() {
        return this.snapshotAvailable;
    }

    public static final class Builder {
        private final String sourceDimension;
        private final String targetDimension;
        private boolean sourceRemoved = true;
        private boolean sourceContainerAvailable;
        private boolean targetContainerAvailable;
        private boolean targetPhysicsAvailable;
        private boolean duplicateTargetUuid = true;
        private boolean dependenciesPresent = true;
        private boolean activeKinematicContraption = true;
        private boolean compatibleSectionLayout;
        private boolean targetSlotOccupied = true;
        private boolean entitiesPresent = true;
        private boolean transactionConflict = true;
        private boolean snapshotAvailable;

        private Builder(final String sourceDimension, final String targetDimension) {
            this.sourceDimension = requireDimension(sourceDimension, "sourceDimension");
            this.targetDimension = requireDimension(targetDimension, "targetDimension");
        }

        private static String requireDimension(final String dimension, final String name) {
            Objects.requireNonNull(dimension, name);
            if (dimension.isBlank()) {
                throw new IllegalArgumentException(name + " must not be blank");
            }
            return dimension;
        }

        public Builder sourceRemoved(final boolean sourceRemoved) {
            this.sourceRemoved = sourceRemoved;
            return this;
        }

        public Builder sourceContainerAvailable(final boolean sourceContainerAvailable) {
            this.sourceContainerAvailable = sourceContainerAvailable;
            return this;
        }

        public Builder targetContainerAvailable(final boolean targetContainerAvailable) {
            this.targetContainerAvailable = targetContainerAvailable;
            return this;
        }

        public Builder targetPhysicsAvailable(final boolean targetPhysicsAvailable) {
            this.targetPhysicsAvailable = targetPhysicsAvailable;
            return this;
        }

        public Builder duplicateTargetUuid(final boolean duplicateTargetUuid) {
            this.duplicateTargetUuid = duplicateTargetUuid;
            return this;
        }

        public Builder dependenciesPresent(final boolean dependenciesPresent) {
            this.dependenciesPresent = dependenciesPresent;
            return this;
        }

        public Builder activeKinematicContraption(final boolean activeKinematicContraption) {
            this.activeKinematicContraption = activeKinematicContraption;
            return this;
        }

        public Builder compatibleSectionLayout(final boolean compatibleSectionLayout) {
            this.compatibleSectionLayout = compatibleSectionLayout;
            return this;
        }

        public Builder targetSlotOccupied(final boolean targetSlotOccupied) {
            this.targetSlotOccupied = targetSlotOccupied;
            return this;
        }

        public Builder entitiesPresent(final boolean entitiesPresent) {
            this.entitiesPresent = entitiesPresent;
            return this;
        }

        public Builder transactionConflict(final boolean transactionConflict) {
            this.transactionConflict = transactionConflict;
            return this;
        }

        public Builder snapshotAvailable(final boolean snapshotAvailable) {
            this.snapshotAvailable = snapshotAvailable;
            return this;
        }

        public CrossLevelTransferPreflightFacts build() {
            return new CrossLevelTransferPreflightFacts(this);
        }
    }
}
