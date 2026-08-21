package dev.ryanhcode.sable.sublevel.plot;

import dev.ryanhcode.sable.sublevel.storage.serialization.SubLevelData;
import dev.ryanhcode.sable.sublevel.storage.serialization.SubLevelReconstructionMassSnapshot;
import net.minecraft.server.level.ServerLevel;
import org.jetbrains.annotations.ApiStatus;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;

/**
 * Prepared, mutation-free entry point for transactional SubLevel reconstruction.
 *
 * <p>Preparation always runs the mutation-free serialized-data preflight first, freezes accepted
 * input into an immutable plan, validates deterministic payload codecs/metadata and authoritative
 * self-mass, verifies target registry availability, freezes a canonical staged payload, decodes
 * deterministic chunk state without live chunk allocation, verifies target ChunkMap publication
 * coordinates and entity sections are clean, requires platform callbacks to be staging/defer safe,
 * verifies current target physics capabilities, and then captures a fresh target-container rollback
 * baseline. A transaction token is created only after every gate succeeds.</p>
 *
 * <p>The first materialization stage remains fully detached: it may reserve provider runtime/body/
 * section state through {@link #materializeDetachedPhysics()}, but still cannot allocate a live
 * target chunk or publish the target through the SubLevel container.</p>
 */
@ApiStatus.Experimental
public final class SubLevelReconstructionAttempt {
    public sealed interface Preparation permits Prepared, PreflightRejected, PayloadRejected, MassRejected, RegistryRejected, StagingRejected, DecodeRejected, PublicationRejected, EntityRejected, PlatformRejected, RuntimeRejected, BaselineRejected {
        boolean accepted();
    }

    public record Prepared(SubLevelReconstructionAttempt attempt) implements Preparation {
        public Prepared {
            Objects.requireNonNull(attempt, "attempt");
        }

        @Override
        public boolean accepted() {
            return true;
        }
    }

    public record PreflightRejected(Set<SubLevelReconstructionPreflight.Failure> failures)
            implements Preparation {
        public PreflightRejected {
            Objects.requireNonNull(failures, "failures");
            failures = immutablePreflightFailures(failures);
            if (failures.isEmpty()) {
                throw new IllegalArgumentException("Preflight rejection requires failure evidence");
            }
        }

        @Override
        public boolean accepted() {
            return false;
        }
    }

    public record PayloadRejected(Set<SubLevelReconstructionPayloadPreflight.Failure> failures)
            implements Preparation {
        public PayloadRejected {
            Objects.requireNonNull(failures, "failures");
            failures = immutablePayloadFailures(failures);
            if (failures.isEmpty()) {
                throw new IllegalArgumentException("Payload rejection requires failure evidence");
            }
        }

        @Override
        public boolean accepted() {
            return false;
        }
    }

    public record MassRejected(Set<SubLevelReconstructionMassSnapshot.Failure> failures)
            implements Preparation {
        public MassRejected {
            Objects.requireNonNull(failures, "failures");
            failures = immutableMassFailures(failures);
            if (failures.isEmpty()) {
                throw new IllegalArgumentException("Mass rejection requires failure evidence");
            }
        }

        @Override
        public boolean accepted() {
            return false;
        }
    }

    public record RegistryRejected(Set<SubLevelReconstructionRegistryPreflight.Failure> failures)
            implements Preparation {
        public RegistryRejected {
            Objects.requireNonNull(failures, "failures");
            failures = immutableRegistryFailures(failures);
            if (failures.isEmpty()) {
                throw new IllegalArgumentException("Registry rejection requires failure evidence");
            }
        }

        @Override
        public boolean accepted() {
            return false;
        }
    }

    public record StagingRejected(Set<SubLevelReconstructionStagedPayload.Failure> failures)
            implements Preparation {
        public StagingRejected {
            Objects.requireNonNull(failures, "failures");
            failures = immutableStagingFailures(failures);
            if (failures.isEmpty()) {
                throw new IllegalArgumentException("Staging rejection requires failure evidence");
            }
        }

        @Override
        public boolean accepted() {
            return false;
        }
    }

    public record DecodeRejected(
            Set<SubLevelReconstructionDecodedPayload.Failure> failures,
            Set<Long> failedChunkKeys
    ) implements Preparation {
        public DecodeRejected {
            Objects.requireNonNull(failures, "failures");
            Objects.requireNonNull(failedChunkKeys, "failedChunkKeys");
            failures = immutableDecodeFailures(failures);
            failedChunkKeys = Set.copyOf(failedChunkKeys);
            if (failures.isEmpty()) {
                throw new IllegalArgumentException("Decode rejection requires failure evidence");
            }
        }

        @Override
        public boolean accepted() {
            return false;
        }
    }

    public record PublicationRejected(
            Set<SubLevelReconstructionPublicationPreflight.Failure> failures,
            Set<Long> blockedChunkKeys
    ) implements Preparation {
        public PublicationRejected {
            Objects.requireNonNull(failures, "failures");
            Objects.requireNonNull(blockedChunkKeys, "blockedChunkKeys");
            failures = immutablePublicationFailures(failures);
            blockedChunkKeys = Set.copyOf(blockedChunkKeys);
            if (failures.isEmpty()) {
                throw new IllegalArgumentException("Publication rejection requires failure evidence");
            }
        }

        @Override
        public boolean accepted() {
            return false;
        }
    }

    public record EntityRejected(
            Set<SubLevelReconstructionEntityPreflight.Failure> failures,
            Set<Long> blockedChunkKeys
    ) implements Preparation {
        public EntityRejected {
            Objects.requireNonNull(failures, "failures");
            Objects.requireNonNull(blockedChunkKeys, "blockedChunkKeys");
            failures = immutableEntityFailures(failures);
            blockedChunkKeys = Set.copyOf(blockedChunkKeys);
            if (failures.isEmpty()) {
                throw new IllegalArgumentException("Entity rejection requires failure evidence");
            }
        }

        @Override
        public boolean accepted() {
            return false;
        }
    }

    public record PlatformRejected(Set<SubLevelReconstructionPlatformPreflight.Failure> failures)
            implements Preparation {
        public PlatformRejected {
            Objects.requireNonNull(failures, "failures");
            failures = immutablePlatformFailures(failures);
            if (failures.isEmpty()) {
                throw new IllegalArgumentException("Platform rejection requires failure evidence");
            }
        }

        @Override
        public boolean accepted() {
            return false;
        }
    }

    public record RuntimeRejected(Set<SubLevelReconstructionRuntimePreflight.Failure> failures)
            implements Preparation {
        public RuntimeRejected {
            Objects.requireNonNull(failures, "failures");
            failures = immutableRuntimeFailures(failures);
            if (failures.isEmpty()) {
                throw new IllegalArgumentException("Runtime rejection requires failure evidence");
            }
        }

        @Override
        public boolean accepted() {
            return false;
        }
    }

    public record BaselineRejected(Set<SubLevelReconstructionContainerBaseline.Failure> failures)
            implements Preparation {
        public BaselineRejected {
            Objects.requireNonNull(failures, "failures");
            failures = immutableBaselineFailures(failures);
            if (failures.isEmpty()) {
                throw new IllegalArgumentException("Baseline rejection requires failure evidence");
            }
        }

        @Override
        public boolean accepted() {
            return false;
        }
    }

    private final ServerLevel targetLevel;
    private final SubLevelReconstructionPlan plan;
    private final SubLevelReconstructionMassSnapshot massSnapshot;
    private final SubLevelReconstructionStagedPayload stagedPayload;
    private final SubLevelReconstructionDecodedPayload decodedPayload;
    private final SubLevelReconstructionContainerBaseline baseline;
    private final SubLevelReconstructionTransaction transaction;

    private SubLevelReconstructionAttempt(
            final ServerLevel targetLevel,
            final SubLevelReconstructionPlan plan,
            final SubLevelReconstructionMassSnapshot massSnapshot,
            final SubLevelReconstructionStagedPayload stagedPayload,
            final SubLevelReconstructionDecodedPayload decodedPayload,
            final SubLevelReconstructionContainerBaseline baseline
    ) {
        this.targetLevel = Objects.requireNonNull(targetLevel, "targetLevel");
        this.plan = Objects.requireNonNull(plan, "plan");
        this.massSnapshot = Objects.requireNonNull(massSnapshot, "massSnapshot");
        this.stagedPayload = Objects.requireNonNull(stagedPayload, "stagedPayload");
        this.decodedPayload = Objects.requireNonNull(decodedPayload, "decodedPayload");
        this.baseline = Objects.requireNonNull(baseline, "baseline");
        this.transaction = new SubLevelReconstructionTransaction(plan);
    }

    /**
     * Builds a prepared attempt without changing target-world state.
     */
    public static Preparation prepare(final ServerLevel targetLevel, final SubLevelData data) {
        Objects.requireNonNull(targetLevel, "targetLevel");
        Objects.requireNonNull(data, "data");

        final SubLevelReconstructionPlan.Preparation planPreparation =
                SubLevelReconstructionPlan.prepare(targetLevel, data);
        if (!planPreparation.accepted()) {
            return new PreflightRejected(planPreparation.failures());
        }

        final SubLevelReconstructionPlan plan = planPreparation.plan().orElseThrow();
        final SubLevelReconstructionPayloadPreflight.Result payload =
                SubLevelReconstructionPayloadPreflight.validate(plan);
        if (!payload.accepted()) {
            return new PayloadRejected(payload.failures());
        }

        final SubLevelReconstructionMassPreflight.Result mass =
                SubLevelReconstructionMassPreflight.validate(plan);
        if (!mass.accepted()) {
            return new MassRejected(mass.failures());
        }
        final SubLevelReconstructionMassSnapshot massSnapshot = mass.snapshot().orElseThrow();

        final SubLevelReconstructionRegistryPreflight.Result registry =
                SubLevelReconstructionRegistryPreflight.validate(targetLevel, plan);
        if (!registry.accepted()) {
            return new RegistryRejected(registry.failures());
        }

        final SubLevelReconstructionStagedPayload.Capture stagedCapture =
                SubLevelReconstructionStagedPayload.capture(targetLevel, plan);
        if (!stagedCapture.accepted()) {
            return new StagingRejected(stagedCapture.failures());
        }
        final SubLevelReconstructionStagedPayload stagedPayload = stagedCapture.payload().orElseThrow();

        final SubLevelReconstructionDecodedPayload.Capture decodedCapture =
                SubLevelReconstructionDecodedPayload.decode(targetLevel, stagedPayload);
        if (!decodedCapture.accepted()) {
            return new DecodeRejected(decodedCapture.failures(), decodedCapture.failedChunkKeys());
        }
        final SubLevelReconstructionDecodedPayload decodedPayload = decodedCapture.payload().orElseThrow();

        final SubLevelReconstructionPublicationPreflight.Result publication =
                SubLevelReconstructionPublicationPreflight.validate(targetLevel, plan);
        if (!publication.accepted()) {
            return new PublicationRejected(publication.failures(), publication.blockedChunkKeys());
        }

        final SubLevelReconstructionEntityPreflight.Result entities =
                SubLevelReconstructionEntityPreflight.validate(targetLevel, plan);
        if (!entities.accepted()) {
            return new EntityRejected(entities.failures(), entities.blockedChunkKeys());
        }

        final SubLevelReconstructionPlatformPreflight.Result platform =
                SubLevelReconstructionPlatformPreflight.validate(decodedPayload);
        if (!platform.accepted()) {
            return new PlatformRejected(platform.failures());
        }

        final SubLevelReconstructionRuntimePreflight.Result runtime =
                SubLevelReconstructionRuntimePreflight.validate(targetLevel);
        if (!runtime.accepted()) {
            return new RuntimeRejected(runtime.failures());
        }

        final SubLevelReconstructionContainerBaseline.Capture baselineCapture =
                SubLevelReconstructionContainerBaseline.capture(targetLevel, plan);
        if (!baselineCapture.accepted()) {
            return new BaselineRejected(baselineCapture.failures());
        }

        return new Prepared(new SubLevelReconstructionAttempt(
                targetLevel,
                plan,
                massSnapshot,
                stagedPayload,
                decodedPayload,
                baselineCapture.baseline().orElseThrow()
        ));
    }

    public SubLevelReconstructionPlan plan() {
        return this.plan;
    }

    public SubLevelReconstructionTransaction.State state() {
        return this.transaction.state();
    }

    /**
     * Acquires the first transactional materialization stage while keeping all Java world/container
     * publication detached and rollbackable.
     */
    public SubLevelReconstructionDetachedPhysics materializeDetachedPhysics() {
        return SubLevelReconstructionDetachedPhysics.acquire(this);
    }

    @ApiStatus.Internal
    ServerLevel targetLevel() {
        return this.targetLevel;
    }

    @ApiStatus.Internal
    SubLevelReconstructionMassSnapshot massSnapshot() {
        return this.massSnapshot;
    }

    @ApiStatus.Internal
    SubLevelReconstructionStagedPayload stagedPayload() {
        return this.stagedPayload;
    }

    @ApiStatus.Internal
    SubLevelReconstructionDecodedPayload decodedPayload() {
        return this.decodedPayload;
    }

    @ApiStatus.Internal
    SubLevelReconstructionContainerBaseline baseline() {
        return this.baseline;
    }

    @ApiStatus.Internal
    SubLevelReconstructionTransaction transaction() {
        return this.transaction;
    }

    private static Set<SubLevelReconstructionPreflight.Failure> immutablePreflightFailures(
            final Set<SubLevelReconstructionPreflight.Failure> failures
    ) {
        final EnumSet<SubLevelReconstructionPreflight.Failure> copy =
                EnumSet.noneOf(SubLevelReconstructionPreflight.Failure.class);
        copy.addAll(failures);
        return Collections.unmodifiableSet(copy);
    }

    private static Set<SubLevelReconstructionPayloadPreflight.Failure> immutablePayloadFailures(
            final Set<SubLevelReconstructionPayloadPreflight.Failure> failures
    ) {
        final EnumSet<SubLevelReconstructionPayloadPreflight.Failure> copy =
                EnumSet.noneOf(SubLevelReconstructionPayloadPreflight.Failure.class);
        copy.addAll(failures);
        return Collections.unmodifiableSet(copy);
    }

    private static Set<SubLevelReconstructionMassSnapshot.Failure> immutableMassFailures(
            final Set<SubLevelReconstructionMassSnapshot.Failure> failures
    ) {
        final EnumSet<SubLevelReconstructionMassSnapshot.Failure> copy =
                EnumSet.noneOf(SubLevelReconstructionMassSnapshot.Failure.class);
        copy.addAll(failures);
        return Collections.unmodifiableSet(copy);
    }

    private static Set<SubLevelReconstructionRegistryPreflight.Failure> immutableRegistryFailures(
            final Set<SubLevelReconstructionRegistryPreflight.Failure> failures
    ) {
        final EnumSet<SubLevelReconstructionRegistryPreflight.Failure> copy =
                EnumSet.noneOf(SubLevelReconstructionRegistryPreflight.Failure.class);
        copy.addAll(failures);
        return Collections.unmodifiableSet(copy);
    }

    private static Set<SubLevelReconstructionStagedPayload.Failure> immutableStagingFailures(
            final Set<SubLevelReconstructionStagedPayload.Failure> failures
    ) {
        final EnumSet<SubLevelReconstructionStagedPayload.Failure> copy =
                EnumSet.noneOf(SubLevelReconstructionStagedPayload.Failure.class);
        copy.addAll(failures);
        return Collections.unmodifiableSet(copy);
    }

    private static Set<SubLevelReconstructionDecodedPayload.Failure> immutableDecodeFailures(
            final Set<SubLevelReconstructionDecodedPayload.Failure> failures
    ) {
        final EnumSet<SubLevelReconstructionDecodedPayload.Failure> copy =
                EnumSet.noneOf(SubLevelReconstructionDecodedPayload.Failure.class);
        copy.addAll(failures);
        return Collections.unmodifiableSet(copy);
    }

    private static Set<SubLevelReconstructionPublicationPreflight.Failure> immutablePublicationFailures(
            final Set<SubLevelReconstructionPublicationPreflight.Failure> failures
    ) {
        final EnumSet<SubLevelReconstructionPublicationPreflight.Failure> copy =
                EnumSet.noneOf(SubLevelReconstructionPublicationPreflight.Failure.class);
        copy.addAll(failures);
        return Collections.unmodifiableSet(copy);
    }

    private static Set<SubLevelReconstructionEntityPreflight.Failure> immutableEntityFailures(
            final Set<SubLevelReconstructionEntityPreflight.Failure> failures
    ) {
        final EnumSet<SubLevelReconstructionEntityPreflight.Failure> copy =
                EnumSet.noneOf(SubLevelReconstructionEntityPreflight.Failure.class);
        copy.addAll(failures);
        return Collections.unmodifiableSet(copy);
    }

    private static Set<SubLevelReconstructionPlatformPreflight.Failure> immutablePlatformFailures(
            final Set<SubLevelReconstructionPlatformPreflight.Failure> failures
    ) {
        final EnumSet<SubLevelReconstructionPlatformPreflight.Failure> copy =
                EnumSet.noneOf(SubLevelReconstructionPlatformPreflight.Failure.class);
        copy.addAll(failures);
        return Collections.unmodifiableSet(copy);
    }

    private static Set<SubLevelReconstructionRuntimePreflight.Failure> immutableRuntimeFailures(
            final Set<SubLevelReconstructionRuntimePreflight.Failure> failures
    ) {
        final EnumSet<SubLevelReconstructionRuntimePreflight.Failure> copy =
                EnumSet.noneOf(SubLevelReconstructionRuntimePreflight.Failure.class);
        copy.addAll(failures);
        return Collections.unmodifiableSet(copy);
    }

    private static Set<SubLevelReconstructionContainerBaseline.Failure> immutableBaselineFailures(
            final Set<SubLevelReconstructionContainerBaseline.Failure> failures
    ) {
        final EnumSet<SubLevelReconstructionContainerBaseline.Failure> copy =
                EnumSet.noneOf(SubLevelReconstructionContainerBaseline.Failure.class);
        copy.addAll(failures);
        return Collections.unmodifiableSet(copy);
    }
}
