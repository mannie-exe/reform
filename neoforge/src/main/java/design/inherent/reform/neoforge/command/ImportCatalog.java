package design.inherent.reform.neoforge.command;

import design.inherent.reform.definition.DocumentDefinition;
import design.inherent.reform.definition.DocumentId;
import design.inherent.reform.importing.ImportFormat;
import design.inherent.reform.importing.ImportSource;
import design.inherent.reform.operation.DocumentImports;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Explicit native command choices. Construction snapshots metadata; it never captures a source. */
public final class ImportCatalog<T> {
  public record Source(DocumentId id, String label, int maximumBytes) {}

  private final DocumentDefinition<T> definition;
  private final Map<DocumentId, ImportSource> sources;
  private final List<Source> sourceChoices;
  private final List<ImportFormat<T>> formats;
  private final List<DocumentImports.Format> formatChoices;
  private final DocumentImports.Limits limits;

  public ImportCatalog(
      DocumentDefinition<T> definition,
      List<? extends ImportSource> sources,
      List<? extends ImportFormat<T>> formats) {
    this(definition, sources, formats, DocumentImports.Limits.STANDARD, 64);
  }

  public ImportCatalog(
      DocumentDefinition<T> definition,
      List<? extends ImportSource> sources,
      List<? extends ImportFormat<T>> formats,
      DocumentImports.Limits limits,
      int maximumSources) {
    this.definition = Objects.requireNonNull(definition);
    this.limits = Objects.requireNonNull(limits);
    if (maximumSources < 1
        || maximumSources > 1024
        || sources.size() > maximumSources
        || formats.isEmpty()
        || formats.size() > limits.maximumFormats())
      throw new IllegalArgumentException("Command import choices exceed their admission bounds");
    var admitted = new LinkedHashMap<DocumentId, ImportSource>();
    var choices = new java.util.ArrayList<Source>();
    for (var source : sources) {
      var choice =
          new Source(
              Objects.requireNonNull(source.id()), label(source.label()), source.maximumBytes());
      if (choice.maximumBytes() < 1
          || choice.maximumBytes() > limits.maximumBytes()
          || admitted.putIfAbsent(choice.id(), source) != null)
        throw new IllegalArgumentException("Duplicate or out-of-bound command import source");
      choices.add(choice);
    }
    var parsers = new LinkedHashMap<DocumentId, ImportFormat<T>>();
    var parserChoices = new java.util.ArrayList<DocumentImports.Format>();
    for (var format : formats) {
      var choice =
          new DocumentImports.Format(
              Objects.requireNonNull(format.id()),
              label(format.label()),
              format.maximumBytes(),
              Objects.requireNonNull(format.invalidDraftPolicy()));
      if (format.definition() != definition
          || choice.maximumBytes() < 1
          || choice.maximumBytes() > limits.maximumBytes()
          || parsers.putIfAbsent(choice.id(), format) != null)
        throw new IllegalArgumentException(
            "Command import requires unique formats for the actual definition");
      parserChoices.add(choice);
    }
    this.sources = Collections.unmodifiableMap(admitted);
    sourceChoices = List.copyOf(choices);
    this.formats = List.copyOf(parsers.values());
    formatChoices = List.copyOf(parserChoices);
  }

  private static String label(String value) {
    if (Objects.requireNonNull(value).isBlank() || value.length() > 256)
      throw new IllegalArgumentException("Import labels must contain one to 256 characters");
    return value;
  }

  public DocumentDefinition<T> definition() {
    return definition;
  }

  public List<Source> sources() {
    return sourceChoices;
  }

  public List<DocumentImports.Format> formats() {
    return formatChoices;
  }

  ImportSource source(DocumentId id) {
    return sources.get(id);
  }

  List<ImportFormat<T>> parsers() {
    return formats;
  }

  DocumentImports.Limits limits() {
    return limits;
  }
}
