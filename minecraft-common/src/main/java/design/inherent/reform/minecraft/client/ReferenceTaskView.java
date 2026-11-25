package design.inherent.reform.minecraft.client;

import design.inherent.reform.definition.FieldPath;
import design.inherent.reform.edit.DraftNode;
import design.inherent.reform.operation.OperationDescriptor.EntryPoint;
import design.inherent.reform.operation.OperationHandle;
import design.inherent.reform.owner.OwnerContext;
import design.inherent.reform.owner.PolicyRefusal;
import design.inherent.reform.presentation.FormGeometry;
import design.inherent.reform.presentation.TaskPresentation;
import design.inherent.reform.presentation.ThemeValues;
import design.inherent.reform.reference.*;
import java.util.*;
import java.util.concurrent.CompletionException;
import java.util.function.Consumer;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;

/** Shared retained catalogue task. It borrows one editor and never owns a second settings draft. */
final class ReferenceTaskView<T, V> extends FormView implements AutoCloseable {
  private final ClientReferenceTask<T, V> declaration;
  private final ClientEditor<T> editor;
  private final ClientViewContext scope;
  private final ClientEditingWorkflow workflow;
  private OwnerContext sourceLifetime, graphicLifetime;
  private ReferenceBrowser<V> browser;
  private ReferenceBrowser.Search search;
  private ReferenceBrowser.Lookup<V> lookup;
  private OperationHandle<?> edit;
  private Throwable sourceFailure, graphicFailure;
  private Component notice = Component.empty();
  private FormEditBox filter, manual;
  private TaskPanels panels;
  private TaskPanels.State retained = TaskPanels.State.empty();
  private Consumer<FormPanel> prefix = ignored -> {};
  private boolean unified, visible, ended, started, details;
  private Object generation;
  private List<Button> choosing = List.of();
  private Preview preview;
  private Object observedOwner, observedGraphic;
  private ReferenceTitles.Snapshot observedTitles;
  private ClientGraphicHost.View<?, ?> graphicView;
  private boolean sourceWasCurrent, showPreviewPending;

  private interface Preview extends AutoCloseable {
    void append(FormPanel target);

    Object state();

    void visible(boolean value);

    @Override
    void close();
  }

  ReferenceTaskView(
      ClientReferenceTask<T, V> declaration,
      ClientEditor<T> editor,
      ClientViewContext scope,
      ClientEditingWorkflow workflow) {
    super(Component.literal(declaration.source().toString()));
    this.declaration = declaration;
    this.editor = editor;
    this.scope = scope;
    this.workflow = workflow;
    scope.onClose(this::close);
    // Source factories are entered below under their own disposable construction scope.
  }

  private static Component text(String key, String fallback) {
    return Component.translatableWithFallback("reform.task." + key, fallback);
  }

  private OwnerContext lifetime() {
    scope.requireCurrent();
    return new OwnerContext(
        net.minecraft.client.Minecraft.getInstance()::execute,
        design.inherent.reform.minecraft.internal.ClientSettingsRuntime.workers(),
        design.inherent.reform.minecraft.internal.ClientSettingsRuntime::requireClientThread,
        OwnerContext.Limits.STANDARD);
  }

  private void openSource() {
    closeSource();
    sourceLifetime = lifetime();
    try {
      var references = declaration.openSource(new ClientViewContext(sourceLifetime), editor);
      sourceLifetime.requireOwner();
      browser = new ReferenceBrowser<>(references);
      sourceFailure = null;
      sourceWasCurrent = true;
    } catch (RuntimeException | Error failure) {
      sourceFailure = failure;
      try {
        closeSource();
      } catch (RuntimeException | Error cleanup) {
        failure.addSuppressed(cleanup);
      }
    }
  }

  private void closeSource() {
    var previous = sourceLifetime;
    sourceLifetime = null;
    if (browser != null) browser.close();
    browser = null;
    search = null;
    lookup = null;
    if (previous != null) previous.close();
  }

  void compose(boolean unified, Consumer<FormPanel> prefix) {
    this.unified = unified;
    this.prefix = prefix;
  }

  FormPanel primary() {
    return panels.primary();
  }

  void preserve() {
    if (panels != null) retained = panels.remember(getFocused());
  }

  void visible(boolean value) {
    if (ended || visible == value) return;
    visible = value;
    if (preview != null) preview.visible(value);
    if (!value) {
      showPreviewPending = false;
      cancel(search == null ? null : search.operation());
      cancel(lookup == null ? null : lookup.operation());
    }
  }

  private static void cancel(OperationHandle<?> operation) {
    if (operation != null && !operation.snapshot().terminal()) operation.requestCancellation();
  }

  @Override
  protected void beforeResize() {
    preserve();
  }

  @Override
  protected void init() {
    generation = new Object();
    observedTitles = editor.presentation().referenceTitleSnapshot(declaration.source());
    if (filter == null) {
      filter = new FormEditBox(font, 0, 0, 180, 20, text("search", "Search"));
      filter.setMaxLength(declaration.limits().maximumQueryLength());
      manual = new FormEditBox(font, 0, 0, 180, 20, text("id", "Reference ID"));
      manual.setMaxLength(32768);
    }
    int gap = theme().values().value(ThemeValues.Role.SPACE_CONTENT);
    int control = theme().values().value(ThemeValues.Role.CONTROL_HEIGHT);
    int minimum =
        Math.max(
            theme().values().value(ThemeValues.Role.MEASURE_INFORMATION_MINIMUM),
            Math.addExact(ControlSizing.preferred(filter).width(), 2 * gap));
    int detailMinimum =
        Math.max(
            minimum,
            declaration
                .graphic()
                .map(spec -> FormPanel.widthForContent(subjectWidth(spec), theme(), 1))
                .orElse(0));
    panels =
        new TaskPanels(
            new FormGeometry.Placement(left, top, width, height),
            unified,
            ((NativeFormScreen) ownerScreen()).taskPresentation(),
            TaskPresentation.Kind.CHOOSER,
            new TaskPresentation.Offer(
                new FormGeometry.Size(minimum, 3 * font.lineHeight + control + gap),
                Math.max(minimum, width / 2)),
            new TaskPresentation.Offer(
                new FormGeometry.Size(detailMinimum, 3 * font.lineHeight + control + gap),
                detailMinimum),
            theme(),
            retained);
    var list = panels.primary();
    var detail = panels.detail();
    prefix.accept(list);
    if (!declaration.help().getString().isEmpty()) list.row(Component.empty(), declaration.help());
    list.beginGroup("primary:search", text("search", "Search"));
    list.field(
        "primary:filter",
        Component.empty(),
        text("search_help", "Search available values."),
        filter,
        button(text("search", "Search"), () -> search(Optional.empty())));
    list.actions("primary:refresh", button(text("refresh", "Refresh source"), this::refresh));
    list.endGroup();
    list.beginGroup("primary:choices", text("choices", "Choices"));
    if (browser == null)
      list.row(
          started
              ? text("unavailable", "Source unavailable")
              : text("opening", "Opening catalogue"),
          started ? reason(sourceFailure) : Component.empty());
    else
      browser
          .page()
          .ifPresent(
              page -> {
                if (page.availability() != ReferencePage.Availability.READY) {
                  list.row(
                      text("unavailable", "Source unavailable"), Component.literal(page.reason()));
                  return;
                }
                list.row(
                    Component.empty(),
                    Component.translatableWithFallback(
                        "reform.task.page_values",
                        "Values on this page: %s",
                        page.choices().size()));
                for (var choice : page.choices()) {
                  var action =
                      button(
                          Component.literal(clip(choice.label(), 160)),
                          () -> inspect(choice.key()));
                  action.setTooltip(
                      net.minecraft.client.gui.components.Tooltip.create(
                          Component.literal(
                              clip(choice.label(), 512) + "\n" + clip(choice.key(), 512))));
                  list.row(
                      "primary:choice:" + choice.key(),
                      Component.empty(),
                      Component.empty(),
                      action);
                }
                if (page.exhausted())
                  list.row(text("end", "End of this search"), Component.empty());
                else
                  list.actions(
                      "primary:more", button(text("next", "Next page"), () -> search(page.next())));
              });
    list.endGroup();
    detail.beginGroup("detail:inspected", text("inspected", "Inspected value"));
    if (browser != null)
      browser
          .resolution()
          .ifPresent(
              resolution -> {
                var title =
                    browser.page().stream()
                        .flatMap(page -> page.choices().stream())
                        .filter(
                            choice ->
                                browser.selectedKey().filter(choice.key()::equals).isPresent())
                        .map(ReferencePage.Choice::label)
                        .findFirst();
                detail.row(
                    title
                        .<Component>map(Component::literal)
                        .orElseGet(() -> text("inspection", "Inspection")),
                    resolution.status() == ReferenceResolution.Status.RESOLVED
                        ? ReferencePreview.format(resolution.preview())
                        : Component.literal(resolution.reason()));
              });
    detail.field(
        "detail:manual",
        text("id", "Reference ID"),
        text("manual_help", "Inspect an ID without changing a setting."),
        manual,
        button(text("inspect", "Inspect"), () -> inspect(manual.getValue())));
    var picks = new ArrayList<Button>();
    for (var selection : declaration.selections()) {
      var value =
          editor
              .view()
              .snapshot()
              .filter(snapshot -> snapshot.draft().contains(selection.field()))
              .map(snapshot -> snapshot.draft().at(selection.field()))
              .filter(DraftNode.Scalar.class::isInstance)
              .map(DraftNode.Scalar.class::cast)
              .map(DraftNode.Scalar::raw)
              .orElse("—");
      var requestedId = value;
      var title =
          observedTitles == null
              ? Component.literal(value)
              : observedTitles.title(value).orElseGet(() -> Component.literal(requestedId));
      var token = generation;
      var information =
          new InformationButton(
              selection.title(),
              () -> {
                if (!ended
                    && visible
                    && token == generation
                    && isCurrent()
                    && editor.isOpen()
                    && editor.actions().mayInspect())
                  editor.presentation().information(selection.field(), selection.title());
              });
      detail.field(
          "detail:requested:" + selection.field(),
          selection.title(),
          text("draft_value", "Draft value").copy().append(": ").append(title),
          information);
      if (details)
        detail.row(
            "detail:requested-id:" + selection.field(),
            text("requested_id", "Requested ID").copy().append(": ").append(selection.title()),
            Component.literal(requestedId));
      var pick = button(selection.label(), () -> choose(selection));
      pick.active = canChoose(selection.field());
      picks.add(pick);
      detail.actions("detail:choose:" + selection.field(), pick);
    }
    choosing = List.copyOf(picks);
    detail.actions(
        "detail:technical",
        button(
            details
                ? text("hide_details", "Hide source details")
                : text("source_details", "Source details"),
            () -> {
              details = !details;
              rebuildWidgets();
            }));
    if (details && browser != null) {
      var stamp = browser.stamp();
      detail.row(text("source", "Source"), Component.literal(stamp.source().toString()));
      detail.row(text("revision", "Revision"), Component.literal(stamp.revision()));
      detail.row(text("snapshot", "Snapshot"), Component.literal(stamp.snapshot().toString()));
      detail.row(text("context", "Context"), Component.literal(stamp.context().toString()));
      browser
          .page()
          .ifPresent(
              page ->
                  detail.row(
                      text("search", "Search"),
                      Component.translatableWithFallback(
                          "reform.reference.examined",
                          "%s shown; %s source entries examined on this page",
                          page.choices().size(),
                          page.examined())));
    }
    if (!notice.getString().isEmpty()) detail.row("detail:notice", Component.empty(), notice);
    detail.endGroup();
    if (preview != null) preview.append(detail);
    else if (graphicFailure != null)
      detail.row(text("preview_unavailable", "Preview unavailable"), reason(graphicFailure));
    if (detail != list) addRenderableWidget(detail);
    observedOwner = editor.view();
    observedGraphic = preview == null ? null : preview.state();
  }

  void finish() {
    panels.finish().ifPresent(this::setFocused);
  }

  private Button button(Component label, Runnable action) {
    var token = generation;
    return ClientControls.action(
            font,
            label,
            () -> {
              if (!ended && visible && token == generation && isCurrent()) action.run();
            },
            theme())
        .build();
  }

  private boolean canChoose(FieldPath field) {
    return browser != null
        && browser.current()
        && browser.selectedKey().isPresent()
        && browser
            .resolution()
            .filter(value -> value.status() == ReferenceResolution.Status.RESOLVED)
            .isPresent()
        && (edit == null || edit.snapshot().terminal())
        && editor.canStage(field);
  }

  private void choose(ClientReferenceTask.Selection selection) {
    try {
      if (!canChoose(selection.field()))
        throw new PolicyRefusal(
            "reference.input_unaccepted",
            "Finish or repair pending input before choosing this value");
      edit =
          ReferenceSelection.chooseFromBrowser(
              EntryPoint.GUI, editor.actions(), selection.field(), browser);
      var issued = edit;
      issued
          .completion()
          .whenComplete(
              (result, failure) ->
                  minecraft.execute(
                      () -> {
                        if (ended || edit != issued) return;
                        notice =
                            failure != null
                                ? reason(failure)
                                : result.report().valid()
                                    ? text(
                                        "staged",
                                        "The draft changed. Nothing was saved or applied.")
                                    : ClientStatusScreen.reason(
                                        result.report(),
                                        text("not_staged", "The value was not staged."));
                        if (visible && isCurrent()) rebuildWidgets();
                      }));
    } catch (RuntimeException refused) {
      notice = reason(refused);
    }
    rebuildWidgets();
  }

  private void refresh() {
    try {
      openSource();
    } catch (RuntimeException failed) {
      sourceFailure = failed;
    }
    if (browser != null) search(Optional.empty());
    rebuildWidgets();
  }

  private void search(Optional<ReferenceQuery.Cursor> cursor) {
    try {
      if (browser == null)
        throw new PolicyRefusal("reference.unavailable", "Refresh this source before searching");
      var controller = browser;
      var work =
          controller.search(
              EntryPoint.GUI,
              new ReferenceQuery(
                  filter.getValue(), Math.min(8, declaration.limits().maximumPageSize()), cursor));
      search = work;
      work.operation()
          .completion()
          .whenComplete(
              (result, failure) ->
                  minecraft.execute(
                      () -> {
                        if (ended || browser != controller || search != work) return;
                        try {
                          if (failure != null) notice = reason(failure);
                          else if (!controller.accept(work))
                            notice =
                                ClientStatusScreen.reason(
                                    result.report(),
                                    text("search_stale", "Search is no longer current."));
                        } catch (RuntimeException refused) {
                          notice = reason(refused);
                        }
                        if (visible && isCurrent()) rebuildWidgets();
                      }));
    } catch (RuntimeException refused) {
      notice = reason(refused);
    }
    rebuildWidgets();
  }

  private void inspect(String raw) {
    try {
      if (browser == null)
        throw new PolicyRefusal("reference.unavailable", "Refresh this source before inspecting");
      manual.setMaxLength(Math.max(32768, raw.length()));
      manual.setValue(raw);
      var controller = browser;
      var work = controller.resolve(EntryPoint.GUI, raw);
      lookup = work;
      work.operation()
          .completion()
          .whenComplete(
              (result, failure) ->
                  minecraft.execute(
                      () -> {
                        if (ended || browser != controller || lookup != work) return;
                        try {
                          if (failure != null) notice = reason(failure);
                          else if (!controller.accept(work))
                            notice =
                                ClientStatusScreen.reason(
                                    result.report(),
                                    text("lookup_stale", "Inspection is no longer current."));
                        } catch (RuntimeException refused) {
                          notice = reason(refused);
                        }
                        if (visible && isCurrent()) rebuildWidgets();
                      }));
    } catch (RuntimeException refused) {
      notice = reason(refused);
    }
    rebuildWidgets();
  }

  @Override
  boolean ownsFocus(GuiEventListener focused) {
    return panels != null && (focused == panels.primary() || focused == panels.detail());
  }

  @Override
  public void tick() {
    if (ended || !visible || !isCurrent()) return;
    if (!started) {
      started = true;
      openSource();
      declaration.graphic().ifPresent(this::openGraphic);
      if (browser != null) search(Optional.empty());
    }
    boolean current = browser != null && browser.current();
    if (sourceWasCurrent && !current)
      notice = text("stale", "This source changed. Refresh before choosing a value.");
    if (sourceWasCurrent != current
        || observedTitles != editor.presentation().referenceTitleSnapshot(declaration.source())
        || observedOwner != editor.view()
        || !Objects.equals(observedGraphic, preview == null ? null : preview.state())) {
      sourceWasCurrent = current;
      rebuildWidgets();
    }
    // Native pointer dispatch focuses the pressed button after its callback returns.
    // Resolve the current panel after that dispatch and any intervening reflow.
    if (showPreviewPending) {
      showPreviewPending = false;
      var target = panels.detail();
      setFocused(target);
      target.focusRow("detail:graphic");
    }
    for (int i = 0; i < choosing.size(); i++)
      choosing.get(i).active = canChoose(declaration.selections().get(i).field());
  }

  private <S> void openGraphic(ClientTaskGraphic<T, S> spec) {
    graphicLifetime = lifetime();
    // Retire the returned view before hooks registered by the provider factory.
    graphicLifetime.onClose(
        () -> {
          if (graphicView != null) graphicView.close();
        });
    try {
      var provider =
          Objects.requireNonNull(
              spec.factory().open(new ClientViewContext(graphicLifetime), editor));
      graphicLifetime.requireOwner();
      var view =
          workflow.graphics(spec.aggregateLimits(), spec.maximumViews()).open(editor, provider);
      graphicView = view;
      preview =
          new Preview() {
            final List<ClientTaskGraphic.Choice<S>> choices = spec.choices();
            final AbstractWidget widget = view.widget(spec.title());
            String chosen = choices.getFirst().id();
            boolean facts;

            public Object state() {
              return view.snapshot();
            }

            public void visible(boolean value) {
              view.visible(value);
            }

            public void close() {
              view.close();
            }

            public void append(FormPanel target) {
              target.beginGroup("detail:preview", spec.title());
              var token = generation;
              var choice =
                  NativeCycles.<String>builder(
                          id ->
                              choices.stream()
                                  .filter(value -> value.id().equals(id))
                                  .findFirst()
                                  .orElseThrow()
                                  .label(),
                          chosen,
                          choices.stream().map(ClientTaskGraphic.Choice::id).toArray(String[]::new))
                      .create(
                          0,
                          0,
                          subjectWidth(spec),
                          theme().values().value(ThemeValues.Role.CONTROL_HEIGHT),
                          text("subject", "Preview subject"),
                          (button, value) -> {
                            if (ended
                                || !visible
                                || token != generation
                                || !isCurrent()
                                || chosen.equals(value)) return;
                            chosen = value;
                            view.invalidate();
                            rebuildWidgets();
                          });
              target.row(
                  "detail:subject",
                  Component.empty(),
                  text(
                      "preview_draft",
                      "Preview uses the complete draft. An inspected choice is not applied automatically."),
                  choice);
              target.actions(
                  "detail:prepare",
                  button(
                      text("prepare", "Prepare draft preview"),
                      () -> {
                        try {
                          view.request(
                              choices.stream()
                                  .filter(value -> value.id().equals(chosen))
                                  .findFirst()
                                  .orElseThrow()
                                  .subject());
                        } catch (RuntimeException refused) {
                          notice = reason(refused);
                        }
                        rebuildWidgets();
                      }),
                  button(
                      text("cancel_preview", "Cancel preparation"),
                      () -> {
                        view.cancel();
                        rebuildWidgets();
                      }));
              int size = theme().values().value(ThemeValues.Role.MEASURE_GRAPHIC_PREFERRED);
              ControlSizing.offer(widget, size, Math.min(size, target.visibleRowHeight()));
              target.anchorRow("detail:graphic", Component.empty(), Component.empty(), widget);
              target.actions(
                  "detail:camera",
                  button(text("rotate_left", "Rotate left"), () -> view.orbit(-15, 0)),
                  button(text("rotate_right", "Rotate right"), () -> view.orbit(15, 0)),
                  button(text("zoom_in", "Zoom in"), () -> view.zoom(1.2)),
                  button(text("zoom_out", "Zoom out"), () -> view.zoom(1 / 1.2)),
                  button(text("reset_camera", "Reset view"), view::resetCamera),
                  button(text("show_preview", "Show preview"), () -> showPreviewPending = true));
              target.actions(
                  "detail:preview_details",
                  button(
                      facts
                          ? text("hide_preview_details", "Hide preview details")
                          : text("preview_details", "Preview details"),
                      () -> {
                        facts = !facts;
                        rebuildWidgets();
                      }));
              if (facts)
                for (var fact : view.facts())
                  target.row(Component.literal(fact.title()), Component.literal(fact.value()));
              if (!view.snapshot().report().issues().isEmpty())
                target.row(
                    Component.empty(),
                    ClientStatusScreen.reason(
                        view.snapshot().report(), text("preview_failed", "Preview unavailable")));
              target.endGroup();
            }
          };
    } catch (RuntimeException | Error failed) {
      graphicFailure = failed;
      var previous = graphicLifetime;
      graphicLifetime = null;
      if (previous != null)
        try {
          previous.close();
        } catch (RuntimeException | Error cleanup) {
          failed.addSuppressed(cleanup);
        }
    }
  }

  private int subjectWidth(ClientTaskGraphic<?, ?> spec) {
    int widest =
        spec.labels().stream()
            .map(
                label ->
                    CommonComponents.optionNameValue(text("subject", "Preview subject"), label))
            .map(caption -> theme().text(ClientTheme.TextRole.VALUE, caption))
            .mapToInt(font::width)
            .max()
            .orElseThrow();
    return Math.max(
        theme().values().value(ThemeValues.Role.CONTROL_MINIMUM_WIDTH),
        Math.addExact(widest, theme().values().value(ThemeValues.Role.CONTROL_CAPTION_PADDING)));
  }

  @Override
  public Component getNarrationMessage() {
    var message = text("catalogue", "Catalogue").copy();
    if (!notice.getString().isEmpty()) message.append(". ").append(notice);
    if (graphicFailure != null) message.append(". ").append(reason(graphicFailure));
    if (graphicView != null) message.append(". ").append(graphicView.statusNarration());
    return message;
  }

  @Override
  public void close() {
    if (ended) return;
    ended = true;
    showPreviewPending = false;
    Throwable failure = null;
    try {
      closeSource();
    } catch (RuntimeException | Error failed) {
      failure = failed;
    }
    try {
      if (graphicLifetime != null) graphicLifetime.close();
    } catch (RuntimeException | Error failed) {
      if (failure == null) failure = failed;
      else failure.addSuppressed(failed);
    }
    graphicLifetime = null;
    preview = null;
    if (failure instanceof RuntimeException failed) throw failed;
    if (failure instanceof Error failed) throw failed;
  }

  private static String clip(String value, int limit) {
    return value.codePointCount(0, value.length()) <= limit
        ? value
        : value.substring(0, value.offsetByCodePoints(0, limit)) + "…";
  }

  private static Component reason(Throwable failure) {
    while (failure instanceof CompletionException && failure.getCause() != null)
      failure = failure.getCause();
    if (failure instanceof PolicyRefusal refused)
      return ClientStatusScreen.reason(
          refused.report(), text("unavailable", "This task is unavailable."));
    return text("failed", "This task could not finish. Your draft is retained.");
  }
}
