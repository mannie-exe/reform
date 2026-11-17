package design.inherent.reform.neoforge.configuration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.CharacterCodingException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class NativeFileTest {
  @TempDir Path directory;

  @Test
  void exactBytesAndStrictUtf8RemainReadOnly() throws Exception {
    var path = directory.resolve("Actual owner [native].toml");
    Files.writeString(path, "# native comments\nvalue = 1\n");
    var before = Files.getLastModifiedTime(path);
    var first = NativeFile.read(path, 128);
    assertEquals("# native comments\nvalue = 1\n", first.text());
    assertTrue(first.same(NativeFile.read(path, 128)));
    assertEquals(before, Files.getLastModifiedTime(path));
    assertEquals("sha256:", first.revision.substring(0, 7));
    Files.writeString(path, "# changed comment\nvalue = 1\n");
    var changed = NativeFile.read(path, 128);
    assertFalse(first.same(changed));
    assertFalse(first.revision.equals(changed.revision));
    Files.write(path, new byte[] {(byte) 0xc3, 0x28});
    var invalid = NativeFile.read(path, 128);
    assertThrows(CharacterCodingException.class, invalid::text);
    assertEquals(2, Files.size(path));
  }

  @Test
  void refusesMissingDirectoryOversizeAndLinkWithoutCreatingAnything() throws Exception {
    assertThrows(IOException.class, () -> NativeFile.read(directory.resolve("absent"), 16));
    assertThrows(IOException.class, () -> NativeFile.read(directory, 16));
    var file = directory.resolve("large.toml");
    Files.writeString(file, "12345678901234567");
    assertThrows(IOException.class, () -> NativeFile.read(file, 16));
    var link = directory.resolve("link.toml");
    try {
      Files.createSymbolicLink(link, file);
    } catch (UnsupportedOperationException unsupported) {
      return;
    }
    assertThrows(IOException.class, () -> NativeFile.read(link, 128));
    assertEquals("12345678901234567", Files.readString(file));
  }
}
