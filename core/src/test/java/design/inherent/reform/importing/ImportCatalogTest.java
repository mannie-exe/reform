package design.inherent.reform.importing;

import static org.junit.jupiter.api.Assertions.*;

import design.inherent.reform.definition.DocumentDefinition;
import design.inherent.reform.definition.DocumentId;
import design.inherent.reform.edit.DraftLimits;
import design.inherent.reform.operation.DocumentImports;
import design.inherent.reform.serialization.JsonLimits;
import design.inherent.reform.value.ValueTypes;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class ImportCatalogTest {
  private static final DocumentDefinition<Integer> DEFINITION =
      new DocumentDefinition<>(
          DocumentId.parse("example:profile"), 1, ValueTypes.integer(0, 10), () -> 1);

  private static List<ImportFormat<Integer>> formats(DocumentDefinition<Integer> definition) {
    return JsonImportFormat.documentFormats(
        definition,
        new JsonLimits(8192, 32, 1024, 512, 64, 1024),
        DraftLimits.CONFIGURATION,
        ImportFormat.InvalidDraftPolicy.ALLOW_REPAIRABLE);
  }

  private static ImportSource source(String name) {
    return ImportSource.snapshot(
        DocumentId.parse(name),
        new ImportInput("Registered profile", "3".getBytes(StandardCharsets.UTF_8), 16));
  }

  @Test
  void snapshotsBoundedChoicesWithoutCheckingOrCapturingSource() {
    var calls = new AtomicInteger();
    var source =
        new ImportSource() {
          public DocumentId id() {
            return DocumentId.parse("example:admitted");
          }

          public String label() {
            return "Café profile";
          }

          public int maximumBytes() {
            return 16;
          }

          public void requireCurrent() {
            calls.incrementAndGet();
            throw new AssertionError("guard in metadata");
          }

          public Capture capture() {
            calls.incrementAndGet();
            throw new AssertionError("capture in metadata");
          }
        };
    var sources = new ArrayList<ImportSource>(List.of(source));
    var parsers = new ArrayList<>(formats(DEFINITION));
    var catalog = new ImportCatalog<>(DEFINITION, sources, parsers);
    sources.clear();
    parsers.clear();
    assertSame(DEFINITION, catalog.definition());
    assertSame(source, catalog.admittedSource(source.id()).orElseThrow());
    assertEquals(
        List.of(new ImportCatalog.Source(source.id(), "Café profile", 16)), catalog.sources());
    assertEquals(
        List.of(
            DocumentId.parse("reform:import/value_json"),
            DocumentId.parse("reform:import/schema_json")),
        catalog.formats().stream().map(DocumentImports.Format::id).toList());
    assertTrue(catalog.admittedSource(DocumentId.parse("example:unknown")).isEmpty());
    assertThrows(UnsupportedOperationException.class, () -> catalog.sources().clear());
    assertThrows(UnsupportedOperationException.class, () -> catalog.formats().clear());
    assertEquals(0, calls.get());
  }

  @Test
  void refusesDuplicateForeignAndUnboundedChoicesBeforeUse() {
    var source = source("example:admitted");
    var parsers = formats(DEFINITION);
    assertThrows(
        IllegalArgumentException.class,
        () -> new ImportCatalog<>(DEFINITION, List.of(source, source), parsers));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new ImportCatalog<>(
                DEFINITION, List.of(source), List.of(parsers.getFirst(), parsers.getFirst())));
    var foreign = new DocumentDefinition<>(DEFINITION.id(), 1, DEFINITION.type(), () -> 1);
    assertThrows(
        IllegalArgumentException.class,
        () -> new ImportCatalog<>(DEFINITION, List.of(source), formats(foreign)));
    assertThrows(
        IllegalArgumentException.class,
        () -> new ImportCatalog<>(DEFINITION, List.of(source), List.of()));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new ImportCatalog<>(
                DEFINITION,
                List.of(source, source("example:another")),
                parsers,
                DocumentImports.Limits.STANDARD,
                1));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new ImportCatalog<>(
                DEFINITION, List.of(source), parsers, DocumentImports.Limits.STANDARD, 1025));
    var small =
        new DocumentImports.Limits(1, 1, 1, 1, 1, DocumentImports.Limits.STANDARD.operations());
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new ImportCatalog<>(
                DEFINITION, List.of(source), List.of(parsers.getFirst()), small, 1));
    var empty = new ImportCatalog<>(DEFINITION, List.of(), parsers);
    assertTrue(empty.sources().isEmpty());
  }
}
