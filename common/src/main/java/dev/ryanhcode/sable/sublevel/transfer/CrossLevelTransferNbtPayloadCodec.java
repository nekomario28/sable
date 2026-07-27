package dev.ryanhcode.sable.sublevel.transfer;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.Tag;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.Objects;
import java.util.Optional;

/**
 * Strict binary codec for immutable transfer snapshot NBT payloads.
 */
public final class CrossLevelTransferNbtPayloadCodec {
    private CrossLevelTransferNbtPayloadCodec() {
    }

    /**
     * Encodes one compound tag to canonical uncompressed binary NBT.
     *
     * @param tag snapshot root tag
     * @return encoded payload bytes
     */
    public static byte[] encode(final CompoundTag tag) {
        Objects.requireNonNull(tag, "tag");

        try {
            final ByteArrayOutputStream byteStream = new ByteArrayOutputStream();
            try (DataOutputStream output = new DataOutputStream(byteStream)) {
                NbtIo.writeAnyTag(tag, output);
            }
            final byte[] payload = byteStream.toByteArray();
            if (payload.length == 0) {
                throw new IllegalStateException("Encoded NBT payload is empty");
            }
            return payload;
        } catch (final IOException exception) {
            throw new IllegalStateException("Failed to encode transfer snapshot NBT", exception);
        }
    }

    /**
     * Decodes one bounded binary NBT payload fail-closed.
     *
     * <p>The payload must contain exactly one compound root tag. Malformed data,
     * non-compound roots, trailing bytes, empty input, and input larger than the
     * caller-supplied limit are rejected.</p>
     *
     * @param payload encoded binary NBT
     * @param maximumPayloadBytes maximum accepted byte length
     * @return decoded compound, or empty when invalid
     */
    public static Optional<CompoundTag> decode(
            final byte[] payload,
            final int maximumPayloadBytes
    ) {
        Objects.requireNonNull(payload, "payload");
        if (maximumPayloadBytes < 1) {
            throw new IllegalArgumentException("maximumPayloadBytes must be positive");
        }
        if (payload.length == 0 || payload.length > maximumPayloadBytes) {
            return Optional.empty();
        }

        try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(payload))) {
            final Tag decoded = NbtIo.readAnyTag(input, NbtAccounter.unlimitedHeap());
            if (!(decoded instanceof final CompoundTag compoundTag)) {
                return Optional.empty();
            }
            if (input.available() != 0) {
                return Optional.empty();
            }
            return Optional.of(compoundTag);
        } catch (final IOException | RuntimeException exception) {
            return Optional.empty();
        }
    }
}
