package design.inherent.reform.minecraft.client;

import design.inherent.reform.definition.DocumentDefinition;
import design.inherent.reform.definition.DocumentId;
import design.inherent.reform.definition.FieldPath;
import design.inherent.reform.operation.FieldInformation;
import design.inherent.reform.owner.EditorActor;
import design.inherent.reform.owner.PolicyRefusal;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.BiFunction;
import java.util.function.Function;
import net.minecraft.network.chat.Component;

/** Owner-supplied contextual facts, captured explicitly after the actual field read operation. */
public final class FormInformation<T> {
  /** Owner summaries may put raw values and source keys behind an explicit disclosure. */
  public enum TechnicalDetails {
    INLINE,
    DISCLOSURE
  }

  public record Fact(Component label, Component value) {
    public Fact {
      label = Objects.requireNonNull(label).copy();
      value = Objects.requireNonNull(value).copy();
    }

    @Override
    public Component label() {
      return label.copy();
    }

    @Override
    public Component value() {
      return value.copy();
    }
  }

  /** Actual document/field identity; a link does not open a source or grant access. */
  public record Related(DocumentId document, FieldPath path, Component title) {
    public Related {
      Objects.requireNonNull(document);
      Objects.requireNonNull(path);
      title = Objects.requireNonNull(title).copy();
    }

    @Override
    public Component title() {
      return title.copy();
    }
  }

  public record Subject(
      Component purpose,
      List<Fact> facts,
      List<Related> related,
      Optional<String> key,
      TechnicalDetails technicalDetails) {
    public static final Subject EMPTY =
        new Subject(Component.empty(), List.of(), List.of(), Optional.empty());

    public Subject(
        Component purpose, List<Fact> facts, List<Related> related, Optional<String> key) {
      this(purpose, facts, related, key, TechnicalDetails.INLINE);
    }

    public Subject {
      purpose = Objects.requireNonNull(purpose).copy();
      facts = List.copyOf(facts);
      related = List.copyOf(related);
      Objects.requireNonNull(key);
      Objects.requireNonNull(technicalDetails);
      if (facts.size() > 128
          || related.size() > 32
          || key.filter(value -> value.length() > 4096).isPresent())
        throw new IllegalArgumentException("Information requires bounded facts, links and keys");
      long characters = purpose.getString().length() + key.map(String::length).orElse(0);
      for (var fact : facts)
        characters += (long) fact.label().getString().length() + fact.value().getString().length();
      for (var link : related)
        characters +=
            (long) link.title().getString().length()
                + link.document().toString().length()
                + link.path().toString().length();
      if (characters > 32768)
        throw new IllegalArgumentException("Complete owner information exceeds its text limit");
    }

    @Override
    public Component purpose() {
      return purpose.copy();
    }

    /**
     * Diagnostic visibility does not affect observation, application warnings or read authority.
     */
    public Subject withTechnicalDetails(TechnicalDetails presentation) {
      return new Subject(purpose, facts, related, key, presentation);
    }
  }

  private final DocumentDefinition<T> definition;
  private final BiFunction<FieldInformation, EditorActor.View<T>, Subject> describe;

  private FormInformation(
      DocumentDefinition<T> definition,
      BiFunction<FieldInformation, EditorActor.View<T>, Subject> describe) {
    this.definition = Objects.requireNonNull(definition);
    this.describe = Objects.requireNonNull(describe);
  }

  /**
   * Called on the native owner thread for an explicit, currently read-authorized capture, never
   * during rendering/search. Use immutable declarations and cached owner observations; perform no
   * file IO, source writes or expensive validation here. Refresh captures new observations.
   */
  public static <T> FormInformation<T> of(
      DocumentDefinition<T> definition, Function<FieldInformation, Subject> describe) {
    Objects.requireNonNull(describe);
    return new FormInformation<>(definition, (information, editor) -> describe.apply(information));
  }

  /**
   * Typed cached authoring state at the same exact captured revision; no parser or actor lookup.
   */
  public static <T> FormInformation<T> of(
      DocumentDefinition<T> definition,
      BiFunction<FieldInformation, EditorActor.View<T>, Subject> describe) {
    return new FormInformation<>(definition, describe);
  }

  DocumentDefinition<T> definition() {
    return definition;
  }

  Subject capture(FieldInformation information, EditorActor.View<T> editor) {
    if (!information.target().document().equals(definition.id()))
      throw new IllegalArgumentException("Information belongs to another actual document");
    if (editor
        .snapshot()
        .filter(
            snapshot ->
                snapshot.definition() == definition && information.target().matches(snapshot))
        .isEmpty())
      throw new PolicyRefusal(
          "information.stale", "Refresh information for the current authoring state");
    return Objects.requireNonNull(describe.apply(information, editor));
  }
}
