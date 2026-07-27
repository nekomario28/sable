package dev.ryanhcode.sable.sublevel.transfer;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Immutable transfer snapshot payload sealed with a SHA-256 digest.
 */
public final class CrossLevelTransferSnapshotEnvelope {
    public static final int SHA_256_LENGTH = 32;

    private final UUID transactionId;
    private final UUID subLevelId;
    private final int snapshotFormatVersion;
    private final byte[] payload;
    private final byte[] digest;

    private CrossLevelTransferSnapshotEnvelope(
            final UUID transactionId,
            final UUID subLevelId,
            final int snapshotFormatVersion,
            final byte[] payload,
            final byte[] digest
    ) {
        this.transactionId = Objects.requireNonNull(transactionId, "transactionId");
        this.subLevelId = Objects.requireNonNull(subLevelId, "subLevelId");
        if (snapshotFormatVersion < 1) {
            throw new IllegalArgumentException("snapshot format version must be positive");
        }
        Objects.requireNonNull(payload, "payload");
        Objects.requireNonNull(digest, "digest");
        if (payload.length == 0) {
            throw new IllegalArgumentException("snapshot payload must not be empty");
        }
        if (digest.length != SHA_256_LENGTH) {
            throw new IllegalArgumentException("SHA-256 digest must contain exactly 32 bytes");
        }

        this.snapshotFormatVersion = snapshotFormatVersion;
        this.payload = payload.clone();
        this.digest = digest.clone();
    }

    /**
     * Creates a new envelope and computes its digest from an immutable payload copy.
     */
    public static CrossLevelTransferSnapshotEnvelope create(
            final UUID transactionId,
            final UUID subLevelId,
            final int snapshotFormatVersion,
            final byte[] payload
    ) {
        Objects.requireNonNull(payload, "payload");
        final byte[] payloadCopy = payload.clone();
        return new CrossLevelTransferSnapshotEnvelope(
                transactionId,
                subLevelId,
                snapshotFormatVersion,
                payloadCopy,
                sha256(payloadCopy)
        );
    }

    /**
     * Restores a stored envelope only when all metadata is valid and the payload
     * digest matches. Corrupt or unsupported input fails closed.
     */
    public static Optional<CrossLevelTransferSnapshotEnvelope> restore(
            final UUID transactionId,
            final UUID subLevelId,
            final int snapshotFormatVersion,
            final byte[] payload,
            final byte[] digest
    ) {
        try {
            final CrossLevelTransferSnapshotEnvelope envelope = new CrossLevelTransferSnapshotEnvelope(
                    transactionId,
                    subLevelId,
                    snapshotFormatVersion,
                    payload,
                    digest
            );
            return envelope.verifyIntegrity() ? Optional.of(envelope) : Optional.empty();
        } catch (final RuntimeException exception) {
            return Optional.empty();
        }
    }

    public boolean verifyIntegrity() {
        return MessageDigest.isEqual(this.digest, sha256(this.payload));
    }

    public boolean matches(final CrossLevelTransferTransactionState state) {
        Objects.requireNonNull(state, "state");
        return this.transactionId.equals(state.transactionId()) &&
                this.subLevelId.equals(state.subLevelId()) &&
                this.snapshotFormatVersion == state.snapshotFormatVersion();
    }

    public UUID transactionId() {
        return this.transactionId;
    }

    public UUID subLevelId() {
        return this.subLevelId;
    }

    public int snapshotFormatVersion() {
        return this.snapshotFormatVersion;
    }

    public byte[] payload() {
        return this.payload.clone();
    }

    public byte[] digest() {
        return this.digest.clone();
    }

    private static byte[] sha256(final byte[] payload) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(payload);
        } catch (final NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    @Override
    public boolean equals(final Object object) {
        if (this == object) return true;
        if (!(object instanceof final CrossLevelTransferSnapshotEnvelope that)) return false;
        return this.snapshotFormatVersion == that.snapshotFormatVersion &&
                this.transactionId.equals(that.transactionId) &&
                this.subLevelId.equals(that.subLevelId) &&
                Arrays.equals(this.payload, that.payload) &&
                Arrays.equals(this.digest, that.digest);
    }

    @Override
    public int hashCode() {
        int result = Objects.hash(this.transactionId, this.subLevelId, this.snapshotFormatVersion);
        result = 31 * result + Arrays.hashCode(this.payload);
        result = 31 * result + Arrays.hashCode(this.digest);
        return result;
    }
}
