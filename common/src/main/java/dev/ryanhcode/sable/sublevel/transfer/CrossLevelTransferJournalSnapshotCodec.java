package dev.ryanhcode.sable.sublevel.transfer;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Versioned NBT codec for a complete transfer journal snapshot.
 */
public final class CrossLevelTransferJournalSnapshotCodec {
    private static final int CODEC_VERSION = 1;
    private static final String CODEC_VERSION_KEY = "codec_version";
    private static final String TRANSACTIONS_KEY = "transactions";

    private CrossLevelTransferJournalSnapshotCodec() {
    }

    public static CompoundTag encode(final CrossLevelTransferJournalSnapshot snapshot) {
        Objects.requireNonNull(snapshot, "snapshot");

        final ListTag transactions = new ListTag();
        for (final CrossLevelTransferTransactionState state : snapshot.states()) {
            transactions.add(CrossLevelTransferTransactionStateCodec.encode(state));
        }

        final CompoundTag tag = new CompoundTag();
        tag.putInt(CODEC_VERSION_KEY, CODEC_VERSION);
        tag.put(TRANSACTIONS_KEY, transactions);
        return tag;
    }

    /**
     * Decodes the entire journal fail-closed. One malformed or conflicting entry
     * rejects the complete snapshot so ownership cannot be silently discarded.
     *
     * @param tag journal NBT
     * @return decoded snapshot, or empty when unsupported, malformed, or conflicting
     */
    public static Optional<CrossLevelTransferJournalSnapshot> decode(final CompoundTag tag) {
        Objects.requireNonNull(tag, "tag");

        if (!tag.contains(CODEC_VERSION_KEY, Tag.TAG_INT) ||
                !tag.contains(TRANSACTIONS_KEY, Tag.TAG_LIST) ||
                tag.getInt(CODEC_VERSION_KEY) != CODEC_VERSION) {
            return Optional.empty();
        }

        final ListTag transactionTags = tag.getList(TRANSACTIONS_KEY, Tag.TAG_COMPOUND);
        final List<CrossLevelTransferTransactionState> states = new ArrayList<>(transactionTags.size());
        for (final Tag transactionTag : transactionTags) {
            if (!(transactionTag instanceof final CompoundTag compoundTag)) {
                return Optional.empty();
            }

            final Optional<CrossLevelTransferTransactionState> state =
                    CrossLevelTransferTransactionStateCodec.decode(compoundTag);
            if (state.isEmpty()) {
                return Optional.empty();
            }
            states.add(state.orElseThrow());
        }

        try {
            return Optional.of(CrossLevelTransferJournalSnapshot.of(states));
        } catch (final IllegalArgumentException exception) {
            return Optional.empty();
        }
    }
}
