package design.inherent.reform.edit;

import design.inherent.reform.definition.DocumentDefinition;
import design.inherent.reform.definition.FieldPath;
import design.inherent.reform.serialization.SourcePath;
import java.util.Map;

/** A completely validated value and its frozen authored representation; not an authority grant. */
public final class Candidate<T> {
  private final DocumentDefinition<T> definition;
  private final T value;
  private final DraftTree draft;
  private final String json;
  private final Map<SourcePath, FieldPath> addresses;

  Candidate(
      DocumentDefinition<T> definition,
      T value,
      DraftTree draft,
      String json,
      Map<SourcePath, FieldPath> addresses) {
    this.definition = definition;
    this.value = definition.type().freeze(value);
    this.draft = draft;
    this.json = json;
    this.addresses = Map.copyOf(addresses);
  }

  public DocumentDefinition<T> definition() {
    return definition;
  }

  public T value() {
    return definition.type().freeze(value);
  }

  public DraftTree draft() {
    return draft;
  }

  public String authoredJson() {
    return json;
  }

  public Map<SourcePath, FieldPath> addresses() {
    return addresses;
  }
}
