package dev.ryanhcode.sable.sublevel.transfer;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.*;

class CrossLevelTransferNbtPayloadCodecTest {
    @Test
    void nestedCompoundRoundTrips() {
        final CompoundTag original = snapshotTag();

        final byte[] payload = CrossLevelTransferNbtPayloadCodec.encode(original);
        final CompoundTag decoded = CrossLevelTransferNbtPayloadCodec.decode(
                payload,
                payload.length
        ).orElseThrow();

        assertEquals(original, decoded);
        assertNotSame(original, decoded);
    }

    @Test
    void encodedPayloadIsIndependentFromLaterTagMutation() {
        final CompoundTag original = snapshotTag();
        final byte[] payload = CrossLevelTransferNbtPayloadCodec.encode(original);

        original.putString("name", "mutated");

        final CompoundTag decoded = CrossLevelTransferNbtPayloadCodec.decode(
                payload,
                payload.length
        ).orElseThrow();
        assertEquals("ship", decoded.getString("name"));
    }

    @Test
    void emptyMalformedAndOversizedPayloadsFailClosed() {
        assertTrue(CrossLevelTransferNbtPayloadCodec.decode(new byte[0], 1).isEmpty());
        assertTrue(CrossLevelTransferNbtPayloadCodec.decode(new byte[]{1, 2, 3}, 3).isEmpty());

        final byte[] payload = CrossLevelTransferNbtPayloadCodec.encode(snapshotTag());
        assertTrue(CrossLevelTransferNbtPayloadCodec.decode(payload, payload.length - 1).isEmpty());
    }

    @Test
    void nonCompoundRootFailsClosed() throws IOException {
        final byte[] stringPayload = encodeAnyTag(StringTag.valueOf("not-a-compound"));

        assertTrue(CrossLevelTransferNbtPayloadCodec.decode(
                stringPayload,
                stringPayload.length
        ).isEmpty());
    }

    @Test
    void trailingBytesFailClosed() {
        final byte[] payload = CrossLevelTransferNbtPayloadCodec.encode(snapshotTag());
        final byte[] withTrailingBytes = Arrays.copyOf(payload, payload.length + 2);
        withTrailingBytes[payload.length] = 42;
        withTrailingBytes[payload.length + 1] = 43;

        assertTrue(CrossLevelTransferNbtPayloadCodec.decode(
                withTrailingBytes,
                withTrailingBytes.length
        ).isEmpty());
    }

    @Test
    void nullAndInvalidLimitContractsAreRejected() {
        assertThrows(NullPointerException.class, () -> CrossLevelTransferNbtPayloadCodec.encode(null));
        assertThrows(NullPointerException.class, () -> CrossLevelTransferNbtPayloadCodec.decode(null, 1));
        assertThrows(
                IllegalArgumentException.class,
                () -> CrossLevelTransferNbtPayloadCodec.decode(new byte[]{1}, 0)
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> CrossLevelTransferNbtPayloadCodec.decode(new byte[]{1}, -1)
        );
    }

    private static CompoundTag snapshotTag() {
        final CompoundTag root = new CompoundTag();
        root.putString("name", "ship");
        root.putUUID("uuid", java.util.UUID.randomUUID());
        root.putIntArray("coordinates", new int[]{1, 2, 3});
        root.putByteArray("payload", new byte[]{4, 5, 6});

        final CompoundTag nested = new CompoundTag();
        nested.putLong("tick", 42L);
        nested.putBoolean("active", true);
        root.put("nested", nested);

        final ListTag list = new ListTag();
        list.add(StringTag.valueOf("alpha"));
        list.add(StringTag.valueOf("beta"));
        root.put("list", list);
        return root;
    }

    private static byte[] encodeAnyTag(final Tag tag) throws IOException {
        final ByteArrayOutputStream byteStream = new ByteArrayOutputStream();
        try (DataOutputStream output = new DataOutputStream(byteStream)) {
            NbtIo.writeAnyTag(tag, output);
        }
        return byteStream.toByteArray();
    }
}
