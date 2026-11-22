package design.inherent.reform.minecraft.client;

import design.inherent.reform.definition.DocumentDefinition;
import design.inherent.reform.definition.FieldPath;
import design.inherent.reform.edit.DraftNode;
import design.inherent.reform.edit.DraftTarget;
import design.inherent.reform.edit.DraftTree;
import design.inherent.reform.edit.DraftTypes;
import design.inherent.reform.operation.DocumentActions;
import design.inherent.reform.owner.EditorActor;
import design.inherent.reform.value.ValueType;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.BooleanSupplier;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** Explicit native auxiliary editors over the generated form's actual shared draft and services. */
public final class FieldEditors<T> {
  @FunctionalInterface
  public interface Editor<T> {
    /**
     * Build an auxiliary screen without saving, replacing the actor or switching screens. On normal
     * return call {@link Request#finish}; on unexpected removal call {@link Request#abandon}.
     */
    Screen open(Request<T> request);
  }

  public static final class Request<T> {
    private final Component title, contextLabel;
    private final DocumentActions<T> actions;
    private final DraftTarget target;
    private final ValueType<?> type;
    private final DraftTree draft;
    private final BooleanSupplier available;
    private final Runnable returned, abandoned;
    private boolean ended;

    Request(
        Component title,
        Component contextLabel,
        DocumentActions<T> actions,
        DraftTarget target,
        ValueType<?> type,
        DraftTree draft,
        BooleanSupplier available,
        Runnable returned,
        Runnable abandoned) {
      this.title = title.copy();
      this.contextLabel = contextLabel.copy();
      this.actions = actions;
      this.target = target;
      this.type = type;
      this.draft = draft;
      this.available = available;
      this.returned = returned;
      this.abandoned = abandoned;
    }

    public Component title() {
      return title.copy();
    }

    public Component contextLabel() {
      return contextLabel.copy();
    }

    public DocumentActions<T> actions() {
      return actions;
    }

    public DraftTarget target() {
      return target;
    }

    public ValueType<?> type() {
      return type;
    }

    /** Original bounded immutable draft; invalid or inactive sibling data is not replaced. */
    public DraftTree draft() {
      return draft;
    }

    public DraftNode input() {
      return draft.at(target.path());
    }

    /** Cached input/lifetime test only. Shared operations still check actual authority. */
    public boolean current() {
      if (ended || !available.getAsBoolean()) return false;
      var view = actions.view();
      if (view.availability() != EditorActor.Availability.READY
          || view.snapshot().isEmpty()
          || view.review().isPresent()
          || view.pending().isPresent()) return false;
      var snapshot = view.snapshot().orElseThrow();
      return target.equals(snapshot.target(target.path()))
          && snapshot.draft().activeAt(target.path());
    }

    /** Return to the form once, retaining its actual shared draft, history and ownership. */
    public void finish() {
      if (ended) return;
      ended = true;
      returned.run();
    }

    /** Release this editing lifetime once when its auxiliary screen is unexpectedly discarded. */
    public void abandon() {
      if (ended) return;
      ended = true;
      abandoned.run();
    }
  }

  public static final class Builder<T> {
    private final DocumentDefinition<T> definition;
    private final Map<FieldPath, Editor<T>> fields = new LinkedHashMap<>();
    private final Map<ValueType<?>, Editor<T>> types = new IdentityHashMap<>();

    private Builder(DocumentDefinition<T> definition) {
      this.definition = Objects.requireNonNull(definition);
    }

    /** Exact declared path. Row existence and active alternatives are checked when opening. */
    public Builder<T> field(FieldPath path, Editor<T> editor) {
      Objects.requireNonNull(path);
      Objects.requireNonNull(editor);
      DraftTypes.declaredAt(definition.type(), path);
      if (fields.containsKey(path) || fields.size() + types.size() >= 256)
        throw new IllegalArgumentException("Use unique bounded auxiliary field registrations");
      fields.put(path, editor);
      return this;
    }

    /** Reuse this exact value type, including actual stable collection rows of that type. */
    public Builder<T> type(ValueType<?> type, Editor<T> editor) {
      Objects.requireNonNull(type);
      Objects.requireNonNull(editor);
      if (types.containsKey(type) || fields.size() + types.size() >= 256)
        throw new IllegalArgumentException("Use unique bounded auxiliary type registrations");
      types.put(type, editor);
      return this;
    }

    public FieldEditors<T> build() {
      return new FieldEditors<>(definition, fields, types);
    }
  }

  private final DocumentDefinition<T> definition;
  private final Map<FieldPath, Editor<T>> fields;
  private final Map<ValueType<?>, Editor<T>> types;

  private FieldEditors(
      DocumentDefinition<T> definition,
      Map<FieldPath, Editor<T>> fields,
      Map<ValueType<?>, Editor<T>> types) {
    this.definition = definition;
    this.fields = Map.copyOf(fields);
    this.types = java.util.Collections.unmodifiableMap(new IdentityHashMap<>(types));
  }

  public static <T> Builder<T> builder(DocumentDefinition<T> definition) {
    return new Builder<>(definition);
  }

  void requireCompatible(DocumentDefinition<T> actual) {
    if (definition != actual)
      throw new IllegalArgumentException("Auxiliary editors require their actual definition");
  }

  Optional<Editor<T>> at(FieldPath path, ValueType<?> type) {
    return Optional.ofNullable(fields.containsKey(path) ? fields.get(path) : types.get(type));
  }
}
