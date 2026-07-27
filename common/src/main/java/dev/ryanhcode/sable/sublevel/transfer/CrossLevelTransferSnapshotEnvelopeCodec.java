package dev.ryanhcode.sable.sublevel.transfer;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Versioned NBT codec for immutable transfer snapshot envelopes.
 */
public final class CrossLevelTransferSnapshotEnvelopeCodec {
    private static final int CODEC_VERSION = 1;

    private static final String CODEC_VERSION_KEY = "codec_version";
    private static final String TRANSACTION_ID_KEY = "transaction_id";
    private static final String SUB_LEVEL_ID_KEY = "sub_level_id";
    private static final String SNAPSHOT_FORMAT_VERSION_KEY = "snapshot_format_version";
    private static final String PAYLOAD_KEY = "payload";
    private static final String DIGEST_KEY = "sha256";

    private CrossLevelTransferSnapshotEnvelopeCodec() {
    }

    public static CompoundTag encode(final CrossLevelTransferSnapshotEnvelope envelope) {
        Objects.requireNonNull(envelope, "envelope");
        if (!envelope.verifyIntegrity()) {
            throw new IllegalArgumentException("Cannot encode a corrupt snapshot envelope");
        }

        final CompoundTag tag = new CompoundTag();
        tag.putInt(CODEC_VERSION_KEY, CODEC_VERSION);
        tag.putUUID(TRANSACTION_ID_KEY, envelope.transactionId());
        tag.putUUID(SUB_LEVEL_ID_KEY, envelope.subLevelId());
        tag.putInt(SNAPSHOT_FORMAT_VERSION_KEY, envelope.snapshotFormatVersion());
        tag.putByteArray(PAYLOAD_KEY, envelope.payload());
        tag.putByteArray(DIGEST_KEY, envelope.digest());
        return tag;
    }

    /**
     * Decodes one complete envelope fail-closed and verifies its SHA-256 digest.
     *
     * @param tag persisted envelope NBT
     * @param maximumPayloadBytes maximum accepted payload size
     */
    public static Optional<CrossLevelTransferSnapshotEnvelope> decode(
            final CompoundTag tag,
            final int maximumPayloadBytes
    ) {
        Objects.requireNonNull(tag, "tag");
        if (maximumPayloadBytes < 1) {
            throw new IllegalArgumentException("maximumPayloadBytes must be positive");
        }
        if (!hasRequiredFields(tag) || tag.getInt(CODEC_VERSION_KEY) != CODEC_VERSION) {
            return Optional.empty();
        }

        try {
            final UUID transactionId = tag.getUUID(TRANSACTION_ID_KEY);
            final UUID subLevelId = tag.getUUID(SUB_LEVEL_ID_KEY);
            final int snapshotFormatVersion = tag.getInt(SNAPSHOT_FORMAT_VERSION_KEY);
            final byte[] payload = tag.getByteArray(PAYLOAD_KEY);
            final byte[] digest = tag.getByteArray(DIGEST_KEY);

            if (payload.length == 0 || payload.length > maximumPayloadBytes ||
                    digest.length != CrossLevelTransferSnapshotEnvelope.SHA_256_LENGTH) {
                return Optional.empty();
            }

            return CrossLevelTransferSnapshotEnvelope.restore(
                    transactionId,
                    subLevelId,
                    snapshotFormatVersion,
                    payload,
                    digest
            );
        } catch (final RuntimeException exception) {
            return Optional.empty();
        }
    }

    private static boolean hasRequiredFields(final CompoundTag tag) {
        return tag.contains(CODEC_VERSION_KEY, Tag.TAG_INT) &&
                tag.contains(TRANSACTION_ID_KEY, Tag.TAG_INT_ARRAY) &&
                tag.contains(SUB_LEVEL_ID_KEY, Tag.TAG_INT_ARRAY) &&
                tag.contains(SNAPSHOT_FORMAT_VERSION_KEY, Tag.TAG_INT) &&
                tag.contains(PAYLOAD_KEY, Tag.TAG_BYTE_ARRAY) &&
                tag.contains(DIGEST_KEY, Tag.TAG_BYTE_ARRAY);
    }
}
