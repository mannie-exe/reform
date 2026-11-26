package design.inherent.reform.minecraft.client;

import static org.junit.jupiter.api.Assertions.*;

import design.inherent.reform.definition.*;
import design.inherent.reform.minecraft.client.ui.*;
import design.inherent.reform.owner.OwnerContext;
import design.inherent.reform.presentation.ui.*;
import design.inherent.reform.value.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import net.minecraft.network.chat.Component;
import org.junit.jupiter.api.Test;

class SubjectFormsTest {
  record Subject(int container, boolean open, boolean looted) {}

  static final FieldKey<Integer> CONTAINER = FieldKey.of("container", ValueTypes.integer(0, 2));
  static final FieldKey<Boolean> OPEN = FieldKey.of("open", ValueTypes.bool()),
      LOOTED = FieldKey.of("looted", ValueTypes.bool());
  static final ValueType<Subject> TYPE =
      RecordType.<Subject>builder(v -> new Subject(v.get(CONTAINER), v.get(OPEN), v.get(LOOTED)))
          .field(CONTAINER, Subject::container)
          .field(OPEN, Subject::open)
          .field(LOOTED, Subject::looted)
          .build();

  static UiText text(String text) {
    return UiText.of(Component.literal(text));
  }

  static List<SubjectForms.Control<Subject>> controls() {
    return List.of(
        new SubjectForms.Select<>(
            "container",
            text("Container"),
            text(""),
            CONTAINER.type(),
            new UiLens<>(Subject::container, (s, v) -> new Subject(v, s.open(), s.looted())),
            List.of(
                new SubjectForms.Choice<>("chest", text("Chest"), 0),
                new SubjectForms.Choice<>("trapped", text("Trapped chest"), 1),
                new SubjectForms.Choice<>("barrel", text("Barrel"), 2)),
            3),
        new SubjectForms.Toggle<>(
            "open",
            text("Open"),
            text(""),
            new UiLens<>(Subject::open, (s, v) -> new Subject(s.container(), v, s.looted()))),
        new SubjectForms.Toggle<>(
            "looted",
            text("Looted"),
            text(""),
            new UiLens<>(Subject::looted, (s, v) -> new Subject(s.container(), s.open(), v))));
  }

  @Test
  void mountedListFocusDoesNotAcceptAndThreeIndependentControlsCoverTwelveSubjects() {
    try (var context =
        new OwnerContext(Runnable::run, Runnable::run, () -> {}, OwnerContext.Limits.STANDARD)) {
      var form = new AtomicReference<SubjectForms.Form<Subject>>();
      var spec = new AtomicReference<ViewSpec.Column>();
      try (var tree =
          MountedUiTree.open(
              ClientViewContext.borrow(context),
              UiLimits.STANDARD,
              (scope, props) -> {
                var built =
                    SubjectForms.of(
                        scope,
                        DocumentId.parse("test:subject"),
                        TYPE,
                        new Subject(0, false, false),
                        controls());
                form.set(built);
                spec.set((ViewSpec.Column) built.content());
                return built.content();
              },
              "fixed")) {
        var list = ((ViewSpec.ChoiceList) spec.get().children().get(0)).parts();
        var open = ((ViewSpec.Toggle) spec.get().children().get(1)).parts().binding();
        var looted = ((ViewSpec.Toggle) spec.get().children().get(2)).parts().binding();
        assertInstanceOf(
            UiActionResult.Applied.class, list.active().changes().invoke(Optional.of("barrel")));
        assertEquals(0, form.get().value().snapshot().value().container());
        var subjects = new HashSet<Subject>();
        for (int i = 0; i < 3; i++)
          for (boolean a : List.of(false, true))
            for (boolean b : List.of(false, true)) {
              assertInstanceOf(
                  UiActionResult.Applied.class,
                  list.accept().invoke(List.of("chest", "trapped", "barrel").get(i)));
              assertInstanceOf(UiActionResult.Applied.class, open.changes().invoke(a));
              assertInstanceOf(UiActionResult.Applied.class, looted.changes().invoke(b));
              assertEquals(new Subject(i, a, b), form.get().value().snapshot().value());
              subjects.add(form.get().value().snapshot().value());
              assertTrue(tree.refresh(false));
              assertTrue(tree.failure().isEmpty());
            }
        assertEquals(12, subjects.size());
        assertInstanceOf(UiActionResult.Refused.class, list.accept().invoke("unknown"));
        assertEquals(new Subject(2, true, true), form.get().value().snapshot().value());
        assertEquals(
            List.of(
                MountedUiTree.Kind.CHOICE_LIST,
                MountedUiTree.Kind.TOGGLE,
                MountedUiTree.Kind.TOGGLE),
            tree.snapshot().stream().map(MountedUiTree.Row::kind).toList());
      }
    }
  }
}
