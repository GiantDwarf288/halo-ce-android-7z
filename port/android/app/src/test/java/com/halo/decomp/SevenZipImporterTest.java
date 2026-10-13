package com.halo.decomp;

import org.apache.commons.compress.archivers.sevenz.SevenZArchiveEntry;
import org.apache.commons.compress.archivers.sevenz.SevenZOutputFile;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import static org.junit.Assert.*;

public class SevenZipImporterTest {
    @Rule public TemporaryFolder folder = new TemporaryFolder();

    private File archive(String[] names, String[] contents) throws IOException {
        File archive = folder.newFile("game.7z");
        try (SevenZOutputFile output = new SevenZOutputFile(archive)) {
            for (int i = 0; i < names.length; i++) {
                SevenZArchiveEntry entry = new SevenZArchiveEntry();
                entry.setName(names[i]);
                entry.setDirectory(false);
                output.putArchiveEntry(entry);
                output.write(contents[i].getBytes(StandardCharsets.UTF_8));
                output.closeArchiveEntry();
            }
        }
        return archive;
    }

    private File existingMaps() throws IOException {
        File maps = new File(folder.getRoot(), "maps");
        assertTrue(maps.mkdir());
        Files.write(new File(maps, "old.map").toPath(), "old".getBytes(StandardCharsets.UTF_8));
        return maps;
    }

    private void importFile(File file) throws IOException {
        try (FileInputStream input = new FileInputStream(file)) {
            assertTrue(SevenZipImporter.matches(input.getChannel()));
            SevenZipImporter.importArchive(input.getChannel(), folder.getRoot(),
                (description, done, total) -> {});
        }
    }

    private void assertNoStaging() {
        for (File file : folder.getRoot().listFiles()) {
            assertFalse("left an import directory behind: " + file,
                file.getName().startsWith("7z-import-"));
        }
    }

    private void assertRejects(File file) throws IOException {
        try {
            importFile(file);
            fail("Unexpectedly accepted an invalid archive");
        } catch (IOException expected) {
            assertTrue(expected.getMessage() != null && !expected.getMessage().isEmpty());
        }
        assertNoStaging();
    }

    @Test
    public void copiesNestedMapsWithCaseInsensitiveNamesAndReplacesOldMaps() throws IOException {
        existingMaps();
        File file = archive(
            new String[] {"Halo CE/MAPS/UI.MAP", "Halo CE/MAPS/BeaverCreek.MAP"},
            new String[] {"ui-content", "level-content"});
        importFile(file);
        assertEquals("ui-content", Files.readString(new File(folder.getRoot(), "maps/ui.map").toPath()));
        assertEquals("level-content",
            Files.readString(new File(folder.getRoot(), "maps/beavercreek.map").toPath()));
        assertFalse(new File(folder.getRoot(), "maps/old.map").exists());
        assertNoStaging();
    }

    @Test
    public void rejectsTraversalWithoutTouchingExistingMaps() throws IOException {
        File maps = existingMaps();
        File file = archive(
            new String[] {"maps/ui.map", "maps/../../other.map"},
            new String[] {"ok", "evil"});
        assertRejects(file);
        assertTrue(new File(maps, "old.map").isFile());
        assertFalse(new File(folder.getRoot(), "other.map").exists());
    }

    @Test
    public void rejectsDuplicateMapNamesAfterCaseNormalization() throws IOException {
        File maps = existingMaps();
        File file = archive(
            new String[] {"maps/UI.MAP", "maps/ui.map"},
            new String[] {"first", "second"});
        assertRejects(file);
        assertTrue(new File(maps, "old.map").isFile());
    }

    @Test
    public void rejectsEmptyUiMapWithoutReplacingOldMaps() throws IOException {
        File maps = existingMaps();
        File file = archive(new String[] {"maps/ui.map", "maps/a.map"},
            new String[] {"", "content"});
        assertRejects(file);
        assertTrue(new File(maps, "old.map").isFile());
    }

    @Test
    public void rejectsAnInvalidCompressedDiscWithoutDestroyingOldMaps() throws IOException {
        File maps = existingMaps();
        File file = archive(new String[] {"disc/Halo.iso"}, new String[] {"not an Xbox disc"});
        assertRejects(file);
        assertTrue(new File(maps, "old.map").isFile());
    }

    @Test
    public void rejectsAmbiguousDiscImages() throws IOException {
        File file = archive(new String[] {"a.iso", "b.xiso"}, new String[] {"A", "B"});
        assertRejects(file);
    }
}
