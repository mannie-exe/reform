package design.inherent.reform.storage;

import static org.junit.jupiter.api.Assertions.*;

import design.inherent.reform.definition.DocumentId;
import java.io.IOException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class OwnedFileTest {
  @TempDir Path root;

  private OwnedFile file(String name) {
    return new OwnedFile(StorageBinding.relative(root, name), 1024);
  }

  private static long entries(Path path) throws IOException {
    try (var stream = Files.list(path)) {
      return stream.count();
    }
  }

  @Test
  void bindingIsPureAndRejectsAmbiguousPortablePaths() throws Exception {
    var binding = StorageBinding.newJson(root, DocumentId.parse("example:profiles/start"));
    assertEquals(root.resolve("example/profiles/start.json"), binding.path());
    assertFalse(Files.exists(root.resolve("example")));
    for (String name :
        List.of("", ".", "..", "a/b", "a\\b", "x.", "x ", "CON", "nul.json", "COM1.toml", "x:y"))
      assertThrows(IllegalArgumentException.class, () -> StorageBinding.relative(root, name), name);
    assertThrows(
        IllegalArgumentException.class,
        () -> StorageBinding.newJson(root, DocumentId.parse("example:../outside")));
    assertThrows(
        IllegalArgumentException.class,
        () -> StorageBinding.newJson(root, DocumentId.parse("example:a//b")));
    assertThrows(
        IllegalArgumentException.class,
        () -> StorageBinding.relative(Path.of("relative-root"), "prefs.json"));
    assertEquals(0, entries(root));
  }

  @Test
  void readDoesNotInitializeAndCreateUsesTheExpectedMissingSnapshot() throws Exception {
    var file = new OwnedFile(StorageBinding.relative(root, "example", "prefs.json"), 1024);
    var absent = file.read();
    assertFalse(absent.present());
    assertFalse(Files.exists(root.resolve("example")));
    var created = file.write(absent, "{\"value\":1}\n");
    assertEquals(OwnedFile.Persistence.SAVED, created.state());
    assertTrue(created.failure().isEmpty());
    assertEquals("{\"value\":1}\n", created.observed().orElseThrow().text().orElseThrow());
    assertEquals(1, entries(root.resolve("example")));
    var unchanged = file.write(created.observed().orElseThrow(), "{\"value\":1}\n");
    assertEquals(OwnedFile.Persistence.UNCHANGED, unchanged.state());
    assertEquals(
        created.observed().orElseThrow().revision(), unchanged.observed().orElseThrow().revision());
    assertEquals(OwnedFile.Persistence.REFUSED, file.write(absent, "overwrite").state());
    assertEquals(0, OwnedFile.LOCKS.retainedKeys());
  }

  @Test
  void exactConflictAndForeignSnapshotsCannotOverwrite() throws Exception {
    Files.writeString(root.resolve("prefs.json"), "first\r\n");
    var owner = file("prefs.json");
    var baseline = owner.read();
    Files.writeString(root.resolve("prefs.json"), "first\n");
    var refused = owner.write(baseline, "replacement");
    assertEquals(OwnedFile.Persistence.REFUSED, refused.state());
    assertEquals("first\n", Files.readString(root.resolve("prefs.json")));
    assertThrows(
        IllegalArgumentException.class, () -> owner.write(file("prefs.json").read(), "foreign"));
    assertEquals(0, OwnedFile.LOCKS.retainedKeys());
  }

  @Test
  void malformedOversizedAndNonFileInputsRemainUntouched() throws Exception {
    var path = root.resolve("prefs.json");
    var file = new OwnedFile(StorageBinding.relative(root, "prefs.json"), 4);
    Files.write(path, new byte[] {(byte) 0xc3, 0x28});
    assertThrows(IOException.class, file::read);
    assertArrayEquals(new byte[] {(byte) 0xc3, 0x28}, Files.readAllBytes(path));
    Files.writeString(path, "12345");
    assertThrows(IOException.class, file::read);
    Files.writeString(path, "1234");
    var snapshot = file.read();
    for (String invalid : List.of("12345", "€€", "\ud800")) {
      assertEquals(OwnedFile.Persistence.REFUSED, file.write(snapshot, invalid).state());
      assertEquals("1234", Files.readString(path));
    }
    Files.delete(path);
    Files.createDirectory(path);
    assertThrows(IOException.class, file::read);
    assertEquals(0, OwnedFile.LOCKS.retainedKeys());
  }

  @Test
  void caseCollisionsAndSymlinksAreNotAdmitted() throws Exception {
    Files.writeString(root.resolve("Prefs.json"), "case");
    assertThrows(IOException.class, file("prefs.json")::read);
    var target = root.resolve("outside");
    Files.createDirectory(target);
    Files.writeString(target.resolve("real.json"), "protected");
    Files.createSymbolicLink(root.resolve("linked.json"), target.resolve("real.json"));
    assertThrows(IOException.class, file("linked.json")::read);
    Files.createSymbolicLink(root.resolve("linked-directory"), target);
    assertThrows(
        IOException.class,
        () ->
            new OwnedFile(StorageBinding.relative(root, "linked-directory", "real.json"), 1024)
                .read());
    assertEquals("protected", Files.readString(target.resolve("real.json")));
    assertEquals(0, OwnedFile.LOCKS.retainedKeys());
  }

  @Test
  void concurrentInitializersPublishOneCompleteValueWithoutReplacingTheWinner() throws Exception {
    var a = file("prefs.json");
    var b = file("prefs.json");
    var absentA = a.read();
    var absentB = b.read();
    var start = new CountDownLatch(1);
    try (var executor = Executors.newFixedThreadPool(2)) {
      var first =
          executor.submit(
              () -> {
                start.await();
                return a.write(absentA, "first".repeat(100));
              });
      var second =
          executor.submit(
              () -> {
                start.await();
                return b.write(absentB, "second".repeat(100));
              });
      start.countDown();
      var results = List.of(first.get(5, TimeUnit.SECONDS), second.get(5, TimeUnit.SECONDS));
      assertEquals(
          1, results.stream().filter(r -> r.state() == OwnedFile.Persistence.SAVED).count());
      assertEquals(
          1, results.stream().filter(r -> r.state() == OwnedFile.Persistence.REFUSED).count());
      assertTrue(
          List.of("first".repeat(100), "second".repeat(100))
              .contains(Files.readString(root.resolve("prefs.json"))));
    }
    assertEquals(1, entries(root));
    assertEquals(0, OwnedFile.LOCKS.retainedKeys());
  }

  @Test
  void postReplacementFailureReconcilesActualBytesInsteadOfClaimingNoWrite() throws Exception {
    var binding = StorageBinding.relative(root, "prefs.json");
    Files.writeString(binding.path(), "before");
    var file =
        new OwnedFile(
            binding,
            1024,
            4096,
            () -> {
              throw new IOException("after replacement");
            });
    var result = file.write(file.read(), "intended");
    assertEquals(OwnedFile.Persistence.SAVED, result.state());
    assertEquals("intended", result.observed().orElseThrow().text().orElseThrow());
    assertTrue(result.failure().isPresent());
    assertEquals(1, entries(root));
    assertEquals(0, OwnedFile.LOCKS.retainedKeys());
  }

  @Test
  void noncooperatingCreatorAfterTheFinalCheckIsNotReplaced() throws Exception {
    var binding = StorageBinding.relative(root, "prefs.json");
    var probe =
        new OwnedFile.WriteProbe() {
          @Override
          public void before() throws IOException {
            Files.writeString(
                binding.path(),
                "competing complete file",
                java.nio.file.StandardOpenOption.CREATE_NEW);
          }

          @Override
          public void run() {}
        };
    var file = new OwnedFile(binding, 1024, 4096, probe);
    var result = file.write(file.read(), "own defaults");
    assertEquals(OwnedFile.Persistence.REFUSED, result.state());
    assertEquals("competing complete file", result.observed().orElseThrow().text().orElseThrow());
    assertEquals("competing complete file", Files.readString(binding.path()));
    assertEquals(1, entries(root));
  }

  @Test
  void completedDistinctPathsLeaveNoLockTableHistory() throws Exception {
    for (int i = 0; i < 1024; i++) {
      assertFalse(file("missing-" + i + ".json").read().present());
      assertEquals(0, OwnedFile.LOCKS.retainedKeys());
    }
    assertEquals(0, entries(root));
  }

  @Test
  void laterExternalEditIsUnknownAndNeverRolledBack() throws Exception {
    var binding = StorageBinding.relative(root, "prefs.json");
    Files.writeString(binding.path(), "before");
    var file =
        new OwnedFile(
            binding,
            1024,
            4096,
            () -> {
              Files.writeString(binding.path(), "external");
              throw new IOException("later external change");
            });
    var result = file.write(file.read(), "intended");
    assertEquals(OwnedFile.Persistence.UNKNOWN, result.state());
    assertEquals("external", Files.readString(binding.path()));
    assertEquals("external", result.observed().orElseThrow().text().orElseThrow());
    assertEquals(1, entries(root));
  }

  @Test
  void ownTemporaryFileDoesNotConsumeTheDirectoryAdmissionBudget() throws Exception {
    Files.writeString(root.resolve("prefs.json"), "before");
    var file = new OwnedFile(StorageBinding.relative(root, "prefs.json"), 1024, 1);
    assertEquals(OwnedFile.Persistence.SAVED, file.write(file.read(), "after").state());
  }

  @Test
  void unreadablePostReplacementStateRemainsUnknownWithoutRollback() throws Exception {
    var binding = StorageBinding.relative(root, "prefs.json");
    Files.writeString(binding.path(), "before");
    var file =
        new OwnedFile(
            binding, 1024, 4096, () -> Files.write(binding.path(), new byte[] {(byte) 0xc3, 0x28}));
    var result = file.write(file.read(), "intended");
    assertEquals(OwnedFile.Persistence.UNKNOWN, result.state());
    assertTrue(result.observed().isEmpty());
    assertArrayEquals(new byte[] {(byte) 0xc3, 0x28}, Files.readAllBytes(binding.path()));
    assertEquals(1, entries(root));
    assertEquals(0, OwnedFile.LOCKS.retainedKeys());
  }

  @Test
  void uncheckedFailureAfterFirstPublicationStillCleansTheTemporaryAndReportsSaved()
      throws Exception {
    var binding = StorageBinding.relative(root, "prefs.json");
    var file =
        new OwnedFile(
            binding,
            1024,
            4096,
            () -> {
              throw new IllegalStateException("after first publication");
            });
    var result = file.write(file.read(), "intended");
    assertEquals(OwnedFile.Persistence.SAVED, result.state());
    assertTrue(result.failure().isPresent());
    assertEquals("intended", Files.readString(binding.path()));
    assertEquals(1, entries(root));
    assertEquals(0, OwnedFile.LOCKS.retainedKeys());
  }

  @Test
  void postReplacementExceptionSubtypeDoesNotBecomeAnUnattemptedConflict() throws Exception {
    var binding = StorageBinding.relative(root, "prefs.json");
    Files.writeString(binding.path(), "before");
    var file =
        new OwnedFile(
            binding,
            1024,
            4096,
            () -> {
              throw new FileAlreadyExistsException("later callback");
            });
    assertEquals(OwnedFile.Persistence.SAVED, file.write(file.read(), "intended").state());
  }

  @Test
  void replacementPreservesPosixPermissionsWhenSupported() throws Exception {
    var path = root.resolve("prefs.json");
    Files.writeString(path, "before");
    org.junit.jupiter.api.Assumptions.assumeTrue(
        Files.getFileStore(path).supportsFileAttributeView("posix"));
    var permissions =
        Set.of(
            PosixFilePermission.OWNER_READ,
            PosixFilePermission.OWNER_WRITE,
            PosixFilePermission.GROUP_READ);
    Files.setPosixFilePermissions(path, permissions);
    var file = file("prefs.json");
    assertEquals(OwnedFile.Persistence.SAVED, file.write(file.read(), "after").state());
    assertEquals(permissions, Files.getPosixFilePermissions(path));
  }

  @Test
  void explicitlyReadOnlyFileIsRefusedEvenWhenItsParentAllowsReplacement() throws Exception {
    var path = root.resolve("prefs.json");
    Files.writeString(path, "before");
    org.junit.jupiter.api.Assumptions.assumeTrue(
        Files.getFileStore(path).supportsFileAttributeView("posix"));
    var original = Files.getPosixFilePermissions(path);
    var file = file("prefs.json");
    var baseline = file.read();
    try {
      Files.setPosixFilePermissions(path, Set.of(PosixFilePermission.OWNER_READ));
      org.junit.jupiter.api.Assumptions.assumeFalse(
          Files.isWritable(path), "Host bypasses read-only mode");
      assertEquals(OwnedFile.Persistence.REFUSED, file.write(baseline, "after").state());
      assertEquals("before", Files.readString(path));
      assertEquals(1, entries(root));
    } finally {
      Files.setPosixFilePermissions(path, original);
    }
  }
}
