package design.inherent.reform.operation;

import design.inherent.reform.definition.DocumentId;
import design.inherent.reform.owner.OwnerContext;
import design.inherent.reform.value.ValueType;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Typed local service metadata, not an authorization grant or a transport schema. Results must be
 * immutable owner receipts/views; a custom provider freezes its result before completing its stage.
 */
public record OperationDescriptor<I, O>(
    DocumentId id,
    ValueType<I> input,
    OwnerContext.Right right,
    Set<Effect> effects,
    Set<EntryPoint> entryPoints,
    Optional<DocumentId> target) {
  public enum Effect {
    INSPECT,
    EDIT_DRAFT,
    PERSIST,
    APPLY,
    EXPORT,
    PUBLISH,
    GENERATE
  }

  public enum EntryPoint {
    GUI,
    COMMAND,
    INTERNAL
  }

  public OperationDescriptor {
    Objects.requireNonNull(id);
    Objects.requireNonNull(input);
    Objects.requireNonNull(right);
    Objects.requireNonNull(target);
    effects = Set.copyOf(effects);
    entryPoints = Set.copyOf(entryPoints);
    if (entryPoints.isEmpty()) throw new IllegalArgumentException("Operation needs an entry point");
  }

  public OperationDescriptor(
      DocumentId id,
      ValueType<I> input,
      OwnerContext.Right right,
      Set<Effect> effects,
      Set<EntryPoint> entryPoints) {
    this(id, input, right, effects, entryPoints, Optional.empty());
  }
}
