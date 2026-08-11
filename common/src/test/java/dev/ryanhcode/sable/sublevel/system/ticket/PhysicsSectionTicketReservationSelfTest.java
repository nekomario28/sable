package dev.ryanhcode.sable.sublevel.system.ticket;

import net.minecraft.core.SectionPos;

/** Assertion-based executable for exact physics section-ticket ownership semantics. */
public final class PhysicsSectionTicketReservationSelfTest {
    private PhysicsSectionTicketReservationSelfTest() {
    }

    public static void main(final String[] args) {
        borrowedRollbackPreservesOwnedTicket();
        ownedRollbackRestoresAbsence();
        commitPreservesTicketAndIsTerminal();
        borrowedReservationDetectsOwnershipDrift();
        foreignManagerCannotReleaseReservation();
        System.out.println("PHYSICS_SECTION_TICKET_RESERVATION_SELF_TEST: PASS");
    }

    private static void borrowedRollbackPreservesOwnedTicket() {
        final PhysicsChunkTicketManager manager = new PhysicsChunkTicketManager();
        final SectionPos pos = SectionPos.of(1, 2, 3);
        final PhysicsSectionTicketReservation owner = manager.reserveTicketForSection(pos, 10L);
        final PhysicsSectionTicketReservation borrowed = manager.reserveTicketForSection(pos, 20L);

        assert owner.owned();
        assert !borrowed.owned();
        assert owner.ticket() == borrowed.ticket();

        borrowed.rollback();

        assert borrowed.state() == PhysicsSectionTicketReservation.State.ROLLED_BACK;
        final PhysicsSectionTicketReservation stillBorrowed = manager.reserveTicketForSection(pos, 30L);
        assert !stillBorrowed.owned();
        assert stillBorrowed.ticket() == owner.ticket();
        stillBorrowed.rollback();
        owner.rollback();
    }

    private static void ownedRollbackRestoresAbsence() {
        final PhysicsChunkTicketManager manager = new PhysicsChunkTicketManager();
        final SectionPos pos = SectionPos.of(-4, 5, 6);
        final PhysicsSectionTicketReservation first = manager.reserveTicketForSection(pos, 11L);
        assert first.owned();

        first.rollback();

        final PhysicsSectionTicketReservation second = manager.reserveTicketForSection(pos, 12L);
        assert second.owned();
        assert second.ticket() != first.ticket();
        second.rollback();
    }

    private static void commitPreservesTicketAndIsTerminal() {
        final PhysicsChunkTicketManager manager = new PhysicsChunkTicketManager();
        final SectionPos pos = SectionPos.of(7, 8, 9);
        final PhysicsSectionTicketReservation owner = manager.reserveTicketForSection(pos, 13L);
        owner.commit();

        assert owner.state() == PhysicsSectionTicketReservation.State.COMMITTED;
        assertIllegalState(owner::commit);
        assertIllegalState(owner::rollback);

        final PhysicsSectionTicketReservation borrowed = manager.reserveTicketForSection(pos, 14L);
        assert !borrowed.owned();
        assert borrowed.ticket() == owner.ticket();
        borrowed.rollback();
    }

    private static void borrowedReservationDetectsOwnershipDrift() {
        final PhysicsChunkTicketManager manager = new PhysicsChunkTicketManager();
        final SectionPos pos = SectionPos.of(10, 11, 12);
        final PhysicsSectionTicketReservation owner = manager.reserveTicketForSection(pos, 15L);
        final PhysicsSectionTicketReservation borrowed = manager.reserveTicketForSection(pos, 16L);

        owner.rollback();

        assertIllegalState(borrowed::rollback);
        assert borrowed.state() == PhysicsSectionTicketReservation.State.ACTIVE;
    }

    private static void foreignManagerCannotReleaseReservation() {
        final PhysicsChunkTicketManager ownerManager = new PhysicsChunkTicketManager();
        final PhysicsChunkTicketManager foreignManager = new PhysicsChunkTicketManager();
        final PhysicsSectionTicketReservation reservation =
                ownerManager.reserveTicketForSection(SectionPos.of(13, 14, 15), 17L);

        assertIllegalArgument(() -> foreignManager.rollbackReservation(reservation));
        reservation.rollback();
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

    private static void assertIllegalArgument(final Runnable operation) {
        boolean threw = false;
        try {
            operation.run();
        } catch (final IllegalArgumentException expected) {
            threw = true;
        }
        assert threw;
    }
}
