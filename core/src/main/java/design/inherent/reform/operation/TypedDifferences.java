package design.inherent.reform.operation;

import com.google.gson.JsonElement;
import com.mojang.serialization.JsonOps;
import design.inherent.reform.definition.FieldPath;
import design.inherent.reform.owner.PolicyRefusal;
import design.inherent.reform.value.DefaultedType;
import design.inherent.reform.value.ValueShape;
import design.inherent.reform.value.ValueType;
import java.util.ArrayList;
import java.util.List;

/** Shared semantic comparison. Collection changes stay whole-field without persistent row IDs. */
final class TypedDifferences {
  private final List<DocumentActions.Change> changes = new ArrayList<>();
  private final int maximumChanges;
  private final int maximumCharacters;
  private long characters;

  private TypedDifferences(int maximumChanges, int maximumCharacters) {
    if (maximumChanges < 1 || maximumCharacters < 1)
      throw new IllegalArgumentException("Comparison bounds must be positive");
    this.maximumChanges = maximumChanges;
    this.maximumCharacters = maximumCharacters;
  }

  static <T> List<DocumentActions.Change> between(
      ValueType<T> type, T before, T after, int maximumChanges, int maximumCharacters) {
    var result = new TypedDifferences(maximumChanges, maximumCharacters);
    var first = type.codec().encodeStart(JsonOps.INSTANCE, before).getOrThrow();
    var second = type.codec().encodeStart(JsonOps.INSTANCE, after).getOrThrow();
    result.visit(type, FieldPath.ROOT, first, second);
    return List.copyOf(result.changes);
  }

  private static <V> boolean equivalent(ValueType<V> type, JsonElement first, JsonElement second) {
    return type.equivalent(value(type, first), value(type, second));
  }

  private static <V> V value(ValueType<V> type, JsonElement encoded) {
    if (encoded == null && type instanceof DefaultedType<V> defaults) return defaults.fallback();
    if (encoded == null) throw new IllegalStateException("A required comparison field is missing");
    var decoded = type.decode(JsonOps.INSTANCE, encoded, FieldPath.ROOT);
    if (decoded.value().isEmpty() || !decoded.report().valid())
      throw new IllegalStateException("Previously validated comparison could not decode");
    return decoded.value().orElseThrow();
  }

  private static <V> String json(ValueType<V> type, JsonElement encoded) {
    return encoded == null
        ? type.codec().encodeStart(JsonOps.INSTANCE, value(type, null)).getOrThrow().toString()
        : encoded.toString();
  }

  private static <V>
      java.util.Optional<design.inherent.reform.presentation.ChoiceDisplay.Caption> choiceCaption(
          ValueType<V> type, JsonElement encoded) {
    if (encoded == null) return java.util.Optional.empty();
    var shape = type.shape();
    for (int depth = 0; depth < 128; depth++) {
      if (shape instanceof ValueShape.Defaulted<?> wrapped) shape = wrapped.element().shape();
      else if (shape instanceof ValueShape.OptionalValue<?> wrapped)
        shape = wrapped.element().shape();
      else break;
    }
    if (!(shape instanceof ValueShape.Scalar scalar) || scalar.choiceDisplay().isEmpty())
      return java.util.Optional.empty();
    var draft =
        design.inherent.reform.edit.DraftTree.from(
            type, value(type, encoded), design.inherent.reform.edit.DraftLimits.CONFIGURATION);
    return design.inherent.reform.presentation.ChoicePresentation.caption(type, draft.root());
  }

  private static long captionCost(
      java.util.Optional<design.inherent.reform.presentation.ChoiceDisplay.Caption> caption) {
    return caption
        .map(
            value ->
                (long) value.value().length()
                    + value.translationKey().length()
                    + value.fallback().length())
        .orElse(0L);
  }

  private void visit(ValueType<?> type, FieldPath path, JsonElement first, JsonElement second) {
    if (equivalent(type, first, second)) return;
    if (type.shape() instanceof ValueShape.Alternative alternative) {
      String before = first.getAsJsonObject().get(alternative.discriminator()).getAsString();
      String after = second.getAsJsonObject().get(alternative.discriminator()).getAsString();
      if (before.equals(after)) {
        visit(
            alternative.variant(before).orElseThrow().type(),
            path.variant(before),
            first.getAsJsonObject().get(alternative.payload()),
            second.getAsJsonObject().get(alternative.payload()));
        return;
      }
    }
    if (type.shape() instanceof ValueShape.ObjectValue object) {
      for (var field : object.fields())
        visit(
            field.type(),
            path.field(field.id()),
            first.getAsJsonObject().get(field.id()),
            second.getAsJsonObject().get(field.id()));
    } else {
      String before = json(type, first), after = json(type, second);
      var beforeCaption = choiceCaption(type, first);
      var afterCaption = choiceCaption(type, second);
      long next =
          characters
              + path.toString().length()
              + before.length()
              + (long) after.length()
              + captionCost(beforeCaption)
              + captionCost(afterCaption);
      if (changes.size() >= maximumChanges || next > maximumCharacters)
        throw new PolicyRefusal(
            "compare.limit", "The complete comparison exceeds its declared bounds");
      characters = next;
      changes.add(new DocumentActions.Change(path, before, after, beforeCaption, afterCaption));
    }
  }
}
