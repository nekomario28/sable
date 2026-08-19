package dev.ryanhcode.sable.sublevel.plot;

import dev.ryanhcode.sable.sublevel.storage.serialization.SubLevelReconstructionMassSnapshot;
import org.jetbrains.annotations.ApiStatus;

import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Validates and freezes the authoritative self-mass snapshot before reconstruction can reach any
 * platform or physics capability gate.
 */
@ApiStatus.Experimental
public final class SubLevelReconstructionMassPreflight {
    private static final String SNAPSHOT_KEY = "reconstruction_self_mass";

    private SubLevelReconstructionMassPreflight() {
    }

    public static Result validate(final SubLevelReconstructionPlan plan) {
        Objects.requireNonNull(plan, "plan");
        final SubLevelReconstructionMassSnapshot.Decode decoded =
                SubLevelReconstructionMassSnapshot.decode(plan.fullTag(), SNAPSHOT_KEY);
        if (!decoded.accepted()) {
            return new Result(decoded.failures(), Optional.empty());
        }
        return new Result(Set.of(), decoded.snapshot());
    }

    public record Result(
            Set<SubLevelReconstructionMassSnapshot.Failure> failures,
            Optional<SubLevelReconstructionMassSnapshot> snapshot
    ) {
        public Result {
            Objects.requireNonNull(failures, "failures");
            Objects.requireNonNull(snapshot, "snapshot");
            failures = Set.copyOf(failures);
            if (failures.isEmpty() != snapshot.isPresent()) {
                throw new IllegalArgumentException(
                        "Accepted mass preflight requires a snapshot and rejected preflight requires failures"
                );
            }
        }

        public boolean accepted() {
            return this.failures.isEmpty() && this.snapshot.isPresent();
        }
    }
}
