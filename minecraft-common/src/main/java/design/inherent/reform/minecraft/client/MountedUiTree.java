package design.inherent.reform.minecraft.client;

import design.inherent.reform.minecraft.client.ui.UiComponent;
import design.inherent.reform.minecraft.client.ui.ViewSpec;
import design.inherent.reform.owner.PolicyRefusal;
import design.inherent.reform.presentation.ui.UiActionResult;
import design.inherent.reform.presentation.ui.UiAvailability;
import design.inherent.reform.presentation.ui.UiLimits;
import design.inherent.reform.presentation.ui.UiRead;
import design.inherent.reform.presentation.ui.UiScope;
import design.inherent.reform.validation.ValidationReport;
import java.util.ArrayList;
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
    OPEN,
    CLOSE
  }

  record Row(Node node, Kind kind, Component label, Component body, UiAvailability availability) {}

  final class Node {
    final ViewSpec spec;
    final UiScope scope;
    final String id;
    final List<Node> children = new ArrayList<>();
    UiScope branchScope;
    Node branch;
    Boolean condition;
    UiActionResult lastResult;

    Node(ViewSpec spec, UiScope parent) {
      this.spec = Objects.requireNonNull(spec);
      scope = parent.child();
      id = "ui:" + (sequence = Math.incrementExact(sequence));
      try {
        switch (spec) {
          case ViewSpec.Column column -> construct(column.children());
          case ViewSpec.Section section -> construct(section.children());
          case ViewSpec.Component<?> component -> children.add(component(component));
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
        if (condition == null || condition != selected) {
          var nextScope = scope.child();
          final Node next;
          try {
            next = new Node((selected ? show.yes() : show.no()).build(nextScope), nextScope);
            next.reconcile();
          } catch (RuntimeException | Error failed) {
            cleanup(nextScope, failed);
            throw failed;
          }
          var previous = branchScope;
          branchScope = nextScope;
          branch = next;
          condition = selected;
          if (previous != null) previous.close();
        }
        branch.reconcile();
      }
      for (var child : children) child.reconcile();
    }

    void inputs(List<UiRead<?>> into) {
      switch (spec) {
        case ViewSpec.Text text -> {
          add(into, text.title().dependencies());
          add(into, text.body().dependencies());
        }
        case ViewSpec.Action action -> {
          add(into, action.label().dependencies());
          add(into, action.help().dependencies());
          add(into, action.command().dependencies());
        }
        case ViewSpec.Section section -> add(into, section.title().dependencies());
        default -> {}
      }
      for (var child : children) child.inputs(into);
      if (branch != null) branch.inputs(into);
    }

    void rows(List<Row> into) {
      switch (spec) {
        case ViewSpec.Text text ->
            into.add(
                new Row(
                    this,
                    Kind.TEXT,
                    text.title().resolve(),
                    text.body().resolve(),
                    UiAvailability.READY));
        case ViewSpec.Action action ->
            into.add(
                new Row(
                    this,
                    Kind.ACTION,
                    action.label().resolve(),
                    action.help().resolve(),
                    Objects.requireNonNull(action.command().availability())));
        case ViewSpec.Section section ->
            into.add(
                new Row(
                    this,
                    Kind.OPEN,
                    section.title().resolve(),
                    Component.empty(),
                    UiAvailability.READY));
        default -> {}
      }
      for (var child : children) child.rows(into);
      if (branch != null) branch.rows(into);
      if (spec instanceof ViewSpec.Section)
        into.add(
            new Row(this, Kind.CLOSE, Component.empty(), Component.empty(), UiAvailability.READY));
    }
  }

  private final UiScope root;
  private Node tree;
  private long sequence, revision = -1;
  private Object attachment;
  private List<Row> rows = List.of();
  private RuntimeException failure;
  private UiActionResult lastAction;

  private MountedUiTree(ClientViewContext context, UiLimits limits) {
    root = context.openScope(limits);
    root.onRetire(
        () -> {
          attachment = null;
          tree = null;
          rows = List.of();
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

  private void add(List<UiRead<?>> target, List<UiRead<?>> inputs) {
    if (inputs.size() > root.limits().maximumDependencies() - target.size())
      throw new PolicyRefusal(
          "ui.budget_exceeded", "The component's declared input allowance is full");
    target.addAll(inputs);
  }

  boolean refresh(boolean force) {
    root.requireCurrent();
    root.refreshObservations();
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
                      row.kind() == Kind.ACTION
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
    return Optional.ofNullable(failure);
  }

  Optional<UiActionResult> lastAction() {
    return Optional.ofNullable(lastAction);
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
          "ui:failure",
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
        case TEXT -> panel.anchorRow(row.node().id, row.label(), row.body());
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
