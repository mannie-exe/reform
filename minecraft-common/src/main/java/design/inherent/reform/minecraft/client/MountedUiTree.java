package design.inherent.reform.minecraft.client;

import design.inherent.reform.definition.DocumentId;
import design.inherent.reform.minecraft.client.ui.DisclosureParts;
import design.inherent.reform.minecraft.client.ui.UiComponent;
import design.inherent.reform.minecraft.client.ui.UiDisclosureFailure;
import design.inherent.reform.minecraft.client.ui.UiRowFailure;
import design.inherent.reform.minecraft.client.ui.ViewSpec;
import design.inherent.reform.minecraft.client.ui.Views;
import design.inherent.reform.owner.PolicyRefusal;
import design.inherent.reform.presentation.ViewStateKey;
import design.inherent.reform.presentation.ui.UiActionResult;
import design.inherent.reform.presentation.ui.UiAvailability;
import design.inherent.reform.presentation.ui.UiBindings;
import design.inherent.reform.presentation.ui.UiCommand;
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
    DISCLOSURE,
    CHOICE_LIST,
    QUERY_INPUT,
    TOGGLE,
    PREVIEW,
    COMPLETION,
    SURFACE,
    FIELD,
    LAYOUT_ROW,
    LAYOUT_FRAME,
    LAYOUT_CELL,
    LAYOUT_END,
    OPEN,
    CLOSE
  }

  record Row(
      Node node,
      Kind kind,
      Component label,
      Component body,
      UiAvailability availability,
      Node flow,
      boolean sectionAfter,
      Object projection) {
    Row(Node node, Kind kind, Component label, Component body, UiAvailability availability) {
      this(node, kind, label, body, availability, null, false, new Object());
    }

    Row withFlow(Node owner) {
      return new Row(node, kind, label, body, availability, owner, sectionAfter, projection);
    }

    Row endDisclosure() {
      return new Row(node, kind, label, body, availability, flow, true, projection);
    }

    Row blocked(UiAvailability unavailable) {
      return new Row(node, kind, label, body, unavailable, flow, sectionAfter, projection);
    }
  }

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
    private boolean expanded;
    private boolean expansionObserved;
    private UiDisclosureFailure disclosureFailure;
    private List<Row> disclosureRows = List.of();
    private final java.util.Set<String> collapsedIds = new HashSet<>();
    private UiCommand<Void> disclosureCommand;
    private ChoiceListWidget listWidget;
    private Optional<String> listAnchor = Optional.empty();
    private NativeQueryInput queryWidget;
    private ClientIdCompletion completion;
    private design.inherent.reform.minecraft.client.ui.ChoiceListData listData;
    private Optional<String> listActive = Optional.empty();
    private java.util.Set<String> listAccepted = java.util.Set.of();
    private design.inherent.reform.presentation.ui.PickerState.Query queryValue;
    private boolean lifecycleActive;
    private boolean toggleValue;
    private PreviewController<?, ?>.Slot previewSlot;
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
          case ViewSpec.ChoiceList list -> {
            var parts = list.parts();
            add(declared, parts.label().dependencies());
            add(declared, parts.help().dependencies());
            add(declared, List.of(parts.data(), parts.active().value(), parts.accepted()));
            add(declared, parts.active().changes().dependencies());
            add(declared, parts.accept().dependencies());
            parts.paging().ifPresent(command -> add(declared, command.dependencies()));
            parts
                .viewport()
                .ifPresent(
                    binding -> {
                      add(declared, List.of(binding.value()));
                      add(declared, binding.changes().dependencies());
                    });
          }
          case ViewSpec.QueryInput query -> {
            var parts = query.parts();
            add(declared, parts.label().dependencies());
            add(declared, parts.help().dependencies());
            add(declared, List.of(parts.value()));
            add(declared, parts.changes().dependencies());
            add(declared, parts.submit().dependencies());
          }
          case ViewSpec.Toggle toggle -> {
            var parts = toggle.parts();
            add(declared, parts.label().dependencies());
            add(declared, parts.help().dependencies());
            add(declared, List.of(parts.binding().value()));
            add(declared, parts.binding().changes().dependencies());
          }
          case ViewSpec.Preview preview -> {
            add(declared, List.of(preview.controller().state()));
            previewSlot = preview.controller().attach();
          }
          case ViewSpec.Disclosure disclosure -> {
            disclosureCommand = UiBindings.toggle(disclosure.expanded());
            add(declared, disclosureCommand.dependencies());
            add(declared, disclosure.parts().label().dependencies());
            add(declared, disclosure.parts().description().dependencies());
            add(declared, disclosure.parts().collapsedSummary().dependencies());
          }
          case ViewSpec.Section section -> add(declared, section.title().dependencies());
          case ViewSpec.FieldPart part -> {
            add(declared, List.of(part.field().state()));
            add(declared, part.field().labelText().dependencies());
            add(declared, part.field().descriptionText().dependencies());
          }
          case ViewSpec.FieldSurface surface ->
              add(declared, List.of(surface.surface().field().state()));
          case ViewSpec.NativeFieldInput input ->
              add(declared, List.of(input.surface().field().state()));
          case ViewSpec.FieldCompletion input -> {
            add(declared, List.of(input.surface().field().state()));
            add(declared, input.parts().label().dependencies());
          }
          default -> {}
        }
        reads = List.copyOf(declared);
        scope.onRetire(
            () -> {
              suspend();
              if (previewSlot != null) previewSlot.close();
              if (completion != null) completion.close();
              if (listWidget != null) listWidget.clear();
              if (queryWidget != null) {
                queryWidget.changed = () -> {};
                queryWidget.submit = () -> {};
                queryWidget.setValue("");
              }
              cachedRow = null;
              observed = null;
              disclosureRows = List.of();
              collapsedIds.clear();
              disclosureFailure = null;
            });
        switch (spec) {
          case ViewSpec.Managed managed -> children.add(new Node(managed.content(), scope));
          case ViewSpec.FieldSurface surface ->
              children.add(new Node(surface.surface().content(), scope));
          case ViewSpec.Column column -> construct(column.children());
          case ViewSpec.Row row -> construct(row.children());
          case ViewSpec.Frame frame -> children.add(new Node(frame.content(), scope));
          case ViewSpec.Actions actions -> construct(actions.children());
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

    void beforeObserve() {
      retainInputs();
      updateManaged();
    }

    void retainInputs() {
      if (spec instanceof ViewSpec.QueryInput query && queryWidget != null && queryWidget.active)
        pollQuery(query);
      for (var child : children) child.retainInputs();
      if (branch != null && (!(spec instanceof ViewSpec.Disclosure) || expanded))
        branch.retainInputs();
    }

    private void updateManaged() {
      if (spec instanceof ViewSpec.ChoiceList list && listWidget != null)
        listWidget
            .takeBoundary()
            .ifPresent(
                boundary ->
                    list.parts()
                        .paging()
                        .ifPresent(
                            command -> {
                              if (root.project(command.dependencies(), command::availability)
                                  .available()) execute(this, command, boundary);
                            }));
      if (spec instanceof ViewSpec.Managed managed) {
        lifecycleActive = true;
        managed.lifecycle().update();
      }
      if (previewSlot != null) previewSlot.update();
      for (var child : children) child.updateManaged();
      if (branch != null && (!(spec instanceof ViewSpec.Disclosure) || expanded))
        branch.updateManaged();
    }

    private void pollQuery(ViewSpec.QueryInput query) {
      var next =
          new design.inherent.reform.presentation.ui.PickerState.Query(
              queryWidget.getValue(), queryWidget.getCursorPosition(), queryWidget.selection());
      if (!next.equals(queryValue)) {
        execute(this, query.parts().changes(), next);
        // This is the observed native buffer, not an optimistic domain edit acknowledgement.
        queryValue = next;
      }
    }

    void suspend() {
      if (previewSlot != null) previewSlot.suspend();
      if (completion != null) completion.suspend();
      if (lifecycleActive && spec instanceof ViewSpec.Managed managed) {
        lifecycleActive = false;
        managed.lifecycle().suspend();
      }
      if (listWidget != null) {
        if (spec instanceof ViewSpec.ChoiceList list && list.parts().viewport().isPresent())
          listWidget.clear();
        else listWidget.detach();
      }
      if (queryWidget != null) {
        queryWidget.changed = () -> {};
        queryWidget.submit = () -> {};
      }
      for (var child : children) child.suspend();
      if (branch != null) branch.suspend();
    }

    void reconcile() {
      if (spec instanceof ViewSpec.Disclosure disclosure) {
        reconcileDisclosure(disclosure);
        return;
      }
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

    private void reconcileDisclosure(ViewSpec.Disclosure disclosure) {
      boolean next =
          root.project(
              List.of(disclosure.expanded().value()),
              () -> {
                var value = disclosure.expanded().value().snapshot();
                if (value
                    instanceof
                    design.inherent.reform.presentation.ui.UiObservation.Unavailable<Boolean>
                        missing) throw new ReadUnavailable(missing);
                return value.value();
              });
      boolean changed = !expansionObserved || next != expanded;
      expansionObserved = true;
      expanded = next;
      if (!expanded) {
        if (changed) {
          collapsedIds.clear();
          if (branch != null) branch.identities(collapsedIds);
          if (branch != null) {
            branch.retainInputs();
            branch.suspend();
            branch.clearProjectedRows();
          }
          disclosureRows = List.of();
          disclosureFailure = null;
          if (disclosure.parts().retention() == DisclosureParts.Retention.DISPOSE
              && branchScope != null) {
            var previous = branchScope;
            branchScope = null;
            branch = null;
            selectedBranch = null;
            previous.close();
          }
        }
        return;
      }
      collapsedIds.clear();
      if (branch == null && !changed) return; // Explicit collapse/reopen retries failed admission.
      try {
        if (branch == null) selectBranch("content", disclosure.parts().content());
        branch.reconcile();
        var inputs = new ArrayList<UiRead<?>>();
        branch.inputs(inputs);
        disclosureRows =
            root.project(
                inputs,
                () -> {
                  requireAvailable(inputs);
                  var current = new ArrayList<Row>();
                  branch.rows(current);
                  return List.copyOf(current);
                });
        disclosureFailure = null;
      } catch (RuntimeException failed) {
        if (failed instanceof DisclosureRetirementFailure) throw failed;
        disclosureRows = List.of();
        if (branch != null) branch.clearProjectedRows();
        var report =
            failed instanceof ReadUnavailable missing
                ? missing.observation.report()
                : failed instanceof PolicyRefusal refused
                    ? refused.report()
                    : new PolicyRefusal(
                            "ui.disclosure_failed",
                            "This content could not be shown. Close and reopen it to try again.")
                        .report();
        disclosureFailure = new UiDisclosureFailure(id, report, Optional.of(failed));
      }
    }

    private void identities(java.util.Set<String> into) {
      into.add(id);
      for (var child : children) child.identities(into);
      if (branch != null) branch.identities(into);
    }

    void focusRedirects(FormPanel panel) {
      if (spec instanceof ViewSpec.Disclosure && !expanded) {
        for (var hidden : collapsedIds) panel.redirectFocus(hidden, id);
        return;
      }
      for (var child : children) child.focusRedirects(panel);
      if (branch != null) branch.focusRedirects(panel);
    }

    void disclosureFailures(List<UiDisclosureFailure> into) {
      if (disclosureFailure != null) into.add(disclosureFailure);
      if (spec instanceof ViewSpec.Disclosure && !expanded) return;
      for (var child : children) child.disclosureFailures(into);
      if (branch != null) branch.disclosureFailures(into);
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
        if (nextScope.releaseFailure().isPresent()) throw new DisclosureRetirementFailure(failed);
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
      if (branch != null && !(spec instanceof ViewSpec.Disclosure)) branch.inputs(into);
    }

    void clearProjectedRows() {
      cachedRow = null;
      observed = null;
      disclosureRows = List.of();
      listData = null;
      listActive = Optional.empty();
      listAccepted = java.util.Set.of();
      if (listWidget != null) listWidget.clear();
      if (queryWidget != null) {
        queryWidget.setValue("");
        queryValue = null;
      }
      for (var child : children) child.clearProjectedRows();
      if (branch != null) branch.clearProjectedRows();
    }

    void rows(List<Row> into) {
      rows(into, UiAvailability.READY);
    }

    void rows(List<Row> into, UiAvailability inherited) {
      rows(into, inherited, null);
    }

    void rows(List<Row> into, UiAvailability inherited, Node flow) {
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
        if ((current.kind() == Kind.ACTION
                || current.kind() == Kind.DISCLOSURE
                || current.kind() == Kind.FIELD
                || current.kind() == Kind.CHOICE_LIST
                || current.kind() == Kind.PREVIEW
                || current.kind() == Kind.TOGGLE
                || current.kind() == Kind.QUERY_INPUT
                || current.kind() == Kind.COMPLETION)
            && !inherited.available())
          current = new Row(this, current.kind(), current.label(), current.body(), inherited);
        into.add(current.withFlow(flow));
      }
      for (var child : children) {
        if (spec instanceof ViewSpec.Row)
          into.add(
              new Row(
                  child,
                  Kind.LAYOUT_CELL,
                  Component.empty(),
                  Component.empty(),
                  UiAvailability.READY));
        child.rows(into, childrenAvailability, spec instanceof ViewSpec.Actions ? this : null);
        if (spec instanceof ViewSpec.Row)
          into.add(
              new Row(
                  child,
                  Kind.LAYOUT_END,
                  Component.empty(),
                  Component.empty(),
                  UiAvailability.READY));
      }
      if (spec instanceof ViewSpec.Disclosure) {
        if (expanded) {
          if (disclosureFailure != null)
            into.add(
                new Row(
                    this,
                    Kind.TEXT,
                    Component.translatableWithFallback(
                        "reform.ui.disclosure_unavailable", "This content is unavailable."),
                    report(disclosureFailure.report()),
                    UiAvailability.READY));
          for (var row : disclosureRows) {
            if (!childrenAvailability.available()
                && (row.kind() == Kind.ACTION
                    || row.kind() == Kind.DISCLOSURE
                    || row.kind() == Kind.FIELD
                    || row.kind() == Kind.CHOICE_LIST
                    || row.kind() == Kind.PREVIEW
                    || row.kind() == Kind.TOGGLE
                    || row.kind() == Kind.QUERY_INPUT
                    || row.kind() == Kind.COMPLETION)) row = row.blocked(childrenAvailability);
            into.add(row);
          }
          if (!disclosureRows.isEmpty()) {
            int last = into.size() - 1;
            into.set(last, into.get(last).endDisclosure());
          }
        }
      } else if (branch != null) branch.rows(into, childrenAvailability);
      if (spec instanceof ViewSpec.Row || spec instanceof ViewSpec.Frame)
        into.add(
            new Row(
                this, Kind.LAYOUT_END, Component.empty(), Component.empty(), UiAvailability.READY));
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
            case ViewSpec.ChoiceList list -> {
              var parts = list.parts();
              listData = parts.data().snapshot().value();
              listActive = parts.active().value().snapshot().value();
              listAccepted = java.util.Set.copyOf(parts.accepted().snapshot().value());
              listAnchor =
                  parts
                      .viewport()
                      .map(binding -> binding.value().snapshot().value())
                      .orElse(Optional.empty());
              yield new Row(
                  this,
                  Kind.CHOICE_LIST,
                  parts.label().resolve(),
                  parts.help().resolve(),
                  parts.active().changes().availability());
            }
            case ViewSpec.QueryInput query -> {
              var parts = query.parts();
              queryValue = parts.value().snapshot().value();
              if (queryValue.text().length() > parts.maximumCharacters())
                throw new PolicyRefusal(
                    "ui.query_limit", "The query exceeds this control's declared text allowance.");
              yield new Row(
                  this,
                  Kind.QUERY_INPUT,
                  parts.label().resolve(),
                  parts.help().resolve(),
                  parts.changes().availability());
            }
            case ViewSpec.Toggle toggle -> {
              var parts = toggle.parts();
              toggleValue = parts.binding().value().snapshot().value();
              yield new Row(
                  this,
                  Kind.TOGGLE,
                  parts.label().resolve(),
                  parts.help().resolve(),
                  parts.binding().changes().availability());
            }
            case ViewSpec.Preview preview ->
                new Row(
                    this,
                    Kind.PREVIEW,
                    preview.controller().title(),
                    Component.empty(),
                    UiAvailability.READY);
            case ViewSpec.Disclosure disclosure -> {
              var label = disclosure.parts().label().resolve();
              var body = disclosure.parts().description().resolve().copy();
              if (!expanded) join(body, disclosure.parts().collapsedSummary().resolve());
              yield new Row(
                  this,
                  Kind.DISCLOSURE,
                  Component.translatableWithFallback(
                      expanded ? "reform.ui.disclosure_hide" : "reform.ui.disclosure_show",
                      expanded ? "Hide %s" : "Show %s",
                      label),
                  body,
                  disclosureCommand.availability());
            }
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
            case ViewSpec.Row ignored ->
                new Row(
                    this,
                    Kind.LAYOUT_ROW,
                    Component.empty(),
                    Component.empty(),
                    UiAvailability.READY);
            case ViewSpec.Frame ignored ->
                new Row(
                    this,
                    Kind.LAYOUT_FRAME,
                    Component.empty(),
                    Component.empty(),
                    UiAvailability.READY);
            case ViewSpec.Section section ->
                new Row(
                    this,
                    Kind.OPEN,
                    section.title().resolve(),
                    Component.empty(),
                    UiAvailability.READY);
            case ViewSpec.FieldSurface surface ->
                new Row(
                    this, Kind.SURFACE, Component.empty(), Component.empty(), UiAvailability.READY);
            case ViewSpec.FieldCompletion input ->
                new Row(
                    this,
                    Kind.COMPLETION,
                    input.parts().label().resolve(),
                    Component.empty(),
                    UiAvailability.READY);
            case ViewSpec.NativeFieldInput input ->
                new Row(
                    this,
                    Kind.FIELD,
                    input.surface().field().labelText().resolve(),
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
  private boolean attached;
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
    var surfaces =
        new java.util.IdentityHashMap<ClientFieldParts<?>, ClientFieldParts.Surface<?>>();
    var nativeSlots = new java.util.IdentityHashMap<ClientFieldParts.Surface<?>, Boolean>();
    var completionSlots = new java.util.IdentityHashMap<ClientFieldParts.Surface<?>, Boolean>();
    for (var row : rows) {
      ClientFieldParts<?> field;
      ClientFieldParts.Role role;
      if (row.node().spec instanceof ViewSpec.FieldPart part) {
        field = part.field();
        role = part.role();
      } else if (row.node().spec instanceof ViewSpec.FieldSurface surface) {
        field = surface.surface().field();
        role = ClientFieldParts.Role.CONTROL;
        surfaces.put(field, surface.surface());
      } else if (row.node().spec instanceof ViewSpec.FieldCompletion input) {
        field = input.surface().field();
        if (field != requiredField || !field.belongsTo(root))
          throw new PolicyRefusal(
              "ui.foreign_field_part", "Suggestions need their original field surface.");
        if (completionSlots.put(input.surface(), true) != null)
          throw new PolicyRefusal("ui.duplicate_field_part", "Use field suggestions only once.");
        continue;
      } else if (row.node().spec instanceof ViewSpec.NativeFieldInput input) {
        field = input.surface().field();
        if (field != requiredField || !field.belongsTo(root))
          throw new PolicyRefusal(
              "ui.foreign_field_part", "Field parts must belong to this mounted field.");
        if (nativeSlots.put(input.surface(), true) != null)
          throw new PolicyRefusal(
              "ui.duplicate_field_part", "Use the original native input only once.");
        continue;
      } else continue;
      if (field != requiredField || !field.belongsTo(root))
        throw new PolicyRefusal(
            "ui.foreign_field_part", "Field parts must belong to this mounted field.");
      var roles =
          families.computeIfAbsent(
              field, ignored -> java.util.EnumSet.noneOf(ClientFieldParts.Role.class));
      if (!roles.add(role))
        throw new PolicyRefusal("ui.duplicate_field_part", "Use each field part only once.");
    }
    for (var surface : nativeSlots.keySet())
      if (surfaces.get(surface.field()) != surface)
        throw new PolicyRefusal(
            "ui.foreign_field_part", "The native input needs its original field surface.");
    for (var surface : completionSlots.keySet())
      if (!nativeSlots.containsKey(surface) || surfaces.get(surface.field()) != surface)
        throw new PolicyRefusal(
            "ui.foreign_field_part",
            "Suggestions need their visible original input and field surface.");
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
    try {
      if (observe) {
        prepareRefresh();
        root.refreshObservations();
      }
      if (!force && root.revision() == revision) return false;
      attachment = null;
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
                // Geometry decoration must not retire an unchanged action/read projection.
                // The projection token still changes when its actual declared reads change.
                var prior = new java.util.HashMap<Row, Row>();
                for (var row : rows) prior.put(row, row);
                current.replaceAll(row -> prior.getOrDefault(row, row));
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
        tree.suspend();
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
                      (row.kind() == Kind.ACTION
                              || row.kind() == Kind.DISCLOSURE
                              || row.kind() == Kind.FIELD
                              || row.kind() == Kind.CHOICE_LIST
                              || row.kind() == Kind.PREVIEW
                              || row.kind() == Kind.TOGGLE
                              || row.kind() == Kind.QUERY_INPUT
                              || row.kind() == Kind.COMPLETION)
                          ? row.blocked(blocked)
                          : row)
              .toList();
    }
    revision = root.revision();
    return true;
  }

  void prepareRefresh() {
    root.requireCurrent();
    if (attached && tree != null) tree.beforeObserve();
  }

  void hide() {
    attached = false;
    attachment = null;
    if (tree != null) {
      tree.retainInputs();
      tree.suspend();
    }
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

  boolean nativeInputVisible() {
    root.requireCurrent();
    return attached
        && rows.stream()
            .anyMatch(row -> row.kind() == Kind.FIELD && row.availability().available());
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
    attached = true;
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
    tree.focusRedirects(panel);
    Node flow = null;
    var flowParts = new ArrayList<FormPanel.ActionPart>();
    for (var row : rows) {
      if (row.flow() != flow || (row.kind() != Kind.ACTION && row.kind() != Kind.DISCLOSURE)) {
        if (!flowParts.isEmpty()) {
          panel.actionFlow(flow.id, flowParts);
          flowParts.clear();
        }
        flow = row.flow();
      }
      switch (row.kind()) {
        case SURFACE -> {} // Ownership/semantics marker; its children supply the actual surface.
        case COMPLETION -> {
          var node = row.node();
          if (failure != null || !row.availability().available()) {
            if (node.completion != null) node.completion.suspend();
            break;
          }
          var spec = (ViewSpec.FieldCompletion) node.spec;
          if (node.completion == null)
            node.completion =
                new ClientIdCompletion(
                    node.scope, spec.surface().field().input().orElseThrow(), spec.parts());
          node.completion.attach(row.label(), theme);
          var feedback = node.completion.feedback();
          if (!feedback.getString().isBlank())
            panel.anchorRow(node.id + ":completion-feedback", Component.empty(), feedback);
        }
        case TEXT -> {
          if (!(row.node().spec instanceof ViewSpec.FieldPart)
              || !row.label().getString().isBlank()
              || !row.body().getString().isBlank())
            panel.anchorRow(row.node().id, row.label(), row.body());
        }
        case FIELD -> {
          var widgets =
              row.node().spec instanceof ViewSpec.NativeFieldInput input
                  ? input.surface().field().nativeWidgets()
                  : ((ViewSpec.FieldPart) row.node().spec).field().widgets();
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
        case CHOICE_LIST -> {
          var node = row.node();
          var parts = ((ViewSpec.ChoiceList) node.spec).parts();
          if (node.listWidget == null) node.listWidget = new ChoiceListWidget(row.label());
          var widget = node.listWidget;
          widget.setMessage(row.label());
          widget.active = failure == null && row.availability().available();
          var data = node.listData;
          widget.prepare(
              data,
              node.listActive,
              node.listAccepted,
              parts.visibleRows(),
              parts.overscan(),
              theme,
              key -> {
                var expectedActive = widget.observedActive();
                dispatch(
                    token,
                    node,
                    hostGuard,
                    changed,
                    () -> {
                      execute(node, parts.active().changes(), Optional.of(key));
                      if (node.lastResult instanceof UiActionResult.Applied)
                        parts
                            .viewport()
                            .ifPresent(
                                binding ->
                                    execute(node, binding.changes(), widget.observedAnchor()));
                    },
                    () ->
                        retainedChoice(node, data, key) && node.listActive.equals(expectedActive));
              },
              (key, keyboard) -> {
                var expectedActive = widget.observedActive();
                dispatch(
                    token,
                    node,
                    hostGuard,
                    changed,
                    () -> {
                      if (!retainedChoice(node, data, key)) return;
                      execute(node, parts.active().changes(), Optional.of(key));
                      if (root.isOpen()
                          && node.scope.isOpen()
                          && node.lastResult instanceof UiActionResult.Applied)
                        execute(node, parts.accept(), key);
                    },
                    () ->
                        retainedChoice(node, data, key)
                            && (!keyboard || node.listActive.equals(expectedActive)));
              });
          widget.viewport(
              node.listAnchor,
              () ->
                  parts
                      .viewport()
                      .ifPresent(
                          binding -> {
                            var anchor = widget.observedAnchor();
                            dispatch(
                                token,
                                node,
                                hostGuard,
                                changed,
                                () -> execute(node, binding.changes(), anchor),
                                () ->
                                    anchor
                                        .map(key -> retainedChoice(node, data, key))
                                        .orElse(false));
                          }));
          ControlSizing.offer(
              widget,
              theme
                  .values()
                  .value(design.inherent.reform.presentation.ThemeValues.Role.CONTROL_TEXT_INPUT),
              widget.getHeight());
          var help = row.body().copy();
          if (!row.availability().available()) join(help, report(row.availability().report()));
          if (node.lastResult instanceof UiActionResult.Refused refused)
            join(help, report(refused.report()));
          panel.blockField(node.id, row.label(), help, widget);
        }
        case TOGGLE -> {
          var node = row.node();
          var parts = ((ViewSpec.Toggle) node.spec).parts();
          var widget =
              net.minecraft.client.gui.components.Checkbox.builder(
                      theme.text(ClientTheme.TextRole.VALUE, row.label()),
                      Minecraft.getInstance().font)
                  .selected(node.toggleValue)
                  .maxWidth(Math.max(1, panel.contentWidth()))
                  .onValueChange(
                      (button, selected) ->
                          dispatch(
                              token,
                              node,
                              hostGuard,
                              changed,
                              () -> execute(node, parts.binding().changes(), selected),
                              () ->
                                  node.cachedRow != null
                                      && node.cachedRow.projection() == row.projection()))
                  .build();
          ClientControls.theme(widget, theme);
          widget.active = failure == null && row.availability().available();
          var help = row.body().copy();
          if (!row.availability().available()) join(help, report(row.availability().report()));
          if (node.lastResult instanceof UiActionResult.Refused refused)
            join(help, report(refused.report()));
          panel.blockField(node.id, Component.empty(), help, widget);
        }
        case PREVIEW -> {
          var slot = row.node().previewSlot;
          if (failure != null || !row.availability().available()) {
            slot.suspend();
            break;
          }
          slot.widget()
              .ifPresent(
                  widget -> {
                    int size =
                        theme
                            .values()
                            .value(
                                design.inherent.reform.presentation.ThemeValues.Role
                                    .MEASURE_GRAPHIC_PREFERRED);
                    ControlSizing.offer(
                        widget, size, Math.max(1, Math.min(size, panel.visibleRowHeight())));
                    panel.anchorRow(row.node().id, Component.empty(), Component.empty(), widget);
                  });
        }
        case QUERY_INPUT -> {
          var node = row.node();
          var query = (ViewSpec.QueryInput) node.spec;
          var parts = query.parts();
          if (node.queryWidget == null)
            node.queryWidget = new NativeQueryInput(Minecraft.getInstance().font, row.label());
          var widget = node.queryWidget;
          ClientControls.theme(widget, theme);
          widget.setMessage(row.label());
          widget.setMaxLength(parts.maximumCharacters());
          if (!widget.getValue().equals(node.queryValue.text()))
            widget.setValue(node.queryValue.text());
          widget.setCursorPosition(node.queryValue.caret());
          widget.setHighlightPos(node.queryValue.anchor());
          widget.active = failure == null && row.availability().available();
          widget.setEditable(widget.active);
          widget.changed =
              () -> dispatch(token, node, hostGuard, changed, () -> node.pollQuery(query));
          widget.submit =
              () ->
                  dispatch(
                      token,
                      node,
                      hostGuard,
                      changed,
                      () -> {
                        node.pollQuery(query);
                        execute(node, parts.submit(), null);
                      });
          var help = row.body().copy();
          if (!row.availability().available()) join(help, report(row.availability().report()));
          if (node.lastResult instanceof UiActionResult.Refused refused)
            join(help, report(refused.report()));
          panel.blockField(node.id, row.label(), help, widget);
        }
        case LAYOUT_ROW ->
            panel.beginMeasured(row.node().id, ((ViewSpec.Row) row.node().spec).layout());
        case LAYOUT_FRAME ->
            panel.beginMeasured(row.node().id, ((ViewSpec.Frame) row.node().spec).layout());
        case LAYOUT_CELL -> panel.beginCell(row.node().id + ":layout-cell");
        case LAYOUT_END -> panel.endMeasured();
        case OPEN -> panel.beginGroup(row.node().id, row.label());
        case CLOSE -> panel.endGroup();
        case ACTION, DISCLOSURE -> {
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
                            // Unrelated kernel updates do not change this control's meaning.
                            // A cached row survives only while its declared inputs are unchanged
                            // and it remains in the active tree. Changed/withdrawn offers refuse.
                            if (refresh(false)
                                && rows.stream().noneMatch(current -> current == row)) {
                              if (root.isOpen()) changed.run();
                              return;
                            }
                            if (!row.availability().available()) return;
                            panel.restoreFocus(row.node().id + "/0");
                            focusForAction.run();
                            invoke(row.node());
                            if (root.isOpen()) changed.run();
                          }),
                      theme)
                  .createNarration(
                      supplier -> {
                        var narration = supplier.get().copy();
                        if (row.kind() == Kind.DISCLOSURE)
                          join(
                              narration,
                              Component.translatableWithFallback(
                                  row.node().expanded
                                      ? "reform.ui.expanded"
                                      : "reform.ui.collapsed",
                                  row.node().expanded ? "Expanded" : "Collapsed"));
                        return narration;
                      })
                  .build();
          button.active =
              failure == null && row.node().scope.isOpen() && row.availability().available();
          if (!row.availability().available())
            button.setTooltip(Tooltip.create(report(row.availability().report())));
          if (flow != null) flowParts.add(new FormPanel.ActionPart(row.node().id, button, body));
          else panel.row(row.node().id, Component.empty(), body, button);
        }
      }
      if (row.sectionAfter() && row.kind() != Kind.CLOSE) {
        if (!flowParts.isEmpty()) {
          panel.actionFlow(flow.id, flowParts);
          flowParts.clear();
        }
        panel.sectionGap();
      }
    }
    if (!flowParts.isEmpty()) panel.actionFlow(flow.id, flowParts);
  }

  private boolean retainedChoice(
      Node node, design.inherent.reform.minecraft.client.ui.ChoiceListData presented, String key) {
    return node.scope.isOpen()
        && node.listData != null
        && node.listData.retains(presented, key)
        && rows.stream().anyMatch(row -> row.node() == node && row.availability().available());
  }

  private void dispatch(
      Object token,
      Node node,
      Function<Runnable, Runnable> guard,
      Runnable changed,
      Runnable action) {
    dispatch(
        token,
        node,
        guard,
        changed,
        action,
        () ->
            node.spec instanceof ViewSpec.QueryInput
                && rows.stream()
                    .anyMatch(row -> row.node() == node && row.availability().available()));
  }

  private void dispatch(
      Object token,
      Node node,
      Function<Runnable, Runnable> guard,
      Runnable changed,
      Runnable action,
      java.util.function.BooleanSupplier retained) {
    guard
        .apply(
            () -> {
              if (!root.isOpen() || !node.scope.isOpen()) return;
              // Inline field hosts rebuild on the next frame. Their same live list may receive
              // several native events first. Only a still-attached, semantically retained choice
              // may cross that projection refresh; other controls retain their attachment fence.
              if (attachment != token
                  && (!(node.spec instanceof ViewSpec.ChoiceList)
                      || !attached
                      || !retained.getAsBoolean())) return;
              if (refresh(false)) {
                // A query event includes the native buffer just polled by prepareRefresh.
                // Keep Enter on that same live input; source/list events still reject stale data.
                if (!retained.getAsBoolean()) {
                  observeChoice(node);
                  if (root.isOpen()) changed.run();
                  return;
                }
              }
              action.run();
              if (root.isOpen()) {
                refresh(true);
                observeChoice(node);
                changed.run();
              }
            })
        .run();
  }

  private void observeChoice(Node node) {
    if (node.listWidget == null) return;
    if (rows.stream().noneMatch(row -> row.node() == node && row.availability().available())) {
      node.listWidget.clear();
      return;
    }
    node.listWidget.observe(node.listData, node.listActive, node.listAccepted);
  }

  private <T> void execute(Node node, UiCommand<T> command, T input) {
    var ready = root.project(command.dependencies(), command::availability);
    node.lastResult =
        ready.available()
            ? Objects.requireNonNull(command.invoke(input))
            : new UiActionResult.Refused(ready.report(), Optional.empty());
    lastAction = node.lastResult;
  }

  private void invoke(Node node) {
    var command =
        node.spec instanceof ViewSpec.Action action ? action.command() : node.disclosureCommand;
    var available = root.project(command.dependencies(), command::availability);
    node.lastResult =
        available.available()
            ? Objects.requireNonNull(command.invoke(null))
            : new UiActionResult.Refused(available.report(), Optional.empty());
    lastAction = node.lastResult;
    refresh(true);
  }

  List<UiDisclosureFailure> disclosureFailures() {
    root.requireCurrent();
    var failures = new ArrayList<UiDisclosureFailure>();
    tree.disclosureFailures(failures);
    return List.copyOf(failures);
  }

  private static void requireAvailable(List<UiRead<?>> inputs) {
    for (var input : inputs)
      if (input.snapshot()
          instanceof design.inherent.reform.presentation.ui.UiObservation.Unavailable<?> missing)
        throw new ReadUnavailable(missing);
  }

  private static final class DisclosureRetirementFailure extends RuntimeException {
    private static final long serialVersionUID = 1L;

    DisclosureRetirementFailure(Throwable cause) {
      super("Content retirement failed", cause);
    }
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
