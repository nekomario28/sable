package dev.ryanhcode.sable.sublevel.system.ticket;

import dev.ryanhcode.sable.api.physics.PhysicsPipeline;
import net.minecraft.core.SectionPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.chunk.LevelChunkSection;
import org.jetbrains.annotations.ApiStatus;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Transaction-owned physics section resource built on top of exact ticket ownership.
 *
 * <p>If the ticket already existed, the section is treated as shared/pre-existing and is not
 * uploaded or removed by this resource. If the ticket is newly owned, the section is uploaded and
 * rollback removes the pipeline section before removing the owned ticket. Cleanup continues after
 * individual failures and reports all failures.</p>
 */
@ApiStatus.Internal
public final class PhysicsSectionMaterialization {
    public enum State {
        ACTIVE,
        COMMITTED,
        ROLLED_BACK,
        ROLLBACK_FAILED
    }

    public record CleanupFailure(String resource, Throwable cause) {
        public CleanupFailure {
            Objects.requireNonNull(resource, "resource");
            Objects.requireNonNull(cause, "cause");
        }
    }

    public record RollbackReport(State state, List<CleanupFailure> failures) {
        public RollbackReport {
            Objects.requireNonNull(state, "state");
            failures = List.copyOf(Objects.requireNonNull(failures, "failures"));
            if (state != State.ROLLED_BACK && state != State.ROLLBACK_FAILED) {
                throw new IllegalArgumentException("Rollback report requires a rollback terminal state");
            }
            if ((state == State.ROLLED_BACK) != failures.isEmpty()) {
                throw new IllegalArgumentException("Rollback state and cleanup failures disagree");
            }
        }

        public boolean successful() {
            return this.state == State.ROLLED_BACK;
        }
    }

    /**
     * Exception adapter for rollback stacks whose action contract reports failure by throwing.
     */
    public static final class RollbackException extends Exception {
        private final RollbackReport report;

        private RollbackException(final RollbackReport report) {
            super(
                    "Physics section rollback failed for " + report.failures().size() + " resource(s)",
                    report.failures().getFirst().cause()
            );
            this.report = report;
            for (int index = 1; index < report.failures().size(); index++) {
                this.addSuppressed(report.failures().get(index).cause());
            }
        }

        public RollbackReport report() {
            return this.report;
        }
    }

    public sealed interface Acquisition permits Acquired, Failed {
        boolean acquired();
    }

    public record Acquired(PhysicsSectionMaterialization materialization) implements Acquisition {
        public Acquired {
            Objects.requireNonNull(materialization, "materialization");
        }

        @Override
        public boolean acquired() {
            return true;
        }
    }

    public record Failed(Throwable cause, List<CleanupFailure> cleanupFailures) implements Acquisition {
        public Failed {
            Objects.requireNonNull(cause, "cause");
            cleanupFailures = List.copyOf(Objects.requireNonNull(cleanupFailures, "cleanupFailures"));
        }

        @Override
        public boolean acquired() {
            return false;
        }

        public boolean rollbackSuccessful() {
            return this.cleanupFailures.isEmpty();
        }
    }

    private final PhysicsPipeline pipeline;
    private final SectionPos sectionPos;
    private final PhysicsSectionTicketReservation ticketReservation;
    private final boolean uploadedByTransaction;
    private State state = State.ACTIVE;

    private PhysicsSectionMaterialization(
            final PhysicsPipeline pipeline,
            final SectionPos sectionPos,
            final PhysicsSectionTicketReservation ticketReservation,
            final boolean uploadedByTransaction
    ) {
        this.pipeline = Objects.requireNonNull(pipeline, "pipeline");
        this.sectionPos = Objects.requireNonNull(sectionPos, "sectionPos");
        this.ticketReservation = Objects.requireNonNull(ticketReservation, "ticketReservation");
        this.uploadedByTransaction = uploadedByTransaction;
    }

    /**
     * Acquires a section resource on the owning server thread.
     */
    public static Acquisition acquire(
            final ServerLevel level,
            final PhysicsChunkTicketManager ticketManager,
            final PhysicsPipeline pipeline,
            final LevelChunkSection section,
            final SectionPos sectionPos,
            final boolean uploadDataIfGlobal
    ) {
        Objects.requireNonNull(level, "level");
        if (!level.getServer().isSameThread()) {
            return new Failed(
                    new IllegalStateException("Physics section materialization must run on the owning server thread"),
                    List.of()
            );
        }
        return acquire(
                ticketManager,
                pipeline,
                section,
                sectionPos,
                level.getGameTime(),
                uploadDataIfGlobal
        );
    }

    /** Package-private pure seam for executable ownership/failure tests. */
    static Acquisition acquire(
            final PhysicsChunkTicketManager ticketManager,
            final PhysicsPipeline pipeline,
            final LevelChunkSection section,
            final SectionPos sectionPos,
            final long gameTime,
            final boolean uploadDataIfGlobal
    ) {
        Objects.requireNonNull(ticketManager, "ticketManager");
        Objects.requireNonNull(pipeline, "pipeline");
        Objects.requireNonNull(section, "section");
        Objects.requireNonNull(sectionPos, "sectionPos");

        final PhysicsSectionTicketReservation ticket =
                ticketManager.reserveTicketForSection(sectionPos, gameTime);
        if (!ticket.owned()) {
            return new Acquired(new PhysicsSectionMaterialization(
                    pipeline,
                    sectionPos,
                    ticket,
                    false
            ));
        }

        try {
            pipeline.handleChunkSectionAddition(
                    section,
                    sectionPos.x(),
                    sectionPos.y(),
                    sectionPos.z(),
                    uploadDataIfGlobal
            );
            return new Acquired(new PhysicsSectionMaterialization(
                    pipeline,
                    sectionPos,
                    ticket,
                    true
            ));
        } catch (final Throwable additionFailure) {
            final List<CleanupFailure> cleanupFailures = new ArrayList<>();
            try {
                pipeline.handleChunkSectionRemoval(sectionPos.x(), sectionPos.y(), sectionPos.z());
            } catch (final Throwable cleanupFailure) {
                cleanupFailures.add(new CleanupFailure("physics_section", cleanupFailure));
            }
            try {
                ticket.rollback();
            } catch (final Throwable cleanupFailure) {
                cleanupFailures.add(new CleanupFailure("physics_ticket", cleanupFailure));
            }
            return new Failed(additionFailure, cleanupFailures);
        }
    }

    public State state() {
        return this.state;
    }

    public boolean uploadedByTransaction() {
        return this.uploadedByTransaction;
    }

    public PhysicsSectionTicketReservation.Ownership ticketOwnership() {
        return this.ticketReservation.ownership();
    }

    /**
     * Verifies exact ticket ownership and seals the resource after the surrounding transaction commits.
     */
    public void commit() {
        this.requireActive("commit");
        this.ticketReservation.commit();
        this.state = State.COMMITTED;
    }

    /**
     * Restores pre-materialization section/ticket state, continuing cleanup after failures.
     */
    public RollbackReport rollback() {
        this.requireActive("rollback");
        final List<CleanupFailure> failures = new ArrayList<>();

        if (this.uploadedByTransaction) {
            try {
                this.pipeline.handleChunkSectionRemoval(
                        this.sectionPos.x(),
                        this.sectionPos.y(),
                        this.sectionPos.z()
                );
            } catch (final Throwable cleanupFailure) {
                failures.add(new CleanupFailure("physics_section", cleanupFailure));
            }
        }

        try {
            this.ticketReservation.rollback();
        } catch (final Throwable cleanupFailure) {
            failures.add(new CleanupFailure("physics_ticket", cleanupFailure));
        }

        this.state = failures.isEmpty() ? State.ROLLED_BACK : State.ROLLBACK_FAILED;
        return new RollbackReport(this.state, failures);
    }

    /**
     * Rollback-stack adapter that throws when exact resource cleanup could not be proven.
     */
    public void rollbackOrThrow() throws RollbackException {
        final RollbackReport report = this.rollback();
        if (!report.successful()) {
            throw new RollbackException(report);
        }
    }

    @ApiStatus.Internal
    PhysicsSectionTicketReservation ticketReservation() {
        return this.ticketReservation;
    }

    private void requireActive(final String operation) {
        if (this.state != State.ACTIVE) {
            throw new IllegalStateException(
                    "Cannot " + operation + " physics section materialization while it is " + this.state
            );
        }
    }
}
