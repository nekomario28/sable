package dev.ryanhcode.sable.sublevel.plot;

import dev.ryanhcode.sable.sublevel.storage.serialization.SubLevelData;
import net.minecraft.server.level.ServerLevel;
import org.jetbrains.annotations.ApiStatus;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;

/**
 * Prepared, mutation-free entry point for a future transactional SubLevel reconstruction.
 *
 * <p>Preparation always runs the mutation-free serialized-data preflight first, freezes accepted
 * input into an immutable plan, validates deterministic payload codecs/metadata, verifies current
 * target runtime/physics capabilities, and then captures a fresh target-container rollback
 * baseline. A transaction token is created only after every gate succeeds.</p>
 *
 * <p>This class still has no materialization implementation. In particular it never calls legacy
 * {@code SubLevelSerializer.fullyLoad} and cannot fall back to it.</p>
 */
@ApiStatus.Experimental
public final class SubLevelReconstructionAttempt {
    public sealed interface Preparation permits Prepared, PreflightRejected, PayloadRejected, RuntimeRejected, BaselineRejected {
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
    private final SubLevelReconstructionContainerBaseline baseline;
    private final SubLevelReconstructionTransaction transaction;

    private SubLevelReconstructionAttempt(
            final ServerLevel targetLevel,
            final SubLevelReconstructionPlan plan,
            final SubLevelReconstructionContainerBaseline baseline
    ) {
        this.targetLevel = Objects.requireNonNull(targetLevel, "targetLevel");
        this.plan = Objects.requireNonNull(plan, "plan");
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
                baselineCapture.baseline().orElseThrow()
        ));
    }

    public SubLevelReconstructionPlan plan() {
        return this.plan;
    }

    public SubLevelReconstructionTransaction.State state() {
        return this.transaction.state();
    }

    @ApiStatus.Internal
    ServerLevel targetLevel() {
        return this.targetLevel;
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
