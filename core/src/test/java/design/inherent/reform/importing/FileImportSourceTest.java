package design.inherent.reform.importing;

import static org.junit.jupiter.api.Assertions.*;

import design.inherent.reform.definition.DocumentId;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FileImportSourceTest {
  @TempDir Path root;
  private static final DocumentId ID = DocumentId.parse("example:chosen_file");

  @Test
  void constructionAndCaptureDoNotReadOrInitializeTheAdmittedFile() throws Exception {
    Path path = root.resolve("not yet present.json");
    var source = new FileImportSource(ID, path, 128);
    var reader = source.capture();
    assertFalse(Files.exists(path));
    assertThrows(IOException.class, reader::read);
    assertFalse(Files.exists(path));
    Files.writeString(path, "1.00e1");
    var input = reader.read();
    assertEquals("not yet present.json", input.label());
    assertArrayEquals("1.00e1".getBytes(StandardCharsets.UTF_8), input.bytes());
    Files.writeString(path, "20");
    assertArrayEquals("1.00e1".getBytes(StandardCharsets.UTF_8), input.bytes());
    assertEquals("20", Files.readString(path));
  }

  @Test
  void unicodeNamesAndBytesArePreservedWithNoWrite() throws Exception {
    Path path = root.resolve("Café profile.json");
    Files.writeString(path, "\"日本語\"");
    var modified = Files.getLastModifiedTime(path);
    var source = new FileImportSource(ID, path, 128);
    assertEquals(path.getFileName().toString(), source.label());
    assertArrayEquals(Files.readAllBytes(path), source.capture().read().bytes());
    assertEquals(modified, Files.getLastModifiedTime(path));
  }

  @Test
  void relativeDirectoryAndSymlinkSourcesAreNotAdmittedAsRegularFiles() throws Exception {
    assertThrows(
        IllegalArgumentException.class,
        () -> new FileImportSource(ID, Path.of("relative.json"), 128));
    assertThrows(
        IllegalArgumentException.class, () -> new FileImportSource(ID, root, Integer.MAX_VALUE));
    assertThrows(IOException.class, () -> new FileImportSource(ID, root, 128).capture().read());
    Path target = root.resolve("target.json"), link = root.resolve("link.json");
    Files.writeString(target, "1");
    Files.createSymbolicLink(link, target);
    assertThrows(IOException.class, () -> new FileImportSource(ID, link, 128).capture().read());
    assertEquals("1", Files.readString(target));
    assertTrue(Files.isSymbolicLink(link));
  }

  @Test
  void byteBudgetIsCheckedAgainAtEveryActualRead() throws Exception {
    Path path = root.resolve("bounded.json");
    Files.writeString(path, "1");
    var reader = new FileImportSource(ID, path, 4).capture();
    assertEquals(1, reader.read().size());
    Files.writeString(path, "12345");
    assertThrows(IOException.class, reader::read);
    assertEquals("12345", Files.readString(path));
  }
}
