package dev.ryanhcode.sable.sublevel.transfer;

import java.util.Objects;

/**
 * Read-only provider for authoritative target UUID uniqueness evidence.
 */
public final class CrossLevelTransferUuidEvidenceProvider {
    private CrossLevelTransferUuidEvidenceProvider() {
    }

    /**
     * Proves target UUID uniqueness only when a complete conflict-free index shows
     * the UUID at the exact loaded source location and nowhere else.
     *
     * @param evidence current authoritative evidence
     * @param index complete UUID location index
     * @param expected expected transfer identity and source local slot
     * @return evidence enriched only in the UUID uniqueness field
     */
    public static CrossLevelTransferAuthoritativePreflightEvidence enrich(
            final CrossLevelTransferAuthoritativePreflightEvidence evidence,
            final CrossLevelTransferUuidLocationIndex index,
            final CrossLevelTransferTransactionState expected
    ) {
        Objects.requireNonNull(evidence, "evidence");
        Objects.requireNonNull(index, "index");
        Objects.requireNonNull(expected, "expected");

        final CrossLevelTransferSubLevelLocation expectedSourceLocation =
                new CrossLevelTransferSubLevelLocation.Loaded(
                        expected.sourceDimension(),
                        expected.localPlotX(),
                        expected.localPlotZ()
                );

        return new CrossLevelTransferAuthoritativePreflightEvidence(
                index.provesExactLocation(expected.subLevelId(), expectedSourceLocation),
                evidence.transactionOwned(),
                evidence.snapshotVerified()
        );
    }
}
