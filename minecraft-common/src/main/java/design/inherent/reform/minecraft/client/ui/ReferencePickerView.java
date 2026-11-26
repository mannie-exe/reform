package design.inherent.reform.minecraft.client.ui;

import design.inherent.reform.definition.DocumentId;
import design.inherent.reform.owner.PolicyRefusal;
import design.inherent.reform.presentation.ViewStateKey;
import design.inherent.reform.presentation.ui.*;
import design.inherent.reform.value.SnapshotContext;
import design.inherent.reform.value.SnapshotPolicy;
import design.inherent.reform.value.ValueTypes;
import java.util.*;
import java.util.function.Function;
import java.util.function.ToLongFunction;
import net.minecraft.network.chat.Component;

/**
 * The same shared source-backed picker assembly for inline and task placements. No actor is made.
 */
public final class ReferencePickerView {
  public record RoleAction<T>(PickerRole<T> role, UiText label) {
    public RoleAction {
      Objects.requireNonNull(role);
      Objects.requireNonNull(label);
    }
  }

  /** Purpose and domain role captions belong to the owner; common interaction copy belongs here. */
  public record Presentation<T>(
      UiText title,
      UiText purpose,
      UiText listLabel,
      UiText searchLabel,
      List<RoleAction<T>> roles,
      int visibleRows,
      int overscan) {
    public Presentation {
      Objects.requireNonNull(title);
      Objects.requireNonNull(purpose);
      Objects.requireNonNull(listLabel);
      Objects.requireNonNull(searchLabel);
      roles = List.copyOf(roles);
      if (visibleRows < 1 || visibleRows > 12 || overscan < 0 || overscan > 4)
        throw new IllegalArgumentException("Invalid picker row offer");
    }
  }

  /** No fixed role means pin inspection only. A fixed role stages precisely that declared field. */
  public static <T, V> ViewSpec build(
      UiScope parent,
      ReferencePicker<T, V> picker,
      Presentation<T> presentation,
      Optional<PickerRole<T>> fixedRole) {
    Objects.requireNonNull(picker);
    Objects.requireNonNull(presentation);
    Objects.requireNonNull(fixedRole);
    if (!presentation.roles().stream().map(RoleAction::role).toList().equals(picker.roles())
        || fixedRole.filter(role -> !picker.roles().contains(role)).isPresent())
      throw new IllegalArgumentException("Use the picker's exact roles in their authored order");
    if (picker.policy().maximumQueryCharacters() > 4096)
      throw new IllegalArgumentException(
          "Declare a query allowance supported by the native query input");
    var scope = parent.child();
    var state = picker.state();
    var inputs = List.<UiRead<?>>of(state);
    UiRead<ChoiceListData> data =
        scope.derive(
            key(
                "window",
                scope,
                value -> {
                  long size = 128;
                  for (var item : value.items())
                    size +=
                        64
                            + 2L
                                * (item.key().length()
                                    + item.title().length()
                                    + item.detail().length());
                  return size;
                }),
            inputs,
            values -> {
              var current = values.value(state);
              var items =
                  current.window().choices().stream()
                      .map(
                          choice -> new ChoiceListData.Item(choice.key(), choice.label(), "", true))
                      .toList();
              return new ChoiceListData(
                  current.stamp().map(stamp -> stamp.snapshot().toString()).orElse("unavailable")
                      + ":"
                      + current.queryGeneration(),
                  items,
                  OptionalLong.empty(),
                  current.window().before(),
                  current.window().after());
            });
    UiRead<Optional<String>> active =
        scope.derive(
            key("active", scope, value -> 32L + value.map(text -> 2L * text.length()).orElse(0L)),
            inputs,
            values -> values.value(state).active());
    UiRead<Set<String>> accepted =
        scope.derive(
            key(
                "accepted",
                scope,
                value -> 64L + value.stream().mapToLong(text -> 32L + 2L * text.length()).sum()),
            inputs,
            values -> {
              var current = values.value(state);
              var keys = new LinkedHashSet<String>();
              for (var role : picker.roles())
                if (fixedRole.isEmpty() || fixedRole.orElseThrow().equals(role)) {
                  var requested = current.requested().get(role.id());
                  if (requested != null) requested.raw().ifPresent(keys::add);
                }
              return Set.copyOf(keys);
            });
    UiRead<PickerState.Query> query =
        scope.derive(
            key("query", scope, value -> 64L + 2L * value.text().length()),
            inputs,
            values -> values.value(state).query());
    UiCommand<Optional<String>> activeChanges =
        map(
            picker.active(),
            key ->
                option(
                    state,
                    key.orElseThrow(
                        () ->
                            new PolicyRefusal(
                                "reference.no_active_choice", "Choose an available row."))),
            inputs);
    UiBinding<Optional<String>> selection =
        new UiBinding<>() {
          public UiRead<Optional<String>> value() {
            return active;
          }

          public UiCommand<Optional<String>> changes() {
            return activeChanges;
          }
        };
    UiCommand<String> accept;
    if (fixedRole.isPresent())
      accept = map(picker.activate(fixedRole.orElseThrow()), key -> option(state, key), inputs);
    else
      accept =
          map(
              picker.activate(),
              key -> new PickerState.Activation<T>(option(state, key), Optional.empty()),
              inputs);
    var paging =
        new UiCommand<ChoiceListParts.Boundary>() {
          public List<UiRead<?>> dependencies() {
            return inputs;
          }

          public UiAvailability availability() {
            return picker.nextPage().availability().available()
                    || picker.previousPage().availability().available()
                ? UiAvailability.READY
                : UiAvailability.blocked(
                    "reference.no_continuation", "No more results are available at this edge.");
          }

          public UiActionResult invoke(ChoiceListParts.Boundary edge) {
            var command =
                edge == ChoiceListParts.Boundary.AFTER ? picker.nextPage() : picker.previousPage();
            var ready = command.availability();
            return ready.available()
                ? command.invoke(null)
                : new UiActionResult.Refused(ready.report(), Optional.empty());
          }
        };
    var children = new ArrayList<ViewSpec>();
    children.add(Views.text("purpose", UiText.of(Component.empty()), presentation.purpose()));
    children.add(
        Views.text(
            "status",
            UiText.of(Component.empty()),
            UiText.from(state, ReferencePickerView::status)));
    children.add(
        Views.choiceList(
            "choices",
            new ChoiceListParts(
                presentation.listLabel(),
                UiText.of(Component.empty()),
                data,
                selection,
                accepted,
                accept,
                presentation.visibleRows(),
                presentation.overscan(),
                Optional.of(paging))));
    children.add(
        Views.queryInput(
            "search",
            new QueryInputParts(
                presentation.searchLabel(),
                UiText.of(
                    Component.translatableWithFallback(
                        "reform.ui.search_help", "Search available values.")),
                query,
                picker.query(),
                picker.flushSearch(),
                picker.policy().maximumQueryCharacters())));
    children.add(
        Views.text(
            "inspection",
            UiText.from(state, ReferencePickerView::inspectionTitle),
            UiText.from(state, ReferencePickerView::inspectionStatus)));
    if (fixedRole.isEmpty())
      for (var role : presentation.roles())
        children.add(
            Views.action(
                "use-" + role.role().id(),
                role.label(),
                UiText.of(Component.empty()),
                picker.acceptInspected(role.role())));
    // Continuations also remain reachable when a filtered scan returns an empty intermediate page.
    children.add(
        Views.action(
            "continue",
            UiText.of(Component.translatableWithFallback("reform.ui.more_choices", "More choices")),
            UiText.of(Component.empty()),
            picker.nextPage()));
    children.add(
        Views.action(
            "refresh",
            UiText.of(
                Component.translatableWithFallback("reform.ui.refresh_choices", "Refresh choices")),
            UiText.of(Component.empty()),
            picker.refresh()));
    var detailsOpen =
        scope.local(
            new ViewStateKey<>(DocumentId.parse("reform:picker_details_open"), ValueTypes.bool()),
            false);
    children.add(
        Views.disclosure(
            "source-details",
            UiBindings.local(detailsOpen),
            DisclosureParts.retained(
                UiText.of(
                    Component.translatableWithFallback(
                        "reform.ui.source_details", "Source details")),
                UiText.of(Component.empty()),
                UiText.of(Component.empty()),
                branch -> {
                  UiRead<List<String>> blocks =
                      branch.derive(
                          key(
                              "details",
                              branch,
                              value ->
                                  64L
                                      + value.stream()
                                          .mapToLong(text -> 32L + 2L * text.length())
                                          .sum()),
                          inputs,
                          values -> details(values.value(state)));
                  var count =
                      branch.derive(
                          new ViewStateKey<>(
                              DocumentId.parse("reform:picker_detail_count"),
                              ValueTypes.integer(0, 128)),
                          List.of(blocks),
                          values -> values.value(blocks).size());
                  return Views.repeat(
                      "blocks",
                      count,
                      0,
                      128,
                      (row, index) ->
                          Views.text(
                              "detail",
                              UiText.of(Component.empty()),
                              UiText.from(blocks, values -> Component.literal(values.get(index)))));
                })));
    return Views.managed(
        "picker-interest",
        new UiMountLifecycle() {
          private ReferencePicker<T, V>.Interest interest;

          public void update() {
            if (interest == null) interest = picker.mount();
            interest.update();
          }

          public void suspend() {
            if (interest != null) {
              var previous = interest;
              interest = null;
              previous.close();
            }
          }
        },
        Views.section("picker", presentation.title(), children));
  }

  private static <T> UiReadKey<T> key(String name, UiScope scope, ToLongFunction<T> weight) {
    return new UiReadKey<>(
        DocumentId.parse("reform:picker_view_" + name),
        SnapshotPolicy.immutable(),
        SnapshotContext.Limits.CONFIGURATION,
        scope.limits().maximumStateBytes(),
        weight);
  }

  private static PickerState.Option option(UiRead<PickerState> read, String key) {
    var state = read.snapshot().value();
    return new PickerState.Option(
        state
            .stamp()
            .orElseThrow(
                () ->
                    new PolicyRefusal(
                        "reference.not_admitted", "Open or refresh these choices first.")),
        state.queryGeneration(),
        key);
  }

  private static <I, O> UiCommand<I> map(
      UiCommand<O> delegate, Function<I, O> input, List<UiRead<?>> extra) {
    var dependencies = new ArrayList<UiRead<?>>(delegate.dependencies());
    dependencies.addAll(extra);
    return new UiCommand<>() {
      public List<UiRead<?>> dependencies() {
        return List.copyOf(dependencies);
      }

      public UiAvailability availability() {
        return delegate.availability();
      }

      public UiActionResult invoke(I value) {
        try {
          return delegate.invoke(input.apply(value));
        } catch (PolicyRefusal refused) {
          return new UiActionResult.Refused(refused.report(), Optional.of(refused));
        }
      }
    };
  }

  private static Component status(PickerState value) {
    return switch (value.source()) {
      case UNADMITTED ->
          Component.translatableWithFallback(
              "reform.ui.choices_not_open", "Choices have not been opened.");
      case SUSPENDED ->
          Component.translatableWithFallback(
              "reform.ui.choices_suspended", "Choices are hidden. The query is retained.");
      case STALE ->
          Component.translatableWithFallback(
              "reform.ui.choices_stale", "The source changed. Refresh choices to continue.");
      case UNAVAILABLE ->
          failureStatus(
              value,
              Component.translatableWithFallback(
                  "reform.ui.choices_unavailable",
                  "Choices are unavailable. Refresh choices to retry."));
      case READY ->
          switch (value.searching()) {
            case RUNNING, DRAINING, DEBOUNCING ->
                Component.translatableWithFallback("reform.ui.finding_choices", "Finding choices…");
            case FAILED, REFUSED, CANCELLED ->
                failureStatus(
                    value,
                    Component.translatableWithFallback(
                        "reform.ui.search_stopped",
                        "The search did not finish. Refresh choices to retry."));
            default ->
                !value.window().choices().isEmpty()
                    ? Component.empty()
                    : value.window().after()
                        ? Component.translatableWithFallback(
                            "reform.ui.search_incomplete",
                            "No choices in this window. Continue the search for more results.")
                        : Component.translatableWithFallback(
                            "reform.ui.no_matching_choices",
                            "No matching choices. Change the search.");
          };
    };
  }

  private static Component failureStatus(PickerState state, Component fallback) {
    var reason =
        state
            .page()
            .filter(
                page ->
                    page.availability()
                        != design.inherent.reform.reference.ReferencePage.Availability.READY)
            .map(design.inherent.reform.reference.ReferencePage::reason)
            .filter(text -> !text.isBlank());
    if (reason.isEmpty())
      reason =
          state.report().issues().stream()
              .map(issue -> issue.fallback())
              .filter(text -> !text.isBlank())
              .findFirst();
    if (reason.isEmpty())
      reason =
          state.searchWork().stream()
              .flatMap(work -> work.report().stream())
              .flatMap(report -> report.issues().stream())
              .map(issue -> issue.fallback())
              .filter(text -> !text.isBlank())
              .findFirst();
    boolean unavailable =
        state.source() == PickerState.Source.UNAVAILABLE
            || state
                .page()
                .filter(
                    page ->
                        page.availability()
                            != design.inherent.reform.reference.ReferencePage.Availability.READY)
                .isPresent();
    return reason
        .<Component>map(
            text ->
                Component.translatableWithFallback(
                    unavailable
                        ? "reform.ui.choices_failed_cause"
                        : "reform.ui.search_failed_cause",
                    unavailable ? "Choices are unavailable. %s" : "The search did not finish. %s",
                    Component.literal(text)))
        .orElse(fallback);
  }

  private static Component inspectionTitle(PickerState state) {
    if (state.inspection().isEmpty())
      return Component.translatableWithFallback("reform.ui.inspection", "Inspection");
    return state
        .inspection()
        .orElseThrow()
        .title()
        .map(Component::literal)
        .map(value -> (Component) value)
        .orElseGet(
            () ->
                Component.translatableWithFallback(
                    "reform.ui.inspected_reference", "Inspected reference"));
  }

  private static Component inspectionStatus(PickerState state) {
    if (state.inspecting() == PickerState.Phase.RUNNING
        || state.inspecting() == PickerState.Phase.DRAINING)
      return Component.translatableWithFallback(
          "reform.ui.inspecting_choice", "Inspecting this choice…");
    if (state.inspection().isEmpty())
      return Component.translatableWithFallback(
          "reform.ui.inspect_choice_help", "Choose a row to inspect it.");
    return switch (state.inspection().orElseThrow().status()) {
      case RESOLVED -> Component.translatableWithFallback("reform.ui.choice_resolved", "Available");
      case UNKNOWN_REFERENCE ->
          Component.translatableWithFallback("reform.ui.choice_unknown", "Unknown reference");
      case MALFORMED ->
          Component.translatableWithFallback("reform.ui.choice_malformed", "Invalid ID");
      case MISSING_DEPENDENCY ->
          Component.translatableWithFallback(
              "reform.ui.choice_dependency_missing", "A required source is missing");
      case UNAVAILABLE_CONTEXT ->
          Component.translatableWithFallback(
              "reform.ui.choice_context_unavailable", "This source is unavailable");
    };
  }

  private static List<String> details(PickerState state) {
    var blocks = new ArrayList<String>();
    state.page().ifPresent(page -> add(blocks, "Reason", page.reason()));
    state
        .stamp()
        .ifPresent(
            stamp -> {
              add(blocks, "Source", stamp.source().toString());
              add(blocks, "Revision", stamp.revision());
            });
    state.inspectionRequest().ifPresent(raw -> add(blocks, "ID", raw));
    state
        .inspection()
        .ifPresent(
            value -> {
              add(blocks, "Reason", value.reason());
              for (var fact : value.facts().entrySet()) add(blocks, fact.getKey(), fact.getValue());
            });
    for (var requested : state.requested().entrySet())
      requested.getValue().raw().ifPresent(raw -> add(blocks, "Draft " + requested.getKey(), raw));
    appendReport(blocks, state.report());
    for (var work : List.of(state.searchWork(), state.lookupWork(), state.editWork()))
      work.ifPresent(value -> value.report().ifPresent(report -> appendReport(blocks, report)));
    return List.copyOf(blocks);
  }

  private static void appendReport(
      List<String> blocks, design.inherent.reform.validation.ValidationReport report) {
    for (var issue : report.issues()) {
      add(blocks, "Reason", issue.fallback());
      add(blocks, "Code", issue.code());
    }
  }

  private static void add(List<String> blocks, String label, String value) {
    if (value.isEmpty()) return;
    String text = label + ": " + value;
    for (int start = 0; start < text.length(); ) {
      if (blocks.size() == 128)
        throw new PolicyRefusal(
            "reference.detail_limit",
            "These details exceed the view allowance. Reduce the source detail or use a larger admitted inspector.");
      int end = Math.min(text.length(), start + 4096);
      if (end < text.length()
          && Character.isHighSurrogate(text.charAt(end - 1))
          && Character.isLowSurrogate(text.charAt(end))) end--;
      blocks.add(text.substring(start, end));
      start = end;
    }
  }

  private ReferencePickerView() {}
}
