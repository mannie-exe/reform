package example.reform.platform;

import static example.reform.platform.PlatformWidgets.*;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import design.inherent.reform.definition.DocumentId;
import design.inherent.reform.definition.FieldPath;
import design.inherent.reform.edit.DraftNode;
import design.inherent.reform.edit.DraftTypes;
import design.inherent.reform.minecraft.client.*;
import design.inherent.reform.minecraft.client.ui.*;
import design.inherent.reform.owner.OwnerContext;
import design.inherent.reform.presentation.*;
import design.inherent.reform.presentation.ui.*;
import design.inherent.reform.value.ValueTypes;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.HashMap;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.IntConsumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * Real shared components in framework and caller-owned hosts, with no alternate product renderer.
 */
final class PlatformComponentChecks implements PlatformFormProbe.Scene {
  private static final DocumentId PAGE = DocumentId.parse("fixture:semantic_counter");
  private static final ViewStateKey<Integer> COUNT =
      new ViewStateKey<>(DocumentId.parse("fixture:semantic_count"), ValueTypes.integer(0, 3));
  private static final ViewStateKey<Boolean> OPEN =
      new ViewStateKey<>(DocumentId.parse("fixture:semantic_detail"), ValueTypes.bool());

  static CompletionStage<JsonObject> run(ClientRegistration registration) {
    return PlatformFormProbe.run(registration, "COMPONENTS", PlatformComponentChecks::new);
  }

  private final PlatformFormProbe.Driver driver;
  private final Minecraft client = Minecraft.getInstance();
  private final Screen parent = NativeScreens.current(client);
  private final JsonArray checks = new JsonArray();
  private final int[] originalWidth = {0}, originalHeight = {0};
  private ClientEditingWorkflow workflow;
  private ClientViewState<Integer> frameworkState;
  private UiScope frameworkScope;
  private CompletableFuture<NavigationAttempt.Result> navigation;
  private ExternalScreen external;
  private UiLocal<Integer> externalState;
  private UiLocal<List<String>> activeRows;
  private final HashMap<String, UiLocal<Integer>> rowState = new HashMap<>();
  private final HashMap<String, UiScope> rowScopes = new HashMap<>();
  private int rowFactories, rowCloses;
  private boolean keyedRowsVerified;
  private boolean boundFieldsVerified;
  private ClientEditor<PlatformWorkflowExample.Preferences> boundEditor;
  private UiScope fieldScope;
  private FieldBinding<PlatformWorkflowExample.Preferences, ?> distanceBinding, labelBinding;
  private design.inherent.reform.operation.OperationHandle<?> fieldOperation;
  private String originalDistance, originalLabel;
  private final JsonArray fieldOperations = new JsonArray();
  private Button retired;
  private Path settingsPath;
  private byte[] settingsBytes;
  private BasicFileAttributes settingsAttributes;
  private int stage,
      frameworkCreates,
      frameworkCloses,
      externalCreates,
      externalCloses,
      branchCreates,
      branchCloses,
      projections;
  private long beforeFrames;
  private int beforeProjection;
  private boolean complete;

  private PlatformComponentChecks(PlatformFormProbe.Driver driver) {
    this.driver = driver;
  }

  private void check(String description, boolean valid) {
    if (!valid) throw new AssertionError(description);
    checks.add(description);
  }

  private Component text(String value) {
    return Component.literal(value);
  }

  private UiCommand<Void> increment(UiRead<Integer> value, IntConsumer edit) {
    return new UiCommand<>() {
      public UiAvailability availability() {
        return value.snapshot().value() < 3
            ? UiAvailability.READY
            : UiAvailability.blocked("fixture.count_full", "The counter has reached three.");
      }

      public UiActionResult invoke(Void ignored) {
        var readiness = availability();
        if (!readiness.available())
          return new UiActionResult.Refused(readiness.report(), Optional.empty());
        edit.accept(value.snapshot().value() + 1);
        return new UiActionResult.Applied();
      }

      public List<UiRead<?>> dependencies() {
        return List.of(value);
      }
    };
  }

  private ViewSpec counter(UiScope scope, UiRead<Integer> count, IntConsumer edit) {
    var expanded = scope.local(OPEN, true);
    var itemType = ValueTypes.text(128);
    var items =
        scope.local(
            new ViewStateKey<>(
                DocumentId.parse("fixture:semantic_rows"), ValueTypes.list(itemType, 4)),
            List.of("a:First", "b:Second"));
    activeRows = items;
    UiCommand<Void> toggle =
        new UiCommand<>() {
          public UiAvailability availability() {
            return UiAvailability.READY;
          }

          public UiActionResult invoke(Void ignored) {
            expanded.replace(!expanded.snapshot().value());
            return new UiActionResult.Applied();
          }

          public List<UiRead<?>> dependencies() {
            return List.of(expanded);
          }
        };
    return Views.section(
        "counter",
        UiText.of(text("Retained component")),
        List.of(
            Views.text(
                "value",
                UiText.of(text("Current count")),
                UiText.from(
                    count,
                    value -> {
                      projections++;
                      return text("Count " + value);
                    })),
            Views.action(
                "increment",
                UiText.of(text("Increment")),
                UiText.from(
                    count, value -> text("Changes local state. Current count " + value + ".")),
                increment(count, edit)),
            Views.action(
                "toggle",
                UiText.of(text("Toggle detail")),
                UiText.of(text("Changes presentation only.")),
                toggle),
            Views.show(
                "detail",
                expanded,
                branch -> {
                  branchCreates++;
                  branch.onRetire(() -> branchCloses++);
                  return Views.text(
                      "body", text("Detail"), text("This branch has its own lifetime."));
                },
                branch -> Views.text("body", text("Detail hidden"), Component.empty())),
            Views.forEach(
                "rows",
                items,
                itemType,
                4,
                s -> s.substring(0, 1),
                (row, key, item, index) -> {
                  rowFactories++;
                  row.onRetire(() -> rowCloses++);
                  var local = row.local(COUNT, 0);
                  rowState.put(key, local);
                  rowScopes.put(key, row);
                  return Views.action(
                      "value",
                      UiText.from(item, Component::literal),
                      UiText.from(local, n -> text("Row count " + n)),
                      increment(local, local::replace));
                }),
            Views.action(
                "move",
                UiText.of(text("Move rows")),
                UiText.of(Component.empty()),
                localAction(() -> items.replace(List.of("b:Updated", "a:First")))),
            Views.action(
                "break",
                UiText.of(text("Break rows")),
                UiText.of(Component.empty()),
                localAction(() -> items.replace(List.of("a:First", "a:Duplicate")))),
            Views.action(
                "repair",
                UiText.of(text("Repair rows")),
                UiText.of(Component.empty()),
                localAction(() -> items.replace(List.of("a:First", "b:Second"))))));
  }

  private UiCommand<Void> localAction(Runnable action) {
    return new UiCommand<>() {
      public UiAvailability availability() {
        return UiAvailability.READY;
      }

      public UiActionResult invoke(Void ignored) {
        action.run();
        return new UiActionResult.Applied();
      }
    };
  }

  private void press(Screen screen, String label) {
    var control = button(screen, label);
    check("semantic control is currently mounted: " + label, control != null && control.active);
    check("semantic control has a native focus path: " + label, focus(screen, control));
    check(
        "native Space activates exactly one semantic action: " + label,
        NativeClientProbe.key(screen, "key.keyboard.space", true));
    NativeClientProbe.key(screen, "key.keyboard.space", false);
  }

  private boolean arrived() {
    if (!navigation.isDone()) return false;
    check(
        "identified component navigation is actually revealed",
        navigation.join().outcome() == NavigationAttempt.Outcome.REVEALED);
    return true;
  }

  @Override
  public boolean tick() throws Exception {
    switch (stage) {
      case 0 -> {
        settingsPath =
            client
                .gameDirectory
                .toPath()
                .resolve("config/reform_platform_example/workflow_preferences.json");
        settingsBytes = Files.readAllBytes(settingsPath);
        settingsAttributes = Files.readAttributes(settingsPath, BasicFileAttributes.class);
        NativeClientProbe.size(client, originalWidth, originalHeight);
        var form = PlatformWorkflowExample.settings().form(text("Unopened document"));
        var category =
            new ClientWorkflowLayout.Category(PAGE, text("Components"), text("View-local state"));
        var layout =
            new ClientWorkflowLayout(
                List.of(
                    new ClientWorkflowLayout.Group(List.of(category), List.of(), List.of()),
                    new ClientWorkflowLayout.Group(
                        List.of(
                            new ClientWorkflowLayout.Category(
                                DocumentId.parse("fixture:semantic_settings"),
                                text("Settings"),
                                Component.empty())),
                        List.of(
                            new ClientWorkflowLayout.Placement(
                                form.route(), form.route(), form.title(), Optional.empty())),
                        List.of())),
                List.of());
        var body =
            ClientCustomView.component(
                new ViewKey<>(new DestinationId(PAGE), COUNT),
                0,
                UiLimits.STANDARD,
                (scope, current) -> {
                  frameworkCreates++;
                  frameworkState = current;
                  frameworkScope = scope;
                  scope.onRetire(() -> frameworkCloses++);
                  return counter(scope, scope.observe(COUNT, current), current::replace);
                });
        var metadata =
            ClientWorkspacePresentation.standard(
                    new WorkspaceIdentity(
                        DocumentId.parse("fixture:semantic_workspace"),
                        text("Semantic components")))
                .withView(body);
        check(
            "component declaration is lazy metadata",
            metadata.validate(List.of(form), layout).valid() && frameworkCreates == 0);
        workflow = ClientEditingWorkflow.open(parent, List.of(form), layout, metadata);
        NativeScreens.open(client, workflow.screen());
        beforeFrames = PlatformFrameObservations.sequence();
        stage = 1;
      }
      case 1 -> {
        if (PlatformFrameObservations.sequence() - beforeFrames < 8) return false;
        check(
            "unopened Home constructs no component or document editor",
            frameworkCreates == 0
                && workflow.documents().stream()
                    .noneMatch(ClientEditingWorkflow.DocumentState::opened));
        navigation =
            workflow
                .navigation()
                .open(new ClientDestination.View(new DestinationId(PAGE)))
                .completion()
                .toCompletableFuture();
        stage = 2;
      }
      case 2 -> {
        if (!arrived()) return false;
        check("framework mounts exactly one component factory", frameworkCreates == 1);
        retired = button(workflow.screen(), "Increment");
        press(workflow.screen(), "Increment");
        check(
            "framework semantic edit changes retained view state only",
            frameworkState.value() == 1
                && workflow.documents().stream()
                    .noneMatch(ClientEditingWorkflow.DocumentState::opened));
        press(workflow.screen(), "a:First");
        var oldRow = button(workflow.screen(), "a:First");
        press(workflow.screen(), "Move rows");
        check(
            "keyed move updates native order and values without recreating row state",
            rowFactories == 2
                && rowState.get("a").snapshot().value() == 1
                && button(workflow.screen(), "b:Updated").getY()
                    < button(workflow.screen(), "a:First").getY());
        press(workflow.screen(), "Break rows");
        var blockedRow = button(workflow.screen(), "a:First");
        check(
            "duplicate keys retain earlier rows disabled with an available repair control",
            !blockedRow.active && button(workflow.screen(), "Repair rows").active);
        NativeClientProbe.pressRetired(oldRow);
        NativeClientProbe.pressRetired(blockedRow);
        check(
            "retired and currently disabled row callbacks cannot bypass list failure",
            rowState.get("a").snapshot().value() == 1);
        press(workflow.screen(), "Repair rows");
        check(
            "actual native repair restores retained keyed rows without a new factory",
            rowFactories == 2
                && rowState.get("a").snapshot().value() == 1
                && button(workflow.screen(), "a:First").active
                && button(workflow.screen(), "b:Second").active);
        // A cached callback must observe a newly changed input even before the next host update.
        var notYetRefreshed = button(workflow.screen(), "a:First");
        activeRows.replace(List.of("b:Updated"));
        NativeClientProbe.pressRetired(notYetRefreshed);
        check(
            "dispatch refresh rejects a row removed before the next host turn",
            !rowScopes.get("a").isOpen() && rowFactories == 2);
        keyedRowsVerified = true;
        beforeProjection = projections;
        beforeFrames = PlatformFrameObservations.sequence();
        stage = 3;
      }
      case 3 -> {
        if (PlatformFrameObservations.sequence() - beforeFrames < 8) return false;
        check(
            "steady native frames do not reconstruct or project component content",
            frameworkCreates == 1 && projections == beforeProjection);
        driver.capture("components-framework.png", () -> stage = 4);
        stage = -1;
      }
      case 4 -> {
        navigation =
            workflow
                .navigation()
                .open(new ClientDestination.Home())
                .completion()
                .toCompletableFuture();
        stage = 5;
      }
      case 5 -> {
        if (!arrived()) return false;
        NativeClientProbe.pressRetired(retired);
        check("hidden framework callback cannot edit retained state", frameworkState.value() == 1);
        navigation =
            workflow
                .navigation()
                .open(new ClientDestination.View(new DestinationId(PAGE)))
                .completion()
                .toCompletableFuture();
        stage = 6;
      }
      case 6 -> {
        if (!arrived()) return false;
        check(
            "framework return retains component identity and state",
            frameworkCreates == 1 && frameworkState.value() == 1);
        press(workflow.screen(), "Toggle detail");
        check(
            "conditional branch retires its actual scope", branchCreates == 1 && branchCloses == 1);
        navigation =
            workflow
                .navigation()
                .open(new ClientDestination.Document(PlatformWorkflowExample.DEFINITION.id()))
                .completion()
                .toCompletableFuture();
        stage = 20;
      }
      case 20 -> {
        if (!arrived()) return false;
        boundEditor = workflow.editor(PlatformWorkflowExample.DEFINITION);
        if (!boundEditor.canStage(FieldPath.ROOT.field("distance"))) return false;
        fieldScope = frameworkScope.child();
        distanceBinding =
            ClientFieldBindings.field(
                fieldScope,
                boundEditor,
                FieldPath.ROOT.field("distance"),
                DraftTypes.declaredAt(
                    PlatformWorkflowExample.DEFINITION.type(), FieldPath.ROOT.field("distance")));
        labelBinding =
            ClientFieldBindings.field(
                fieldScope,
                boundEditor,
                FieldPath.ROOT.field("label"),
                DraftTypes.declaredAt(
                    PlatformWorkflowExample.DEFINITION.type(), FieldPath.ROOT.field("label")));
        originalDistance =
            ((DraftNode.Scalar) distanceBinding.state().snapshot().value().draft()).raw();
        originalLabel = ((DraftNode.Scalar) labelBinding.state().snapshot().value().draft()).raw();
        replace(workflow.screen(), fieldInput("distance"), "bad");
        stage = 21;
      }
      case 21 -> {
        fieldScope.refreshObservations();
        var state = distanceBinding.state().snapshot().value();
        if (boundEditor.busy()
            || state.input().isEmpty()
            || !state.input().orElseThrow().accepted()
            || !((DraftNode.Scalar) state.draft()).raw().equals("bad")
            || !boundEditor.canStage(FieldPath.ROOT.field("label"))) return false;
        var input = fieldInput("distance");
        input.setCursorPosition(2);
        input.setHighlightPos(1);
        fieldScope.refreshObservations();
        state = distanceBinding.state().snapshot().value();
        check(
            "actual bound field keeps invalid actor input, native raw text, caret and selection",
            state.value().isEmpty()
                && !state.validation().valid()
                && state.input().orElseThrow().raw().equals("bad")
                && state.input().orElseThrow().caret() == 2
                && state.input().orElseThrow().anchor() == 1
                && !state.submission().available());
        fieldOperation = fieldEdit(labelBinding, "Semantic sibling");
        stage = 22;
      }
      case 22 -> {
        if (!fieldOperation.completion().toCompletableFuture().isDone() || boundEditor.busy())
          return false;
        fieldScope.refreshObservations();
        var state = labelBinding.state().snapshot().value();
        if (!state.value().orElseThrow().equals("Semantic sibling")) return false;
        check(
            "actual field operation preserves the invalid sibling and retained editor",
            workflow.editor(PlatformWorkflowExample.DEFINITION) == boundEditor
                && ((DraftNode.Scalar) distanceBinding.state().snapshot().value().draft())
                    .raw()
                    .equals("bad")
                && boundEditor.actions().operation(fieldOperation.snapshot().id()).isPresent());
        replace(workflow.screen(), fieldInput("distance"), originalDistance);
        stage = 23;
      }
      case 23 -> {
        if (boundEditor.busy() || !boundEditor.canStage(FieldPath.ROOT.field("label")))
          return false;
        fieldScope.refreshObservations();
        if (!((DraftNode.Scalar) distanceBinding.state().snapshot().value().draft())
            .raw()
            .equals(originalDistance)) return false;
        fieldOperation = fieldEdit(labelBinding, originalLabel);
        stage = 24;
      }
      case 24 -> {
        if (!fieldOperation.completion().toCompletableFuture().isDone() || boundEditor.busy())
          return false;
        fieldScope.refreshObservations();
        if (!((DraftNode.Scalar) labelBinding.state().snapshot().value().draft())
            .raw()
            .equals(originalLabel)) return false;
        fieldScope.close();
        check(
            "binding close retires observations while the actual editor stays open",
            boundEditor.isOpen()
                && fieldScope.usage().stateBytes() == 0
                && fieldScope.usage().scopes() == 0);
        boundFieldsVerified = true;
        workflow.close();
        check(
            "workflow closes component tree and charged presentation data",
            frameworkCloses == 1
                && frameworkScope.usage().scopes() == 0
                && frameworkScope.usage().stateBytes() == 0);
        external = new ExternalScreen();
        NativeScreens.open(client, external);
        stage = 7;
      }
      case 7 -> {
        if (external.mount == null) return false;
        check("caller-owned screen mounts one component without a workflow", externalCreates == 1);
        press(external, "Increment");
        check(
            "caller-owned semantic action changes only its local state",
            externalState.snapshot().value() == 1);
        var narration = NativeClientProbe.narration(external.mount::updateNarration);
        check(
            "native component narration includes current action context",
            narration.contains("Increment") && narration.contains("count 1"));
        external.originalMount = external.mount;
        NativeClientProbe.resize(client, 320, 240);
        stage = 8;
      }
      case 8 -> {
        int[] width = {0}, height = {0};
        NativeClientProbe.size(client, width, height);
        if (width[0] != 320 || height[0] != 240) return false;
        check(
            "physical narrow window retains host mount and state",
            external.mount == external.originalMount
                && externalCreates == 1
                && externalState.snapshot().value() == 1);
        driver.capture(
            "components-narrow.png",
            () -> {
              NativeClientProbe.resize(client, originalWidth[0], originalHeight[0]);
              stage = 9;
            });
        stage = -1;
      }
      case 9 -> {
        int[] width = {0}, height = {0};
        NativeClientProbe.size(client, width, height);
        if (width[0] != originalWidth[0] || height[0] != originalHeight[0]) return false;
        check(
            "physical return keeps the original component mount and native focus",
            external.mount == external.originalMount
                && externalCreates == 1
                && externalState.snapshot().value() == 1
                && external.getFocused() == external.mount);
        driver.capture("components-external.png", () -> stage = 10);
        stage = -1;
      }
      case 10 -> {
        var old = button(external, "Increment");
        check(
            "native pointer dispatch reaches the mounted component",
            NativeClientProbe.pointerDown(
                external, old.getX() + old.getWidth() / 2.0, old.getY() + old.getHeight() / 2.0));
        check(
            "native pointer action changes the local counter once",
            externalState.snapshot().value() == 2);
        external.mount.visible(false);
        NativeClientProbe.pressRetired(old);
        check(
            "suspended external component has no native children capture or live callbacks",
            external.mount.children().isEmpty()
                && !external.mount.isDragging()
                && externalState.snapshot().value() == 2);
        NativeClientProbe.pointerUp(external, 0, 0);
        external.mount.visible(true);
        check("external component resumes without recreating factory", externalCreates == 1);
        var scope = external.mount.scope();
        external.mount.close();
        check(
            "mount closes only owned resources and keeps borrowed host open",
            external.host.isOpen()
                && externalCloses == 1
                && scope.usage().scopes() == 0
                && scope.usage().stateBytes() == 0);
        external.host.close();
        NativeScreens.open(client, parent);
        var after = Files.readAttributes(settingsPath, BasicFileAttributes.class);
        check(
            "component journeys preserve source bytes time and file identity",
            java.util.Arrays.equals(settingsBytes, Files.readAllBytes(settingsPath))
                && settingsAttributes.lastModifiedTime().equals(after.lastModifiedTime())
                && Objects.equals(settingsAttributes.fileKey(), after.fileKey()));
        complete = true;
        return true;
      }
      default -> {}
    }
    return false;
  }

  private EditBox fieldInput(String field) {
    var path = FieldPath.ROOT.field(field);
    check(
        "bound field remains revealable through its real form",
        boundEditor.form().focusField(path));
    return (EditBox) boundEditor.form().fieldControl(path).orElseThrow();
  }

  private design.inherent.reform.operation.OperationHandle<?> fieldEdit(
      FieldBinding<?, ?> binding, String text) {
    var result = binding.edits().invoke(new FieldEdit.Raw<>(text));
    check(
        "semantic field edit returns its actual owner operation",
        result instanceof UiActionResult.Pending);
    var handle = ((UiActionResult.Pending) result).operation();
    fieldOperations.add(handle.snapshot().id().toString());
    return handle;
  }

  private final class ExternalScreen extends NativeFormScreen {
    final OwnerContext host =
        new OwnerContext(
            client::execute,
            NativeClientProbe.ioPool(),
            () -> {
              if (!client.isSameThread())
                throw new IllegalStateException("Actual native thread required");
            },
            OwnerContext.Limits.STANDARD);
    ClientUiMount mount, originalMount;
    int draws;
    boolean restoreMountFocus;

    ExternalScreen() {
      super(Component.literal("Caller-owned component"));
    }

    @Override
    protected void init() {
      var offer =
          new FormGeometry.Placement(8, 8, Math.max(1, width - 16), Math.max(1, height - 16));
      if (mount == null) {
        mount =
            ClientUiMount.mount(
                ClientViewContext.borrow(host),
                UiLimits.STANDARD,
                (scope, props) -> {
                  externalCreates++;
                  scope.onRetire(() -> externalCloses++);
                  externalState = scope.local(COUNT, 0);
                  return counter(scope, externalState, externalState::replace);
                },
                "fixed",
                offer,
                ClientTheme.standard());
      } else mount.reflow(offer, ClientTheme.standard());
      addRenderableWidget(mount);
      if (restoreMountFocus) setFocused(mount);
      restoreMountFocus = false;
    }

    @Override
    protected void beforeResize() {
      restoreMountFocus = mount != null && getFocused() == mount;
    }

    @Override
    public void tick() {
      if (mount != null && mount.isOpen()) mount.update();
    }

    @Override
    public void render(FormCanvas canvas, int x, int y, float delta) {
      super.render(canvas, x, y, delta);
      draws++;
    }

    @Override
    public void removed() {
      if (mount != null) mount.close();
      host.close();
    }
  }

  @Override
  public JsonObject report() {
    var report = new JsonObject();
    report.addProperty("status", complete ? "PASS" : "RUNNING");
    report.addProperty("passed", complete);
    report.addProperty("sourceUnchanged", complete);
    report.addProperty("retiredUiDataZero", complete);
    report.addProperty("keyedRowsVerified", complete && keyedRowsVerified);
    report.addProperty("boundFieldsVerified", complete && boundFieldsVerified);
    report.add("boundFieldOperations", fieldOperations.deepCopy());
    report.addProperty("keyedRowFactories", rowFactories);
    report.addProperty("keyedRowCloses", rowCloses);
    report.add("checks", checks.deepCopy());
    report.addProperty("frameworkFactories", frameworkCreates);
    report.addProperty("frameworkCloses", frameworkCloses);
    report.addProperty("externalFactories", externalCreates);
    report.addProperty("externalCloses", externalCloses);
    report.addProperty("externalDraws", external == null ? 0 : external.draws);
    report.addProperty("stage", stage);
    return report;
  }

  @Override
  public void close() {
    if (workflow != null && workflow.isOpen()) workflow.close();
    if (external != null) external.removed();
    if (originalWidth[0] > 0 && originalHeight[0] > 0)
      NativeClientProbe.resize(client, originalWidth[0], originalHeight[0]);
    NativeScreens.open(client, parent);
  }
}
