package design.inherent.reform.reference;

import design.inherent.reform.definition.DocumentId;
import design.inherent.reform.definition.FieldPath;
import design.inherent.reform.edit.DraftEdit;
import design.inherent.reform.edit.DraftNode;
import design.inherent.reform.edit.DraftTarget;
import design.inherent.reform.edit.DraftTree;
import design.inherent.reform.edit.DraftTypes;
import design.inherent.reform.operation.DocumentActions;
import design.inherent.reform.operation.OperationDescriptor.EntryPoint;
import design.inherent.reform.operation.OperationHandle;
import design.inherent.reform.owner.EditorActor;
import design.inherent.reform.owner.PolicyRefusal;
import design.inherent.reform.value.ValueShape;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Stable collection editing over a borrowed document actor. Catalog browsing is separate; raw
 * authoring never becomes resource resolution, write authority or an implicit Save.
 */
public final class CollectionSelection<T> implements AutoCloseable {
  public record Item(UUID id, DraftNode input, DraftTree.InputState inputState) {
    public Item {
      Objects.requireNonNull(id);
      Objects.requireNonNull(input);
      Objects.requireNonNull(inputState);
    }

    public Optional<String> raw() {
      return input instanceof DraftNode.Scalar scalar
          ? Optional.of(scalar.raw())
          : Optional.empty();
    }
  }

  public record View(
      DraftTarget target,
      List<Item> items,
      int maximumSize,
      boolean unique,
      ValueShape.CollectionOrder order,
      ValueShape.Scalar element,
      Optional<DocumentId> referenceSource,
      List<String> knownChoices) {
    public View {
      items = List.copyOf(items);
      knownChoices = List.copyOf(knownChoices);
    }
  }

  public record Edit<T>(DraftTarget target, OperationHandle<EditorActor.View<T>> operation) {}

  public enum Acceptance {
    ACCEPTED,
    REFUSED,
    PENDING,
    STALE
  }

  private final Thread thread = Thread.currentThread();
  private final DocumentActions<T> document;
  private final FieldPath path;
  private View view;
  private Edit<T> pending;
  private boolean closed;

  public CollectionSelection(DocumentActions<T> document, FieldPath path) {
    this.document = Objects.requireNonNull(document);
    this.path = Objects.requireNonNull(path);
    refresh();
  }

  /** Immutable cached evidence. Input validity does not imply that a reference resolves. */
  public View view() {
    thread();
    requireOpen();
    return view;
  }

  /** Cached lifetime/version evidence only; actual document operations check current READ. */
  public boolean current() {
    thread();
    if (closed) return false;
    return matches(document.view(), view.target());
  }

  public Optional<Edit<T>> pending() {
    thread();
    return Optional.ofNullable(pending);
  }

  /** Explicitly adopt a changed view; never retarget an older widget's captured target. */
  public void refresh() {
    thread();
    requireOpen();
    if (pending != null)
      throw new PolicyRefusal("selection.pending", "Wait for the issued selection edit to finish");
    bind(document.view());
  }

  public Edit<T> change(EntryPoint entry, DraftTarget expected, DraftEdit.ChangeScalarRows change) {
    thread();
    requireOpen();
    if (pending != null)
      throw new PolicyRefusal("selection.pending", "Wait for the issued selection edit to finish");
    if (!view.target().equals(expected) || !current())
      throw new PolicyRefusal("selection.stale", "The collection changed; refresh its selection");
    var operation = document.edit(entry, new DraftEdit(expected, change));
    pending = new Edit<>(expected, operation);
    return pending;
  }

  /** Only this controller's issued completion can advance its target after its own edit. */
  public Acceptance accept(Edit<T> request) {
    thread();
    if (closed || request == null || request != pending) return Acceptance.STALE;
    var result = request.operation().snapshot();
    if (!result.terminal()) return Acceptance.PENDING;
    pending = null;
    if (result.result().isEmpty()) return Acceptance.REFUSED;
    var actual = document.view();
    var completed = result.result().orElseThrow();
    if (completed.snapshot().isEmpty()
        || !matches(actual, completed.snapshot().orElseThrow().target(path)))
      return Acceptance.STALE;
    bind(actual);
    return Acceptance.ACCEPTED;
  }

  private boolean matches(EditorActor.View<T> actual, DraftTarget target) {
    return actual.availability() == EditorActor.Availability.READY
        && actual
            .snapshot()
            .filter(
                snapshot ->
                    snapshot.session().equals(target.session())
                        && snapshot.generation() == target.generation()
                        && target
                            .version()
                            .filter(version -> version == snapshot.version())
                            .isPresent()
                        && snapshot.draft().activeAt(path))
            .isPresent();
  }

  private void bind(EditorActor.View<T> actual) {
    if (actual.availability() != EditorActor.Availability.READY || actual.snapshot().isEmpty())
      throw new PolicyRefusal("selection.draft", "The editing context is not ready");
    var snapshot = actual.snapshot().orElseThrow();
    var type = DraftTypes.at(document.definition().type(), snapshot.draft(), path);
    if (!(type.shape() instanceof ValueShape.Sequence sequence)
        || !(snapshot.draft().at(path) instanceof DraftNode.Sequence values))
      throw new PolicyRefusal("selection.shape", "Repair this collection before selecting values");
    var shape = sequence.element().shape();
    Optional<DocumentId> source = Optional.empty();
    if (shape instanceof ValueShape.Reference<?> reference) {
      source = Optional.of(reference.source());
      shape = reference.element().shape();
    }
    if (!(shape instanceof ValueShape.Scalar scalar))
      throw new PolicyRefusal("selection.shape", "This selection needs scalar elements");
    var items =
        values.rows().stream()
            .map(
                row ->
                    new Item(
                        row.id(),
                        row.value(),
                        actual
                            .inputs()
                            .getOrDefault(path.row(row.id()), DraftTree.InputState.UNAVAILABLE)))
            .toList();
    var choices =
        scalar.kind() == ValueShape.ScalarKind.BOOLEAN
            ? List.of("false", "true")
            : scalar.choices();
    view =
        new View(
            snapshot.target(path),
            items,
            sequence.maximumSize(),
            sequence.unique(),
            sequence.order(),
            scalar,
            source,
            choices);
  }

  private void requireOpen() {
    if (closed) throw new PolicyRefusal("selection.closed", "This collection selection is closed");
  }

  private void thread() {
    if (Thread.currentThread() != thread)
      throw new IllegalStateException("Collection selection belongs to its creating owner thread");
  }

  /** Releases local observations; issued edits stay owned by the document with no rollback. */
  @Override
  public void close() {
    thread();
    closed = true;
    view = null;
    pending = null;
  }
}
