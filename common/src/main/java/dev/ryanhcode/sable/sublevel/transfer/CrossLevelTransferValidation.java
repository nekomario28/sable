package dev.ryanhcode.sable.sublevel.transfer;

import java.util.Collection;
import java.util.Collections;
import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;

/**
 * Immutable result of cross-level transfer preflight validation.
 */
public final class CrossLevelTransferValidation {
    private static final CrossLevelTransferValidation ACCEPTED = new CrossLevelTransferValidation(
            EnumSet.noneOf(CrossLevelTransferFailure.class)
    );

    private final Set<CrossLevelTransferFailure> failures;

    private CrossLevelTransferValidation(final EnumSet<CrossLevelTransferFailure> failures) {
        this.failures = Collections.unmodifiableSet(failures);
    }

    /**
     * @return the shared successful validation result
     */
    public static CrossLevelTransferValidation accepted() {
        return ACCEPTED;
    }

    /**
     * Creates a rejected validation result.
     *
     * @param failures one or more failure codes
     * @return an immutable rejected validation result
     */
    public static CrossLevelTransferValidation rejected(final Collection<CrossLevelTransferFailure> failures) {
        Objects.requireNonNull(failures, "failures");

        final EnumSet<CrossLevelTransferFailure> copy = EnumSet.noneOf(CrossLevelTransferFailure.class);
        for (final CrossLevelTransferFailure failure : failures) {
            copy.add(Objects.requireNonNull(failure, "failure"));
        }

        if (copy.isEmpty()) {
            throw new IllegalArgumentException("A rejected validation must contain at least one failure");
        }

        return new CrossLevelTransferValidation(copy);
    }

    /**
     * @return whether validation passed
     */
    public boolean isAccepted() {
        return this.failures.isEmpty();
    }

    /**
     * @return immutable failure codes; empty when accepted
     */
    public Set<CrossLevelTransferFailure> failures() {
        return this.failures;
    }

    /**
     * @param failure the failure code to query
     * @return whether the result contains that failure
     */
    public boolean hasFailure(final CrossLevelTransferFailure failure) {
        return this.failures.contains(Objects.requireNonNull(failure, "failure"));
    }

    @Override
    public boolean equals(final Object object) {
        if (this == object) return true;
        if (!(object instanceof final CrossLevelTransferValidation that)) return false;
        return this.failures.equals(that.failures);
    }

    @Override
    public int hashCode() {
        return this.failures.hashCode();
    }

    @Override
    public String toString() {
        return this.isAccepted() ? "CrossLevelTransferValidation[accepted]" :
                "CrossLevelTransferValidation[failures=" + this.failures + ']';
    }
}
