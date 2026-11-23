package design.inherent.reform.minecraft.client;

import com.mojang.blaze3d.platform.InputConstants;
import design.inherent.reform.definition.DocumentDefinition;
import design.inherent.reform.definition.DocumentId;
import design.inherent.reform.definition.FieldPath;
import design.inherent.reform.importing.ImportCatalog;
import design.inherent.reform.operation.DocumentActions;
import design.inherent.reform.owner.DocumentSession;
import design.inherent.reform.owner.EditorActor;
import design.inherent.reform.presentation.FormGeometry;
import design.inherent.reform.presentation.FormPresentation;
import design.inherent.reform.reference.ReferenceActions;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** Native top-level host for the same reusable retained settings presentation. */
public final class SettingsScreen<T> extends FormHostScreen implements SettingsForm<T> {
  interface PreviewEntry {
    boolean retained();

    Screen open(Runnable returned, Runnable abandoned);
  }

  /** Owner-supplied presentation only; labels grant no persistence or application capability. */
  public record SubmissionLabels(Component primary, Component reviewed, Component closing) {
    public SubmissionLabels {
      primary = Objects.requireNonNull(primary).copy();
      reviewed = Objects.requireNonNull(reviewed).copy();
      closing = Objects.requireNonNull(closing).copy();
    }

    @Override
    public Component primary() {
      return primary.copy();
    }

    @Override
    public Component reviewed() {
      return reviewed.copy();
    }

    @Override
    public Component closing() {
      return closing.copy();
    }

    public static SubmissionLabels standard(DocumentSession.CommitKind kind) {
      Objects.requireNonNull(kind);
      return kind == DocumentSession.CommitKind.CONTEXT
          ? new SubmissionLabels(
              text("admit_recipe", "Apply recipe"),
              text("apply_reviewed", "Apply reviewed recipe"),
              text("apply_close", "Apply recipe and close"))
          : new SubmissionLabels(
              text("save_apply", "Save and apply"),
              text("save_reviewed", "Save reviewed changes"),
              text("save_close", "Save and close"));
    }
  }

  private final SettingsView<T> view;
  private final WorkspaceInformation information =
      new WorkspaceInformation(
          this::rebuildWidgets,
          link ->
              link.document().equals(document())
                  ? Optional.of(() -> navigate(link.path()))
                  : Optional.empty(),
          () -> minecraft != null && NativeScreens.current(minecraft) == this);
  private FormPanel informationPanel;
  private final WorkspaceNavigation navigation =
      new WorkspaceNavigation(
          () -> minecraft != null && NativeScreens.current(minecraft) == this,
          this::rebuildWidgets,
          key -> navigate(key.field().orElse(FieldPath.ROOT)));
  private WorkspaceNavigation.Widgets navigationWidgets;
  private boolean drawerOpen;
  private FieldPath displayedSection = FieldPath.ROOT;
  private WorkspaceNavigation.Position returningBody;

  /** No implicit initialization, actor lookup or settings ownership is inferred by the screen. */
  public SettingsScreen(
      Screen parent,
      Component title,
      Component contextLabel,
      DocumentDefinition<T> definition,
      DocumentActions<T> actions) {
    this(parent, title, contextLabel, definition, actions, Map.of());
  }

  public SettingsScreen(
      Screen parent,
      Component title,
      Component contextLabel,
      DocumentDefinition<T> definition,
      DocumentActions<T> actions,
      Map<DocumentId, ? extends ReferenceActions<String, ?>> sources) {
    this(parent, title, contextLabel, definition, actions, sources, FormPresentation.EMPTY);
  }

  public SettingsScreen(
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

  public SettingsScreen(
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

  public SettingsScreen(
      Screen parent,
      Component title,
      Component contextLabel,
      DocumentDefinition<T> definition,
      DocumentActions<T> actions,
      Map<DocumentId, ? extends ReferenceActions<String, ?>> sources,
      FormPresentation presentation,
      FieldEditors<T> fieldEditors,
      SubmissionLabels submissionLabels) {
    super(title, parent);
    view =
        new SettingsView<>(
            parent,
            title,
            contextLabel,
            definition,
            actions,
            sources,
            presentation,
            fieldEditors,
            submissionLabels);
  }

  private static Component text(String key, String fallback) {
    return Component.translatableWithFallback("reform.settings." + key, fallback);
  }

  public SettingsScreen<T> withImports(ImportCatalog<T> imports) {
    view.withImports(imports);
    return this;
  }

  public SettingsScreen<T> withInformation(FormInformation<T> information) {
    view.withInformation(information);
    return this;
  }

  /** Declare document-default presentation before activation; the owner operation is unchanged. */
  public SettingsScreen<T> withDefaults(Component label, Component help) {
    view.withDefaults(new ClientFormOptions.DefaultsPresentation(label, help));
    return this;
  }

  void withPreview(PreviewEntry entry) {
    view.withPreview(entry);
  }

  void withReviewActions(List<ReviewScreen.Action> entries) {
    view.withReviewActions(entries);
  }

  void returnAfterApply() {
    view.returnAfterApply();
  }

  void withScopedSave(Component title, Runnable review) {
    view.withScopedSave(title, review);
  }

  void borrow(Runnable abandoned) {
    view.borrow(abandoned);
  }

  void releaseBorrowed() {
    view.releaseBorrowed();
  }

  boolean retainedInput() {
    return view.retainedInput();
  }

  boolean pendingWork() {
    return view.pendingWork();
  }

  boolean canSubmit() {
    return view.canSubmit();
  }

  /** Declare cached owner titles before activation; reference editing/lookup keeps its owner. */
  public SettingsScreen<T> withReferenceTitles(Map<DocumentId, ReferenceTitles> titles) {
    view.withReferenceTitles(titles);
    return this;
  }

  void focusAfterLoad(FieldPath path) {
    view.focusAfterLoad(path);
  }

  SettingsView<T> content() {
    return view;
  }

  public DocumentId document() {
    return view.document();
  }

  public DocumentActions<T> editingActions() {
    return view.editingActions();
  }

  public EditorActor.View<T> editorView() {
    return view.editorView();
  }

  public Optional<AbstractWidget> fieldControl(FieldPath path) {
    return view.fieldControl(path);
  }

  public Optional<FieldPath> selectedSection() {
    return view.selectedSection();
  }

  public boolean selectSection(FieldPath path) {
    return view.selectSection(path);
  }

  public boolean focusField(FieldPath path) {
    return view.focusField(path);
  }

  /** Displayed observation only; its revision identifies any earlier draft, and READ is current. */
  public Optional<design.inherent.reform.operation.FieldInformation> inspectedField() {
    return information.observation();
  }

  @Override
  protected void init() {
    suspendView();
    view.standalone();
    view.inlineInformation(null);
    informationPanel = null;
    int padding =
        Math.min(
            theme()
                .values()
                .value(design.inherent.reform.presentation.ThemeValues.Role.SPACE_SCREEN),
            Math.max(0, (Math.min(width, height) - 1) / 2));
    int available = Math.max(1, width - 2 * padding);
    if (!view.hasNavigation()) {
      navigationWidgets = null;
      drawerOpen = false;
      displayedSection = view.navigationTarget();
      var columns =
          information.open()
              ? FormGeometry.columns(available, 180, 180, Math.max(180, available * 2 / 3), 8)
              : Optional.<FormGeometry.Columns>empty();
      if (information.open() && columns.isEmpty()) view.inlineInformation(information);
      mount(
          view,
          new FormGeometry.Placement(
              padding,
              padding,
              columns.map(FormGeometry.Columns::firstWidth).orElse(available),
              Math.max(1, height - 2 * padding)));
      if (columns.isPresent()) {
        var at = columns.orElseThrow();
        mountInformation(
            new FormGeometry.Placement(
                padding + at.secondX(),
                padding,
                at.secondWidth(),
                Math.max(1, height - 2 * padding)));
      }
      return;
    }
    var metrics =
        new FormGeometry.WorkspaceMetrics(
            Math.max(
                theme()
                    .values()
                    .value(
                        design.inherent.reform.presentation.ThemeValues.Role
                            .MEASURE_NAVIGATION_MINIMUM),
                font.width(workspaceText("search", "Search settings")) + 20),
            theme()
                .values()
                .value(design.inherent.reform.presentation.ThemeValues.Role.MEASURE_EDITOR_MINIMUM),
            theme()
                .values()
                .value(
                    design.inherent.reform.presentation.ThemeValues.Role
                        .MEASURE_INFORMATION_MINIMUM),
            Math.max(112, Math.min(200, available / 4)),
            theme()
                .values()
                .value(
                    design.inherent.reform.presentation.ThemeValues.Role
                        .MEASURE_INFORMATION_PREFERRED),
            theme()
                .values()
                .value(design.inherent.reform.presentation.ThemeValues.Role.SPACE_CONTENT));
    int top = padding;
    int bottom = Math.max(top + 1, height - padding);
    var layout =
        FormGeometry.workspace(available, top, bottom, metrics, drawerOpen, information.open());
    if (layout.compactNavigation()) {
      top = Math.min(bottom - 1, padding + 26);
      layout =
          FormGeometry.workspace(available, top, bottom, metrics, drawerOpen, information.open());
      var toggle =
          theme()
              .buttonBuilder(
                  workspaceText("categories", "Categories"),
                  ignored -> {
                    drawerOpen = !drawerOpen;
                    rebuildWidgets();
                  })
              .bounds(padding, padding, Math.min(100, available), 20)
              .build();
      addRenderableWidget(toggle);
    }
    if (layout.navigationOverlay()) suspendView();
    else {
      if (information.open() && layout.information().isEmpty()) view.inlineInformation(information);
      if (returningBody != null) {
        view.restorePosition(returningBody);
        returningBody = null;
      }
      var bounds = layout.editor();
      mount(
          view,
          new FormGeometry.Placement(
              padding + bounds.x(), bounds.y(), bounds.width(), bounds.height()));
      if (layout.information().isPresent()) {
        var at = layout.information().orElseThrow();
        mountInformation(
            new FormGeometry.Placement(padding + at.x(), at.y(), at.width(), at.height()));
      }
    }
    navigationWidgets = null;
    displayedSection = view.navigationTarget();
    if (layout.navigation().isPresent()) {
      var bounds = layout.navigation().orElseThrow();
      var destination =
          new WorkspaceNavigation.Key(
              WorkspaceNavigation.Kind.DESTINATION, document(), Optional.empty());
      var selected = view.navigationTarget();
      var selectedKey =
          selected.equals(FieldPath.ROOT)
              ? destination
              : new WorkspaceNavigation.Key(
                  WorkspaceNavigation.Kind.FIELD, document(), Optional.of(selected));
      var entries = new ArrayList<WorkspaceNavigation.Entry>();
      entries.add(
          new WorkspaceNavigation.Entry(
              destination,
              Optional.empty(),
              title,
              Component.empty(),
              () -> navigate(FieldPath.ROOT)));
      for (var entry : view.navigationEntries()) {
        if (entry.path().equals(FieldPath.ROOT)) continue;
        var key =
            new WorkspaceNavigation.Key(
                WorkspaceNavigation.Kind.FIELD, document(), Optional.of(entry.path()));
        var parent =
            entry
                .parent()
                .filter(path -> !path.equals(FieldPath.ROOT))
                .map(
                    path ->
                        new WorkspaceNavigation.Key(
                            WorkspaceNavigation.Kind.FIELD, document(), Optional.of(path)))
                .orElse(destination);
        var help =
            entry
                .section()
                .map(
                    section ->
                        Component.translatableWithFallback(
                            section.translationKey() + ".help", section.help()))
                .orElse(Component.empty());
        entries.add(
            new WorkspaceNavigation.Entry(
                key,
                Optional.of(parent),
                Component.literal(entry.label()),
                help,
                () -> navigate(entry.path())));
      }
      var known =
          entries.stream()
              .map(WorkspaceNavigation.Entry::key)
              .collect(java.util.stream.Collectors.toSet());
      for (var field : view.searchableFields()) {
        var key =
            new WorkspaceNavigation.Key(
                WorkspaceNavigation.Kind.FIELD, document(), Optional.of(field.path()));
        if (!known.add(key)) continue;
        var parent =
            view.navigationParent(field.path())
                .filter(path -> !path.equals(FieldPath.ROOT))
                .map(
                    path ->
                        new WorkspaceNavigation.Key(
                            WorkspaceNavigation.Kind.FIELD, document(), Optional.of(path)))
                .filter(known::contains)
                .orElse(destination);
        entries.add(
            new WorkspaceNavigation.Entry(
                key,
                Optional.of(parent),
                Component.literal(field.label()),
                Component.literal(field.help()),
                () -> navigate(field.path()),
                true,
                field.search()));
      }
      navigationWidgets =
          navigation.build(
              minecraft,
              new FormGeometry.Placement(
                  padding + bounds.x(), bounds.y(), bounds.width(), bounds.height()),
              entries,
              selectedKey,
              view.navigationLimits(),
              view.searchProblem().map(List::of).orElse(List.of()));
      addRenderableWidget(navigationWidgets.search());
      addRenderableWidget(navigationWidgets.panel());
      for (var control : navigationWidgets.history()) addRenderableWidget(control);
      if (navigation.restoreSearchFocus()) setFocused(navigationWidgets.search());
      else if (navigation.restoreTreeFocus()) {
        setFocused(navigationWidgets.panel());
        navigation.restoreTree();
      }
    }
  }

  private void navigate(FieldPath field) {
    drawerOpen = false;
    returningBody =
        navigation.restoring() ? navigation.position(navigationKey(field)).orElse(null) : null;
    boolean returning = returningBody != null;
    view.deferFocus(field);
    rebuildWidgets();
    if (!returning) view.focusAfterLoad(field);
  }

  private WorkspaceNavigation.Key navigationKey(FieldPath field) {
    return field.equals(FieldPath.ROOT)
        ? new WorkspaceNavigation.Key(
            WorkspaceNavigation.Kind.DESTINATION, document(), Optional.empty())
        : new WorkspaceNavigation.Key(
            WorkspaceNavigation.Kind.FIELD, document(), Optional.of(field));
  }

  private void mountInformation(FormGeometry.Placement at) {
    informationPanel =
        new FormPanel(minecraft, at.x(), at.y(), at.width(), at.height(), false, theme());
    addRenderableWidget(informationPanel);
    information.dock(informationPanel, () -> setFocused(informationPanel));
  }

  @Override
  protected void showInformation(SettingsView<?> owner, FieldPath path, Component label) {
    information.show(owner, path, label);
  }

  @Override
  protected void beforeRebuild() {
    navigation.preserve(getFocused());
    information.preserve(getFocused());
    view.preserveStandalone();
    navigation.remember(navigationKey(displayedSection), view.navigationPosition());
  }

  @Override
  protected void beforeResize() {
    navigation.preserve(getFocused());
    information.preserve(getFocused());
    view.preserveStandalone();
    super.beforeResize();
  }

  @Override
  protected void setInitialFocus() {
    if (hasCurrentFocus()) return;
    if (navigationWidgets != null && navigation.restoreSearchFocus()) {
      setFocused(navigationWidgets.search());
      return;
    }
    if (navigationWidgets != null && navigation.restoreTreeFocus()) {
      setFocused(navigationWidgets.panel());
      navigation.restoreTree();
      return;
    }
    super.setInitialFocus();
  }

  @Override
  public boolean keyPressed(int key, int scan, int modifiers) {
    if (key == InputConstants.KEY_ESCAPE && navigation.clearFocusedFilter()) return true;
    if (key == InputConstants.KEY_ESCAPE && drawerOpen) {
      drawerOpen = false;
      rebuildWidgets();
      return true;
    }
    if (key == InputConstants.KEY_ESCAPE
        && information.open()
        && (getFocused() == informationPanel || informationPanel == null)) {
      information.close();
      return true;
    }
    if (key == InputConstants.KEY_F
        && hasControlDown()
        && navigation.focusSearch()
        && navigationWidgets != null) {
      setFocused(navigationWidgets.search());
      return true;
    }
    return super.keyPressed(key, scan, modifiers);
  }

  @Override
  public void tick() {
    super.tick();
    if (information.tick()) rebuildWidgets();
    if (!drawerOpen && !displayedSection.equals(view.navigationTarget())) rebuildWidgets();
  }

  @Override
  public void onClose() {
    if (mountedView() == null) {
      drawerOpen = false;
      rebuildWidgets();
    }
    super.onClose();
  }

  @Override
  public void removed() {
    if (!handingOff()) {
      information.clear();
      view.removed();
    }
  }

  @Override
  public void render(FormCanvas canvas, int x, int y, float delta) {
    if (information.guard()) rebuildWidgets();
    super.render(canvas, x, y, delta);
  }

  private static Component workspaceText(String key, String fallback) {
    return Component.translatableWithFallback("reform.workspace." + key, fallback);
  }
}
