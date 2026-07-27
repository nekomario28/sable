package dev.ryanhcode.sable.sublevel.transfer;

import java.util.Objects;

/**
 * Read-only provider for authoritative verified snapshot evidence.
 */
public final class CrossLevelTransferSnapshotEvidenceProvider {
    private CrossLevelTransferSnapshotEvidenceProvider() {
    }

    /**
     * Replaces only the snapshot verification field. A snapshot is authoritative
     * only when its digest is valid and its immutable identity matches the transfer.
     */
    public static CrossLevelTransferAuthoritativePreflightEvidence enrich(
            final CrossLevelTransferAuthoritativePreflightEvidence evidence,
            final CrossLevelTransferSnapshotEnvelope envelope,
            final CrossLevelTransferTransactionState expected
    ) {
        Objects.requireNonNull(evidence, "evidence");
        Objects.requireNonNull(envelope, "envelope");
        Objects.requireNonNull(expected, "expected");

        return new CrossLevelTransferAuthoritativePreflightEvidence(
                evidence.targetUuidUnique(),
                evidence.transactionOwned(),
                envelope.verifyIntegrity() && envelope.matches(expected)
        );
    }
}
