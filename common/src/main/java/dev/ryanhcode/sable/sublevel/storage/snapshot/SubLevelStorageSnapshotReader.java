package dev.ryanhcode.sable.sublevel.storage.snapshot;

import dev.ryanhcode.sable.sublevel.storage.holding.GlobalSavedSubLevelPointer;
import dev.ryanhcode.sable.sublevel.storage.holding.SavedSubLevelPointer;
import dev.ryanhcode.sable.sublevel.storage.holding.SubLevelHoldingChunk;
import dev.ryanhcode.sable.sublevel.storage.region.SubLevelRegionFile;
import dev.ryanhcode.sable.sublevel.storage.region.SubLevelStorageFile;
import dev.ryanhcode.sable.sublevel.storage.serialization.SubLevelData;
import dev.ryanhcode.sable.sublevel.storage.serialization.SubLevelSerializer;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.world.level.ChunkPos;
import org.jetbrains.annotations.ApiStatus;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.BasicFileAttributes;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads a consistent, mutation-free view of every sub-level referenced by Sable holding-region files.
 *
 * <p>This reader deliberately opens storage files with {@link StandardOpenOption#READ} only. It does not
 * use the live storage cache, create files, flush files, load holding chunks into memory, or mutate any
 * sub-level state. Any malformed file, missing pointer target, or file change observed during the scan
 * fails the whole operation.</p>
 */
@ApiStatus.Internal
public final class SubLevelStorageSnapshotReader {
    private static final int HEADER_BYTES = 4096;
    private static final int HEADER_INDEX_CAPACITY = HEADER_BYTES / Integer.BYTES;
    private static final long NBT_HEAP_QUOTA_BYTES = 256L * 1024L * 1024L;
    private static final Pattern REGION_FILE_PATTERN = Pattern.compile("r\\.(-?\\d+)\\.(-?\\d+)\\Q" + SubLevelRegionFile.FILE_EXTENSION + "\\E");

    private SubLevelStorageSnapshotReader() {
    }

    public record StoredSubLevel(UUID uuid, GlobalSavedSubLevelPointer pointer) {
        public StoredSubLevel {
            Objects.requireNonNull(uuid, "uuid");
            Objects.requireNonNull(pointer, "pointer");
        }
    }

    /**
     * Captures all stored sub-level UUIDs and their exact pointers.
     *
     * @throws IOException when the directory cannot be read completely, data is malformed, a referenced
     *                     sub-level is missing, or any scanned file changes while it is being read
     */
    public static List<StoredSubLevel> capture(final Path folder) throws IOException {
        Objects.requireNonNull(folder, "folder");
        if (!Files.exists(folder)) {
            return List.of();
        }
        if (!Files.isDirectory(folder)) {
            throw new IOException("Sable sub-level storage path is not a directory: " + folder);
        }

        final Set<Path> regionFilesBefore = listRegionFiles(folder);
        final List<StoredSubLevel> result = new ArrayList<>();

        for (final Path regionPath : regionFilesBefore) {
            captureRegion(folder, regionPath, result);
        }

        final Set<Path> regionFilesAfter = listRegionFiles(folder);
        if (!regionFilesBefore.equals(regionFilesAfter)) {
            throw new IOException("Sable holding-region file set changed during snapshot capture");
        }

        result.sort(Comparator
                .comparing((StoredSubLevel entry) -> entry.uuid().toString())
                .thenComparingInt(entry -> entry.pointer().chunkPos().x)
                .thenComparingInt(entry -> entry.pointer().chunkPos().z)
                .thenComparingInt(entry -> entry.pointer().storageIndex())
                .thenComparingInt(entry -> entry.pointer().subLevelIndex()));
        return List.copyOf(result);
    }

    private static Set<Path> listRegionFiles(final Path folder) throws IOException {
        final Set<Path> paths = new LinkedHashSet<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(folder, "*" + SubLevelRegionFile.FILE_EXTENSION)) {
            for (final Path path : stream) {
                if (!Files.isRegularFile(path)) {
                    throw new IOException("Sable holding-region entry is not a regular file: " + path);
                }
                final Path normalized = path.toAbsolutePath().normalize();
                final Matcher matcher = REGION_FILE_PATTERN.matcher(path.getFileName().toString());
                if (!matcher.matches()) {
                    throw new IOException("Malformed Sable holding-region file name: " + path.getFileName());
                }
                paths.add(normalized);
            }
        }
        return paths;
    }

    private static void captureRegion(
            final Path folder,
            final Path regionPath,
            final List<StoredSubLevel> result
    ) throws IOException {
        final Matcher matcher = REGION_FILE_PATTERN.matcher(regionPath.getFileName().toString());
        if (!matcher.matches()) {
            throw new IOException("Malformed Sable holding-region file name: " + regionPath.getFileName());
        }

        final int regionX;
        final int regionZ;
        try {
            regionX = Integer.parseInt(matcher.group(1));
            regionZ = Integer.parseInt(matcher.group(2));
        } catch (final NumberFormatException exception) {
            throw new IOException("Invalid Sable holding-region coordinate in " + regionPath.getFileName(), exception);
        }

        final FileSnapshot regionSnapshot = FileSnapshot.capture(regionPath);
        final int[] spans = readHeader(regionPath);

        for (int index = 0; index < spans.length; index++) {
            if (spans[index] == 0) {
                continue;
            }

            final int localX = index & (SubLevelRegionFile.SIDE_LENGTH - 1);
            final int localZ = index >>> SubLevelRegionFile.LOG_SIDE_LENGTH;
            if (localZ >= SubLevelRegionFile.SIDE_LENGTH) {
                throw new IOException("Invalid holding-chunk index " + index + " in " + regionPath);
            }

            final ChunkPos chunkPos = new ChunkPos(
                    regionX * SubLevelRegionFile.SIDE_LENGTH + localX,
                    regionZ * SubLevelRegionFile.SIDE_LENGTH + localZ
            );
            final Path externalRegionFolder = folder.resolve(regionBaseName(regionX, regionZ) + ".r");
            final CompoundTag holdingTag = readTag(
                    regionPath,
                    externalRegionFolder,
                    index,
                    SubLevelRegionFile.SECTOR_SIZE
            );
            if (holdingTag == null) {
                throw new IOException("Occupied holding-region index " + index + " has no data in " + regionPath);
            }

            final SubLevelHoldingChunk holdingChunk;
            try {
                holdingChunk = SubLevelHoldingChunk.from(chunkPos, holdingTag);
            } catch (final RuntimeException exception) {
                throw new IOException("Malformed holding-chunk data at " + chunkPos + " in " + regionPath, exception);
            }

            for (final SavedSubLevelPointer localPointer : holdingChunk.getSubLevelPointers()) {
                final GlobalSavedSubLevelPointer pointer = new GlobalSavedSubLevelPointer(
                        chunkPos,
                        localPointer.storageIndex(),
                        localPointer.subLevelIndex()
                );
                result.add(new StoredSubLevel(readSubLevelUuid(folder, pointer), pointer));
            }
        }

        regionSnapshot.verifyUnchanged(regionPath);
    }

    private static UUID readSubLevelUuid(
            final Path folder,
            final GlobalSavedSubLevelPointer pointer
    ) throws IOException {
        final ChunkPos chunkPos = pointer.chunkPos();
        final String baseName = regionBaseName(chunkPos.getRegionX(), chunkPos.getRegionZ());
        final Path storagePath = folder.resolve(baseName + "." + pointer.storageIndex() + SubLevelStorageFile.FILE_EXTENSION);
        final Path externalFolder = folder.resolve(baseName + ".r");
        final CompoundTag subLevelTag = readTag(
                storagePath,
                externalFolder,
                pointer.subLevelIndex(),
                4096
        );
        if (subLevelTag == null) {
            throw new IOException("Stored Sable pointer has no sub-level data: " + pointer);
        }

        final SubLevelData data;
        try {
            data = SubLevelSerializer.fromData(subLevelTag);
        } catch (final RuntimeException exception) {
            throw new IOException("Malformed Sable sub-level data at " + pointer, exception);
        }
        if (data == null || data.uuid() == null) {
            throw new IOException("Stored Sable pointer has no valid UUID: " + pointer);
        }
        return data.uuid();
    }

    private static int[] readHeader(final Path path) throws IOException {
        final FileSnapshot snapshot = FileSnapshot.capture(path);
        final ByteBuffer header = ByteBuffer.allocate(HEADER_BYTES);
        try (FileChannel channel = FileChannel.open(path, StandardOpenOption.READ)) {
            readFully(channel, header, 0L);
        }
        snapshot.verifyUnchanged(path);
        header.flip();

        final int[] spans = new int[HEADER_INDEX_CAPACITY];
        for (int index = 0; index < spans.length; index++) {
            spans[index] = header.getInt();
        }
        return spans;
    }

    private static CompoundTag readTag(
            final Path path,
            final Path externalFolder,
            final int index,
            final int sectorSize
    ) throws IOException {
        if (index < 0 || index >= HEADER_INDEX_CAPACITY) {
            throw new IOException("Sable storage index is outside the header capacity: " + index);
        }
        if (!Files.isRegularFile(path)) {
            throw new IOException("Missing Sable storage file: " + path);
        }

        final FileSnapshot snapshot = FileSnapshot.capture(path);
        final int[] spans = readHeader(path);
        final int span = spans[index];
        if (span == 0) {
            snapshot.verifyUnchanged(path);
            return null;
        }

        final int spanStart = span >>> 8 & 0xFFFFFF;
        final int spanLength = span & 0xFF;
        if (spanStart <= 0 || spanLength <= 0) {
            throw new IOException("Invalid Sable sector span at index " + index + " in " + path);
        }

        final long offset = (long) spanStart * sectorSize;
        final long byteLength = (long) spanLength * sectorSize;
        if (byteLength > Integer.MAX_VALUE || offset < HEADER_BYTES || offset + byteLength > snapshot.size()) {
            throw new IOException("Sable sector span is outside file bounds at index " + index + " in " + path);
        }

        final ByteBuffer sectorData = ByteBuffer.allocate((int) byteLength);
        try (FileChannel channel = FileChannel.open(path, StandardOpenOption.READ)) {
            readFully(channel, sectorData, offset);
        }
        snapshot.verifyUnchanged(path);
        sectorData.flip();

        if (sectorData.remaining() < Integer.BYTES + 1) {
            throw new IOException("Truncated Sable sector header at index " + index + " in " + path);
        }
        final int declaredBytes = sectorData.getInt();
        final byte dataType = sectorData.get();
        if (declaredBytes <= 0) {
            throw new IOException("Invalid Sable sector payload length at index " + index + " in " + path);
        }
        final int payloadBytes = declaredBytes - 1;

        if ((dataType & SubLevelStorageFile.EXTERNAL_MASK) != 0) {
            if (payloadBytes != 0) {
                throw new IOException("Sable sector contains both external and inline payloads at index " + index + " in " + path);
            }
            final Path externalPath = externalFolder.resolve(index + SubLevelStorageFile.SINGLE_FILE_EXTENSION);
            return readNbtFromStableFile(externalPath);
        }

        if (payloadBytes < 0 || payloadBytes > sectorData.remaining()) {
            throw new IOException("Truncated Sable inline payload at index " + index + " in " + path);
        }
        final byte[] payload = new byte[payloadBytes];
        sectorData.get(payload);
        try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(payload))) {
            return readNbt(input);
        }
    }

    private static CompoundTag readNbtFromStableFile(final Path path) throws IOException {
        if (!Files.isRegularFile(path)) {
            throw new IOException("Missing external Sable NBT file: " + path);
        }
        final FileSnapshot snapshot = FileSnapshot.capture(path);
        final CompoundTag tag;
        try (InputStream stream = Files.newInputStream(path, StandardOpenOption.READ);
             DataInputStream input = new DataInputStream(stream)) {
            tag = readNbt(input);
        }
        snapshot.verifyUnchanged(path);
        return tag;
    }

    private static CompoundTag readNbt(final DataInputStream input) throws IOException {
        if (SubLevelStorageFile.COMPRESS_DATA) {
            return NbtIo.readCompressed(input, NbtAccounter.create(NBT_HEAP_QUOTA_BYTES));
        }
        return NbtIo.read(input);
    }

    private static void readFully(
            final FileChannel channel,
            final ByteBuffer buffer,
            final long offset
    ) throws IOException {
        long position = offset;
        while (buffer.hasRemaining()) {
            final int read = channel.read(buffer, position);
            if (read < 0) {
                throw new IOException("Unexpected end of Sable storage file");
            }
            if (read == 0) {
                throw new IOException("Unable to make progress while reading Sable storage file");
            }
            position += read;
        }
    }

    private static String regionBaseName(final int regionX, final int regionZ) {
        return "r." + regionX + "." + regionZ;
    }

    private record FileSnapshot(long size, long modifiedMillis, Object fileKey) {
        private static FileSnapshot capture(final Path path) throws IOException {
            final BasicFileAttributes attributes = Files.readAttributes(path, BasicFileAttributes.class);
            if (!attributes.isRegularFile()) {
                throw new IOException("Sable storage path is not a regular file: " + path);
            }
            return new FileSnapshot(
                    attributes.size(),
                    attributes.lastModifiedTime().toMillis(),
                    attributes.fileKey()
            );
        }

        private void verifyUnchanged(final Path path) throws IOException {
            final FileSnapshot current = capture(path);
            if (this.size != current.size ||
                    this.modifiedMillis != current.modifiedMillis ||
                    !Objects.equals(this.fileKey, current.fileKey)) {
                throw new IOException("Sable storage file changed during snapshot capture: " + path);
            }
        }
    }
}
