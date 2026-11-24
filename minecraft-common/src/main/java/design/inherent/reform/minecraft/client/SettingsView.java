package design.inherent.reform.minecraft.client;

import static design.inherent.reform.minecraft.client.FieldLabels.readable;

import com.mojang.blaze3d.platform.InputConstants;
import design.inherent.reform.definition.DocumentDefinition;
import design.inherent.reform.definition.DocumentId;
import design.inherent.reform.definition.FieldPath;
import design.inherent.reform.edit.DraftEdit;
import design.inherent.reform.edit.DraftNode;
import design.inherent.reform.edit.DraftTarget;
import design.inherent.reform.edit.DraftTree;
import design.inherent.reform.importing.ImportCatalog;
import design.inherent.reform.importing.ImportFormat;
import design.inherent.reform.importing.JsonImportFormat;
import design.inherent.reform.minecraft.client.SettingsScreen.PreviewEntry;
import design.inherent.reform.minecraft.client.SettingsScreen.SubmissionLabels;
import design.inherent.reform.minecraft.internal.ClientSettingsRuntime;
import design.inherent.reform.operation.DocumentActions;
import design.inherent.reform.operation.OperationDescriptor.EntryPoint;
import design.inherent.reform.operation.OperationHandle;
import design.inherent.reform.owner.CommitReceipt;
import design.inherent.reform.owner.DocumentSession;
import design.inherent.reform.owner.EditorActor;
import design.inherent.reform.owner.PolicyRefusal;
import design.inherent.reform.presentation.FormCatalog;
import design.inherent.reform.presentation.FormDependencies;
import design.inherent.reform.presentation.FormGeometry;
import design.inherent.reform.presentation.FormModel;
import design.inherent.reform.presentation.FormPresentation;
import design.inherent.reform.presentation.FormTarget;
import design.inherent.reform.presentation.FormTokens;
import design.inherent.reform.presentation.NumericInput;
import design.inherent.reform.reference.CollectionSelection;
import design.inherent.reform.reference.ReferenceActions;
import design.inherent.reform.reference.ReferenceSelection;
import design.inherent.reform.validation.ValidationReport;
import design.inherent.reform.value.ValueShape;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.function.Consumer;
import java.util.function.DoubleConsumer;
import java.util.function.Supplier;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.locale.Language;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;

/** Generated recursive native form over the same admitted owner/operation services. */
final class SettingsView<T> extends FormView implements SettingsForm<T> {
  private record Control(
      AbstractWidget widget,
      Consumer<String> display,
      boolean editable,
      ValueShape shape,
      boolean cycle,
      DraftTarget target,
      Object token,
      Quantity quantity) {}

  private static final class Quantity {
    NumericInput input;
    CycleButton<String> units;
    Button less, more;
    QuantitySlider slider;
    boolean deferred;

    Quantity(NumericInput input) {
      this.input = input;
    }
  }

  private static final class QuantitySlider extends AbstractSliderButton {
    private final Component label;
    private final DoubleConsumer edits;

    QuantitySlider(Component label, DoubleConsumer edits) {
      super(0, 0, FormTokens.Control.SLIDER_PREFERRED, FormTokens.Control.HEIGHT, label, 0);
      this.label = label;
      this.edits = edits;
    }

    void display(NumericInput input, Component unit) {
      input.position().ifPresent(position -> value = position);
      setMessage(
          design.inherent.reform.minecraft.NumericComponents.value(
              input.caption(), Component.literal(input.text()).append(" ").append(unit)));
    }

    @Override
    protected void applyValue() {
      edits.accept(value);
    }

    @Override
    protected void updateMessage() {
      /* The mapped display supplies the selected value. */
    }

    @Override
    protected MutableComponent createNarrationMessage() {
      return Component.translatable(
          "gui.narrate.slider", label.copy().append(": ").append(getMessage()));
    }
  }

  private final Screen parent;
  private final Component contextLabel;
  private final DocumentDefinition<T> definition;
  private final DocumentActions<T> actions;
  private final Map<DocumentId, ReferenceActions<String, ?>> sources;
  private Map<DocumentId, ReferenceTitles> referenceTitles = Map.of();

  /** Borrow only cached owner-published titles; this performs no source lookup or admission. */
  ReferenceTitles.Snapshot referenceTitleSnapshot(DocumentId source) {
    var titles = referenceTitles.get(source);
    return titles == null ? null : titles.snapshot();
  }

  private final Map<FieldPath, TitledReference> titledReferences = new LinkedHashMap<>();

  private final class TitledReference {
    final FieldPath path;
    final DocumentId source;
    final Button button;
    final ReferenceTitles titles;
    final Component help;
    EditorActor.View<T> described;
    ReferenceTitles.Snapshot observed;
    String raw;

    TitledReference(
        FieldPath path, DocumentId source, Button button, ReferenceTitles titles, Component help) {
      this.path = path;
      this.source = source;
      this.button = button;
      this.titles = titles;
      this.help = help;
    }

    void refresh() {
      var view = actions.view();
      boolean changed = described != view;
      if (changed) {
        described = view;
        var issue =
            view.validation().issues().stream()
                .filter(value -> value.path().equals(path) || value.related().contains(path))
                .findFirst();
        var explanation = locallyAvailable(path) ? help : dependencyText(path);
        panel.summary(
            key(path) + ":editor",
            expandedEditors.contains(path)
                ? explanation
                : issue
                    .<Component>map(
                        value ->
                            text("invalid", "Invalid")
                                .copy()
                                .append(": ")
                                .append(value.fallback())
                                .append("\n")
                                .append(explanation))
                    .orElse(explanation));
      }
      if (view.snapshot().isEmpty() || !view.snapshot().orElseThrow().draft().contains(path))
        return;
      String nextRaw = raw(view.snapshot().orElseThrow().draft().at(path));
      var next = titles.snapshot();
      if (observed == next && Objects.equals(raw, nextRaw) && !changed) return;
      observed = next;
      raw = nextRaw;
      var title = next.title(raw).orElseGet(() -> Component.literal("ID: " + clip(raw, 160)));
      button.setMessage(title);
      var tooltip =
          title.copy().append("\n").append(clip(raw, 512)).append("\n").append(source.toString());
      if (!locallyAvailable(path)) tooltip.append("\n").append(dependencyText(path));
      button.setTooltip(Tooltip.create(tooltip));
    }
  }

  private final FormPresentation presentation;
  private ClientTheme documentTheme, fieldTheme;

  @Override
  protected ClientTheme theme() {
    return fieldTheme != null ? fieldTheme : documentTheme != null ? documentTheme : super.theme();
  }

  private final FieldEditors<T> fieldEditors;
  private final SubmissionLabels submissionLabels;
  private final FormNavigationIndex catalog;

  private record InputPosition(int cursor, int selection, Optional<String> unit) {}

  private final Map<FieldPath, InputPosition> inputPositions = new LinkedHashMap<>();
  private final java.util.Set<FieldPath> expandedEditors = new java.util.HashSet<>();
  private final Map<FieldPath, Button> editorButtons = new LinkedHashMap<>();
  private final Map<FieldPath, Button> disclosureButtons = new LinkedHashMap<>();
  private final FormDependencies.Bound dependencies;
  private final Map<FieldPath, Control> controls = new LinkedHashMap<>();
  private final Map<FieldPath, AbstractWidget> defaultControls = new LinkedHashMap<>();
  private final Map<FieldPath, String> local = new LinkedHashMap<>();
  private final Map<FieldPath, Long> versions = new LinkedHashMap<>();
  private final Map<FieldPath, Long> acceptedVersions = new LinkedHashMap<>();
  private final List<AbstractWidget> footer = new ArrayList<>();
  private final Map<Button, Runnable> footerCallbacks = new java.util.IdentityHashMap<>();
  private final List<FieldPath> visibleFields = new ArrayList<>();
  private java.util.Set<FieldPath> previousFields = java.util.Set.of();
  private final Map<AbstractWidget, Boolean> structureButtons = new LinkedHashMap<>();
  private final Map<AbstractWidget, FieldPath> structurePaths = new LinkedHashMap<>();
  private final Map<FieldPath, Button> availabilityButtons = new LinkedHashMap<>();
  private final Map<FieldPath, String> availabilityRows = new LinkedHashMap<>();
  private EditorActor.View<T> dependencyView;
  private long dependencyVersion = -1;
  private FormDependencies.Evaluation dependencyEvaluation;
  private List<FormModel.Layout> formLayout = List.of();
  private UUID formSession;
  private long formGeneration;
  private FormPanel panel;
  private FormChrome chrome;
  private EditorActor.View<T> displayed;
  private Button save,
      undo,
      redo,
      revert,
      defaults,
      reload,
      importData,
      conflict,
      done,
      inspection,
      reviewSubmission,
      outcomeButton,
      previewButton;
  private PreviewEntry preview;
  private List<ReviewScreen.Action> reviewActions = List.of();
  private boolean returnAfterApply;
  private Component scopedSaveLabel;
  private Runnable scopedSave;
  private FormTarget requestedFocus;
  private OperationHandle<EditorActor.Commit<T>> lastCommit;
  private Object widgetGeneration;
  private int pendingEdits;
  private long version;
  private long previewInputEpoch;
  private boolean activated, requested, updating, saving, loading, handoff, closed, formUnavailable;
  private Component notice;
  private DraftTree modelDraft;
  private FormModel cachedModel;
  private IllegalArgumentException modelFailure;
  private Language displayedLanguage;
  private boolean bodyVisible = true;
  private Runnable editorAbandoned;
  private ImportCatalog<T> declaredImports;
  private Runnable discardDocument;
  private FieldPath workspaceSelection = FieldPath.ROOT;
  private FormTarget.Section selectedBox;
  private design.inherent.reform.presentation.SectionNavigation.Bookmark retainedBodyScroll;
  private String retainedBodyFocus;
  private boolean retainedBodyFocused;

  void preserveStandalone() {
    if (composition != null || panel == null) return;
    retainedBodyScroll = panel.bookmark();
    retainedBodyFocus = panel.focusKey();
    retainedBodyFocused = getFocused() == panel;
  }

  WorkspaceNavigation.Position navigationPosition() {
    return panel == null || composition != null
        ? null
        : new WorkspaceNavigation.Position(
            panel.bookmark(), panel.rememberedFocusKey(), document());
  }

  void restorePosition(WorkspaceNavigation.Position position) {
    retainedBodyScroll = position.scroll();
    retainedBodyFocus = position.focus();
    retainedBodyFocused = position.focus() != null;
    requestedFocus = null;
  }

  void deferFocus(FieldPath path) {
    workspaceSelection = Objects.requireNonNull(path);
    selectedBox = null;
    requestedFocus = new FormTarget.Field(path);
  }

  void deferTarget(FormTarget target) {
    if (target instanceof FormTarget.Field field) {
      deferFocus(field.path());
      return;
    }
    var declared =
        catalog.targets().stream().filter(entry -> entry.id().equals(target)).findFirst();
    if (declared.isEmpty())
      throw new PolicyRefusal(
          "navigation.target_missing", "That section is absent from the current form");
    workspaceSelection = declared.orElseThrow().scope();
    selectedBox = (FormTarget.Section) target;
    requestedFocus = target;
  }

  private Composition composition;
  private FormInformation<T> information;
  private ClientFormOptions.DefaultsPresentation defaultsPresentation;
  private WorkspaceInformation inlineInformation;
  private final Map<FieldPath, InformationButton> informationButtons = new LinkedHashMap<>();

  void withInformation(FormInformation<T> information) {
    if (activated || requested || closed || this.information != null)
      throw new IllegalStateException("Information must be declared before activation");
    if (Objects.requireNonNull(information).definition() != definition)
      throw new IllegalArgumentException("Information requires this form's actual definition");
    this.information = information;
  }

  void withDefaults(ClientFormOptions.DefaultsPresentation presentation) {
    if (activated || requested || closed || defaultsPresentation != null)
      throw new IllegalStateException("Defaults presentation must be declared before activation");
    defaultsPresentation = Objects.requireNonNull(presentation);
  }

  void withReferenceTitles(Map<DocumentId, ReferenceTitles> titles) {
    if (activated || requested || closed)
      throw new IllegalStateException("Reference titles must be declared before activation");
    if (titles.size() > 128)
      throw new IllegalArgumentException("Too many titled reference sources");
    referenceTitles = Map.copyOf(titles);
  }

  private Component defaultsLabel() {
    return defaultsPresentation != null
        ? defaultsPresentation.label()
        : actions.loadsOwnerDefaults()
            ? text("load_defaults", "Load defaults")
            : text("defaults", "Defaults");
  }

  private Component defaultsHelp() {
    return defaultsPresentation == null ? Component.empty() : defaultsPresentation.help();
  }

  FormInformation.Subject describeInformation(
      design.inherent.reform.operation.FieldInformation captured) {
    return information == null
        ? FormInformation.Subject.EMPTY
        : information.capture(captured, actions.view());
  }

  Optional<Component> numericBounds(design.inherent.reform.operation.FieldInformation captured) {
    if (!mayInspect()) return Optional.empty();
    var snapshot = actions.view().snapshot().filter(value -> captured.target().matches(value));
    if (snapshot.isEmpty()) return Optional.empty();
    var current = snapshot.orElseThrow();
    return design.inherent.reform.minecraft.NumericComponents.bounds(
        design.inherent.reform.edit.DraftTypes.at(
                definition.type(), current.draft(), captured.path())
            .shape());
  }

  Optional<Component> supplementalHelp(design.inherent.reform.operation.FieldInformation captured) {
    if (!mayInspect()
        || actions.view().snapshot().filter(value -> captured.target().matches(value)).isEmpty())
      return Optional.empty();
    return presentation.catalog().sections().stream()
        .filter(
            section ->
                section.path().equals(captured.path()) && !section.supplementalHelp().isBlank())
        .findFirst()
        .map(section -> Component.literal(section.supplementalHelp()));
  }

  boolean mayInspect() {
    return !closed && actions.mayInspect();
  }

  void inlineInformation(WorkspaceInformation information) {
    inlineInformation = information;
  }

  record Selection(
      FieldPath path,
      Component title,
      Component help,
      Optional<ClientWorkflowLayout.Section> section) {
    Selection {
      Objects.requireNonNull(path);
      title = Objects.requireNonNull(title).copy();
      help = Objects.requireNonNull(help).copy();
      Objects.requireNonNull(section);
    }
  }

  private record Composition(FormPanel panel, List<Selection> selections) {}

  /** Compose rows from this actual owner; the host owns the shared scroll/focus surface. */
  void compose(FormPanel panel, List<Selection> selections) {
    composition = new Composition(Objects.requireNonNull(panel), List.copyOf(selections));
  }

  void standalone() {
    composition = null;
  }

  private String key(FieldPath path) {
    return scoped(FormModel.key(path));
  }

  private String scoped(String key) {
    return composition == null ? key : definition.id() + ":" + key;
  }

  @Override
  boolean ownsFocus(GuiEventListener focused) {
    if (footer.contains(focused) || focused == inspection) return true;
    if (focused != panel || panel == null) return false;
    var child = panel.getFocused();
    return controls.values().stream().anyMatch(control -> control.widget() == child)
        || editorButtons.containsValue(child)
        || disclosureButtons.containsValue(child)
        || structureButtons.containsKey(child)
        || informationButtons.containsValue(child)
        || availabilityButtons.containsValue(child);
  }

  List<AbstractWidget> ownerActions() {
    var result =
        new ArrayList<>(
            footer.stream().filter(widget -> widget != done && widget != save).toList());
    result.add(save);
    return List.copyOf(result);
  }

  List<FormGeometry.Size> actionSizes(net.minecraft.client.gui.Font measuredFont) {
    return actionSizes(measuredFont, super.theme());
  }

  List<FormGeometry.Size> actionSizes(
      net.minecraft.client.gui.Font measuredFont, ClientTheme parent) {
    var theme = parent.child(presentation.theme().root());
    var labels = new ArrayList<Component>();
    labels.add(text("document_actions", "Document actions…"));
    labels.add(text("revert", "Revert"));
    labels.add(text("review_changes", "Review changes…"));
    if (actions.view().review().isPresent()) labels.add(text("conflict", "Review conflict…"));
    labels.add(commitLabel());
    return labels.stream()
        .map(
            label ->
                new FormGeometry.Size(
                    Math.max(
                        theme
                            .values()
                            .value(
                                design.inherent.reform.presentation.ThemeValues.Role
                                    .CONTROL_MINIMUM_WIDTH),
                        Math.addExact(
                            measuredFont.width(theme.text(ClientTheme.TextRole.VALUE, label)),
                            theme
                                .values()
                                .value(
                                    design.inherent.reform.presentation.ThemeValues.Role
                                        .CONTROL_CAPTION_PADDING))),
                    theme
                        .values()
                        .value(
                            design.inherent.reform.presentation.ThemeValues.Role.CONTROL_HEIGHT)))
        .toList();
  }

  Component statusMessage() {
    return status();
  }

  Component contextMessage() {
    return contextLabel.copy();
  }

  FormCatalog.Limits navigationLimits() {
    var limits = presentation.catalog().limits();
    // Section and scalar-search projections each retain their own complete admission budget.
    return new FormCatalog.Limits(
        catalog.maximumTargets(),
        limits.maximumLabelCharacters(),
        limits.maximumTotalLabelCharacters(),
        limits.maximumQueryCharacters(),
        limits.maximumHistoryEntries());
  }

  List<FormNavigationIndex.Target> navigationTargets() {
    return catalog.targets();
  }

  /** Explicit navigation admission may project the current model; cached inspection never does. */
  boolean navigationTargetExists(FormTarget target) {
    var snapshot = actions.view().snapshot();
    if (closed || snapshot.isEmpty()) return false;
    var value = snapshot.orElseThrow();
    if (target instanceof FormTarget.Field field)
      return value.draft().contains(field.path()) && value.draft().activeAt(field.path());
    catalog.project(model(value.draft()), value.session(), value.generation());
    return catalog.targets().stream().anyMatch(entry -> entry.id().equals(target));
  }

  Optional<Component> searchProblem() {
    return catalog.searchProblem();
  }

  boolean hasNavigation() {
    return presentation.catalog().enabled();
  }

  /** Declare admitted source choices before activation; browsing them performs no source IO. */
  SettingsView<T> withImports(ImportCatalog<T> imports) {
    if (activated || requested || closed || declaredImports != null)
      throw new IllegalStateException(
          "Import choices must be declared once before form activation");
    if (Objects.requireNonNull(imports).definition() != definition)
      throw new IllegalArgumentException("Import choices require this form's actual definition");
    declaredImports = imports;
    return this;
  }

  void withPreview(PreviewEntry entry) {
    if (activated || requested || closed || preview != null)
      throw new IllegalStateException("Preview must be declared before form activation");
    preview = Objects.requireNonNull(entry);
  }

  void withReviewActions(List<ReviewScreen.Action> entries) {
    if (activated || requested || closed || !reviewActions.isEmpty())
      throw new IllegalStateException("Review actions must be declared before form activation");
    reviewActions = List.copyOf(entries);
  }

  void returnAfterApply() {
    if (!contextRecipe())
      throw new IllegalStateException("Automatic return requires a world recipe");
    returnAfterApply = true;
  }

  void withScopedSave(Component title, Runnable review) {
    if (activated || requested || closed || scopedSave != null)
      throw new IllegalStateException("Bind scoped saving before form activation");
    scopedSaveLabel =
        Component.translatableWithFallback(
            "reform.settings.review_scope", "Review %s…", title.copy());
    scopedSave = Objects.requireNonNull(review);
  }

  /** Borrow the actual session before activation; the caller closes it after the workflow ends. */
  void borrow(Runnable abandoned) {
    if (activated || requested || closed || editorAbandoned != null)
      throw new IllegalStateException("Only a new inactive form can transfer editor ownership");
    editorAbandoned = Objects.requireNonNull(abandoned);
  }

  public DocumentId document() {
    return definition.id();
  }

  /** The same typed operations used by this view; this accessor transfers no ownership. */
  public DocumentActions<T> editingActions() {
    ClientSettingsRuntime.requireClientThread();
    if (closed) throw new PolicyRefusal("editor.closed", "This form is closed");
    return actions;
  }

  boolean retainedInput() {
    return controls.entrySet().stream()
        .anyMatch(
            entry ->
                entry.getValue().editable()
                    && !Objects.equals(
                        versions.get(entry.getKey()), acceptedVersions.get(entry.getKey())));
  }

  design.inherent.reform.presentation.graphics.PreviewInputStamp previewInputStamp(
      design.inherent.reform.edit.DraftRevision expected) {
    ClientSettingsRuntime.requireClientThread();
    if (closed) throw new PolicyRefusal("editor.closed", "This retained form is closed");
    var current = actions.view();
    if (formUnavailable
        || current.availability() != EditorActor.Availability.READY
        || current.snapshot().isEmpty())
      throw new PolicyRefusal("editor.unavailable", "No current preview input is available");
    if (pendingWork() || retainedInput() || unsent())
      throw new PolicyRefusal(
          "preview.input_unaccepted",
          "Finish or repair the retained input before preparing a preview");
    if (!expected.matches(current.snapshot().orElseThrow()))
      throw new PolicyRefusal("preview.stale", "The requested preview draft is no longer current");
    if (!actions.mayInspect())
      throw new PolicyRefusal(
          "owner.permission", "Read access to this preview input is unavailable");
    return new design.inherent.reform.presentation.graphics.PreviewInputStamp(
        expected, previewInputEpoch);
  }

  Map<DocumentId, design.inherent.reform.reference.ReferenceStamp> previewReferenceStamps() {
    var stamps = new LinkedHashMap<DocumentId, design.inherent.reform.reference.ReferenceStamp>();
    sources.forEach(
        (id, source) -> source.observedStamp().ifPresent(stamp -> stamps.put(id, stamp)));
    return Map.copyOf(stamps);
  }

  private void advancePreviewInput() {
    if (previewInputEpoch == Long.MAX_VALUE)
      throw new PolicyRefusal(
          "preview.input_epoch_exhausted", "Reopen this editor before further native edits");
    previewInputEpoch++;
  }

  boolean pendingWork() {
    return saving || loading || pendingEdits > 0;
  }

  boolean canStage(FieldPath path) {
    var current = actions.view();
    return !closed
        && !formUnavailable
        && !pendingWork()
        && !retainedInput()
        && !unsent()
        && current.availability() == EditorActor.Availability.READY
        && current
            .snapshot()
            .filter(snapshot -> snapshot.draft().contains(path) && snapshot.draft().activeAt(path))
            .isPresent()
        && current.pending().isEmpty()
        && current.review().isEmpty()
        && locallyAvailable(path);
  }

  boolean canSubmit() {
    var current = actions.view();
    return !closed
        && !formUnavailable
        && !pendingWork()
        && scopedSave == null
        && !retainedInput()
        && !unsent()
        && current.availability() == EditorActor.Availability.READY
        && current.snapshot().isPresent()
        && current.validation().valid()
        && current.pending().isEmpty()
        && current.review().isEmpty();
  }

  void releaseBorrowed() {
    closed = true;
    editorAbandoned = null;
    disposePresentation();
  }

  private void abandonEditing() {
    if (editorAbandoned == null) actions.close();
    else editorAbandoned.run();
  }

  /** No implicit initialization, actor lookup or settings ownership is inferred by the screen. */
  SettingsView(
      Screen parent,
      Component title,
      Component contextLabel,
      DocumentDefinition<T> definition,
      DocumentActions<T> actions) {
    this(parent, title, contextLabel, definition, actions, Map.of());
  }

  SettingsView(
      Screen parent,
      Component title,
      Component contextLabel,
      DocumentDefinition<T> definition,
      DocumentActions<T> actions,
      Map<DocumentId, ? extends ReferenceActions<String, ?>> sources) {
    this(parent, title, contextLabel, definition, actions, sources, FormPresentation.EMPTY);
  }

  SettingsView(
      Screen parent,
      Component title,
      Component contextLabel,
      DocumentDefinition<T> definition,
      DocumentActions<T> actions,
      Map<DocumentId, ? extends ReferenceActions<String, ?>> sources,
      FormPresentation presentation) {
    this(
        parent,
        title,
        contextLabel,
        definition,
        actions,
        sources,
        presentation,
        FieldEditors.builder(definition).build());
  }

  SettingsView(
      Screen parent,
      Component title,
      Component contextLabel,
      DocumentDefinition<T> definition,
      DocumentActions<T> actions,
      Map<DocumentId, ? extends ReferenceActions<String, ?>> sources,
      FormPresentation presentation,
      FieldEditors<T> fieldEditors) {
    this(
        parent,
        title,
        contextLabel,
        definition,
        actions,
        sources,
        presentation,
        fieldEditors,
        SubmissionLabels.standard(actions.commitKind()));
  }

  SettingsView(
      Screen parent,
      Component title,
      Component contextLabel,
      DocumentDefinition<T> definition,
      DocumentActions<T> actions,
      Map<DocumentId, ? extends ReferenceActions<String, ?>> sources,
      FormPresentation presentation,
      FieldEditors<T> fieldEditors,
      SubmissionLabels submissionLabels) {
    super(title);
    this.parent = parent;
    this.contextLabel = Objects.requireNonNull(contextLabel);
    this.definition = Objects.requireNonNull(definition);
    this.actions = Objects.requireNonNull(actions);
    var admitted = new LinkedHashMap<DocumentId, ReferenceActions<String, ?>>();
    sources.forEach(
        (id, source) -> {
          if (!id.equals(source.source()))
            throw new IllegalArgumentException(
                "Reference binding must retain its actual source ID");
          admitted.put(id, source);
        });
    this.sources = Map.copyOf(admitted);
    this.presentation = Objects.requireNonNull(presentation);
    this.fieldEditors = Objects.requireNonNull(fieldEditors);
    this.submissionLabels = Objects.requireNonNull(submissionLabels);
    fieldEditors.requireCompatible(definition);
    dependencies = presentation.bind(definition.type());
    if (definition != actions.definition())
      throw new IllegalArgumentException("A form requires its actual owner definition");
    catalog = new FormNavigationIndex(presentation, definition.id());
  }

  private static Component text(String key, String fallback) {
    return Component.translatableWithFallback("reform.settings." + key, fallback);
  }

  private boolean contextRecipe() {
    return actions.commitKind() == DocumentSession.CommitKind.CONTEXT;
  }

  private Component commitLabel() {
    if (scopedSaveLabel != null) return scopedSaveLabel.copy();
    return submissionLabels.primary();
  }

  private <O> CompletionStage<O> action(Supplier<OperationHandle<O>> operation) {
    try {
      return DocumentActions.result(operation.get());
    } catch (RuntimeException refused) {
      return java.util.concurrent.CompletableFuture.<O>failedFuture(refused)
          .minimalCompletionStage();
    }
  }

  /** Cached read-only editing state for composed/native views; no validation or owner effect. */
  public EditorActor.View<T> editorView() {
    return actions.view();
  }

  /** Current native control only; the caller gains no owner or persistence authority. */
  public Optional<AbstractWidget> fieldControl(FieldPath path) {
    if (closed || !actions.isOpen() || !isCurrent()) return Optional.empty();
    if (!visibleFields.contains(path) && !editorButtons.containsKey(path)) return Optional.empty();
    var scalar = visibleFields.contains(path) ? controls.get(path) : null;
    return scalar != null
        ? Optional.of(scalar.widget())
        : Optional.ofNullable(
            defaultControls.containsKey(path)
                ? defaultControls.get(path)
                : editorButtons.get(path));
  }

  /** Cached catalog selection; this performs no owner operation or draft mutation. */
  public Optional<FieldPath> selectedSection() {
    return closed
        ? Optional.empty()
        : catalog.nearest(workspaceSelection).map(FormCatalog.Entry::path);
  }

  FormTarget navigationFormTarget() {
    return selectedBox == null ? new FormTarget.Field(workspaceSelection) : selectedBox;
  }

  /** Select an admitted section while preserving pending-input and lifetime checks. */
  public boolean selectSection(FieldPath path) {
    if (!catalog.contains(path)) return false;
    boolean selected =
        changeCatalog(
            () -> {
              workspaceSelection = path;
            });
    if (selected) focusField(path);
    return selected;
  }

  private boolean changeCatalog(Runnable navigation) {
    if (closed || minecraft == null || !isCurrent()) return false;
    if (saving || loading || pendingEdits > 0 || unsent()) {
      notice = text("catalog_wait", "Finish pending input before changing sections.");
      return false;
    }
    navigation.run();
    rebuildWidgets();
    return true;
  }

  /** Focus and reveal a currently rendered field, including related diagnostic destinations. */
  public boolean focusField(FieldPath path) {
    selectedBox = null;
    boolean rebuild = composition == null && hasNavigation() && !workspaceSelection.equals(path);
    workspaceSelection = path;
    if (rebuild) rebuildWidgets();
    if (path.equals(FieldPath.ROOT) && panel != null) {
      var owned = new ArrayList<AbstractWidget>();
      visibleFields.stream()
          .map(controls::get)
          .filter(Objects::nonNull)
          .map(Control::widget)
          .forEach(owned::add);
      owned.addAll(defaultControls.values());
      owned.addAll(editorButtons.values());
      owned.addAll(availabilityButtons.values());
      owned.addAll(informationButtons.values());
      setFocused(panel);
      return panel.focusFirst(owned);
    }
    var controlPath = path;
    if (fieldControl(path).isEmpty()) {
      controlPath =
          controls.keySet().stream()
              .filter(value -> value.startsWith(path))
              .findFirst()
              .orElseGet(
                  () ->
                      editorButtons.keySet().stream()
                          .filter(value -> value.startsWith(path))
                          .findFirst()
                          .orElse(path));
    }
    var control = visibleFields.contains(controlPath) ? controls.get(controlPath) : null;
    var widget = control == null ? editorButtons.get(controlPath) : control.widget();
    if (widget == null || !widget.active) {
      widget = availabilityButtons.get(path);
      if (widget == null)
        widget =
            structurePaths.entrySet().stream()
                .filter(entry -> entry.getValue().equals(path))
                .map(Map.Entry::getKey)
                .findFirst()
                .orElse(null);
    }
    if (panel == null || !bodyVisible) return false;
    if (widget == null) {
      if (catalog == null || !catalog.selectedPath().filter(path::equals).isPresent()) return false;
      setFocused(panel);
      panel.focusVisibleControl();
      return panel.getFocused() != null;
    }
    String focus = panel.fieldFocus(key(controlPath) + (control == null ? ":editor" : ""), widget);
    if (focus == null) return false;
    setFocused(panel);
    panel.restoreFocus(focus);
    return true;
  }

  @Override
  public boolean focusTarget(FormTarget target) {
    if (target instanceof FormTarget.Field field) return focusField(field.path());
    var section = (FormTarget.Section) target;
    if (closed || !isCurrent()) return false;
    var declared =
        catalog.targets().stream().filter(entry -> entry.id().equals(section)).findFirst();
    if (declared.isEmpty()) return false;
    if (composition == null
        && hasNavigation()
        && !workspaceSelection.equals(declared.orElseThrow().scope())) {
      workspaceSelection = declared.orElseThrow().scope();
      rebuildWidgets();
    }
    if (closed || !isCurrent() || panel == null || !bodyVisible) return false;
    if (!panel.focusGroup(scoped(section.id()))) return false;
    setFocused(panel);
    selectedBox = section;
    return true;
  }

  void focusAfterLoad(FieldPath path) {
    focusAfterLoad(new FormTarget.Field(path));
  }

  void focusAfterLoad(FormTarget target) {
    requestedFocus = Objects.requireNonNull(target);
    revealRequestedFocus();
  }

  private void revealRequestedFocus() {
    if (requestedFocus == null
        || !bodyVisible
        || !isCurrent()
        || loading
        || actions.view().snapshot().isEmpty()) return;
    var path = requestedFocus;
    requestedFocus = null;
    if (!focusTarget(path))
      notice =
          text(
              "inspect_target_unavailable",
              "That destination is unavailable in the current draft.");
  }

  @Override
  protected void init() {
    documentTheme = super.theme().child(presentation.theme().root());
    fieldTheme = null;
    activated = true;
    // Reconcile only acknowledged local input with edits made through another view/service.
    // A refused or still-pending local edit must remain available for explicit recovery.
    synchronize(Map.copyOf(acceptedVersions));
    previousFields = java.util.Set.copyOf(visibleFields);
    {
      controls.forEach(
          (path, control) -> {
            if (previousFields.contains(path) && control.widget() instanceof FormEditBox input) {
              inputPositions.remove(path);
              inputPositions.put(
                  path,
                  new InputPosition(
                      input.getCursorPosition(),
                      input.selection(),
                      Optional.ofNullable(control.quantity()).map(value -> value.input.unit())));
            }
          });
      int maximum =
          actions.view().snapshot().map(value -> value.draft().limits().maximumNodes()).orElse(1);
      while (inputPositions.size() > maximum)
        inputPositions.remove(inputPositions.keySet().iterator().next());
    }
    displayedLanguage = Language.getInstance();
    widgetGeneration = new Object();
    float scroll = panel == null ? 0 : panel.scroll();
    String focus = panel == null ? null : panel.focusKey();
    footer.clear();
    footerCallbacks.clear();
    structureButtons.clear();
    structurePaths.clear();
    defaultControls.clear();
    availabilityButtons.clear();
    availabilityRows.clear();
    editorButtons.clear();
    titledReferences.clear();
    disclosureButtons.clear();
    informationButtons.clear();
    var current = actions.view();
    save = button(commitLabel(), this::requestSave);
    reviewSubmission =
        button(text("review_changes", "Review changes…"), () -> openInspection(true));
    outcomeButton = button(text("operation", "Operation…"), this::openOutcome);
    previewButton =
        preview == null ? null : button(text("preview_recipe", "Preview…"), this::openPreview);
    undo = button(text("undo", "Undo"), () -> requestHistory(false));
    redo = button(text("redo", "Redo"), () -> requestHistory(true));
    revert =
        button(
            text("revert", "Revert"),
            () -> perform(action(() -> actions.revert(EntryPoint.GUI)), true));
    defaults =
        button(
            defaultsLabel(), () -> perform(action(() -> actions.defaults(EntryPoint.GUI)), true));
    if (!defaultsHelp().getString().isEmpty()) defaults.setTooltip(Tooltip.create(defaultsHelp()));
    reload =
        button(
            contextRecipe()
                ? text("reload_context", "Reload context")
                : text("reload", "Reload saved"),
            this::requestReload);
    importData = button(text("import", "Import…"), this::openImport);
    conflict =
        current.review().isPresent()
            ? button(text("conflict", "Review conflict…"), this::openConflict)
            : null;
    done = button(text("done", "Done"), this::onClose);
    var more = button(text("document_actions", "Document actions…"), this::openActions);
    footer.clear();
    footer.add(done);
    footer.add(more);
    footer.add(revert);
    footer.add(reviewSubmission);
    if (conflict != null) footer.add(conflict);
    footer.add(save);
    if (composition != null) {
      inspection = button(text("inspect", "Inspect…"), this::openInspection);
      footer.remove(inspection);
      appendComposedRows(current);
      return;
    }
    inspection = button(text("inspect", "Inspect…"), this::openInspection);
    inspection.setTooltip(
        Tooltip.create(text("inspect_help", "Inspect source, changes and diagnostics")));
    footer.remove(save);
    footer.add(save);
    chrome =
        new FormChrome(
            minecraft,
            new FormGeometry.Placement(left, top, width, height),
            title,
            contextLabel,
            status(),
            footer,
            theme());
    var content = prepareContent(current);
    var model = content.model();
    var modelError = content.error();
    bodyVisible = true;
    panel = chrome.panel();
    chrome.mountActions(this::addRenderableWidget, panel);
    chrome.alignTrailingAction();
    if (inlineInformation != null) inlineInformation.append(panel);
    if (modelError != null) panel.row(text("form_unavailable", "Form unavailable"), modelError);
    if (model != null && bodyVisible) {
      expandedEditors.retainAll(
          formLayout.stream()
              .map(FormModel.Layout::path)
              .collect(java.util.stream.Collectors.toSet()));
      standaloneFields(model);
    }
    retainModelInputs();
    if (bodyVisible) addRenderableWidget(panel);
    panel.finish(scroll);
    panel.restoreFocus(focus);
    if (focus != null) setFocused(panel);
    if (retainedBodyScroll != null) panel.restore(retainedBodyScroll);
    if (retainedBodyFocused && retainedBodyFocus != null) {
      setFocused(panel);
      panel.restoreFocus(retainedBodyFocus);
    }
    if (inlineInformation != null) inlineInformation.focus(panel, () -> setFocused(panel));
    displayed = current;
    feedback();
    if (!requested) {
      requested = true;
      if (current.availability() == EditorActor.Availability.LOADING) requestReload();
    }
  }

  private record Content(FormModel model, Component error) {}

  private Content prepareContent(EditorActor.View<T> current) {
    visibleFields.clear();
    formLayout = List.of();
    FormModel model = null;
    Component modelError = null;
    if (current.snapshot().isPresent()) {
      var snapshot = current.snapshot().orElseThrow();
      if (!snapshot.session().equals(formSession) || snapshot.generation() != formGeneration)
        inputPositions.clear();
      formSession = snapshot.session();
      formGeneration = snapshot.generation();
      try {
        model = model(snapshot.draft());
        formLayout = model.layout();
        formUnavailable = false;
      } catch (IllegalArgumentException unavailable) {
        formUnavailable = true;
        notice = failure(unavailable);
        modelError = notice;
      }
    }
    if (catalog != null)
      catalog.project(
          model, current.snapshot().map(value -> value.session()).orElse(null), formGeneration);
    if (composition == null && !catalog.allFields())
      catalog.nearest(workspaceSelection).ifPresent(section -> catalog.select(section.path()));
    return new Content(model, modelError);
  }

  private void standaloneFields(FormModel model) {
    if (!hasNavigation() || catalog.allFields()) {
      fields(model.root(), title);
      return;
    }
    var selected = catalog.nearest(workspaceSelection);
    if (selected.isEmpty()) {
      var generation = widgetGeneration;
      var fallback =
          theme()
              .buttonBuilder(
                  text("catalog_all_fields", "Show all fields"),
                  ignored -> {
                    if (closed || !isCurrent() || widgetGeneration != generation) return;
                    catalog.showAllFields();
                    rebuildWidgets();
                  })
              .bounds(0, 0, 120, 20)
              .build();
      panel.row(text("catalog_section", "Section unavailable"), catalog.unavailable(), fallback);
      return;
    }
    var section = selected.orElseThrow();
    if (section.node().isEmpty()) {
      panel.row(text("catalog_section", "Section unavailable"), catalog.unavailable());
      return;
    }
    var node = section.node().orElseThrow();
    var shape = node.type().orElseThrow().shape();
    boolean inlineHelp =
        (shape instanceof ValueShape.Scalar
                || shape instanceof ValueShape.Reference<?> reference
                    && !referenceTitles.containsKey(reference.source()))
            && fieldEditors.at(node.path(), node.type().orElseThrow()).isEmpty();
    if (!inlineHelp) catalog.help().ifPresent(help -> panel.row(Component.empty(), help));
    var caption = catalog.caption(node.path());
    if (node.role() == FormModel.Role.ROW || node.role() == FormModel.Role.MAP_ROW) {
      var parent = catalog.parent().orElseThrow();
      rowActions(parent, node, parent.children().indexOf(node), caption);
    }
    fields(node, caption);
  }

  private void appendComposedRows(EditorActor.View<T> current) {
    panel = composition.panel();
    if (composition.selections().isEmpty()) {
      visibleFields.clear();
      bodyVisible = false;
      displayed = current;
      feedback();
      if (!requested) {
        requested = true;
        if (current.availability() == EditorActor.Availability.LOADING) requestReload();
      }
      return;
    }
    panel.scoped(theme(), () -> appendScopedRows(current));
  }

  private void appendScopedRows(EditorActor.View<T> current) {
    var content = prepareContent(current);
    panel = composition.panel();
    bodyVisible = true;
    if (content.error() != null)
      panel.row(text("form_unavailable", "Form unavailable"), content.error());
    else if (content.model() != null) {
      var nodes = new LinkedHashMap<FieldPath, FormModel.Node>();
      var pending = new java.util.ArrayDeque<FormModel.Node>();
      pending.push(content.model().root());
      while (!pending.isEmpty()) {
        var node = pending.pop();
        nodes.put(node.path(), node);
        for (var child : node.children().reversed()) pending.push(child);
      }
      ClientWorkflowLayout.Section openSection = null;
      for (var selection : composition.selections()) {
        var section = selection.section().orElse(null);
        if (!Objects.equals(openSection, section)) {
          if (openSection != null) panel.endGroup();
          openSection = section;
          if (section != null) {
            if (!section.eyebrow().getString().isBlank()) panel.eyebrow(section.eyebrow());
            panel.beginGroup(scoped("section:" + section.id()), section.title());
            if (!section.help().getString().isBlank()) panel.row(Component.empty(), section.help());
          }
        }
        var node = nodes.get(selection.path());
        if (node == null) {
          panel.row(selection.title(), text("field_unavailable", "Unavailable in this draft"));
          continue;
        }
        if (node.path().equals(FieldPath.ROOT)) {
          if (openSection == null) panel.section(key(node.path()), selection.title());
          else panel.anchor(key(node.path()));
        }
        if (!selection.help().getString().isBlank()) panel.row(Component.empty(), selection.help());
        fields(node, selection.title());
      }
      if (openSection != null) panel.endGroup();
    }
    retainModelInputs();
    displayed = current;
    feedback();
    if (!requested) {
      requested = true;
      if (current.availability() == EditorActor.Availability.LOADING) requestReload();
    }
  }

  private void retainModelInputs() {
    var retained =
        formLayout.stream()
            .map(FormModel.Layout::path)
            .collect(java.util.stream.Collectors.toSet());
    controls.keySet().retainAll(retained);
    local.keySet().retainAll(retained);
    versions.keySet().retainAll(retained);
    acceptedVersions.keySet().retainAll(retained);
    inputPositions.keySet().retainAll(retained);
    expandedEditors.retainAll(retained);
  }

  @Override
  public FormPresentation fieldPresentation() {
    return presentation;
  }

  @Override
  public List<FieldPath> fieldOrder() {
    var declared = SettingsForm.super.fieldOrder();
    var snapshot = actions.view().snapshot();
    if (snapshot.isEmpty()) return declared;
    FormModel tree;
    try {
      tree = model(snapshot.orElseThrow().draft());
    } catch (IllegalArgumentException unavailable) {
      return declared;
    }
    var admitted = new java.util.HashSet<>(declared);
    var ordered = new java.util.LinkedHashSet<FieldPath>();
    var pending = new java.util.ArrayDeque<FormModel.Node>();
    pending.add(tree.root());
    while (!pending.isEmpty()) {
      var node = pending.removeFirst();
      if (admitted.contains(node.path())) {
        ordered.add(node.path());
        // A declared whole collection/value is one review field, not a second row projection.
        continue;
      }
      var children =
          design.inherent.reform.presentation.FormComposition.fields(
              design.inherent.reform.presentation.FormComposition.resolve(
                  node.children(), presentation));
      for (int at = children.size() - 1; at >= 0; at--) pending.addFirst(children.get(at));
    }
    ordered.addAll(declared);
    return List.copyOf(ordered);
  }

  private FormModel model(DraftTree draft) {
    if (draft != modelDraft) {
      modelDraft = draft;
      cachedModel = null;
      modelFailure = null;
      try {
        cachedModel = new FormModel(definition.type(), draft);
      } catch (IllegalArgumentException failed) {
        modelFailure = failed;
      }
    }
    if (modelFailure != null) throw modelFailure;
    return cachedModel;
  }

  void discardDocument(Runnable discard) {
    discardDocument = discard;
  }

  private DocumentActionsScreen.Action menuAction(Supplier<Button> control, Component help) {
    return new DocumentActionsScreen.Action(
        control.get().getMessage(),
        help,
        () -> !closed && control.get() != null && control.get().active,
        () -> {
          var current = control.get();
          if (!closed && isCurrent() && current != null && current.active) {
            var callback = footerCallbacks.get(current);
            if (callback != null) callback.run();
          }
        });
  }

  private void openActions() {
    if (closed || !isCurrent()) return;
    if (saving || loading || pendingEdits > 0 || unsent()) {
      notice = text("actions_wait", "Finish pending input before opening document actions.");
      return;
    }
    var groups = new ArrayList<DocumentActionsScreen.Group>();
    groups.add(
        new DocumentActionsScreen.Group(
            text("edit_history", "Edit history"),
            List.of(
                menuAction(() -> undo, Component.empty()),
                menuAction(() -> redo, Component.empty()))));
    groups.add(
        new DocumentActionsScreen.Group(
            text("document_source", "Source and defaults"),
            List.of(
                menuAction(() -> defaults, defaultsHelp()),
                menuAction(() -> reload, Component.empty()),
                menuAction(() -> importData, Component.empty()))));
    var results = new ArrayList<DocumentActionsScreen.Action>();
    results.add(
        menuAction(
            () -> inspection, text("inspect_help", "Inspect source, changes and diagnostics")));
    results.add(menuAction(() -> outcomeButton, Component.empty()));
    if (previewButton != null) results.add(menuAction(() -> previewButton, Component.empty()));
    groups.add(
        new DocumentActionsScreen.Group(
            text("document_results", "Inspection and results"), results));
    if (discardDocument != null)
      groups.add(
          new DocumentActionsScreen.Group(
              text("document_lifetime", "Draft"),
              List.of(
                  new DocumentActionsScreen.Action(
                      text("discard", "Discard draft"),
                      text("discard_help", "Release this draft. Completed saves are retained."),
                      () -> !closed && !saving && !loading && pendingEdits == 0,
                      () -> {
                        if (!closed && isCurrent()) discardDocument.run();
                      }))));
    var owner = title.copy();
    openScreen(
        new DocumentActionsScreen(
            actions,
            owner,
            contextLabel,
            groups,
            () -> !closed,
            () -> openScreen(ownerScreen()),
            this::abandonEditing));
  }

  @Override
  protected void rebuildWidgets() {
    if (composition != null) {
      super.rebuildWidgets();
      return;
    }
    var bookmark = panel == null ? null : panel.bookmark();
    String focusedField = panel == null ? null : panel.focusKey();
    int focusedFooter = footer.indexOf(getFocused());
    boolean inspectionFocused = getFocused() == inspection;
    super.rebuildWidgets();
    if (bodyVisible && bookmark != null) panel.restore(bookmark);
    if (bodyVisible && focusedField != null) {
      setFocused(panel);
      panel.restoreFocus(focusedField);
    }
    if (inspectionFocused) setFocused(inspection);
    else if (focusedFooter >= 0 && focusedFooter < footer.size())
      setFocused(footer.get(focusedFooter));
  }

  @Override
  protected void setInitialFocus() {
    if (panel != null
        && bodyVisible
        && (catalog != null || !controls.isEmpty() || !editorButtons.isEmpty())) {
      setFocused(panel);
      panel.focusVisibleControl();
      if (panel.getFocused() != null) return;
    }
    super.setInitialFocus();
  }

  private Button button(Component label, Runnable action) {
    var generation = widgetGeneration;
    var result =
        theme()
            .buttonBuilder(
                label,
                ignored -> {
                  if (!closed && isCurrent() && widgetGeneration == generation) action.run();
                })
            .bounds(0, 0, Math.max(56, font.width(label) + 20), 20)
            .build();
    footer.add(result);
    footerCallbacks.put(result, action);
    return result;
  }

  private void fields(FormModel.Node field, Component proposedLabel) {
    var previous = fieldTheme;
    var metadata = presentation.theme().field(field.path());
    var next = panel.theme();
    if (metadata.isPresent()) {
      var declared = metadata.orElseThrow();
      if (declared.variant().isPresent())
        next = next.componentDefaults(declared.variant().orElseThrow().defaults());
      next = next.child(declared.patch());
    }
    fieldTheme = next;
    try {
      panel.scoped(next, () -> fieldContents(field, proposedLabel));
    } finally {
      fieldTheme = previous;
    }
  }

  private void fieldContents(FormModel.Node field, Component proposedLabel) {
    Component label =
        catalog != null && catalog.contains(field.path())
            ? catalog.caption(field.path())
            : proposedLabel;
    var path = field.path();
    var node = field.input();
    var shape = field.type().orElseThrow().shape();
    var editor = fieldEditors.at(path, field.type().orElseThrow());
    if (editor.isPresent()) {
      var generation = widgetGeneration;
      var change =
          theme()
              .buttonBuilder(
                  text("edit_field", "Edit…"),
                  ignored -> {
                    if (widgetGeneration == generation)
                      openField(field, label, editor.orElseThrow());
                  })
              .bounds(0, 0, 80, 20)
              .createNarration(original -> label.copy().append(": ").append(original.get()))
              .build();
      var raw =
          theme()
              .buttonBuilder(
                  expandedEditors.contains(path)
                      ? text("hide_fields", "Hide fields")
                      : text("show_fields", "Show fields"),
                  ignored -> {
                    if (closed
                        || !isCurrent()
                        || widgetGeneration != generation
                        || saving
                        || loading
                        || pendingEdits > 0
                        || unsent()) return;
                    if (!expandedEditors.remove(path)) expandedEditors.add(path);
                    rebuildWidgets();
                  })
              .bounds(0, 0, 100, 20)
              .createNarration(original -> label.copy().append(": ").append(original.get()))
              .build();
      editorButtons.put(path, change);
      disclosureButtons.put(path, raw);
      panel.field(
          key(path) + ":editor",
          label,
          presentation
              .unavailableAt(path)
              .map(ignored -> dependencyText(path))
              .orElseGet(
                  () ->
                      text(
                          "editor_help",
                          "Edit this field, or show its raw controls to repair input.")),
          change,
          raw,
          informationButton(path, label));
      if (!expandedEditors.contains(path)) return;
    }
    if (editor.isEmpty()
        && shape instanceof ValueShape.Reference<?> reference
        && referenceTitles.containsKey(reference.source())) {
      titledReference(path, label, reference);
      if (!expandedEditors.contains(path)) return;
      panel.beginGroup(key(path) + ":manual_id", text("manual_reference_id", "Manual ID"));
      try {
        scalarContents(field, label);
      } finally {
        panel.endGroup();
      }
      return;
    }
    if (!(shape instanceof ValueShape.Scalar || shape instanceof ValueShape.Reference<?>)
        && hasAvailability(path)) {
      String key = key(path) + ":availability";
      availabilityRows.put(path, key);
      panel.row(key, label, dependencyText(path), availabilityButton(path, label));
    }
    if (shape instanceof ValueShape.ObjectValue && node instanceof DraftNode.ObjectValue) {
      if (!path.equals(FieldPath.ROOT)) panel.section(key(path), label);
      children(field);
      return;
    }
    if ((shape instanceof ValueShape.Sequence && node instanceof DraftNode.Sequence)
        || (shape instanceof ValueShape.Mapping && node instanceof DraftNode.Mapping)) {
      int size = field.children().size();
      int maximum =
          shape instanceof ValueShape.Sequence sequence
              ? sequence.maximumSize()
              : ((ValueShape.Mapping) shape).maximumSize();
      var add =
          structuralButton(
              path,
              text("add_entry", "Add entry"),
              label,
              new DraftEdit.InsertRow(Optional.empty()));
      add.setTooltip(Tooltip.create(text("add_entry_help", "Add a blank entry for editing")));
      structureButtons.put(add, size < maximum);
      var collectionControls = new ArrayList<AbstractWidget>();
      collectionControls.add(add);
      collectionControls.add(informationButton(path, label));
      if (shape instanceof ValueShape.Sequence sequence) {
        var element = sequence.element().shape();
        if (element instanceof ValueShape.Reference<?> reference)
          element = reference.element().shape();
        if (element instanceof ValueShape.Scalar scalar
            && (sequence.element().shape() instanceof ValueShape.Reference<?>
                || scalar.kind() == ValueShape.ScalarKind.BOOLEAN
                || scalar.kind() == ValueShape.ScalarKind.CHOICE)) {
          var generation = widgetGeneration;
          var choose =
              theme()
                  .buttonBuilder(
                      text("choose_values", "Choose values…"),
                      ignored -> {
                        if (widgetGeneration == generation) openCollection(path, label);
                      })
                  .bounds(0, 0, 120, 20)
                  .createNarration(original -> label.copy().append(": ").append(original.get()))
                  .build();
          structureButtons.put(choose, true);
          structurePaths.put(choose, path);
          collectionControls.add(choose);
        }
      }
      panel.row(
          key(path) + ":collection",
          label,
          Component.translatableWithFallback(
              "reform.settings.entry_count", "%s entries (limit %s)", size, maximum),
          collectionControls.toArray(AbstractWidget[]::new));
      for (int i = 0; i < size; i++) {
        var child = field.children().get(i);
        var rowLabel =
            Component.translatableWithFallback("reform.settings.entry", "Entry %s", i + 1);
        rowActions(field, child, i, label);
        fields(child, rowLabel);
      }
      return;
    }
    if (shape instanceof ValueShape.Defaulted<?> defaults
        && node instanceof DraftNode.Defaulted value) {
      boolean direct = defaultedBoolean(path, label, defaults, value);
      if (!direct)
        panel.row(
            key(path) + ":default",
            label,
            value.overridden()
                ? text("override_active", "Override active")
                : text("using_default", "Using default"),
            structuralButton(
                path,
                value.overridden()
                    ? text("use_default", "Use default")
                    : text("override", "Override"),
                label,
                new DraftEdit.Override(!value.overridden())),
            informationButton(path, label));
      for (var retained : value.retainedOverrides())
        panel.row(
            key(path) + ":retained:" + retained.id(),
            text("retained_override", "Retained input"),
            Component.literal(preview(retained.value())),
            structuralButton(
                path,
                text("recover_input", "Recover input"),
                label,
                new DraftEdit.RestoreOverride(retained.id())),
            structuralButton(
                path,
                text("discard_input", "Discard input"),
                label,
                new DraftEdit.DiscardOverride(retained.id())));
      if (!direct) for (var child : field.children()) fields(child, label);
      return;
    }
    if (shape instanceof ValueShape.OptionalValue<?>
        && node instanceof DraftNode.OptionalValue value) {
      panel.row(
          key(path) + ":optional",
          label,
          value.present() ? Component.empty() : text("empty_value", "Empty"),
          structuralButton(
              path,
              value.present() ? text("use_empty", "Use empty") : text("add_value", "Add value"),
              label,
              new DraftEdit.Presence(!value.present())));
      for (var child : field.children()) fields(child, label);
      return;
    }
    if (shape instanceof ValueShape.Alternative alternatives
        && node instanceof DraftNode.Alternative value) {
      var choices =
          new ArrayList<>(alternatives.variants().stream().map(ValueShape.Variant::id).toList());
      if (!choices.contains(value.selected())) choices.addFirst(value.selected());
      var generation = widgetGeneration;
      var choice =
          NativeCycles.<String>builder(
                  id ->
                      id.isEmpty()
                          ? text("choose_variant", "Choose a variant")
                          : Component.literal(readable(id)),
                  value.selected(),
                  choices)
              .displayOnlyValue()
              .create(
                  0,
                  0,
                  160,
                  20,
                  label,
                  (widget, id) -> {
                    if (widgetGeneration == generation)
                      structural(path, new DraftEdit.SelectVariant(id));
                  });
      structureButtons.put(choice, true);
      structurePaths.put(choice, path);
      panel.row(key(path) + ":variant", label, Component.empty(), choice);
      for (var branch : value.branches().keySet())
        if (!branch.equals(value.selected()))
          panel.row(
              key(path) + ":branch:" + branch,
              text("inactive_branch", "Inactive branch"),
              Component.literal(clip(branch, 120)),
              structuralButton(
                  path,
                  text("discard_branch", "Discard branch"),
                  label,
                  new DraftEdit.DiscardBranch(branch)));
      children(field);
      return;
    }
    if (!(shape instanceof ValueShape.Scalar || shape instanceof ValueShape.Reference<?>)) {
      panel.row(
          key(path) + ":repair",
          label,
          text(
              "retained_malformed",
              "Input is retained; repair replaces it with a blank value and can be undone."),
          structuralButton(
              path, text("repair_value", "Repair value"), label, new DraftEdit.Repair()));
    }
    scalarContents(field, label);
  }

  private void scalarContents(FormModel.Node field, Component label) {
    var path = field.path();
    var node = field.input();
    var shape = field.type().orElseThrow().shape();
    visibleFields.add(path);
    var control = controls.get(path);
    var raw = raw(node);
    boolean cycle = field.cycling();
    if (control == null
        || !control.shape().equals(shape)
        || control.cycle() != cycle
        || (control.quantity() != null)
            != (node instanceof DraftNode.Scalar
                && shape instanceof ValueShape.Scalar scalar
                && scalar.numericDisplay().isPresent())
        || !control.target().session().equals(formSession)) {
      local.put(path, raw);
      versions.put(path, 0L);
      acceptedVersions.put(path, 0L);
      control = createControl(shape, path, node, label, cycle, null);
      if (control.quantity() != null) local.put(path, control.quantity().input.text());
      controls.put(path, control);
    } else if (!previousFields.contains(path)) {
      // Keep the editing state; a detached native widget must not become current again.
      control = createControl(shape, path, node, label, cycle, control);
      controls.put(path, control);
    } else if (control.target().generation() != formGeneration) {
      control =
          new Control(
              control.widget(),
              control.display(),
              control.editable(),
              control.shape(),
              control.cycle(),
              actions.view().snapshot().orElseThrow().target(path).typing(),
              control.token(),
              control.quantity());
      controls.put(path, control);
    }
    if (shape instanceof ValueShape.Reference<?> reference) {
      var source = sources.get(reference.source());
      var generation = widgetGeneration;
      var browse =
          theme()
              .buttonBuilder(
                  source == null
                      ? text("source_unavailable", "Source unavailable")
                      : text("browse", "Browse…"),
                  ignored -> {
                    if (widgetGeneration == generation) openReference(path, label, source);
                  })
              .bounds(0, 0, 120, 20)
              .createNarration(original -> label.copy().append(": ").append(original.get()))
              .build();
      browse.setTooltip(Tooltip.create(Component.literal(reference.source().toString())));
      structureButtons.put(browse, source != null);
      structurePaths.put(browse, path);
      fieldRow(path, label, control.widget(), browse);
    } else if (control.quantity() != null) {
      var quantity = control.quantity();
      ControlSizing.offer(quantity.units, unitWidth(quantity.input), 20);
      if (quantity.slider == null)
        fieldRow(path, label, control.widget(), quantity.less, quantity.more, quantity.units);
      else
        fieldRow(
            path,
            label,
            control.widget(),
            quantity.less,
            quantity.more,
            quantity.units,
            quantity.slider);
    } else fieldRow(path, label, control.widget());
  }

  private void titledReference(FieldPath path, Component label, ValueShape.Reference<?> reference) {
    var generation = widgetGeneration;
    var source = sources.get(reference.source());
    var choose =
        theme()
            .buttonBuilder(
                Component.empty(),
                ignored -> {
                  if (widgetGeneration == generation) openReference(path, label, source);
                })
            .bounds(0, 0, 160, 20)
            .createNarration(original -> label.copy().append(": ").append(original.get()))
            .build();
    var raw =
        theme()
            .buttonBuilder(
                expandedEditors.contains(path)
                    ? text("hide_reference_id", "Hide ID")
                    : text("edit_reference_id", "Edit ID"),
                ignored -> {
                  if (closed
                      || !isCurrent()
                      || widgetGeneration != generation
                      || saving
                      || loading
                      || pendingEdits > 0
                      || unsent()) return;
                  boolean expanding = !expandedEditors.remove(path);
                  if (expanding) expandedEditors.add(path);
                  rebuildWidgets();
                  if (expanding) focusField(path);
                  else {
                    setFocused(panel);
                    panel.focusFirst(List.of(disclosureButtons.get(path)));
                  }
                })
            .bounds(0, 0, 80, 20)
            .createNarration(original -> label.copy().append(": ").append(original.get()))
            .build();
    editorButtons.put(path, choose);
    disclosureButtons.put(path, raw);
    var guidance =
        source == null
            ? text(
                "reference_source_missing",
                "Source unavailable. Edit the ID to retain or repair its value.")
            : text("reference_title_help", "Choose from the source, or edit the ID directly.");
    var purpose = fieldHelp(path);
    var help =
        purpose.getString().isEmpty() ? guidance : purpose.copy().append("\n").append(guidance);
    var titled =
        new TitledReference(
            path, reference.source(), choose, referenceTitles.get(reference.source()), help);
    titledReferences.put(path, titled);
    panel.relatedField(
        key(path) + ":editor",
        expandedEditors.contains(path) ? null : relationship(path),
        label,
        help,
        choose,
        raw,
        informationButton(path, label));
    titled.refresh();
  }

  private void rowActions(FormModel.Node field, FormModel.Node child, int i, Component label) {
    int size = field.children().size();
    var shape = field.type().orElseThrow().shape();
    int maximum =
        shape instanceof ValueShape.Sequence sequence
            ? sequence.maximumSize()
            : ((ValueShape.Mapping) shape).maximumSize();
    var path = field.path();
    var row = ((FieldPath.Row) child.path().segments().getLast()).id();
    var caption = catalog == null ? Component.empty() : catalog.caption(child.path());
    var rowLabel =
        caption.getString().isBlank()
            ? Component.translatableWithFallback("reform.settings.entry", "Entry %s", i + 1)
            : caption;
    var rowContext = label.copy().append(": ").append(rowLabel);
    var up =
        structuralButton(
            path,
            text("move_up", "Up"),
            rowContext,
            new DraftEdit.MoveRow(
                row,
                i > 0
                    ? Optional.of(
                        ((FieldPath.Row) field.children().get(i - 1).path().segments().getLast())
                            .id())
                    : Optional.of(row)));
    var down =
        structuralButton(
            path,
            text("move_down", "Down"),
            rowContext,
            new DraftEdit.MoveRow(
                row,
                i + 2 < size
                    ? Optional.of(
                        ((FieldPath.Row) field.children().get(i + 2).path().segments().getLast())
                            .id())
                    : Optional.empty()));
    var duplicate =
        structuralButton(
            path,
            text("duplicate", "Copy"),
            rowContext,
            new DraftEdit.DuplicateRow(row, Optional.empty()));
    structureButtons.put(up, i > 0);
    structureButtons.put(down, i + 1 < size);
    structureButtons.put(duplicate, size < maximum);
    panel.row(
        scoped(child.key()) + ":row",
        rowLabel,
        Component.empty(),
        up,
        down,
        duplicate,
        structuralButton(path, text("remove", "Remove"), rowContext, new DraftEdit.RemoveRow(row)));
  }

  private void fieldRow(FieldPath path, Component label, AbstractWidget... widgets) {
    var row = new ArrayList<>(List.of(widgets));
    row.add(informationButton(path, label));
    if (hasAvailability(path)) row.add(availabilityButton(path, label));
    panel.relatedField(
        key(path), relationship(path), label, fieldHelp(path), row.toArray(AbstractWidget[]::new));
    var control = controls.get(path);
    if (control != null && control.quantity() != null)
      panel.keepTogether(control.quantity().less, control.quantity().more);
  }

  private String relationship(FieldPath path) {
    return presentation
        .range(path)
        .map(range -> scoped("range:" + range.id()))
        .or(() -> presentation.layout().row(path).map(row -> scoped("row:" + row.id())))
        .orElse(null);
  }

  private Component fieldHelp(FieldPath path) {
    var declared =
        presentation.catalog().sections().stream()
            .filter(section -> section.path().equals(path) && CatalogHelp.present(section))
            .findFirst();
    if (declared.isPresent()) {
      var section = declared.orElseThrow();
      return CatalogHelp.describe(section);
    }
    String key = labelKey(path) + ".help";
    return Language.getInstance().has(key) ? Component.translatable(key) : Component.empty();
  }

  private boolean defaultedBoolean(
      FieldPath path, Component label, ValueShape.Defaulted<?> shape, DraftNode.Defaulted value) {
    if (!(shape.element().shape() instanceof ValueShape.Scalar scalar)
        || scalar.kind() != ValueShape.ScalarKind.BOOLEAN
        || !(shape.fallback() instanceof Boolean fallback)
        || value.overridden()
            && (!(value.value() instanceof DraftNode.Scalar raw)
                || !List.of("true", "false").contains(raw.raw()))) return false;
    var selected = value.overridden() ? ((DraftNode.Scalar) value.value()).raw() : "default";
    var generation = widgetGeneration;
    java.util.function.Function<String, Component> caption =
        choice ->
            switch (choice) {
              case "default" ->
                  text("default", "Default")
                      .copy()
                      .append(" (")
                      .append(fallback ? text("on", "On") : text("off", "Off"))
                      .append(")");
              case "true" -> text("on", "On");
              case "false" -> text("off", "Off");
              default -> throw new IllegalArgumentException("Select an admitted boolean choice");
            };
    int controlWidth =
        List.of("default", "true", "false").stream()
            .mapToInt(
                choice ->
                    font.width(caption.apply(choice))
                        + theme()
                            .values()
                            .value(
                                design.inherent.reform.presentation.ThemeValues.Role
                                    .CONTROL_CAPTION_PADDING)
                        + theme()
                            .values()
                            .value(
                                design.inherent.reform.presentation.ThemeValues.Role
                                    .CONTROL_CHOICE_INDICATOR))
            .max()
            .orElseThrow();
    var button =
        NativeCycles.<String>builder(caption, selected, List.of("default", "true", "false"))
            .displayOnlyValue()
            .create(
                0,
                0,
                controlWidth,
                20,
                label,
                (widget, choice) -> {
                  if (widgetGeneration == generation)
                    structural(
                        path,
                        new DraftEdit.SelectDefaultedScalar(
                            choice.equals("default") ? Optional.empty() : Optional.of(choice)));
                });
    structureButtons.put(button, true);
    structurePaths.put(button, path);
    defaultControls.put(path, button);
    visibleFields.add(path);
    fieldRow(path, label, button);
    return true;
  }

  private InformationButton informationButton(FieldPath path, Component label) {
    var generation = widgetGeneration;
    var button =
        new InformationButton(
            label,
            () -> {
              if (!closed && isCurrent() && widgetGeneration == generation && mayInspect())
                information(path, label);
            });
    informationButtons.put(path, button);
    return button;
  }

  private Button availabilityButton(FieldPath path, Component label) {
    var generation = widgetGeneration;
    var button =
        theme()
            .buttonBuilder(
                text("availability", "Availability"),
                ignored -> {
                  if (!closed && isCurrent() && widgetGeneration == generation)
                    notice = dependencyText(path);
                })
            .bounds(0, 0, Math.max(80, font.width(text("availability", "Availability")) + 16), 20)
            .createNarration(original -> label.copy().append(": ").append(dependencyText(path)))
            .build();
    availabilityButtons.put(path, button);
    return button;
  }

  private FormDependencies.Decision dependency(FieldPath path) {
    if (!dependencies.affects(path)) return FormDependencies.Decision.AVAILABLE;
    var current = actions.view();
    if (current.availability() != EditorActor.Availability.READY || current.snapshot().isEmpty())
      return new FormDependencies.Decision(FormDependencies.State.UNKNOWN, List.of());
    if (dependencyView != current || dependencyVersion != version || dependencyEvaluation == null) {
      var draft = current.snapshot().orElseThrow().draft();
      var pending = new java.util.HashSet<FieldPath>();
      for (var input : dependencies.inputs()) {
        var control = controls.get(input);
        if (control != null && !matches(input, control, draft)) pending.add(input);
      }
      dependencyEvaluation = dependencies.evaluate(draft, current.inputs(), pending);
      dependencyView = current;
      dependencyVersion = version;
    }
    return dependencyEvaluation.at(path);
  }

  private boolean locallyAvailable(FieldPath path) {
    return presentation.unavailableAt(path).isEmpty()
        && dependency(path).state() == FormDependencies.State.AVAILABLE;
  }

  private boolean hasAvailability(FieldPath path) {
    return presentation.unavailableAt(path).isPresent() || dependencies.affects(path);
  }

  private Component dependencyText(FieldPath path) {
    var unavailable = presentation.unavailableAt(path);
    if (unavailable.isPresent()) {
      var reason = unavailable.orElseThrow();
      return text("field_unavailable", "Unavailable")
          .copy()
          .append("\n")
          .append(Component.translatableWithFallback(reason.translationKey(), reason.fallback()));
    }
    var decision = dependency(path);
    MutableComponent result =
        switch (decision.state()) {
          case AVAILABLE -> text("available", "Available").copy();
          case DISABLED -> text("inactive", "Inactive").copy();
          case UNKNOWN -> text("availability_unknown", "Availability unknown").copy();
        };
    for (var reason : decision.reasons())
      result
          .append("\n")
          .append(Component.translatableWithFallback(reason.translationKey(), reason.fallback()))
          .append(" (")
          .append(inputLabel(reason.input()))
          .append(")");
    return result;
  }

  private Component inputLabel(FieldPath path) {
    return FieldLabels.input(definition.id(), path, title);
  }

  private <V> void openReference(
      FieldPath path, Component label, ReferenceActions<String, V> source) {
    if (closed
        || !isCurrent()
        || source == null
        || saving
        || loading
        || pendingEdits > 0
        || unsent()) return;
    if (!locallyAvailable(path)) return;
    Supplier<ReferenceSelection<T, V>> factory =
        () -> new ReferenceSelection<>(actions, path, source);
    final ReferenceSelection<T, V> selection;
    try {
      selection = factory.get();
    } catch (design.inherent.reform.owner.PolicyRefusal | IllegalArgumentException refused) {
      notice = failure(refused);
      return;
    }
    var screen =
        new ReferenceScreen<>(
            label,
            selection,
            source.limits(),
            factory,
            () -> {
              synchronize(Map.copyOf(versions));
              openScreen(ownerScreen());
            },
            this::abandonEditing);
    handoff = true;
    try {
      openScreen(screen);
    } finally {
      handoff = false;
    }
  }

  private void openCollection(FieldPath path, Component label) {
    if (closed
        || !isCurrent()
        || saving
        || loading
        || pendingEdits > 0
        || unsent()
        || !locallyAvailable(path)) return;
    try {
      var selection = new CollectionSelection<>(actions, path);
      var source = selection.view().referenceSource().map(sources::get).orElse(null);
      var screen =
          new CollectionScreen<>(
              label,
              selection,
              source,
              labelKey(path) + "/choices/",
              () -> locallyAvailable(path),
              () -> {
                synchronize(Map.copyOf(versions));
                openScreen(ownerScreen());
              },
              this::abandonEditing);
      handoff = true;
      try {
        openScreen(screen);
      } finally {
        handoff = false;
      }
    } catch (design.inherent.reform.owner.PolicyRefusal | IllegalArgumentException refused) {
      notice = failure(refused);
    }
  }

  private void openInspection() {
    openInspection(false);
  }

  boolean acceptsInspection() {
    var view = actions.view();
    return !closed
        && !saving
        && !loading
        && pendingEdits == 0
        && !unsent()
        && view.availability() == EditorActor.Availability.READY
        && view.snapshot().isPresent();
  }

  void openInspection(boolean submitting) {
    if (!isCurrent() || !acceptsInspection()) return;
    var focused = panel == null ? null : panel.getFocused();
    var selected =
        controls.entrySet().stream()
            .filter(entry -> entry.getValue().widget() == focused)
            .map(Map.Entry::getKey)
            .findFirst()
            .or(() -> catalog == null ? Optional.empty() : catalog.selectedPath());
    var screen =
        new ReviewScreen<>(
            submitting
                ? text("review_title", "Review changes")
                : text("inspect_title", "Source and value inspection"),
            title.copy().append(" · ").append(contextLabel),
            actions,
            selected,
            () -> !closed,
            path -> {
              synchronize(Map.copyOf(versions));
              openScreen(ownerScreen());
              path.ifPresent(
                  value -> {
                    if (!focusField(value))
                      notice =
                          text(
                              "inspect_field_unavailable",
                              "That field is unavailable in the current draft.");
                  });
            },
            this::abandonEditing,
            submitting
                ? Optional.of(
                    new ReviewScreen.Submission(
                        scopedSaveLabel == null ? submissionLabels.reviewed() : scopedSaveLabel,
                        target -> {
                          synchronize(Map.copyOf(versions));
                          openScreen(ownerScreen());
                          requestSave(false, Optional.of(target), true);
                        }))
                : Optional.empty());
    screen.withActions(reviewActions);
    screen.withFieldOrder(fieldOrder());
    screen.withFieldPresentation(presentation);
    handoff = true;
    try {
      openScreen(screen);
    } finally {
      handoff = false;
    }
  }

  private void openOutcome() {
    var selected = lastCommit;
    if (closed || !isCurrent() || selected == null) return;
    var id = selected.snapshot().id();
    var screen =
        new OperationScreen<>(
            text("operation_title", "Operation result"),
            contextLabel,
            selected,
            () ->
                !closed
                    && actions.view().availability() == EditorActor.Availability.READY
                    && actions.operation(id).filter(handle -> handle == selected).isPresent(),
            CommitFeedback::details,
            List.of(
                new OperationScreen.Action(
                    "apply_saved",
                    text("retry_application", "Apply saved values"),
                    () -> applicationBlocked(selected),
                    () -> {
                      synchronize(Map.copyOf(versions));
                      openScreen(ownerScreen());
                      if (applicationBlocked(selected).isEmpty())
                        beginCommit(() -> actions.applySaved(EntryPoint.GUI), false, true);
                    })),
            () -> {
              synchronize(Map.copyOf(versions));
              openScreen(ownerScreen());
            },
            this::abandonEditing);
    handoff = true;
    try {
      openScreen(screen);
    } finally {
      handoff = false;
    }
  }

  private void openPreview() {
    if (closed
        || !isCurrent()
        || preview == null
        || saving
        || loading
        || pendingEdits > 0
        || unsent()) return;
    try {
      var screen =
          preview.open(
              () -> {
                synchronize(Map.copyOf(versions));
                openScreen(ownerScreen());
              },
              this::abandonEditing);
      handoff = true;
      try {
        openScreen(screen);
      } finally {
        handoff = false;
      }
    } catch (PolicyRefusal refused) {
      notice = failure(refused);
      feedback();
    }
  }

  private Optional<Component> applicationBlocked(OperationHandle<EditorActor.Commit<T>> selected) {
    if (closed
        || selected != lastCommit
        || actions.view().availability() != EditorActor.Availability.READY)
      return Optional.of(text("application_unavailable", "This editing context is unavailable."));
    if (!selected.snapshot().terminal() || saving || loading || pendingEdits > 0)
      return Optional.of(text("application_wait", "Wait for the current operation to settle."));
    if (!actions.supportedOperations().contains(DocumentSession.Operation.APPLY_SAVED))
      return Optional.of(
          text(
              "application_unsupported",
              "This owner has no application-only service. Follow its declared restart or context procedure."));
    if (actions.view().pending().isPresent() || actions.view().review().isPresent())
      return Optional.of(
          text(
              "application_reconcile",
              "Reconcile the pending outcome or source conflict before applying saved values."));
    var result = selected.snapshot().result();
    var receipt = result.flatMap(EditorActor.Commit::receipt);
    if (receipt.isEmpty()
        || receipt.orElseThrow().baselineOutcomeUnknown()
        || !receipt.orElseThrow().acknowledgesBaseline())
      return Optional.of(
          text(
              "application_no_receipt",
              "No confirmed saved baseline is available for this recovery."));
    var application = receipt.orElseThrow().application();
    if (application == CommitReceipt.Application.APPLIED
        || application == CommitReceipt.Application.STAGED
        || application == CommitReceipt.Application.NOT_ATTEMPTED)
      return Optional.of(
          text(
              "application_not_needed",
              "No application-only recovery is indicated by this receipt."));
    return Optional.empty();
  }

  boolean acceptsImport() {
    var view = actions.view();
    return !closed
        && !saving
        && !loading
        && pendingEdits == 0
        && !unsent()
        && view.availability() == EditorActor.Availability.READY
        && view.snapshot().isPresent()
        && view.pending().isEmpty()
        && view.review().isEmpty();
  }

  void openImport() {
    if (!isCurrent() || !acceptsImport()) return;
    var view = actions.view();
    var snapshot = view.snapshot().orElseThrow();
    var screen =
        new ImportScreen<>(
            text("import_title", "Import profile"),
            contextLabel,
            actions,
            declaredImports == null
                ? JsonImportFormat.documentFormats(
                    definition,
                    snapshot.jsonLimits(),
                    snapshot.draft().limits(),
                    ImportFormat.InvalidDraftPolicy.ALLOW_REPAIRABLE)
                : declaredImports.parsers(),
            () -> !closed,
            () -> {
              synchronize(Map.copyOf(versions));
              openScreen(ownerScreen());
            },
            this::abandonEditing,
            Optional.ofNullable(declaredImports));
    screen.withFieldOrder(fieldOrder());
    screen.withFieldPresentation(presentation);
    handoff = true;
    try {
      openScreen(screen);
    } finally {
      handoff = false;
    }
  }

  private void openField(FormModel.Node field, Component label, FieldEditors.Editor<T> editor) {
    var view = actions.view();
    if (closed
        || !isCurrent()
        || saving
        || loading
        || pendingEdits > 0
        || unsent()
        || view.availability() != EditorActor.Availability.READY
        || view.snapshot().isEmpty()
        || view.pending().isPresent()
        || view.review().isPresent()
        || !locallyAvailable(field.path())) return;
    if (layoutChanged(view)) {
      notice = text("review_changed_form", "The form changed; review this control again.");
      rebuildWidgets();
      return;
    }
    var snapshot = view.snapshot().orElseThrow();
    try {
      var request =
          new FieldEditors.Request<>(
              label,
              contextLabel,
              actions,
              snapshot.target(field.path()),
              field.type().orElseThrow(),
              snapshot.draft(),
              () -> !closed && locallyAvailable(field.path()),
              () -> {
                synchronize(Map.copyOf(versions));
                openScreen(ownerScreen());
              },
              this::abandonEditing);
      var screen = Objects.requireNonNull(editor.open(request));
      if (screen == ownerScreen())
        throw new IllegalArgumentException("Open a separate auxiliary screen");
      handoff = true;
      try {
        openScreen(screen);
      } finally {
        handoff = false;
      }
    } catch (RuntimeException refused) {
      notice = failure(refused);
    }
  }

  private void children(FormModel.Node parent) {
    composed(
        design.inherent.reform.presentation.FormComposition.resolve(
            parent.children(), presentation));
  }

  private void composed(List<design.inherent.reform.presentation.FormComposition.Part> parts) {
    for (var part : parts)
      switch (part) {
        case design.inherent.reform.presentation.FormComposition.Field field ->
            composedField(field.node());
        case design.inherent.reform.presentation.FormComposition.Box box -> {
          var scopedTheme = panel.theme();
          var declared = presentation.theme().section(box.id());
          if (declared.isPresent()) {
            var metadata = declared.orElseThrow();
            if (metadata.variant().isPresent())
              scopedTheme =
                  scopedTheme.componentDefaults(metadata.variant().orElseThrow().defaults());
            scopedTheme = scopedTheme.child(metadata.patch());
          }
          panel.scoped(
              scopedTheme,
              () -> {
                panel.beginGroup(
                    scoped(box.id()),
                    Component.translatableWithFallback(box.translationKey(), box.label()));
                if (!box.help().isBlank())
                  panel.row(
                      Component.empty(),
                      Component.translatableWithFallback(
                          box.translationKey() + ".help", box.help()));
                composed(box.children());
                panel.endGroup();
              });
        }
        case design.inherent.reform.presentation.FormComposition.Row row -> {
          for (var field : row.children()) composedField(field.node());
        }
      }
  }

  private void composedField(FormModel.Node child) {
    if (child.role() == FormModel.Role.UNKNOWN) {
      var label =
          Component.translatableWithFallback(
              "reform.settings.unknown_field", "Unknown field: %s", clip(child.name(), 120));
      var retained =
          new FormEditBox(
              font,
              0,
              0,
              theme()
                  .values()
                  .value(design.inherent.reform.presentation.ThemeValues.Role.CONTROL_TEXT_INPUT),
              theme()
                  .values()
                  .value(design.inherent.reform.presentation.ThemeValues.Role.CONTROL_HEIGHT),
              label);
      String raw = raw(child.input());
      retained.setMaxLength(Math.max(128, raw.length()));
      retained.setValue(raw);
      retained.setEditable(false);
      panel.row(
          scoped(child.key()),
          label,
          text("unknown_retained", "Retained until explicitly removed"),
          retained,
          structuralButton(
              new FieldPath(child.path().segments().subList(0, child.path().segments().size() - 1)),
              text("remove", "Remove"),
              label,
              new DraftEdit.RemoveUnknown(child.name())));
    } else fields(child, label(child));
  }

  boolean acceptsConflict() {
    return !closed
        && !saving
        && !loading
        && pendingEdits == 0
        && !unsent()
        && actions.view().review().isPresent()
        && actions.view().pending().isEmpty();
  }

  void openConflict() {
    if (!acceptsConflict()) return;
    final ConflictScreen<T> screen;
    try {
      screen =
          new ConflictScreen<>(
                  text("conflict_title", "Review conflict"),
                  contextLabel,
                  actions,
                  () -> !closed,
                  result -> {
                    result.ifPresent(
                        resolved ->
                            notice =
                                resolved.report().valid()
                                    ? text(
                                        "conflict_resolved",
                                        "Choices resolved in this draft. Save or apply separately.")
                                    : text(
                                            "conflict_needs_repair",
                                            "Choices resolved in this draft; validation needs attention.")
                                        .copy()
                                        .append(" ")
                                        .append(first(resolved.report())));
                    synchronize(Map.copyOf(versions));
                    openScreen(ownerScreen());
                  },
                  this::abandonEditing)
              .withFieldOrder(fieldOrder())
              .withFieldPresentation(presentation);
    } catch (RuntimeException refused) {
      notice = failure(refused);
      feedback();
      return;
    }
    handoff = true;
    try {
      openScreen(screen);
    } finally {
      handoff = false;
    }
  }

  private Component label(FormModel.Node field) {
    return switch (field.role()) {
      case ROOT -> title;
      case FIELD ->
          Component.translatableWithFallback(labelKey(field.path()), readable(field.name()));
      case ROW, MAP_ROW ->
          Component.translatableWithFallback("reform.settings.entry", "Entry %s", field.name());
      case KEY -> text("entry_key", "Key");
      case VALUE -> text("entry_value", "Value");
      case VARIANT -> Component.literal(readable(field.name()));
      case UNKNOWN -> Component.literal(clip(field.name(), 120));
    };
  }

  private Button structuralButton(
      FieldPath path, Component label, Component context, DraftEdit.Action edit) {
    var generation = widgetGeneration;
    var button =
        theme()
            .buttonBuilder(
                label,
                ignored -> {
                  if (widgetGeneration == generation) structural(path, edit);
                })
            .bounds(0, 0, Math.max(40, font.width(label) + 16), 20)
            .createNarration(original -> context.copy().append(": ").append(original.get()))
            .build();
    structureButtons.put(button, true);
    structurePaths.put(button, path);
    return button;
  }

  private void structural(FieldPath path, DraftEdit.Action edit) {
    if (closed || !isCurrent() || !locallyAvailable(path)) return;
    var current = actions.view();
    if (pendingEdits > 0
        || saving
        || loading
        || unsent()
        || current.availability() != EditorActor.Availability.READY
        || current.snapshot().isEmpty()) return;
    if (layoutChanged(current)) {
      notice = text("review_changed_form", "The form changed; review this control again.");
      rebuildWidgets();
      return;
    }
    var before = Map.copyOf(versions);
    pendingEdits++;
    notice = null;
    action(
            () ->
                actions.edit(
                    EntryPoint.GUI,
                    new DraftEdit(current.snapshot().orElseThrow().target(path), edit)))
        .whenComplete(
            (result, failed) ->
                minecraft.execute(
                    () -> {
                      pendingEdits--;
                      if (failed != null) notice = failure(failed);
                      else {
                        synchronize(before);
                        if (edit instanceof DraftEdit.RestoreOverride)
                          notice =
                              text(
                                  "recovered_input",
                                  "Input recovered. Enable Override to use it if the default is still active.");
                      }
                      if (isCurrent()) {
                        if (edit instanceof DraftEdit.SelectDefaultedScalar
                            || layoutChanged(actions.view())) rebuildWidgets();
                        else feedback();
                      }
                    }));
    feedback();
  }

  private static String preview(DraftNode node) {
    String value = raw(node);
    return value.isEmpty()
        ? text("structured_input", "Structured input retained").getString()
        : clip(value, 160);
  }

  private static String clip(String text, int maximum) {
    return text.codePointCount(0, text.length()) <= maximum
        ? text
        : text.substring(0, text.offsetByCodePoints(0, maximum)) + "…";
  }

  private Control createControl(
      ValueShape declared,
      FieldPath path,
      DraftNode node,
      Component label,
      boolean cycle,
      Control retained) {
    String raw = retained == null ? raw(node) : local.getOrDefault(path, raw(node));
    var target = actions.view().snapshot().orElseThrow().target(path).typing();
    var token = new Object();
    ValueShape shape =
        declared instanceof ValueShape.Reference<?> reference
            ? reference.element().shape()
            : declared;
    if (shape instanceof ValueShape.Scalar scalar) {
      var choices =
          scalar.kind() == ValueShape.ScalarKind.BOOLEAN
              ? List.of("false", "true")
              : scalar.choices();
      if (cycle) {
        java.util.function.Function<String, Component> caption =
            value ->
                scalar.kind() == ValueShape.ScalarKind.BOOLEAN
                    ? value.equals("true") ? text("on", "On") : text("off", "Off")
                    : Component.literal(value);
        var button =
            NativeCycles.<String>builder(caption, raw, choices)
                .displayOnlyValue()
                .create(
                    0,
                    0,
                    Math.max(
                        60,
                        Math.min(
                            theme()
                                .values()
                                .value(
                                    design.inherent.reform.presentation.ThemeValues.Role
                                        .CONTROL_UNIT_PREFERRED_LIMIT),
                            choices.stream()
                                .mapToInt(
                                    value ->
                                        font.width(caption.apply(value))
                                            + theme()
                                                .values()
                                                .value(
                                                    design.inherent.reform.presentation.ThemeValues
                                                        .Role.CONTROL_CAPTION_PADDING)
                                            + theme()
                                                .values()
                                                .value(
                                                    design.inherent.reform.presentation.ThemeValues
                                                        .Role.CONTROL_CHOICE_INDICATOR))
                                .max()
                                .orElse(60))),
                    20,
                    label,
                    (widget, value) -> edit(path, token, value));
        return new Control(button, button::setValue, true, declared, true, target, token, null);
      }
      Quantity quantity =
          scalar.numericDisplay().isPresent() && node instanceof DraftNode.Scalar value
              ? quantity(
                  scalar, value, path, label, token, retained == null ? null : retained.quantity())
              : null;
      if (quantity != null) raw = quantity.input.text();
      int inputLimit =
          quantity == null
              ? scalar.maximumLength() > 0 ? scalar.maximumLength() : 128
              : quantity.input.display().limits().maximumCharacters();
      var input =
          new FormEditBox(
              font,
              0,
              0,
              scalar.kind() == ValueShape.ScalarKind.TEXT
                  ? theme()
                      .values()
                      .value(
                          design.inherent.reform.presentation.ThemeValues.Role.CONTROL_TEXT_INPUT)
                  : theme()
                      .values()
                      .value(
                          design.inherent.reform.presentation.ThemeValues.Role
                              .CONTROL_NUMERIC_INPUT),
              theme()
                  .values()
                  .value(design.inherent.reform.presentation.ThemeValues.Role.CONTROL_HEIGHT),
              label);
      input.setMaxLength(Math.max(raw.length(), inputLimit));
      input.setValue(raw);
      var position = inputPositions.get(path);
      if (position != null) {
        input.setCursorPosition(Math.min(position.cursor(), raw.length()));
        input.setHighlightPos(Math.min(position.selection(), raw.length()));
      }
      input.setResponder(value -> edit(path, token, value));
      return new Control(
          input,
          value -> {
            input.setMaxLength(
                Math.max(inputLimit, Math.max(input.getValue().length(), value.length())));
            input.setValue(value);
          },
          true,
          declared,
          false,
          target,
          token,
          quantity);
    }
    var unavailable =
        new FormEditBox(
            font,
            0,
            0,
            theme()
                .values()
                .value(design.inherent.reform.presentation.ThemeValues.Role.CONTROL_TEXT_INPUT),
            theme()
                .values()
                .value(design.inherent.reform.presentation.ThemeValues.Role.CONTROL_HEIGHT),
            label);
    unavailable.setMaxLength(Math.max(128, raw.length()));
    unavailable.setValue(
        raw.isEmpty() ? text("unsupported", "Editor unavailable").getString() : raw);
    unavailable.setEditable(false);
    unavailable.setTooltip(
        Tooltip.create(
            text("unsupported_help", "This type needs a dedicated editor. Its data is retained.")));
    return new Control(unavailable, ignored -> {}, false, declared, false, target, token, null);
  }

  private int unitWidth(NumericInput input) {
    int width =
        input.display().units().stream()
            .mapToInt(
                unit ->
                    font.width(
                            Component.translatableWithFallback(
                                unit.translationKey(), unit.fallback()))
                        + 24)
            .max()
            .orElse(
                theme()
                    .values()
                    .value(
                        design.inherent.reform.presentation.ThemeValues.Role.CONTROL_UNIT_MINIMUM));
    if (input.display().units().stream().noneMatch(unit -> unit.id().equals(input.unit())))
      width =
          Math.max(
              width,
              font.width(
                      text("unit_unavailable", "Unavailable unit")
                          .copy()
                          .append(": " + input.unit()))
                  + 24);
    return Math.max(48, Math.min(240, width));
  }

  private Quantity quantity(
      ValueShape.Scalar shape,
      DraftNode.Scalar node,
      FieldPath path,
      Component label,
      Object token,
      Quantity retained) {
    var quantity = new Quantity(retained == null ? NumericInput.open(shape, node) : retained.input);
    if (retained != null) quantity.deferred = retained.deferred;
    var mapping = quantity.input.display();
    var rememberedPosition = inputPositions.get(path);
    if (rememberedPosition != null
        && rememberedPosition.unit().isPresent()
        && mapping.units().stream()
            .anyMatch(unit -> unit.id().equals(rememberedPosition.unit().orElseThrow()))) {
      var selection = quantity.input.select(rememberedPosition.unit().orElseThrow());
      if (selection.accepted()) quantity.input = selection.input();
    }
    var ids = new ArrayList<>(mapping.units().stream().map(unit -> unit.id()).toList());
    if (!ids.contains(quantity.input.unit())) ids.add(quantity.input.unit());
    quantity.units =
        NativeCycles.<String>builder(
                id ->
                    mapping.units().stream()
                        .filter(unit -> unit.id().equals(id))
                        .<Component>map(
                            unit ->
                                Component.translatableWithFallback(
                                    unit.translationKey(), unit.fallback()))
                        .findFirst()
                        .orElseGet(
                            () ->
                                text("unit_unavailable", "Unavailable unit")
                                    .copy()
                                    .append(": " + id)),
                quantity.input.unit(),
                ids)
            .displayOnlyValue()
            .create(
                0,
                0,
                unitWidth(quantity.input),
                20,
                label.copy().append(": ").append(text("unit", "Display unit")),
                (button, unit) -> changeQuantity(path, token, quantity.input.select(unit), false));
    quantity.less =
        theme()
            .buttonBuilder(
                Component.literal("-"),
                ignored -> changeQuantity(path, token, quantity.input.step(-1), true))
            .bounds(0, 0, 24, 20)
            .createNarration(
                original -> label.copy().append(": ").append(text("decrease", "Decrease")))
            .build();
    quantity.more =
        theme()
            .buttonBuilder(
                Component.literal("+"),
                ignored -> changeQuantity(path, token, quantity.input.step(1), true))
            .bounds(0, 0, 24, 20)
            .createNarration(
                original -> label.copy().append(": ").append(text("increase", "Increase")))
            .build();
    var hint =
        text("unit_help", "Display only; stored unit: ")
            .copy()
            .append(mapping.canonicalUnit())
            .append("; ")
            .append(text("step", "step"))
            .append(": " + mapping.step());
    mapping
        .referenceTicksPerSecond()
        .ifPresent(
            rate ->
                hint.append("; ")
                    .append(text("nominal_tick_rate", "nominal ticks per second"))
                    .append(": " + rate));
    quantity.units.setTooltip(Tooltip.create(hint));
    if (mapping.slider().isPresent()) {
      quantity.slider =
          new QuantitySlider(
              label,
              position -> changeQuantity(path, token, quantity.input.slide(position), true, true));
      quantity.slider.setTooltip(
          Tooltip.create(
              text(
                  "slider_step_help",
                  "Dragging selects the nearest declared step. Exact text entry is not rounded.")));
    }
    return quantity;
  }

  private void changeQuantity(
      FieldPath path, Object token, NumericInput.Change change, boolean stage) {
    changeQuantity(path, token, change, stage, false);
  }

  private void changeQuantity(
      FieldPath path, Object token, NumericInput.Change change, boolean stage, boolean continuous) {
    var control = controls.get(path);
    if (closed
        || !isCurrent()
        || !visibleFields.contains(path)
        || control == null
        || control.token() != token
        || control.quantity() == null
        || !locallyAvailable(path)
        || saving
        || loading
        || !continuous && (pendingEdits > 0 || unsent())) return;
    var quantity = control.quantity();
    boolean changed =
        !quantity.input.expected().equals(change.input().expected())
            || !Objects.equals(local.get(path), change.input().text());
    if (change.accepted() && changed) advancePreviewInput();
    updating = true;
    try {
      if (change.accepted()) {
        quantity.input = change.input();
        if (!quantity.input.text().equals(local.get(path)))
          control.display().accept(quantity.input.text());
        local.put(path, quantity.input.text());
      } else notice = Component.literal(change.reason());
      quantity.units.setValue(quantity.input.unit());
    } finally {
      updating = false;
    }
    if (change.accepted() && stage) {
      if (continuous && !changed) {
        feedback();
        return;
      }
      versions.put(path, ++version);
      if (continuous && pendingEdits > 0) {
        quantity.deferred = true;
        feedback();
      } else {
        quantity.deferred = false;
        submit(path, quantity.input.text());
      }
    } else feedback();
  }

  private void edit(FieldPath path, Object token, String value) {
    if (closed || !isCurrent() || !visibleFields.contains(path)) return;
    var current = controls.get(path);
    if (current == null || current.token() != token) return;
    if (!locallyAvailable(path)) return;
    if (updating || value.equals(local.get(path))) return;
    advancePreviewInput();
    if (current.quantity() != null) {
      current.quantity().input = current.quantity().input.edit(value);
      current.quantity().deferred = false;
    }
    local.put(path, value);
    versions.put(path, ++version);
    submit(path, value);
  }

  private void submit(FieldPath path, String value) {
    var control = controls.get(path);
    var submittedVersion = versions.get(path);
    var target = control.target();
    DraftEdit.Action input =
        control.quantity() == null
            ? new DraftEdit.SetRaw(value)
            : control.quantity().input.action().orElseThrow();
    pendingEdits++;
    notice = null;
    action(() -> actions.edit(EntryPoint.GUI, new DraftEdit(target, input)))
        .whenComplete(
            (result, failed) ->
                minecraft.execute(
                    () -> {
                      pendingEdits--;
                      if (failed == null
                          && controls.get(path) != null
                          && controls.get(path).token() == control.token())
                        acceptedVersions.merge(path, submittedVersion, Math::max);
                      if (failed != null) {
                        notice = failure(failed);
                        controls
                            .values()
                            .forEach(
                                item -> {
                                  if (item.quantity() != null) item.quantity().deferred = false;
                                });
                      }
                      if (isCurrent()) {
                        feedback();
                        if (failed == null) flushContinuous();
                      }
                    }));
    feedback();
  }

  private void flushContinuous() {
    if (closed
        || pendingEdits > 0
        || actions.view().availability() != EditorActor.Availability.READY
        || actions.view().snapshot().isEmpty()) return;
    var draft = actions.view().snapshot().orElseThrow().draft();
    for (var entry : controls.entrySet()) {
      var quantity = entry.getValue().quantity();
      if (quantity != null && quantity.deferred && locallyAvailable(entry.getKey())) {
        quantity.deferred = false;
        if (!matches(entry.getKey(), entry.getValue(), draft)) {
          submit(entry.getKey(), local.get(entry.getKey()));
          return;
        }
      }
    }
  }

  private boolean unsent() {
    var snapshot = actions.view().snapshot();
    if (snapshot.isEmpty()) return false;
    return controls.entrySet().stream()
        .anyMatch(
            entry ->
                entry.getValue().editable()
                    && !matches(entry.getKey(), entry.getValue(), snapshot.orElseThrow().draft()));
  }

  private boolean matches(FieldPath path, Control control, DraftTree draft) {
    return control.quantity() == null
        ? Objects.equals(local.get(path), raw(at(draft, path)))
        : control.quantity().input.text().equals(local.get(path))
            && control.quantity().input.matches(at(draft, path));
  }

  private void requestSave() {
    requestSave(false);
  }

  private void requestSave(boolean closeAfterSave) {
    requestSave(closeAfterSave, Optional.empty(), false);
  }

  private void requestSave(
      boolean closeAfterSave,
      Optional<design.inherent.reform.edit.DraftRevision> reviewed,
      boolean showOutcome) {
    if (saving || loading || pendingEdits > 0 || actions.view().snapshot().isEmpty()) return;
    if (unsent()) {
      var draft = actions.view().snapshot().orElseThrow().draft();
      for (var entry : controls.entrySet())
        if (entry.getValue().editable() && !matches(entry.getKey(), entry.getValue(), draft))
          submit(entry.getKey(), local.get(entry.getKey()));
      return;
    }
    if (!actions.view().validation().valid()
        || actions.view().pending().isPresent()
        || actions.view().review().isPresent()) return;
    if (scopedSave != null) {
      handoff = true;
      try {
        scopedSave.run();
      } catch (PolicyRefusal refused) {
        notice = failure(refused);
        feedback();
      } finally {
        handoff = false;
      }
      return;
    }
    beginCommit(
        () ->
            reviewed
                .map(target -> actions.commit(EntryPoint.GUI, target))
                .orElseGet(() -> actions.commit(EntryPoint.GUI)),
        closeAfterSave || returnAfterApply,
        showOutcome && !returnAfterApply);
  }

  private void beginCommit(
      Supplier<OperationHandle<EditorActor.Commit<T>>> submit,
      boolean closeAfterSave,
      boolean showOutcome) {
    var before = Map.copyOf(versions);
    saving = true;
    notice = null;
    final OperationHandle<EditorActor.Commit<T>> operation;
    try {
      operation = submit.get();
      lastCommit = operation;
    } catch (RuntimeException refused) {
      saving = false;
      notice = failure(refused);
      feedback();
      return;
    }
    DocumentActions.result(operation)
        .whenComplete(
            (result, failed) ->
                minecraft.execute(
                    () -> {
                      saving = false;
                      if (failed != null) notice = failure(failed);
                      else if (!result.editorReport().valid())
                        notice = first(result.editorReport());
                      else
                        result
                            .receipt()
                            .ifPresent(
                                receipt -> {
                                  notice = CommitFeedback.summary(receipt);
                                  if (receipt.acknowledgesBaseline()) synchronize(before);
                                  var after = actions.view();
                                  boolean finished =
                                      receipt.application() == CommitReceipt.Application.APPLIED
                                          || receipt.application()
                                              == CommitReceipt.Application.STAGED
                                          || receipt.persistence()
                                                  == CommitReceipt.Persistence.UNCHANGED
                                              && receipt.application()
                                                  == CommitReceipt.Application.NOT_ATTEMPTED;
                                  if (closeAfterSave
                                      && isCurrent()
                                      && !closed
                                      && receipt.acknowledgesBaseline()
                                      && receipt.persistenceReport().valid()
                                      && receipt.applicationReport().valid()
                                      && finished
                                      && after.availability() == EditorActor.Availability.READY
                                      && after.snapshot().isPresent()
                                      && after.pending().isEmpty()
                                      && after.review().isEmpty()
                                      && after.validation().valid()
                                      && pendingEdits == 0
                                      && !loading
                                      && !after.dirty()
                                      && !unsent()) finish();
                                });
                      if (isCurrent()) {
                        if (layoutChanged(actions.view())) rebuildWidgets();
                        else feedback();
                      }
                    }));
    if (showOutcome && !closed && isCurrent()) openOutcome();
  }

  private void perform(CompletionStage<EditorActor.View<T>> operation, boolean synchronize) {
    perform(operation, synchronize, false);
  }

  private void requestReload() {
    if (saving || loading || pendingEdits > 0) return;
    if (unsent()) {
      notice = text("reload_unsent", "Retry edits or discard them explicitly before reloading.");
      return;
    }
    loading = true;
    feedback();
    perform(action(() -> actions.load(EntryPoint.GUI)), true, true);
  }

  private void perform(
      CompletionStage<EditorActor.View<T>> operation, boolean synchronize, boolean finishesReload) {
    var before = Map.copyOf(versions);
    notice = null;
    operation.whenComplete(
        (result, failed) ->
            minecraft.execute(
                () -> {
                  if (failed != null) notice = failure(failed);
                  else if (synchronize) synchronize(before);
                  if (finishesReload) loading = false;
                  if (isCurrent()) {
                    if (layoutChanged(actions.view())) rebuildWidgets();
                    else feedback();
                  }
                }));
  }

  private void synchronize(Map<FieldPath, Long> before) {
    var snapshot = actions.view().snapshot();
    if (snapshot.isEmpty()) return;
    updating = true;
    try {
      controls.forEach(
          (path, control) -> {
            if (control.editable() && Objects.equals(before.get(path), versions.get(path))) {
              var node = at(snapshot.orElseThrow().draft(), path);
              var raw = raw(node);
              if (control.quantity() != null && node instanceof DraftNode.Scalar scalar) {
                var quantity = control.quantity();
                quantity.input = quantity.input.synchronize(scalar);
                quantity.deferred = false;
                quantity.units.setValue(quantity.input.unit());
                raw = quantity.input.text();
              }
              if (!raw.equals(local.get(path))) control.display().accept(raw);
              local.put(path, raw);
              acceptedVersions.put(path, versions.get(path));
            }
          });
    } finally {
      updating = false;
    }
  }

  private void feedback() {
    var current = actions.view();
    if (save == null) return;
    boolean unaccepted = unsent();
    boolean historyReady =
        current.availability() == EditorActor.Availability.READY
            && !saving
            && !loading
            && pendingEdits == 0
            && !unaccepted;
    undo.active = historyReady && current.canUndo();
    redo.active = historyReady && current.canRedo();
    importData.active = historyReady && current.pending().isEmpty() && current.review().isEmpty();
    inspection.active = historyReady && current.snapshot().isPresent();
    reviewSubmission.active = inspection.active;
    outcomeButton.active = lastCommit != null;
    if (previewButton != null)
      previewButton.active =
          historyReady
              && (preview.retained()
                  || (current.validation().valid()
                      && current.pending().isEmpty()
                      && current.review().isEmpty()));
    if (conflict != null)
      conflict.active = historyReady && current.review().isPresent() && current.pending().isEmpty();
    editorButtons.forEach(
        (path, button) -> {
          button.active =
              historyReady
                  && current.snapshot().isPresent()
                  && current.pending().isEmpty()
                  && current.review().isEmpty()
                  && locallyAvailable(path);
          if (!titledReferences.containsKey(path))
            button.setTooltip(locallyAvailable(path) ? null : Tooltip.create(dependencyText(path)));
        });
    disclosureButtons.forEach(
        (path, button) -> {
          button.active = !saving && !loading && pendingEdits == 0 && !unaccepted;
          button.setTooltip(
              button.active
                  ? null
                  : Tooltip.create(
                      text(
                          "settle_raw_input",
                          "Finish or explicitly discard pending input before changing field visibility.")));
        });
    save.setMessage(unaccepted ? text("retry_edits", "Retry edits") : commitLabel());
    save.active =
        current.snapshot().isPresent()
            && current.availability() == EditorActor.Availability.READY
            && !formUnavailable
            && !saving
            && !loading
            && pendingEdits == 0
            && current.pending().isEmpty()
            && current.review().isEmpty()
            && (unaccepted || current.validation().valid());
    revert.active =
        defaults.active =
            current.snapshot().isPresent() && !saving && !loading && pendingEdits == 0;
    reload.active = !saving && !loading && pendingEdits == 0;
    done.active = (editorAbandoned != null || !saving) && !loading && pendingEdits == 0;
    structureButtons.forEach(
        (button, enabled) ->
            button.active =
                enabled
                    && locallyAvailable(structurePaths.get(button))
                    && current.availability() == EditorActor.Availability.READY
                    && !loading
                    && !saving
                    && pendingEdits == 0
                    && !unaccepted);
    controls.forEach(
        (path, control) -> {
          boolean availableHere = locallyAvailable(path);
          if (control.widget() instanceof EditBox input)
            input.setEditable(
                control.editable()
                    && availableHere
                    && !loading
                    && current.availability() == EditorActor.Availability.READY
                    && (control.quantity() == null
                        || control.quantity().input.display().units().stream()
                            .anyMatch(unit -> unit.id().equals(control.quantity().input.unit()))));
          else
            control.widget().active =
                availableHere
                    && !loading
                    && current.availability() == EditorActor.Availability.READY;
          if (control.quantity() != null) {
            var quantity = control.quantity();
            boolean available =
                current.availability() == EditorActor.Availability.READY
                    && availableHere
                    && !saving
                    && !loading
                    && pendingEdits == 0
                    && !unaccepted;
            quantity.units.active = available;
            var less = quantity.input.step(-1);
            var more = quantity.input.step(1);
            quantity.less.active = available && less.accepted();
            quantity.more.active = available && more.accepted();
            quantity.less.setTooltip(
                Tooltip.create(
                    less.accepted()
                        ? text("decrease", "Decrease")
                        : Component.literal(less.reason())));
            quantity.more.setTooltip(
                Tooltip.create(
                    more.accepted()
                        ? text("increase", "Increase")
                        : Component.literal(more.reason())));
            if (quantity.slider != null) {
              quantity.slider.display(quantity.input, quantity.units.getMessage());
              quantity.slider.active =
                  !saving
                      && availableHere
                      && !loading
                      && current.availability() == EditorActor.Availability.READY
                      && quantity.input.position().isPresent();
            }
          }
          var issue =
              current.validation().issues().stream()
                  .filter(value -> value.path().equals(path) || value.related().contains(path))
                  .findFirst();
          var summary =
              issue
                  .<Component>map(
                      value ->
                          text("invalid", "Invalid").copy().append(": ").append(value.fallback()))
                  .orElseGet(
                      () ->
                          control.quantity() == null
                              ? Component.empty()
                              : numericNotice(control.quantity()));
          if (!availableHere) {
            var explanation = dependencyText(path).copy();
            if (!summary.getString().isEmpty()) explanation.append("\n").append(summary);
            summary = explanation;
          }
          var help = fieldHelp(path);
          if (!help.getString().isEmpty()) {
            if (!summary.getString().isEmpty()) help = help.copy().append("\n").append(summary);
            summary = help;
          }
          panel.summary(key(path), summary);
          if (control.widget() instanceof EditBox input && control.editable()) {
            if (input instanceof NativeTextInput themed)
              themed.valueRole(
                  issue.isEmpty() ? ClientTheme.TextRole.VALUE : ClientTheme.TextRole.ERROR);
            else
              input.setTextColor(
                  FormCanvas.textColor(
                      issue.isEmpty() ? FormPanel.TITLE_TEXT : FormPanel.ERROR_TEXT));
            input.setTooltip(issue.isEmpty() && availableHere ? null : Tooltip.create(summary));
          }
        });
    availabilityButtons.forEach(
        (path, button) -> {
          button.active = current.availability() == EditorActor.Availability.READY && !loading;
          button.setTooltip(Tooltip.create(dependencyText(path)));
        });
    availabilityRows.forEach((path, key) -> panel.summary(key, dependencyText(path)));
    informationButtons.values().forEach(button -> button.active = mayInspect() && !loading);
    refreshReferenceTitles();
  }

  private static Component numericNotice(Quantity quantity) {
    if (!quantity.input.notice().isEmpty()) return Component.literal(quantity.input.notice());
    if (quantity.input.caption().isEmpty()) return Component.empty();
    return design.inherent.reform.minecraft.NumericComponents.value(
        quantity.input.caption(),
        Component.literal(quantity.input.text()).append(" ").append(quantity.units.getMessage()));
  }

  private void refreshReferenceTitles() {
    for (var titled : titledReferences.values()) {
      titled.refresh();
      if (!sources.containsKey(titled.source)) titled.button.active = false;
    }
  }

  @Override
  public void tick() {
    if (displayedLanguage != Language.getInstance()) {
      rebuildWidgets();
      return;
    }
    if (displayed != actions.view()) {
      synchronize(Map.copyOf(acceptedVersions));
      displayed = actions.view();
      boolean changed = layoutChanged(displayed);
      if (catalog != null) {
        FormModel currentModel = null;
        if (displayed.snapshot().isPresent()) {
          try {
            currentModel = model(displayed.snapshot().orElseThrow().draft());
          } catch (IllegalArgumentException unavailable) {
            /* The form reports this state during rebuild. */
          }
        }
        changed |=
            catalog.project(
                currentModel,
                displayed.snapshot().map(value -> value.session()).orElse(null),
                displayed.snapshot().map(value -> value.generation()).orElse(0L));
      }
      if (changed) rebuildWidgets();
      else {
        feedback();
      }
    }
    revealRequestedFocus();
    refreshReferenceTitles();
  }

  private Component status() {
    var current = actions.view();
    if (notice != null) return notice;
    if (saving)
      return contextRecipe()
          ? text("admitting", "Applying recipe; waiting for the actual result")
          : text("saving", "Saving; waiting for the actual result");
    if (loading)
      return contextRecipe()
          ? text("reloading_context", "Loading context values…")
          : text("reloading", "Loading saved values…");
    if (pendingEdits > 0) return text("validating", "Validating edits…");
    if (current.availability() == EditorActor.Availability.LOADING)
      return text("loading", "Loading settings…");
    if (current.availability() == EditorActor.Availability.CLOSED)
      return text("closed", "This editing context is closed.");
    if (!current.observationReport().valid()) return first(current.observationReport());
    if (!current.validation().valid()) return first(current.validation());
    if (!current.operationReport().valid()) return first(current.operationReport());
    if (current.review().isPresent())
      return contextRecipe()
          ? text("context_review_required", "Context values changed; conflict review is required.")
          : text("review_required", "Saved values changed; conflict review is required.");
    if (current.pending().isPresent())
      return contextRecipe()
          ? text(
              "context_reconcile_required",
              "The previous application needs an observed result. Reload to review it.")
          : text(
              "reconcile_required",
              "The previous save needs an observed result. Reload to review it.");
    if (unsent())
      return text("retry_required", "Some edits were not accepted. Retry them before applying.");
    var basis = current.snapshot().flatMap(snapshot -> snapshot.draft().basis());
    if (basis.isPresent())
      return Component.translatableWithFallback(
          "reform.settings.draft_basis",
          "Unapplied recipe based on %s",
          basis.orElseThrow().source().description());
    return current.dirty()
        ? contextRecipe()
            ? text("unapplied", "Unapplied changes")
            : text("unsaved", "Unsaved changes")
        : contextRecipe()
            ? text("context_ready", "Ready; recipe draft matches context")
            : text("ready", "Ready; no unsaved edits");
  }

  @Override
  public void render(FormCanvas graphics, int mouseX, int mouseY, float delta) {
    if (composition != null) return;
    super.render(graphics, mouseX, mouseY, delta);
    chrome.render(graphics, status());
  }

  @Override
  public Component getNarrationMessage() {
    var message = title.copy().append(". ").append(contextLabel).append(". ").append(status());
    return catalog == null ? message : message.append(". ").append(catalog.narration());
  }

  @Override
  public boolean keyPressed(int key, int scan, int modifiers) {
    if (closed || !isCurrent()) return false;
    if (super.keyPressed(key, scan, modifiers)) return true;
    if (loading) return false;
    if (hasControlDown()) {
      if (key == InputConstants.KEY_I) {
        var focused = panel == null ? null : panel.getFocused();
        var path =
            controls.entrySet().stream()
                .filter(entry -> entry.getValue().widget() == focused)
                .map(Map.Entry::getKey)
                .findFirst()
                .orElse(workspaceSelection);
        information(path, FieldLabels.input(definition.id(), path, title));
        return true;
      }
      if (key == InputConstants.KEY_S || key == InputConstants.KEY_RETURN) {
        requestSave();
        return true;
      }
      if (key == InputConstants.KEY_Z) {
        requestHistory(hasShiftDown());
        return true;
      }
      if (key == InputConstants.KEY_Y) {
        requestHistory(true);
        return true;
      }
    }
    return false;
  }

  private void requestHistory(boolean forward) {
    var view = actions.view();
    if (forward ? !view.canRedo() : !view.canUndo()) return;
    perform(
        action(() -> forward ? actions.redo(EntryPoint.GUI) : actions.undo(EntryPoint.GUI)), true);
  }

  @Override
  public void onClose() {
    if (closed || !isCurrent()) return;
    if (closeThroughHost()) return;
    if (editorAbandoned != null) {
      if (loading || pendingEdits > 0) {
        notice = text("wait_close", "Wait for the current operation before closing.");
        return;
      }
      handoff = true;
      try {
        openScreen(parent);
      } finally {
        handoff = false;
      }
      return;
    }
    if (saving || loading || pendingEdits > 0) {
      notice = text("wait_close", "Wait for the current operation before closing.");
      return;
    }
    if (actions.view().dirty() || unsent() || actions.view().review().isPresent()) {
      handoff = true;
      try {
        openScreen(
            new UnsavedChangesScreen(
                contextRecipe()
                    ? text("unsaved_recipe_title", "Unapplied recipe changes")
                    : text("unsaved_title", "Unsaved changes"),
                contextLabel,
                contextRecipe()
                    ? text(
                        "discard_recipe_help",
                        "Apply this recipe, discard the draft, or keep editing. Discarding does not undo a creation-context update that already took place.")
                    : text(
                        "discard_help",
                        "Save these changes, discard the draft, or keep editing. Discarding does not undo any save that has already taken place."),
                submissionLabels.closing(),
                this::closeSaveBlocked,
                choice -> {
                  if (choice == UnsavedChangesScreen.Choice.DISCARD) finish();
                  else {
                    openScreen(ownerScreen());
                    if (choice == UnsavedChangesScreen.Choice.SAVE) requestSave(true);
                  }
                },
                this::abandonEditing));
      } finally {
        handoff = false;
      }
    } else finish();
  }

  private Optional<Component> closeSaveBlocked() {
    var view = actions.view();
    if (closed
        || view.availability() != EditorActor.Availability.READY
        || view.snapshot().isEmpty())
      return Optional.of(text("closed", "This editing context is unavailable."));
    if (saving || loading || pendingEdits > 0)
      return Optional.of(text("wait_close", "Wait for the current operation before closing."));
    if (unsent())
      return Optional.of(
          text("close_unsent", "Keep editing and retry the unaccepted input before submitting."));
    if (view.pending().isPresent())
      return Optional.of(
          text(
              "close_pending",
              "Keep editing and reconcile the previous result before submitting again."));
    if (view.review().isPresent())
      return Optional.of(
          text(
              "close_conflict",
              "Keep editing and resolve the current conflict before submitting."));
    return view.validation().valid() ? Optional.empty() : Optional.of(first(view.validation()));
  }

  private void finish() {
    closed = true;
    abandonEditing();
    disposePresentation();
    openScreen(parent);
  }

  private void disposePresentation() {
    titledReferences.clear();
    referenceTitles = Map.of();
    requestedFocus = null;
    scopedSave = null;
    scopedSaveLabel = null;
    if (catalog != null) catalog.close();
    inputPositions.clear();
    cachedModel = null;
    modelDraft = null;
    modelFailure = null;
  }

  @Override
  public void removed() {
    dependencyView = null;
    dependencyEvaluation = null;
    dependencyVersion = -1;
    if (!handoff) {
      if (!closed) abandonEditing();
      disposePresentation();
    }
  }

  private static String raw(DraftNode node) {
    if (node instanceof DraftNode.Scalar scalar) return scalar.raw();
    if (node instanceof DraftNode.Opaque opaque) return opaque.json();
    return node instanceof DraftNode.NullValue ? "null" : "";
  }

  private static DraftNode at(DraftTree draft, FieldPath path) {
    try {
      return draft.at(path);
    } catch (IllegalArgumentException unavailable) {
      return new DraftNode.Missing();
    }
  }

  private boolean layoutChanged(EditorActor.View<T> view) {
    if ((conflict != null) != view.review().isPresent()) return true;
    if (view.snapshot().isEmpty()) return !formLayout.isEmpty();
    var snapshot = view.snapshot().orElseThrow();
    if (!snapshot.session().equals(formSession) || snapshot.generation() != formGeneration)
      return true;
    try {
      return !formLayout.equals(model(snapshot.draft()).layout());
    } catch (IllegalArgumentException unavailable) {
      return !formUnavailable;
    }
  }

  private String labelKey(FieldPath path) {
    return FieldLabels.key(definition.id(), path);
  }

  private static Component first(ValidationReport report) {
    return report.issues().isEmpty()
        ? text("unavailable", "Validation is unavailable")
        : Component.literal(report.issues().getFirst().fallback());
  }

  private static Component failure(Throwable failed) {
    while (failed instanceof CompletionException && failed.getCause() != null)
      failed = failed.getCause();
    return Component.literal(Objects.toString(failed.getMessage(), "The operation is unavailable"));
  }
}
