package design.inherent.reform.presentation.ui;

import design.inherent.reform.definition.DocumentId;
import design.inherent.reform.operation.OperationDescriptor.EntryPoint;
import design.inherent.reform.operation.OperationHandle;
import design.inherent.reform.owner.PolicyRefusal;
import design.inherent.reform.reference.ReferenceActions;
import design.inherent.reform.reference.ReferenceBrowser;
import design.inherent.reform.reference.ReferencePage;
import design.inherent.reform.reference.ReferenceQuery;
import design.inherent.reform.reference.ReferenceStamp;
import design.inherent.reform.validation.ValidationReport;
import design.inherent.reform.value.SnapshotContext;
import design.inherent.reform.value.SnapshotPolicy;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/**
 * Bounded ID-prefix suggestions over a picker's actual admitted source. Native key-prefix queries
 * are preferred; older text-only sources use bounded filtering of their empty-query listing. This
 * port never resolves an ID, writes a field, or changes the browse query. Its host drives update
 * outside paint and binds explicit acceptance to the original input's stamped replacement command.
 */
public final class ReferenceSuggestions<V> {
  public record Policy(
      int maximumChoices, int maximumPrefixCharacters, int maximumPages, long debounceNanos) {
    public static final Policy STANDARD = new Policy(4, 4096, 64, 200_000_000L);

    public Policy {
      if (maximumChoices < 1
          || maximumChoices > 12
          || maximumPrefixCharacters < 1
          || maximumPrefixCharacters > 4096
          || maximumPages < 1
          || maximumPages > 1024
          || debounceNanos < 0
          || debounceNanos > 5_000_000_000L)
        throw new IllegalArgumentException("Declare finite suggestion limits");
    }
  }

  public record Choice(String key, String title) {
    public Choice {
      Objects.requireNonNull(key);
      Objects.requireNonNull(title);
    }
  }

  /**
   * Complete means this suggestion query exhausted its listing, never that an ID is valid/unknown.
   */
  public record State(
      String prefix,
      long generation,
      PickerState.Phase phase,
      Optional<ReferenceStamp> stamp,
      List<Choice> choices,
      Optional<String> active,
      int pages,
      boolean complete,
      Optional<PickerState.Work> work,
      ValidationReport report) {
    public State {
      Objects.requireNonNull(prefix);
      Objects.requireNonNull(phase);
      Objects.requireNonNull(stamp);
      choices = List.copyOf(choices);
      Objects.requireNonNull(active);
      Objects.requireNonNull(work);
      Objects.requireNonNull(report);
    }
  }

  /** A suggestion is meaningful only for this exact source, prefix generation, and key. */
  public record Option(ReferenceStamp stamp, long generation, String key) {
    public Option {
      Objects.requireNonNull(stamp);
      Objects.requireNonNull(key);
    }
  }

  private final UiScope scope;
  private final Runnable requireRead;
  private final Consumer<FieldBinding<?, ?>> requireTarget;
  private final Supplier<ReferenceActions<String, V>> sourceFactory;
  private final Policy policy;
  private final LongSupplier clock;
  private final UiReadKey<State> stateKey;
  private final UiRead<State> state;
  private ReferenceActions<String, V> source;
  private ReferenceBrowser<V> browser;
  private ReferenceBrowser.Search search;
  private OperationHandle<?> retiring;
  private String prefix = "", active;
  private long generation, deadline, observationRevision;
  private boolean demanded, complete;
  private boolean enumerating;
  private int pages;
  private Optional<ReferenceQuery.Cursor> cursor = Optional.empty();
  private final ArrayList<Choice> choices = new ArrayList<>();
  private PickerState.Phase phase = PickerState.Phase.IDLE;
  private ValidationReport report = ValidationReport.VALID;
  private ValidationReport observedFailure;
  private Object demandToken = new Object();
  private WeakReference<UiSnapshot<State>> cached;

  ReferenceSuggestions(
      UiScope parent,
      Runnable requireRead,
      Consumer<FieldBinding<?, ?>> requireTarget,
      Supplier<ReferenceActions<String, V>> sourceFactory,
      Policy policy,
      LongSupplier clock) {
    this.requireRead = Objects.requireNonNull(requireRead);
    this.requireTarget = Objects.requireNonNull(requireTarget);
    this.sourceFactory = Objects.requireNonNull(sourceFactory);
    this.policy = Objects.requireNonNull(policy);
    this.clock = Objects.requireNonNull(clock);
    scope = parent.child();
    try {
      stateKey =
          new UiReadKey<>(
              DocumentId.parse("reform:reference_suggestions"),
              SnapshotPolicy.immutable(),
              SnapshotContext.Limits.CONFIGURATION,
              parent.limits().maximumStateBytes(),
              ReferenceSuggestions::weight);
      scope.onRetire(
          () -> {
            demandToken = new Object();
            releaseBrowser();
            choices.clear();
            source = null;
            prefix = "";
            active = null;
            retiring = null;
            cached = null;
          });
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

  public UiRead<State> state() {
    return state;
  }

  public Policy policy() {
    return policy;
  }

  public boolean isOpen() {
    return scope.isOpen();
  }

  /** The native input must belong to this controller's exact actor and declared reference role. */
  public void requireField(FieldBinding<?, ?> field) {
    scope.requireCurrent();
    requireRead.run();
    requireTarget.accept(Objects.requireNonNull(field));
  }

  /** Raw input remains in its original owner; this is only a latest-only read demand. */
  public void request(String next) {
    Objects.requireNonNull(next);
    scope.update(
        () -> {
          var token = new Object();
          demandToken = token;
          requireRead.run();
          requireDemand(token);
          if (observedFailure != null || browser != null && !browser.current()) releaseBrowser();
          requireDemand(token);
          observedFailure = null;
          cancelSearch();
          requireDemand(token);
          generation = Math.incrementExact(generation);
          choices.clear();
          active = null;
          pages = 0;
          cursor = Optional.empty();
          complete = false;
          enumerating = false;
          if (next.length() > policy.maximumPrefixCharacters()) {
            var refused =
                new PolicyRefusal(
                    "reference.completion_limit",
                    "This ID is too long for suggestions. Keep editing it manually.");
            prefix = "";
            demanded = false;
            phase = PickerState.Phase.REFUSED;
            report = refused.report();
            publish();
            throw refused;
          }
          prefix = next;
          demanded = true;
          deadline = clock.getAsLong() + policy.debounceNanos();
          phase = PickerState.Phase.DEBOUNCING;
          report = ValidationReport.VALID;
          publish();
        });
  }

  /** Only a host update may enter the existing source service; at most one new page per turn. */
  public void update() {
    scope.update(
        () -> {
          var token = demandToken;
          try {
            requireRead.run();
            if (token != demandToken) return;
            if (observedFailure != null) {
              withdraw(observedFailure);
              publish();
              return;
            }
            if (state.snapshot() instanceof UiObservation.Unavailable<State> missing) {
              withdraw(missing.report());
              publish();
              return;
            }
            if (retiring != null && retiring.snapshot().terminal()) retiring = null;
            if (browser != null) browser.requireCurrent();
            if (token != demandToken) return;
            if (search != null && search.operation().snapshot().terminal()) poll(token);
            if (token != demandToken) return;
            if (demanded && clock.getAsLong() - deadline >= 0) {
              if (retiring != null) phase = PickerState.Phase.DRAINING;
              else {
                if (browser == null) {
                  var candidate = Objects.requireNonNull(sourceFactory.get());
                  if (token != demandToken) return;
                  var admitted = new ReferenceBrowser<>(candidate);
                  if (token != demandToken) {
                    admitted.close();
                    return;
                  }
                  source = candidate;
                  browser = admitted;
                }
                browser.requireCurrent();
                if (token != demandToken) return;
                if (prefix.length() > source.limits().maximumQueryLength()) enumerating = true;
                demanded = false;
                var selected = browser;
                var started =
                    selected.search(
                        EntryPoint.GUI,
                        new ReferenceQuery(
                            enumerating ? "" : prefix,
                            Math.min(
                                enumerating ? 64 : policy.maximumChoices(),
                                source.limits().maximumPageSize()),
                            cursor,
                            enumerating
                                ? ReferenceQuery.Match.TEXT
                                : ReferenceQuery.Match.KEY_PREFIX));
                if (token != demandToken || browser != selected) {
                  if (!started.operation().snapshot().terminal())
                    started.operation().requestCancellation();
                  return;
                }
                search = started;
                phase = PickerState.Phase.RUNNING;
              }
            }
            publish();
            if (state.snapshot() instanceof UiObservation.Unavailable<State> missing) {
              withdraw(missing.report());
              publish();
            } else if (observedFailure != null) {
              withdraw(observedFailure);
              publish();
            }
          } catch (PolicyRefusal failed) {
            if (token != demandToken) return;
            withdraw(failed.report());
            publish();
          } catch (RuntimeException failed) {
            if (token != demandToken) return;
            withdraw(
                new PolicyRefusal(
                        "reference.completion_failed",
                        "Suggestions could not be read. Continue editing the ID, or reopen suggestions to retry.")
                    .report());
            publish();
          }
        });
  }

  private void poll(Object token) {
    var selected = browser;
    var pending = search;
    boolean accepted = selected.accept(pending);
    if (token != demandToken || browser != selected || search != pending) return;
    if (!accepted) {
      var outcome = pending.operation().snapshot();
      withdraw(
          outcome.report().valid()
              ? new PolicyRefusal(
                      "reference.completion_failed",
                      "Suggestions did not finish. Keep editing, or reopen them to retry.")
                  .report()
              : outcome.report());
      publish();
      return;
    }
    var page = pending.operation().snapshot().result().orElseThrow();
    if (page.availability() == ReferencePage.Availability.UNSUPPORTED && !enumerating) {
      search = null;
      browser.clearSearch();
      enumerating = true;
      cursor = Optional.empty();
      demanded = true;
      deadline = clock.getAsLong();
      return;
    }
    if (page.availability() != ReferencePage.Availability.READY) {
      withdraw(
          new PolicyRefusal(
                  "reference.completion_unavailable",
                  page.reason().isBlank()
                      ? "Suggestions are unavailable. Continue editing the ID manually."
                      : page.reason())
              .report());
      publish();
      return;
    }
    pages++;
    boolean omitted = false;
    for (var choice : page.choices()) {
      if (!choice.key().startsWith(prefix)) continue;
      if (choices.stream().anyMatch(existing -> existing.key().equals(choice.key())))
        throw new PolicyRefusal(
            "reference.completion_overlap",
            "The source repeated an ID. Reopen suggestions to retry.");
      if (choices.size() == policy.maximumChoices()) {
        omitted = true;
        break;
      }
      choices.add(new Choice(choice.key(), choice.label()));
    }
    cursor = page.next();
    complete = page.exhausted() && !omitted;
    search = null;
    browser.clearSearch();
    if (complete || choices.size() == policy.maximumChoices() || pages >= policy.maximumPages()) {
      phase = PickerState.Phase.READY;
      demanded = false;
    } else {
      demanded = true;
      deadline = clock.getAsLong();
    }
  }

  /** Moves a local active suggestion only; it never edits or resolves a domain value. */
  public void move(int direction) {
    if (direction != -1 && direction != 1)
      throw new IllegalArgumentException("Use one suggestion step");
    scope.update(
        () -> {
          var token = demandToken;
          requireRead.run();
          requireDemand(token);
          requireAdmittedState();
          if (browser == null || choices.isEmpty()) return;
          browser.requireCurrent();
          requireDemand(token);
          int index = -1;
          for (int i = 0; i < choices.size(); i++)
            if (choices.get(i).key().equals(active)) index = i;
          int next =
              index < 0
                  ? (direction > 0 ? 0 : choices.size() - 1)
                  : Math.clamp(index + direction, 0, choices.size() - 1);
          active = choices.get(next).key();
          publish();
        });
  }

  /** Read admission only. The caller must still use its exact original input's edit guards. */
  public Optional<Option> activeOption() {
    scope.requireCurrent();
    requireRead.run();
    requireAdmittedState();
    if (browser == null || active == null) return Optional.empty();
    var option = new Option(browser.stamp(), generation, active);
    requireChoice(option);
    if (!option.key().equals(active)) throw stale();
    return Optional.of(option);
  }

  /** Explicit local navigation in the current suggestion offer; not an edit or resolution. */
  public void highlight(Option option) {
    scope.update(
        () -> {
          String key = requireChoice(option);
          active = key;
          publish();
        });
  }

  /** Read admission only. The caller must still use its exact original input's edit guards. */
  public String requireChoice(Option option) {
    Objects.requireNonNull(option);
    scope.requireCurrent();
    requireRead.run();
    requireAdmittedState();
    var selected = browser;
    var token = demandToken;
    if (!matches(selected, option)) throw stale();
    selected.requireCurrent();
    requireAdmittedState();
    if (token != demandToken || browser != selected || !matches(selected, option)) throw stale();
    return option.key();
  }

  private boolean matches(ReferenceBrowser<V> selected, Option option) {
    return selected != null
        && selected.stamp().equals(option.stamp())
        && generation == option.generation()
        && choices.stream().anyMatch(choice -> choice.key().equals(option.key()));
  }

  private static PolicyRefusal stale() {
    return new PolicyRefusal(
        "reference.completion_stale", "The suggestions changed. Choose a current ID.");
  }

  private void requireDemand(Object token) {
    if (token != demandToken)
      throw new PolicyRefusal(
          "reference.completion_superseded", "A newer suggestion request replaced this one.");
  }

  /** Blur/Escape/hidden presentation withdraw suggestions without accepting anything. */
  public void suspend() {
    scope.update(
        () -> {
          demandToken = new Object();
          prefix = "";
          generation = Math.incrementExact(generation);
          choices.clear();
          active = null;
          complete = false;
          pages = 0;
          phase = PickerState.Phase.IDLE;
          report = ValidationReport.VALID;
          observedFailure = null;
          releaseBrowser();
          publish();
        });
  }

  private void withdraw(ValidationReport reason) {
    demandToken = new Object();
    choices.clear();
    active = null;
    complete = false;
    phase = PickerState.Phase.REFUSED;
    report = reason;
    prefix = "";
    observedFailure = reason;
    releaseBrowser();
  }

  private void publish() {
    if (scope.isOpen()) scope.refreshObservation(state);
  }

  private void requireAdmittedState() {
    if (observedFailure != null) throw new PolicyRefusal(observedFailure);
    if (state.snapshot() instanceof UiObservation.Unavailable<State> missing)
      throw new PolicyRefusal(missing.report());
  }

  private void cancelSearch() {
    if (search != null && !search.operation().snapshot().terminal()) retiring = search.operation();
    search = null;
    if (browser != null) browser.clearSearch();
  }

  private void releaseBrowser() {
    var selected = browser;
    var pending = search;
    browser = null;
    search = null;
    demanded = false;
    cursor = Optional.empty();
    if (pending != null && !pending.operation().snapshot().terminal())
      retiring = pending.operation();
    if (selected != null) selected.close();
  }

  private UiObservation<State> observe() {
    scope.requireCurrent();
    State facts;
    try {
      requireRead.run();
      if (observedFailure != null) throw new PolicyRefusal(observedFailure);
      if (browser != null) browser.requireCurrent();
      facts =
          new State(
              prefix,
              generation,
              phase,
              Optional.ofNullable(browser).map(ReferenceBrowser::stamp),
              choices,
              Optional.ofNullable(active),
              pages,
              complete,
              search == null
                  ? Optional.empty()
                  : Optional.of(PickerState.Work.of(search.operation())),
              report);
    } catch (PolicyRefusal failed) {
      observedFailure = failed.report();
      facts =
          new State(
              "",
              generation,
              PickerState.Phase.REFUSED,
              Optional.empty(),
              List.of(),
              Optional.empty(),
              0,
              false,
              Optional.empty(),
              failed.report());
    }
    if (weight(facts) > scope.limits().maximumStateBytes())
      throw new PolicyRefusal(
          "ui.budget_exceeded",
          "Suggestions exceed the UI memory allowance. Continue editing the ID manually.");
    var captured = stateKey.capture(facts);
    var previous = cached == null ? null : cached.get();
    if (previous != null && previous.sameData(captured)) captured = previous;
    else observationRevision = Math.incrementExact(observationRevision);
    cached = new WeakReference<>(captured);
    return new UiObservation.Available<>(captured, observationRevision);
  }

  private static long weight(State value) {
    long bytes = 256L + 2L * value.prefix().length();
    for (var choice : value.choices())
      bytes += 64L + 2L * (choice.key().length() + choice.title().length());
    bytes += 2L * value.report().toString().length();
    if (value.work().isPresent()) bytes += 2L * value.work().orElseThrow().toString().length();
    if (value.stamp().isPresent()) bytes += 2L * value.stamp().orElseThrow().toString().length();
    return bytes;
  }
}
