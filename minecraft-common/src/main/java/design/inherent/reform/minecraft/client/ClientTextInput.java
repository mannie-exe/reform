package design.inherent.reform.minecraft.client;

import design.inherent.reform.definition.DocumentId;
import design.inherent.reform.definition.FieldPath;
import design.inherent.reform.owner.PolicyRefusal;
import design.inherent.reform.presentation.ui.*;
import design.inherent.reform.value.SnapshotContext;
import design.inherent.reform.value.SnapshotPolicy;
import java.lang.ref.WeakReference;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Borrows the form's actual text buffer and acknowledgment pipeline. There is no second input,
 * document writer, or save action. Poll the parent scope outside paint; close this binding when its
 * completion/input contribution is retired.
 */
public final class ClientTextInput implements AutoCloseable {
  /** Opaque, nonportable identity of one actual input and exact text/selection observation. */
  public static final class Stamp {
    final Object producer, control;
    final design.inherent.reform.edit.DraftRevision draft;
    final long epoch;
    final String raw;
    final int caret, anchor;

    Stamp(
        Object producer,
        Object control,
        design.inherent.reform.edit.DraftRevision draft,
        long epoch,
        String raw,
        int caret,
        int anchor) {
      this.producer = producer;
      this.control = control;
      this.draft = draft;
      this.epoch = epoch;
      this.raw = raw;
      this.caret = caret;
      this.anchor = anchor;
    }

    @Override
    public boolean equals(Object other) {
      return other instanceof Stamp s
          && producer == s.producer
          && control == s.control
          && epoch == s.epoch
          && draft.equals(s.draft)
          && raw.equals(s.raw)
          && caret == s.caret
          && anchor == s.anchor;
    }

    @Override
    public int hashCode() {
      return Objects.hash(
          System.identityHashCode(producer),
          System.identityHashCode(control),
          draft,
          epoch,
          raw,
          caret,
          anchor);
    }
  }

  /** Cached actual native state; editable is feedback, never a transferable permission grant. */
  public record State(
      Stamp stamp, String raw, int caret, int anchor, int maximumCharacters, boolean editable) {
    public State {
      Objects.requireNonNull(stamp);
      Objects.requireNonNull(raw);
    }
  }

  /**
   * Replace [start,end) in the stamped input and set the resulting caret/anchor. Indices are Java
   * UTF-16 offsets; splitting a surrogate pair is refused. No native truncation is used.
   */
  public record Replacement(
      Stamp expected, int start, int end, String text, int caret, int anchor) {
    public Replacement {
      Objects.requireNonNull(expected);
      Objects.requireNonNull(text);
    }
  }

  private final UiScope scope;
  private final FieldPath path;
  private final Object producer = new Object();
  private SettingsView<?> view;
  private final UiRead<State> state;
  private WeakReference<State> observed;
  private long revision;

  private ClientTextInput(UiScope parent, SettingsView<?> view, FieldPath path) {
    parent.requireCurrent();
    this.path = Objects.requireNonNull(path);
    this.view = view;
    scope = parent.child();
    try {
      var key = readKey(parent.limits().maximumStateBytes());
      scope.onRetire(
          () -> {
            this.view = null;
            observed = null;
          });
      state =
          scope.observe(
              key,
              () -> {
                try {
                  var current = this.view.textInput(path, producer);
                  if (observed == null || !current.equals(observed.get())) {
                    if (revision == Long.MAX_VALUE)
                      throw new PolicyRefusal(
                          "field.input_epoch_exhausted", "Reopen this input before further edits.");
                    revision++;
                  }
                  observed = new WeakReference<>(current);
                  return new UiObservation.Available<>(key.capture(current), revision);
                } catch (RuntimeException | Error unavailable) {
                  observed = null;
                  throw unavailable;
                }
              });
    } catch (RuntimeException | Error failed) {
      try {
        scope.close();
      } catch (RuntimeException | Error cleanup) {
        if (cleanup != failed) failed.addSuppressed(cleanup);
      }
      throw failed;
    }
  }

  static UiReadKey<State> readKey(long allowance) {
    return new UiReadKey<>(
        DocumentId.parse("reform:native_text_input"),
        SnapshotPolicy.immutable(),
        SnapshotContext.Limits.CONFIGURATION,
        allowance,
        value ->
            256L
                + 2L * value.raw().length()
                + 2L * value.stamp().draft.sourceRevision().length()
                + 2L * value.stamp().draft.document().toString().length());
  }

  public static ClientTextInput bind(UiScope scope, ClientEditor<?> editor, FieldPath path) {
    return new ClientTextInput(scope, editor.presentation(), path);
  }

  static ClientTextInput bind(UiScope scope, SettingsView<?> form, FieldPath path) {
    return new ClientTextInput(scope, form, path);
  }

  public UiRead<State> state() {
    return state;
  }

  public UiCommand<Replacement> replacements() {
    return new UiCommand<>() {
      public List<UiRead<?>> dependencies() {
        return List.of(state);
      }

      public UiAvailability availability() {
        return state.snapshot().value().editable()
            ? UiAvailability.READY
            : UiAvailability.blocked("field.input_unavailable", "This field input is unavailable.");
      }

      public UiActionResult invoke(Replacement request) {
        Objects.requireNonNull(request);
        var result = new UiActionResult[1];
        try {
          scope.update(() -> result[0] = view.replaceTextInput(path, producer, request));
          return result[0];
        } catch (PolicyRefusal refused) {
          return new UiActionResult.Refused(refused.report(), Optional.of(refused));
        }
      }
    };
  }

  @Override
  public void close() {
    scope.close();
  }
}
