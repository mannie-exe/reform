package design.inherent.reform.presentation.ui;

import com.mojang.serialization.JsonOps;
import design.inherent.reform.definition.DocumentDefinition;
import design.inherent.reform.definition.DocumentId;
import design.inherent.reform.definition.FieldPath;
import design.inherent.reform.edit.DraftEdit;
import design.inherent.reform.edit.DraftNode;
import design.inherent.reform.edit.DraftRevision;
import design.inherent.reform.edit.DraftTypes;
import design.inherent.reform.operation.DocumentActions;
import design.inherent.reform.operation.OperationDescriptor.EntryPoint;
import design.inherent.reform.owner.PolicyRefusal;
import design.inherent.reform.validation.ValidationReport;
import design.inherent.reform.value.SnapshotContext;
import design.inherent.reform.value.SnapshotPolicy;
import design.inherent.reform.value.ValueType;
import java.lang.ref.WeakReference;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Borrows the actual owner and stable path. Closing releases only presentation observations. */
public final class FieldBinding<T, V> implements AutoCloseable {
  public record Identity<T, V>(
      DocumentDefinition<T> definition, UUID context, FieldPath path, ValueType<V> type) {}

  private final UiScope scope;
  private final DocumentActions<T> actions;
  private final Identity<T, V> identity;
  private final FieldFrontend frontend;
  private final UiReadKey<FieldState<V>> key;
  private final UiRead<FieldState<V>> state;
  private UiObservation.Available<FieldState<V>> cached;
  private WeakReference<design.inherent.reform.owner.EditorActor.View<T>> observedView;
  private long revision;

  private FieldBinding(
      UiScope parent,
      DocumentActions<T> actions,
      FieldPath path,
      ValueType<V> type,
      FieldFrontend frontend) {
    parent.requireCurrent();
    this.actions = Objects.requireNonNull(actions);
    actions.requireInspection();
    if (DraftTypes.declaredAt(actions.definition().type(), path) != type)
      throw new PolicyRefusal("field.type_mismatch", "Bind the exact declared field type.");
    identity =
        new Identity<>(actions.definition(), actions.context(), Objects.requireNonNull(path), type);
    this.frontend = Objects.requireNonNull(frontend);
    scope = parent.child();
    try {
      key =
          new UiReadKey<>(
              DocumentId.parse("reform:bound_field"),
              new SnapshotPolicy<>() {
                public FieldState<V> freeze(FieldState<V> value, SnapshotContext context) {
                  return copy(value, value.value().map(v -> type.freeze(v, context)));
                }

                public boolean equivalent(FieldState<V> first, FieldState<V> second) {
                  if (!copy(first, Optional.empty()).equals(copy(second, Optional.empty()))
                      || first.value().isPresent() != second.value().isPresent()) return false;
                  return first.value().isEmpty()
                      || type.equivalent(first.value().orElseThrow(), second.value().orElseThrow());
                }
              },
              SnapshotContext.Limits.CONFIGURATION,
              parent.limits().maximumStateBytes(),
              FieldBinding::weight);
      scope.onRetire(
          () -> {
            cached = null;
            observedView = null;
          });
      state = scope.observe(key, this::readOwner);
    } catch (RuntimeException | Error failed) {
      try {
        scope.close();
      } catch (RuntimeException | Error cleanup) {
        if (cleanup != failed) failed.addSuppressed(cleanup);
      }
      throw failed;
    }
  }

  public static <T, V> FieldBinding<T, V> bind(
      UiScope parent, DocumentActions<T> actions, FieldPath path, ValueType<V> exactType) {
    return bind(parent, actions, path, exactType, FieldFrontend.NONE);
  }

  public static <T, V> FieldBinding<T, V> bind(
      UiScope parent,
      DocumentActions<T> actions,
      FieldPath path,
      ValueType<V> exactType,
      FieldFrontend frontend) {
    return new FieldBinding<>(parent, actions, path, exactType, frontend);
  }

  public Identity<T, V> identity() {
    return identity;
  }

  public UiRead<FieldState<V>> state() {
    return state;
  }

  private static <V> FieldState<V> copy(FieldState<V> s, Optional<V> value) {
    return new FieldState<>(
        s.revision(),
        s.target(),
        s.draft(),
        value,
        s.parse(),
        s.validation(),
        s.input(),
        s.active(),
        s.changed(),
        s.editing(),
        s.submission(),
        s.encodedValueBytes(),
        s.observationFailure());
  }

  private static long weight(FieldState<?> value) {
    long total =
        256L
            + value.encodedValueBytes()
            + 32L * value.draft().size().nodes()
            + 2L * value.draft().size().characters()
            + 2L * value.revision().sourceRevision().length();
    if (value.input().isPresent()) total += 2L * value.input().orElseThrow().raw().length();
    for (var report :
        List.of(value.validation(), value.editing().report(), value.submission().report())) {
      for (var issue : report.issues()) {
        total +=
            128L
                + 2L
                    * (issue.code().length()
                        + issue.fallback().length()
                        + issue.path().toString().length());
        for (var related : issue.related()) total += 2L * related.toString().length();
        for (var argument : issue.arguments().entrySet())
          total += 2L * (argument.getKey().length() + argument.getValue().length());
      }
    }
    return total;
  }

  private UiObservation<FieldState<V>> readOwner() {
    scope.requireCurrent();
    try {
      actions.requireInspection();
      var view = actions.view();
      var current = view.snapshot().orElseThrow();
      if (current.definition() != identity.definition()
          || !current.context().equals(identity.context()))
        throw new PolicyRefusal("field.owner_changed", "This field belongs to another owner.");
      if (!current.draft().contains(identity.path()))
        throw new PolicyRefusal("field.missing", "This field is no longer present in the draft.");
      boolean active = current.draft().activeAt(identity.path());
      var raw = current.draft().at(identity.path());
      UiAvailability editing;
      try {
        actions.requireEditing();
        editing =
            active
                ? frontend.editing()
                : UiAvailability.blocked("field.inactive", "This field is inactive.");
      } catch (PolicyRefusal blocked) {
        editing = new UiAvailability(false, blocked.report());
      }
      var nativeInput = frontend.input();
      var submission = editing.available() ? frontend.submission() : editing;
      boolean sameView = cached != null && observedView != null && observedView.get() == view;
      if (sameView) {
        var previous = cached.data().value();
        if (previous.input().equals(nativeInput)
            && previous.editing().equals(editing)
            && previous.submission().equals(submission)) return cached;
      }
      FieldState<V> next;
      if (sameView) {
        var previous = cached.data().value();
        next =
            new FieldState<>(
                previous.revision(),
                previous.target(),
                previous.draft(),
                previous.value(),
                previous.parse(),
                previous.validation(),
                nativeInput,
                previous.active(),
                previous.changed(),
                editing,
                submission,
                previous.encodedValueBytes(),
                previous.observationFailure());
      } else {
        var inspected =
            current.draft().inspectField(identity.type(), identity.path(), current.jsonLimits());
        var issues =
            new ValidationReport(
                view.validation().issues().stream()
                    .filter(
                        issue ->
                            issue.path().startsWith(identity.path())
                                || identity.path().startsWith(issue.path())
                                || issue.related().stream()
                                    .anyMatch(
                                        p ->
                                            p.startsWith(identity.path())
                                                || identity.path().startsWith(p)))
                    .toList(),
                view.validation().complete());
        next =
            new FieldState<>(
                DraftRevision.of(current),
                new design.inherent.reform.edit.DraftTarget(
                    current.session(),
                    current.generation(),
                    Optional.of(current.version()),
                    identity.path()),
                raw,
                inspected.value(),
                view.inputs().getOrDefault(identity.path(), inspected.input()),
                issues.and(inspected.report()),
                nativeInput,
                active,
                view.changedAt(identity.path()),
                editing,
                submission,
                inspected.encodedBytes(),
                inspected.failure());
      }
      var captured = key.capture(next);
      if (cached == null || !cached.data().sameData(captured)) {
        if (revision == Long.MAX_VALUE)
          throw new PolicyRefusal(
              "field.epoch_exhausted", "Reopen this field before further edits.");
        cached = new UiObservation.Available<>(captured, ++revision);
      }
      observedView = new WeakReference<>(view);
      return cached;
    } catch (RuntimeException | Error failed) {
      cached = null;
      observedView = null;
      throw failed;
    }
  }

  public UiCommand<FieldEdit<V>> edits() {
    return new UiCommand<>() {
      public List<UiRead<?>> dependencies() {
        return List.of(state);
      }

      public UiAvailability availability() {
        return state.snapshot().value().editing();
      }

      public UiActionResult invoke(FieldEdit<V> request) {
        Objects.requireNonNull(request);
        var result = new UiActionResult[1];
        try {
          scope.update(
              () -> {
                var current = scope.project(List.of(), () -> readOwner().value());
                if (!current.editing().available()) {
                  result[0] =
                      new UiActionResult.Refused(current.editing().report(), Optional.empty());
                  return;
                }
                actions.requireEditing();
                DraftEdit.Action action;
                if (request instanceof FieldEdit.Raw<V> raw)
                  action = new DraftEdit.SetRaw(raw.text());
                else if (request instanceof FieldEdit.Structure<V> structure)
                  action = structure.action();
                else {
                  var typed = (FieldEdit.Typed<V>) request;
                  if (!(current.draft() instanceof DraftNode.Scalar))
                    throw new PolicyRefusal(
                        "field.typed_edit_unsupported",
                        "Use the declared structural controls for this field.");
                  var encoded =
                      identity
                          .type()
                          .codec()
                          .encodeStart(JsonOps.INSTANCE, identity.type().freeze(typed.value()));
                  if (encoded.error().isPresent()
                      || encoded.result().filter(e -> e.isJsonPrimitive()).isEmpty())
                    throw new PolicyRefusal(
                        "field.value_invalid",
                        "This value cannot be represented by the declared scalar field.");
                  action = new DraftEdit.SetRaw(encoded.result().orElseThrow().getAsString());
                }
                result[0] =
                    new UiActionResult.Pending(
                        actions.edit(EntryPoint.GUI, new DraftEdit(current.target(), action)));
              });
          return result[0];
        } catch (PolicyRefusal refused) {
          return new UiActionResult.Refused(refused.report(), Optional.of(refused));
        }
      }
    };
  }

  @Override
  public void close() {
    scope.close();
  }
}
