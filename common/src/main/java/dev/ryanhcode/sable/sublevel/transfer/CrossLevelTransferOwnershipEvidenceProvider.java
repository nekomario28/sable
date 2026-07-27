package dev.ryanhcode.sable.sublevel.transfer;

import java.util.Objects;

/**
 * Read-only provider for authoritative transaction ownership evidence.
 */
public final class CrossLevelTransferOwnershipEvidenceProvider {
    private CrossLevelTransferOwnershipEvidenceProvider() {
    }

    /**
     * Replaces only the transaction ownership field of existing authoritative
     * evidence. UUID uniqueness and snapshot verification remain unchanged.
     *
     * @param evidence current authoritative evidence
     * @param controller authoritative journal controller
     * @param expected expected immutable transfer identity
     * @return evidence enriched with atomic ownership verification
     */
    public static CrossLevelTransferAuthoritativePreflightEvidence enrich(
            final CrossLevelTransferAuthoritativePreflightEvidence evidence,
            final CrossLevelTransferJournalController controller,
            final CrossLevelTransferTransactionState expected
    ) {
        Objects.requireNonNull(evidence, "evidence");
        Objects.requireNonNull(controller, "controller");
        Objects.requireNonNull(expected, "expected");

        return new CrossLevelTransferAuthoritativePreflightEvidence(
                evidence.targetUuidUnique(),
                controller.ownsTransfer(expected),
                evidence.snapshotVerified()
        );
    }
}
