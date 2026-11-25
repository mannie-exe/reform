package design.inherent.reform.presentation.ui;

import design.inherent.reform.definition.DocumentId;
import design.inherent.reform.edit.DraftNode;
import design.inherent.reform.edit.DraftRevision;
import design.inherent.reform.edit.DraftTypes;
import design.inherent.reform.operation.DocumentActions;
import design.inherent.reform.operation.OperationDescriptor.EntryPoint;
import design.inherent.reform.operation.OperationHandle;
import design.inherent.reform.operation.OperationSnapshot;
import design.inherent.reform.owner.EditorActor;
import design.inherent.reform.owner.PolicyRefusal;
import design.inherent.reform.presentation.ViewStateKey;
import design.inherent.reform.reference.ReferenceActions;
import design.inherent.reform.reference.ReferenceBrowser;
import design.inherent.reform.reference.ReferencePage;
import design.inherent.reform.reference.ReferenceQuery;
import design.inherent.reform.reference.ReferenceResolution;
import design.inherent.reform.reference.ReferenceSelection;
import design.inherent.reform.reference.ReferenceStamp;
import design.inherent.reform.validation.ValidationReport;
import design.inherent.reform.value.SnapshotContext;
import design.inherent.reform.value.SnapshotPolicy;
import design.inherent.reform.value.ValueShape;
import design.inherent.reform.value.ValueTypes;
import java.lang.ref.WeakReference;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/**
 * One source/controller for inline and task reference presentations. The host calls update outside
 * paint; the existing source owns work and the existing document owns edits. No executor is
 * created.
 */
public final class ReferencePicker<T, V> implements AutoCloseable {
  public record Policy(
      int pageSize,
      int maximumQueryCharacters,
      int maximumInspectionCharacters,
      long debounceNanos,
      int maximumWindowPages,
      int maximumCursors) {
    public static final Policy STANDARD = new Policy(64, 128, 32768, 200_000_000L, 4, 1024);

    public Policy(
        int pageSize,
        int maximumQueryCharacters,
        int maximumInspectionCharacters,
        long debounceNanos) {
      this(pageSize, maximumQueryCharacters, maximumInspectionCharacters, debounceNanos, 4, 1024);
    }

    public Policy {
      if (pageSize < 1
          || maximumQueryCharacters < 1
          || maximumInspectionCharacters < 1
          || debounceNanos < 0
          || debounceNanos > 60_000_000_000L
          || maximumWindowPages < 1
          || maximumWindowPages > 64
          || maximumCursors < 2
          || maximumCursors > 16384)
        throw new IllegalArgumentException("Finite picker limits and debounce are required");
    }
  }

  private record PendingChoice<T>(
      PickerRole<T> role,
      DraftRevision revision,
      Optional<FieldFrontend.Input> input,
      String key,
      ReferenceStamp stamp,
      long queryGeneration) {}

  private final UiScope scope;
  private final DocumentActions<T> document;
  private final DocumentId sourceId;
  private final Supplier<ReferenceActions<String, V>> sourceFactory;
  private final Map<String, PickerRole<T>> roles;
  private final Map<String, FieldFrontend> frontends;
  private final Policy policy;
  private final LongSupplier clock;
  private final UiLocal<String> queryText;
  private final UiLocal<Integer> caret, anchor;
  private final UiReadKey<PickerState> stateKey;
  private final UiRead<PickerState> state;
  private ReferenceActions<String, V> source;
  private ReferenceBrowser<V> browser;
  private ReferenceBrowser.Search search;
  private ReferenceBrowser.Lookup<V> lookup;
  private OperationHandle<?> retiringSearch, retiringLookup;

  private record LookupDemand<T>(String raw, PendingChoice<T> choice) {}

  private LookupDemand<T> lookupDemand;
  private Optional<ReferenceQuery.Cursor> queryCursor = Optional.empty();
  private PickerState.Query queryValue = PickerState.Query.atEnd("");
  private OperationHandle<EditorActor.View<T>> edit;
  private PendingChoice<T> pendingChoice;
  private PickerState.Source sourceState = PickerState.Source.UNADMITTED;
  private PickerState.Phase searching = PickerState.Phase.IDLE, inspecting = PickerState.Phase.IDLE;
  private String active, inspected, inspectedTitle;
  private ValidationReport report = ValidationReport.VALID;
  private long queryGeneration, deadline, observationRevision;
  private boolean queryPending, seekingMatches;
  private final java.util.NavigableMap<Integer, ReferencePage<String>> pages =
      new java.util.TreeMap<>();
  private final java.util.ArrayList<Optional<ReferenceQuery.Cursor>> cursors =
      new java.util.ArrayList<>();
  private int requestedPage;
  private Interest interest;

  private WeakReference<UiSnapshot<PickerState>> cached;

  private ReferencePicker(
      UiScope parent,
      DocumentActions<T> document,
      DocumentId source,
      Supplier<ReferenceActions<String, V>> sourceFactory,
      List<PickerRole<T>> roles,
      Map<String, FieldFrontend> frontends,
      Policy policy,
      LongSupplier clock) {
    this.document = Objects.requireNonNull(document);
    document.requireInspection();
    sourceId = Objects.requireNonNull(source);
    this.sourceFactory = Objects.requireNonNull(sourceFactory);
    this.policy = Objects.requireNonNull(policy);
    this.clock = Objects.requireNonNull(clock);
    if (roles.isEmpty() || roles.size() > 16)
      throw new IllegalArgumentException("Declare 1–16 picker roles");
    var admitted = new LinkedHashMap<String, PickerRole<T>>();
    for (var role : roles) {
      var type = DraftTypes.declaredAt(document.definition().type(), role.field());
      if (!(type.shape() instanceof ValueShape.Reference<?> reference)
          || !reference.source().equals(sourceId))
        throw new IllegalArgumentException(
            "Every picker role must declare this exact reference source");
      if (admitted.putIfAbsent(role.id(), role) != null)
        throw new IllegalArgumentException("Duplicate picker role");
    }
    this.roles = java.util.Collections.unmodifiableMap(new LinkedHashMap<>(admitted));
    this.frontends = Map.copyOf(frontends);
    if (!admitted.keySet().containsAll(frontends.keySet()))
      throw new IllegalArgumentException("Frontend restrictions need an admitted role");
    scope = parent.child();
    try {
      queryText =
          scope.local(
              new ViewStateKey<>(
                  DocumentId.parse("reform:picker_query"),
                  ValueTypes.text(policy.maximumQueryCharacters())),
              "");
      caret =
          scope.local(
              new ViewStateKey<>(
                  DocumentId.parse("reform:picker_caret"),
                  ValueTypes.integer(0, policy.maximumQueryCharacters())),
              0);
      anchor =
          scope.local(
              new ViewStateKey<>(
                  DocumentId.parse("reform:picker_anchor"),
                  ValueTypes.integer(0, policy.maximumQueryCharacters())),
              0);
      stateKey =
          new UiReadKey<>(
              DocumentId.parse("reform:reference_picker"),
              SnapshotPolicy.immutable(),
              SnapshotContext.Limits.CONFIGURATION,
              parent.limits().maximumStateBytes(),
              ReferencePicker::weight);
      scope.onRetire(this::retire);
      state = scope.observe(stateKey, this::observe);
    } catch (RuntimeException | Error failed) {
      try {
        scope.close();
      } catch (RuntimeException | Error cleanup) {
        if (cleanup != failed) failed.addSuppressed(cleanup);
      }
      throw failed;
    }
  }

  public static <T, V> ReferencePicker<T, V> open(
      UiScope scope,
      DocumentActions<T> document,
      DocumentId source,
      Supplier<ReferenceActions<String, V>> sourceFactory,
      List<PickerRole<T>> roles,
      Map<String, FieldFrontend> frontends,
      Policy policy,
      LongSupplier clock) {
    return new ReferencePicker<>(
        scope, document, source, sourceFactory, roles, frontends, policy, clock);
  }

  /** Compose against the actual field owner without opening or exposing another editor. */
  public static <T, V> ReferencePicker<T, V> open(
      UiScope scope,
      FieldBinding<?, ?> field,
      design.inherent.reform.definition.DocumentDefinition<T> definition,
      DocumentId source,
      Supplier<ReferenceActions<String, V>> sourceFactory,
      List<PickerRole<T>> roles,
      Map<String, FieldFrontend> frontends,
      Policy policy,
      LongSupplier clock) {
    return open(
        scope, field.document(definition), source, sourceFactory, roles, frontends, policy, clock);
  }

  public UiRead<PickerState> state() {
    return state;
  }

  public UiScope scope() {
    return scope;
  }

  public List<PickerRole<T>> roles() {
    return List.copyOf(roles.values());
  }

  public Policy policy() {
    return policy;
  }

  /** One visible driver, borrowed independently from the controller/source/document lifetimes. */
  public final class Interest implements AutoCloseable {
    private boolean open = true, admitted;

    private Interest() {}

    public void update() {
      scope.update(
          () -> {
            if (!open || interest != this)
              throw refusal("reference.mount_closed", "This picker attachment has ended.");
            if (!admitted) {
              admitted = true;
              admit().invoke(null);
            }
            ReferencePicker.this.update();
          });
    }

    @Override
    public void close() {
      if (!open) return;
      if (!scope.isOpen()) {
        open = false;
        return;
      }
      scope.update(
          () -> {
            open = false;
            if (interest == this) {
              interest = null;
              suspend();
            }
          });
    }
  }

  /** Acquiring the attachment itself performs no source admission or work. */
  public Interest mount() {
    var result = new AtomicReference<Interest>();
    scope.update(
        () -> {
          if (interest != null)
            throw refusal(
                "reference.already_mounted", "This picker already has a visible attachment.");
          interest = new Interest();
          result.set(interest);
        });
    return result.get();
  }

  /**
   * Explicit view admission. The borrowed source factory is never called by construction or paint.
   */
  public UiCommand<Void> admit() {
    return command(
        ignored -> {
          try {
            return pending(admitSource());
          } catch (PolicyRefusal denied) {
            throw denied;
          } catch (RuntimeException failed) {
            report =
                refusal(
                        "reference.source_failed",
                        "Choices could not be opened. Refresh to try again.")
                    .report();
            return new UiActionResult.Refused(report, Optional.of(failed));
          }
        });
  }

  public UiCommand<PickerState.Query> query() {
    return command(
        input -> {
          document.requireInspection();
          if (input.text().length() > policy.maximumQueryCharacters())
            throw new PolicyRefusal("reference.query_limit", "The search is too long; shorten it.");
          boolean changed = !queryValue.text().equals(input.text());
          queryText.replace(input.text());
          caret.replace(input.caret());
          anchor.replace(input.anchor());
          queryValue = input;
          if (changed) {
            queryGeneration = Math.incrementExact(queryGeneration);
            cancelSearch();
            resetWindow();
            queryCursor = Optional.empty();
            if (pendingChoice != null || lookupDemand != null && lookupDemand.choice() != null)
              cancelLookup();
            active = null;
            queryPending = true;
            deadline = clock.getAsLong() + policy.debounceNanos();
            searching = PickerState.Phase.DEBOUNCING;
            report = ValidationReport.VALID;
          }
          return new UiActionResult.Applied();
        });
  }

  /** Enter flushes only the current query. It does not accept a list item or stage a field. */
  public UiCommand<Void> flushSearch() {
    return command(
        this::sourceAvailability,
        ignored -> {
          requireSource();
          if (!queryPending
              && search != null
              && search.query().filter().equals(queryValue.text())
              && (!search.operation().snapshot().terminal()
                  || search.operation().snapshot().state() == OperationSnapshot.State.COMPLETED))
            return pending(search.operation());
          queryPending = true;
          deadline = clock.getAsLong();
          return pending(startSearch(Optional.empty()));
        });
  }

  public UiCommand<Void> nextPage() {
    return command(
        () -> pageAvailability(true),
        ignored -> {
          require(pageAvailability(true));
          return requestPage(pages.lastKey() + 1);
        });
  }

  public UiCommand<Void> previousPage() {
    return command(
        () -> pageAvailability(false),
        ignored -> {
          require(pageAvailability(false));
          return requestPage(pages.firstKey() - 1);
        });
  }

  /** Uses only a cursor actually returned for this exact source/query. */
  private UiActionResult requestPage(int index) {
    requireSource();
    if (index < 0 || index >= cursors.size())
      throw refusal("reference.cursor", "These results changed; restart the search.");
    var cursor = cursors.get(index);
    if (cursor.isPresent() && !cursor.orElseThrow().filter().equals(queryValue.text()))
      throw refusal("reference.cursor", "The search changed; restart it.");
    if (search != null
        && requestedPage == index
        && (!search.operation().snapshot().terminal()
            || search.operation().snapshot().state() == OperationSnapshot.State.COMPLETED))
      return pending(search.operation());
    if (search != null && !search.operation().snapshot().terminal()) cancelSearch();
    requestedPage = index;
    seekingMatches = false;
    queryCursor = cursor;
    deadline = clock.getAsLong();
    return pending(startSearch(cursor));
  }

  /** Keyboard focus/hover can move the active row without pinning inspection or editing. */
  public UiCommand<PickerState.Option> active() {
    return command(
        () -> listAvailability(),
        option -> {
          requireOption(option);
          active = option.key();
          return new UiActionResult.Applied();
        });
  }

  public UiCommand<String> inspect() {
    return command(
        this::sourceAvailability,
        raw -> {
          return pending(beginLookup(raw, null));
        });
  }

  /** No role pins inspection only; an explicit role requests its one guarded field edit. */
  public UiCommand<PickerState.Activation<T>> activate() {
    return command(
        () -> listAvailability(),
        activation -> {
          requireOption(activation.option());
          var choice =
              activation
                  .role()
                  .map(role -> captureChoice(role, activation.option().key()))
                  .orElse(null);
          return pending(beginLookup(activation.option().key(), choice));
        });
  }

  /** Fixed-role inline acceptance; read-only tasks instead use activate with no role. */
  public UiCommand<PickerState.Option> activate(PickerRole<T> role) {
    requireRole(role);
    return command(
        () -> {
          var ready = listAvailability();
          return ready.available() ? roleAvailability(role) : ready;
        },
        option -> activate().invoke(new PickerState.Activation<>(option, Optional.of(role))));
  }

  private UiAvailability sourceAvailability() {
    var value = state.snapshot();
    if (value instanceof UiObservation.Unavailable<PickerState> missing)
      return new UiAvailability(false, missing.report());
    return value.value().source() == PickerState.Source.READY
        ? UiAvailability.READY
        : UiAvailability.blocked("reference.not_admitted", "Open or refresh these choices first.");
  }

  private UiAvailability listAvailability() {
    var ready = sourceAvailability();
    if (!ready.available()) return ready;
    return !state.snapshot().value().window().choices().isEmpty()
        ? UiAvailability.READY
        : UiAvailability.blocked(
            "reference.no_choices", "Wait for available choices or change the search.");
  }

  private UiAvailability pageAvailability(boolean forward) {
    var ready = sourceAvailability();
    if (!ready.available()) return ready;
    var window = state.snapshot().value().window();
    if (window.pageCount() == 0)
      return UiAvailability.blocked("reference.no_page", "Search before continuing.");
    if (forward ? window.after() : window.before()) return UiAvailability.READY;
    return UiAvailability.blocked(
        forward ? "reference.no_next_page" : "reference.no_previous_page",
        forward ? "There are no more choices in this search." : "These are the first results.");
  }

  private UiAvailability roleAvailability(PickerRole<T> role) {
    var value = state.snapshot().value();
    if (value.acceptance().isPresent()
        || value.editWork().filter(work -> !terminal(work.state())).isPresent())
      return UiAvailability.blocked(
          "reference.edit_pending", "Wait for the previous selection to finish.");
    var requested = value.requested().get(role.id());
    return requested == null
        ? UiAvailability.blocked("reference.field_unavailable", "This field is unavailable.")
        : requested.editing();
  }

  public UiCommand<PickerRole<T>> acceptInspected() {
    return command(
        () -> acceptanceAvailability(Optional.empty()),
        role -> {
          requireSource();
          if (pendingChoice != null || edit != null && !edit.snapshot().terminal())
            throw refusal("reference.edit_pending", "Wait for the previous selection to finish.");
          String selected =
              browser
                  .selectedKey()
                  .orElseThrow(
                      () -> refusal("reference.unresolved", "Inspect a resolved choice first."));
          var choice = captureChoice(role, selected);
          edit = stage(choice);
          return pending(edit);
        });
  }

  /** Bind a field-specific button to its own cached permission/input feedback. */
  public UiCommand<Void> acceptInspected(PickerRole<T> role) {
    requireRole(role);
    return command(
        () -> acceptanceAvailability(Optional.of(role)), ignored -> acceptInspected().invoke(role));
  }

  private UiAvailability acceptanceAvailability(Optional<PickerRole<T>> role) {
    var current = state.snapshot();
    if (current instanceof UiObservation.Unavailable<PickerState> missing)
      return new UiAvailability(false, missing.report());
    var value = current.value();
    if (value.source() != PickerState.Source.READY)
      return UiAvailability.blocked(
          "reference.not_admitted", "Open or refresh these choices first.");
    if (value.acceptance().isPresent()
        || value.editWork().filter(work -> !terminal(work.state())).isPresent())
      return UiAvailability.blocked(
          "reference.edit_pending", "Wait for the previous selection to finish.");
    if (value.inspection().filter(i -> i.status() == ReferenceResolution.Status.RESOLVED).isEmpty())
      return UiAvailability.blocked("reference.unresolved", "Inspect a resolved choice first.");
    if (role.isPresent()) return value.requested().get(role.orElseThrow().id()).editing();
    return value.requested().values().stream()
            .filter(r -> r.editing().available())
            .findFirst()
            .isPresent()
        ? UiAvailability.READY
        : value.requested().values().stream()
            .map(PickerState.Requested::editing)
            .findFirst()
            .orElse(
                UiAvailability.blocked(
                    "reference.field_unavailable", "This field is unavailable."));
  }

  private static boolean terminal(OperationSnapshot.State value) {
    return switch (value) {
      case COMPLETED, REFUSED, FAILED, CANCELLED -> true;
      default -> false;
    };
  }

  public UiCommand<Void> refresh() {
    return admit();
  }

  /** Withdraws this view's observations/work, never its borrowed source or document. */
  public void suspend() {
    scope.update(
        () -> {
          releaseBrowser();
          sourceState = PickerState.Source.SUSPENDED;
          report = ValidationReport.VALID;
        });
  }

  /** Poll actual handles and debounce on the creating owner thread, outside render/measurement. */
  public void update() {
    scope.update(
        () -> {
          if (retiringSearch != null && retiringSearch.snapshot().terminal()) {
            retiringSearch = null;
          }
          if (retiringLookup != null && retiringLookup.snapshot().terminal()) {
            retiringLookup = null;
            if (lookup == null && lookupDemand == null && inspecting == PickerState.Phase.DRAINING)
              inspecting = PickerState.Phase.CANCELLED;
          }
          if (browser != null) {
            try {
              requireSource();
            } catch (PolicyRefusal unavailable) {
              withdraw(unavailable);
              return;
            }
            pollSearch();
            pollLookup();
            if (browser != null && lookupDemand != null && retiringLookup == null) {
              var desired = lookupDemand;
              lookupDemand = null;
              try {
                beginLookup(desired.raw(), desired.choice());
              } catch (PolicyRefusal refused) {
                inspecting = PickerState.Phase.REFUSED;
                report = refused.report();
              }
            }
            if (browser != null && queryPending && clock.getAsLong() - deadline >= 0) {
              try {
                startSearch(queryCursor);
              } catch (PolicyRefusal refused) {
                queryPending = false;
                searching = PickerState.Phase.REFUSED;
                report = refused.report();
              }
            }
          }
          // Kernel rejection must not leave an uncharged presentation payload in this controller.
          if (state.snapshot() instanceof UiObservation.Unavailable<PickerState> missing) {
            releaseBrowser();
            sourceState = PickerState.Source.UNAVAILABLE;
            report = missing.report();
          }
        });
  }

  private void pollSearch() {
    var selected = browser;
    var requested = search;
    if (selected == null
        || requested == null
        || searching != PickerState.Phase.RUNNING
        || !requested.operation().snapshot().terminal()) return;
    var terminal = requested.operation().snapshot();
    final boolean accepted;
    try {
      accepted = selected.accept(requested);
    } catch (PolicyRefusal refused) {
      if (browser == selected && search == requested) withdraw(refused);
      return;
    }
    if (browser != selected || search != requested) return;
    if (accepted
        && terminal.result().orElseThrow().availability() == ReferencePage.Availability.READY) {
      try {
        acceptPage(requestedPage, terminal.result().orElseThrow());
      } catch (PolicyRefusal refused) {
        searching = PickerState.Phase.REFUSED;
        report = refused.report();
        return;
      }
    }
    searching =
        accepted
            ? terminal.result().orElseThrow().availability() == ReferencePage.Availability.READY
                ? PickerState.Phase.READY
                : PickerState.Phase.REFUSED
            : phase(terminal.state());
    if (accepted && seekingMatches && searching == PickerState.Phase.READY) {
      var page = terminal.result().orElseThrow();
      if (page.choices().isEmpty() && page.next().isPresent()) {
        queryCursor = page.next();
        requestedPage = Math.incrementExact(requestedPage);
        queryPending = true;
        deadline = clock.getAsLong();
      } else seekingMatches = false;
    }
  }

  private void pollLookup() {
    var selected = browser;
    var requested = lookup;
    if (selected == null
        || requested == null
        || inspecting != PickerState.Phase.RUNNING
        || !requested.operation().snapshot().terminal()) return;
    var terminal = requested.operation().snapshot();
    final boolean accepted;
    try {
      accepted = selected.accept(requested);
    } catch (PolicyRefusal refused) {
      if (browser == selected && lookup == requested) withdraw(refused);
      return;
    }
    if (browser != selected || lookup != requested) return;
    if (!accepted) {
      inspecting = phase(terminal.state());
      pendingChoice = null;
      return;
    }
    inspecting = PickerState.Phase.READY;
    if (pendingChoice == null) return;
    var choice = pendingChoice;
    pendingChoice = null;
    try {
      if (selected.selectedKey().isEmpty())
        throw refusal("reference.unresolved", "This ID is not resolved; inspect another choice.");
      var operation = stage(choice);
      if (browser == selected && lookup == requested) edit = operation;
    } catch (PolicyRefusal refused) {
      if (browser == selected && lookup == requested) report = refused.report();
    }
  }

  private OperationHandle<?> admitSource() {
    document.requireInspection();
    releaseBrowser();
    try {
      var candidate = Objects.requireNonNull(sourceFactory.get());
      if (!candidate.source().equals(sourceId))
        throw new IllegalArgumentException("The picker factory returned another source");
      source = candidate;
      browser = new ReferenceBrowser<>(source);
      sourceState = PickerState.Source.READY;
      report = ValidationReport.VALID;
      queryGeneration = Math.incrementExact(queryGeneration);
      queryPending = true;
      deadline = clock.getAsLong();
      return startSearch(Optional.empty());
    } catch (PolicyRefusal failed) {
      withdraw(failed);
      throw failed;
    } catch (RuntimeException failed) {
      releaseBrowser();
      sourceState = PickerState.Source.UNAVAILABLE;
      report =
          refusal("reference.source_failed", "Choices could not be opened. Refresh to try again.")
              .report();
      throw failed;
    }
  }

  private OperationHandle<?> startSearch(Optional<ReferenceQuery.Cursor> cursor) {
    requireSource();
    String filter = queryValue.text();
    if (filter.length() > source.limits().maximumQueryLength())
      throw refusal("reference.query_limit", "This source needs a shorter search.");
    if (retiringSearch != null && !retiringSearch.snapshot().terminal()) {
      queryCursor = cursor;
      queryPending = true;
      searching = PickerState.Phase.DRAINING;
      return retiringSearch;
    }
    retiringSearch = null;
    queryPending = false;
    search =
        browser.search(
            EntryPoint.GUI,
            new ReferenceQuery(
                filter, Math.min(policy.pageSize(), source.limits().maximumPageSize()), cursor));
    searching = PickerState.Phase.RUNNING;
    return search.operation();
  }

  private OperationHandle<?> beginLookup(String raw, PendingChoice<T> choice) {
    requireSource();
    if (Objects.requireNonNull(raw).length() > policy.maximumInspectionCharacters())
      throw refusal("reference.input_limit", "This ID exceeds the inspection allowance.");
    if (edit != null && !edit.snapshot().terminal())
      throw refusal("reference.edit_pending", "Wait for the previous selection to finish.");
    cancelLookup();
    inspected = raw;
    inspectedTitle =
        pages.values().stream()
            .flatMap(page -> page.choices().stream())
            .filter(choiceValue -> choiceValue.key().equals(raw))
            .map(ReferencePage.Choice::label)
            .findFirst()
            .orElse(null);
    if (retiringLookup != null && !retiringLookup.snapshot().terminal()) {
      lookupDemand = new LookupDemand<>(raw, choice);
      inspecting = PickerState.Phase.DRAINING;
      return retiringLookup;
    }
    retiringLookup = null;
    lookup = browser.resolve(EntryPoint.GUI, raw);
    pendingChoice = choice;
    inspecting = PickerState.Phase.RUNNING;
    report = ValidationReport.VALID;
    return lookup.operation();
  }

  private void requireOption(PickerState.Option option) {
    requireSource();
    if (!browser.stamp().equals(option.stamp())
        || option.queryGeneration() != queryGeneration
        || pages.values().stream()
            .flatMap(page -> page.choices().stream())
            .noneMatch(choice -> choice.key().equals(option.key())))
      throw refusal("reference.option_stale", "The choices changed; select from the current list.");
  }

  private PendingChoice<T> captureChoice(PickerRole<T> role, String key) {
    requireRole(role);
    document.requireEditing();
    var current = document.view().snapshot().orElseThrow();
    if (!current.draft().contains(role.field()) || !current.draft().activeAt(role.field()))
      throw refusal("reference.field_inactive", "This field is inactive or no longer present.");
    var frontend = frontends.getOrDefault(role.id(), FieldFrontend.NONE);
    require(frontend.editing());
    var input = frontend.input();
    if (input.filter(value -> !value.accepted() || value.pending()).isPresent())
      throw refusal(
          "reference.input_unaccepted", "Finish or repair this input before choosing a value.");
    return new PendingChoice<>(
        role,
        DraftRevision.of(document.view().snapshot().orElseThrow()),
        input,
        key,
        browser.stamp(),
        queryGeneration);
  }

  private OperationHandle<EditorActor.View<T>> stage(PendingChoice<T> choice) {
    requireSource();
    var selectedBrowser = browser;
    var selectedLookup = lookup;
    requireChoice(choice, selectedBrowser, selectedLookup);
    return ReferenceSelection.chooseFromBrowser(
        EntryPoint.GUI,
        document,
        choice.role().field(),
        browser,
        choice.revision(),
        () -> {
          requireSource();
          document.requireEditing();
          var frontend = frontends.getOrDefault(choice.role().id(), FieldFrontend.NONE);
          require(frontend.editing());
          if (!choice.input().equals(frontend.input()))
            throw refusal("reference.input_stale", "The input changed; choose this value again.");
          requireChoice(choice, selectedBrowser, selectedLookup);
        });
  }

  private void requireChoice(
      PendingChoice<T> choice,
      ReferenceBrowser<V> selectedBrowser,
      ReferenceBrowser.Lookup<V> selectedLookup) {
    if (browser != selectedBrowser
        || lookup != selectedLookup
        || queryGeneration != choice.queryGeneration()
        || !selectedBrowser.stamp().equals(choice.stamp())
        || selectedBrowser.selectedKey().filter(choice.key()::equals).isEmpty())
      throw refusal("reference.selection_stale", "The inspected choice changed; choose it again.");
  }

  private void requireRole(PickerRole<T> role) {
    if (!role.equals(roles.get(role.id())))
      throw refusal("reference.role", "Use one of this picker's declared fields.");
  }

  private void requireSource() {
    document.requireInspection();
    if (browser == null)
      throw refusal("reference.not_admitted", "Open or refresh these choices first.");
    browser.requireCurrent();
  }

  private UiObservation<PickerState> observe() {
    scope.requireCurrent();
    try {
      document.requireInspection();
      if (browser != null) browser.requireCurrent();
      var view = document.view();
      var requested = new LinkedHashMap<String, PickerState.Requested>();
      var snapshot = view.snapshot().orElseThrow();
      for (var role : roles.values()) {
        Optional<String> raw = Optional.empty();
        boolean active =
            snapshot.draft().contains(role.field()) && snapshot.draft().activeAt(role.field());
        if (snapshot.draft().contains(role.field())
            && snapshot.draft().at(role.field()) instanceof DraftNode.Scalar scalar)
          raw = Optional.of(scalar.raw());
        UiAvailability editing;
        try {
          document.requireEditing();
          editing =
              active
                  ? frontends.getOrDefault(role.id(), FieldFrontend.NONE).editing()
                  : UiAvailability.blocked(
                      "reference.field_inactive", "This field is inactive or no longer present.");
        } catch (PolicyRefusal refused) {
          editing = new UiAvailability(false, refused.report());
        }
        var input = frontends.getOrDefault(role.id(), FieldFrontend.NONE).input();
        if (editing.available() && input.filter(i -> !i.accepted() || i.pending()).isPresent())
          editing =
              UiAvailability.blocked(
                  "reference.input_unaccepted",
                  "Finish or repair this input before choosing a value.");
        requested.put(role.id(), new PickerState.Requested(raw, input, active, editing));
      }
      var resolution =
          browser == null ? Optional.<ReferenceResolution<V>>empty() : browser.resolution();
      var facts =
          new PickerState(
              sourceState,
              queryValue(),
              queryGeneration,
              browser == null ? Optional.empty() : Optional.of(browser.stamp()),
              searching,
              browser == null ? Optional.empty() : browser.page(),
              window(),
              Optional.ofNullable(active),
              inspecting,
              Optional.ofNullable(inspected),
              resolution.map(
                  r ->
                      new PickerState.Inspection(
                          inspected,
                          r.status(),
                          r.preview(),
                          r.reason(),
                          Optional.ofNullable(inspectedTitle))),
              requested,
              sourceWork(search == null ? null : search.operation(), retiringSearch),
              sourceWork(lookup == null ? null : lookup.operation(), retiringLookup),
              work(edit),
              acceptance(),
              report);
      return observed(facts);
    } catch (PolicyRefusal denied) {
      return observed(
          new PickerState(
              PickerState.Source.UNAVAILABLE,
              queryValue(),
              queryGeneration,
              Optional.empty(),
              PickerState.Phase.REFUSED,
              Optional.empty(),
              PickerState.Window.EMPTY,
              Optional.empty(),
              PickerState.Phase.REFUSED,
              Optional.ofNullable(inspected),
              Optional.empty(),
              Map.of(),
              Optional.empty(),
              Optional.empty(),
              Optional.empty(),
              Optional.empty(),
              denied.report()));
    }
  }

  private UiObservation<PickerState> observed(PickerState facts) {
    var previous = cached == null ? null : cached.get();
    var captured = stateKey.capture(facts);
    if (previous != null && previous.sameData(captured)) captured = previous;
    else observationRevision = Math.incrementExact(observationRevision);
    cached = new WeakReference<>(captured);
    return new UiObservation.Available<>(captured, observationRevision);
  }

  private Optional<PickerState.Acceptance> acceptance() {
    var current =
        pendingChoice != null ? pendingChoice : lookupDemand == null ? null : lookupDemand.choice();
    return current == null
        ? Optional.empty()
        : Optional.of(
            new PickerState.Acceptance(current.role().id(), current.revision(), current.input()));
  }

  private PickerState.Query queryValue() {
    return queryValue;
  }

  private Optional<PickerState.Work> sourceWork(
      OperationHandle<?> current, OperationHandle<?> retiring) {
    if (browser == null || sourceState != PickerState.Source.READY) return Optional.empty();
    return current != null
        ? work(current)
        : retiring == null ? Optional.empty() : Optional.of(PickerState.retiring(retiring));
  }

  private Optional<PickerState.Work> work(OperationHandle<?> handle) {
    return handle == null ? Optional.empty() : Optional.of(PickerState.Work.of(handle));
  }

  private <I> UiCommand<I> command(Function<I, UiActionResult> invoke) {
    return command(() -> UiAvailability.READY, invoke);
  }

  private <I> UiCommand<I> command(
      Supplier<UiAvailability> readiness, Function<I, UiActionResult> invoke) {
    return new UiCommand<>() {
      public List<UiRead<?>> dependencies() {
        return List.of(state);
      }

      public UiAvailability availability() {
        var current = state.snapshot();
        return current instanceof UiObservation.Unavailable<PickerState> unavailable
            ? new UiAvailability(false, unavailable.report())
            : readiness.get();
      }

      public UiActionResult invoke(I input) {
        var result = new AtomicReference<UiActionResult>();
        try {
          scope.update(
              () -> {
                try {
                  result.set(invoke.apply(input));
                } catch (RuntimeException failed) {
                  var refusal =
                      failed instanceof PolicyRefusal p
                          ? p
                          : refusal(
                              failed instanceof IllegalArgumentException
                                  ? "reference.input_invalid"
                                  : "reference.request_failed",
                              failed instanceof IllegalArgumentException
                                  ? "This picker input is invalid."
                                  : "This request failed. Refresh to try again.");
                  report = refusal.report();
                  result.set(new UiActionResult.Refused(refusal.report(), Optional.of(failed)));
                }
              });
          return result.get();
        } catch (PolicyRefusal refused) {
          return new UiActionResult.Refused(refused.report(), Optional.of(refused));
        }
      }
    };
  }

  private void withdraw(PolicyRefusal cause) {
    boolean stale = source != null && browser != null && !browser.current();
    releaseBrowser();
    sourceState = stale ? PickerState.Source.STALE : PickerState.Source.UNAVAILABLE;
    report = cause.report();
  }

  private void resetWindow() {
    pages.clear();
    cursors.clear();
    cursors.add(Optional.empty());
    requestedPage = 0;
    seekingMatches = true;
  }

  private void acceptPage(int index, ReferencePage<String> page) {
    if (!page.stamp().equals(browser.stamp()) || index < 0 || index >= cursors.size())
      throw refusal("reference.cursor", "These results changed; restart the search.");
    var next = new java.util.TreeMap<>(pages);
    next.put(index, page);
    while (next.size() > policy.maximumWindowPages()) {
      if (index - next.firstKey() > next.lastKey() - index) next.pollFirstEntry();
      else next.pollLastEntry();
    }
    var keys = new java.util.HashSet<String>();
    for (var admitted : next.values())
      for (var choice : admitted.choices())
        if (!keys.add(choice.key()))
          throw refusal(
              "reference.page_overlap",
              "The source returned overlapping choices. Refresh or narrow the search.");
    if (page.next().isPresent()) {
      var cursor = page.next().orElseThrow();
      if (!cursor.snapshot().equals(page.stamp().snapshot())
          || !cursor.filter().equals(queryValue.text())
          || cursors.subList(0, Math.min(index + 1, cursors.size())).contains(Optional.of(cursor)))
        throw refusal(
            "reference.cursor",
            "The choices changed while loading another page. Refresh the search.");
      if (index + 1 < cursors.size() && !cursors.get(index + 1).equals(Optional.of(cursor)))
        throw refusal(
            "reference.cursor",
            "The choices changed while loading another page. Refresh the search.");
      if (index + 1 == cursors.size()) {
        if (cursors.size() >= policy.maximumCursors())
          throw refusal(
              "reference.page_history_limit",
              "Too many result pages are retained. Narrow or restart this search.");
        cursors.add(Optional.of(cursor));
      }
    }
    pages.clear();
    pages.putAll(next);
    report = ValidationReport.VALID;
  }

  private PickerState.Window window() {
    if (browser == null || pages.isEmpty()) return PickerState.Window.EMPTY;
    var choices = new java.util.ArrayList<ReferencePage.Choice<String>>();
    for (var page : pages.values()) choices.addAll(page.choices());
    int first = pages.firstKey(), last = pages.lastKey();
    return new PickerState.Window(
        choices, first, pages.size(), cursors.size(), first > 0, last + 1 < cursors.size());
  }

  private void cancelSearch() {
    if (browser != null) browser.clearSearch();
    if (search != null && !search.operation().snapshot().terminal())
      retiringSearch = search.operation();
    search = null;
  }

  private void cancelLookup() {
    if (browser != null) browser.clearLookup();
    if (lookup != null && !lookup.operation().snapshot().terminal())
      retiringLookup = lookup.operation();
    lookup = null;
    lookupDemand = null;
    pendingChoice = null;
    inspecting =
        retiringLookup != null && !retiringLookup.snapshot().terminal()
            ? PickerState.Phase.DRAINING
            : PickerState.Phase.IDLE;
  }

  private void releaseBrowser() {
    cancelSearch();
    cancelLookup();
    var previous = browser;
    browser = null;
    resetWindow();
    active = null;
    inspectedTitle = null;
    queryPending = false;
    searching = PickerState.Phase.IDLE;
    inspecting = PickerState.Phase.IDLE;
    if (previous != null) previous.close();
  }

  private void retire() {
    if (interest != null) {
      interest.open = false;
      interest = null;
    }
    releaseBrowser();
    source = null;
    edit = null;
    retiringSearch = null;
    retiringLookup = null;
    cached = null;
  }

  private static PolicyRefusal refusal(String code, String text) {
    return new PolicyRefusal(code, text);
  }

  private static void require(UiAvailability value) {
    if (!value.available()) throw new PolicyRefusal(value.report());
  }

  private static UiActionResult pending(OperationHandle<?> operation) {
    return new UiActionResult.Pending(operation);
  }

  private static PickerState.Phase phase(OperationSnapshot.State state) {
    return switch (state) {
      case REFUSED -> PickerState.Phase.REFUSED;
      case FAILED -> PickerState.Phase.FAILED;
      case CANCELLED -> PickerState.Phase.CANCELLED;
      default -> PickerState.Phase.RUNNING;
    };
  }

  private static long weight(PickerState state) {
    long size = 256L + 2L * state.query().text().length();
    for (var value : state.requested().entrySet())
      size +=
          reportWeight(value.getValue().editing().report())
              + 64L
              + 2L * value.getKey().length()
              + value.getValue().raw().map(raw -> 2L * raw.length()).orElse(0L);
    for (var value : state.requested().values())
      if (value.input().isPresent()) size += 64L + 2L * value.input().orElseThrow().raw().length();
    for (var work : List.of(state.searchWork(), state.lookupWork(), state.editWork()))
      if (work.isPresent())
        size += 64L + work.orElseThrow().report().map(ReferencePicker::reportWeight).orElse(0L);
    if (state.stamp().isPresent())
      size += 128L + 2L * state.stamp().orElseThrow().revision().length();
    if (state.inspectionRequest().isPresent())
      size += 2L * state.inspectionRequest().orElseThrow().length();
    if (state.active().isPresent()) size += 2L * state.active().orElseThrow().length();
    if (state.page().isPresent()) {
      var page = state.page().orElseThrow();
      size += 128L + 2L * page.reason().length();
      if (page.next().isPresent()) size += 64L + 2L * page.next().orElseThrow().filter().length();
      for (var choice : page.choices())
        size +=
            64L
                + 2L * (choice.key().length() + choice.label().length())
                + textWeight(choice.preview());
    }
    size += (64L + 2L * state.query().text().length()) * state.window().retainedCursors() + 64L;
    for (var choice : state.window().choices())
      size +=
          64L
              + 2L * (choice.key().length() + choice.label().length())
              + textWeight(choice.preview());
    if (state.inspection().isPresent()) {
      var value = state.inspection().orElseThrow();
      size +=
          64L
              + 2L * (value.key().length() + value.reason().length())
              + textWeight(value.facts())
              + value.title().map(title -> 2L * title.length()).orElse(0L);
    }
    if (state.acceptance().isPresent()) {
      var expected = state.acceptance().orElseThrow();
      size +=
          192L + 2L * (expected.role().length() + expected.revision().sourceRevision().length());
      if (expected.input().isPresent())
        size += 64L + 2L * expected.input().orElseThrow().raw().length();
    }
    size += reportWeight(state.report());
    return size;
  }

  private static long reportWeight(ValidationReport report) {
    long size = 0;
    for (var issue : report.issues()) {
      size +=
          128L
              + 2L
                  * (issue.code().length()
                      + issue.fallback().length()
                      + issue.path().toString().length());
      for (var related : issue.related()) size += 2L * related.toString().length();
      size += textWeight(issue.arguments());
    }
    return size;
  }

  private static long textWeight(Map<String, String> facts) {
    long size = 0;
    for (var entry : facts.entrySet())
      size += 48L + 2L * (entry.getKey().length() + entry.getValue().length());
    return size;
  }

  @Override
  public void close() {
    scope.close();
  }
}
