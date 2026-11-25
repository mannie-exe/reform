package design.inherent.reform.minecraft.client;

import design.inherent.reform.definition.FieldPath;
import design.inherent.reform.presentation.ui.FieldBinding;
import design.inherent.reform.presentation.ui.FieldFrontend;
import design.inherent.reform.presentation.ui.UiAvailability;
import design.inherent.reform.presentation.ui.UiScope;
import design.inherent.reform.value.ValueType;
import java.util.Optional;

/** Actual retained editor/input adapter; it creates no editor, draft, widget, or save path. */
public final class ClientFieldBindings {
  public static <T, V> FieldBinding<T, V> field(
      UiScope scope, ClientEditor<T> editor, FieldPath path, ValueType<V> exactType) {
    return FieldBinding.bind(
        scope,
        editor.actions(),
        path,
        exactType,
        new FieldFrontend() {
          public Optional<Input> input() {
            return editor.presentation().boundFieldInput(path);
          }

          public UiAvailability editing() {
            return editor.canStage(path)
                ? UiAvailability.READY
                : UiAvailability.blocked(
                    "field.input_unaccepted",
                    "Finish or repair the current input before choosing another value.");
          }

          public UiAvailability submission() {
            return editor.canSubmit()
                ? UiAvailability.READY
                : UiAvailability.blocked(
                    "field.submission_unavailable",
                    "Resolve the draft's pending input, validation, or review before saving.");
          }
        });
  }

  private ClientFieldBindings() {}
}
