package dev.ryanhcode.sable.sublevel.system.ticket;

import net.minecraft.core.SectionPos;

import java.util.ArrayList;
import java.util.List;

/** Assertion-based executable for physics section materialization ownership and cleanup. */
public final class PhysicsSectionMaterializationSelfTest {
    private PhysicsSectionMaterializationSelfTest() {
    }

    public static void main(final String[] args) {
        ownedSectionUploadsAndRollsBack();
        borrowedSectionIsNeverUploadedOrRemoved();
        additionFailureFailsClosedAndRestoresTicket();
        rollbackStackAdapterSurfacesCleanupFailure();
        commitVerificationRemainsRollbackable();
        commitPreservesSectionTicket();
        System.out.println("PHYSICS_SECTION_MATERIALIZATION_SELF_TEST: PASS");
    }

    private static void ownedSectionUploadsAndRollsBack() {
        final PhysicsChunkTicketManager manager = new PhysicsChunkTicketManager();
        final PipelineProbe probe = new PipelineProbe();
        final SectionPos pos = SectionPos.of(1, 2, 3);
        final PhysicsSectionMaterialization materialization = acquired(
                PhysicsSectionMaterialization.acquire(
                        manager,
                        pos,
                        10L,
                        () -> probe.add(true),
                        probe::remove
                )
        );

        assert materialization.uploadedByTransaction();
        assert materialization.ticketOwnership() == PhysicsSectionTicketReservation.Ownership.OWNED;
        assert probe.events.equals(List.of("add:true"));

        final PhysicsSectionMaterialization.RollbackReport rollback = materialization.rollback();
        assert rollback.successful();
        assert probe.events.equals(List.of("add:true", "remove"));

        final PhysicsSectionTicketReservation after = manager.reserveTicketForSection(pos, 11L);
        assert after.owned();
        after.rollback();
    }

    private static void borrowedSectionIsNeverUploadedOrRemoved() {
        final PhysicsChunkTicketManager manager = new PhysicsChunkTicketManager();
        final SectionPos pos = SectionPos.of(4, 5, 6);
        final PhysicsSectionTicketReservation preExisting = manager.reserveTicketForSection(pos, 12L);
        final PipelineProbe probe = new PipelineProbe();

        final PhysicsSectionMaterialization materialization = acquired(
                PhysicsSectionMaterialization.acquire(
                        manager,
                        pos,
                        13L,
                        () -> probe.add(true),
                        probe::remove
                )
        );

        assert !materialization.uploadedByTransaction();
        assert materialization.ticketOwnership() == PhysicsSectionTicketReservation.Ownership.BORROWED;
        assert probe.events.isEmpty();
        assert materialization.rollback().successful();
        assert probe.events.isEmpty();

        final PhysicsSectionTicketReservation stillExisting = manager.reserveTicketForSection(pos, 14L);
        assert !stillExisting.owned();
        assert stillExisting.ticket() == preExisting.ticket();
        stillExisting.rollback();
        preExisting.rollback();
    }

    private static void additionFailureFailsClosedAndRestoresTicket() {
        final PhysicsChunkTicketManager manager = new PhysicsChunkTicketManager();
        final PipelineProbe probe = new PipelineProbe();
        probe.failAddition = true;
        final SectionPos pos = SectionPos.of(7, 8, 9);

        final PhysicsSectionMaterialization.Failed failed = failed(
                PhysicsSectionMaterialization.acquire(
                        manager,
                        pos,
                        15L,
                        () -> probe.add(false),
                        probe::remove
                )
        );

        assert !failed.rollbackSuccessful();
        assert failed.cleanupFailures().size() == 1;
        assert failed.cleanupFailures().getFirst().resource().equals("physics_section_state_unknown");
        assert probe.events.equals(List.of("add:false"));

        // Ticket ownership is known and is restored even though pipeline mutation outcome is unknown.
        final PhysicsSectionTicketReservation after = manager.reserveTicketForSection(pos, 16L);
        assert after.owned();
        after.rollback();
    }

    private static void rollbackStackAdapterSurfacesCleanupFailure() {
        final PhysicsChunkTicketManager manager = new PhysicsChunkTicketManager();
        final PipelineProbe probe = new PipelineProbe();
        final SectionPos pos = SectionPos.of(10, 11, 12);
        final PhysicsSectionMaterialization materialization = acquired(
                PhysicsSectionMaterialization.acquire(
                        manager,
                        pos,
                        17L,
                        () -> probe.add(true),
                        probe::remove
                )
        );
        probe.failRemoval = true;

        PhysicsSectionMaterialization.RollbackException thrown = null;
        try {
            materialization.rollbackOrThrow();
        } catch (final PhysicsSectionMaterialization.RollbackException exception) {
            thrown = exception;
        }

        assert thrown != null;
        assert thrown.report().state() == PhysicsSectionMaterialization.State.ROLLBACK_FAILED;
        assert thrown.report().failures().size() == 1;
        assert thrown.report().failures().getFirst().resource().equals("physics_section");
        assert materialization.state() == PhysicsSectionMaterialization.State.ROLLBACK_FAILED;

        // Ticket cleanup still ran even though section cleanup failed.
        final PhysicsSectionTicketReservation after = manager.reserveTicketForSection(pos, 18L);
        assert after.owned();
        after.rollback();
    }

    private static void commitVerificationRemainsRollbackable() {
        final PhysicsChunkTicketManager manager = new PhysicsChunkTicketManager();
        final PipelineProbe probe = new PipelineProbe();
        final SectionPos pos = SectionPos.of(13, 14, 15);
        final PhysicsSectionMaterialization materialization = acquired(
                PhysicsSectionMaterialization.acquire(
                        manager,
                        pos,
                        19L,
                        () -> probe.add(true),
                        probe::remove
                )
        );

        materialization.verifyCommit();

        assert materialization.state() == PhysicsSectionMaterialization.State.ACTIVE;
        assert materialization.rollback().successful();
        assert probe.events.equals(List.of("add:true", "remove"));

        final PhysicsSectionTicketReservation after = manager.reserveTicketForSection(pos, 20L);
        assert after.owned();
        after.rollback();
    }

    private static void commitPreservesSectionTicket() {
        final PhysicsChunkTicketManager manager = new PhysicsChunkTicketManager();
        final PipelineProbe probe = new PipelineProbe();
        final SectionPos pos = SectionPos.of(16, 17, 18);
        final PhysicsSectionMaterialization materialization = acquired(
                PhysicsSectionMaterialization.acquire(
                        manager,
                        pos,
                        21L,
                        () -> probe.add(true),
                        probe::remove
                )
        );

        materialization.commit();

        assert materialization.state() == PhysicsSectionMaterialization.State.COMMITTED;
        assertIllegalState(materialization::commit);
        assertIllegalState(materialization::rollback);
        assert probe.events.equals(List.of("add:true"));

        final PhysicsSectionTicketReservation borrowed = manager.reserveTicketForSection(pos, 22L);
        assert !borrowed.owned();
        borrowed.rollback();
    }

    private static PhysicsSectionMaterialization acquired(
            final PhysicsSectionMaterialization.Acquisition acquisition
    ) {
        assert acquisition instanceof PhysicsSectionMaterialization.Acquired;
        return ((PhysicsSectionMaterialization.Acquired) acquisition).materialization();
    }

    private static PhysicsSectionMaterialization.Failed failed(
            final PhysicsSectionMaterialization.Acquisition acquisition
    ) {
        assert acquisition instanceof PhysicsSectionMaterialization.Failed;
        return (PhysicsSectionMaterialization.Failed) acquisition;
    }

    private static void assertIllegalState(final Runnable operation) {
        boolean threw = false;
        try {
            operation.run();
        } catch (final IllegalStateException expected) {
            threw = true;
        }
        assert threw;
    }

    private static final class PipelineProbe {
        private final List<String> events = new ArrayList<>();
        private boolean failAddition;
        private boolean failRemoval;

        private void add(final boolean uploadDataIfGlobal) {
            this.events.add("add:" + uploadDataIfGlobal);
            if (this.failAddition) {
                throw new IllegalStateException("injected addition failure");
            }
        }

        private void remove() {
            this.events.add("remove");
            if (this.failRemoval) {
                throw new IllegalStateException("injected removal failure");
            }
        }
    }
}
