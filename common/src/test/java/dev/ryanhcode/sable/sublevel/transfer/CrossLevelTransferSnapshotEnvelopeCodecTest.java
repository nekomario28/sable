package dev.ryanhcode.sable.sublevel.transfer;

import net.minecraft.nbt.CompoundTag;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class CrossLevelTransferSnapshotEnvelopeCodecTest {
    @Test
    void validEnvelopeRoundTrips() {
        final CrossLevelTransferSnapshotEnvelope envelope = envelope("snapshot");
        final CompoundTag encoded = CrossLevelTransferSnapshotEnvelopeCodec.encode(envelope);

        final CrossLevelTransferSnapshotEnvelope decoded =
                CrossLevelTransferSnapshotEnvelopeCodec.decode(
                        encoded,
                        envelope.payload().length
                ).orElseThrow();

        assertEquals(envelope, decoded);
        assertTrue(decoded.verifyIntegrity());
    }

    @Test
    void oversizedPayloadFailsClosed() {
        final CrossLevelTransferSnapshotEnvelope envelope = envelope("snapshot");
        final CompoundTag encoded = CrossLevelTransferSnapshotEnvelopeCodec.encode(envelope);

        assertTrue(CrossLevelTransferSnapshotEnvelopeCodec.decode(
                encoded,
                envelope.payload().length - 1
        ).isEmpty());
    }

    @Test
    void tamperedPayloadAndDigestFailClosed() {
        final CrossLevelTransferSnapshotEnvelope envelope = envelope("snapshot");

        final CompoundTag payloadTampered = CrossLevelTransferSnapshotEnvelopeCodec.encode(envelope);
        final byte[] payload = payloadTampered.getByteArray("payload");
        payload[0] ^= 1;
        payloadTampered.putByteArray("payload", payload);
        assertTrue(CrossLevelTransferSnapshotEnvelopeCodec.decode(
                payloadTampered,
                payload.length
        ).isEmpty());

        final CompoundTag digestTampered = CrossLevelTransferSnapshotEnvelopeCodec.encode(envelope);
        final byte[] digest = digestTampered.getByteArray("sha256");
        digest[0] ^= 1;
        digestTampered.putByteArray("sha256", digest);
        assertTrue(CrossLevelTransferSnapshotEnvelopeCodec.decode(
                digestTampered,
                envelope.payload().length
        ).isEmpty());
    }

    @Test
    void unsupportedVersionAndMissingFieldsFailClosed() {
        final CrossLevelTransferSnapshotEnvelope envelope = envelope("snapshot");
        final CompoundTag unsupported = CrossLevelTransferSnapshotEnvelopeCodec.encode(envelope);
        unsupported.putInt("codec_version", Integer.MAX_VALUE);
        assertTrue(CrossLevelTransferSnapshotEnvelopeCodec.decode(
                unsupported,
                envelope.payload().length
        ).isEmpty());

        for (final String key : new String[]{
                "codec_version",
                "transaction_id",
                "sub_level_id",
                "snapshot_format_version",
                "payload",
                "sha256"
        }) {
            final CompoundTag missing = CrossLevelTransferSnapshotEnvelopeCodec.encode(envelope);
            missing.remove(key);
            assertTrue(CrossLevelTransferSnapshotEnvelopeCodec.decode(
                    missing,
                    envelope.payload().length
            ).isEmpty(), key);
        }
    }

    @Test
    void invalidDigestLengthAndSnapshotFormatFailClosed() {
        final CrossLevelTransferSnapshotEnvelope envelope = envelope("snapshot");
        final CompoundTag invalidDigest = CrossLevelTransferSnapshotEnvelopeCodec.encode(envelope);
        invalidDigest.putByteArray("sha256", new byte[1]);
        assertTrue(CrossLevelTransferSnapshotEnvelopeCodec.decode(
                invalidDigest,
                envelope.payload().length
        ).isEmpty());

        final CompoundTag invalidFormat = CrossLevelTransferSnapshotEnvelopeCodec.encode(envelope);
        invalidFormat.putInt("snapshot_format_version", 0);
        assertTrue(CrossLevelTransferSnapshotEnvelopeCodec.decode(
                invalidFormat,
                envelope.payload().length
        ).isEmpty());
    }

    @Test
    void nullAndLimitContractsAreRejected() {
        final CrossLevelTransferSnapshotEnvelope envelope = envelope("snapshot");

        assertThrows(
                NullPointerException.class,
                () -> CrossLevelTransferSnapshotEnvelopeCodec.encode(null)
        );
        assertThrows(
                NullPointerException.class,
                () -> CrossLevelTransferSnapshotEnvelopeCodec.decode(null, 1)
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> CrossLevelTransferSnapshotEnvelopeCodec.decode(
                        CrossLevelTransferSnapshotEnvelopeCodec.encode(envelope),
                        0
                )
        );
    }

    private static CrossLevelTransferSnapshotEnvelope envelope(final String payload) {
        return CrossLevelTransferSnapshotEnvelope.create(
                UUID.randomUUID(),
                UUID.randomUUID(),
                1,
                payload.getBytes(StandardCharsets.UTF_8)
        );
    }
}
