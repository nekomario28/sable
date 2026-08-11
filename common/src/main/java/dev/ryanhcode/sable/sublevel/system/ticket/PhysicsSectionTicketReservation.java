package dev.ryanhcode.sable.sublevel.system.ticket;

import net.minecraft.core.SectionPos;
import org.jetbrains.annotations.ApiStatus;

import java.util.Objects;

/**
 * Single-use ownership evidence for one entry in {@link PhysicsChunkTicketManager}'s section-ticket map.
 *
 * <p>A reservation is either borrowed, meaning the exact ticket existed before the transaction,
 * or owned, meaning this reservation inserted the exact ticket. Rollback removes only owned
 * tickets and verifies borrowed tickets were not replaced or removed. This class deliberately
 * owns only the ticket-map entry; physics-pipeline section upload/removal is a separate resource
 * and must have its own rollback action.</p>
 */
@ApiStatus.Internal
public final class PhysicsSectionTicketReservation {
    public enum Ownership {
        BORROWED,
        OWNED
    }

    public enum State {
        ACTIVE,
        COMMITTED,
        ROLLED_BACK
    }

    private final PhysicsChunkTicketManager manager;
    private final SectionPos sectionPos;
    private final PhysicsChunkTicket ticket;
    private final Ownership ownership;
    private State state = State.ACTIVE;

    PhysicsSectionTicketReservation(
            final PhysicsChunkTicketManager manager,
            final SectionPos sectionPos,
            final PhysicsChunkTicket ticket,
            final Ownership ownership
    ) {
        this.manager = Objects.requireNonNull(manager, "manager");
        this.sectionPos = Objects.requireNonNull(sectionPos, "sectionPos");
        this.ticket = Objects.requireNonNull(ticket, "ticket");
        this.ownership = Objects.requireNonNull(ownership, "ownership");
    }

    public SectionPos sectionPos() {
        return this.sectionPos;
    }

    public Ownership ownership() {
        return this.ownership;
    }

    public boolean owned() {
        return this.ownership == Ownership.OWNED;
    }

    public State state() {
        return this.state;
    }

    /**
     * Verifies that the exact reserved ticket can be committed without making the reservation terminal.
     *
     * <p>This is intentionally separate from sealing so a coordinator can verify every transaction
     * resource first and still roll all of them back if a later verification fails.</p>
     */
    void verifyCommit() {
        this.requireActive("verify commit");
        this.manager.verifyReservation(this);
    }

    /**
     * Makes a previously verified reservation terminal without performing any external mutation.
     */
    void sealCommit() {
        this.requireActive("seal commit");
        this.state = State.COMMITTED;
    }

    /**
     * Convenience single-resource commit: verify exact ownership, then seal.
     */
    public void commit() {
        this.verifyCommit();
        this.sealCommit();
    }

    /**
     * Restores ticket-map ownership to its pre-reservation state.
     *
     * <p>Owned reservations remove only their exact ticket by identity. Borrowed reservations do
     * not mutate the map, but still verify the pre-existing ticket is unchanged.</p>
     */
    public void rollback() {
        this.requireActive("rollback");
        this.manager.rollbackReservation(this);
        this.state = State.ROLLED_BACK;
    }

    PhysicsChunkTicket ticket() {
        return this.ticket;
    }

    PhysicsChunkTicketManager manager() {
        return this.manager;
    }

    private void requireActive(final String operation) {
        if (this.state != State.ACTIVE) {
            throw new IllegalStateException(
                    "Cannot " + operation + " physics section ticket reservation while it is " + this.state
            );
        }
    }
}
