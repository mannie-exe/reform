package design.inherent.reform.neoforge.client;

import design.inherent.reform.definition.DocumentId;
import design.inherent.reform.definition.FieldPath;
import design.inherent.reform.operation.DocumentBatch;
import design.inherent.reform.operation.OperationHandle;
import design.inherent.reform.owner.EditorActor;
import design.inherent.reform.owner.OwnerContext;
import design.inherent.reform.owner.PolicyRefusal;
import design.inherent.reform.presentation.FormGeometry;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Predicate;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.locale.Language;
import net.minecraft.network.chat.Component;

/**
 * One explicitly opened client workflow across separately owned documents. Views retain the same
 * actual sessions; this controller neither replaces backends nor promises a cross-file transaction.
 */
public final class ClientEditingWorkflow implements AutoCloseable {
  public static final int MAXIMUM_DOCUMENTS = 32;

  /** Cached presentation only, never source readiness, authorization or a submission receipt. */
  public record DocumentState(
      DocumentId document,
      Component title,
      boolean opened,
      boolean available,
      boolean dirty,
      boolean busy,
      Component summary,
      DocumentId ownerDocument) {
    public DocumentState(
        DocumentId document,
        Component title,
        boolean opened,
        boolean available,
        boolean dirty,
        boolean busy,
        Component summary) {
      this(document, title, opened, available, dirty, busy, summary, document);
    }

    public DocumentState {
      Objects.requireNonNull(document);
      Objects.requireNonNull(ownerDocument);
      title = Objects.requireNonNull(title).copy();
      summary = Objects.requireNonNull(summary).copy();
    }

    @Override
    public Component title() {
      return title.copy();
    }

    @Override
    public Component summary() {
      return summary.copy();
    }
  }

  private static final class Contributed<O> {
    final ClientWorkflowLayout.Action<O> action;
    OperationHandle<O> latest;

    Contributed(ClientWorkflowLayout.Action<O> action) {
      this.action = action;
    }

    boolean busy() {
      return latest != null && !latest.snapshot().terminal();
    }
  }

  private static final class Entry {
    final ClientForm form;
    ClientEditor<?> editor;

    Entry(ClientForm form) {
      this.form = form;
    }
  }

  private final LinkedHashMap<DocumentId, Entry> entries = new LinkedHashMap<>();
  private final Map<DocumentId, Entry> ownerEntries = new LinkedHashMap<>();
  private final Map<DocumentId, ClientWorkflowLayout.Placement> placements = new LinkedHashMap<>();
  private final Map<DocumentId, ClientWorkflowLayout.ReviewScope> reviews = new LinkedHashMap<>();
  private final Map<DocumentId, ClientWorkflowLayout.ReviewScope> guarded = new LinkedHashMap<>();
  private final Map<DocumentId, Contributed<?>> contributions = new LinkedHashMap<>();
  private final ClientWorkflowLayout layout;
  private final OwnerContext lifetime;
  private final OwnerContext.Access batchAccess;
  private final Overview overview;
  private final DocumentBatch.Validator validator;
  private DocumentBatch batch;
  private BatchReviewScreen batchView;
  private boolean closed;

  private ClientEditingWorkflow(
      Screen parent,
      Component title,
      List<ClientForm> forms,
      DocumentBatch.Validator validator,
      ClientWorkflowLayout layout) {
    ClientSettingsRuntime.requireClientThread();
    Objects.requireNonNull(title);
    this.validator = Objects.requireNonNull(validator);
    this.layout = layout;
    var declared = List.copyOf(forms);
    if (declared.isEmpty() || declared.size() > MAXIMUM_DOCUMENTS)
      throw new IllegalArgumentException("A client workflow requires between 1 and 32 documents");
    for (var form : declared) {
      var entry = new Entry(form);
      if (entries.putIfAbsent(form.route(), entry) != null
          || ownerEntries.putIfAbsent(form.document(), entry) != null)
        throw new IllegalArgumentException(
            "Register each workflow route and owner once. Use placements for additional views.");
    }
    if (layout != null) admitLayout(layout);
    var client = Minecraft.getInstance();
    lifetime =
        new OwnerContext(
            client::execute,
            ClientSettingsRuntime.workers(),
            ClientSettingsRuntime::requireClientThread,
            OwnerContext.Limits.STANDARD);
    overview = new Overview(parent, title.copy());
    try {
      ClientSettingsRuntime.manageContext(lifetime);
      batchAccess = lifetime.access(() -> true, () -> true);
      lifetime.onClose(this::retire);
    } catch (RuntimeException | Error failed) {
      lifetime.close();
      throw failed;
    }
  }

  /**
   * Call on the native client thread when entering the UI, not during mod metadata registration.
   */
  public static ClientEditingWorkflow open(Screen parent, Component title, List<ClientForm> forms) {
    return open(parent, title, forms, ignored -> DocumentBatch.Check.VALID);
  }

  public static ClientEditingWorkflow open(
      Screen parent, Component title, List<ClientForm> forms, DocumentBatch.Validator validator) {
    return new ClientEditingWorkflow(parent, title, forms, validator, null);
  }

  public static ClientEditingWorkflow open(
      Screen parent, Component title, List<ClientForm> forms, ClientWorkflowLayout layout) {
    return new ClientEditingWorkflow(
        parent, title, forms, ignored -> DocumentBatch.Check.VALID, Objects.requireNonNull(layout));
  }

  private void admitLayout(ClientWorkflowLayout layout) {
    for (var group : layout.groups()) {
      for (var view : group.placements()) {
        entry(view.route());
        placements.put(view.id(), view);
      }
      for (var action : group.actions()) contributions.put(action.id(), new Contributed<>(action));
    }
    for (var scope : layout.reviews()) {
      reviews.put(scope.id(), scope);
      scope.checked().forEach(this::entry);
      for (var route : scope.writable()) {
        entry(route);
        if (scope.requiredOnSave() && guarded.putIfAbsent(route, scope) != null)
          throw new IllegalArgumentException("An owner can require only one save scope");
      }
    }
    for (var scope : reviews.values())
      for (var route : scope.writable())
        if (guarded.getOrDefault(route, scope) != scope)
          throw new IllegalArgumentException(
              "A review cannot bypass an owner's required save scope");
    if (!placements.values().stream()
        .map(ClientWorkflowLayout.Placement::route)
        .collect(java.util.stream.Collectors.toSet())
        .containsAll(entries.keySet()))
      throw new IllegalArgumentException("Every registered owner needs a placement");
  }

  public boolean isOpen() {
    return !closed && lifetime.isOpen();
  }

  public Screen screen() {
    requireOpen();
    return overview;
  }

  /** Navigate from this workflow's overview, keeping its actual owner/session lifetime. */
  public void show(DocumentId document) {
    requireOpen();
    if (Minecraft.getInstance().screen != overview)
      throw new PolicyRefusal("client.workflow_view", "Return to this workflow's overview first");
    overview.openDocument(document, Optional.empty());
  }

  public void showPlacement(DocumentId id) {
    requireOverview();
    var placement = placements.get(Objects.requireNonNull(id));
    if (placement == null)
      throw new IllegalArgumentException("This placement is not in this workflow");
    overview.openDocument(placement.route(), placement.field());
  }

  /** Open at most one real editor per document. Independent workflows keep independent actors. */
  public ClientEditor<?> editor(DocumentId document) {
    requireOpen();
    var entry = entry(document);
    if (entry.editor != null) {
      if (!entry.editor.isOpen())
        throw new PolicyRefusal(
            "client.editor_unavailable",
            "This editing context ended; explicitly discard it before opening another");
      return entry.editor;
    }
    var opened = entry.form.open(overview);
    try {
      requireOpen();
      opened.onAbandoned(this::close);
      var required = guarded.get(document);
      if (required != null)
        opened
            .screen()
            .withScopedSave(required.title(), () -> beginReview(required, opened.screen()));
      entry.editor = opened;
      return opened;
    } catch (RuntimeException | Error failed) {
      opened.close();
      throw failed;
    }
  }

  /**
   * Explicitly retire one draft. This does not undo a completed write or cancel an entered effect;
   * ordinary navigation refuses retirement while a known operation is pending.
   */
  public void discard(DocumentId document) {
    requireOpen();
    if (batch != null && batch.hasActiveOperations())
      throw new PolicyRefusal("client.batch_busy", "Wait for the batch operation to settle");
    var entry = entry(document);
    if (entry.editor == null) return;
    if (entry.editor.isOpen() && entry.editor.busy())
      throw new PolicyRefusal("client.editor_busy", "Wait for this document's pending operation");
    entry.editor.close();
    entry.editor = null;
  }

  /** Local cached views only; no validation, source IO, source capture or permission lookup. */
  public List<DocumentState> documents() {
    requireOpen();
    return entries.values().stream().map(this::state).toList();
  }

  private DocumentState state(Entry entry) {
    var editor = entry.editor;
    if (editor == null) {
      var available = entry.form.availability();
      return new DocumentState(
          entry.form.route(),
          entry.form.title(),
          false,
          available.ready(),
          false,
          false,
          available.ready()
              ? text("unopened", "Not opened; saved and effective values have not been inspected.")
              : available.message(),
          entry.form.document());
    }
    var view = editor.view();
    Component summary;
    if (!editor.isOpen())
      summary =
          text(
              "context_ended",
              "Editing context ended; opening a new one requires discarding this draft.");
    else if (editor.busy())
      summary = text("pending", "Operation pending; wait for its actual result.");
    else if (view.availability() != EditorActor.Availability.READY)
      summary =
          ClientStatusScreen.reason(
              view.observationReport(), text("unavailable", "Document unavailable"));
    else if (view.review().isPresent())
      summary = text("conflict", "Source changed; review this document's conflict.");
    else if (!view.observationReport().valid())
      summary =
          ClientStatusScreen.reason(
              view.observationReport(), text("unavailable", "Document unavailable"));
    else if (!view.operationReport().valid())
      summary =
          ClientStatusScreen.reason(
              view.operationReport(), text("refused", "The last operation was refused"));
    else if (!view.validation().valid())
      summary =
          ClientStatusScreen.reason(view.validation(), text("invalid", "Invalid draft retained"));
    else
      summary =
          editor.dirty()
              ? text("dirty", "Unsaved draft retained")
              : text("clean", "No unsaved edits");
    if (view.lastReceipt().isPresent())
      summary =
          summary
              .copy()
              .append(". ")
              .append(
                  Component.translatableWithFallback(
                      "reform.workflow.receipt",
                      "Recorded receipt: %s",
                      CommitFeedback.summary(view.lastReceipt().orElseThrow())));
    return new DocumentState(
        entry.form.route(),
        entry.form.title(),
        true,
        editor.isOpen(),
        editor.dirty(),
        editor.isOpen() && editor.busy(),
        summary,
        entry.form.document());
  }

  private Entry entry(DocumentId id) {
    var entry = entries.get(Objects.requireNonNull(id));
    if (entry == null) throw new IllegalArgumentException("This document is not in this workflow");
    return entry;
  }

  private void requireOpen() {
    ClientSettingsRuntime.requireClientThread();
    if (!isOpen())
      throw new PolicyRefusal("client.workflow_closed", "This editing workflow is closed");
  }

  private void requireOverview() {
    requireOpen();
    if (Minecraft.getInstance().screen != overview)
      throw new PolicyRefusal("client.workflow_view", "Return to this workflow's overview first");
  }

  /** Forced lifetime shutdown. An effect already entered may still finish; this is no rollback. */
  @Override
  public void close() {
    ClientSettingsRuntime.requireClientThread();
    if (!closed) lifetime.close();
  }

  private void retire() {
    if (closed) return;
    closed = true;
    Throwable failure = null;
    try {
      if (batch != null) batch.close();
    } catch (RuntimeException | Error failed) {
      failure = failed;
    }
    for (var entry : entries.values()) {
      try {
        if (entry.editor != null) entry.editor.close();
      } catch (RuntimeException | Error failed) {
        if (failure == null) failure = failed;
        else if (failure != failed) failure.addSuppressed(failed);
      }
      entry.editor = null;
    }
    entries.clear();
    ownerEntries.clear();
    placements.clear();
    reviews.clear();
    guarded.clear();
    contributions.clear();
    batch = null;
    batchView = null;
    if (failure instanceof RuntimeException failed) throw failed;
    if (failure instanceof Error failed) throw failed;
  }

  private static Component text(String key, String fallback) {
    return Component.translatableWithFallback("reform.workflow." + key, fallback);
  }

  /**
   * Explicitly enter every declared owner and review the complete vector, not a filtered subset.
   */
  public void reviewAll() {
    requireOverview();
    if (layout != null)
      throw new PolicyRefusal("client.review_scope", "Choose an explicit review scope");
    beginReview(
        text("review_all", "Review all documents"),
        DocumentId.parse("reform:client_workflow"),
        route -> true,
        route -> true,
        validator,
        overview);
  }

  public void review(DocumentId id) {
    requireOverview();
    var scope = reviews.get(Objects.requireNonNull(id));
    if (scope == null)
      throw new IllegalArgumentException("This review scope is not in this workflow");
    beginReview(scope, overview);
  }

  private void beginReview(ClientWorkflowLayout.ReviewScope scope, Screen from) {
    beginReview(
        scope.title(),
        scope.id(),
        route -> scope.writable().contains(route) || scope.checked().contains(route),
        scope.writable()::contains,
        scope.validate(),
        from);
  }

  private void beginReview(
      Component title,
      DocumentId id,
      Predicate<DocumentId> included,
      Predicate<DocumentId> mayWrite,
      DocumentBatch.Validator validate,
      Screen from) {
    requireOpen();
    if (Minecraft.getInstance().screen != from)
      throw new PolicyRefusal("client.workflow_view", "This workflow view is no longer current");
    if (batch != null && batch.hasActiveOperations())
      throw new PolicyRefusal("client.batch_busy", "Wait for the batch operation to settle");
    var members = new ArrayList<DocumentBatch.Member<?>>();
    for (var route : entries.keySet()) {
      if (!included.test(route)) continue;
      var selected = editor(route);
      if (selected.busy())
        throw new PolicyRefusal(
            "client.editor_busy", "Wait for pending document work before review");
      if (selected.screen().retainedInput())
        throw new PolicyRefusal(
            "client.unaccepted_input",
            "Return to the document and retry or explicitly revert unaccepted input before batch review");
      members.add(
          DocumentBatch.member(
              selected.actions(),
              mayWrite.test(route) && selected.dirty()
                  ? DocumentBatch.Intent.COMMIT
                  : DocumentBatch.Intent.CHECK));
    }
    if (batch != null) batch.close();
    batch =
        new DocumentBatch(
            lifetime, batchAccess, id, members, validate, DocumentBatch.Limits.STANDARD);
    batchView =
        new BatchReviewScreen(
            title,
            batch,
            this::isOpen,
            owner -> ownerEntries.get(owner).form.title(),
            this::inspectBatch,
            () -> Minecraft.getInstance().setScreen(overview),
            this::close);
    if (from == overview) overview.navigate(batchView);
    else Minecraft.getInstance().setScreen(batchView);
  }

  private void inspectBatch(DocumentBatch.Entry captured, Screen parent) {
    var bound = ownerEntries.get(captured.document());
    var selected = editor(bound.form.route());
    var report = captured.preflight();
    var cross =
        batchView.reviewedInput().orElseThrow().check().documents().get(captured.document());
    if (cross != null) report = report.and(cross);
    var screen =
        new ReviewScreen<>(
            text("review_fields", "Reviewed source and fields"),
            bound.form.title(),
            selected.actions(),
            () -> isOpen() && selected.isOpen(),
            path -> {
              if (path.isPresent()) {
                Minecraft.getInstance().setScreen(selected.screen());
                selected.screen().focusField(path.orElseThrow());
              } else Minecraft.getInstance().setScreen(parent);
            },
            this::close,
            captured.review(),
            report);
    Minecraft.getInstance().setScreen(screen);
  }

  private <O> void openOperation(Contributed<O> entry, boolean restart) {
    requireOverview();
    var action = entry.action;
    if (entry.latest == null || restart) {
      if (entry.busy())
        throw new PolicyRefusal("client.operation_busy", "This operation is already running");
      var blocked = action.blocked().get();
      if (blocked.isPresent())
        throw new PolicyRefusal("client.operation_unavailable", blocked.orElseThrow().getString());
      entry.latest = Objects.requireNonNull(action.start().get());
    }
    overview.navigate(
        new OperationScreen<>(
            action.title(),
            action.context(),
            entry.latest,
            () -> isOpen() && action.mayObserve().getAsBoolean(),
            action.describe(),
            List.of(),
            () -> Minecraft.getInstance().setScreen(overview),
            this::close));
  }

  private final class Overview extends FormScreen {
    private final Screen parent;
    private FormEditBox filter;
    private FormPanel panel;
    private List<DocumentState> displayed = List.of();
    private List<Boolean> displayedOperations = List.of();
    private Language language;
    private Component notice;
    private Object generation;
    private boolean handoff;

    Overview(Screen parent, Component title) {
      super(title);
      this.parent = parent;
    }

    @Override
    protected void init() {
      generation = new Object();
      language = Language.getInstance();
      var previous = filter;
      filter =
          new FormEditBox(
              font, 12, 34, Math.max(1, width - 24), 20, text("search", "Search documents"));
      filter.setMaxLength(256);
      filter.setHint(text("search", "Search documents"));
      if (previous != null) {
        filter.setValue(previous.getValue());
        filter.setCursorPosition(previous.getCursorPosition());
        filter.setHighlightPos(previous.selection());
      }
      filter.setResponder(ignored -> rebuildWidgets());
      addRenderableWidget(filter);
      var token = generation;
      var footer = new ArrayList<Button>();
      var done =
          Button.builder(
                  text("done", "Done"),
                  ignored -> {
                    if (current(token)) onClose();
                  })
              .bounds(0, 0, Math.max(60, font.width(text("done", "Done")) + 20), 20)
              .build();
      footer.add(done);
      var review =
          Button.builder(
                  text("review_all", "Review all documents"),
                  ignored -> {
                    if (!current(token)) return;
                    try {
                      reviewAll();
                    } catch (PolicyRefusal refused) {
                      notice =
                          ClientStatusScreen.reason(
                              refused.report(), text("unavailable", "Review unavailable"));
                      rebuildWidgets();
                    }
                  })
              .bounds(
                  0,
                  0,
                  Math.max(60, font.width(text("review_all", "Review all documents")) + 20),
                  20)
              .build();
      review.active =
          documents().stream().noneMatch(DocumentState::busy)
              && (batch == null || !batch.hasActiveOperations());
      if (layout == null) footer.add(review);
      if (batchView != null) {
        var result =
            Button.builder(
                    text("batch_result", "Last batch review/result"),
                    ignored -> {
                      if (current(token)) navigate(batchView);
                    })
                .bounds(
                    0,
                    0,
                    Math.max(60, font.width(text("batch_result", "Last batch review/result")) + 20),
                    20)
                .build();
        footer.add(result);
      }
      var geometry =
          FormGeometry.controls(
              footer.stream().map(value -> new FormGeometry.Size(value.getWidth(), 20)).toList(),
              Math.max(1, width - 24),
              4);
      int footerTop = height - 12 - geometry.height();
      for (int i = 0; i < footer.size(); i++) {
        var at = geometry.placements().get(i);
        var control = footer.get(i);
        control.setPosition(12 + at.x(), footerTop + at.y());
        control.setWidth(at.width());
        addRenderableWidget(control);
      }
      float scroll = panel == null ? 0 : panel.scroll();
      String focus = panel == null ? null : panel.focusKey();
      panel =
          new FormPanel(
              minecraft, 12, 62, Math.max(1, width - 24), Math.max(1, footerTop - 70), false);
      displayed = documents();
      displayedOperations = contributionStates();
      var query = filter.getValue().strip().toLowerCase(Locale.ROOT);
      if (notice != null) panel.row(Component.empty(), notice);
      if (layout == null) {
        panel.row(
            Component.empty(),
            text(
                "independent",
                "Each document saves separately. Returning here retains its draft and undo history."));
        for (var state : displayed)
          if (matches(query, state.title(), state.document()))
            documentRow(token, state.document(), state.title(), state, Optional.empty());
      } else {
        var states = new LinkedHashMap<DocumentId, DocumentState>();
        for (var state : displayed) states.put(state.document(), state);
        for (var group : layout.groups()) {
          boolean entireGroup = group.title().getString().toLowerCase(Locale.ROOT).contains(query);
          var selected =
              group.placements().stream()
                  .filter(
                      item ->
                          entireGroup
                              || matches(query, item.title(), item.id())
                              || matches(query, states.get(item.route()).title(), item.route()))
                  .toList();
          var actions =
              group.actions().stream()
                  .filter(item -> entireGroup || matches(query, item.title(), item.id()))
                  .toList();
          if (selected.isEmpty() && actions.isEmpty()) continue;
          panel.heading(group.title());
          for (var item : selected)
            documentRow(token, item.id(), item.title(), states.get(item.route()), item.field());
          for (var action : actions) operationRow(token, contributions.get(action.id()));
        }
        var scopes =
            reviews.values().stream()
                .filter(scope -> matches(query, scope.title(), scope.id()))
                .toList();
        if (!scopes.isEmpty()) panel.heading(text("scoped_reviews", "Scoped reviews"));
        for (var scope : scopes) {
          var open =
              button(
                  token, text("review", "Review changes"), scope.title(), () -> review(scope.id()));
          open.active = batch == null || !batch.hasActiveOperations();
          panel.row(scope.id().toString(), scope.title(), Component.empty(), open);
        }
      }
      panel.finish(scroll);
      addRenderableWidget(panel);
      if (focus != null) {
        setFocused(panel);
        panel.restoreFocus(focus);
      }
    }

    private boolean matches(String filter, Component label, DocumentId id) {
      return (label.getString() + " " + id).toLowerCase(Locale.ROOT).contains(filter);
    }

    private void documentRow(
        Object token,
        DocumentId id,
        Component label,
        DocumentState state,
        Optional<FieldPath> field) {
      var entry = entry(state.document());
      var buttons = new ArrayList<Button>();
      var open =
          button(
              token,
              state.opened() ? text("resume", "Resume") : text("open", "Open"),
              label,
              () -> openDocument(state.document(), field));
      open.active = state.available();
      buttons.add(open);
      if (state.opened()) {
        var discard =
            button(
                token,
                text("discard", "Discard draft"),
                label,
                () -> confirmDiscard(state.document()));
        discard.active = !state.busy();
        buttons.add(discard);
      } else if (entry.form.canRetry()) {
        buttons.add(button(token, text("retry", "Retry binding"), label, entry.form::retry));
      }
      panel.row(id.toString(), label, state.summary(), buttons.toArray(Button[]::new));
    }

    private void operationRow(Object token, Contributed<?> entry) {
      var action = entry.action;
      var blocked = action.blocked().get();
      var open =
          button(
              token,
              entry.latest == null
                  ? text("start", "Start…")
                  : text("operation_result", "View operation"),
              action.title(),
              () -> openOperation(entry, false));
      open.active = entry.latest != null || blocked.isEmpty();
      if (entry.latest != null) {
        var retry =
            button(
                token,
                text("run_again", "Run again…"),
                action.title(),
                () -> openOperation(entry, true));
        retry.active = !entry.busy() && blocked.isEmpty();
        panel.row(
            action.id().toString(), action.title(), blocked.orElse(action.context()), open, retry);
      } else
        panel.row(action.id().toString(), action.title(), blocked.orElse(action.context()), open);
    }

    private Button button(Object token, Component label, Component document, Runnable action) {
      return Button.builder(
              label,
              ignored -> {
                if (!current(token)) return;
                try {
                  action.run();
                } catch (PolicyRefusal refused) {
                  notice =
                      ClientStatusScreen.reason(
                          refused.report(), text("unavailable", "Operation unavailable"));
                  rebuildWidgets();
                }
              })
          .bounds(0, 0, Math.max(48, font.width(label) + 20), 20)
          .createNarration(original -> document.copy().append(": ").append(original.get()))
          .build();
    }

    @Override
    protected void rebuildWidgets() {
      boolean searchFocused = getFocused() == filter;
      super.rebuildWidgets();
      if (searchFocused) setFocused(filter);
    }

    private boolean current(Object token) {
      return isOpen() && generation == token && minecraft.screen == this;
    }

    private void navigate(Screen next) {
      handoff = true;
      try {
        minecraft.setScreen(next);
      } finally {
        handoff = false;
      }
    }

    private void openDocument(DocumentId id, Optional<FieldPath> field) {
      var selected = editor(id);
      notice = null;
      navigate(selected.screen());
      field.ifPresent(selected.screen()::focusAfterLoad);
    }

    private void confirmDiscard(DocumentId id) {
      navigate(
          new UnsavedChangesScreen(
              text("discard_title", "Discard this document's draft?"),
              entry(id).form.title(),
              text(
                  "discard_help",
                  "Completed saves are retained. Discarding releases this editor; a later Open creates a new session."),
              text("review", "Review changes"),
              () ->
                  entry(id).editor != null && entry(id).editor.isOpen()
                      ? Optional.empty()
                      : Optional.of(text("context_ended", "This editing context ended.")),
              choice -> {
                minecraft.setScreen(this);
                try {
                  if (choice == UnsavedChangesScreen.Choice.DISCARD) {
                    discard(id);
                    rebuildWidgets();
                  } else if (choice == UnsavedChangesScreen.Choice.SAVE)
                    openDocument(id, Optional.empty());
                } catch (PolicyRefusal refused) {
                  notice =
                      ClientStatusScreen.reason(
                          refused.report(), text("unavailable", "Operation unavailable"));
                  rebuildWidgets();
                }
              },
              ClientEditingWorkflow.this::close));
    }

    @Override
    public void onClose() {
      if (!isOpen() || minecraft.screen != this) return;
      var current = documents();
      if (current.stream().anyMatch(DocumentState::busy)
          || batch != null && batch.hasActiveOperations()
          || contributions.values().stream().anyMatch(Contributed::busy)) {
        notice =
            text(
                "wait",
                "Wait for pending operations and reconcile unknown results before closing.");
        rebuildWidgets();
        return;
      }
      if (current.stream()
          .anyMatch(state -> state.dirty() || state.opened() && !state.available())) {
        navigate(
            new UnsavedChangesScreen(
                text("unsaved", "Retained document drafts"),
                title,
                layout == null
                    ? text(
                        "exit_help",
                        "Review all documents before ordered saving, discard the remaining drafts, or keep editing. Previously completed saves are retained; documents do not form one atomic transaction.")
                    : text(
                        "exit_scoped_help",
                        "Return to the overview to review a scope or discard the remaining drafts."),
                text("review", "Review changes"),
                Optional::empty,
                choice -> {
                  if (choice == UnsavedChangesScreen.Choice.DISCARD) finish();
                  else {
                    minecraft.setScreen(this);
                    if (choice == UnsavedChangesScreen.Choice.SAVE && layout == null) {
                      try {
                        reviewAll();
                      } catch (PolicyRefusal refused) {
                        notice =
                            ClientStatusScreen.reason(
                                refused.report(), text("unavailable", "Review unavailable"));
                        rebuildWidgets();
                      }
                    }
                  }
                },
                ClientEditingWorkflow.this::close));
      } else finish();
    }

    private void finish() {
      // A domain/custom view may have started work after the decision was displayed.
      if (documents().stream().anyMatch(DocumentState::busy)
          || batch != null && batch.hasActiveOperations()
          || contributions.values().stream().anyMatch(Contributed::busy)) {
        if (minecraft.screen != this) minecraft.setScreen(this);
        notice =
            text(
                "wait",
                "Wait for pending operations and reconcile unknown results before closing.");
        rebuildWidgets();
        return;
      }
      close();
      minecraft.setScreen(parent);
    }

    @Override
    public void tick() {
      if (isOpen()
          && minecraft.screen == this
          && (language != Language.getInstance()
              || !displayed.equals(documents())
              || !displayedOperations.equals(contributionStates()))) rebuildWidgets();
    }

    private List<Boolean> contributionStates() {
      return contributions.values().stream().map(Contributed::busy).toList();
    }

    @Override
    public void removed() {
      if (!handoff) close();
    }

    @Override
    protected void setInitialFocus() {
      if (getFocused() == null) setFocused(filter);
    }

    @Override
    public Component getNarrationMessage() {
      return title
          .copy()
          .append(". ")
          .append(
              text(
                  "independent",
                  "Each document saves separately. Returning here retains its draft and undo history."));
    }

    @Override
    public void render(GuiGraphics graphics, int x, int y, float delta) {
      super.render(graphics, x, y, delta);
      graphics.drawCenteredString(font, title, width / 2, 12, FormPanel.TITLE_TEXT);
    }
  }
}
