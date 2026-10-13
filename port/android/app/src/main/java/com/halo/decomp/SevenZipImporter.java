package com.halo.decomp;

import org.apache.commons.compress.archivers.sevenz.SevenZArchiveEntry;
import org.apache.commons.compress.archivers.sevenz.SevenZFile;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/**
 * Imports Halo game data from a 7z archive. An archive may contain a disc
 * image (.iso or .xiso) or an extracted maps folder (including nested paths
 * such as Halo/maps/ui.map). The archive is not modified.
 *
 * Only the selected game's data is unpacked, into a private staging directory.
 * Nothing is installed under dataRoot/maps until the import has succeeded.
 */
final class SevenZipImporter {
    private static final int BUFFER_SIZE = 256 * 1024;
    private static final int MAX_ENTRIES = 4096;
    private static final int MAX_7Z_MEMORY_KIB = 256 * 1024;
    private static final long MAX_IMAGE_BYTES = 16L * 1024 * 1024 * 1024;
    private static final long MAX_MAP_BYTES = 8L * 1024 * 1024 * 1024;

    interface Progress {
        void report(String phase, long done, long total);
    }

    private SevenZipImporter() {}

    static boolean matches(FileChannel channel) throws IOException {
        ByteBuffer header = ByteBuffer.allocate(6);
        long offset = 0;
        while (header.hasRemaining()) {
            int count = channel.read(header, offset);
            if (count <= 0) break;
            offset += count;
        }
        return SevenZFile.matches(header.array(), header.position());
    }

    /** A portable, relative path; never trust a name supplied by an archive. */
    private static String safePath(String raw) throws IOException {
        if (raw == null || raw.isEmpty() || raw.startsWith("/") || raw.startsWith("\\"))
            throw new IOException("The archive contains an unsafe path.");
        String[] parts = raw.replace('\\', '/').split("/");
        StringBuilder path = new StringBuilder();
        if (parts.length > 32)
            throw new IOException("The archive contains too many nested folders.");
        for (String part : parts) {
            if (part.isEmpty() || part.equals(".") || part.equals("..") || part.length() > 255)
                throw new IOException("The archive contains an unsafe path.");
            for (int i = 0; i < part.length(); i++) {
                char c = part.charAt(i);
                if (c < 32 || c == 127 || c == ':' || c == '|')
                    throw new IOException("The archive contains an unsafe filename.");
            }
            if (path.length() != 0) path.append('/');
            path.append(part.toLowerCase(Locale.ROOT));
        }
        if (path.length() > 1024)
            throw new IOException("The archive contains an excessively long path.");
        return path.toString();
    }

    private static String pathOf(SevenZArchiveEntry entry) throws IOException {
        String name = entry.getName();
        // Directory entries frequently end with a slash; we don't unpack them.
        if (entry.isDirectory() && name != null &&
            (name.endsWith("/") || name.endsWith("\\")))
            name = name.substring(0, name.length() - 1);
        return safePath(name);
    }

    private static boolean isImage(String path) {
        return path.endsWith(".iso") || path.endsWith(".xiso");
    }

    private static void requireSize(long size, long limit) throws IOException {
        if (size < 0 || size > limit)
            throw new IOException("The archive contains an unexpectedly large file.");
    }

    private static void copyEntry(SevenZFile archive, File output, long size,
        long limit, Progress progress, String label, long before, long total) throws IOException {
        requireSize(size, limit);
        File parent = output.getParentFile();
        if (!parent.isDirectory() && !parent.mkdirs())
            throw new IOException("Cannot create the import folder.");
        if (output.exists())
            throw new IOException("Duplicate path in the archive: " + label);
        byte[] buffer = new byte[BUFFER_SIZE];
        long written = 0;
        try (FileOutputStream stream = new FileOutputStream(output)) {
            while (written < size) {
                int count = archive.read(buffer, 0, (int) Math.min(buffer.length, size - written));
                if (count <= 0)
                    throw new IOException("The archive ended early while reading " + label + ".");
                stream.write(buffer, 0, count);
                written += count;
                progress.report(label, before + written, total);
            }
        }
    }

    private static void deleteStaging(File path) {
        File[] children = path.listFiles();
        if (children != null) {
            for (File child : children) deleteStaging(child);
        }
        path.delete();
    }

    /** Keep old data if the copy or the final installation fails. */
    private static void install(File stagedMaps, File dataRoot) throws IOException {
        File ui = new File(stagedMaps, "ui.map");
        if (!ui.isFile() || ui.length() == 0)
            throw new IOException("The archive's maps folder has no nonempty ui.map.");

        File destination = new File(dataRoot, "maps");
        File previous = new File(dataRoot, "maps-before-import-" + System.nanoTime());
        boolean backedUp = destination.exists();
        if (backedUp && !destination.renameTo(previous))
            throw new IOException("Cannot prepare app storage for the imported maps.");
        if (!stagedMaps.renameTo(destination)) {
            if (backedUp && !previous.renameTo(destination))
                throw new IOException("Could not install maps. Previous maps are preserved in " +
                    previous.getName() + ".");
            throw new IOException("Could not install the extracted maps.");
        }
        if (backedUp) deleteStaging(previous);
    }

    static void importArchive(FileChannel channel, File dataRoot, Progress progress) throws IOException {
        if (dataRoot == null)
            throw new IOException("App storage is unavailable.");

        File staging = new File(dataRoot, "7z-import-" + System.nanoTime());
        if (!staging.mkdir())
            throw new IOException("Could not create a temporary import folder.");

        try {
            String mapPrefix = null;
            String imagePath = null;
            long total = 0;
            long imageSize = 0;
            int entryCount = 0;

            try (SevenZFile archive = SevenZFile.builder()
                .setSeekableByteChannel(channel)
                .setMaxMemoryLimitKiB(MAX_7Z_MEMORY_KIB)
                .get()) {

                // Inspect metadata first, before writing anything. Prefer
                // extracted maps if the archive offers both formats.
                for (SevenZArchiveEntry entry : archive.getEntries()) {
                    if (++entryCount > MAX_ENTRIES)
                        throw new IOException("The archive contains too many files.");
                    String path = pathOf(entry);
                    if (entry.isDirectory()) continue;
                    if (path.equals("ui.map") || path.endsWith("/ui.map")) {
                        String prefix = path.substring(0, path.length() - "ui.map".length());
                        if (mapPrefix != null && !mapPrefix.equals(prefix))
                            throw new IOException("The archive contains more than one maps folder.");
                        mapPrefix = prefix;
                    }
                    if (isImage(path)) {
                        if (imagePath != null)
                            imagePath = ""; // ambiguous only if no extracted maps are found
                        else
                            imagePath = path;
                    }
                }

                if (mapPrefix == null && (imagePath == null || imagePath.isEmpty()))
                    throw new IOException("Choose a .7z with a single .iso/.xiso or a maps folder containing ui.map.");

                // Count only the files we will actually copy, and reject
                // duplicates after lowercasing (as the game's maps names are).
                Set<String> names = new HashSet<>();
                for (SevenZArchiveEntry entry : archive.getEntries()) {
                    if (entry.isDirectory()) continue;
                    String path = pathOf(entry);
                    if (mapPrefix != null && path.startsWith(mapPrefix)) {
                        String relative = path.substring(mapPrefix.length());
                        if (relative.isEmpty() || !names.add(relative))
                            throw new IOException("Duplicate filename in maps: " + relative);
                        long size = entry.getSize();
                        requireSize(size, MAX_MAP_BYTES - total);
                        total += size;
                    } else if (mapPrefix == null && path.equals(imagePath)) {
                        imageSize = entry.getSize();
                        requireSize(imageSize, MAX_IMAGE_BYTES);
                        total = imageSize;
                    }
                }

                if (total == 0)
                    throw new IOException("There is no game data in the archive.");
                // On phones the source may be compressed on another volume;
                // check local space before beginning a large extraction.
                long available = dataRoot.getUsableSpace();
                if (available > 0 && total > available)
                    throw new IOException("Not enough free storage to unpack this archive.");

                File stagedMaps = new File(staging, "maps");
                File image = new File(staging, "disc.iso");
                if (mapPrefix != null && !stagedMaps.mkdir())
                    throw new IOException("Cannot prepare the maps folder.");

                long written = 0;
                SevenZArchiveEntry entry;
                while ((entry = archive.getNextEntry()) != null) {
                    if (entry.isDirectory()) continue;
                    String path = pathOf(entry);
                    if (mapPrefix != null && path.startsWith(mapPrefix)) {
                        String relative = path.substring(mapPrefix.length());
                        // safePath already verified all path components.
                        copyEntry(archive, new File(stagedMaps, relative), entry.getSize(),
                            MAX_MAP_BYTES - written, progress, "Extracting maps/" + relative,
                            written, total);
                        written += entry.getSize();
                    } else if (mapPrefix == null && path.equals(imagePath)) {
                        copyEntry(archive, image, entry.getSize(), MAX_IMAGE_BYTES,
                            progress, "Unpacking " + path, 0, total);
                    }
                }

                if (mapPrefix == null) {
                    if (!image.isFile() || image.length() != imageSize)
                        throw new IOException("The disc image was not extracted completely.");
                    try (FileInputStream input = new FileInputStream(image)) {
                        XisoExtractor.extractMaps(input.getChannel(), staging,
                            (file, done, size) ->
                                progress.report("Extracting maps/" + file, done, size));
                    }
                    if (!image.delete())
                        throw new IOException("Could not clean up the temporary disc image.");
                }

                install(stagedMaps, dataRoot);
            }
        } finally {
            deleteStaging(staging);
        }
    }
}
