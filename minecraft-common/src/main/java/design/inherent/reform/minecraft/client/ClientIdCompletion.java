package design.inherent.reform.minecraft.client;

import com.mojang.blaze3d.platform.InputConstants;
import design.inherent.reform.definition.DocumentId;
import design.inherent.reform.minecraft.client.ui.ChoiceListData;
import design.inherent.reform.minecraft.client.ui.CompletionParts;
import design.inherent.reform.owner.PolicyRefusal;
import design.inherent.reform.presentation.ThemeValues.Role;
import design.inherent.reform.presentation.ui.*;
import design.inherent.reform.value.SnapshotContext;
import design.inherent.reform.value.SnapshotPolicy;
import java.util.*;
import net.minecraft.network.chat.Component;

/** Field-owned popup over the original text input. Only explicit acceptance invokes its writer. */
final class ClientIdCompletion implements AutoCloseable {
  private record Prepared(
      Optional<ClientTextInput.State> input,
      ChoiceListData data,
      Map<String, ReferenceSuggestions.Option> options,
      Optional<String> active,
      String label,
      String status,
      String narration) {
    Prepared {
      options = Map.copyOf(options);
    }

    static Prepared empty() {
      return new Prepared(
          Optional.empty(),
          new ChoiceListData("", List.of(), OptionalLong.empty()),
          Map.of(),
          Optional.empty(),
          "",
          "",
          "");
    }

    long weight() {
      long bytes = 512L + 4L * (label.length() + status.length() + narration.length());
      if (input.isPresent()) {
        var state = input.orElseThrow();
        bytes +=
            4L * state.raw().length()
                + 2L * state.stamp().draft.sourceRevision().length()
                + 2L * state.stamp().draft.document().toString().length();
      }
      for (var item : data.items())
        bytes += 256L + 4L * (item.key().length() + item.title().length() + item.detail().length());
      for (var option : options.values())
        bytes +=
            256L
                + 2L
                    * (option.key().length()
                        + option.stamp().revision().length()
                        + option.stamp().source().toString().length());
      return bytes;
    }
  }

  private final UiScope scope;
  private final ClientTextInput input;
  private final ReferenceSuggestions<?> source;
  private final int requestedRows;
  private final SettingsView<?> view;
  private final ChoiceListWidget list;
  private final UiReadKey<Prepared> key;
  private final UiRead<Prepared> admitted;
  private Prepared prepared = Prepared.empty();
  private CompletionPlacement placement;
  private Component label = Component.empty();
  private FormEditBox widget;
  private ClientTheme theme;
  private long revision = 1;
  private int rootWidth, rootHeight;
  private boolean attached, wanted, closed;
  private ClientTextInput.Stamp dismissed;

  ClientIdCompletion(UiScope parent, ClientTextInput input, CompletionParts parts) {
    this.input = Objects.requireNonNull(input);
    source = parts.suggestions();
    requestedRows = parts.visibleRows();
    view = input.view();
    scope = parent.child();
    try {
      key =
          new UiReadKey<>(
              DocumentId.parse("reform:completion_popup"),
              SnapshotPolicy.immutable(),
              SnapshotContext.Limits.CONFIGURATION,
              parent.limits().maximumStateBytes(),
              Prepared::weight);
      admitted =
          scope.observe(key, () -> new UiObservation.Available<>(key.capture(prepared), revision));
      list = new ChoiceListWidget(Component.empty());
      scope.onRetire(this::retire);
      view.addCompletion(this);
    } catch (RuntimeException | Error failure) {
      scope.close();
      throw failure;
    }
  }

  void attach(Component label, ClientTheme theme) {
    if (closed) return;
    this.label = label.copy();
    this.theme = theme;
    attached = true;
  }

  void update() {
    if (closed || !attached || !scope.isOpen()) return;
    try {
      scope.refreshObservation(input.state());
      var observation = input.state().snapshot();
      if (!(observation instanceof UiObservation.Available<ClientTextInput.State> available)) {
        dismiss(false);
        return;
      }
      var current = available.data().value();
      widget = input.widget();
      if (!current.focused() || !current.editable() || !view.completionAnchor(widget)) {
        dismiss(false);
        return;
      }
      if (dismissed != null && sameBuffer(current.stamp(), dismissed)) return;
      var prior = prepared.input().orElse(null);
      if (!wanted || prior == null || !sameText(prior.stamp(), current.stamp())) {
        wanted = true;
        dismissed = null;
        publish(
            new Prepared(
                Optional.of(current),
                new ChoiceListData("", List.of(), OptionalLong.empty()),
                Map.of(),
                Optional.empty(),
                label.getString(),
                "",
                ""));
        source.request(current.raw());
      }
      source.update();
      var state = source.state().snapshot().value();
      var items = new ArrayList<ChoiceListData.Item>();
      var options = new LinkedHashMap<String, ReferenceSuggestions.Option>();
      if (state.stamp().isPresent())
        for (var choice : state.choices()) {
          String id = CompletionIdentity.key(choice.key());
          var option =
              new ReferenceSuggestions.Option(
                  state.stamp().orElseThrow(), state.generation(), choice.key());
          if (options.put(id, option) != null)
            throw new PolicyRefusal(
                "reference.completion_identity",
                "Suggestions could not be distinguished. Keep editing the ID manually.");
          boolean fits = choice.key().length() <= current.maximumCharacters();
          items.add(
              new ChoiceListData.Item(
                  id,
                  choice.title(),
                  fits ? choice.key() : "This ID exceeds the input's character limit.",
                  fits));
        }
      String status =
          !state.report().valid()
              ? state.report().issues().stream()
                  .map(issue -> issue.fallback())
                  .findFirst()
                  .orElse("Suggestions are unavailable.")
              : switch (state.phase()) {
                case DEBOUNCING -> "Waiting for typing to pause…";
                case RUNNING, DRAINING -> "Reading suggestions…";
                default ->
                    items.isEmpty()
                        ? "No suggestions. You can keep this ID."
                        : "Choose an ID, or keep typing.";
              };
      Optional<String> active = state.active().map(CompletionIdentity::key);
      String title = label.getString();
      String narration = title + ". " + status;
      if (active.isPresent()) {
        var selected =
            items.stream().filter(item -> item.key().equals(active.orElseThrow())).findFirst();
        if (selected.isPresent())
          narration +=
              " " + selected.orElseThrow().title() + ". " + selected.orElseThrow().detail();
      }
      if (!items.isEmpty())
        narration += " Use Up or Down to select, Enter to accept, or Escape to dismiss.";
      publish(
          new Prepared(
              Optional.of(current),
              new ChoiceListData(Long.toString(state.generation()), items, OptionalLong.empty()),
              options,
              active,
              title,
              status,
              narration));
      if (!wanted) return;
      measure();
      if (placement == null) {
        widget.completionNarration(
            Component.literal("Suggestions do not fit here. Continue editing the ID manually."));
        return;
      }
      list.setPosition(placement.x() + 2, placement.listY());
      list.setWidth(placement.width() - 4);
      list.prepare(
          prepared.data(),
          prepared.active(),
          Set.of(),
          placement.rows(),
          0,
          theme,
          this::highlight,
          selected -> accept(selected));
      list.active = true;
      list.setFocused(true); // local active descendant; native focus remains the original input
      widget.completionNarration(Component.literal(prepared.narration()));
    } catch (PolicyRefusal failure) {
      fail(failure.getMessage());
    } catch (IllegalArgumentException failure) {
      fail("Suggestions exceed this view's allowance. Keep editing the ID manually.");
    }
  }

  private static boolean sameText(ClientTextInput.Stamp a, ClientTextInput.Stamp b) {
    return a.producer == b.producer
        && a.control == b.control
        && a.epoch == b.epoch
        && a.draft.equals(b.draft)
        && a.raw.equals(b.raw);
  }

  private static boolean sameBuffer(ClientTextInput.Stamp a, ClientTextInput.Stamp b) {
    return a.producer == b.producer
        && a.control == b.control
        && a.raw.equals(b.raw)
        && a.caret == b.caret
        && a.anchor == b.anchor;
  }

  private void publish(Prepared next) {
    if (prepared.equals(next)) return;
    prepared = next;
    revision = Math.incrementExact(revision);
    scope.refreshObservation(admitted);
    if (admitted.snapshot() instanceof UiObservation.Unavailable<Prepared>)
      throw new PolicyRefusal(
          "ui.budget_exceeded",
          "Suggestions exceed this view's allowance. Keep editing the ID manually.");
  }

  private void measure() {
    var root = view.ownerScreen();
    rootWidth = root.width;
    rootHeight = root.height;
    int rowHeight = Math.max(20, theme.values().value(Role.CONTROL_HEIGHT));
    placement =
        CompletionPlacement.fit(
                rootWidth,
                rootHeight,
                widget.getX(),
                widget.getY(),
                widget.getWidth(),
                widget.getHeight(),
                rowHeight,
                requestedRows,
                view.font.lineHeight + 2,
                view.font.lineHeight + 2)
            .orElse(null);
  }

  private boolean visible() {
    return !closed
        && attached
        && wanted
        && placement != null
        && view.isCurrent()
        && widget != null
        && widget.isFocused()
        && widget.active
        && view.ownerScreen().width == rootWidth
        && view.ownerScreen().height == rootHeight;
  }

  Component narration() {
    return !closed && attached && widget != null && widget.isFocused() && view.isCurrent()
        ? Component.literal(prepared.narration())
        : Component.empty();
  }

  void render(FormCanvas canvas, int x, int y, float delta) {
    if (!visible()) return;
    ((NativeCanvas) canvas)
        .foreground(
            () -> {
              var at = placement;
              canvas.fill(at.x(), at.y(), at.x() + at.width(), at.y() + at.height(), 0xff202020);
              canvas.enableScissor(at.x(), at.y(), at.x() + at.width(), at.y() + at.height());
              try {
                canvas.drawString(view.font, prepared.label(), at.x() + 2, at.y() + 2, 0xffffffff);
                canvas.widget(list, x, y, delta);
                canvas.drawString(
                    view.font,
                    prepared.status(),
                    at.x() + 2,
                    at.listY() + list.getHeight() + 2,
                    0xffcccccc);
              } finally {
                canvas.disableScissor();
              }
            });
  }

  boolean covers(double x, double y) {
    return visible() && placement.contains(x, y);
  }

  boolean key(int key, int modifiers) {
    if (modifiers != 0
        || key != InputConstants.KEY_ESCAPE
            && key != InputConstants.KEY_UP
            && key != InputConstants.KEY_DOWN
            && key != InputConstants.KEY_RETURN) return false;
    if (closed || !attached) return false;
    update();
    if (!wanted || widget == null || !widget.isFocused()) return false;
    if (key == InputConstants.KEY_ESCAPE) {
      dismiss(true);
      return true;
    }
    if (key == InputConstants.KEY_RETURN) {
      prepared.active().ifPresent(this::accept);
      return true;
    }
    if (visible()) list.listKey(key);
    return true;
  }

  boolean click(double x, double y, int button) {
    if (!covers(x, y)) {
      if (wanted && (widget == null || !widget.isMouseOver(x, y))) dismiss(true);
      return false;
    }
    if (button == InputConstants.MOUSE_BUTTON_LEFT) list.listClick(x, y, button);
    return true;
  }

  boolean drag(double x, double y, int button) {
    return visible() && list.listDrag(x, y, button);
  }

  boolean release() {
    return list.listRelease();
  }

  boolean scroll(double x, double y, double horizontal, double vertical) {
    if (!covers(x, y)) return false;
    list.mouseScrolled(x, y, horizontal, vertical);
    return true;
  }

  private boolean currentInput() {
    scope.refreshObservation(input.state());
    var current = input.state().snapshot();
    return current instanceof UiObservation.Available<ClientTextInput.State> available
        && prepared.input().isPresent()
        && available.data().value().editable()
        && prepared.input().orElseThrow().stamp().equals(available.data().value().stamp());
  }

  private void highlight(String id) {
    try {
      var option = prepared.options().get(id);
      if (option == null || !currentInput()) {
        dismiss(true);
        return;
      }
      source.highlight(option);
      update();
    } catch (PolicyRefusal failure) {
      fail(failure.getMessage());
    }
  }

  private void accept(String id) {
    try {
      var option = prepared.options().get(id);
      if (option == null || !currentInput()) {
        dismiss(true);
        return;
      }
      String replacement = source.requireChoice(option);
      var before = prepared.input().orElseThrow();
      var result =
          input
              .replacements()
              .invoke(
                  new ClientTextInput.Replacement(
                      before.stamp(),
                      0,
                      before.raw().length(),
                      replacement,
                      replacement.length(),
                      replacement.length()));
      if (result instanceof UiActionResult.Refused refused) {
        fail(refused.report().issues().getFirst().fallback());
        return;
      }
      if (scope.isOpen()) {
        scope.refreshObservation(input.state());
        if (input.state().snapshot()
            instanceof UiObservation.Available<ClientTextInput.State> now) {
          publish(
              new Prepared(
                  Optional.of(now.data().value()),
                  new ChoiceListData("", List.of(), OptionalLong.empty()),
                  Map.of(),
                  Optional.empty(),
                  "",
                  "",
                  ""));
        }
        dismiss(true);
      }
    } catch (PolicyRefusal failure) {
      fail(failure.getMessage());
    }
  }

  private void fail(String reason) {
    dismiss(true);
    if (scope.isOpen())
      publish(
          new Prepared(
              prepared.input(),
              new ChoiceListData("", List.of(), OptionalLong.empty()),
              Map.of(),
              Optional.empty(),
              "",
              reason,
              reason));
    if (widget != null) widget.completionNarration(Component.literal(reason));
  }

  private void dismiss(boolean explicit) {
    if (explicit && prepared.input().isPresent())
      dismissed = prepared.input().orElseThrow().stamp();
    else if (!explicit) dismissed = null;
    boolean hadDemand = wanted;
    wanted = false;
    placement = null;
    list.clear();
    if (widget != null) widget.completionNarration(Component.empty());
    if (hadDemand && source.isOpen()) source.suspend();
    if (scope.isOpen())
      publish(
          new Prepared(
              explicit ? prepared.input() : Optional.empty(),
              new ChoiceListData("", List.of(), OptionalLong.empty()),
              Map.of(),
              Optional.empty(),
              "",
              "",
              ""));
  }

  void suspend() {
    if (closed) return;
    dismiss(false);
    attached = false;
    widget = null;
  }

  private void retire() {
    if (closed) return;
    suspend();
    closed = true;
    view.removeCompletion(this);
    prepared = Prepared.empty();
    label = Component.empty();
  }

  @Override
  public void close() {
    scope.close();
  }
}
