package design.inherent.reform.edit;

import design.inherent.reform.definition.DocumentId;
import design.inherent.reform.definition.FieldKey;
import design.inherent.reform.definition.RecordTypes;
import design.inherent.reform.value.ValueType;
import design.inherent.reform.value.ValueTypes;
import java.util.Objects;
import java.util.UUID;

/** One complete editor/source revision. Describing it never grants read or write authority. */
public record DraftRevision(
    DocumentId document,
    UUID context,
    UUID session,
    long generation,
    long version,
    String sourceRevision) {
  private static final ValueType<DraftRevision> TYPE =
      RecordTypes.record(DraftRevision.class)
          .register(UUID.class, ValueTypes.uuid())
          .register(
              DocumentId.class,
              RecordTypes.record(DocumentId.class)
                  .field("namespace", FieldKey.of("namespace", ValueTypes.text(255)))
                  .field("path", FieldKey.of("path", ValueTypes.text(255)))
                  .build())
          .field("generation", FieldKey.of("generation", ValueTypes.longInteger(0, Long.MAX_VALUE)))
          .field("version", FieldKey.of("version", ValueTypes.longInteger(0, Long.MAX_VALUE)))
          .field("sourceRevision", FieldKey.of("sourceRevision", ValueTypes.text(4096)))
          .build();

  public DraftRevision {
    Objects.requireNonNull(document);
    Objects.requireNonNull(context);
    Objects.requireNonNull(session);
    Objects.requireNonNull(sourceRevision);
    if (generation < 0 || version < 0)
      throw new IllegalArgumentException("Negative editor generation/version");
  }

  public static DraftRevision of(EditSession.Snapshot<?> snapshot) {
    return new DraftRevision(
        snapshot.definition().id(),
        snapshot.context(),
        snapshot.session(),
        snapshot.generation(),
        snapshot.version(),
        snapshot.sourceRevision());
  }

  public boolean matches(EditSession.Snapshot<?> snapshot) {
    return document.equals(snapshot.definition().id())
        && context.equals(snapshot.context())
        && session.equals(snapshot.session())
        && generation == snapshot.generation()
        && version == snapshot.version()
        && sourceRevision.equals(snapshot.sourceRevision());
  }

  public static ValueType<DraftRevision> type() {
    return TYPE;
  }
}
