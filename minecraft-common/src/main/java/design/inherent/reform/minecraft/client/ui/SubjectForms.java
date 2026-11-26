package design.inherent.reform.minecraft.client.ui;

import design.inherent.reform.definition.DocumentId;
import design.inherent.reform.owner.PolicyRefusal;
import design.inherent.reform.presentation.ViewStateKey;
import design.inherent.reform.presentation.ui.*;
import design.inherent.reform.value.*;
import java.util.*;

/** Independent typed preview controls over one complete local subject, never a settings draft. */
public final class SubjectForms {
  public sealed interface Control<S> permits Toggle, Select {
    String key();

    ViewSpec build(UiScope scope, UiLocal<S> state);
  }

  public record Toggle<S>(String key, UiText label, UiText help, UiLens<S, Boolean> lens)
      implements Control<S> {
    public Toggle {
      identity(key);
      Objects.requireNonNull(label);
      Objects.requireNonNull(help);
      Objects.requireNonNull(lens);
    }

    public ViewSpec build(UiScope scope, UiLocal<S> state) {
      var binding =
          UiBindings.lens(
              scope,
              state,
              new ViewStateKey<>(DocumentId.parse("reform:subject_toggle"), ValueTypes.bool()),
              lens);
      return Views.toggle(key, new ToggleParts(label, help, binding));
    }
  }

  public record Choice<V>(String key, UiText label, V value) {
    public Choice {
      identity(key);
      Objects.requireNonNull(label);
      Objects.requireNonNull(value);
    }
  }

  public record Select<S, V>(
      String key,
      UiText label,
      UiText help,
      ValueType<V> type,
      UiLens<S, V> lens,
      List<Choice<V>> choices,
      int visibleRows)
      implements Control<S> {
    public Select {
      identity(key);
      Objects.requireNonNull(label);
      Objects.requireNonNull(help);
      Objects.requireNonNull(type);
      Objects.requireNonNull(lens);
      var ids = new HashSet<String>();
      if (choices.isEmpty() || choices.size() > 128 || visibleRows < 1 || visibleRows > 12)
        throw new IllegalArgumentException("Subject choices require bounded list admission");
      var admission = new ViewStateKey<>(DocumentId.parse("reform:subject_choice"), type);
      choices =
          choices.stream()
              .map(
                  choice -> {
                    if (!ids.add(choice.key()))
                      throw new IllegalArgumentException("Duplicate subject choice");
                    return new Choice<>(
                        choice.key(), choice.label(), admission.capture(choice.value()).value());
                  })
              .toList();
    }

    @Override
    public List<Choice<V>> choices() {
      return choices.stream()
          .map(c -> new Choice<>(c.key(), c.label(), type.freeze(c.value())))
          .toList();
    }

    public ViewSpec build(UiScope scope, UiLocal<S> state) {
      var value =
          UiBindings.lens(
              scope,
              state,
              new ViewStateKey<>(DocumentId.parse("reform:subject_choice_value"), type),
              lens);
      var active =
          scope.local(
              new ViewStateKey<>(
                  DocumentId.parse("reform:subject_active_key"), ValueTypes.text(128)),
              "");
      UiRead<Optional<String>> activeRead =
          scope.derive(
              SubjectForms.key("active"),
              List.of(active),
              in -> Optional.of(in.value(active)).filter(text -> !text.isEmpty()));
      UiRead<Set<String>> selected =
          scope.derive(
              SubjectForms.key("selected"),
              List.of(value.value()),
              in -> {
                var current = in.value(value.value());
                var result = new HashSet<String>();
                for (var choice : choices)
                  if (type.equivalent(current, choice.value())) result.add(choice.key());
                return Set.copyOf(result);
              });
      var labels =
          choices.stream()
              .flatMap(choice -> choice.label().dependencies().stream())
              .distinct()
              .toList();
      UiRead<ChoiceListData> options =
          scope.derive(
              SubjectForms.key("options"),
              labels,
              in ->
                  new ChoiceListData(
                      "subject:" + key,
                      choices.stream()
                          .map(
                              choice ->
                                  new ChoiceListData.Item(
                                      choice.key(), choice.label().resolve().getString(), "", true))
                          .toList(),
                      OptionalLong.of(choices.size()),
                      false,
                      false));
      var activeBinding =
          new UiBinding<Optional<String>>() {
            public UiRead<Optional<String>> value() {
              return activeRead;
            }

            public UiCommand<Optional<String>> changes() {
              return new UiCommand<>() {
                public List<UiRead<?>> dependencies() {
                  return List.of(active);
                }

                public UiAvailability availability() {
                  scope.requireCurrent();
                  return UiAvailability.READY;
                }

                public UiActionResult invoke(Optional<String> id) {
                  if (id.isPresent() && choices.stream().noneMatch(c -> c.key().equals(id.get())))
                    return refused("This subject choice is unavailable.");
                  active.replace(id.orElse(""));
                  return new UiActionResult.Applied();
                }
              };
            }
          };
      var accept =
          new UiCommand<String>() {
            public List<UiRead<?>> dependencies() {
              return List.of(value.value(), state);
            }

            public UiAvailability availability() {
              return value.changes().availability();
            }

            public UiActionResult invoke(String id) {
              var choice = choices.stream().filter(c -> c.key().equals(id)).findFirst();
              return choice.isEmpty()
                  ? refused("This subject choice is unavailable.")
                  : value.changes().invoke(type.freeze(choice.get().value()));
            }
          };
      return Views.choiceList(
          key,
          new ChoiceListParts(
              label, help, options, activeBinding, selected, accept, visibleRows, 1));
    }
  }

  public static final class Form<S> {
    private final UiLocal<S> value;
    private final ViewSpec content;
    private final ValueType<S> type;

    private Form(UiLocal<S> value, ViewSpec content, ValueType<S> type) {
      this.value = value;
      this.content = content;
      this.type = type;
    }

    public UiRead<S> value() {
      return value;
    }

    public ValueType<S> type() {
      return type;
    }

    public ViewSpec content() {
      return content;
    }
  }

  public static <S> Form<S> of(
      UiScope parent, DocumentId id, ValueType<S> type, S initial, List<Control<S>> controls) {
    if (controls.isEmpty() || controls.size() > 16)
      throw new IllegalArgumentException("A subject needs one to sixteen declared controls");
    var keys = new HashSet<String>();
    for (var control : controls)
      if (!keys.add(control.key())) throw new IllegalArgumentException("Duplicate subject control");
    var scope = parent.child();
    try {
      var value = scope.local(new ViewStateKey<>(id, type), initial);
      var content =
          Views.column(
              "subject-controls",
              controls.stream().map(control -> control.build(scope.child(), value)).toList());
      return new Form<>(value, content, type);
    } catch (RuntimeException | Error failure) {
      scope.close();
      throw failure;
    }
  }

  private static <V> UiReadKey<V> key(String name) {
    return new UiReadKey<>(
        DocumentId.parse("reform:subject_" + name),
        SnapshotPolicy.immutable(),
        SnapshotContext.Limits.CONFIGURATION,
        65536,
        value -> {
          if (value instanceof ChoiceListData list)
            return 128L
                + list.items().stream()
                    .mapToLong(item -> 64L + 2L * (item.key().length() + item.title().length()))
                    .sum();
          if (value instanceof Set<?> set)
            return 64L + set.stream().mapToLong(item -> 32L + 2L * item.toString().length()).sum();
          return 512L;
        });
  }

  private static UiActionResult refused(String message) {
    return new UiActionResult.Refused(
        new PolicyRefusal("preview.subject_unavailable", message).report(), Optional.empty());
  }

  private static void identity(String key) {
    if (!Objects.requireNonNull(key).matches("[a-z][a-z0-9._/-]{0,127}"))
      throw new IllegalArgumentException("Subject controls need stable bounded keys");
  }

  private SubjectForms() {}
}
