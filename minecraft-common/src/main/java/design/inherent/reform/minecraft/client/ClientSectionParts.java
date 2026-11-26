package design.inherent.reform.minecraft.client;

import design.inherent.reform.definition.FieldPath;
import design.inherent.reform.owner.PolicyRefusal;
import design.inherent.reform.presentation.FormTarget;
import design.inherent.reform.presentation.graphics.GraphicLimits;
import design.inherent.reform.presentation.ui.UiScope;
import design.inherent.reform.value.ValueType;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.BiFunction;

/** Canonical generated field families; no second draft, input, scroll owner, or save action. */
public final class ClientSectionParts<T> {
  private final UiScope scope;
  private final FormTarget.Section target;
  private final List<FieldPath> order;
  private Map<FieldPath, ClientFieldParts<?>> fields;
  private Optional<ClientEditor<T>> editor;
  private BiFunction<GraphicLimits, Integer, ClientGraphicHost> graphics;

  ClientSectionParts(
      UiScope scope,
      FormTarget.Section target,
      Map<FieldPath, ClientFieldParts<?>> fields,
      Optional<ClientEditor<T>> editor,
      BiFunction<GraphicLimits, Integer, ClientGraphicHost> graphics) {
    this.scope = Objects.requireNonNull(scope);
    this.target = Objects.requireNonNull(target);
    if (fields.isEmpty() || fields.size() > 64)
      throw new IllegalArgumentException("A section needs one to 64 actual fields");
    this.order = List.copyOf(fields.keySet());
    this.fields = Map.copyOf(fields);
    if (fields.values().stream().anyMatch(parts -> !parts.belongsTo(scope)))
      throw new IllegalArgumentException("Section parts need the exact admitted field scope");
    var original = fields.values().iterator().next().binding().identity();
    for (var entry : fields.entrySet()) {
      var identity = entry.getValue().binding().identity();
      if (!identity.path().equals(entry.getKey())
          || identity.definition() != original.definition()
          || !identity.context().equals(original.context()))
        throw new IllegalArgumentException(
            "Section fields must belong to one actual owner and their declared paths");
    }
    this.editor = Objects.requireNonNull(editor);
    this.graphics = graphics;
    scope.onRetire(
        () -> {
          this.fields = Map.of();
          this.editor = Optional.empty();
          this.graphics = null;
        });
  }

  public FormTarget.Section target() {
    scope.requireCurrent();
    return target;
  }

  public List<FieldPath> fields() {
    scope.requireCurrent();
    return order;
  }

  public <V> ClientFieldParts<V> field(FieldPath path, ValueType<V> exactType) {
    scope.requireCurrent();
    var field = fields.get(Objects.requireNonNull(path));
    if (field == null || field.binding().identity().type() != Objects.requireNonNull(exactType))
      throw new IllegalArgumentException("Use an actual section field and its exact declared type");
    @SuppressWarnings(
        "unchecked") // Exact type capability, not decoded shape or a class-name guess.
    var typed = (ClientFieldParts<V>) field;
    return typed;
  }

  /** Only a caller-retained/workflow editor, never an invented wrapper or owner. */
  public Optional<ClientEditor<T>> editor() {
    scope.requireCurrent();
    return editor;
  }

  /** Borrow the workflow's one aggregate host; construction performs no preparation. */
  public ClientGraphicHost graphics(GraphicLimits limits, int maximumViews) {
    scope.requireCurrent();
    if (graphics == null || editor.isEmpty())
      throw new PolicyRefusal(
          "ui.preview_host_unavailable", "This host has no admitted workflow preview service.");
    return graphics.apply(Objects.requireNonNull(limits), maximumViews);
  }

  static List<FieldPath> paths(
      design.inherent.reform.presentation.FormPresentation presentation,
      FormTarget.Section target) {
    List<FieldPath> paths;
    if (target.id().startsWith("group:"))
      paths =
          presentation.layout().groups().stream()
              .filter(group -> target.id().equals("group:" + group.id()))
              .findFirst()
              .orElseThrow(
                  () -> new IllegalArgumentException("Declare an actual generated section"))
              .fields();
    else {
      var range =
          presentation.ranges().stream()
              .filter(value -> target.id().equals("range:" + value.id()))
              .findFirst()
              .orElseThrow(() -> new IllegalArgumentException("Declare an actual generated range"));
      paths = List.of(range.minimum(), range.maximum());
    }
    if (paths.isEmpty() || paths.size() > 64)
      throw new IllegalArgumentException("A section needs one to 64 actual fields");
    return List.copyOf(paths);
  }

  List<ClientFieldParts<?>> requiredFields() {
    scope.requireCurrent();
    return order.stream().map(fields::get).toList();
  }
}
