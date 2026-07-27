package dev.ryanhcode.sable.sublevel.transfer;

import net.minecraft.nbt.CompoundTag;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class CrossLevelTransferTransactionStateCodecTest {
    @Test
    void stateRoundTripsWithoutLoss() {
        final CrossLevelTransferTransactionState expected = new CrossLevelTransferTransactionState(
                UUID.randomUUID(),
                UUID.randomUUID(),
                "minecraft:overworld",
                "starlance:space",
                -12,
                27,
                CrossLevelTransferPhase.TARGET_LOADED,
                CrossLevelTransferTransactionState.CURRENT_SNAPSHOT_FORMAT_VERSION
        );

        final CompoundTag encoded = CrossLevelTransferTransactionStateCodec.encode(expected);
        final Optional<CrossLevelTransferTransactionState> decoded =
                CrossLevelTransferTransactionStateCodec.decode(encoded);

        assertTrue(decoded.isPresent());
        assertEquals(expected, decoded.orElseThrow());
    }

    @Test
    void missingAndMalformedFieldsAreRejected() {
        assertTrue(CrossLevelTransferTransactionStateCodec.decode(new CompoundTag()).isEmpty());

        final CrossLevelTransferTransactionState state = validState();
        final CompoundTag missingPhase = CrossLevelTransferTransactionStateCodec.encode(state);
        missingPhase.remove("phase");
        assertTrue(CrossLevelTransferTransactionStateCodec.decode(missingPhase).isEmpty());

        final CompoundTag unknownPhase = CrossLevelTransferTransactionStateCodec.encode(state);
        unknownPhase.putString("phase", "NOT_A_PHASE");
        assertTrue(CrossLevelTransferTransactionStateCodec.decode(unknownPhase).isEmpty());

        final CompoundTag malformedUuid = CrossLevelTransferTransactionStateCodec.encode(state);
        malformedUuid.putIntArray("transaction_id", new int[]{1});
        assertTrue(CrossLevelTransferTransactionStateCodec.decode(malformedUuid).isEmpty());
    }

    @Test
    void unsupportedVersionsAreRejected() {
        final CrossLevelTransferTransactionState state = validState();

        final CompoundTag futureCodec = CrossLevelTransferTransactionStateCodec.encode(state);
        futureCodec.putInt("codec_version", 2);
        assertTrue(CrossLevelTransferTransactionStateCodec.decode(futureCodec).isEmpty());

        final CompoundTag futureSnapshot = CrossLevelTransferTransactionStateCodec.encode(state);
        futureSnapshot.putInt("snapshot_format_version", 2);
        assertTrue(CrossLevelTransferTransactionStateCodec.decode(futureSnapshot).isEmpty());
    }

    @Test
    void invalidDecodedStateIsRejectedWithoutThrowing() {
        final CompoundTag sameDimension = CrossLevelTransferTransactionStateCodec.encode(validState());
        sameDimension.putString("target_dimension", "minecraft:overworld");
        assertTrue(CrossLevelTransferTransactionStateCodec.decode(sameDimension).isEmpty());
    }

    @Test
    void nullInputsAreRejected() {
        assertThrows(NullPointerException.class, () -> CrossLevelTransferTransactionStateCodec.encode(null));
        assertThrows(NullPointerException.class, () -> CrossLevelTransferTransactionStateCodec.decode(null));
    }

    private static CrossLevelTransferTransactionState validState() {
        return new CrossLevelTransferTransactionState(
                UUID.randomUUID(),
                UUID.randomUUID(),
                "minecraft:overworld",
                "starlance:space",
                2,
                3,
                CrossLevelTransferPhase.SNAPSHOT_WRITTEN,
                CrossLevelTransferTransactionState.CURRENT_SNAPSHOT_FORMAT_VERSION
        );
    }
}
