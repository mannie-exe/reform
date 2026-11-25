package design.inherent.reform.minecraft.client;

import design.inherent.reform.definition.DocumentId;
import design.inherent.reform.minecraft.client.ui.UiComponent;
import design.inherent.reform.minecraft.client.ui.UiRowFailure;
import design.inherent.reform.minecraft.client.ui.ViewSpec;
import design.inherent.reform.minecraft.client.ui.Views;
import design.inherent.reform.owner.PolicyRefusal;
import design.inherent.reform.presentation.ViewStateKey;
import design.inherent.reform.presentation.ui.UiActionResult;
import design.inherent.reform.presentation.ui.UiAvailability;
import design.inherent.reform.presentation.ui.UiLimits;
import design.inherent.reform.presentation.ui.UiRead;
import design.inherent.reform.presentation.ui.UiScope;
import design.inherent.reform.validation.ValidationReport;
import design.inherent.reform.value.ValueTypes;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.network.chat.Component;

/** One mounted declaration/state tree, shared by framework and caller-owned native hosts. */
final class MountedUiTree implements AutoCloseable {
  private static final class ReadUnavailable extends RuntimeException {
    private static final long serialVersionUID = 1L;
    final transient design.inherent.reform.presentation.ui.UiObservation.Unavailable<?> observation;

    ReadUnavailable(
        design.inherent.reform.presentation.ui.UiObservation.Unavailable<?> observation) {
      super("An admitted presentation input is unavailable", observation.cause().orElse(null));
      this.observation = observation;
    }

    @java.io.Serial
    private void writeObject(java.io.ObjectOutputStream stream)
        throws java.io.NotSerializableException {
      throw new java.io.NotSerializableException(
          "Use bounded diagnostic reports, not Java exception transport");
    }

    @java.io.Serial
    private void readObject(java.io.ObjectInputStream stream)
        throws java.io.NotSerializableException {
      throw new java.io.NotSerializableException(
          "A local presentation observation cannot be deserialized");
    }
  }

  enum Kind {
    TEXT,
    ACTION,
    FIELD,
    OPEN,
    CLOSE
  }

  record Row(Node node, Kind kind, Component label, Component body, UiAvailability availability) {}

  private record Keyed<I>(String key, ViewStateKey.Snapshot<I> value) {}

  private record Vector<I>(long revision, List<Keyed<I>> items) {}

  private final class ForMount<I> {
    private final Node owner;
    private final ViewSpec.ForEach<I> spec;
    private final ViewStateKey<I> itemKey;
    private final ViewStateKey<Integer> indexKey;
    private LinkedHashMap<String, Entry> entries = new LinkedHashMap<>();
    private long revision = -1;
    private RuntimeException problem;

    private final class Entry {
      final UiScope scope;
      final UiLocalPair data;
      final Node node;
      final UiRowFailure failure;

      private final class UiLocalPair {
        final design.inherent.reform.presentation.ui.UiLocal<I> item;
        final design.inherent.reform.presentation.ui.UiLocal<Integer> index;

        UiLocalPair(UiScope scope, Keyed<I> current, int position) {
          item = scope.local(itemKey, current.value().value());
          index = scope.local(indexKey, position);
        }
      }

      Entry(Keyed<I> current, int position) {
        var admitted = owner.scope.child();
        Node content;
        UiLocalPair values;
        UiRowFailure problem = null;
        try {
          values = new UiLocalPair(admitted.child(), current, position);
          content =
              new Node(
                  spec.row().build(admitted, current.key(), values.item, values.index), admitted);
        } catch (RuntimeException failed) {
          cleanup(admitted, failed);
          // A cleanup failure cannot be recast as a usable fallback or a successful retirement.
          if (admitted.releaseFailure().isPresent()) throw failed;
          var report =
              new PolicyRefusal(
                      "ui.row_failed",
                      "This item could not be shown. Update the list to try again.")
                  .report();
          problem = new UiRowFailure(owner.id, current.key(), report, Optional.of(failed));
          admitted = owner.scope.child();
          values = null;
          try {
            content =
                new Node(
                    Views.text(
                        "unavailable",
                        Component.translatableWithFallback(
                            "reform.ui.item_unavailable", "Item unavailable"),
                        report(report)),
                    admitted);
          } catch (RuntimeException | Error fallbackFailed) {
            fallbackFailed.addSuppressed(failed);
            cleanup(admitted, fallbackFailed);
            throw fallbackFailed;
          }
        } catch (Error failed) {
          cleanup(admitted, failed);
          throw failed;
        }
        scope = admitted;
        data = values;
        node = content;
        failure = problem;
      }
    }

    ForMount(Node owner, ViewSpec.ForEach<I> spec) {
      this.owner = owner;
      this.spec = spec;
      itemKey = new ViewStateKey<>(DocumentId.parse("reform:ui_row_item"), spec.itemType());
      indexKey =
          new ViewStateKey<>(
              DocumentId.parse("reform:ui_row_index"),
              ValueTypes.integer(0, spec.maximumRows() - 1));
    }

    void reconcile() {
      try {
        applyVector();
        problem = null;
      } catch (RuntimeException failed) {
        problem = failed;
      }
    }

    private void applyVector() {
      final Vector<I> source;
      try {
        source =
            root.project(
                List.of(spec.items()),
                () -> {
                  var observation = spec.items().snapshot();
                  if (observation
                      instanceof
                      design.inherent.reform.presentation.ui.UiObservation.Unavailable<List<I>>
                          missing) throw new ReadUnavailable(missing);
                  if (observation.revision() == revision) return new Vector<I>(revision, List.of());
                  var values = observation.value();
                  if (values.size() > spec.maximumRows())
                    throw new PolicyRefusal(
                        "ui.row_limit", "This list exceeds its declared view allowance.");
                  var keys = new HashSet<String>();
                  var next = new ArrayList<Keyed<I>>(values.size());
                  for (var item : values) {
                    String key = spec.itemKey().apply(item);
                    if (key == null || key.isBlank() || key.length() > 256)
                      throw new PolicyRefusal(
                          "ui.row_key", "Every item needs a bounded stable key.");
                    if (!keys.add(key))
                      throw new PolicyRefusal(
                          "ui.duplicate_row_key", "This list contains duplicate item keys.");
                    next.add(new Keyed<>(key, itemKey.capture(item)));
                  }
                  return new Vector<>(observation.revision(), List.copyOf(next));
                });
      } catch (ReadUnavailable missing) {
        var retired = entries;
        entries = new LinkedHashMap<>();
        owner.children.clear();
        revision = -1;
        for (var entry : retired.values()) cleanup(entry.scope, missing);
        throw missing;
      }
      if (source.revision() == revision) return;
      var next = new LinkedHashMap<String, Entry>();
      var created = new ArrayList<Entry>();
      try {
        for (int i = 0; i < source.items().size(); i++) {
          var current = source.items().get(i);
          var entry = entries.get(current.key());
          if (entry == null || entry.failure != null) {
            entry = new Entry(current, i);
            created.add(entry);
          }
          next.put(current.key(), entry);
        }
        root.update(
            () -> {
              int position = 0;
              for (var current : source.items()) {
                var entry = next.get(current.key());
                if (entry.data != null) {
                  entry.data.item.replace(current.value().value());
                  entry.data.index.replace(position);
                }
                position++;
              }
            });
      } catch (RuntimeException | Error failed) {
        for (var entry : created) cleanup(entry.scope, failed);
        throw failed;
      }
      var previous = entries;
      entries = next;
      owner.children.clear();
      for (var entry : next.values()) owner.children.add(entry.node);
      revision = source.revision();
      Throwable retirement = null;
      for (var old : previous.entrySet()) {
        if (next.get(old.getKey()) == old.getValue()) continue;
        try {
          old.getValue().scope.close();
        } catch (RuntimeException | Error failed) {
          if (retirement == null) retirement = failed;
          else if (retirement != failed) retirement.addSuppressed(failed);
        }
      }
      if (retirement instanceof RuntimeException failed) throw failed;
      if (retirement instanceof Error failed) throw failed;
    }

    void failures(List<UiRowFailure> into) {
      for (var entry : entries.values()) if (entry.failure != null) into.add(entry.failure);
    }
  }

  final class Node {
    final ViewSpec spec;
    final UiScope scope;
    final String id;
    final List<Node> children = new ArrayList<>();
    UiScope branchScope;
    Node branch;
    String selectedBranch;
    UiActionResult lastResult;
    ForMount<?> repeated;
    final List<UiRead<?>> reads;
    private Row cachedRow;
    private long[] observed;

    Node(ViewSpec spec, UiScope parent) {
      this.spec = Objects.requireNonNull(spec);
      scope = parent.child();
      id = nativePrefix + (sequence = Math.incrementExact(sequence));
      try {
        var declared = new ArrayList<UiRead<?>>();
        switch (spec) {
          case ViewSpec.Text text -> {
            add(declared, text.title().dependencies());
            add(declared, text.body().dependencies());
          }
          case ViewSpec.Action action -> {
            add(declared, action.label().dependencies());
            add(declared, action.help().dependencies());
            add(declared, action.command().dependencies());
          }
          case ViewSpec.Section section -> add(declared, section.title().dependencies());
          case ViewSpec.FieldPart part -> {
            add(declared, List.of(part.field().state()));
            add(declared, part.field().labelText().dependencies());
            add(declared, part.field().descriptionText().dependencies());
          }
          default -> {}
        }
        reads = List.copyOf(declared);
        scope.onRetire(
            () -> {
              cachedRow = null;
              observed = null;
            });
        switch (spec) {
          case ViewSpec.Column column -> construct(column.children());
          case ViewSpec.Section section -> construct(section.children());
          case ViewSpec.Component<?> component -> children.add(component(component));
          case ViewSpec.ForEach<?> each -> repeated = new ForMount<>(this, each);
          case ViewSpec.Repeat repeat -> {
            var type =
                ValueTypes.integer(
                    repeat.from(), Math.addExact(repeat.from(), repeat.maximumSlots() - 1));
            var indices =
                scope.derive(
                    new ViewStateKey<>(
                        DocumentId.parse("reform:ui_repeat_slots"),
                        ValueTypes.list(type, repeat.maximumSlots())),
                    List.of(repeat.count()),
                    inputs -> {
                      int count = inputs.value(repeat.count());
                      if (count < 0 || count > repeat.maximumSlots())
                        throw new PolicyRefusal(
                            "ui.slot_limit",
                            "The decorative slot count exceeds its declared allowance.");
                      var values = new ArrayList<Integer>(count);
                      for (int i = 0; i < count; i++) values.add(repeat.from() + i);
                      return List.copyOf(values);
                    });
            repeated =
                new ForMount<>(
                    this,
                    new ViewSpec.ForEach<>(
                        repeat.key(),
                        indices,
                        type,
                        repeat.maximumSlots(),
                        Object::toString,
                        (slot, key, item, index) ->
                            repeat.row().build(slot, item.snapshot().value())));
          }
          default -> {}
        }
      } catch (RuntimeException | Error failed) {
        cleanup(scope, failed);
        throw failed;
      }
    }

    private <P> Node component(ViewSpec.Component<P> component) {
      return new Node(component.factory().build(scope, component.props()), scope);
    }

    private void construct(List<ViewSpec> descriptions) {
      for (var description : descriptions) children.add(new Node(description, scope));
    }

    void reconcile() {
      if (repeated != null) repeated.reconcile();
      if (spec instanceof ViewSpec.Show show) {
        boolean selected =
            root.project(
                List.of(show.test()),
                () -> {
                  var current = show.test().snapshot();
                  if (current
                      instanceof
                      design.inherent.reform.presentation.ui.UiObservation.Unavailable<Boolean>
                          missing) throw new ReadUnavailable(missing);
                  return current.value();
                });
        selectBranch(selected ? "yes" : "no", selected ? show.yes() : show.no());
      } else if (spec instanceof ViewSpec.Switch choice) {
        var dependencies = new ArrayList<UiRead<?>>();
        for (var candidate : choice.cases()) add(dependencies, List.of(candidate.test()));
        var selected =
            root.project(
                dependencies,
                () -> {
                  ViewSpec.Case first = null;
                  for (var candidate : choice.cases()) {
                    var current = candidate.test().snapshot();
                    if (current
                        instanceof
                        design.inherent.reform.presentation.ui.UiObservation.Unavailable<Boolean>
                            missing) throw new ReadUnavailable(missing);
                    if (!current.value()) continue;
                    if (first != null)
                      throw new PolicyRefusal(
                          "ui.ambiguous_case", "More than one exclusive view case is selected.");
                    first = candidate;
                    if (!choice.exclusive()) break;
                  }
                  return first;
                });
        selectBranch(
            selected == null ? "" : selected.key(),
            selected == null ? choice.fallback() : selected.content());
      }
      if (branch != null) branch.reconcile();
      for (var child : children) child.reconcile();
    }

    private void selectBranch(String key, ViewSpec.Branch factory) {
      if (Objects.equals(selectedBranch, key)) return;
      var nextScope = scope.child();
      final Node next;
      try {
        next = new Node(factory.build(nextScope), nextScope);
        next.reconcile();
      } catch (RuntimeException | Error failed) {
        cleanup(nextScope, failed);
        throw failed;
      }
      var previous = branchScope;
      branchScope = nextScope;
      branch = next;
      selectedBranch = key;
      if (previous != null) previous.close();
    }

    void failures(List<UiRowFailure> into) {
      if (repeated != null) repeated.failures(into);
      for (var child : children) child.failures(into);
      if (branch != null) branch.failures(into);
    }

    Optional<RuntimeException> collectionFailure() {
      if (repeated != null && repeated.problem != null) return Optional.of(repeated.problem);
      for (var child : children) {
        var failed = child.collectionFailure();
        if (failed.isPresent()) return failed;
      }
      return branch == null ? Optional.empty() : branch.collectionFailure();
    }

    void inputs(List<UiRead<?>> into) {
      add(into, reads);
      for (var child : children) child.inputs(into);
      if (branch != null) branch.inputs(into);
    }

    void clearProjectedRows() {
      cachedRow = null;
      observed = null;
      for (var child : children) child.clearProjectedRows();
      if (branch != null) branch.clearProjectedRows();
    }

    void rows(List<Row> into) {
      rows(into, UiAvailability.READY);
    }

    void rows(List<Row> into, UiAvailability inherited) {
      var childrenAvailability = inherited;
      if (repeated != null && repeated.problem != null) {
        var diagnosis = diagnostic(repeated.problem);
        childrenAvailability = new UiAvailability(false, diagnosis);
        into.add(
            new Row(
                this,
                Kind.TEXT,
                Component.translatableWithFallback(
                    "reform.ui.list_unavailable", "This list could not update."),
                report(diagnosis),
                UiAvailability.READY));
      }
      var current = projectedRow();
      if (current != null) {
        if ((current.kind() == Kind.ACTION || current.kind() == Kind.FIELD)
            && !inherited.available())
          current = new Row(this, current.kind(), current.label(), current.body(), inherited);
        into.add(current);
      }
      for (var child : children) child.rows(into, childrenAvailability);
      if (branch != null) branch.rows(into, childrenAvailability);
      if (spec instanceof ViewSpec.Section)
        into.add(
            new Row(this, Kind.CLOSE, Component.empty(), Component.empty(), UiAvailability.READY));
    }

    private Row projectedRow() {
      var revisions = new long[reads.size()];
      for (int i = 0; i < reads.size(); i++) revisions[i] = reads.get(i).snapshot().revision();
      if (observed != null && java.util.Arrays.equals(observed, revisions)) return cachedRow;
      var next =
          switch (spec) {
            case ViewSpec.Text text ->
                new Row(
                    this,
                    Kind.TEXT,
                    text.title().resolve(),
                    text.body().resolve(),
                    UiAvailability.READY);
            case ViewSpec.Action action ->
                new Row(
                    this,
                    Kind.ACTION,
                    action.label().resolve(),
                    action.help().resolve(),
                    Objects.requireNonNull(action.command().availability()));
            case ViewSpec.FieldPart part -> {
              var field = part.field();
              var state = field.state().snapshot().value();
              yield switch (part.role()) {
                case LABEL ->
                    new Row(
                        this,
                        Kind.TEXT,
                        field.labelText().resolve(),
                        Component.empty(),
                        UiAvailability.READY);
                case DESCRIPTION ->
                    new Row(
                        this,
                        Kind.TEXT,
                        Component.empty(),
                        field.descriptionText().resolve(),
                        UiAvailability.READY);
                case FEEDBACK ->
                    new Row(
                        this,
                        Kind.TEXT,
                        Component.empty(),
                        report(state.validation()),
                        UiAvailability.READY);
                case CONTROL ->
                    new Row(
                        this,
                        Kind.FIELD,
                        field.labelText().resolve(),
                        Component.empty(),
                        UiAvailability.READY);
              };
            }
            case ViewSpec.Section section ->
                new Row(
                    this,
                    Kind.OPEN,
                    section.title().resolve(),
                    Component.empty(),
                    UiAvailability.READY);
            default -> null;
          };
      cachedRow = next;
      observed = revisions;
      return next;
    }
  }

  private final String nativePrefix = "ui:" + java.util.UUID.randomUUID() + ":";
  private final UiScope root;
  private Node tree;
  private long sequence, revision = -1;
  private Object attachment;
  private List<Row> rows = List.of();
  private RuntimeException failure;
  private ClientFieldParts<?> requiredField;
  private UiActionResult lastAction;

  private MountedUiTree(ClientViewContext context, UiLimits limits) {
    this(context.openScope(limits));
  }

  private MountedUiTree(UiScope admitted) {
    root = admitted;
    root.onRetire(
        () -> {
          attachment = null;
          tree = null;
          rows = List.of();
          requiredField = null;
        });
  }

  static <P> MountedUiTree open(
      ClientViewContext context, UiLimits limits, UiComponent<P> component, P props) {
    var mounted = new MountedUiTree(context, limits);
    try {
      mounted.tree =
          mounted
          .new Node(
              Objects.requireNonNull(component).build(mounted.root, Objects.requireNonNull(props)),
              mounted.root);
      mounted.refresh(true);
      return mounted;
    } catch (RuntimeException | Error failed) {
      cleanup(mounted.root, failed);
      throw failed;
    }
  }

  static MountedUiTree field(
      UiScope parent,
      ClientFieldComponent component,
      Function<UiScope, ClientFieldParts<?>> parts) {
    var mounted = new MountedUiTree(parent.child());
    try {
      mounted.requiredField = Objects.requireNonNull(parts.apply(mounted.root));
      mounted.tree =
          mounted.new Node(component.build(mounted.root, mounted.requiredField), mounted.root);
      mounted.refreshCached(true);
      return mounted;
    } catch (RuntimeException | Error failed) {
      cleanup(mounted.root, failed);
      throw failed;
    }
  }

  private void fieldParts(List<Row> rows) {
    var families =
        new java.util.IdentityHashMap<
            ClientFieldParts<?>, java.util.EnumSet<ClientFieldParts.Role>>();
    for (var row : rows) {
      if (!(row.node().spec instanceof ViewSpec.FieldPart part)) continue;
      if (part.field() != requiredField || !part.field().belongsTo(root))
        throw new PolicyRefusal(
            "ui.foreign_field_part", "Field parts must belong to this mounted field.");
      var roles =
          families.computeIfAbsent(
              part.field(), ignored -> java.util.EnumSet.noneOf(ClientFieldParts.Role.class));
      if (!roles.add(part.role()))
        throw new PolicyRefusal("ui.duplicate_field_part", "Use each field part only once.");
    }
    if (requiredField != null && !families.containsKey(requiredField))
      throw new PolicyRefusal(
          "ui.missing_field_part", "A field needs its label, control, and feedback parts.");
    for (var roles : families.values())
      if (!roles.containsAll(
          java.util.EnumSet.of(
              ClientFieldParts.Role.LABEL,
              ClientFieldParts.Role.CONTROL,
              ClientFieldParts.Role.FEEDBACK)))
        throw new PolicyRefusal(
            "ui.missing_field_part", "A field needs its label, control, and feedback parts.");
  }

  private void add(List<UiRead<?>> target, List<UiRead<?>> inputs) {
    if (inputs.size() > root.limits().maximumDependencies() - target.size())
      throw new PolicyRefusal(
          "ui.budget_exceeded", "The component's declared input allowance is full");
    target.addAll(inputs);
  }

  boolean refresh(boolean force) {
    return refresh(force, true);
  }

  boolean refreshCached(boolean force) {
    return refresh(force, false);
  }

  private boolean refresh(boolean force, boolean observe) {
    root.requireCurrent();
    if (observe) root.refreshObservations();
    if (!force && root.revision() == revision) return false;
    attachment = null;
    try {
      tree.reconcile();
      var inputs = new ArrayList<UiRead<?>>();
      tree.inputs(inputs);
      rows =
          root.project(
              inputs,
              () -> {
                for (var input : inputs)
                  if (input.snapshot()
                      instanceof
                      design.inherent.reform.presentation.ui.UiObservation.Unavailable<?> missing)
                    throw new ReadUnavailable(missing);
                var current = new ArrayList<Row>();
                tree.rows(current);
                fieldParts(current);
                return List.copyOf(current);
              });
      failure = null;
    } catch (RuntimeException failed) {
      // Initial admission failures must not cache a partially constructed destination.
      if (revision < 0 && !(failed instanceof ReadUnavailable)) throw failed;
      failure = failed;
      if (failed instanceof ReadUnavailable) {
        // Permission/context loss is not a stale-content offer. Remove previously disclosed values
        // and controls without recreating the actual owner or source.
        rows = List.of();
        tree.clearProjectedRows();
        revision = root.revision();
        return true;
      }
      var blocked =
          new UiAvailability(
              false,
              failed instanceof PolicyRefusal refused
                  ? refused.report()
                  : new PolicyRefusal(
                          "ui.projection_failed", "This view could not update. Try again.")
                      .report());
      rows =
          rows.stream()
              .map(
                  row ->
                      (row.kind() == Kind.ACTION || row.kind() == Kind.FIELD)
                          ? new Row(row.node(), row.kind(), row.label(), row.body(), blocked)
                          : row)
              .toList();
    }
    revision = root.revision();
    return true;
  }

  void hide() {
    attachment = null;
  }

  boolean isOpen() {
    return root.isOpen();
  }

  UiScope scope() {
    return root;
  }

  List<Row> snapshot() {
    root.requireCurrent();
    return rows;
  }

  Optional<RuntimeException> failure() {
    if (failure != null) return Optional.of(failure);
    return tree == null ? Optional.empty() : tree.collectionFailure();
  }

  Optional<UiActionResult> lastAction() {
    return Optional.ofNullable(lastAction);
  }

  List<UiRowFailure> rowFailures() {
    root.requireCurrent();
    var failures = new ArrayList<UiRowFailure>();
    tree.failures(failures);
    return List.copyOf(failures);
  }

  void append(
      FormPanel panel,
      ClientTheme theme,
      Function<Runnable, Runnable> hostGuard,
      Runnable changed,
      Runnable focusForAction) {
    root.requireCurrent();
    Object token = new Object();
    attachment = token;
    if (failure != null)
      panel.row(
          nativePrefix + "failure",
          failure instanceof ReadUnavailable
              ? Component.translatableWithFallback(
                  "reform.ui.content_unavailable", "This content is unavailable.")
              : Component.translatableWithFallback(
                  "reform.ui.refresh_failed", "This view could not update. Try again."),
          report(
              failure instanceof ReadUnavailable missing
                  ? missing.observation.report()
                  : failure instanceof PolicyRefusal refused
                      ? refused.report()
                      : ValidationReport.VALID));
    for (var row : rows) {
      switch (row.kind()) {
        case TEXT -> {
          if (!(row.node().spec instanceof ViewSpec.FieldPart)
              || !row.label().getString().isBlank()
              || !row.body().getString().isBlank())
            panel.anchorRow(row.node().id, row.label(), row.body());
        }
        case FIELD -> {
          var part = (ViewSpec.FieldPart) row.node().spec;
          var widgets = part.field().widgets();
          if (failure != null || !row.availability().available()) {
            for (var widget : widgets) {
              widget.active = false;
              if (widget instanceof FormEditBox input) input.setEditable(false);
            }
          }
          panel.field(
              row.node().id,
              Component.empty(),
              row.availability().available()
                  ? Component.empty()
                  : report(row.availability().report()),
              widgets.toArray(net.minecraft.client.gui.components.AbstractWidget[]::new));
        }
        case OPEN -> panel.beginGroup(row.node().id, row.label());
        case CLOSE -> panel.endGroup();
        case ACTION -> {
          var body = row.body().copy();
          if (!row.availability().available()) join(body, report(row.availability().report()));
          if (row.node().lastResult instanceof UiActionResult.Refused refused)
            join(body, report(refused.report()));
          var button =
              ClientControls.action(
                      Minecraft.getInstance().font,
                      row.label(),
                      hostGuard.apply(
                          () -> {
                            if (!root.isOpen() || attachment != token || !row.node().scope.isOpen())
                              return;
                            if (refresh(false)) {
                              if (root.isOpen()) changed.run();
                              return;
                            }
                            if (!row.availability().available()) return;
                            panel.restoreFocus(row.node().id);
                            focusForAction.run();
                            invoke(row.node());
                            if (root.isOpen()) changed.run();
                          }),
                      theme)
                  .build();
          button.active =
              failure == null && row.node().scope.isOpen() && row.availability().available();
          if (!row.availability().available())
            button.setTooltip(Tooltip.create(report(row.availability().report())));
          panel.row(row.node().id, Component.empty(), body, button);
        }
      }
    }
  }

  private void invoke(Node node) {
    var action = (ViewSpec.Action) node.spec;
    var available = root.project(action.command().dependencies(), action.command()::availability);
    node.lastResult =
        available.available()
            ? Objects.requireNonNull(action.command().invoke(null))
            : new UiActionResult.Refused(available.report(), Optional.empty());
    lastAction = node.lastResult;
    refresh(true);
  }

  private static void join(net.minecraft.network.chat.MutableComponent target, Component added) {
    if (added.getString().isBlank()) return;
    if (!target.getString().isBlank()) target.append("\n");
    target.append(added);
  }

  private static Component report(ValidationReport report) {
    return report.issues().isEmpty()
        ? Component.empty()
        : Component.literal(report.issues().getFirst().fallback());
  }

  private static ValidationReport diagnostic(RuntimeException failed) {
    if (failed instanceof ReadUnavailable missing) return missing.observation.report();
    if (failed instanceof PolicyRefusal refused) return refused.report();
    return new PolicyRefusal("ui.collection_failed", "This list could not update. Try again.")
        .report();
  }

  private static void cleanup(UiScope scope, Throwable cause) {
    try {
      scope.close();
    } catch (RuntimeException | Error failed) {
      if (failed != cause) cause.addSuppressed(failed);
    }
  }

  @Override
  public void close() {
    root.close();
  }
}
