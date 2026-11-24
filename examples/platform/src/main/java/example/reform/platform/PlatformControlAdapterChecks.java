package example.reform.platform;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import design.inherent.reform.definition.FieldPath;
import design.inherent.reform.edit.DraftNode;
import design.inherent.reform.minecraft.client.*;
import design.inherent.reform.owner.EditorActor;
import design.inherent.reform.owner.PolicyRefusal;
import design.inherent.reform.presentation.FormGeometry;
import design.inherent.reform.presentation.FormPresentation;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletionStage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** Real replacements and nested native wrappers over the retained production form pipeline. */
final class PlatformControlAdapterChecks implements PlatformFormProbe.Scene {
  private static final FieldPath LABEL = FieldPath.ROOT.field("label");
  private static final FieldPath DISTANCE = FieldPath.ROOT.field("distance");
  private final Minecraft client = Minecraft.getInstance();
  private final Screen parent = NativeScreens.current(client);
  private final PlatformFormProbe.Driver driver;
  private final JsonArray checks = new JsonArray();
  private ClientEditor<?> editor;
  private ClientControlAdapter.Binding labelBinding, distanceBinding, retiredBinding;
  private SelectionBox replacement;
  private NativeControlFrame frame;
  private int stage, creates, disposals, labelEdits;
  private long initialVersion;
  private final int[] originalWidth = {0}, originalHeight = {0};
  private byte[] before;
  private Object ownerActions;
  private design.inherent.reform.edit.DraftRevision revision;
  private boolean failFactory, failDisposal;
  private final RuntimeException factoryFailure =
      new IllegalStateException("controlled control factory failure");
  private final RuntimeException disposalFailure =
      new IllegalStateException("controlled control disposal failure");

  static CompletionStage<JsonObject> run(ClientRegistration registration) {
    return PlatformFormProbe.run(
        registration, "CUSTOM_CONTROLS", PlatformControlAdapterChecks::new);
  }

  private PlatformControlAdapterChecks(PlatformFormProbe.Driver driver) {
    this.driver = driver;
  }

  private static final class SelectionBox extends EditBox {
    int anchor;

    SelectionBox(net.minecraft.client.gui.Font font, Component label) {
      super(font, 0, 0, 180, 20, label);
    }

    @Override
    public void setHighlightPos(int value) {
      super.setHighlightPos(value);
      anchor = Math.clamp(value, 0, getValue().length());
    }
  }

  private java.nio.file.Path file() {
    return client
        .gameDirectory
        .toPath()
        .resolve("config/reform_platform_example/workflow_preferences.json");
  }

  private void check(String label, boolean value) {
    if (!value) throw new AssertionError(label);
    checks.add(label);
  }

  private String raw(FieldPath path) {
    return ((DraftNode.Scalar) editor.view().snapshot().orElseThrow().draft().at(path)).raw();
  }

  private design.inherent.reform.edit.DraftRevision revision() {
    return design.inherent.reform.edit.DraftRevision.of(editor.view().snapshot().orElseThrow());
  }

  private EditBox nested() {
    return (EditBox) distanceBinding.nativeControl();
  }

  private ClientControlAdapter.Control replacement(ClientControlAdapter.Binding binding) {
    creates++;
    if (failFactory) throw factoryFailure;
    labelBinding = binding;
    var input = new SelectionBox(client.font, binding.label());
    replacement = input;
    input.setMaxLength(binding.state().maximumCharacters());
    input.setValue(binding.state().raw());
    binding
        .state()
        .selection()
        .ifPresent(
            value -> {
              input.setCursorPosition(value.cursor());
              input.setHighlightPos(value.anchor());
            });
    input.setResponder(
        value -> {
          labelEdits++;
          binding.editRaw(value);
        });
    return new ClientControlAdapter.Control(
        input,
        new FormGeometry.Size(180, 20),
        state -> {
          input.setMaxLength(state.maximumCharacters());
          if (!input.getValue().equals(state.raw())) input.setValue(state.raw());
          input.setEditable(state.editable());
        },
        () ->
            Optional.of(
                new ClientControlAdapter.Selection(input.getCursorPosition(), input.anchor)),
        () -> {
          disposals++;
          if (failDisposal) throw disposalFailure;
        });
  }

  private ClientControlAdapter.Control wrapper(ClientControlAdapter.Binding binding) {
    creates++;
    distanceBinding = binding;
    frame = new NativeControlFrame(binding.nativeControl());
    return new ClientControlAdapter.Control(
        frame,
        new FormGeometry.Size(188, 28),
        state -> {},
        binding::nativeSelection,
        () -> disposals++);
  }

  private ClientFormOptions<PlatformWorkflowExample.Preferences> options(
      FormPresentation presentation) {
    return new ClientFormOptions<PlatformWorkflowExample.Preferences>(presentation, Map.of())
        .withControl(LABEL, this::replacement)
        .withControl(DISTANCE, this::wrapper);
  }

  private void open(FormPresentation presentation) {
    editor =
        PlatformWorkflowExample.settings()
            .form(Component.literal("Custom native controls"), options(presentation))
            .open(parent);
    NativeScreens.open(client, editor.screen());
  }

  @Override
  public boolean tick() throws Exception {
    switch (stage) {
      case 0 -> {
        if (PlatformWorkflowExample.settings().runtime().isEmpty()) return false;
        before = Files.readAllBytes(file());
        NativeClientProbe.size(client, originalWidth, originalHeight);
        try {
          options(FormPresentation.EMPTY).withControl(LABEL, this::replacement);
          throw new AssertionError("Duplicate control admitted");
        } catch (IllegalArgumentException expected) {
          check("duplicate adapter declaration refuses before native factory entry", creates == 0);
        }
        open(FormPresentation.EMPTY);
        stage = 1;
      }
      case 1 -> {
        if (editor.view().availability() != EditorActor.Availability.READY
            || labelBinding == null
            || distanceBinding == null
            || editor.busy()
            || !labelBinding.state().editable()
            || !distanceBinding.state().editable()) return false;
        check(
            "actual scalar replacement and wrapper are mounted in the original form",
            editor.form().fieldControl(LABEL).orElseThrow() == replacement
                && editor.form().fieldControl(DISTANCE).orElseThrow() == frame);
        check(
            "custom controls expose resolved cached state without a second owner",
            labelBinding.state().raw().equals("Example")
                && distanceBinding.state().raw().equals("64")
                && editor.form().controlReport().valid());
        ownerActions = editor.form().editingActions();
        initialVersion = editor.view().snapshot().orElseThrow().version();
        nested().setValue("bad");
        replacement.setValue("Adapter");
        stage = 2;
      }
      case 2 -> {
        if (editor.busy()
            || !raw(DISTANCE).equals("bad")
            || !raw(LABEL).equals("Adapter")
            || editor.view().validation().valid()) return false;
        check(
            "one replacement input dispatches one existing edit, preserving an invalid sibling",
            editor.view().snapshot().orElseThrow().version() == initialVersion + 2
                && !editor.canSubmit());
        check(
            "guarded adapter edit refuses native input overflow before changing the draft",
            overflowRefused() && raw(LABEL).equals("Adapter"));
        check(
            "public field focus reaches the nested current native input",
            editor.form().focusField(DISTANCE) && frame.isFocused() && nested().isFocused());
        nested().setCursorPosition(1);
        nested().setHighlightPos(1);
        check(
            "native key is consumed by the nested input",
            NativeClientProbe.key(editor.screen(), "key.keyboard.right", true));
        NativeClientProbe.key(editor.screen(), "key.keyboard.right", false);
        check(
            "nested keyboard movement keeps its native cursor",
            nested().getCursorPosition() == 2 && frame.parentActivations == 0);
        nested().setCursorPosition(2);
        nested().setHighlightPos(0);
        revision = revision();
        driver.capture("control-custom-wide.png", () -> stage = 3);
        stage = 99;
      }
      case 3 -> {
        NativeClientProbe.resize(client, 320, 240);
        stage = 4;
      }
      case 4 -> {
        if (!window(320, 240)) return false;
        check(
            "real native resize retains the same custom widgets, actor, input and selection",
            editor.form().fieldControl(DISTANCE).orElseThrow() == frame
                && editor.form().fieldControl(LABEL).orElseThrow() == replacement
                && editor.form().editingActions() == ownerActions
                && revision().equals(revision)
                && raw(DISTANCE).equals("bad")
                && raw(LABEL).equals("Adapter")
                && nested().getCursorPosition() == 2
                && distanceBinding.nativeSelection().orElseThrow().anchor() == 0
                && nested().isFocused());
        check(
            "reflow constructs no second control or effect",
            creates == 2 && disposals == 0 && revision().equals(revision));
        driver.capture("control-custom-narrow.png", () -> stage = 5);
        stage = 99;
      }
      case 5 -> {
        NativeClientProbe.resize(client, originalWidth[0], originalHeight[0]);
        stage = 6;
      }
      case 6 -> {
        if (!window(originalWidth[0], originalHeight[0])) return false;
        check(
            "native return preserves invalid input and submission guard",
            raw(DISTANCE).equals("bad")
                && !editor.canSubmit()
                && nested().getCursorPosition() == 2
                && distanceBinding.nativeSelection().orElseThrow().anchor() == 0);
        distanceBinding.editRaw("64");
        labelBinding.editRaw("Example");
        stage = 7;
      }
      case 7 -> {
        if (!raw(DISTANCE).equals("64") || !raw(LABEL).equals("Example") || !editor.canSubmit())
          return false;
        check(
            "explicit repair uses the original draft and leaves the file untouched",
            editor.form().editingActions() == ownerActions
                && Arrays.equals(before, Files.readAllBytes(file())));
        retiredBinding = labelBinding;
        editor.close();
        check(
            "retiring the form disposes each custom control once",
            disposals == 2 && !retiredBinding.state().editable());
        check(
            "retired binding refuses without reviving an owner",
            refused(retiredBinding, "retired"));
        open(
            FormPresentation.EMPTY.withUnavailableFields(
                List.of(
                    new FormPresentation.UnavailableField(
                        LABEL,
                        "fixture.control.disabled",
                        "This field is unavailable in this context."))));
        stage = 8;
      }
      case 8 -> {
        if (editor.view().availability() != EditorActor.Availability.READY
            || editor.form().fieldControl(LABEL).isEmpty()) return false;
        var beforeRevision = revision();
        check(
            "disabled replacement cannot override cached availability or stage an edit",
            !labelBinding.state().editable()
                && refused(labelBinding, "blocked")
                && revision().equals(beforeRevision)
                && raw(LABEL).equals("Example"));
        editor.close();
        failFactory = true;
        open(FormPresentation.EMPTY);
        stage = 9;
      }
      case 9 -> {
        if (editor.view().availability() != EditorActor.Availability.READY
            || editor.form().fieldControl(LABEL).isEmpty()) return false;
        check(
            "factory failure retains the actual cause and a usable standard control",
            editor.form().controlFailure().orElseThrow() == factoryFailure
                && editor.form().controlReport().issues().stream()
                    .anyMatch(
                        issue ->
                            issue.code().equals("presentation.control_factory_failed")
                                && issue.path().equals(LABEL))
                && editor.form().fieldControl(LABEL).orElseThrow() instanceof EditBox);
        editor.close();
        failFactory = false;
        failDisposal = true;
        open(FormPresentation.EMPTY);
        stage = 10;
      }
      case 10 -> {
        if (editor.view().availability() != EditorActor.Availability.READY
            || editor.form().fieldControl(LABEL).isEmpty()) return false;
        int count = disposals;
        var form = editor.form();
        var binding = labelBinding;
        editor.close();
        check(
            "disposal failure preserves its cause while sibling disposal completes",
            disposals == count + 2
                && form.controlFailure().orElseThrow() == disposalFailure
                && form.controlReport().issues().stream()
                    .anyMatch(issue -> issue.code().equals("presentation.control_close_failed")));
        check("failed disposal does not restore editing authority", refused(binding, "late"));
        check(
            "all custom control cases leave the actual owner source unchanged",
            Arrays.equals(before, Files.readAllBytes(file())));
        NativeScreens.open(client, parent);
        stage = 11;
      }
      case 11 -> {
        return true;
      }
      case 99 -> {}
      default -> throw new AssertionError("Unexpected custom control stage");
    }
    return false;
  }

  private boolean overflowRefused() {
    try {
      labelBinding.editRaw("x".repeat(labelBinding.state().maximumCharacters() + 1));
      return false;
    } catch (PolicyRefusal expected) {
      return expected
          .report()
          .issues()
          .getFirst()
          .code()
          .equals("presentation.control_input_limit");
    }
  }

  private boolean refused(ClientControlAdapter.Binding binding, String raw) {
    try {
      binding.editRaw(raw);
      return false;
    } catch (PolicyRefusal expected) {
      return expected
          .report()
          .issues()
          .getFirst()
          .code()
          .equals("presentation.control_unavailable");
    }
  }

  private boolean window(int width, int height) {
    int[] w = {0}, h = {0};
    NativeClientProbe.size(client, w, h);
    return w[0] == width && h[0] == height;
  }

  @Override
  public String diagnostic() {
    return "custom control stage=" + stage + " creates=" + creates + " disposals=" + disposals;
  }

  @Override
  public JsonObject report() {
    var result = new JsonObject();
    result.addProperty("status", "PASS");
    result.add("checks", checks.deepCopy());
    result.addProperty("creates", creates);
    result.addProperty("disposals", disposals);
    result.addProperty("nativeResponderCalls", labelEdits);
    result.addProperty("initialActorVersion", initialVersion);
    if (editor != null && editor.isOpen()) {
      result.addProperty(
          "actualActorVersion", editor.view().snapshot().map(value -> value.version()).orElse(-1L));
      result.addProperty("busy", editor.busy());
      result.addProperty("canSubmit", editor.canSubmit());
    }
    return result;
  }

  @Override
  public void close() {
    if (editor != null) editor.close();
    NativeScreens.open(client, parent);
  }
}
