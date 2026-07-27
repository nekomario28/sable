package dev.ryanhcode.sable.sublevel.transfer;

/**
 * Authoritative evidence which cannot be proven by loaded runtime observation alone.
 *
 * @param targetUuidUnique whether an authoritative index proves the sub-level UUID is absent from the target
 * @param transactionOwned whether the exact transaction owns both source UUID and target slot
 * @param snapshotVerified whether an immutable snapshot exists and passed integrity verification
 */
public record CrossLevelTransferAuthoritativePreflightEvidence(
        boolean targetUuidUnique,
        boolean transactionOwned,
        boolean snapshotVerified
) {
    /**
     * @return evidence that proves nothing and therefore remains fail-closed
     */
    public static CrossLevelTransferAuthoritativePreflightEvidence unverified() {
        return new CrossLevelTransferAuthoritativePreflightEvidence(false, false, false);
    }
}
