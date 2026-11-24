package example.reform.platform;

import static example.reform.platform.PlatformWidgets.*;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import design.inherent.reform.definition.DocumentDefinition;
import design.inherent.reform.definition.DocumentId;
import design.inherent.reform.definition.FieldPath;
import design.inherent.reform.edit.DraftLimits;
import design.inherent.reform.edit.DraftNode;
import design.inherent.reform.minecraft.client.ClientChoiceScreen;
import design.inherent.reform.minecraft.client.ClientEditingWorkflow;
import design.inherent.reform.minecraft.client.ClientFileSettings;
import design.inherent.reform.minecraft.client.ClientRegistration;
import design.inherent.reform.minecraft.client.ClientWorkflowLayout;
import design.inherent.reform.minecraft.client.KeyBindingScreen;
import design.inherent.reform.minecraft.client.NativeScreens;
import design.inherent.reform.minecraft.client.SettingsScreen;
import design.inherent.reform.minecraft.client.UnsavedChangesScreen;
import design.inherent.reform.operation.OperationDescriptor.EntryPoint;
import design.inherent.reform.operation.OperationHandle;
import design.inherent.reform.owner.CommitReceipt;
import design.inherent.reform.owner.EditorActor;
import design.inherent.reform.serialization.JsonLimits;
import design.inherent.reform.storage.JsonStore;
import design.inherent.reform.storage.OwnedFile;
import design.inherent.reform.storage.StorageBinding;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** Real mixed-owner rows, aliases and auxiliary lifetimes in a single native workspace. */
final class PlatformWorkspaceChecks implements PlatformFormProbe.Scene {
  private static final String DEFAULTS_LABEL = "Restore declared example choices";
  private static final String DEFAULTS_HELP =
      "Stage this example's declared defaults without saving. "
          + "A field's Default choice removes only that field's override.";
  private static final String HELP_SUPPLEMENT = "Owner context survives help translation.";
  private static ClientFileSettings<PlatformWorkflowExample.Preferences> twin;
  private static PlatformWorkspaceChecks active;
  private static int informationCaptures;

  static void register(ClientRegistration registration) {
    var definition =
        new DocumentDefinition<>(
            id("workspace_twin"),
            1,
            design.inherent.reform.definition.RecordTypes.record(
                    PlatformWorkflowExample.Preferences.class)
                .field(
                    "enabled",
                    design.inherent.reform.definition.FieldKey.of(
                        "enabled",
                        design.inherent.reform.value.ValueTypes.defaultedField(
                            design.inherent.reform.value.ValueTypes.bool(), true)))
                .field(
                    "distance",
                    design.inherent.reform.definition.FieldKey.of(
                        "distance", design.inherent.reform.value.ValueTypes.integer(1, 512)))
                .field(
                    "label",
                    design.inherent.reform.definition.FieldKey.of(
                        "label", design.inherent.reform.value.ValueTypes.text(128)))
                .build(),
            () -> new PlatformWorkflowExample.Preferences(true, 64, "Example"));
    var json = new JsonLimits(262144, 128, 16384, 32768, 128, 4096);
    twin =
        ClientFileSettings.clientFile(
            registration,
            new JsonStore<>(
                definition,
                new OwnedFile(
                    StorageBinding.newJson(registration.configurationDirectory(), definition.id()),
                    json.maximumBytes()),
                JsonStore.Format.NEW_ENVELOPE,
                json,
                new DraftLimits(128, 16384, 64, 128, 1048576)));
  }

  static design.inherent.reform.minecraft.client.ClientForm navigationTwin() {
    return twin.form(Component.literal("Tick target preferences"));
  }

  static java.util.concurrent.CompletionStage<JsonObject> run(
      ClientRegistration registration, boolean reopen) {
    return PlatformFormProbe.run(
        registration,
        "WORKSPACE",
        driver -> {
          active = new PlatformWorkspaceChecks(reopen, driver::progress, driver::capture);
          return active;
        });
  }

  static JsonObject stopped(JsonObject prior) {
    var result = active.report();
    result.add("captures", prior.get("captures"));
    return result;
  }

  private static DocumentId id(String name) {
    return DocumentId.parse("reform_platform_example:" + name);
  }

  private final Minecraft client = Minecraft.getInstance();
  private ClientChoiceScreen entryChoice;
  private Screen entryParent;
  private Button retiredEntryChoice;
  private int choiceStage, choiceCalls;
  private boolean choiceBlocked = true, choicesQualified;

  private boolean entryChoices() {
    if (choiceStage == 0) {
      entryParent = NativeScreens.current(client);
      entryChoice =
          new ClientChoiceScreen(
              entryParent,
              Component.literal("Choose settings context"),
              Component.literal(
                  "Local files and running-server policies retain distinct owners and permissions."),
              List.of(
                  new ClientChoiceScreen.Choice(
                      "local",
                      Component.literal("Local files"),
                      Component.literal("Edit local preferences without changing server policy."),
                      Optional::empty,
                      () -> choiceCalls++),
                  new ClientChoiceScreen.Choice(
                      "server",
                      Component.literal("Running server"),
                      Component.literal("Use the actual current world's admission."),
                      () ->
                          choiceBlocked
                              ? Optional.of(Component.literal("No running server is available"))
                              : Optional.empty(),
                      () -> choiceCalls++)),
              Component.literal("Done"));
      NativeScreens.open(client, entryChoice);
      choiceStage++;
      return false;
    }
    if (choiceStage == 1) {
      geometry(entryChoice);
      var unavailable = button(entryChoice, "Running server");
      check(
          "entry choice retains owner blocked reason beside a disabled native control",
          !unavailable.active
              && String.join("\n", measuredText(entryChoice))
                  .contains("No running server is available")
              && NativeNarrationProbe.read(unavailable).contains("No running server is available"));
      NativeClientProbe.press(unavailable);
      check("blocked native choice callback rechecks owner admission", choiceCalls == 0);
      retiredEntryChoice = unavailable;
      choiceBlocked = false;
      choiceStage++;
      return false;
    }
    var admitted = button(entryChoice, "Running server");
    if (!admitted.active) return false;
    NativeClientProbe.press(retiredEntryChoice);
    check("entry reflow retires the previous native callback", choiceCalls == 0);
    check(
        "admitted entry action belongs to the actual native focus tree",
        focus(entryChoice, admitted));
    click(entryChoice, admitted);
    check("native entry choice invokes its owner once", choiceCalls == 1);
    geometry(entryChoice);
    click(entryChoice, button(entryChoice, "Done"));
    check("entry Done restores its actual parent", NativeScreens.current(client) == entryParent);
    NativeClientProbe.press(admitted);
    check("closed entry callbacks cannot replay", choiceCalls == 1);
    choicesQualified = true;
    return true;
  }

  private final Screen parent = NativeScreens.current(client);
  private final boolean reopen;
  private final Consumer<String> progress;
  private final BiConsumer<String, Runnable> capture;
  private final JsonArray checks = new JsonArray();
  private final JsonArray layoutMeasurements = new JsonArray();
  private final DocumentId preferences = PlatformWorkflowExample.DEFINITION.id();
  private ClientEditingWorkflow workflow, shutdown;
  private byte[] jsonBefore, twinBefore, nativeBefore;
  private byte[] actionSourceBefore;
  private int stage, guiScale, informationScale, defaultStage;
  private final int[] windowWidth = new int[1], windowHeight = new int[1];
  private boolean complete;
  private EditBox hiddenControl;
  private Screen closedRoot;
  private Button retiredDetails;
  private Button retiredDefaults;
  private int priorDistanceY;
  private int longLabelScale;
  private SettingsScreen<?> standalone;
  private OperationHandle<?> guardedSave;
  private design.inherent.reform.minecraft.client.NavigationAttempt guardedNavigation;
  private int guardedSubmissions;
  private int rootSelectionStage;
  private PlatformReferenceTitleChecks referenceTitleChecks;
  private PlatformShellChecks shellChecks;
  private PlatformTaskChecks taskChecks;
  private PlatformOperationPresentationChecks operationChecks;
  private PlatformFieldOrderChecks fieldOrderChecks;
  private PlatformThemeChecks themeChecks;
  private PlatformCustomViewChecks customViewChecks;
  private PlatformRootImportChecks rootImportChecks;
  private PlatformRootBatchChecks rootBatchChecks;
  private PlatformRootScopeChecks rootScopeChecks;
  private PlatformLoadedGraphicChecks loadedGraphicChecks;
  private PlatformLoadedSpriteChecks loadedSpriteChecks;
  private PlatformLoadedItemChecks loadedItemChecks;
  private PlatformLoadedBlockChecks loadedBlockChecks;
  private PlatformChestChecks chestChecks;
  private PlatformEntityChecks entityChecks;
  private PlatformRecipeLinkChecks recipeLinkChecks;
  private PlatformRootCloseChecks rootCloseChecks;
  private PlatformRootActionsChecks rootActionsChecks;
  private PlatformBorrowedPresentationChecks borrowedPresentationChecks;
  private PlatformRootInspectionChecks rootInspectionChecks;
  private PlatformRootConflictChecks rootConflictChecks;
  private List<design.inherent.reform.minecraft.client.ClientEditor<?>> abandonedEditors =
      List.of();

  PlatformWorkspaceChecks(
      boolean reopen, Consumer<String> progress, BiConsumer<String, Runnable> capture) {
    this.reopen = reopen;
    this.progress = progress;
    this.capture = capture;
  }

  @Override
  public int tickBudget() {
    // Native item defaults on26.3 additionally require a real owned world join/disconnect.
    // The bounded launcher wall deadline and all existing assertions remain separate.
    return NativeItemFixtureContext.requiresWorld() ? 1200 : 900;
  }

  public String diagnostic() {
    return "workspace stage="
        + stage
        + ", screen="
        + NativeScreens.current(client).getClass().getSimpleName()
        + (stage == 0 && loadedGraphicChecks != null ? "; " + loadedGraphicChecks.diagnostic() : "")
        + (stage == 0 && loadedSpriteChecks != null ? "; " + loadedSpriteChecks.diagnostic() : "")
        + (stage == 0 && loadedBlockChecks != null ? "; " + loadedBlockChecks.diagnostic() : "")
        + (stage == 0 && loadedItemChecks != null ? "; " + loadedItemChecks.diagnostic() : "")
        + (stage == 0 && chestChecks != null ? "; " + chestChecks.diagnostic() : "")
        + (stage == 0 && entityChecks != null ? "; " + entityChecks.diagnostic() : "")
        + (stage == 0 && recipeLinkChecks != null ? "; " + recipeLinkChecks.diagnostic() : "");
  }

  public JsonObject report() {
    var result = new JsonObject();
    result.addProperty("status", complete ? "PASS" : "RUNNING");
    result.add("checks", checks);
    result.add("layoutMeasurements", layoutMeasurements);
    result.add("inputCosts", inputCosts());
    result.addProperty("sourceKind", PlatformWorkflowSources.sourceKind());
    result.addProperty("workflowClosed", workflow != null && !workflow.isOpen());
    result.addProperty("shutdownClosed", shutdown != null && !shutdown.isOpen());
    if (referenceTitleChecks != null) result.add("referenceTitles", referenceTitleChecks.report());
    if (shellChecks != null) result.add("tickNavigation", shellChecks.tickNavigationReport());
    return result;
  }

  private void check(String label, boolean valid) {
    if (!valid) throw new AssertionError(label);
    checks.add(label);
  }

  private void next(String label) {
    stage++;
    progress.accept(label);
  }

  private String focusRoute(Screen screen) {
    var path = new StringBuilder(screen.getClass().getSimpleName());
    net.minecraft.client.gui.components.events.GuiEventListener current = screen;
    for (int depth = 0;
        depth < 8
            && current
                instanceof net.minecraft.client.gui.components.events.ContainerEventHandler parent;
        depth++) {
      current = parent.getFocused();
      if (current == null) {
        path.append(" -> none");
        break;
      }
      path.append(" -> ")
          .append(current.getClass().getSimpleName())
          .append(" focused=")
          .append(current.isFocused());
      if (current instanceof net.minecraft.client.gui.components.AbstractWidget widget)
        path.append(" active=")
            .append(widget.active)
            .append(" label=")
            .append(widget.getMessage().getString());
    }
    return path.toString();
  }

  private ClientWorkflowLayout.Placement placement(
      String name, DocumentId route, String label, Optional<FieldPath> path) {
    return new ClientWorkflowLayout.Placement(id(name), route, Component.literal(label), path)
        .inSection(
            new ClientWorkflowLayout.Section(
                id("content/" + name),
                Component.literal(label),
                Component.literal("These fields share one retained document.")));
  }

  private ClientEditingWorkflow open() {
    var forms = new ArrayList<>(PlatformWorkflowExample.forms());
    var options =
        new design.inherent.reform.minecraft.client.ClientFormOptions<
            PlatformWorkflowExample.Preferences>(
            design.inherent.reform.presentation.FormPresentation.EMPTY
                .withCatalog(
                    new design.inherent.reform.presentation.FormCatalog(
                        List.of(
                            new design.inherent.reform.presentation.FormCatalog.Section(
                                    FieldPath.ROOT.field("distance"),
                                    "workspace.purpose",
                                    "Distance",
                                    "Fallback purpose must not replace resources")
                                .withSupplementalHelp(HELP_SUPPLEMENT),
                            new design.inherent.reform.presentation.FormCatalog.Section(
                                FieldPath.ROOT.field("label"), "workspace.late", "Label", ""))))
                .withLayout(
                    new design.inherent.reform.presentation.FormLayout(
                        List.of(
                            new design.inherent.reform.presentation.FormLayout.Row(
                                "preferences",
                                List.of(
                                    FieldPath.ROOT.field("enabled"),
                                    FieldPath.ROOT.field("distance"),
                                    FieldPath.ROOT.field("label")))),
                        List.of())),
            java.util.Map.of());
    var label = Component.literal(DEFAULTS_LABEL);
    var help = Component.literal(DEFAULTS_HELP);
    var defaultsOptions = options.withDefaults(label, help);
    label.append(" changed by caller");
    help.append(" changed by caller");
    var declared = defaultsOptions.defaults().orElseThrow();
    ((net.minecraft.network.chat.MutableComponent) declared.label()).append(" changed by reader");
    ((net.minecraft.network.chat.MutableComponent) declared.help()).append(" changed by reader");
    check(
        "defaults presentation copies caller and returned Components",
        declared.label().getString().equals(DEFAULTS_LABEL)
            && declared.help().getString().equals(DEFAULTS_HELP));
    check("existing options retain built-in document defaults", options.defaults().isEmpty());
    forms.set(
        0,
        PlatformWorkflowExample.settings()
            .form(
                Component.literal("Client preferences"),
                defaultsOptions.withInformation(
                    definition ->
                        Optional.of(
                            design.inherent.reform.minecraft.client.FormInformation.of(
                                definition,
                                (observed, editor) -> {
                                  informationCaptures++;
                                  var authored =
                                      editor.snapshot().orElseThrow().draft().at(observed.path());
                                  return new design.inherent.reform.minecraft.client.FormInformation
                                          .Subject(
                                          Component.literal(
                                              "Controls how far this example plans the horizon."),
                                          List.of(
                                              new design.inherent.reform.minecraft.client
                                                  .FormInformation.Fact(
                                                  Component.literal("Physical scope"),
                                                  Component.literal("This client")),
                                              new design.inherent.reform.minecraft.client
                                                  .FormInformation.Fact(
                                                  Component.literal("Authored input"),
                                                  Component.literal(
                                                      authored instanceof DraftNode.Scalar scalar
                                                          ? scalar.raw()
                                                          : "Structured input"))),
                                          List.of(
                                              new design.inherent.reform.minecraft.client
                                                  .FormInformation.Related(
                                                  id("workspace_twin"),
                                                  FieldPath.ROOT.field("label"),
                                                  Component.literal("Independent label"))),
                                          Optional.of("distance"))
                                      .withTechnicalDetails(
                                          design.inherent.reform.minecraft.client.FormInformation
                                              .TechnicalDetails.DISCLOSURE);
                                })))));
    var twinForm = twin.form(Component.literal("Independent preferences"));
    forms.add(twinForm);
    var category =
        new ClientWorkflowLayout.Category(
            id("workspace_general"), Component.literal("General"), Component.empty());
    var details =
        new ClientWorkflowLayout.Category(
            id("workspace_details"), Component.literal("Details"), Component.empty());
    var placements = new ArrayList<ClientWorkflowLayout.Placement>();
    for (var form : forms)
      placements.add(
          placement(
              "page/" + placements.size(),
              form.route(),
              form.title().getString(),
              Optional.empty()));
    placements.add(
        placement(
            "distance_alias",
            preferences,
            "Distance alias",
            Optional.of(FieldPath.ROOT.field("distance"))));
    return ClientEditingWorkflow.open(
        parent,
        Component.literal("Mixed-owner settings"),
        forms,
        new ClientWorkflowLayout(
            List.of(
                new ClientWorkflowLayout.Group(List.of(category), placements, List.of()),
                new ClientWorkflowLayout.Group(
                    List.of(details),
                    List.of(
                        placement(
                            "twin_label",
                            twinForm.route(),
                            "Independent label",
                            Optional.of(FieldPath.ROOT.field("label")))),
                    List.of())),
            List.of()));
  }

  private boolean ready(DocumentId route) {
    var editor = workflow.editor(route);
    return editor.isOpen()
        && !editor.busy()
        && editor.view().availability() == EditorActor.Availability.READY
        && editor.view().snapshot().isPresent();
  }

  private boolean guardedCommit(DocumentId route) {
    var editor = workflow.editor(route);
    if (!editor.canSubmit()) return false;
    guardedSubmissions++;
    guardedSave = editor.actions().commit(EntryPoint.GUI);
    return true;
  }

  private boolean savedGuardedCommit() {
    var result = guardedSave.snapshot();
    if (!result.terminal()) return false;
    check("guarded native commit completed", result.result().isPresent());
    var commit = (EditorActor.Commit<?>) result.result().orElseThrow();
    check(
        "guarded native commit persisted through the actual owner",
        commit.receipt().isPresent()
            && commit.receipt().orElseThrow().persistence() == CommitReceipt.Persistence.SAVED);
    return true;
  }

  private EditBox control(DocumentId route, String field) {
    return (EditBox)
        workflow.editor(route).form().fieldControl(FieldPath.ROOT.field(field)).orElseThrow();
  }

  private boolean checkDefaultControl(DocumentId route) {
    var form = workflow.editor(route).form();
    var path = FieldPath.ROOT.field("enabled");
    var value = (DraftNode.Defaulted) form.editorView().snapshot().orElseThrow().draft().at(path);
    var control = form.fieldControl(path).orElseThrow();
    if (defaultStage < 4)
      check("defaulted boolean is keyboard-revealable in the shared panel", form.focusField(path));
    if (defaultStage == 0) {
      check(
          "defaulted boolean has one direct native choice",
          !value.overridden() && control.getMessage().getString().equals("Default (On)"));
      click(workflow.screen(), control);
      defaultStage++;
      return false;
    }
    if (defaultStage == 1) {
      if (!value.overridden() || !((DraftNode.Scalar) value.value()).raw().equals("true"))
        return false;
      check(
          "direct boolean choice writes the explicit authored value",
          control.getMessage().getString().equals("On"));
      click(workflow.screen(), control);
      defaultStage++;
      return false;
    }
    if (defaultStage == 2) {
      if (!value.overridden() || !((DraftNode.Scalar) value.value()).raw().equals("false"))
        return false;
      check(
          "direct boolean off remains an explicit authored value",
          control.getMessage().getString().equals("Off"));
      click(workflow.screen(), control);
      defaultStage++;
      return false;
    }
    if (defaultStage == 3) {
      if (value.overridden()) return false;
      check(
          "direct boolean default remains omitted in the retained actual owner",
          control.getMessage().getString().equals("Default (On)")
              && NativeScreens.current(client) == workflow.screen());
      defaultStage++;
    }
    return true;
  }

  private String raw(DocumentId route, String field) {
    return ((DraftNode.Scalar)
            workflow
                .editor(route)
                .view()
                .snapshot()
                .orElseThrow()
                .draft()
                .at(FieldPath.ROOT.field(field)))
        .raw();
  }

  private void edit(DocumentId route, String field, String value) {
    var view = workflow.editor(route).form();
    check(
        "existing owner can reveal " + route + "/" + field,
        view.focusField(FieldPath.ROOT.field(field)));
    replace(workflow.screen(), control(route, field), value);
  }

  private void press(String label) {
    if (label.equals("Settings")) {
      returnToSettings(workflow.screen());
      return;
    }
    var control = ownerAction(workflow.screen(), label);
    var host = actionHost(workflow.screen());
    check("requested workspace action is in the native focus tree", focus(host, control));
    click(host, control);
  }

  private byte[] source(String name) throws Exception {
    return Files.readAllBytes(
        client.gameDirectory.toPath().resolve("config/reform_platform_example/" + name + ".json"));
  }

  private String narration(Screen screen) {
    var host = actionHost(screen);
    return NativeClientProbe.narration(
        output -> {
          for (var child : host.children())
            if (child instanceof net.minecraft.client.gui.narration.NarratableEntry narrated)
              narrated.updateNarration(output.nest());
        });
  }

  private void navigationGeometry() {
    var measured = measuredPanels(workflow.screen());
    measured.addProperty("width", workflow.screen().width);
    measured.addProperty("height", workflow.screen().height);
    layoutMeasurements.add(measured);
    var rows =
        widgets(workflow.screen()).stream()
            .filter(widget -> widget.getClass().getSimpleName().equals("NavigationButton"))
            .sorted(java.util.Comparator.comparingInt(widget -> widget.getY()))
            .toList();
    check("Home and all three nested navigation rows remain mounted", rows.size() == 4);
    var titles =
        List.of(
            "Home",
            "Interface, accessibility and Einstellungen",
            "Compatibility settings with a long localized navigation name",
            "Independent preferences with a long translated name");
    for (int i = 0; i < rows.size(); i++) {
      var row = rows.get(i);
      check(
          "navigation labels retain content without layout spaces or disclosure glyphs",
          row.getMessage().getString().equals(titles.get(i)));
      if (i > 0 && i < 3)
        check(
            "expanded navigation branches retain native state narration",
            NativeNarrationProbe.read(row).contains("Expanded"));
      // The full widget width is a lower bound on the rendered text height: real padding
      // can only require more lines. This does not repeat the panel's sizing algorithm.
      int textHeight =
          client.font.split(row.getMessage(), Math.max(1, row.getWidth())).size()
              * client.font.lineHeight;
      check(
          "native navigation row contains its wrapped label: " + row.getMessage().getString(),
          textHeight <= row.getHeight());
      if (i > 0)
        check(
            "native navigation row rectangles do not overlap",
            rows.get(i - 1).getBottom() <= row.getY());
    }
  }

  public boolean tick() throws Exception {
    var twinId = id("workspace_twin");
    switch (stage) {
      case 0 -> {
        if (twin.runtime().isEmpty()
            || PlatformReferenceExample.settings().runtime().isEmpty()
            || PlatformWorkflowExample.settings().runtime().isEmpty()
            || PlatformWorkflowExample.keys().definition().isEmpty()
            || !PlatformWorkflowSources.ready()) return false;
        if (!choicesQualified && !entryChoices()) return false;
        if (recipeLinkChecks == null)
          recipeLinkChecks = new PlatformRecipeLinkChecks(this::check, capture);
        if (!recipeLinkChecks.tick()) return false;
        if (loadedGraphicChecks == null)
          loadedGraphicChecks = new PlatformLoadedGraphicChecks(this::check, capture);
        if (!loadedGraphicChecks.tick()) return false;
        if (loadedSpriteChecks == null)
          loadedSpriteChecks = new PlatformLoadedSpriteChecks(this::check, capture);
        if (!loadedSpriteChecks.tick()) return false;
        if (loadedBlockChecks == null)
          loadedBlockChecks = new PlatformLoadedBlockChecks(this::check, capture);
        if (!loadedBlockChecks.tick()) return false;
        if (chestChecks == null) chestChecks = new PlatformChestChecks(this::check, capture);
        if (!chestChecks.tick()) return false;
        if (loadedItemChecks == null)
          loadedItemChecks = new PlatformLoadedItemChecks(this::check, capture);
        if (!loadedItemChecks.tick()) return false;
        if (entityChecks == null) entityChecks = new PlatformEntityChecks(this::check, capture);
        if (!entityChecks.tick()) return false;
        if (customViewChecks == null) customViewChecks = new PlatformCustomViewChecks(this::check);
        if (!customViewChecks.tick()) return false;
        if (rootScopeChecks == null)
          rootScopeChecks = new PlatformRootScopeChecks(this::check, capture);
        if (!rootScopeChecks.tick()) return false;
        if (rootBatchChecks == null)
          rootBatchChecks = new PlatformRootBatchChecks(this::check, capture);
        if (!rootBatchChecks.tick()) return false;
        if (rootCloseChecks == null)
          rootCloseChecks = new PlatformRootCloseChecks(this::check, capture);
        if (!rootCloseChecks.tick()) return false;
        if (rootImportChecks == null)
          rootImportChecks = new PlatformRootImportChecks(this::check, capture);
        if (!rootImportChecks.tick()) return false;
        if (rootActionsChecks == null)
          rootActionsChecks = new PlatformRootActionsChecks(this::check, capture);
        if (!rootActionsChecks.tick()) return false;
        if (borrowedPresentationChecks == null)
          borrowedPresentationChecks = new PlatformBorrowedPresentationChecks(this::check);
        if (!borrowedPresentationChecks.tick()) return false;
        if (rootInspectionChecks == null)
          rootInspectionChecks = new PlatformRootInspectionChecks(this::check, capture);
        if (!rootInspectionChecks.tick()) return false;
        if (rootConflictChecks == null)
          rootConflictChecks = new PlatformRootConflictChecks(this::check, capture);
        if (!rootConflictChecks.tick()) return false;
        if (shellChecks == null) shellChecks = new PlatformShellChecks(this::check, capture);
        if (!shellChecks.tick()) return false;
        if (taskChecks == null) taskChecks = new PlatformTaskChecks(this::check, capture);
        if (!taskChecks.tick()) return false;
        if (operationChecks == null)
          operationChecks = new PlatformOperationPresentationChecks(this::check, capture);
        if (!operationChecks.tick()) return false;
        if (fieldOrderChecks == null)
          fieldOrderChecks = new PlatformFieldOrderChecks(this::check, capture);
        if (!fieldOrderChecks.tick()) return false;
        if (themeChecks == null) themeChecks = new PlatformThemeChecks(this::check, capture);
        if (!themeChecks.tick()) return false;
        jsonBefore = source("workflow_preferences");
        twinBefore = source("workspace_twin");
        nativeBefore = Files.readAllBytes(PlatformWorkflowSources.clientPath());
        workflow = open();
        check(
            "declaring a workspace does not open sources",
            workflow.documents().stream().noneMatch(ClientEditingWorkflow.DocumentState::opened));
        NativeScreens.open(client, workflow.screen());
        check(
            "default Home initially opens no mixed-page owners",
            workflow.documents().stream().noneMatch(ClientEditingWorkflow.DocumentState::opened));
        click(workflow.screen(), button(workflow.screen(), "General"));
        next("mixed page mounts actual owners");
      }
      case 1 -> {
        if (workflow.documents().stream().anyMatch(state -> !state.opened() || state.busy())
            || !ready(preferences)
            || !ready(twinId)) return false;
        if (rootSelectionStage == 0) {
          check(
              "initial owner has an actual focused field",
              workflow.editor(preferences).form().focusField(FieldPath.ROOT.field("distance")));
          workflow.show(twinId);
          rootSelectionStage++;
          return false;
        }
        if (rootSelectionStage == 1) {
          rootSelectionStage++;
          return false;
        }
        if (rootSelectionStage == 2) {
          check(
              "root selection retains the requested document action scope after native ticks",
              workflow
                  .screen()
                  .getNarrationMessage()
                  .getString()
                  .contains("Independent preferences:"));
          rootSelectionStage++;
        }
        if (!checkDefaultControl(twinId)) return false;
        var inactive =
            new design.inherent.reform.minecraft.client.ClientDestination.Field(
                twinId, FieldPath.ROOT.field("enabled").append(FieldPath.Part.VALUE));
        var inactiveDraft = workflow.editor(twinId).view().snapshot().orElseThrow();
        var inactiveAvailability = workflow.navigation().inspect(inactive);
        check(
            "retained inactive override is unavailable rather than missing",
            inactiveDraft.draft().contains(inactive.path())
                && !inactiveDraft.draft().activeAt(inactive.path())
                && inactiveAvailability.kind()
                    == design.inherent.reform.minecraft.client.DestinationAvailability.Kind
                        .UNAVAILABLE
                && inactiveAvailability.report().issues().stream()
                    .anyMatch(issue -> issue.code().equals("navigation.target_inactive")));
        check(
            "inactive link preserves the same retained draft",
            workflow.navigation().open(inactive).result().orElseThrow().outcome()
                    == design.inherent.reform.minecraft.client.NavigationAttempt.Outcome.UNAVAILABLE
                && workflow.editor(twinId).view().snapshot().orElseThrow() == inactiveDraft);
        check(
            "mixed page retains its native root",
            NativeScreens.current(client) == workflow.screen());
        var retained = workflow.editor(preferences);
        check(
            "retained editor exposes the actual workspace screen",
            retained.screen() == workflow.screen());
        var retainedForm = retained.form();
        NativeScreens.open(client, retained.screen());
        check(
            "opening the current native entry is idempotent and preserves its form",
            workflow.isOpen()
                && NativeScreens.current(client) == workflow.screen()
                && retained.form() == retainedForm);
        check("embedded form has no native screen ownership", !(retainedForm instanceof Screen));
        var search = input(workflow.screen(), "Search settings");
        replace(workflow.screen(), search, "client preferences distance");
        check(
            "search preserves its native text through rebuilds",
            input(workflow.screen(), "Search settings")
                .getValue()
                .equals("client preferences distance"));
        var fieldMatches =
            widgets(workflow.screen()).stream()
                .filter(Button.class::isInstance)
                .map(Button.class::cast)
                .filter(button -> button.getMessage().getString().strip().equals("Distance"))
                .toList();
        check("cached scalar search resolves the actual matching owner", fieldMatches.size() == 1);
        check(
            "field search result is in the native focus tree",
            focus(workflow.screen(), fieldMatches.getFirst()));
        click(workflow.screen(), fieldMatches.getFirst());
        check(
            "field search reveals the retained actual control without a second screen",
            control(preferences, "distance").isFocused()
                && retained.form() == retainedForm
                && NativeScreens.current(client) == workflow.screen());
        check(
            "revealing a search result preserves the query",
            input(workflow.screen(), "Search settings")
                .getValue()
                .equals("client preferences distance"));
        replace(workflow.screen(), input(workflow.screen(), "Search settings"), "");
        check("five distinct actual owners share one page", workflow.documents().size() == 5);
        var first = control(preferences, "distance");
        hiddenControl = first;
        var second = control(twinId, "distance");
        check(
            "identical field paths from separate owners keep distinct widgets",
            first != second
                && widgets(workflow.screen()).contains(first)
                && widgets(workflow.screen()).contains(second));
        check(
            "aliases mount the same setting only once",
            widgets(workflow.screen()).stream().filter(widget -> widget == first).count() == 1);
        geometry(workflow.screen());
        if (reopen) {
          check(
              "fresh JVM observes restored reference IDs",
              PlatformReferenceExample.settings()
                  .runtime()
                  .orElseThrow()
                  .view()
                  .value()
                  .orElseThrow()
                  .equals(
                      new PlatformReferenceExample.Profile(
                          "stone",
                          "minecraft:missing_resource",
                          "optional:missing",
                          "minecraft:manual")));
          check(
              "fresh JVM observes completed JSON save", raw(preferences, "distance").equals("128"));
          check(
              "fresh JVM does not observe discarded twin draft",
              raw(twinId, "label").equals("Example"));
          check(
              "fresh JVM observes native key save",
              PlatformWorkflowExample.PRIMARY.saveString().equals("key.keyboard.4"));
          check("fresh JVM observes defaults save", PlatformWorkflowSources.level() == 10);
          check(
              "fresh JVM does not observe discarded CLIENT draft",
              PlatformWorkflowSources.live() == 4);
          press("Done");
          check("clean workspace closes all actual owners", !workflow.isOpen());
          shutdown = open();
          shutdown.editor(preferences);
          complete = true;
          return true;
        }
        stage = 100;
        progress.accept("same-path owners and aliases qualified");
        capture.accept(
            "form-workspace-wide.png",
            () -> {
              var visibleHelp = String.join("\n", measuredText(workflow.screen()));
              check(
                  "translated field purpose retains separately owned context facts",
                  visibleHelp.contains("Localized purpose from resources")
                      && visibleHelp.contains(HELP_SUPPLEMENT)
                      && !visibleHelp.contains("Fallback purpose must not replace resources"));
              check(
                  "language resources resolve help with an empty construction-time fallback",
                  visibleHelp.contains("Purpose loaded after definition construction"));
              workflow.editor(preferences).form().focusField(FieldPath.ROOT.field("distance"));
              var field = control(preferences, "distance");
              var information =
                  java.util.Objects.requireNonNull(informationFor(workflow.screen(), field));
              check(
                  "information action belongs to the requested field row",
                  NativeNarrationProbe.read(information).contains("About Distance"));
              check(
                  "information button text is bold", information.getMessage().getStyle().isBold());
              check(
                  "information button selects the native Unicode font locally",
                  information
                      .getMessage()
                      .getStyle()
                      .getFont()
                      .toString()
                      .contains("minecraft:uniform"));
              click(workflow.screen(), information);
            });
      }
      case 100 -> {
        if (workflow.inspectedField().isEmpty()) return false;
        check(
            "guarded information keeps supplemental help alongside the field",
            measuredText(workflow.screen()).stream()
                    .filter(value -> value.contains(HELP_SUPPLEMENT))
                    .count()
                == 2);
        var information = workflow.inspectedField().orElseThrow();
        check(
            "plain information action keeps the native workspace and exact field: "
                + information.path(),
            NativeScreens.current(client) == workflow.screen()
                && information.path().equals(FieldPath.ROOT.field("distance")));
        check(
            "information captures baseline draft default and actual source identity",
            information.baseline().text().orElseThrow().equals("\"64\"")
                && information.draft().text().equals(information.baseline().text())
                && information.declaredDefault().text().equals(information.baseline().text())
                && information.target().document().equals(preferences));
        check("owner metadata is captured once outside rendering", informationCaptures == 1);
        informationScale = client.options.guiScale().get();
        client.options.guiScale().set(1);
        NativeClientProbe.reflow(client);
        stage = 101;
      }
      case 101 -> {
        if (workflow.screen().width < 600) return false;
        geometry(workflow.screen());
        check(
            "owner summary initially keeps raw values behind disclosure",
            widgets(workflow.screen()).stream()
                .noneMatch(widget -> widget.getMessage().getString().equals("Copy key")));
        retiredDetails = button(workflow.screen(), "Show technical details");
        check(
            "technical disclosure is keyboard-reachable", focus(workflow.screen(), retiredDetails));
        check(
            "native Enter expands technical disclosure",
            NativeClientProbe.key(actionHost(workflow.screen()), "key.keyboard.enter", true));
        NativeClientProbe.key(actionHost(workflow.screen()), "key.keyboard.enter", false);
        stage = 104;
      }
      case 104 -> {
        if (widgets(workflow.screen()).stream()
            .noneMatch(widget -> widget.getMessage().getString().equals("Copy key"))) return false;
        check(
            "expansion preserves the exact admitted observation without recapture",
            workflow.inspectedField().orElseThrow().path().equals(FieldPath.ROOT.field("distance"))
                && informationCaptures == 1);
        NativeClientProbe.pressRetired(retiredDetails);
        check(
            "retired disclosure callback cannot toggle rebuilt information",
            widgets(workflow.screen()).stream()
                .anyMatch(
                    widget -> widget.getMessage().getString().equals("Hide technical details")));
        press("Hide technical details");
        stage = 105;
      }
      case 105 -> {
        if (widgets(workflow.screen()).stream()
            .anyMatch(widget -> widget.getMessage().getString().equals("Copy key"))) return false;
        check("collapsing retains the admitted observation", workflow.inspectedField().isPresent());
        stage = 102;
        capture.accept(
            "form-workspace-information-wide.png",
            () -> {
              client.options.guiScale().set(4);
              NativeClientProbe.reflow(client);
            });
      }
      case 102 -> {
        if (workflow.screen().width >= 488) return false;
        var close = button(workflow.screen(), "Close information");
        var field = control(preferences, "distance");
        check(
            "compact information shares the real editing surface",
            workflow.screen().children().stream()
                .filter(
                    net.minecraft.client.gui.components.events.ContainerEventHandler.class
                        ::isInstance)
                .map(net.minecraft.client.gui.components.events.ContainerEventHandler.class::cast)
                .anyMatch(
                    parent -> widgets(parent).contains(close) && widgets(parent).contains(field)));
        var viewport =
            workflow.screen().children().stream()
                .filter(design.inherent.reform.minecraft.client.ScrollFrame.class::isInstance)
                .map(design.inherent.reform.minecraft.client.ScrollFrame.class::cast)
                .filter(widget -> widgets(widget).contains(close))
                .findFirst()
                .orElseThrow();
        check(
            "compact information is revealed without another navigation action",
            viewport.isMouseOver(close.getX() + 1, close.getY() + 1)
                && viewport.isMouseOver(close.getRight() - 1, close.getBottom() - 1)
                && close.isFocused());
        check(
            "information survives scale changes without rereading its owner",
            workflow.inspectedField().isPresent() && informationCaptures == 1);
        press("Show technical details");
        check(
            "inline disclosure keeps diagnostic key available",
            widgets(workflow.screen()).stream()
                .anyMatch(widget -> widget.getMessage().getString().equals("Copy key")));
        press("Hide technical details");
        geometry(workflow.screen());
        stage = 103;
        capture.accept(
            "form-workspace-information-inline.png",
            () -> {
              press("Close information");
              client.options.guiScale().set(informationScale);
              NativeClientProbe.reflow(client);
              check(
                  "closing information keeps all five owners",
                  workflow.inspectedField().isEmpty()
                      && workflow.documents().stream()
                          .allMatch(ClientEditingWorkflow.DocumentState::opened));
              stage = 106;
            });
      }
      case 103 -> {}
      case 106 -> {
        workflow.show(preferences);
        edit(preferences, "distance", "96");
        next("custom document defaults remain an owner action");
      }
      case 107 -> {
        if (!ready(preferences) || !raw(preferences, "distance").equals("96")) return false;
        retiredDefaults = ownerAction(workflow.screen(), DEFAULTS_LABEL);
        check(
            "composed defaults narrate owner help",
            narration(workflow.screen()).contains(DEFAULTS_HELP));
        stage = 112;
        capture.accept(
            "form-workspace-defaults.png",
            () -> {
              click(workflow.screen(), retiredDefaults);
              stage = 108;
              progress.accept("custom label invokes the existing document defaults operation");
            });
      }
      case 112 -> {}
      case 108 -> {
        if (!ready(preferences)
            || !raw(preferences, "distance").equals("64")
            || !workflow.editor(preferences).canSubmit()
            || !control(preferences, "distance").getValue().equals("64")) return false;
        var undo = ownerAction(workflow.screen(), "Undo");
        if (!undo.active) return false;
        check(
            "document defaults stage only the actual owner's declared choices",
            Arrays.equals(jsonBefore, source("workflow_preferences"))
                && raw(twinId, "distance").equals("64"));
        check(
            "native Enter activates the custom-defaults history action",
            NativeClientProbe.key(actionHost(workflow.screen()), "key.keyboard.enter", true));
        NativeClientProbe.key(actionHost(workflow.screen()), "key.keyboard.enter", false);
        next("custom defaults retain ordinary draft history");
      }
      case 109 -> {
        if (!ready(preferences) || !raw(preferences, "distance").equals("96")) return false;
        NativeClientProbe.pressRetired(retiredDefaults);
        next("retired custom defaults callback cannot repeat an edit");
      }
      case 110 -> {
        if (!ready(preferences)) return false;
        check(
            "retired defaults leave the newer draft intact",
            raw(preferences, "distance").equals("96"));
        press("Revert");
        next("restore original draft before navigation controls");
      }
      case 111 -> {
        if (!ready(preferences) || !raw(preferences, "distance").equals("64")) return false;
        check(
            "defaults and undo never save",
            Arrays.equals(jsonBefore, source("workflow_preferences")));
        press("Settings");
        hiddenControl = control(preferences, "distance");
        edit(preferences, "distance", "broken");
        stage = 2;
      }
      case 2 -> {
        if (!ready(preferences) || workflow.editor(preferences).view().validation().valid())
          return false;
        check(
            "invalid input belongs only to its actual owner",
            raw(preferences, "distance").equals("broken") && raw(twinId, "distance").equals("64"));
        check(
            "an admitted invalid actor draft refuses custom submission",
            !workflow.editor(preferences).canSubmit());
        var invalid = control(preferences, "distance");
        invalid.setCursorPosition(2);
        invalid.setHighlightPos(4);
        priorDistanceY = invalid.getY();
        workflow.showPlacement(id("twin_label"));
        check(
            "logical navigation stays in the same native screen",
            NativeScreens.current(client) == workflow.screen());
        check(
            "unselected owner fields are absent from the native focus tree",
            !widgets(workflow.screen()).contains(hiddenControl)
                && workflow
                    .editor(preferences)
                    .form()
                    .fieldControl(FieldPath.ROOT.field("distance"))
                    .isEmpty());
        edit(twinId, "label", "Other world");
        next("a hidden invalid draft survives another owner edit");
      }
      case 3 -> {
        if (!ready(twinId) || !raw(twinId, "label").equals("Other world")) return false;
        var retiredBack = button(workflow.screen(), "Back");
        check(
            "navigation Back is reachable by native keyboard focus",
            focus(workflow.screen(), retiredBack));
        check(
            "native Enter activates navigation Back",
            NativeClientProbe.key(actionHost(workflow.screen()), "key.keyboard.enter", true));
        NativeClientProbe.key(actionHost(workflow.screen()), "key.keyboard.enter", false);
        var returned = control(preferences, "distance");
        check(
            "Back restores the existing invalid field and its native caret selection",
            returned == hiddenControl
                && returned.isFocused()
                && returned.getValue().equals("broken")
                && returned.getCursorPosition() == 2
                && returned.getHighlighted().equals("ok"));
        check("Back restores the prior page viewport", returned.getY() == priorDistanceY);
        check(
            "Back keeps the native root and every owner alive",
            NativeScreens.current(client) == workflow.screen()
                && workflow.documents().stream()
                    .allMatch(ClientEditingWorkflow.DocumentState::opened));
        press("Forward");
        check(
            "Forward returns to the other retained draft and its focused field",
            control(twinId, "label").getValue().equals("Other world")
                && control(twinId, "label").isFocused()
                && workflow
                    .editor(preferences)
                    .form()
                    .fieldControl(FieldPath.ROOT.field("distance"))
                    .isEmpty());
        press("Back");
        var route = focusRoute(workflow.screen());
        check(
            "pointer Back restores the native text-input route: " + route,
            NativeClientProbe.character(workflow.screen(), 'X'));
        workflow.showPlacement(id("distance_alias"));
        check(
            "new navigation after Back replaces only the forward branch",
            !button(workflow.screen(), "Forward").active);
        NativeClientProbe.pressRetired(retiredBack);
        check(
            "retired history widgets cannot navigate the current page",
            workflow
                .editor(preferences)
                .form()
                .fieldControl(FieldPath.ROOT.field("distance"))
                .isPresent());
        check(
            "return uses retained invalid text and same actor",
            control(preferences, "distance").getValue().equals("brXen"));
        check(
            "hidden edit and invalid text do not write either file",
            Arrays.equals(jsonBefore, source("workflow_preferences"))
                && Arrays.equals(twinBefore, source("workspace_twin")));
        edit(preferences, "distance", "128");
        next("mixed-page input repair stays independent");
      }
      case 4 -> {
        if (!ready(preferences) || !raw(preferences, "distance").equals("128")) return false;
        workflow.show(PlatformWorkflowExample.KEYS);
        var path = FieldPath.ROOT.field(PlatformWorkflowExample.PRIMARY.getName());
        check(
            "real Controls field is mounted in the shared page",
            workflow.editor(PlatformWorkflowExample.KEYS).form().focusField(path));
        click(
            workflow.screen(),
            (Button)
                workflow
                    .editor(PlatformWorkflowExample.KEYS)
                    .form()
                    .fieldControl(path)
                    .orElseThrow());
        check(
            "native key editor remains an explicit auxiliary",
            NativeScreens.current(client) instanceof KeyBindingScreen);
        check(
            "auxiliary handoff retains every other owner",
            workflow.isOpen()
                && workflow.editor(preferences).isOpen()
                && workflow.editor(twinId).isOpen());
        var popup = (KeyBindingScreen) NativeScreens.current(client);
        click(popup, (Button) popup.control(KeyBindingScreen.Control.CAPTURE).orElseThrow());
        NativeClientProbe.key(popup, "key.keyboard.4", true);
        NativeClientProbe.key(popup, "key.keyboard.4", false);
        click(popup, (Button) popup.control(KeyBindingScreen.Control.APPLY).orElseThrow());
        next("auxiliary returns to the retained workspace");
      }
      case 5 -> {
        if (!ready(PlatformWorkflowExample.KEYS)
            || !workflow.editor(PlatformWorkflowExample.KEYS).dirty()) return false;
        check(
            "key auxiliary returns to the same root and all drafts",
            NativeScreens.current(client) == workflow.screen()
                && raw(preferences, "distance").equals("128")
                && raw(twinId, "label").equals("Other world"));
        workflow.show(preferences);
        press("Save and apply");
        next("one owner saves from the mixed page");
      }
      case 6 -> {
        if (!ready(preferences) || workflow.editor(preferences).dirty()) return false;
        check(
            "saving one owner retains unrelated drafts",
            workflow.editor(twinId).dirty()
                && workflow.editor(PlatformWorkflowExample.KEYS).dirty());
        check(
            "one-owner save does not persist the twin",
            Arrays.equals(twinBefore, source("workspace_twin")));
        workflow.show(PlatformWorkflowExample.KEYS);
        press("Save and apply");
        next("native Options save uses its actual writer");
      }
      case 7 -> {
        if (!ready(PlatformWorkflowExample.KEYS)
            || workflow.editor(PlatformWorkflowExample.KEYS).dirty()) return false;
        check(
            "native key application happened",
            PlatformWorkflowExample.PRIMARY.saveString().equals("key.keyboard.4"));
        workflow.show(PlatformWorkflowSources.defaults().route());
        edit(PlatformWorkflowSources.defaults().route(), "level", "10");
        next("registered defaults edit stays in the same page");
      }
      case 8 -> {
        if (!ready(PlatformWorkflowSources.defaults().route())
            || !raw(PlatformWorkflowSources.defaults().route(), "level").equals("10")) return false;
        press(PlatformWorkflowSources.saveLabel());
        next("registered defaults use declared source-specific effect");
      }
      case 9 -> {
        if (!ready(PlatformWorkflowSources.defaults().route())
            || workflow.editor(PlatformWorkflowSources.defaults().route()).dirty()) return false;
        check(
            "registered default writer was observed",
            PlatformWorkflowSources.level() == 10 && PlatformWorkflowSources.defaultSaveObserved());
        workflow.show(PlatformWorkflowSources.client().route());
        edit(PlatformWorkflowSources.client().route(), "live", "14");
        next("unsaved native CLIENT draft stays isolated");
      }
      case 10 -> {
        if (!ready(PlatformWorkflowSources.client().route())) return false;
        check(
            "CLIENT draft did not save",
            Arrays.equals(nativeBefore, Files.readAllBytes(PlatformWorkflowSources.clientPath())));
        NativeClientProbe.size(client, windowWidth, windowHeight);
        guiScale = client.options.guiScale().get();
        client.options.guiScale().set(3);
        NativeClientProbe.resize(client, 480, 360);
        NativeClientProbe.reflow(client);
        next("compact workspace reflows retained controls");
      }
      case 11 -> {
        if (workflow.screen().width > 340) return false;
        geometry(workflow.screen());
        check(
            "resize keeps invalid/twin/native draft identities",
            raw(twinId, "label").equals("Other world")
                && raw(PlatformWorkflowSources.client().route(), "live").equals("14"));
        next("compact workspace capture is native");
        capture.accept(
            "form-workspace-narrow.png",
            () -> {
              client.options.guiScale().set(guiScale);
              NativeClientProbe.resize(client, windowWidth[0], windowHeight[0]);
              NativeClientProbe.reflow(client);
              press("Done");
            });
      }
      case 12 -> {
        check(
            "dirty close requests a real decision",
            NativeScreens.current(client) == workflow.screen()
                && button(workflow.screen(), "Keep editing").active
                && button(workflow.screen(), "Discard draft").active);
        click(NativeScreens.current(client), button(NativeScreens.current(client), "Keep editing"));
        check(
            "keep editing retains root and owners",
            NativeScreens.current(client) == workflow.screen() && workflow.editor(twinId).isOpen());
        press("Done");
        click(
            NativeScreens.current(client), button(NativeScreens.current(client), "Discard draft"));
        check(
            "discard closes all owners without changing completed saves",
            !workflow.isOpen()
                && Arrays.equals(twinBefore, source("workspace_twin"))
                && Arrays.equals(
                    nativeBefore, Files.readAllBytes(PlatformWorkflowSources.clientPath())));
        check("workspace returns to its original parent", NativeScreens.current(client) == parent);
        shutdown = open();
        shutdown.editor(preferences);
        closedRoot = shutdown.screen();
        NativeScreens.open(client, closedRoot);
        shutdown.close();
        next("closed native root survives its final rendered frame");
      }
      case 13 -> {
        check(
            "closed workspace narration does not inspect retired sources",
            !shutdown.isOpen() && closedRoot.getNarrationMessage().equals(closedRoot.getTitle()));
        NativeScreens.open(client, parent);
        var catalog =
            new design.inherent.reform.presentation.FormCatalog(
                List.of(
                    new design.inherent.reform.presentation.FormCatalog.Section(
                        FieldPath.ROOT.field("distance"),
                        "workspace.distance",
                        "Distance",
                        "Standalone numeric input"),
                    new design.inherent.reform.presentation.FormCatalog.Section(
                        FieldPath.ROOT.field("label"),
                        "workspace.label",
                        "Label",
                        "Standalone text input")));
        NativeScreens.open(
            client,
            twin.screen(
                parent,
                Component.literal("Standalone navigation"),
                new design.inherent.reform.minecraft.client.ClientFormOptions<
                        PlatformWorkflowExample.Preferences>(
                        design.inherent.reform.presentation.FormPresentation.EMPTY
                            .withCatalog(catalog)
                            .withLayout(
                                new design.inherent.reform.presentation.FormLayout(
                                    List.of(),
                                    List.of(
                                        new design.inherent.reform.presentation.FormLayout.Group(
                                            "standalone_values",
                                            "fixture.standalone_values",
                                            "Standalone values",
                                            "Same retained source",
                                            List.of(
                                                FieldPath.ROOT.field("label"),
                                                FieldPath.ROOT.field("distance")))))),
                        java.util.Map.of())
                    .withDefaults(
                        Component.literal(DEFAULTS_LABEL), Component.literal(DEFAULTS_HELP))));
        next("standalone form uses the same navigation history");
      }
      case 14 -> {
        if (!(NativeScreens.current(client) instanceof SettingsScreen<?> ready)
            || ready.editorView().availability() != EditorActor.Availability.READY
            || ready.editorView().snapshot().isEmpty()
            || ready.fieldControl(FieldPath.ROOT.field("distance")).isEmpty()) return false;
        if (!button(ready, "Done").active || ready.editingActions().hasActiveOperations())
          return false;
        standalone = ready;
        var defaults = ownerAction(standalone, DEFAULTS_LABEL);
        check(
            "standalone defaults narrate owner help",
            narration(standalone).contains(DEFAULTS_HELP));
        boolean refused = false;
        try {
          standalone.withDefaults(Component.literal("Late replacement"), Component.empty());
        } catch (IllegalStateException expected) {
          refused = true;
        }
        check("active default-action presentation cannot be replaced", refused);
        returnToSettings(standalone);
        var sameActions = standalone.editingActions();
        click(standalone, button(standalone, "Standalone values"));
        check(
            "standalone typed layout target reveals the actual group without a new owner",
            standalone.focusTarget(
                    new design.inherent.reform.presentation.FormTarget.Section(
                        "group:standalone_values"))
                && standalone.editingActions() == sameActions);
        var ordered =
            widgets(standalone).stream()
                .filter(widget -> widget.getClass().getSimpleName().equals("NavigationButton"))
                .sorted(java.util.Comparator.comparingInt(widget -> widget.getY()))
                .map(widget -> widget.getMessage().getString())
                .toList();
        check(
            "standalone section hierarchy uses its rendered field order: " + ordered,
            ordered.equals(
                List.of("Standalone navigation", "Standalone values", "Label", "Distance")));
        check(
            "standalone grouped body uses the same label then distance order",
            standalone.fieldControl(FieldPath.ROOT.field("label")).orElseThrow().getY()
                < standalone.fieldControl(FieldPath.ROOT.field("distance")).orElseThrow().getY());
        check(
            "standalone group navigation leaves the source bytes untouched",
            Arrays.equals(twinBefore, source("workspace_twin")));
        var row =
            widgets(standalone).stream()
                .filter(Button.class::isInstance)
                .map(Button.class::cast)
                .filter(button -> button.getMessage().getString().strip().equals("Distance"))
                .findFirst()
                .orElse(null);
        if (row == null) return false;
        check("standalone tree destination is keyboard-reachable", focus(standalone, row));
        click(standalone, row);
        var field =
            (EditBox) standalone.fieldControl(FieldPath.ROOT.field("distance")).orElseThrow();
        var route = focusRoute(standalone);
        check(
            "standalone direct navigation restores native typing: " + route,
            NativeClientProbe.character(standalone, 'b'));
        replace(standalone, field, "broken");
        next("standalone invalid field is retained");
      }
      case 15 -> {
        if (standalone.editorView().validation().valid()
            || standalone.editingActions().hasActiveOperations()) return false;
        var field =
            (EditBox) standalone.fieldControl(FieldPath.ROOT.field("distance")).orElseThrow();
        field.setCursorPosition(2);
        field.setHighlightPos(4);
        priorDistanceY = field.getY();
        var row =
            widgets(standalone).stream()
                .filter(Button.class::isInstance)
                .map(Button.class::cast)
                .filter(button -> button.getMessage().getString().strip().equals("Label"))
                .findFirst()
                .orElseThrow();
        check("standalone second destination is keyboard-reachable", focus(standalone, row));
        var retiredField = field;
        click(standalone, row);
        check(
            "standalone navigation retires the previous section's mounted input",
            standalone.fieldControl(FieldPath.ROOT.field("distance")).isEmpty());
        click(standalone, button(standalone, "Back"));
        field = (EditBox) standalone.fieldControl(FieldPath.ROOT.field("distance")).orElseThrow();
        check(
            "standalone return creates a fresh input over retained editing state",
            field != retiredField);
        check(
            "standalone Back restores caret selection and viewport: cursor="
                + field.getCursorPosition()
                + ", selection="
                + field.getHighlighted()
                + ", y="
                + field.getY()
                + "/"
                + priorDistanceY
                + ", "
                + focusRoute(standalone),
            field.getValue().equals("broken")
                && field.getCursorPosition() == 2
                && field.getHighlighted().equals("ok")
                && field.isFocused()
                && field.getY() == priorDistanceY);
        check(
            "standalone pointer Back restores native typing",
            NativeClientProbe.character(standalone, 'X'));
        check(
            "standalone native typing uses the restored selection",
            field.getValue().equals("brXen"));
        click(standalone, button(standalone, "Forward"));
        check(
            "standalone Forward reveals its retained field",
            standalone.fieldControl(FieldPath.ROOT.field("label")).orElseThrow().isFocused());
        next("standalone dirty close keeps its own source policy");
      }
      case 16 -> {
        if (!button(standalone, "Done").active || standalone.editingActions().hasActiveOperations())
          return false;
        standalone.onClose();
        if (!(NativeScreens.current(client) instanceof UnsavedChangesScreen)) return false;
        var decision = NativeScreens.current(client);
        click(decision, button(decision, "Discard draft"));
        check(
            "standalone history never saves its discarded draft",
            NativeScreens.current(client) == parent
                && Arrays.equals(twinBefore, source("workspace_twin")));
        workflow =
            ClientEditingWorkflow.open(
                parent,
                Component.literal("Single-owner workflow"),
                List.of(twin.form(Component.literal("Independent preferences"))));
        NativeScreens.open(client, workflow.screen());
        workflow.show(twinId);
        next("default workflow retains field history without a custom layout");
      }
      case 17 -> {
        if (!ready(twinId)
            || workflow
                .editor(twinId)
                .form()
                .fieldControl(FieldPath.ROOT.field("distance"))
                .isEmpty()
            || !button(workflow.screen(), "Done").active) return false;
        var defaults = ownerAction(workflow.screen(), "Defaults");
        check(
            "unmodified callers keep built-in Defaults without extra help",
            !narration(workflow.screen()).contains(DEFAULTS_HELP));
        press("Settings");
        replace(workflow.screen(), input(workflow.screen(), "Search settings"), "Distance");
        var row =
            widgets(workflow.screen()).stream()
                .filter(Button.class::isInstance)
                .map(Button.class::cast)
                .filter(button -> button.getMessage().getString().strip().equals("Distance"))
                .findFirst()
                .orElse(null);
        if (row == null) return false;
        check("default workflow field search is keyboard-reachable", focus(workflow.screen(), row));
        click(workflow.screen(), row);
        check(
            "default workflow records the requested field identity",
            button(workflow.screen(), "Back").active);
        press("Back");
        check(
            "default workflow Back retains its forward branch",
            button(workflow.screen(), "Forward").active);
        press("Forward");
        check(
            "default workflow Forward returns to its actual field",
            control(twinId, "distance").isFocused()
                && workflow
                    .editor(twinId)
                    .form()
                    .selectedSection()
                    .orElseThrow()
                    .equals(FieldPath.ROOT));
        check(
            "default workflow history restores its native key-input route",
            NativeClientProbe.key(actionHost(workflow.screen()), "key.keyboard.left", true));
        NativeClientProbe.key(actionHost(workflow.screen()), "key.keyboard.left", false);
        workflow.screen().onClose();
        check(
            "default workflow history does not write its source",
            !workflow.isOpen()
                && NativeScreens.current(client) == parent
                && Arrays.equals(twinBefore, source("workspace_twin")));
        var form = twin.form(Component.literal("Independent preferences"));
        var root =
            new ClientWorkflowLayout.Category(
                id("long_root"),
                Component.literal("Interface, accessibility and Einstellungen"),
                Component.empty());
        var group =
            new ClientWorkflowLayout.Category(
                id("long_group"),
                Component.literal("Compatibility settings with a long localized navigation name"),
                Component.empty());
        workflow =
            ClientEditingWorkflow.open(
                parent,
                Component.literal("Wrapped native navigation"),
                List.of(form),
                new ClientWorkflowLayout(
                    List.of(
                        new ClientWorkflowLayout.Group(
                            List.of(root, group),
                            List.of(
                                placement(
                                    "long_child",
                                    form.route(),
                                    "Independent preferences with a long translated name",
                                    Optional.empty())),
                            List.of())),
                    List.of()));
        NativeScreens.open(client, workflow.screen());
        workflow.showPlacement(id("long_child"));
        longLabelScale = client.options.guiScale().get();
        client.options.guiScale().set(4);
        NativeClientProbe.reflow(client);
        stage = 200;
      }
      case 200 -> {
        if (!ready(twinId) || workflow.screen().width > 427) return false;
        stage = 201;
        capture.accept("form-workspace-long-navigation.png", () -> stage = 202);
      }
      case 201 -> {}
      case 202 -> {
        navigationGeometry();
        client.options.guiScale().set(longLabelScale);
        NativeClientProbe.reflow(client);
        stage = 203;
      }
      case 203 -> {
        if (!ready(twinId) || workflow.screen().width <= 427) return false;
        navigationGeometry();
        workflow.screen().onClose();
        check(
            "long navigation reflow never changes its owner's source or lifetime",
            !workflow.isOpen()
                && NativeScreens.current(client) == parent
                && Arrays.equals(twinBefore, source("workspace_twin")));
        workflow =
            ClientEditingWorkflow.open(
                parent,
                Component.literal("Guarded submission"),
                List.of(twin.form(Component.literal("Bounded preferences"))));
        NativeScreens.open(client, workflow.screen());
        guardedNavigation =
            workflow
                .navigation()
                .open(
                    new design.inherent.reform.minecraft.client.ClientDestination.Document(twinId));
        stage = 210;
      }
      case 210 -> {
        if (guardedNavigation.result().isEmpty()) return false;
        check(
            "custom submission waits for actual navigation mount",
            guardedNavigation.result().orElseThrow().outcome()
                == design.inherent.reform.minecraft.client.NavigationAttempt.Outcome.REVEALED);
        if (!ready(twinId)) return false;
        check(
            "a settled valid native form admits a custom submission",
            workflow.editor(twinId).canSubmit());
        edit(twinId, "distance", "80");
        next("stage a valid sibling before rejected native text");
      }
      case 211 -> {
        if (!ready(twinId) || !raw(twinId, "distance").equals("80")) return false;
        check(
            "the valid sibling is dirty and submittable",
            workflow.editor(twinId).dirty() && workflow.editor(twinId).canSubmit());
        edit(twinId, "label", "x".repeat(96));
        next("native text exceeds the owner's complete draft budget");
      }
      case 212 -> {
        if (!ready(twinId)) return false;
        var editor = workflow.editor(twinId);
        var admittedLabel = raw(twinId, "label");
        // Native character events can admit a valid prefix before the complete input is refused.
        // Neither that prefix nor a valid sibling may stand in for the retained native text.
        check(
            "rejected native text remains outside the valid dirty actor",
            editor.view().validation().valid()
                && editor.view().dirty()
                && raw(twinId, "distance").equals("80")
                && admittedLabel.length() < 96
                && admittedLabel.chars().allMatch(value -> value == 'x')
                && control(twinId, "label").getValue().equals("x".repeat(96)));
        check(
            "the native-input guard prevents partial sibling submission",
            !editor.canSubmit() && !guardedCommit(twinId) && guardedSubmissions == 0);
        check(
            "refused custom submission preserves source and effective values",
            Arrays.equals(twinBefore, source("workspace_twin"))
                && twin.runtime().orElseThrow().view().value().orElseThrow().distance() == 64);
        edit(twinId, "label", "Repaired");
        next("repair the retained native input explicitly");
      }
      case 213 -> {
        if (!ready(twinId) || !raw(twinId, "label").equals("Repaired")) return false;
        check(
            "repair reopens the guard without writing",
            workflow.editor(twinId).canSubmit()
                && Arrays.equals(twinBefore, source("workspace_twin")));
        check("a guarded custom action submits the actual complete draft", guardedCommit(twinId));
        next("await the actual owner persistence receipt");
      }
      case 214 -> {
        if (!savedGuardedCommit() || !ready(twinId)) return false;
        check(
            "guarded submission saves both intended native fields",
            twin.runtime().orElseThrow().view().value().orElseThrow().distance() == 80
                && twin.runtime()
                    .orElseThrow()
                    .view()
                    .value()
                    .orElseThrow()
                    .label()
                    .equals("Repaired")
                && !workflow.editor(twinId).dirty());
        edit(twinId, "distance", "64");
        edit(twinId, "label", "Example");
        next("restore the fixture through the same native controls");
      }
      case 215 -> {
        if (!ready(twinId)
            || !raw(twinId, "distance").equals("64")
            || !raw(twinId, "label").equals("Example")) return false;
        check("restoring uses the same guarded owner submission", guardedCommit(twinId));
        next("await native restoration without direct file writes");
      }
      case 216 -> {
        if (!savedGuardedCommit() || !ready(twinId)) return false;
        var closed = workflow.editor(twinId);
        workflow.screen().onClose();
        check("a closed editor never admits custom submission", !closed.canSubmit());
        check(
            "guard controls restore exact saved bytes",
            Arrays.equals(twinBefore, source("workspace_twin")));
        referenceTitleChecks = new PlatformReferenceTitleChecks(progress, capture);
        stage = 220;
      }
      case 220 -> {
        if (!referenceTitleChecks.tick()) return false;
        checks.addAll(referenceTitleChecks.report().getAsJsonArray("checks"));
        workflow = open();
        NativeScreens.open(client, workflow.screen());
        click(workflow.screen(), button(workflow.screen(), "General"));
        stage = 221;
      }
      case 221 -> {
        if (workflow.documents().stream().anyMatch(state -> !state.opened() || state.busy())
            || !ready(preferences)) return false;
        actionSourceBefore = source("workflow_preferences");
        edit(preferences, "distance", "129");
        stage = 222;
      }
      case 222 -> {
        if (!ready(preferences)
            || !raw(preferences, "distance").equals("129")
            || !workflow.editor(preferences).canSubmit()) return false;
        press("Document actions…");
        var menu = actionHost(workflow.screen());
        check(
            "document actions retain every actual owner while open",
            menu == workflow.screen()
                && NativeScreens.current(client) == workflow.screen()
                && workflow.screen().getNarrationMessage().getString().contains("Document actions")
                && workflow.isOpen()
                && workflow.documents().stream()
                    .allMatch(ClientEditingWorkflow.DocumentState::opened));
        abandonedEditors =
            workflow.documents().stream()
                .<design.inherent.reform.minecraft.client.ClientEditor<?>>map(
                    state -> workflow.editor(state.document()))
                .toList();
        var retired = button(menu, DEFAULTS_LABEL);
        geometry(menu);
        stage = 224;
        capture.accept(
            "form-workspace-actions-lifetime.png",
            () -> {
              NativeScreens.open(client, parent);
              NativeClientProbe.pressRetired(retired);
              stage = 223;
            });
      }
      case 224 -> {}
      case 223 -> {
        check(
            "unexpected document-action departure closes the whole editing lifetime",
            !workflow.isOpen()
                && abandonedEditors.stream()
                    .noneMatch(design.inherent.reform.minecraft.client.ClientEditor::isOpen));
        check(
            "retired action cannot write or default after abandonment",
            Arrays.equals(actionSourceBefore, source("workflow_preferences")));
        shutdown = open();
        shutdown.editor(preferences);
        complete = true;
        return true;
      }
      default -> throw new AssertionError("Unknown workspace stage " + stage);
    }
    return false;
  }
}
