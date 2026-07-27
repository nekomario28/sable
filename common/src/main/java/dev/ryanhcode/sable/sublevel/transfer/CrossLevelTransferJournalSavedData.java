package dev.ryanhcode.sable.sublevel.transfer;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.saveddata.SavedData;
import org.jetbrains.annotations.NotNull;

import java.util.Objects;
import java.util.Optional;

/**
 * World-independent SavedData representation of the cross-dimension transfer journal.
 *
 * <p>This type deliberately has no DataStorage lookup method yet. A later phase may
 * bind it to one authoritative server-level storage location after persistence
 * behavior is proven independently.</p>
 *
 * <p>Malformed durable data is preserved verbatim and makes the instance read-only.
 * It is never converted to an empty journal during an ordinary save.</p>
 */
public final class CrossLevelTransferJournalSavedData extends SavedData {
    public static final String FILE_ID = "sable_cross_level_transfer_journal";

    private final CrossLevelTransferJournalLoadStatus loadStatus;
    private CrossLevelTransferJournalSnapshot snapshot;
    private final CompoundTag preservedCorruptTag;

    private CrossLevelTransferJournalSavedData(
            final CrossLevelTransferJournalSnapshot snapshot
    ) {
        this.loadStatus = CrossLevelTransferJournalLoadStatus.READABLE;
        this.snapshot = Objects.requireNonNull(snapshot, "snapshot");
        this.preservedCorruptTag = null;
    }

    private CrossLevelTransferJournalSavedData(final CompoundTag corruptTag) {
        this.loadStatus = CrossLevelTransferJournalLoadStatus.CORRUPT_PRESERVED;
        this.snapshot = null;
        this.preservedCorruptTag = Objects.requireNonNull(corruptTag, "corruptTag").copy();
    }

    /**
     * Creates a new readable empty journal without attaching it to world storage.
     */
    public static CrossLevelTransferJournalSavedData createEmpty() {
        return new CrossLevelTransferJournalSavedData(CrossLevelTransferJournalSnapshot.empty());
    }

    /**
     * Loads a journal without silently discarding malformed durable ownership.
     *
     * @param tag persisted journal NBT
     * @return readable data, or a read-only instance preserving the corrupt tag
     */
    public static CrossLevelTransferJournalSavedData load(final CompoundTag tag) {
        Objects.requireNonNull(tag, "tag");
        return CrossLevelTransferJournalSnapshotCodec.decode(tag)
                .map(CrossLevelTransferJournalSavedData::new)
                .orElseGet(() -> new CrossLevelTransferJournalSavedData(tag));
    }

    public CrossLevelTransferJournalLoadStatus loadStatus() {
        return this.loadStatus;
    }

    /**
     * Returns the complete readable journal snapshot. Corrupt preserved data has no
     * interpreted snapshot and therefore returns an empty Optional.
     */
    public Optional<CrossLevelTransferJournalSnapshot> snapshot() {
        return Optional.ofNullable(this.snapshot);
    }

    /**
     * Replaces the complete journal atomically in memory.
     *
     * @param snapshot new conflict-free snapshot
     * @throws IllegalStateException when durable input was corrupt and preserved
     */
    public void replaceSnapshot(final CrossLevelTransferJournalSnapshot snapshot) {
        Objects.requireNonNull(snapshot, "snapshot");
        if (!this.loadStatus.canMutate()) {
            throw new IllegalStateException("Cannot replace a corrupt preserved transfer journal");
        }
        if (!this.snapshot.equals(snapshot)) {
            this.snapshot = snapshot;
            this.setDirty();
        }
    }

    @Override
    public @NotNull CompoundTag save(
            final CompoundTag compoundTag,
            final HolderLookup.Provider provider
    ) {
        Objects.requireNonNull(compoundTag, "compoundTag");
        Objects.requireNonNull(provider, "provider");

        if (this.loadStatus == CrossLevelTransferJournalLoadStatus.CORRUPT_PRESERVED) {
            compoundTag.merge(this.preservedCorruptTag.copy());
            return compoundTag;
        }

        compoundTag.merge(CrossLevelTransferJournalSnapshotCodec.encode(this.snapshot));
        return compoundTag;
    }
}
