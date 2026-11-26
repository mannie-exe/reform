package example.reform.platform;

import static example.reform.platform.PlatformWidgets.*;

import com.google.gson.*;
import design.inherent.reform.definition.*;
import design.inherent.reform.edit.DraftNode;
import design.inherent.reform.minecraft.client.*;
import design.inherent.reform.minecraft.client.ui.*;
import design.inherent.reform.operation.*;
import design.inherent.reform.owner.OwnerContext;
import design.inherent.reform.presentation.*;
import design.inherent.reform.presentation.ui.*;
import design.inherent.reform.reference.*;
import design.inherent.reform.value.*;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * Actual shared picker/controller, real owner/source and original field frontend; no private UI.
 */
final class PlatformPickerChecks implements PlatformFormProbe.Scene {
  record Profile(String models, String artwork) {}

  static final DocumentId SOURCE = DocumentId.parse("fixture:shared_profiles");
  static final FieldKey<String> MODELS =
      FieldKey.of("models", ValueTypes.reference(ValueTypes.text(128), SOURCE));
  static final FieldKey<String> ARTWORK = FieldKey.of("artwork", MODELS.type());
  static final DocumentDefinition<Profile> DEFINITION =
      new DocumentDefinition<>(
          DocumentId.parse("reform_platform_example:picker_profiles"),
          1,
          RecordType.<Profile>builder(
                  values -> new Profile(values.get(MODELS), values.get(ARTWORK)))
              .field(MODELS, Profile::models)
              .field(ARTWORK, Profile::artwork)
              .build(),
          () -> new Profile(id(0), id(0)));
  static final PickerRole<Profile> MODEL_ROLE =
      new PickerRole<>("models", FieldPath.ROOT.field("models"));
  static final PickerRole<Profile> ART_ROLE =
      new PickerRole<>("artwork", FieldPath.ROOT.field("artwork"));
  static final DocumentId TASK = DocumentId.parse("fixture:shared_picker_task");
  static final DocumentId ROUTE = DocumentId.parse("fixture:shared_picker_owner_route");
  static final ReferenceLimits LIMITS = new ReferenceLimits(64, 65536, 4, 128, 4);
  static final ReferencePicker.Policy POLICY =
      new ReferencePicker.Policy(4, 128, 32768, 200_000_000L, 3, 128);
  static ClientFileSettings<Profile> settings;

  static void register(ClientRegistration registration) {
    settings = ClientFileSettings.clientJson(registration, DEFINITION);
  }

  static CompletionStage<JsonObject> run(ClientRegistration registration) {
    return PlatformFormProbe.run(registration, "PICKERS", PlatformPickerChecks::new);
  }

  static String id(int index) {
    return "fixture:profile_" + String.format(java.util.Locale.ROOT, "%03d", index);
  }

  static Component text(String value) {
    return Component.literal(value);
  }

  private final Minecraft client = Minecraft.getInstance();
  private final Screen parent = NativeScreens.current(client);
  private final PlatformFormProbe.Driver driver;
  private final JsonArray checks = new JsonArray();
  private final AtomicLong clock = new AtomicLong();
  private ClientEditingWorkflow workflow;
  private ClientEditor<Profile> editor;
  private ClientEditor<Profile> discardedEditor;
  private ReferenceActions<String, List<String>> source;
  private OwnerContext sourceContext;
  private final AtomicBoolean sourceReadable = new AtomicBoolean(true);
  private volatile SearchGate searchGate;
  private ReferenceSuggestions.Option withdrawnOption;
  private int withdrawnSearches, withdrawnLookups, withdrawnFactories;
  private UUID heldSearch;
  private boolean completionReadWithdrawal, completionSourceDrain;
  private final JsonObject sourceAuthorityFacts = new JsonObject();

  private static final class SearchGate {
    final CountDownLatch entered = new CountDownLatch(1);
    final CountDownLatch release = new CountDownLatch(1);
    final CountDownLatch exited = new CountDownLatch(1);
    final AtomicBoolean claimed = new AtomicBoolean();
    volatile String thread, prefix;

    void await(ReferenceQuery query) {
      if (query.match() != ReferenceQuery.Match.KEY_PREFIX || !claimed.compareAndSet(false, true))
        return;
      thread = Thread.currentThread().getName();
      prefix = query.filter();
      entered.countDown();
      try {
        if (!release.await(25, TimeUnit.SECONDS))
          throw new AssertionError("Owned native source gate did not drain within its bound");
      } catch (InterruptedException interrupted) {
        Thread.currentThread().interrupt();
        throw new IllegalStateException(interrupted);
      } finally {
        exited.countDown();
      }
    }
  }

  private ReferencePicker<Profile, List<String>> task, inline;
  private ReferenceSuggestions<List<String>> suggestions;
  private boolean completionVerified, completionDismissed, completionPointer, completionBlur;
  private boolean completionStale, completionStaleFeedback, completionReflow, standaloneCompletion;
  private SettingsScreen<?> standalone;
  private DocumentActions<?> standaloneActions;
  private UiScope standaloneScope;
  private ClientFieldParts<?> standaloneParts;
  private ClientTextInput standaloneInput;
  private ReferenceSuggestions<List<String>> standaloneSuggestions;
  private EditBox standaloneWidget;
  private int standaloneFactories, standaloneCloses;
  private design.inherent.reform.edit.DraftRevision reflowRevision;
  private UiScope taskScope, inlineScope, fieldScope;
  private UiLocal<Boolean> expanded;
  private UiLocal<Boolean> manualExpanded;
  private UiLocal<Boolean> unrelatedPresentation;
  private ClientTextInput originalInput;
  private ClientTextInput.Stamp hiddenStamp;
  private EditBox manualWidget;
  private design.inherent.reform.edit.DraftRevision manualRevision;
  private int undoRemaining;
  private ClientFieldParts<?> originalParts;
  private CompletableFuture<?> pending;
  private CompletableFuture<NavigationAttempt.Result> navigation;
  private Path file;
  private byte[] bytes;
  private BasicFileAttributes attrs;
  private int stage, factories, pagingPass, baselineSearches, sourceReturns;
  private boolean pickerReturnVerified;
  private PickerState returnBefore;
  private NavigationBookmark returnBookmark;
  private AbstractWidget returnList;
  private EditBox returnQuery;
  private Optional<String> returnAnchor;
  private design.inherent.reform.edit.DraftRevision returnDraft;
  private final JsonObject returnFacts = new JsonObject();
  private final AtomicInteger searches = new AtomicInteger(), lookups = new AtomicInteger();
  private int[] width = {0}, height = {0};
  private long frames;
  private boolean complete;
  private int fieldFactories, fieldRetirements;
  private long inlineWaitFrame = -1;
  private final JsonObject pointerFacts = new JsonObject();

  private void pointerBounds(String phase) {
    var widget = list();
    var facts = new JsonObject();
    facts.addProperty("x", widget.getX());
    facts.addProperty("y", widget.getY());
    facts.addProperty("width", widget.getWidth());
    facts.addProperty("height", widget.getHeight());
    facts.addProperty("screenWidth", workflow.screen().width);
    facts.addProperty("screenHeight", workflow.screen().height);
    facts.addProperty("focused", widget.isFocused());
    pointerFacts.add(phase, facts);
  }

  private PlatformPickerChecks(PlatformFormProbe.Driver driver) {
    this.driver = driver;
  }

  private void check(String label, boolean valid) {
    if (!valid) throw new AssertionError(label);
    checks.add(label);
  }

  private ReferenceActions<String, List<String>> source() {
    factories++;
    if (source == null) {
      sourceContext =
          new OwnerContext(
              client::execute,
              NativeClientProbe.ioPool(),
              () -> {
                if (!client.isSameThread())
                  throw new IllegalStateException("Actual native source owner thread required");
              },
              OwnerContext.Limits.STANDARD);
      source =
          new ReferenceActions<>(
              sourceContext,
              sourceContext.access(sourceReadable::get, () -> false),
              SOURCE,
              ValueTypes.text(128),
              ValueTypes.list(ValueTypes.text(128), 8).snapshotPolicy(),
              LIMITS);
      source.bind(
          "native-shared-picker",
          stamp -> {
            var entries = new ArrayList<ReferenceIndex.Entry<String, List<String>>>();
            for (int i = 0; i < 25; i++)
              entries.add(
                  new ReferenceIndex.Entry<>(
                      id(i),
                      i == 0 ? List.of() : List.of("contribution"),
                      "Profile " + String.format(java.util.Locale.ROOT, "%03d", i),
                      "Profile " + String.format(java.util.Locale.ROOT, "%03d", i),
                      Map.of(
                          "Declared models",
                          Integer.toString(i % 3),
                          "Declared artwork",
                          Integer.toString(i % 2))));
            var index =
                new ReferenceIndex<>(
                    stamp,
                    ValueTypes.text(128),
                    ValueTypes.list(ValueTypes.text(128), 8).snapshotPolicy(),
                    LIMITS,
                    entries);
            return new ReferenceResolver<String, List<String>>() {
              public ReferenceStamp stamp() {
                return stamp;
              }

              public boolean supports(ReferenceQuery.Match match) {
                return index.supports(match);
              }

              public ReferencePage<String> search(ReferenceQuery query) {
                searches.incrementAndGet();
                var held = searchGate;
                if (held != null) held.await(query);
                return index.search(query);
              }

              public ReferenceResolution<List<String>> resolve(String key) {
                lookups.incrementAndGet();
                return index.resolve(key);
              }
            };
          });
    }
    return source;
  }

  private ReferencePickerView.Presentation<Profile> presentation() {
    return new ReferencePickerView.Presentation<>(
        UiText.of(text("Profile browser")),
        UiText.of(text("Inspect profiles, or choose one for a draft field.")),
        UiText.of(text("Available profiles")),
        UiText.of(text("Search profiles")),
        List.of(
            new ReferencePickerView.RoleAction<>(MODEL_ROLE, UiText.of(text("Use for Models"))),
            new ReferencePickerView.RoleAction<>(ART_ROLE, UiText.of(text("Use for Artwork")))),
        4,
        2);
  }

  private PickerState taskState() {
    return task.state().snapshot().value();
  }

  private PickerState inlineState() {
    return inline.state().snapshot().value();
  }

  private String raw(String field) {
    return ((DraftNode.Scalar)
            editor.view().snapshot().orElseThrow().draft().at(FieldPath.ROOT.field(field)))
        .raw();
  }

  private AbstractWidget list() {
    return widgets(workflow.screen()).stream()
        .filter(widget -> widget.getMessage().getString().equals("Available profiles"))
        .findFirst()
        .orElseThrow();
  }

  private EditBox search() {
    return (EditBox)
        widgets(workflow.screen()).stream()
            .filter(
                widget ->
                    widget instanceof EditBox
                        && widget.getMessage().getString().equals("Search profiles"))
            .findFirst()
            .orElseThrow();
  }

  private void key(String key) {
    check(
        "native key reaches current picker: " + key,
        NativeClientProbe.key(workflow.screen(), "key.keyboard." + key, true));
    NativeClientProbe.key(workflow.screen(), "key.keyboard." + key, false);
  }

  private void press(String caption) {
    var button = button(workflow.screen(), caption);
    check("native action is active: " + caption, button.active && focus(workflow.screen(), button));
    key("enter");
  }

  private void completionPointer(int index) {
    completionPointer(workflow.screen(), manualWidget, index);
  }

  private void completionPointer(Screen screen, EditBox anchor, int index) {
    // Black-box native pointer coordinates from the actual anchor and offered root geometry.
    int row = Math.max(20, ClientTheme.standard().values().value(ThemeValues.Role.CONTROL_HEIGHT));
    int heading = client.font.lineHeight + 2;
    int fixed = heading * 2 + 4;
    int below = Math.max(0, screen.height - 4 - anchor.getY() - anchor.getHeight());
    int above = Math.max(0, anchor.getY() - 4);
    boolean down = below >= fixed + row * 4 || below >= above;
    int rows = Math.min(4, Math.max(0, (down ? below : above) - fixed) / row);
    check("pointer witness has its actual offered row", rows > index);
    int y =
        (down ? anchor.getY() + anchor.getHeight() : anchor.getY() - fixed - row * rows)
            + 2
            + heading
            + index * row
            + row / 2;
    double x = Math.clamp(anchor.getX(), 4, screen.width - 184) + 20;
    check(
        "native pointer is consumed by the completion offer",
        NativeClientProbe.pointerDown(screen, x, y));
    NativeClientProbe.pointerUp(screen, x, y);
  }

  private String standaloneRaw(String field) {
    return ((DraftNode.Scalar)
            standalone
                .editingActions()
                .view()
                .snapshot()
                .orElseThrow()
                .draft()
                .at(FieldPath.ROOT.field(field)))
        .raw();
  }

  private boolean standaloneSettled() {
    standaloneScope.refreshObservations();
    var field = standaloneParts.state().snapshot().value();
    return field.input().isPresent()
        && field.input().orElseThrow().accepted()
        && !field.input().orElseThrow().pending()
        && field.validation().valid()
        && standalone.editingActions().view().pending().isEmpty();
  }

  private void standaloneKey(String key) {
    check(
        "native standalone completion key: " + key,
        NativeClientProbe.key(standalone, "key.keyboard." + key, true));
    NativeClientProbe.key(standalone, "key.keyboard." + key, false);
  }

  private Screen standaloneScreen() {
    var options =
        new ClientFormOptions<Profile>(FormPresentation.EMPTY, Map.of())
            .withComponent(
                ART_ROLE.field(),
                (scope, parts) -> {
                  standaloneFactories++;
                  scope.onRetire(() -> standaloneCloses++);
                  standaloneScope = scope;
                  standaloneParts = parts;
                  var picker =
                      ReferencePicker.open(
                          scope,
                          parts.binding(),
                          DEFINITION,
                          SOURCE,
                          this::source,
                          List.of(MODEL_ROLE, ART_ROLE),
                          Map.of("artwork", parts.frontend()),
                          POLICY,
                          clock::get);
                  standaloneSuggestions = picker.suggestions(ReferenceSuggestions.Policy.STANDARD);
                  var manual =
                      scope.local(
                          new ViewStateKey<>(
                              DocumentId.parse("fixture:standalone_id"), ValueTypes.bool()),
                          true);
                  return Views.column(
                      "standalone-artwork",
                      List.of(
                          parts.label("label"),
                          parts.controlSurface(
                              "control",
                              Views.text(
                                  "purpose",
                                  Component.empty(),
                                  text("Type an artwork profile ID, or choose a suggestion.")),
                              UiBindings.local(manual),
                              UiText.of(text("Artwork ID")),
                              new CompletionParts(
                                  standaloneSuggestions,
                                  UiText.of(text("Suggested artwork IDs")),
                                  4)),
                          parts.feedback("feedback")));
                });
    return settings.screen(parent, text("Standalone completion"), options);
  }

  private void open(ClientDestination destination, int next) {
    navigation = workflow.navigation().open(destination).completion().toCompletableFuture();
    stage = next;
  }

  private boolean arrived() {
    if (!navigation.isDone()) return false;
    var result = navigation.join();
    if (result.outcome() != NavigationAttempt.Outcome.REVEALED)
      throw new AssertionError(
          "Picker navigation " + result.target() + ": " + result.outcome() + " " + result.report(),
          result.cause().orElse(null));
    check(
        "identified picker destination revealed",
        result.outcome() == NavigationAttempt.Outcome.REVEALED);
    return true;
  }

  private void unchanged() throws Exception {
    var after = Files.readAttributes(file, BasicFileAttributes.class);
    check(
        "shared picker preserves file bytes time and identity",
        Arrays.equals(bytes, Files.readAllBytes(file))
            && attrs.lastModifiedTime().equals(after.lastModifiedTime())
            && Objects.equals(attrs.fileKey(), after.fileKey()));
  }

  @Override
  public boolean tick() throws Exception {
    switch (stage) {
      case 0 -> {
        file =
            client
                .gameDirectory
                .toPath()
                .resolve("config/reform_platform_example/picker_profiles.json");
        if (settings.runtime().isEmpty() || !Files.isRegularFile(file)) return false;
        bytes = Files.readAllBytes(file);
        attrs = Files.readAttributes(file, BasicFileAttributes.class);
        NativeClientProbe.size(client, width, height);
        var options =
            new ClientFormOptions<Profile>(FormPresentation.EMPTY, Map.of())
                .withComponent(
                    ART_ROLE.field(),
                    (scope, parts) -> {
                      fieldFactories++;
                      scope.onRetire(() -> fieldRetirements++);
                      originalParts = parts;
                      fieldScope = scope;
                      unrelatedPresentation =
                          scope.local(
                              new ViewStateKey<>(
                                  DocumentId.parse("fixture:unrelated_presentation"),
                                  ValueTypes.bool()),
                              false);
                      expanded =
                          scope.local(
                              new ViewStateKey<>(
                                  DocumentId.parse("fixture:inline_picker_open"),
                                  ValueTypes.bool()),
                              false);
                      manualExpanded =
                          scope.local(
                              new ViewStateKey<>(
                                  DocumentId.parse("fixture:manual_id_open"), ValueTypes.bool()),
                              false);
                      inlineScope = scope;
                      inline =
                          ReferencePicker.open(
                              scope,
                              parts.binding(),
                              DEFINITION,
                              SOURCE,
                              this::source,
                              List.of(MODEL_ROLE, ART_ROLE),
                              Map.of("artwork", parts.frontend()),
                              POLICY,
                              clock::get);
                      suggestions = inline.suggestions(ReferenceSuggestions.Policy.STANDARD);
                      var choices =
                          Views.disclosure(
                              "inline",
                              UiBindings.local(expanded),
                              DisclosureParts.retained(
                                  UiText.of(text("Artwork choices")),
                                  UiText.of(Component.empty()),
                                  UiText.of(Component.empty()),
                                  branch -> {
                                    return ReferencePickerView.build(
                                        branch, inline, presentation(), Optional.of(ART_ROLE));
                                  }));
                      return Views.column(
                          "artwork",
                          List.of(
                              parts.label("label"),
                              parts.description("description"),
                              parts.controlSurface(
                                  "control",
                                  choices,
                                  UiBindings.local(manualExpanded),
                                  UiText.of(text("Artwork ID")),
                                  new CompletionParts(
                                      suggestions, UiText.of(text("Suggested artwork IDs")), 4)),
                              parts.feedback("feedback")));
                    });
        var form = settings.form(text("Profile selection"), options).atRoute(ROUTE);
        var layout =
            new ClientWorkflowLayout(
                List.of(
                    new ClientWorkflowLayout.Group(
                        List.of(
                            new ClientWorkflowLayout.Category(
                                TASK, text("Profiles"), Component.empty())),
                        List.of(),
                        List.of()),
                    new ClientWorkflowLayout.Group(
                        List.of(
                            new ClientWorkflowLayout.Category(
                                DocumentId.parse("fixture:picker_settings"),
                                text("Settings"),
                                Component.empty())),
                        List.of(
                            new ClientWorkflowLayout.Placement(
                                form.route(), form.route(), form.title(), Optional.empty())),
                        List.of())),
                List.of());
        var body =
            ClientCustomView.documentComponent(
                new ViewKey<>(
                    new DestinationId(TASK),
                    new ViewStateKey<>(
                        DocumentId.parse("fixture:picker_task_state"), ValueTypes.integer(0, 1))),
                0,
                UiLimits.STANDARD,
                form.route(),
                DEFINITION,
                (scope, admittedEditor, state) -> {
                  editor = admittedEditor;
                  taskScope = scope;
                  var modelInput =
                      ClientFieldBindings.field(
                          scope, admittedEditor, MODEL_ROLE.field(), MODELS.type());
                  var artworkInput =
                      ClientFieldBindings.field(
                          scope, admittedEditor, ART_ROLE.field(), ARTWORK.type());
                  task =
                      ReferencePicker.open(
                          scope,
                          admittedEditor.actions(),
                          SOURCE,
                          this::source,
                          List.of(MODEL_ROLE, ART_ROLE),
                          Map.of(
                              "models", modelInput.frontend(), "artwork", artworkInput.frontend()),
                          POLICY,
                          clock::get);
                  return ReferencePickerView.build(scope, task, presentation(), Optional.empty());
                });
        var metadata =
            ClientWorkspacePresentation.standard(
                    new WorkspaceIdentity(
                        DocumentId.parse("fixture:picker_workspace"),
                        text("Shared profile picker")))
                .withView(body);
        check(
            "picker declarations do not admit sources",
            metadata.validate(List.of(form), layout).valid() && factories == 0 + sourceReturns);
        workflow = ClientEditingWorkflow.open(parent, List.of(form), layout, metadata);
        NativeScreens.open(client, workflow.screen());
        frames = PlatformFrameObservations.sequence();
        stage = 1;
      }
      case 1 -> {
        if (PlatformFrameObservations.sequence() - frames < 8) return false;
        check(
            "unopened Home creates no picker source or document",
            factories == 0 + sourceReturns
                && task == null
                && inline == null
                && workflow.documents().stream()
                    .noneMatch(ClientEditingWorkflow.DocumentState::opened));
        open(new ClientDestination.View(new DestinationId(TASK)), 3);
      }
      case 3 -> {
        if (!arrived() || task == null || taskState().searching() != PickerState.Phase.READY)
          return false;
        check(
            "framework view admission supplies exact loaded retained owner",
            editor == workflow.editor(ROUTE)
                && editor.actions().definition() == DEFINITION
                && editor.view().availability()
                    == design.inherent.reform.owner.EditorActor.Availability.READY
                && inline == null);
        check(
            "task admits one actual bounded source search",
            factories == 1 + sourceReturns
                && searches.get() == 1
                && taskState().window().choices().size() == 4);
        check("native shared list gets focus", focus(workflow.screen(), list()));
        pointerBounds("afterFocus");
        frames = PlatformFrameObservations.sequence();
        stage = 32;
      }
      case 32 -> {
        if (PlatformFrameObservations.sequence() - frames < 4) return false;
        driver.capture("pickers-pointer.png", () -> stage = 33);
        stage = -1;
      }
      case 33 -> {
        pointerBounds("beforePointer");
        var nativeList = list();
        check(
            "native pointer reaches first shared choice",
            NativeClientProbe.pointerDown(
                workflow.screen(),
                nativeList.getX() + nativeList.getWidth() / 2.0,
                nativeList.getY() + nativeList.getHeight() / 8.0));
        NativeClientProbe.pointerUp(
            workflow.screen(),
            nativeList.getX() + nativeList.getWidth() / 2.0,
            nativeList.getY() + nativeList.getHeight() / 8.0);
        stage = 31;
      }
      case 31 -> {
        if (taskState().inspection().isEmpty()) return false;
        check(
            "resolved-empty profile remains inspectable and selectable",
            taskState().inspection().orElseThrow().key().equals(id(0))
                && taskState().inspection().orElseThrow().status()
                    == ReferenceResolution.Status.RESOLVED
                && button(workflow.screen(), "Use for Models").active
                && !editor.dirty());
        check("list focus remains after pointer inspection", focus(workflow.screen(), list()));
        key("end");
        key("enter");
        stage = 4;
      }
      case 4 -> {
        var state = taskState();
        if (state.inspection().isEmpty()
            || state.searching() != PickerState.Phase.READY
            || state.window().choices().size() < 8) return false;
        check(
            "task Enter pins inspection without staging",
            state.inspection().orElseThrow().key().equals(id(3))
                && raw("models").equals(id(0))
                && raw("artwork").equals(id(0))
                && !editor.dirty());
        pagingPass = 1;
        stage = 5;
      }
      case 5 -> {
        if (taskState().searching() != PickerState.Phase.READY) return false;
        check("list remains focused during actual source paging", list().isFocused());
        key("end");
        pagingPass++;
        stage = 6;
      }
      case 6 -> {
        if (taskState().searching() != PickerState.Phase.READY) return false;
        if (pagingPass < 3) {
          stage = 5;
          return false;
        }
        if (taskState().window().firstPage() == 0) return false;
        check(
            "scroll demand retains finite pages and pinned title",
            taskState().window().pageCount() == 3
                && taskState().window().choices().size() <= 12
                && taskState()
                    .inspection()
                    .orElseThrow()
                    .title()
                    .orElseThrow()
                    .equals("Profile 003"));
        press("Use for Models");
        stage = 7;
      }
      case 7 -> {
        if (editor.busy() || !raw("models").equals(id(3))) return false;
        check("explicit task action stages Models only", raw("artwork").equals(id(0)));
        unchanged();
        var input = search();
        check("native query gets focus", focus(workflow.screen(), input));
        input.setValue("Profile 0");
        stage = 8;
      }
      case 8 -> {
        if (!taskState().query().text().equals("Profile 0")) return false;
        baselineSearches = searches.get();
        var input = search();
        input.setValue("Profile 02");
        input.setCursorPosition(3);
        input.setHighlightPos(1);
        stage = 9;
      }
      case 9 -> {
        if (!taskState().query().text().equals("Profile 02")) return false;
        check(
            "query edit retains caret and selection without source entry",
            searches.get() == baselineSearches
                && taskState().query().caret() == 3
                && taskState().query().anchor() == 1);
        clock.set(199_999_999L);
        frames = PlatformFrameObservations.sequence();
        stage = 10;
      }
      case 10 -> {
        if (PlatformFrameObservations.sequence() - frames < 4) return false;
        check("200ms source deadline has not entered early", searches.get() == baselineSearches);
        clock.set(200_000_000L);
        stage = 11;
      }
      case 11 -> {
        if (taskState().searching() != PickerState.Phase.READY
            || taskState().window().choices().isEmpty()) return false;
        check(
            "actual query completes bounded empty scan pages",
            taskState().window().choices().getFirst().key().equals(id(20))
                && raw("models").equals(id(3))
                && raw("artwork").equals(id(0)));
        check(
            "filtered list gets native focus before return capture",
            focus(workflow.screen(), list()));
        key("end");
        stage = 111;
      }
      case 111 -> {
        var state = taskState();
        if (state.searching() != PickerState.Phase.READY || state.window().choices().size() != 5)
          return false;
        key("end");
        frames = PlatformFrameObservations.sequence();
        stage = 112;
      }
      case 112 -> {
        if (PlatformFrameObservations.sequence() - frames < 4) return false;
        returnBefore = taskState();
        returnList = list();
        returnQuery = search();
        returnAnchor = task.listAnchor().snapshot().value();
        returnDraft =
            design.inherent.reform.edit.DraftRevision.of(editor.view().snapshot().orElseThrow());
        returnBookmark = workflow.navigation().bookmark();
        check(
            "return capture separates actual scroll anchor active and pinned inspection",
            returnAnchor.equals(Optional.of(id(21)))
                && returnBefore.active().equals(Optional.of(id(24)))
                && returnBefore.inspection().orElseThrow().key().equals(id(3))
                && returnBefore.query().equals(new PickerState.Query("Profile 02", 3, 1))
                && returnList.isFocused()
                && returnQuery.getCursorPosition() == 3
                && returnQuery.getHighlighted().equals("ro"));
        driver.capture("pickers-task.png", () -> open(new ClientDestination.Home(), 12));
        stage = -1;
      }
      case 12 -> {
        if (!arrived()) return false;
        int beforeFactories = factories,
            beforeSearches = searches.get(),
            beforeLookups = lookups.get();
        taskScope.refreshObservations(); // The hidden host no longer drives this cached read.
        check(
            "hidden observation refresh performs no source work",
            factories == beforeFactories
                && searches.get() == beforeSearches
                && lookups.get() == beforeLookups);
        check(
            "Home suspends controller and withdraws source facts",
            taskState().source() == PickerState.Source.SUSPENDED
                && taskState().window().choices().isEmpty());
        baselineSearches = searches.get();
        frames = PlatformFrameObservations.sequence();
        stage = 13;
      }
      case 13 -> {
        if (PlatformFrameObservations.sequence() - frames < 8) return false;
        check(
            "hidden picker does no automatic search",
            searches.get() == baselineSearches && factories == 1 + sourceReturns);
        navigation =
            workflow.navigation().restore(returnBookmark).completion().toCompletableFuture();
        stage = 630;
      }
      case 630 -> {
        if (!arrived()
            || taskState().searching() != PickerState.Phase.READY
            || taskState().inspecting() != PickerState.Phase.READY) return false;
        var returned = taskState();
        var comparisons = new JsonObject();
        comparisons.addProperty("query", returned.query().equals(returnBefore.query()));
        comparisons.addProperty("stamp", returned.stamp().equals(returnBefore.stamp()));
        comparisons.addProperty("window", returned.window().equals(returnBefore.window()));
        comparisons.addProperty("active", returned.active().equals(returnBefore.active()));
        comparisons.addProperty(
            "inspection", returned.inspection().equals(returnBefore.inspection()));
        comparisons.addProperty(
            "anchor", task.listAnchor().snapshot().value().equals(returnAnchor));
        comparisons.addProperty(
            "newQueryGeneration", returned.queryGeneration() != returnBefore.queryGeneration());
        comparisons.addProperty(
            "draft",
            returnDraft.equals(
                design.inherent.reform.edit.DraftRevision.of(
                    editor.view().snapshot().orElseThrow())));
        comparisons.addProperty("owner", workflow.editor(ROUTE) == editor);
        returnFacts.add("comparisons", comparisons);
        returnFacts.addProperty("before", returnBefore.toString());
        returnFacts.addProperty("returned", returned.toString());
        returnFacts.addProperty("returnedAnchor", task.listAnchor().snapshot().value().orElse(""));

        check(
            "same-source return restores bounded window and distinct picker context",
            returned.query().equals(returnBefore.query())
                && returned.stamp().equals(returnBefore.stamp())
                && returned.window().equals(returnBefore.window())
                && returned.active().equals(returnBefore.active())
                && returned
                    .inspection()
                    .orElseThrow()
                    .equals(returnBefore.inspection().orElseThrow())
                && task.listAnchor().snapshot().value().equals(returnAnchor)
                && returned.queryGeneration() != returnBefore.queryGeneration()
                && returnDraft.equals(
                    design.inherent.reform.edit.DraftRevision.of(
                        editor.view().snapshot().orElseThrow()))
                && workflow.editor(ROUTE) == editor);
        sourceReturns++;
        frames = PlatformFrameObservations.sequence();
        stage = 631;
      }
      case 631 -> {
        if (PlatformFrameObservations.sequence() - frames < 4) return false;
        check(
            "native return retains exact list query controls focus caret and selection",
            list() == returnList
                && search() == returnQuery
                && list().isFocused()
                && returnQuery.getValue().equals("Profile 02")
                && returnQuery.getCursorPosition() == 3
                && returnQuery.getHighlighted().equals("ro")
                && factories == 1 + sourceReturns
                && raw("models").equals(id(3))
                && raw("artwork").equals(id(0)));
        returnFacts.addProperty("anchor", returnAnchor.orElseThrow());
        returnFacts.addProperty("active", taskState().active().orElseThrow());
        returnFacts.addProperty("inspected", taskState().inspection().orElseThrow().key());
        returnFacts.addProperty("query", taskState().query().toString());
        returnFacts.addProperty("window", taskState().window().toString());
        returnFacts.addProperty("sameControls", list() == returnList && search() == returnQuery);
        returnFacts.addProperty("listFocused", list().isFocused());
        unchanged();
        pickerReturnVerified = true;
        driver.capture(
            "pickers-task-returned.png", () -> open(new ClientDestination.Document(ROUTE), 14));
        stage = -1;
      }
      case 14 -> {
        if (!arrived() || !editor.canStage(ART_ROLE.field())) return false;
        check(
            "field uses same owner and collapsed source remains lazy",
            workflow.editor(ROUTE) == editor
                && inline != null
                && inlineState().source() == PickerState.Source.UNADMITTED
                && factories == 1 + sourceReturns);
        originalInput = originalParts.input().orElseThrow();
        check(
            "collapsed surface does not reveal the original native input",
            originalInput.state().snapshot() instanceof UiObservation.Unavailable<?>);
        frames = PlatformFrameObservations.sequence();
        stage = 141;
      }
      case 141 -> {
        // Let the host observe the newly admitted input subscription before using its controls.
        if (PlatformFrameObservations.sequence() - frames < 4) return false;
        // Change a separate presentation value after paint, before the real native gesture.
        // The disclosure's label, binding, availability and target remain unchanged.
        unrelatedPresentation.replace(true);
        press("Show Artwork choices");
        stage = 15;
      }
      case 15 -> {
        if (inline == null || inlineState().searching() != PickerState.Phase.READY) {
          if (inlineWaitFrame < 0) inlineWaitFrame = PlatformFrameObservations.sequence();
          if (PlatformFrameObservations.sequence() - inlineWaitFrame > 12) {
            driver.capture(
                "pickers-surface-diagnostic.png",
                () -> {
                  throw new AssertionError(
                      "Early inline diagnostic after accepted expansion gesture");
                });
            stage = -1;
          }
          return false;
        }
        check(
            "inline source uses original field input guard",
            originalParts.binding().identity().context().equals(editor.actions().context())
                && inlineState().requested().get("artwork").input().isPresent()
                && factories == 2 + sourceReturns);
        check(
            "unchanged disclosure accepts one gesture across unrelated presentation revision",
            unrelatedPresentation.snapshot().value() && expanded.snapshot().value());
        check("inline list gets keyboard focus", focus(workflow.screen(), list()));
        key("down");
        key("down");
        key("enter");
        stage = 16;
      }
      case 16 -> {
        if (editor.busy() || !raw("artwork").equals(id(1))) return false;
        check("inline Enter stages only its fixed Artwork role", raw("models").equals(id(3)));
        unchanged();
        driver.capture(
            "pickers-inline.png",
            () -> {
              NativeClientProbe.resize(client, 320, 240);
              stage = 17;
            });
        stage = -1;
      }
      case 17 -> {
        int[] w = {0}, h = {0};
        NativeClientProbe.size(client, w, h);
        if (w[0] != 320 || h[0] != 240) return false;
        check(
            "real compact window retains original owner and selection",
            workflow.editor(ROUTE) == editor
                && raw("artwork").equals(id(1))
                && expanded.snapshot().value());
        driver.capture(
            "pickers-narrow.png",
            () -> {
              NativeClientProbe.resize(client, width[0], height[0]);
              stage = 18;
            });
        stage = -1;
      }
      case 18 -> {
        int[] w = {0}, h = {0};
        NativeClientProbe.size(client, w, h);
        if (w[0] != width[0] || h[0] != height[0] || !editor.canSubmit()) return false;
        press("Show Artwork ID");
        stage = 24;
      }
      case 24 -> {
        if (!(originalInput.state().snapshot() instanceof UiObservation.Available<?>)) return false;
        manualWidget =
            widgets(workflow.screen()).stream()
                .filter(EditBox.class::isInstance)
                .map(EditBox.class::cast)
                .filter(widget -> widget.getMessage().getString().equals("Artwork"))
                .findFirst()
                .orElseThrow();
        check(
            "manual disclosure mounts the actual field input",
            focus(workflow.screen(), manualWidget));
        manualWidget.setValue("fixture:profile_00");
        stage = 240;
      }
      case 240 -> {
        if (!editor.canSubmit() || !raw("artwork").equals("fixture:profile_00")) return false;
        check("completion typing preserves the original field focus", manualWidget.isFocused());
        clock.addAndGet(200_000_000L);
        stage = 241;
      }
      case 241 -> {
        var offer = suggestions.state().snapshot().value();
        if (offer.phase() != PickerState.Phase.READY || offer.choices().size() != 4) return false;
        check(
            "completion uses bounded actual key-prefix source with no resolve or browsing query change",
            offer.prefix().equals("fixture:profile_00")
                && lookups.get() == 3
                && inlineState().query().text().isEmpty());
        check(
            "suggestions do not implicitly select or accept the first option",
            offer.active().isEmpty() && raw("artwork").equals("fixture:profile_00"));
        NativeClientProbe.key(workflow.screen(), "key.keyboard.enter", true);
        NativeClientProbe.key(workflow.screen(), "key.keyboard.enter", false);
        check(
            "Enter without an active suggestion follows field handling without acceptance",
            raw("artwork").equals("fixture:profile_00"));
        key("down");
        key("down");
        check(
            "native arrows select a suggestion while original input keeps focus",
            suggestions.activeOption().orElseThrow().key().equals(id(1))
                && manualWidget.isFocused());
        frames = PlatformFrameObservations.sequence();
        stage = 242;
      }
      case 242 -> {
        if (PlatformFrameObservations.sequence() - frames < 4) return false;
        driver.capture(
            "pickers-completion.png",
            () -> {
              key("enter");
              stage = 243;
            });
        stage = -1;
      }
      case 243 -> {
        if (!editor.canSubmit() || !raw("artwork").equals(id(1))) return false;
        check(
            "explicit completion acceptance uses original field and no save",
            manualWidget.getValue().equals(id(1))
                && raw("models").equals(id(3))
                && manualWidget.isFocused());
        unchanged();
        completionVerified = true;
        manualWidget.setValue("fixture:profile_00");
        manualWidget.setCursorPosition(3);
        manualWidget.setHighlightPos(1);
        stage = 244;
      }
      case 244 -> {
        if (!editor.canSubmit() || !raw("artwork").equals("fixture:profile_00")) return false;
        clock.addAndGet(200_000_000L);
        stage = 245;
      }
      case 245 -> {
        var offer = suggestions.state().snapshot().value();
        if (offer.phase() != PickerState.Phase.READY || offer.choices().size() != 4) return false;
        check(
            "actual root narration describes the focused completion offer",
            workflow.screen().getNarrationMessage().getString().contains("Suggested artwork IDs"));
        var revision =
            design.inherent.reform.edit.DraftRevision.of(editor.view().snapshot().orElseThrow());
        key("escape");
        check(
            "first Escape dismisses suggestions without closing or editing the field",
            editor.isOpen()
                && NativeScreens.current(client) == workflow.screen()
                && manualWidget.isFocused()
                && manualWidget.getValue().equals("fixture:profile_00")
                && design.inherent.reform.edit.DraftRevision.of(
                        editor.view().snapshot().orElseThrow())
                    .equals(revision)
                && suggestions.state().snapshot().value().choices().isEmpty());
        completionDismissed = true;
        key("down");
        clock.addAndGet(200_000_000L);
        stage = 246;
      }
      case 246 -> {
        if (suggestions.state().snapshot().value().phase() != PickerState.Phase.READY) return false;
        check(
            "explicit arrow reopens a dismissed suggestion offer",
            !suggestions.state().snapshot().value().choices().isEmpty());
        check(
            "another real field control takes native focus",
            focus(workflow.screen(), button(workflow.screen(), "Hide Artwork ID")));
        frames = PlatformFrameObservations.sequence();
        stage = 247;
      }
      case 247 -> {
        if (PlatformFrameObservations.sequence() - frames < 4) return false;
        check(
            "native blur withdraws suggestions without accepting",
            suggestions.state().snapshot().value().choices().isEmpty()
                && raw("artwork").equals("fixture:profile_00"));
        completionBlur = true;
        check("original input can regain native focus", focus(workflow.screen(), manualWidget));
        key("down");
        clock.addAndGet(200_000_000L);
        stage = 248;
      }
      case 248 -> {
        var offer = suggestions.state().snapshot().value();
        if (offer.phase() != PickerState.Phase.READY || offer.choices().size() != 4) return false;
        frames = PlatformFrameObservations.sequence();
        stage = 249;
      }
      case 249 -> {
        if (PlatformFrameObservations.sequence() - frames < 4) return false;
        manualWidget.setValue(id(19));
        // A pointer aimed at the still-painted old prefix offer must not replace this newer text.
        completionPointer(1);
        check(
            "stale completion pointer preserves newer native text",
            manualWidget.getValue().equals(id(19)));
        frames = PlatformFrameObservations.sequence();
        stage = 2490;
      }
      case 2490 -> {
        if (PlatformFrameObservations.sequence() - frames < 4) return false;
        driver.capture(
            "pickers-completion-stale.png",
            () -> {
              var report = editor.form().controlReport();
              var issue =
                  report.issues().stream()
                      .filter(
                          value ->
                              value.code().equals("field.input_stale")
                                  && value.path().equals(ART_ROLE.field()))
                      .findFirst();
              check(
                  "stale completion failure is available as actual typed field feedback",
                  issue.isPresent());
              check(
                  "stale completion cause remains available in actual root narration",
                  workflow
                      .screen()
                      .getNarrationMessage()
                      .getString()
                      .contains(issue.orElseThrow().fallback()));
              completionStaleFeedback = true;
              stage = 251;
            });
        stage = -1;
      }
      case 251 -> {
        if (!editor.canSubmit() || !raw("artwork").equals(id(19))) return false;
        check("stale completion pointer cannot stage its old choice", raw("models").equals(id(3)));
        completionStale = true;
        manualWidget.setValue("fixture:profile_00");
        manualWidget.setCursorPosition(3);
        manualWidget.setHighlightPos(1);
        stage = 252;
      }
      case 252 -> {
        if (!editor.canSubmit() || !raw("artwork").equals("fixture:profile_00")) return false;
        key("down");
        clock.addAndGet(200_000_000L);
        stage = 253;
      }
      case 253 -> {
        if (suggestions.state().snapshot().value().phase() != PickerState.Phase.READY) return false;
        key("down");
        key("down");
        check(
            "reflow begins with the exact active suggestion and input selection",
            suggestions.activeOption().orElseThrow().key().equals(id(1))
                && manualWidget.getCursorPosition() == 3
                && originalInput.state().snapshot().value().anchor() == 1);
        reflowRevision =
            design.inherent.reform.edit.DraftRevision.of(editor.view().snapshot().orElseThrow());
        NativeClientProbe.resize(client, 320, 240);
        stage = 254;
      }
      case 254 -> {
        int[] w = {0}, h = {0};
        NativeClientProbe.size(client, w, h);
        if (w[0] != 320 || h[0] != 240) return false;
        frames = PlatformFrameObservations.sequence();
        stage = 255;
      }
      case 255 -> {
        if (PlatformFrameObservations.sequence() - frames < 4) return false;
        var input = originalInput.state().snapshot().value();
        check(
            "real compact completion return keeps original input focus raw caret and selection",
            input.raw().equals("fixture:profile_00")
                && input.caret() == 3
                && input.anchor() == 1
                && input.focused()
                && widgets(workflow.screen()).contains(manualWidget)
                && reflowRevision.equals(
                    design.inherent.reform.edit.DraftRevision.of(
                        editor.view().snapshot().orElseThrow())));
        driver.capture(
            "pickers-completion-narrow.png",
            () -> {
              NativeClientProbe.resize(client, width[0], height[0]);
              stage = 256;
            });
        stage = -1;
      }
      case 256 -> {
        int[] w = {0}, h = {0};
        NativeClientProbe.size(client, w, h);
        if (w[0] != width[0] || h[0] != height[0]) return false;
        var input = originalInput.state().snapshot().value();
        check(
            "completion wide return retains actual control owner draft and selection",
            input.focused()
                && input.raw().equals("fixture:profile_00")
                && input.caret() == 3
                && input.anchor() == 1
                && workflow.editor(ROUTE) == editor
                && reflowRevision.equals(
                    design.inherent.reform.edit.DraftRevision.of(
                        editor.view().snapshot().orElseThrow())));
        completionReflow = true;
        // Reopening is explicit if the compact root could not offer a popup at its input anchor.
        if (suggestions.state().snapshot().value().phase() != PickerState.Phase.READY) {
          key("down");
          clock.addAndGet(200_000_000L);
        }
        stage = 257;
      }
      case 257 -> {
        if (suggestions.state().snapshot().value().phase() != PickerState.Phase.READY) return false;
        frames = PlatformFrameObservations.sequence();
        stage = 258;
      }
      case 258 -> {
        if (PlatformFrameObservations.sequence() - frames < 4) return false;
        driver.capture(
            "pickers-completion-returned.png",
            () -> {
              completionPointer(1);
              stage = 250;
            });
        stage = -1;
      }
      case 250 -> {
        if (!editor.canSubmit() || !raw("artwork").equals(id(1))) return false;
        check(
            "pointer completion stages only Artwork and retains original input focus",
            manualWidget.isFocused() && raw("models").equals(id(3)));
        completionPointer = true;
        unchanged();
        manualWidget.setValue("fixture:unlisted");
        manualWidget.setCursorPosition(3);
        manualWidget.setHighlightPos(1);
        stage = 25;
      }
      case 25 -> {
        if (!editor.canSubmit() || !raw("artwork").equals("fixture:unlisted")) return false;
        var state = originalInput.state().snapshot().value();
        if (!state.raw().equals("fixture:unlisted") || state.caret() != 3 || state.anchor() != 1)
          return false;
        hiddenStamp = state.stamp();
        manualRevision =
            design.inherent.reform.edit.DraftRevision.of(editor.view().snapshot().orElseThrow());
        check(
            "manual ID edits only its real draft and never resolves on typing",
            raw("models").equals(id(3)) && lookups.get() == 3);
        UiBindings.local(manualExpanded).changes().invoke(false);
        // This still-painted Hide offer is now obsolete. It must not toggle the new closed state.
        press("Hide Artwork ID");
        frames = PlatformFrameObservations.sequence();
        stage = 26;
      }
      case 26 -> {
        if (PlatformFrameObservations.sequence() - frames < 4) return false;
        check(
            "obsolete disclosure offer cannot reopen a changed control",
            !manualExpanded.snapshot().value());
        var hiddenResult =
            originalInput
                .replacements()
                .invoke(new ClientTextInput.Replacement(hiddenStamp, 0, 0, "late", 0, 0));
        check(
            "hidden canonical input is withdrawn and old raw command refuses",
            originalInput.state().snapshot() instanceof UiObservation.Unavailable<?>
                && hiddenResult instanceof UiActionResult.Refused refused
                && refused.report().issues().getFirst().code().equals("field.input_unavailable"));
        check(
            "collapse preserves whole draft and revision",
            manualRevision.equals(
                    design.inherent.reform.edit.DraftRevision.of(
                        editor.view().snapshot().orElseThrow()))
                && raw("artwork").equals("fixture:unlisted"));
        press("Show Artwork ID");
        stage = 27;
      }
      case 27 -> {
        if (!(originalInput.state().snapshot() instanceof UiObservation.Available<?>)) return false;
        var state = originalInput.state().snapshot().value();
        check(
            "reopening restores original raw caret selection and control identity",
            state.raw().equals("fixture:unlisted")
                && state.caret() == 3
                && state.anchor() == 1
                && widgets(workflow.screen()).contains(manualWidget));
        manualWidget.setValue(id(1));
        stage = 28;
      }
      case 28 -> {
        if (!editor.canSubmit() || !raw("artwork").equals(id(1))) return false;
        unchanged();
        undoRemaining = 10;
        pending =
            editor
                .actions()
                .undo(OperationDescriptor.EntryPoint.GUI)
                .completion()
                .toCompletableFuture();
        stage = 19;
      }
      case 19 -> {
        if (!pending.isDone() || !editor.canSubmit()) return false;
        pending.join();
        if (--undoRemaining == 0) {
          stage = 20;
          return false;
        }
        pending =
            editor
                .actions()
                .undo(OperationDescriptor.EntryPoint.GUI)
                .completion()
                .toCompletableFuture();
      }
      case 20 -> {
        if (!pending.isDone() || !editor.canSubmit()) return false;
        pending.join();
        check(
            "original owner Undo restores both draft roles without saving",
            raw("models").equals(id(0)) && raw("artwork").equals(id(0)) && !editor.dirty());
        discardedEditor = editor;
        var previousTaskScope = taskScope;
        workflow.discard(ROUTE);
        check(
            "discard ends document-associated component and field scopes",
            !discardedEditor.isOpen()
                && !previousTaskScope.isOpen()
                && previousTaskScope.usage().stateBytes() == 0
                && !inlineScope.isOpen());
        open(new ClientDestination.View(new DestinationId(TASK)), 23);
      }
      case 23 -> {
        if (!arrived() || taskState().searching() != PickerState.Phase.READY) return false;
        check(
            "fresh view reopens one fresh owner and default component state",
            editor != discardedEditor
                && editor == workflow.editor(ROUTE)
                && raw("models").equals(id(0))
                && raw("artwork").equals(id(0))
                && taskState().query().text().isEmpty()
                && factories == 3 + sourceReturns);
        workflow.close();
        check(
            "all picker scopes retire with actual workflow",
            !taskScope.isOpen()
                && taskScope.usage().stateBytes() == 0
                && fieldScope.usage().stateBytes() == 0);
        unchanged();
        NativeScreens.open(client, parent);
        var opened = standaloneScreen();
        check(
            "completion can use an actual standalone settings host",
            opened instanceof SettingsScreen<?>);
        standalone = (SettingsScreen<?>) opened;
        standaloneActions = standalone.editingActions();
        NativeScreens.open(client, standalone);
        stage = 260;
      }
      case 260 -> {
        if (standaloneParts == null
            || standalone.editingActions().view().availability()
                != design.inherent.reform.owner.EditorActor.Availability.READY) return false;
        standaloneInput = standaloneParts.input().orElseThrow();
        if (!(standaloneInput.state().snapshot() instanceof UiObservation.Available<?>))
          return false;
        standaloneWidget =
            widgets(standalone).stream()
                .filter(EditBox.class::isInstance)
                .map(EditBox.class::cast)
                .filter(widget -> widget.getMessage().getString().equals("Artwork"))
                .findFirst()
                .orElseThrow();
        check(
            "standalone completion borrows its actual actor and original input",
            standaloneParts
                    .binding()
                    .identity()
                    .context()
                    .equals(standalone.editingActions().context())
                && standaloneFactories == 1
                && factories == 3 + sourceReturns
                && focus(standalone, standaloneWidget));
        standaloneWidget.setValue("fixture:profile_00");
        stage = 261;
      }
      case 261 -> {
        if (!standaloneSettled() || !standaloneRaw("artwork").equals("fixture:profile_00"))
          return false;
        clock.addAndGet(200_000_000L);
        stage = 262;
      }
      case 262 -> {
        var offer = standaloneSuggestions.state().snapshot().value();
        if (offer.phase() != PickerState.Phase.READY || offer.choices().size() != 4) return false;
        standaloneKey("down");
        standaloneKey("down");
        check(
            "standalone arrows retain the original text focus",
            standaloneWidget.isFocused()
                && standaloneSuggestions.activeOption().orElseThrow().key().equals(id(1)));
        frames = PlatformFrameObservations.sequence();
        stage = 263;
      }
      case 263 -> {
        if (PlatformFrameObservations.sequence() - frames < 4) return false;
        driver.capture(
            "pickers-completion-standalone.png",
            () -> {
              withdrawnOption = standaloneSuggestions.activeOption().orElseThrow();
              withdrawnSearches = searches.get();
              withdrawnLookups = lookups.get();
              withdrawnFactories = factories;
              sourceReadable.set(false);
              completionPointer(standalone, standaloneWidget, 1);
              frames = PlatformFrameObservations.sequence();
              stage = 270;
            });
        stage = -1;
      }
      case 270 -> {
        if (PlatformFrameObservations.sequence() - frames < 4) return false;
        var offer = standaloneSuggestions.state().snapshot().value();
        check(
            "actual source READ loss withdraws suggestions without editing the original draft",
            offer.choices().isEmpty()
                && offer.stamp().isEmpty()
                && standaloneRaw("artwork").equals("fixture:profile_00")
                && standaloneRaw("models").equals(id(0))
                && searches.get() == withdrawnSearches
                && lookups.get() == withdrawnLookups
                && factories == withdrawnFactories);
        check(
            "actual READ refusal reaches original field feedback and root narration",
            standalone.controlReport().issues().stream()
                    .anyMatch(
                        issue ->
                            issue.code().equals("owner.permission")
                                && issue.path().equals(ART_ROLE.field()))
                && standalone.getNarrationMessage().getString().contains("permission"));
        sourceAuthorityFacts.addProperty("readyRefusal", standalone.controlReport().toString());
        sourceAuthorityFacts.addProperty(
            "readyNarration", standalone.getNarrationMessage().getString());
        sourceReadable.set(true);
        clock.addAndGet(1_000_000_000L);
        frames = PlatformFrameObservations.sequence();
        stage = 271;
      }
      case 271 -> {
        if (PlatformFrameObservations.sequence() - frames < 8) return false;
        check(
            "restoring source READ alone cannot revive suggestions or enter source work",
            standaloneSuggestions.state().snapshot().value().choices().isEmpty()
                && searches.get() == withdrawnSearches
                && lookups.get() == withdrawnLookups
                && factories == withdrawnFactories);
        try {
          standaloneSuggestions.requireChoice(withdrawnOption);
          throw new AssertionError("A withdrawn suggestion cannot regain acceptance authority");
        } catch (design.inherent.reform.owner.PolicyRefusal expected) {
          checks.add("withdrawn source option remains refused after READ restoration");
        }
        unchanged();
        completionReadWithdrawal = true;
        standaloneKey("down");
        clock.addAndGet(200_000_000L);
        stage = 272;
      }
      case 272 -> {
        var offer = standaloneSuggestions.state().snapshot().value();
        if (offer.phase() != PickerState.Phase.READY || offer.choices().size() != 4) return false;
        check(
            "explicit source recovery reuses one actual source service",
            factories == 4 + sourceReturns);
        searchGate = new SearchGate();
        standaloneWidget.setValue("fixture:profile_02");
        stage = 273;
      }
      case 273 -> {
        if (!standaloneSettled() || !standaloneRaw("artwork").equals("fixture:profile_02"))
          return false;
        clock.addAndGet(200_000_000L);
        stage = 274;
      }
      case 274 -> {
        if (searchGate.entered.getCount() != 0) return false;
        var offer = standaloneSuggestions.state().snapshot().value();
        if (offer.work().isEmpty()) return false;
        heldSearch = offer.work().orElseThrow().id();
        check(
            "native source search entered its actual worker before READ loss",
            offer.phase() == PickerState.Phase.RUNNING
                && sourceContext.outstandingCalls() > 0
                && searchGate.prefix.equals("fixture:profile_02")
                && !searchGate.thread.equals(Thread.currentThread().getName()));
        withdrawnSearches = searches.get();
        sourceReadable.set(false);
        frames = PlatformFrameObservations.sequence();
        stage = 275;
      }
      case 275 -> {
        if (PlatformFrameObservations.sequence() - frames < 4) return false;
        var offer = standaloneSuggestions.state().snapshot().value();
        check(
            "READ loss retains actual live drain facts without claiming unauthorized cancellation",
            offer.stamp().isEmpty()
                && offer.choices().isEmpty()
                && offer.work().isPresent()
                && offer.work().orElseThrow().id().equals(heldSearch)
                && !offer.work().orElseThrow().cancellationRequested()
                && offer.work().orElseThrow().report().isEmpty()
                && sourceContext.outstandingCalls() > 0
                && searchGate.exited.getCount() == 1);
        sourceAuthorityFacts.addProperty("withdrawnWork", offer.work().orElseThrow().toString());
        sourceAuthorityFacts.addProperty("withdrawnPhase", offer.phase().name());
        sourceAuthorityFacts.addProperty("withdrawnChoices", offer.choices().size());
        sourceAuthorityFacts.addProperty("withdrawnStampPresent", offer.stamp().isPresent());
        sourceAuthorityFacts.addProperty(
            "withdrawnOutstandingCalls", sourceContext.outstandingCalls());
        sourceReadable.set(true);
        standaloneWidget.setValue("fixture:profile_00");
        stage = 276;
      }
      case 276 -> {
        if (!standaloneSettled() || !standaloneRaw("artwork").equals("fixture:profile_00"))
          return false;
        clock.addAndGet(200_000_000L);
        frames = PlatformFrameObservations.sequence();
        stage = 277;
      }
      case 277 -> {
        if (PlatformFrameObservations.sequence() - frames < 4) return false;
        var offer = standaloneSuggestions.state().snapshot().value();
        check(
            "replacement suggestion query waits for actual entered source drain",
            offer.phase() == PickerState.Phase.DRAINING
                && offer.work().isPresent()
                && offer.work().orElseThrow().id().equals(heldSearch)
                && searches.get() == withdrawnSearches
                && searchGate.exited.getCount() == 1);
        sourceAuthorityFacts.addProperty("replacementPhase", offer.phase().name());
        sourceAuthorityFacts.addProperty("replacementWork", offer.work().orElseThrow().toString());
        sourceAuthorityFacts.addProperty(
            "replacementSourceEntriesWhileHeld", searches.get() - withdrawnSearches);
        searchGate.release.countDown();
        stage = 278;
      }
      case 278 -> {
        var offer = standaloneSuggestions.state().snapshot().value();
        if (offer.phase() != PickerState.Phase.READY || offer.choices().size() != 4) return false;
        check(
            "late denied source output cannot replace the fresh prefix result",
            searchGate.exited.getCount() == 0
                && offer.choices().stream()
                    .allMatch(choice -> choice.key().startsWith("fixture:profile_00"))
                && searches.get() == withdrawnSearches + 1
                && factories == 4 + sourceReturns);
        sourceAuthorityFacts.addProperty("freshPrefix", offer.prefix());
        sourceAuthorityFacts.addProperty("freshSourceEntries", searches.get() - withdrawnSearches);
        sourceAuthorityFacts.addProperty("freshChoices", offer.choices().toString());
        completionSourceDrain = true;
        standaloneKey("down");
        standaloneKey("down");
        standaloneKey("enter");
        stage = 264;
      }
      case 264 -> {
        if (!standaloneSettled() || !standaloneRaw("artwork").equals(id(1))) return false;
        check(
            "standalone completion stages only its original field without persistence",
            standaloneRaw("models").equals(id(0))
                && standaloneWidget.isFocused()
                && factories == 4 + sourceReturns);
        unchanged();
        undoRemaining = 4;
        pending =
            standalone
                .editingActions()
                .undo(OperationDescriptor.EntryPoint.GUI)
                .completion()
                .toCompletableFuture();
        stage = 265;
      }
      case 265 -> {
        if (!pending.isDone() || !standaloneSettled()) return false;
        pending.join();
        if (--undoRemaining > 0) {
          pending =
              standalone
                  .editingActions()
                  .undo(OperationDescriptor.EntryPoint.GUI)
                  .completion()
                  .toCompletableFuture();
          return false;
        }
        check(
            "standalone original Undo restores the saved draft",
            standaloneRaw("artwork").equals(id(0))
                && standaloneRaw("models").equals(id(0))
                && !standalone.editingActions().view().dirty());
        var actions = standalone.editingActions();
        var done = button(standalone, "Done");
        check("standalone native Done gets focus", focus(standalone, done));
        standaloneKey("enter");
        check(
            "standalone native close retires its scopes and only its actual actor",
            !actions.isOpen()
                && !standaloneScope.isOpen()
                && standaloneScope.usage().stateBytes() == 0
                && standaloneCloses == 1
                && standaloneFactories == 1
                && NativeScreens.current(client) == parent);
        unchanged();
        standaloneCompletion = true;
        complete = true;
        return true;
      }
      default -> {}
    }
    return false;
  }

  @Override
  public String diagnostic() {
    return "shared picker stage=" + stage;
  }

  @Override
  public JsonObject report() {
    var out = new JsonObject();
    out.addProperty("status", complete ? "PASS" : "RUNNING");
    out.addProperty("passed", complete);
    out.addProperty("sourceUnchanged", complete);
    out.addProperty("scopesRetired", complete);
    out.addProperty("pickerReturnVerified", pickerReturnVerified);
    out.add("pickerReturn", returnFacts.deepCopy());
    out.addProperty("completionVerified", completionVerified);
    out.addProperty("completionDismissed", completionDismissed);
    out.addProperty("completionBlur", completionBlur);
    out.addProperty("completionPointer", completionPointer);
    out.addProperty("completionStale", completionStale);
    out.addProperty("completionStaleFeedback", completionStaleFeedback);
    out.addProperty("completionReflow", completionReflow);
    out.addProperty("standaloneCompletion", standaloneCompletion);
    out.addProperty("completionReadWithdrawal", completionReadWithdrawal);
    out.addProperty("completionSourceDrain", completionSourceDrain);
    out.add("sourceAuthority", sourceAuthorityFacts.deepCopy());
    if (sourceContext != null)
      out.addProperty("sourceOutstandingCalls", sourceContext.outstandingCalls());
    if (standalone != null && standaloneScope != null && standaloneScope.isOpen()) {
      out.addProperty("standaloneCodes", standalone.controlReport().toString());
      out.addProperty("standaloneNarration", standalone.getNarrationMessage().getString());
      out.addProperty(
          "standaloneSuggestions", standaloneSuggestions.state().snapshot().value().toString());
    }
    if (searchGate != null) {
      out.addProperty("heldSourceThread", searchGate.thread);
      out.addProperty("heldSourcePrefix", searchGate.prefix);
      out.addProperty("heldSourceEntered", searchGate.entered.getCount() == 0);
      out.addProperty("heldSourceExited", searchGate.exited.getCount() == 0);
    }
    out.addProperty("standaloneFactories", standaloneFactories);
    out.addProperty("standaloneCloses", standaloneCloses);
    out.addProperty("factories", factories);
    out.addProperty("searches", searches.get());
    out.addProperty("lookups", lookups.get());
    out.addProperty("stage", stage);
    out.addProperty("fieldFactories", fieldFactories);
    out.addProperty("fieldRetirements", fieldRetirements);
    out.addProperty("fieldScopeOpen", fieldScope != null && fieldScope.isOpen());
    if (fieldScope != null && fieldScope.isOpen()) {
      if (unrelatedPresentation != null)
        out.addProperty("unrelatedPresentation", unrelatedPresentation.snapshot().value());
      if (expanded != null) out.addProperty("inlineExpanded", expanded.snapshot().value());
      if (manualExpanded != null)
        out.addProperty("manualExpanded", manualExpanded.snapshot().value());
      out.addProperty("fieldKernelRevision", fieldScope.revision());
    }
    if (navigation != null && navigation.isDone()) {
      var result = navigation.join();
      out.addProperty("navigationOutcome", result.outcome().name());
      out.addProperty("navigationReport", result.report().toString());
      result.cause().ifPresent(cause -> out.addProperty("navigationCause", cause.toString()));
    }
    out.add("pointerBounds", pointerFacts.deepCopy());
    if (editor != null && editor.isOpen()) {
      out.addProperty(
          "staleCompletionCodes",
          editor.form().controlReport().issues().stream()
              .map(issue -> issue.code() + "@" + issue.path())
              .collect(java.util.stream.Collectors.joining(",")));
      out.addProperty("actorAvailability", editor.view().availability().name());
      out.addProperty("canSubmit", editor.canSubmit());
      out.addProperty("busy", editor.busy());
    }
    if (task != null && task.scope().isOpen())
      try {
        var observed = taskState();
        out.addProperty("taskSource", observed.source().name());
        out.addProperty("taskSearch", observed.searching().name());
        out.addProperty("active", observed.active().orElse(""));
        out.addProperty("inspected", observed.inspectionRequest().orElse(""));
        out.addProperty("windowItems", observed.window().choices().size());
        out.addProperty("windowFirst", observed.window().firstPage());
      } catch (design.inherent.reform.owner.PolicyRefusal refused) {
        out.addProperty("observationRefusal", refused.report().issues().getFirst().code());
      }
    if (inline != null && inline.scope().isOpen()) {
      var observed = inlineState();
      out.addProperty("inlineActive", observed.active().orElse(""));
      out.addProperty("inlineSource", observed.source().name());
      out.addProperty("inlineSearch", observed.searching().name());
      out.addProperty("inlineInspecting", observed.inspecting().name());
      out.addProperty("inlineInspected", observed.inspectionRequest().orElse(""));
      out.addProperty("inlineArtwork", raw("artwork"));
      out.addProperty("inlineRequested", observed.requested().get("artwork").toString());
      out.addProperty("inlineAccept", inline.activate(ART_ROLE).availability().toString());
      out.addProperty("inlineReport", observed.report().toString());
      out.addProperty("inlineScreenNarration", workflow.screen().getNarrationMessage().getString());
    }
    out.add("checks", checks.deepCopy());
    return out;
  }

  @Override
  public void close() {
    if (searchGate != null) searchGate.release.countDown();
    if (standaloneActions != null && standaloneActions.isOpen()) standaloneActions.close();
    if (workflow != null && workflow.isOpen()) workflow.close();
    if (sourceContext != null && sourceContext.isOpen()) sourceContext.close();
    if (width[0] > 0) NativeClientProbe.resize(client, width[0], height[0]);
    NativeScreens.open(client, parent);
  }
}
