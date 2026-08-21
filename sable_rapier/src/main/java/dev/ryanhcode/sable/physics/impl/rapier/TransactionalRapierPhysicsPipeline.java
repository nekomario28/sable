package dev.ryanhcode.sable.physics.impl.rapier;

import dev.ryanhcode.sable.api.physics.PhysicsPipelineBody;
import dev.ryanhcode.sable.api.physics.SubLevelReconstructionBodySupport;
import dev.ryanhcode.sable.api.physics.SubLevelReconstructionPhysicsSupport;
import dev.ryanhcode.sable.api.physics.SubLevelReconstructionRuntimeIdSupport;
import dev.ryanhcode.sable.api.physics.SubLevelReconstructionSectionSupport;
import dev.ryanhcode.sable.api.physics.constraint.PhysicsConstraintConfiguration;
import dev.ryanhcode.sable.api.physics.constraint.PhysicsConstraintHandle;
import dev.ryanhcode.sable.api.physics.mass.MassData;
import dev.ryanhcode.sable.companion.math.BoundingBox3ic;
import dev.ryanhcode.sable.companion.math.Pose3d;
import dev.ryanhcode.sable.companion.math.Pose3dc;
import dev.ryanhcode.sable.physics.impl.rapier.collider.RapierVoxelColliderBakery;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import net.minecraft.core.SectionPos;
import net.minecraft.server.level.ServerLevel;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix3dc;
import org.joml.Quaterniond;
import org.joml.Quaterniondc;
import org.joml.Vector3dc;

import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import java.util.function.Supplier;

/**
 * Rapier pipeline entry point for operational reconstruction primitives.
 *
 * <p>Runtime-ID reservations, owner-aware exact section rollback and provisional body lifecycle
 * are implemented and verified. Body commit publishes the already-created provisional native body
 * through the ordinary Java live-body registry without recreating it.</p>
 */
@ApiStatus.Internal
final class TransactionalRapierPhysicsPipeline extends RapierPhysicsPipeline
        implements SubLevelReconstructionPhysicsSupport,
        SubLevelReconstructionRuntimeIdSupport,
        SubLevelReconstructionSectionSupport,
        SubLevelReconstructionBodySupport {
    private static final RapierRuntimeIdAllocator RUNTIME_IDS = new RapierRuntimeIdAllocator();
    private static final Capabilities RECONSTRUCTION_CAPABILITIES = new Capabilities(true, true);

    private final ServerLevel reconstructionLevel;
    private final RapierVoxelColliderBakery reconstructionColliderBakery;
    private final Set<Integer> reconstructionBodyRuntimeIds = new HashSet<>();
    private boolean reconstructionSceneInitialized;

    TransactionalRapierPhysicsPipeline(final ServerLevel level) {
        super(level);
        this.reconstructionLevel = Objects.requireNonNull(level, "level");
        this.reconstructionColliderBakery = new RapierVoxelColliderBakery(level);
    }

    @Override
    public Capabilities reconstructionCapabilities() {
        return RECONSTRUCTION_CAPABILITIES;
    }

    @Override
    public void init(@Nullable final Vector3dc gravity, final double universalDrag) {
        super.init(gravity, universalDrag);
        this.reconstructionSceneInitialized = true;
    }

    @Override
    public void dispose() {
        if (!this.reconstructionBodyRuntimeIds.isEmpty()) {
            throw new IllegalStateException("Rapier refused to dispose a scene with open Java reconstruction body ownership");
        }
        if (this.reconstructionSceneInitialized
                && !RapierReconstructionNative.clearReconstructionBodyOwnershipForScene(this.getSceneHandle())) {
            throw new IllegalStateException("Rapier refused to dispose a scene with open reconstruction body ownership");
        }
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
    public void add(final ServerSubLevel subLevel, final Pose3dc pose) {
        Objects.requireNonNull(subLevel, "subLevel");
        this.rejectProvisionalBodyMutation(subLevel);
        super.add(subLevel, pose);
    }

    @Override
    public void remove(final ServerSubLevel subLevel) {
        Objects.requireNonNull(subLevel, "subLevel");
        this.rejectProvisionalBodyMutation(subLevel);
        super.remove(subLevel);
    }

    @Override
    public void onStatsChanged(@NotNull final ServerSubLevel subLevel) {
        this.rejectProvisionalBodyMutation(subLevel);
        super.onStatsChanged(subLevel);
    }

    @Override
    public void teleport(
            final PhysicsPipelineBody body,
            final Vector3dc position,
            final Quaterniondc orientation
    ) {
        this.rejectProvisionalBodyMutation(body);
        super.teleport(body, position, orientation);
    }

    @Override
    public void applyImpulse(
            final PhysicsPipelineBody body,
            final Vector3dc position,
            final Vector3dc force
    ) {
        this.rejectProvisionalBodyMutation(body);
        super.applyImpulse(body, position, force);
    }

    @Override
    public void applyLinearAndAngularImpulse(
            final PhysicsPipelineBody body,
            final Vector3dc force,
            final Vector3dc torque,
            final boolean wakeUp
    ) {
        this.rejectProvisionalBodyMutation(body);
        super.applyLinearAndAngularImpulse(body, force, torque, wakeUp);
    }

    @Override
    public void addLinearAndAngularVelocity(
            final PhysicsPipelineBody body,
            final Vector3dc linearVelocity,
            final Vector3dc angularVelocity
    ) {
        this.rejectProvisionalBodyMutation(body);
        super.addLinearAndAngularVelocity(body, linearVelocity, angularVelocity);
    }

    @Override
    public void wakeUp(final PhysicsPipelineBody body) {
        this.rejectProvisionalBodyMutation(body);
        super.wakeUp(body);
    }

    @Override
    @Nullable
    public <T extends PhysicsConstraintHandle> T addConstraint(
            @Nullable final PhysicsPipelineBody bodyA,
            @Nullable final PhysicsPipelineBody bodyB,
            @NotNull final PhysicsConstraintConfiguration<T> configuration
    ) {
        this.rejectProvisionalBodyMutation(bodyA);
        this.rejectProvisionalBodyMutation(bodyB);
        return super.addConstraint(bodyA, bodyB, configuration);
    }

    @Override
    public ReconstructionBodyReservation acquireReconstructionBody(final ServerSubLevel target) {
        this.requireReconstructionServerThread();
        Objects.requireNonNull(target, "target");
        if (target.isRemoved()) {
            throw new IllegalStateException("Cannot acquire a reconstruction body for a removed SubLevel");
        }
        if (target.getLevel() != this.reconstructionLevel) {
            throw new IllegalArgumentException("Reconstruction body target belongs to another ServerLevel");
        }

        final MassData massData = target.getMassTracker();
        if (massData == null || massData.isInvalid()) {
            throw new IllegalStateException("Reconstruction body target has no valid authoritative mass");
        }
        final Vector3dc centerOfMass = massData.getCenterOfMass();
        if (centerOfMass == null) {
            throw new IllegalStateException("Reconstruction body target has no authoritative center of mass");
        }
        final Matrix3dc inertia = Objects.requireNonNull(
                massData.getInertiaTensor(),
                "Reconstruction body target has no authoritative inertia tensor"
        );
        final Pose3dc pose = target.logicalPose();
        final Vector3dc position = pose.position();
        final Quaterniond normalizedOrientation = normalizeOrientation(pose.orientation());
        final Pose3d committedPose = new Pose3d(pose);
        committedPose.orientation().set(normalizedOrientation);
        committedPose.rotationPoint().set(centerOfMass);
        final BoundingBox3ic bounds = target.getPlot().getBoundingBox();

        final int[] blockBounds = {
                bounds.minX(), bounds.minY(), bounds.minZ(),
                bounds.maxX(), bounds.maxY(), bounds.maxZ()
        };
        final int[] plotSections = {
                target.getPlot().getChunkMin().x,
                this.reconstructionLevel.getMinSection(),
                target.getPlot().getChunkMin().z,
                target.getPlot().getChunkMax().x,
                this.reconstructionLevel.getMaxSection() - 1,
                target.getPlot().getChunkMax().z
        };
        final double[] poseArray = {
                position.x(), position.y(), position.z(),
                normalizedOrientation.x(), normalizedOrientation.y(),
                normalizedOrientation.z(), normalizedOrientation.w()
        };
        final double[] centerOfMassArray = {
                centerOfMass.x(), centerOfMass.y(), centerOfMass.z()
        };
        final double[] inertiaArray = {
                inertia.m00(), inertia.m01(), inertia.m02(),
                inertia.m10(), inertia.m11(), inertia.m12(),
                inertia.m20(), inertia.m21(), inertia.m22()
        };

        final int runtimeId = target.getRuntimeId();
        final Integer runtimeIdKey = Integer.valueOf(runtimeId);
        if (this.hasActiveSubLevel(runtimeId)) {
            throw new IllegalStateException("Java live-body registry already contains runtime ID " + runtimeId);
        }
        final ReconstructionBodyReservationAdapter reservation =
                new ReconstructionBodyReservationAdapter(this, target, runtimeId, runtimeIdKey, committedPose);
        if (!this.reconstructionBodyRuntimeIds.add(runtimeIdKey)) {
            throw new IllegalStateException("Java reconstruction body ownership already exists for runtime ID " + runtimeId);
        }
        boolean acquired = false;
        try {
            acquired = RapierReconstructionNative.acquireReconstructionSubLevelBody(
                    this.getSceneHandle(),
                    runtimeId,
                    poseArray,
                    massData.getMass(),
                    centerOfMassArray,
                    inertiaArray,
                    blockBounds,
                    plotSections
            );
            if (!acquired) {
                throw new IllegalStateException("Rapier rejected transactional reconstruction body acquisition");
            }
            try {
                reservation.captureCommittedNativePose(centerOfMass);
            } catch (final RuntimeException | Error captureFailure) {
                try {
                    reservation.rollback();
                } catch (final RuntimeException | Error rollbackFailure) {
                    captureFailure.addSuppressed(rollbackFailure);
                }
                throw captureFailure;
            }
            return reservation;
        } finally {
            if (!acquired) {
                this.reconstructionBodyRuntimeIds.remove(runtimeIdKey);
            }
        }
    }

    @Override
    public ReconstructionSectionReservation acquireReconstructionSection(
            final ServerSubLevel owner,
            final SectionPos sectionPos,
            final ReconstructionBlockStateView blockStates
    ) {
        this.requireReconstructionServerThread();
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

    private void requireReconstructionServerThread() {
        if (!this.reconstructionLevel.getServer().isSameThread()) {
            throw new IllegalStateException("Rapier reconstruction mutation must run on the owning server thread");
        }
    }

    private void rejectProvisionalBodyMutation(@Nullable final PhysicsPipelineBody body) {
        if (body instanceof final ServerSubLevel subLevel
                && this.reconstructionBodyRuntimeIds.contains(subLevel.getRuntimeId())) {
            throw new IllegalStateException(
                    "Cannot use the live-body mutation API while a reconstruction body is provisional"
            );
        }
    }

    private static Quaterniond normalizeOrientation(final Quaterniondc orientation) {
        Objects.requireNonNull(orientation, "orientation");
        final double x = orientation.x();
        final double y = orientation.y();
        final double z = orientation.z();
        final double w = orientation.w();
        final double scale = Math.max(
                Math.max(Math.abs(x), Math.abs(y)),
                Math.max(Math.abs(z), Math.abs(w))
        );
        if (!Double.isFinite(scale) || scale == 0.0) {
            throw new IllegalStateException("Reconstruction body target has an invalid orientation");
        }
        final Quaterniond normalized = new Quaterniond(x / scale, y / scale, z / scale, w / scale).normalize();
        if (!Double.isFinite(normalized.x())
                || !Double.isFinite(normalized.y())
                || !Double.isFinite(normalized.z())
                || !Double.isFinite(normalized.w())) {
            throw new IllegalStateException("Reconstruction body target orientation could not be normalized");
        }
        return normalized;
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

    private static final class ReconstructionBodyReservationAdapter
            implements ReconstructionBodyReservation {
        private final TransactionalRapierPhysicsPipeline owner;
        private final ServerSubLevel target;
        private final int ownerRuntimeId;
        private final Integer ownerRuntimeIdKey;
        private final Pose3d committedPose;
        private boolean open = true;

        private ReconstructionBodyReservationAdapter(
                final TransactionalRapierPhysicsPipeline owner,
                final ServerSubLevel target,
                final int ownerRuntimeId,
                final Integer ownerRuntimeIdKey,
                final Pose3dc committedPose
        ) {
            this.owner = Objects.requireNonNull(owner, "owner");
            this.target = Objects.requireNonNull(target, "target");
            this.ownerRuntimeId = ownerRuntimeId;
            this.ownerRuntimeIdKey = Objects.requireNonNull(ownerRuntimeIdKey, "ownerRuntimeIdKey");
            this.committedPose = new Pose3d(Objects.requireNonNull(committedPose, "committedPose"));
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
            this.owner.requireReconstructionServerThread();
            this.requireOpen();
            if (!this.owner.reconstructionBodyRuntimeIds.contains(this.ownerRuntimeIdKey)) {
                throw new IllegalStateException("Java reconstruction body ownership was lost while reservation remained open");
            }
            if (this.owner.hasActiveSubLevel(this.ownerRuntimeId)) {
                throw new IllegalStateException("Provisional reconstruction body was published through the live-body registry");
            }
            if (!RapierReconstructionNative.verifyReconstructionSubLevelBody(
                    this.owner.getSceneHandle(),
                    this.ownerRuntimeId
            )) {
                throw new IllegalStateException("Rapier reconstruction body verification failed");
            }
        }

        private void captureCommittedNativePose(final Vector3dc centerOfMass) {
            this.owner.requireReconstructionServerThread();
            this.requireOpen();
            this.owner.readPose(this.target, this.committedPose);
            this.committedPose.rotationPoint().set(centerOfMass);
        }

        @Override
        public void commit() {
            this.owner.requireReconstructionServerThread();
            this.requireOpen();
            this.verify();

            final boolean published;
            try {
                published = this.owner.publishExistingSubLevel(this.target);
            } catch (final RuntimeException | Error publicationFailure) {
                this.owner.unpublishExistingSubLevel(this.target);
                throw publicationFailure;
            }
            if (!published) {
                throw new IllegalStateException("Java live-body registry rejected reconstruction body publication");
            }

            final boolean nativeCommitted;
            try {
                nativeCommitted = RapierReconstructionNative.commitReconstructionSubLevelBody(
                        this.owner.getSceneHandle(),
                        this.ownerRuntimeId
                );
            } catch (final RuntimeException | Error nativeFailure) {
                if (!this.owner.unpublishExistingSubLevel(this.target)) {
                    nativeFailure.addSuppressed(new IllegalStateException(
                            "Failed to restore Java live-body registry after native reconstruction commit threw"
                    ));
                }
                throw nativeFailure;
            }
            if (!nativeCommitted) {
                if (!this.owner.unpublishExistingSubLevel(this.target)) {
                    throw new IllegalStateException(
                            "Rapier rejected reconstruction body commit and Java live-body registry rollback failed"
                    );
                }
                throw new IllegalStateException("Rapier reconstruction body commit failed");
            }

            this.target.logicalPose().set(this.committedPose);
            this.target.updateLastPose();
            this.owner.reconstructionBodyRuntimeIds.remove(this.ownerRuntimeIdKey);
            this.open = false;
        }

        @Override
        public void rollback() {
            this.owner.requireReconstructionServerThread();
            this.requireOpen();
            if (!this.owner.reconstructionBodyRuntimeIds.contains(this.ownerRuntimeIdKey)) {
                throw new IllegalStateException("Java reconstruction body ownership was lost before rollback");
            }
            if (this.owner.hasActiveSubLevel(this.ownerRuntimeId)) {
                throw new IllegalStateException("Cannot roll back a reconstruction body published through the live-body registry");
            }
            if (!RapierReconstructionNative.rollbackReconstructionSubLevelBody(
                    this.owner.getSceneHandle(),
                    this.ownerRuntimeId
            )) {
                throw new IllegalStateException("Rapier reconstruction body rollback failed");
            }
            this.owner.reconstructionBodyRuntimeIds.remove(this.ownerRuntimeIdKey);
            this.open = false;
        }

        private void requireOpen() {
            if (!this.open) {
                throw new IllegalStateException("Reconstruction body reservation is already closed");
            }
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
            this.owner.requireReconstructionServerThread();
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
            this.owner.requireReconstructionServerThread();
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
            this.owner.requireReconstructionServerThread();
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
