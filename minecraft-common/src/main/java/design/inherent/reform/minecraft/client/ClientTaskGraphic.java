package design.inherent.reform.minecraft.client;

import design.inherent.reform.presentation.graphics.GraphicLimits;
import design.inherent.reform.value.SnapshotContext;
import design.inherent.reform.value.ValueType;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import net.minecraft.network.chat.Component;

/** Explicit provider and finite subject choices for a shared task's draft preview slot. */
public record ClientTaskGraphic<T, S>(
    Component title,
    GraphicLimits aggregateLimits,
    int maximumViews,
    ValueType<S> subjects,
    List<Choice<S>> choices,
    Factory<T, S> factory) {
  @FunctionalInterface
  public interface Factory<T, S> {
    /**
     * Return a lightweight provider. Resource capture, compilation and GPU allocation belong to
     * prepare.
     */
    ClientGraphicProvider<T, S> open(ClientViewContext scope, ClientEditor<T> editor);
  }

  public record Choice<S>(String id, Component label, S subject) {
    public Choice {
      if (!Objects.requireNonNull(id).matches("[a-z][a-z0-9._/-]{0,127}"))
        throw new IllegalArgumentException("Preview choices require stable bounded IDs");
      label = ClientReferenceTask.caption(label, 1024, false);
      Objects.requireNonNull(subject);
    }

    @Override
    public Component label() {
      return label.copy();
    }
  }

  public ClientTaskGraphic {
    title = ClientReferenceTask.caption(title, 1024, false);
    Objects.requireNonNull(aggregateLimits);
    Objects.requireNonNull(factory);
    Objects.requireNonNull(subjects);
    var ids = new HashSet<String>();
    if (maximumViews < 1
        || maximumViews > 512
        || choices.isEmpty()
        || choices.size() > 128
        || choices.stream().anyMatch(choice -> !ids.add(choice.id())))
      throw new IllegalArgumentException(
          "Preview views and subject choices require finite distinct admission");
    var snapshot = new SnapshotContext(SnapshotContext.Limits.CONFIGURATION);
    choices =
        choices.stream()
            .map(
                choice ->
                    new Choice<>(
                        choice.id(), choice.label(), subjects.freeze(choice.subject(), snapshot)))
            .toList();
  }

  @Override
  public Component title() {
    return title.copy();
  }

  @Override
  public List<Choice<S>> choices() {
    var snapshot = new SnapshotContext(SnapshotContext.Limits.CONFIGURATION);
    return choices.stream()
        .map(
            choice ->
                new Choice<>(
                    choice.id(), choice.label(), subjects.freeze(choice.subject(), snapshot)))
        .toList();
  }
}
