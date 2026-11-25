package example.reform.platform;

import static example.reform.platform.PlatformWidgets.*;

import design.inherent.reform.definition.DocumentId;
import design.inherent.reform.minecraft.client.*;
import design.inherent.reform.owner.EditorActor;
import design.inherent.reform.owner.PolicyRefusal;
import design.inherent.reform.presentation.DestinationId;
import design.inherent.reform.presentation.FormGeometry;
import design.inherent.reform.presentation.FormPresentation;
import design.inherent.reform.presentation.FormTheme;
import design.inherent.reform.presentation.ThemePatch;
import design.inherent.reform.presentation.ThemeValues;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.function.BiConsumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** Native witness for lazy Home, stable header offers and one owned canvas lifecycle. */
final class PlatformShellChecks implements AutoCloseable {
  private final Minecraft client = Minecraft.getInstance();
  private final Screen parent = NativeScreens.current(client);
  private final BiConsumer<String, Boolean> check;
  private final BiConsumer<String, Runnable> capture;
  private ClientEditingWorkflow workflow;
  private int stage, creates, closes, draws;
  private int resourceCleanups, lateCleanups;
  private final java.util.ArrayList<String> cleanupOrder = new java.util.ArrayList<>();
  private ClientViewContext lastScope;
  private final IllegalStateException factoryFailure =
      new IllegalStateException("Intentional header factory refusal");
  private final IllegalStateException cleanupFailure =
      new IllegalStateException("Intentional partial cleanup failure");
  private FormGeometry.Placement lastCanvas, homeCanvas;
  private byte[] source;
  private ClientEditor<?> opened;
  private PlatformNavigationChecks navigationChecks;
  private PlatformNavigationTickChecks tickChecks;

  private ClientWorkflowLayout maximumHierarchy(DocumentId route) {
    var groups = new java.util.ArrayList<ClientWorkflowLayout.Group>();
    for (int group = 0; group < ClientWorkflowLayout.MAXIMUM_GROUPS; group++) {
      var path = new java.util.ArrayList<ClientWorkflowLayout.Category>();
      for (int depth = 0; depth < 4; depth++)
        path.add(
            new ClientWorkflowLayout.Category(
                DocumentId.parse("reform_platform_example:limit_category/" + group + "/" + depth),
                Component.literal("Limit category " + group + "." + depth),
                Component.empty()));
      var placements = new java.util.ArrayList<ClientWorkflowLayout.Placement>();
      // The first page deliberately admits no owner, so catalogue capacity cannot mask the
      // static category-prefix deficit. The other 127 pages retain one real declared owner.
      if (group != 0)
        for (int placement = 0; placement < 4; placement++)
          placements.add(
              new ClientWorkflowLayout.Placement(
                  DocumentId.parse(
                      "reform_platform_example:limit_placement/" + group + "/" + placement),
                  route,
                  Component.literal("Limit placement " + group + "." + placement),
                  Optional.empty()));
      groups.add(new ClientWorkflowLayout.Group(path, placements, List.of()));
    }
    return new ClientWorkflowLayout(groups, List.of());
  }

  PlatformShellChecks(BiConsumer<String, Boolean> check, BiConsumer<String, Runnable> capture) {
    this.check = check;
    this.capture = capture;
  }

  private java.nio.file.Path sourcePath() {
    return client
        .gameDirectory
        .toPath()
        .resolve("config/reform_platform_example/workflow_preferences.json");
  }

  private void checkLocationNarration(String offer) {
    var locations =
        widgets(workflow.screen()).stream()
            .map(widget -> NativeClientProbe.narration(widget::updateNarration))
            .filter(
                message ->
                    message.startsWith("Location: ") || message.startsWith("Current location: "))
            .toList();
    check.accept(
        "actual " + offer + " location controls narrate the full path exactly once: " + locations,
        !locations.isEmpty()
            && locations.stream()
                .allMatch(
                    message ->
                        message.contains("Home / ")
                            && message.indexOf("Home / ") == message.lastIndexOf("Home / ")));
  }

  private ClientWorkspacePresentation presentation(boolean fail) {
    var header =
        new HeaderComposition(
            List.of(
                new HeaderComposition.Row(
                    List.of(HeaderComposition.Slot.IDENTITY, HeaderComposition.Slot.LOCATION)),
                new HeaderComposition.Row(
                    List.of(HeaderComposition.Slot.SUBTITLE, HeaderComposition.Slot.CANVAS))),
            Optional.of(
                new HeaderComposition.Canvas(
                    Component.literal("Cached header illustration"),
                    80,
                    160,
                    36,
                    scope -> {
                      creates++;
                      lastScope = scope;
                      scope.requireCurrent();
                      scope.onClose(
                          () -> {
                            scope.checkNativeThread();
                            cleanupOrder.add("resource");
                            resourceCleanups++;
                            if (fail) throw cleanupFailure;
                          });
                      if (fail) {
                        scope.onClose(() -> lateCleanups++);
                        throw factoryFailure;
                      }
                      return new ClientHeaderCanvas() {
                        @Override
                        public void render(
                            FormCanvas canvas,
                            Font font,
                            FormGeometry.Placement bounds,
                            design.inherent.reform.presentation.ThemeValues theme) {
                          draws++;
                          lastCanvas = bounds;
                          canvas.fill(
                              bounds.x(),
                              bounds.y(),
                              bounds.x() + bounds.width(),
                              bounds.y() + bounds.height(),
                              0xff203040);
                        }

                        @Override
                        public void close() {
                          scope.checkNativeThread();
                          cleanupOrder.add("canvas");
                          closes++;
                        }
                      };
                    })));
    return ClientWorkspacePresentation.standard(
            new WorkspaceIdentity(
                DocumentId.parse("reform_platform_example:shell"),
                Component.literal("Stable settings workspace"),
                Component.literal("Declared identity stays in place")))
        .withHeader(header)
        .withTheme(
            ThemePatch.empty()
                .set(ThemeValues.Role.SPACE_SCREEN, 16)
                .set(ThemeValues.Role.SPACE_FIELD, 18)
                .set(ThemeValues.Role.CONTROL_HEIGHT, 24)
                .set(ThemeValues.Role.TEXT, 0xffd0ffe8)
                .set(ThemeValues.Role.TEXT_SUPPORTING, 0xffb8c8dc))
        .withTextStyle(
            ClientTheme.TextRole.TITLE, net.minecraft.network.chat.Style.EMPTY.withBold(true));
  }

  boolean tick() throws Exception {
    switch (stage) {
      case 0 -> {
        source = Files.readAllBytes(sourcePath());
        var forms = new java.util.ArrayList<>(PlatformWorkflowExample.forms());
        var themed =
            FormPresentation.EMPTY
                .withLayout(
                    new design.inherent.reform.presentation.FormLayout(
                        List.of(),
                        List.of(
                            new design.inherent.reform.presentation.FormLayout.Group(
                                "appearance",
                                "fixture.shell.appearance",
                                "Appearance",
                                "Display choices",
                                List.of(
                                    design.inherent.reform.definition.FieldPath.ROOT.field(
                                        "enabled"),
                                    design.inherent.reform.definition.FieldPath.ROOT.field(
                                        "label"))),
                            new design.inherent.reform.presentation.FormLayout.Group(
                                "distance",
                                "fixture.shell.distance",
                                "Distance tuning",
                                "Distance choices",
                                List.of(
                                    design.inherent.reform.definition.FieldPath.ROOT.field(
                                        "distance"))))))
                .withCatalog(
                    new design.inherent.reform.presentation.FormCatalog(
                        List.of("enabled", "distance", "label").stream()
                            .map(
                                id ->
                                    new design.inherent.reform.presentation.FormCatalog.Section(
                                        design.inherent.reform.definition.FieldPath.ROOT.field(id),
                                        "fixture.shell." + id,
                                        id.substring(0, 1).toUpperCase(java.util.Locale.ROOT)
                                            + id.substring(1),
                                        ""))
                            .toList()))
                .withTheme(
                    FormTheme.EMPTY
                        .withRoot(ThemePatch.empty().set(ThemeValues.Role.SPACE_FIELD, 14))
                        .withField(
                            design.inherent.reform.definition.FieldPath.ROOT.field("distance"),
                            ThemePatch.empty().set(ThemeValues.Role.CONTROL_NUMERIC_INPUT, 140)));
        forms.set(
            0,
            PlatformWorkflowExample.settings()
                .form(
                    Component.literal("Client preferences"),
                    new ClientFormOptions<>(themed, java.util.Map.of())));
        var metadata = presentation(false);
        check.accept(
            "shell metadata validates without entering a canvas or source",
            metadata.validate(forms, null).valid() && creates == 0);
        var missing =
            metadata.withInitialDestination(DestinationId.parse("reform_platform_example:missing"));
        check.accept(
            "missing initial destination has structured declaration issues",
            !missing.validate(forms, null).valid());
        try {
          ClientEditingWorkflow.open(parent, forms, null, missing);
          throw new AssertionError("Missing shell target was admitted");
        } catch (PolicyRefusal expected) {
          check.accept("invalid shell refuses before canvas or source entry", creates == 0);
        }
        workflow = ClientEditingWorkflow.open(parent, forms, null, metadata);
        check.accept(
            "constructing a shell does not enter owner or artwork factories",
            creates == 0
                && workflow.documents().stream()
                    .noneMatch(ClientEditingWorkflow.DocumentState::opened));
        NativeScreens.open(client, workflow.screen());
        stage++;
      }
      case 1 -> {
        if (draws == 0) return false;
        check.accept(
            "actual Home mounts one cached artwork and no source owners",
            creates == 1
                && workflow.documents().stream()
                    .noneMatch(ClientEditingWorkflow.DocumentState::opened));
        check.accept(
            "native shell retains an explicitly resolved immutable theme",
            ((NativeFormScreen) workflow.screen())
                        .theme()
                        .values()
                        .value(ThemeValues.Role.SPACE_SCREEN)
                    == 16
                && ((NativeFormScreen) workflow.screen())
                        .theme()
                        .values()
                        .value(ThemeValues.Role.TEXT)
                    == 0xffd0ffe8
                && ((NativeFormScreen) workflow.screen())
                    .theme()
                    .text(ClientTheme.TextRole.TITLE, Component.literal("Measured title"))
                    .getStyle()
                    .isBold());
        check.accept(
            "native Home button uses the declared control height and role foreground",
            button(workflow.screen(), "Client preferences").getHeight() == 24);
        check.accept(
            "Home exposes no document save or defaults actions",
            widgets(workflow.screen()).stream()
                .noneMatch(
                    w ->
                        w instanceof Button
                            && List.of("Save", "Document actions…")
                                .contains(w.getMessage().getString())));
        check.accept(
            "Home narration reports zero cached owners",
            workflow.screen().getNarrationMessage().getString().contains("0 opened"));
        try {
          ((NativeFormScreen) workflow.screen()).withTheme(ClientTheme.standard());
          throw new AssertionError("Mounted screen accepted a replacement theme");
        } catch (IllegalStateException expected) {
          check.accept("actual mounted native screen refuses theme replacement", true);
        }
        homeCanvas = lastCanvas;
        stage++;
        capture.accept(
            "shell-home.png",
            () -> click(workflow.screen(), button(workflow.screen(), "Client preferences")));
      }
      case 2 -> {
        var state =
            workflow.documents().stream()
                .filter(s -> s.document().equals(PlatformWorkflowExample.DEFINITION.id()))
                .findFirst()
                .orElseThrow();
        if (!state.opened() || state.busy()) return false;
        opened = workflow.editor(PlatformWorkflowExample.DEFINITION.id());
        if (opened.view().availability() != EditorActor.Availability.READY) return false;
        check.accept(
            "Home selection admits exactly the selected real owner",
            workflow.documents().stream()
                    .filter(ClientEditingWorkflow.DocumentState::opened)
                    .count()
                == 1);
        check.accept(
            "ordinary location changes retain header artwork bounds and instance",
            homeCanvas.equals(lastCanvas) && creates == 1);
        var distance = design.inherent.reform.definition.FieldPath.ROOT.field("distance");
        // Home's native button starts async navigation. Actor READY does not acknowledge the
        // selected view; wait for its real control in the current screen before inspecting it.
        var distanceControl = opened.form().fieldControl(distance);
        if (distanceControl.isEmpty()
            || !widgets(workflow.screen()).contains(distanceControl.orElseThrow())) return false;
        checkLocationNarration("initial");
        int width = workflow.screen().width, height = workflow.screen().height;
        NativeClientProbe.resizeScreen(workflow.screen(), 320, 240);
        checkLocationNarration("compact logical reflow");
        NativeClientProbe.resizeScreen(workflow.screen(), width, height);
        checkLocationNarration("restored");
        click(workflow.screen(), button(workflow.screen(), "Appearance"));
        click(workflow.screen(), button(workflow.screen(), "Distance tuning"));
        var orderedNavigation =
            widgets(workflow.screen()).stream()
                .filter(widget -> widget.getClass().getSimpleName().equals("NavigationButton"))
                .sorted(java.util.Comparator.comparingInt(widget -> widget.getY()))
                .map(widget -> widget.getMessage().getString())
                .toList();
        check.accept(
            "actual section navigation follows body groups and visual field order: "
                + orderedNavigation,
            orderedNavigation.size() >= 7
                && orderedNavigation
                    .subList(0, 7)
                    .equals(
                        List.of(
                            "Home",
                            "Client preferences",
                            "Appearance",
                            "Enabled",
                            "Label",
                            "Distance tuning",
                            "Distance")));
        var enabled = design.inherent.reform.definition.FieldPath.ROOT.field("enabled");
        var label = design.inherent.reform.definition.FieldPath.ROOT.field("label");
        check.accept(
            "rendered body keeps grouped field order independently of schema order",
            opened.form().fieldControl(enabled).orElseThrow().getY()
                    < opened.form().fieldControl(label).orElseThrow().getY()
                && opened.form().fieldControl(label).orElseThrow().getY()
                    < opened.form().fieldControl(distance).orElseThrow().getY());
        check.accept(
            "typed section reveal retains the same clean owner and source bytes",
            opened
                    .form()
                    .focusTarget(
                        new design.inherent.reform.presentation.FormTarget.Section(
                            "group:appearance"))
                && workflow.editor(PlatformWorkflowExample.DEFINITION.id()) == opened
                && !opened.dirty()
                && Arrays.equals(source, Files.readAllBytes(sourcePath())));
        check.accept(
            "absent typed section refuses rather than selecting a similarly named field",
            !opened
                .form()
                .focusTarget(
                    new design.inherent.reform.presentation.FormTarget.Section("group:missing")));
        check.accept(
            "header navigation leaves the real input focusable",
            opened.form().focusField(distance));
        check.accept(
            "scoped field uses its declared native input offer",
            opened.form().fieldControl(distance).orElseThrow().getWidth() == 140);
        var information =
            widgets(workflow.screen()).stream()
                .filter(w -> w.getMessage().getString().equals("i"))
                .toList();
        check.accept(
            "themed native information glyph retains its local Unicode font",
            !information.isEmpty()
                && information.stream()
                    .allMatch(
                        w ->
                            w.getMessage().getStyle().isBold()
                                && w.getMessage().getStyle().toString().contains("uniform")));
        replace(
            workflow.screen(),
            (net.minecraft.client.gui.components.EditBox)
                opened.form().fieldControl(distance).orElseThrow(),
            "97");
        stage++;
        capture.accept("shell-document.png", workflow::showHome);
      }
      case 3 -> {
        if (opened.busy()) return false;
        check.accept(
            "returning Home retains the same actual editor",
            workflow.editor(PlatformWorkflowExample.DEFINITION.id()) == opened
                && homeCanvas.equals(lastCanvas)
                && creates == 1);
        check.accept(
            "Home retains the complete dirty owner draft without exposing document Save",
            opened.view().dirty()
                && ((design.inherent.reform.edit.DraftNode.Scalar)
                        opened
                            .view()
                            .snapshot()
                            .orElseThrow()
                            .draft()
                            .at(design.inherent.reform.definition.FieldPath.ROOT.field("distance")))
                    .raw()
                    .equals("97")
                && widgets(workflow.screen()).stream()
                    .noneMatch(
                        w ->
                            w instanceof Button
                                && List.of("Save", "Document actions…")
                                    .contains(w.getMessage().getString())));
        check.accept(
            "shell navigation does not persist or modify the source",
            Arrays.equals(source, Files.readAllBytes(sourcePath())));
        workflow.close();
        workflow.close();
        check.accept("canvas is released once at actual workflow retirement", closes == 1);
        check.accept(
            "returned canvas retires before its registered resources on the native thread",
            cleanupOrder.equals(List.of("canvas", "resource"))
                && resourceCleanups == 1
                && !lastScope.isOpen());
        try {
          lastScope.onClose(
              () -> {
                throw new AssertionError("Retired callback registered");
              });
          throw new AssertionError("Retired canvas accepted a new cleanup hook");
        } catch (PolicyRefusal expected) {
          check.accept("retired view context cannot acquire new ownership", true);
        }
        NativeScreens.open(client, parent);
        workflow =
            ClientEditingWorkflow.open(
                parent, PlatformWorkflowExample.forms(), null, presentation(true));
        NativeScreens.open(client, workflow.screen());
        stage++;
      }
      case 4 -> {
        check.accept(
            "failed optional artwork leaves Home and its destinations usable",
            creates == 2
                && workflow.isOpen()
                && button(workflow.screen(), "Client preferences").active);
        workflow.showHome();
        check.accept("failed artwork is not recreated during ordinary navigation", creates == 2);
        check.accept(
            "partial factory failure attempts every registered cleanup and retains its cause",
            resourceCleanups == 2
                && lateCleanups == 1
                && !lastScope.isOpen()
                && Arrays.stream(factoryFailure.getSuppressed())
                    .anyMatch(cause -> cause == cleanupFailure));
        check.accept(
            "optional artwork failure does not open an owner or write",
            workflow.documents().stream().noneMatch(ClientEditingWorkflow.DocumentState::opened)
                && Arrays.equals(source, Files.readAllBytes(sourcePath())));
        stage++;
        capture.accept(
            "shell-canvas-failure.png",
            () -> {
              workflow.close();
              NativeScreens.open(client, parent);
            });
      }
      case 5 -> {
        var forms = List.of(PlatformWorkflowExample.forms().getFirst());
        var layout = maximumHierarchy(forms.getFirst().route());
        var metadata =
            ClientWorkspacePresentation.standard(
                new WorkspaceIdentity(
                    DocumentId.parse("reform_platform_example:maximum_hierarchy"),
                    Component.literal("Complete declared hierarchy")));
        check.accept(
            "maximum category-prefix hierarchy admits without opening an owner",
            metadata.validate(forms, layout).valid());
        workflow = ClientEditingWorkflow.open(parent, forms, layout, metadata);
        NativeScreens.open(client, workflow.screen());
        click(workflow.screen(), button(workflow.screen(), "Limit category 0.0"));
        stage++;
      }
      case 6 -> {
        check.accept(
            "actual native tree admits 1021 static destinations without borrowing owner capacity",
            workflow.isOpen()
                && workflow.documents().stream()
                    .noneMatch(ClientEditingWorkflow.DocumentState::opened)
                && widgets(workflow.screen()).stream()
                    .anyMatch(
                        widget -> widget.getMessage().getString().contains("Limit category 0.0")));
        check.accept(
            "complete hierarchy projection leaves the actual source unchanged",
            Arrays.equals(source, Files.readAllBytes(sourcePath())));
        stage++;
        capture.accept(
            "shell-maximum-hierarchy.png",
            () -> {
              workflow.close();
              NativeScreens.open(client, parent);
            });
      }
      case 7 -> {
        if (navigationChecks == null) navigationChecks = new PlatformNavigationChecks(check);
        if (!navigationChecks.tick()) return false;
        if (tickChecks == null) tickChecks = new PlatformNavigationTickChecks(check);
        return tickChecks.tick();
      }
      default -> throw new AssertionError("Unknown shell scene");
    }
    return false;
  }

  com.google.gson.JsonObject tickNavigationReport() {
    return tickChecks == null ? new com.google.gson.JsonObject() : tickChecks.report();
  }

  @Override
  public void close() {
    if (tickChecks != null) tickChecks.close();
    if (navigationChecks != null) navigationChecks.close();
    if (workflow != null) workflow.close();
  }
}
