package dev.ryanhcode.sable.sublevel.transfer;

import java.util.Objects;

/**
 * Coordinates all read-only Phase 01 preflight evidence and typed validation.
 *
 * <p>The result is an instantaneous assessment only. It does not force journal
 * durability, reserve a target slot, or authorize subsequent world mutation.</p>
 */
public final class CrossLevelTransferPreflightCoordinator {
    private CrossLevelTransferPreflightCoordinator() {
    }

    /**
     * Collects independently authoritative evidence without mutating any source.
     */
    public static CrossLevelTransferAuthoritativePreflightEvidence collectEvidence(
            final CrossLevelTransferJournalController journalController,
            final CrossLevelTransferUuidLocationIndex uuidLocationIndex,
            final CrossLevelTransferSnapshotEnvelope snapshotEnvelope,
            final CrossLevelTransferTransactionState expected
    ) {
        Objects.requireNonNull(journalController, "journalController");
        Objects.requireNonNull(uuidLocationIndex, "uuidLocationIndex");
        Objects.requireNonNull(snapshotEnvelope, "snapshotEnvelope");
        Objects.requireNonNull(expected, "expected");

        CrossLevelTransferAuthoritativePreflightEvidence evidence =
                CrossLevelTransferAuthoritativePreflightEvidence.unverified();
        evidence = CrossLevelTransferOwnershipEvidenceProvider.enrich(
                evidence,
                journalController,
                expected
        );
        evidence = CrossLevelTransferUuidEvidenceProvider.enrich(
                evidence,
                uuidLocationIndex,
                expected
        );
        return CrossLevelTransferSnapshotEvidenceProvider.enrich(
                evidence,
                snapshotEnvelope,
                expected
        );
    }

    /**
     * Collects all authoritative evidence, assembles it with runtime facts, and
     * returns the normal typed fail-closed validation result.
     */
    public static CrossLevelTransferValidation validate(
            final CrossLevelTransferPreflightFacts runtimeFacts,
            final CrossLevelTransferJournalController journalController,
            final CrossLevelTransferUuidLocationIndex uuidLocationIndex,
            final CrossLevelTransferSnapshotEnvelope snapshotEnvelope,
            final CrossLevelTransferTransactionState expected
    ) {
        Objects.requireNonNull(runtimeFacts, "runtimeFacts");
        return CrossLevelTransferPreflightAssembler.validate(
                runtimeFacts,
                collectEvidence(journalController, uuidLocationIndex, snapshotEnvelope, expected)
        );
    }
}
