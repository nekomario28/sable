package dev.ryanhcode.sable.physics.impl.rapier;

import dev.ryanhcode.sable.api.physics.SubLevelReconstructionRuntimeIdSupport;
import dev.ryanhcode.sable.api.physics.SubLevelReconstructionSectionSupport;
import dev.ryanhcode.sable.physics.impl.rapier.collider.RapierVoxelColliderBakery;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import net.minecraft.core.SectionPos;
import net.minecraft.server.level.ServerLevel;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Nullable;
import org.joml.Vector3dc;

import java.util.Objects;
import java.util.function.Supplier;

/**
 * Rapier pipeline entry point for operational reconstruction primitives.
 *
 * <p>Runtime-ID reservations and owner-aware section reservations are exposed independently. This
 * class intentionally does not yet opt into complete reconstruction physics capabilities: the
 * provisional native body lifecycle still has to be implemented and proven.</p>
 */
@ApiStatus.Internal
final class TransactionalRapierPhysicsPipeline extends RapierPhysicsPipeline
        implements SubLevelReconstructionRuntimeIdSupport, SubLevelReconstructionSectionSupport {
    private static final RapierRuntimeIdAllocator RUNTIME_IDS = new RapierRuntimeIdAllocator();

    private final ServerLevel reconstructionLevel;
    private final RapierVoxelColliderBakery reconstructionColliderBakery;
    private boolean reconstructionSceneInitialized;

    TransactionalRapierPhysicsPipeline(final ServerLevel level) {
        super(level);
        this.reconstructionLevel = Objects.requireNonNull(level, "level");
        this.reconstructionColliderBakery = new RapierVoxelColliderBakery(level);
    }

    @Override
    public void init(@Nullable final Vector3dc gravity, final double universalDrag) {
        super.init(gravity, universalDrag);
        this.reconstructionSceneInitialized = true;
    }

    @Override
    public void dispose() {
        if (this.reconstructionSceneInitialized
                && !RapierReconstructionNative.clearReconstructionSectionOwnershipForScene(this.getSceneHandle())) {
            throw new IllegalStateException("Rapier refused to dispose a scene with open reconstruction section ownership");
        }
        super.dispose();
        this.reconstructionSceneInitialized = false;
    }

    @Override
    public int getNextRuntimeID() {
        return RUNTIME_IDS.next();
    }

    @Override
    public RuntimeIdReservation reserveReconstructionRuntimeId() {
        return new RuntimeIdReservationAdapter(this, RUNTIME_IDS.reserve());
    }

    @Override
    public <T> T withReservedRuntimeId(
            final RuntimeIdReservation reservation,
            final Supplier<T> allocation
    ) {
        Objects.requireNonNull(reservation, "reservation");
        Objects.requireNonNull(allocation, "allocation");
        if (!(reservation instanceof final RuntimeIdReservationAdapter adapter) || adapter.owner != this) {
            throw new IllegalArgumentException("Runtime ID reservation was not created by this physics pipeline");
        }
        return RUNTIME_IDS.withReservation(adapter.delegate, allocation);
    }

    @Override
    public ReconstructionSectionReservation acquireReconstructionSection(
            final ServerSubLevel owner,
            final SectionPos sectionPos,
            final ReconstructionBlockStateView blockStates
    ) {
        Objects.requireNonNull(owner, "owner");
        Objects.requireNonNull(sectionPos, "sectionPos");
        Objects.requireNonNull(blockStates, "blockStates");
        if (owner.isRemoved()) {
            throw new IllegalStateException("Cannot acquire a reconstruction section for a removed SubLevel");
        }
        if (owner.getLevel() != this.reconstructionLevel) {
            throw new IllegalArgumentException("Reconstruction section owner belongs to another ServerLevel");
        }

        final int ownerRuntimeId = owner.getRuntimeId();
        final int[] encoded = RapierReconstructionSectionEncoder.encode(
                sectionPos,
                blockStates,
                this.reconstructionColliderBakery
        );
        final ReconstructionSectionReservationAdapter reservation =
                new ReconstructionSectionReservationAdapter(this, sectionPos, ownerRuntimeId);
        final boolean acquired = RapierReconstructionNative.acquireReconstructionSubLevelChunk(
                this.getSceneHandle(),
                sectionPos.x(),
                sectionPos.y(),
                sectionPos.z(),
                encoded,
                ownerRuntimeId
        );
        if (!acquired) {
            throw new IllegalStateException("Rapier rejected transactional reconstruction section acquisition");
        }
        return reservation;
    }

    private record RuntimeIdReservationAdapter(
            TransactionalRapierPhysicsPipeline owner,
            RapierRuntimeIdAllocator.Reservation delegate
    ) implements RuntimeIdReservation {
        private RuntimeIdReservationAdapter {
            Objects.requireNonNull(owner, "owner");
            Objects.requireNonNull(delegate, "delegate");
        }

        @Override
        public int runtimeId() {
            return this.delegate.id();
        }

        @Override
        public boolean open() {
            return this.delegate.open();
        }

        @Override
        public void commit() {
            this.delegate.commit();
        }

        @Override
        public void rollback() {
            this.delegate.rollback();
        }
    }

    private static final class ReconstructionSectionReservationAdapter
            implements ReconstructionSectionReservation {
        private final TransactionalRapierPhysicsPipeline owner;
        private final SectionPos sectionPos;
        private final int ownerRuntimeId;
        private boolean open = true;

        private ReconstructionSectionReservationAdapter(
                final TransactionalRapierPhysicsPipeline owner,
                final SectionPos sectionPos,
                final int ownerRuntimeId
        ) {
            this.owner = Objects.requireNonNull(owner, "owner");
            this.sectionPos = Objects.requireNonNull(sectionPos, "sectionPos");
            this.ownerRuntimeId = ownerRuntimeId;
        }

        @Override
        public SectionPos sectionPos() {
            return this.sectionPos;
        }

        @Override
        public int ownerRuntimeId() {
            return this.ownerRuntimeId;
        }

        @Override
        public boolean open() {
            return this.open;
        }

        @Override
        public void verify() {
            this.requireOpen();
            if (!RapierReconstructionNative.verifyReconstructionSubLevelChunk(
                    this.owner.getSceneHandle(),
                    this.sectionPos.x(),
                    this.sectionPos.y(),
                    this.sectionPos.z(),
                    this.ownerRuntimeId
            )) {
                throw new IllegalStateException("Rapier reconstruction section verification failed");
            }
        }

        @Override
        public void commit() {
            this.requireOpen();
            if (!RapierReconstructionNative.commitReconstructionSubLevelChunk(
                    this.owner.getSceneHandle(),
                    this.sectionPos.x(),
                    this.sectionPos.y(),
                    this.sectionPos.z(),
                    this.ownerRuntimeId
            )) {
                throw new IllegalStateException("Rapier reconstruction section commit failed");
            }
            this.open = false;
        }

        @Override
        public void rollback() {
            this.requireOpen();
            if (!RapierReconstructionNative.rollbackReconstructionSubLevelChunk(
                    this.owner.getSceneHandle(),
                    this.sectionPos.x(),
                    this.sectionPos.y(),
                    this.sectionPos.z(),
                    this.ownerRuntimeId
            )) {
                throw new IllegalStateException("Rapier reconstruction section rollback failed");
            }
            this.open = false;
        }

        private void requireOpen() {
            if (!this.open) {
                throw new IllegalStateException("Reconstruction section reservation is already closed");
            }
        }
    }
}
