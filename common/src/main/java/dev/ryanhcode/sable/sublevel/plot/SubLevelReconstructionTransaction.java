package dev.ryanhcode.sable.sublevel.plot;

import org.jetbrains.annotations.ApiStatus;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Objects;

/**
 * Single-use transaction token and rollback stack for future SubLevel reconstruction.
 *
 * <p>This class deliberately performs no Minecraft or Sable world mutation itself. Mutation stages
 * register their exact inverse before making the corresponding change. A pre-commit failure then
 * executes every registered inverse in strict reverse order, continuing after individual cleanup
 * failures so rollback evidence is complete instead of stopping at the first exception.</p>
 *
 * <p>Construction and state-changing operations are package-private on purpose. External callers
 * must enter through {@link SubLevelReconstructionAttempt#prepare} so mutation cannot begin from a
 * plan that skipped fresh container-baseline capture.</p>
 */
@ApiStatus.Experimental
public final class SubLevelReconstructionTransaction {
    public enum State {
        PREPARED,
        MATERIALIZING,
        COMMITTED,
        ROLLED_BACK,
        ROLLBACK_FAILED
    }

    @FunctionalInterface
    public interface RollbackAction {
        void run() throws Exception;
    }

    @FunctionalInterface
    public interface CommitVerificationAction {
        void run() throws Exception;
    }

    public record CleanupFailure(String label, Exception cause) {
        public CleanupFailure {
            Objects.requireNonNull(label, "label");
            Objects.requireNonNull(cause, "cause");
            if (label.isBlank()) {
                throw new IllegalArgumentException("Rollback action label cannot be blank");
            }
        }
    }

    public record CommitVerificationFailure(String label, Exception cause) {
        public CommitVerificationFailure {
            Objects.requireNonNull(label, "label");
            Objects.requireNonNull(cause, "cause");
            if (label.isBlank()) {
                throw new IllegalArgumentException("Commit verification label cannot be blank");
            }
        }
    }

    /**
     * Raised only before commit. The transaction remains MATERIALIZING and fully rollbackable.
     */
    public static final class CommitVerificationException extends RuntimeException {
        private final List<CommitVerificationFailure> failures;

        private CommitVerificationException(final List<CommitVerificationFailure> failures) {
            super(
                    "Reconstruction commit verification failed for " + failures.size() + " resource(s)",
                    failures.getFirst().cause()
            );
            this.failures = List.copyOf(failures);
            for (int index = 1; index < failures.size(); index++) {
                this.addSuppressed(failures.get(index).cause());
            }
        }

        public List<CommitVerificationFailure> failures() {
            return this.failures;
        }
    }

    public record RollbackReport(
            State state,
            Throwable trigger,
            List<CleanupFailure> cleanupFailures
    ) {
        public RollbackReport {
            Objects.requireNonNull(state, "state");
            Objects.requireNonNull(trigger, "trigger");
            cleanupFailures = List.copyOf(Objects.requireNonNull(cleanupFailures, "cleanupFailures"));
            if (state != State.ROLLED_BACK && state != State.ROLLBACK_FAILED) {
                throw new IllegalArgumentException("Rollback report requires a terminal rollback state");
            }
            if ((state == State.ROLLED_BACK) != cleanupFailures.isEmpty()) {
                throw new IllegalArgumentException(
                        "Successful rollback cannot contain cleanup failures and failed rollback must contain one"
                );
            }
        }

        public boolean successful() {
            return this.state == State.ROLLED_BACK;
        }
    }

    private record RegisteredRollback(String label, RollbackAction action) {
        private RegisteredRollback {
            Objects.requireNonNull(label, "label");
            Objects.requireNonNull(action, "action");
            if (label.isBlank()) {
                throw new IllegalArgumentException("Rollback action label cannot be blank");
            }
        }
    }

    private record RegisteredCommitVerification(String label, CommitVerificationAction action) {
        private RegisteredCommitVerification {
            Objects.requireNonNull(label, "label");
            Objects.requireNonNull(action, "action");
            if (label.isBlank()) {
                throw new IllegalArgumentException("Commit verification label cannot be blank");
            }
        }
    }

    private final SubLevelReconstructionPlan plan;
    private final Deque<RegisteredRollback> rollbackActions = new ArrayDeque<>();
    private final List<RegisteredCommitVerification> commitVerifications = new ArrayList<>();
    private State state = State.PREPARED;

    SubLevelReconstructionTransaction(final SubLevelReconstructionPlan plan) {
        this.plan = Objects.requireNonNull(plan, "plan");
    }

    public SubLevelReconstructionPlan plan() {
        return this.plan;
    }

    public State state() {
        return this.state;
    }

    /**
     * Enters the only state in which reconstruction mutations and resource checks may be registered.
     */
    void beginMaterialization() {
        this.requireState(State.PREPARED, "begin materialization");
        this.state = State.MATERIALIZING;
    }

    /**
     * Registers the exact inverse of a mutation before that mutation is attempted.
     *
     * <p>Actions are executed in reverse registration order.</p>
     */
    void registerRollback(final String label, final RollbackAction action) {
        this.requireState(State.MATERIALIZING, "register rollback action");
        this.rollbackActions.push(new RegisteredRollback(label, action));
    }

    /**
     * Registers a read-only verification that must pass immediately before commit.
     *
     * <p>Every verification runs even when an earlier verification fails, so the caller receives
     * complete failure evidence. A failed verification leaves the transaction MATERIALIZING with
     * all rollback actions intact.</p>
     */
    void registerCommitVerification(final String label, final CommitVerificationAction action) {
        this.requireState(State.MATERIALIZING, "register commit verification");
        this.commitVerifications.add(new RegisteredCommitVerification(label, action));
    }

    /**
     * Runs all read-only resource verification and commits only when all checks pass.
     */
    void commit() {
        this.requireState(State.MATERIALIZING, "commit");

        final List<CommitVerificationFailure> failures = new ArrayList<>();
        for (final RegisteredCommitVerification verification : this.commitVerifications) {
            try {
                verification.action().run();
            } catch (final Exception exception) {
                failures.add(new CommitVerificationFailure(verification.label(), exception));
            }
        }

        if (!failures.isEmpty()) {
            throw new CommitVerificationException(failures);
        }

        this.rollbackActions.clear();
        this.commitVerifications.clear();
        this.state = State.COMMITTED;
    }

    /**
     * Runs every registered inverse in reverse order and records all cleanup failures.
     */
    RollbackReport rollback(final Throwable trigger) {
        this.requireState(State.MATERIALIZING, "rollback");
        Objects.requireNonNull(trigger, "trigger");

        final List<CleanupFailure> failures = new ArrayList<>();
        while (!this.rollbackActions.isEmpty()) {
            final RegisteredRollback rollback = this.rollbackActions.pop();
            try {
                rollback.action().run();
            } catch (final Exception exception) {
                failures.add(new CleanupFailure(rollback.label(), exception));
            }
        }
        this.commitVerifications.clear();

        this.state = failures.isEmpty() ? State.ROLLED_BACK : State.ROLLBACK_FAILED;
        return new RollbackReport(this.state, trigger, failures);
    }

    @ApiStatus.Internal
    int pendingRollbackCount() {
        return this.rollbackActions.size();
    }

    @ApiStatus.Internal
    int pendingCommitVerificationCount() {
        return this.commitVerifications.size();
    }

    private void requireState(final State expected, final String operation) {
        if (this.state != expected) {
            throw new IllegalStateException(
                    "Cannot " + operation + " while reconstruction transaction is " + this.state +
                            "; expected " + expected
            );
        }
    }
}
