package design.inherent.reform.operation;

import design.inherent.reform.edit.DraftNode;
import design.inherent.reform.owner.PolicyRefusal;
import design.inherent.reform.presentation.NumericDisplay;
import design.inherent.reform.presentation.NumericPresentation;
import design.inherent.reform.value.ValueType;
import java.util.Optional;

/** Complete bounded raw-input descriptions, including inactive data; never a saved-file codec. */
final class DraftDescriptions {
  private final int maximumNodes, maximumCharacters;
  private final String refusalCode, refusalMessage;
  private int nodes, characters;

  DraftDescriptions(
      int maximumNodes, int maximumCharacters, String refusalCode, String refusalMessage) {
    if (maximumNodes < 1 || maximumCharacters < 1)
      throw new IllegalArgumentException("Description bounds must be positive");
    this.maximumNodes = maximumNodes;
    this.maximumCharacters = maximumCharacters;
    this.refusalCode = java.util.Objects.requireNonNull(refusalCode);
    this.refusalMessage = java.util.Objects.requireNonNull(refusalMessage);
  }

  void account(String text) {
    if (text.length() > maximumCharacters - characters) throw exhausted();
    characters += text.length();
  }

  String render(DraftNode node) {
    var result = new StringBuilder();
    node(node, result);
    return result.toString();
  }

  Optional<NumericDisplay.Caption> caption(ValueType<?> type, DraftNode node) {
    var caption = NumericPresentation.caption(type, node);
    caption.ifPresent(
        value -> {
          account(value.value().toString());
          account(value.translationKey());
          account(value.fallback());
        });
    return caption;
  }

  Optional<design.inherent.reform.presentation.ChoiceDisplay.Caption> choiceCaption(
      ValueType<?> type, DraftNode node) {
    var caption = design.inherent.reform.presentation.ChoicePresentation.caption(type, node);
    caption.ifPresent(
        value -> {
          account(value.value());
          account(value.translationKey());
          account(value.fallback());
        });
    return caption;
  }

  private void node(DraftNode node, StringBuilder out) {
    if (++nodes > maximumNodes) throw exhausted();
    switch (node) {
      case DraftNode.Scalar scalar -> {
        quote(scalar.raw(), out);
        scalar
            .unitInput()
            .ifPresent(
                input -> {
                  append(" (unit input ", out);
                  quote(input.unit(), out);
                  append(": ", out);
                  quote(input.text(), out);
                  append("; ", out);
                  append(input.state().name().toLowerCase(java.util.Locale.ROOT), out);
                  append(")", out);
                });
      }
      case DraftNode.ObjectValue object -> {
        append("{", out);
        boolean first = true;
        for (var entry : object.fields().entrySet()) {
          if (!first) append(", ", out);
          first = false;
          quote(entry.getKey(), out);
          append(": ", out);
          node(entry.getValue(), out);
        }
        append("}", out);
      }
      case DraftNode.Sequence sequence -> {
        append(sequence.unordered() ? "set [" : "list [", out);
        rows(sequence.rows(), out);
        append("]", out);
      }
      case DraftNode.Mapping mapping -> {
        append("map entries [", out);
        rows(mapping.rows(), out);
        append("]", out);
      }
      case DraftNode.OptionalValue optional -> {
        append(optional.present() ? "present (" : "absent; retained (", out);
        node(optional.value(), out);
        append(")", out);
      }
      case DraftNode.Defaulted value -> {
        append(value.overridden() ? "override (" : "inherited; retained (", out);
        node(value.value(), out);
        append(")", out);
        if (!value.retainedOverrides().isEmpty()) {
          append("; earlier overrides [", out);
          rows(value.retainedOverrides(), out);
          append("]", out);
        }
      }
      case DraftNode.Alternative alternative -> {
        append("selected ", out);
        node(alternative.selector(), out);
        append("; branches {", out);
        boolean first = true;
        for (var entry : alternative.branches().entrySet()) {
          if (!first) append(", ", out);
          first = false;
          quote(entry.getKey(), out);
          append(": ", out);
          node(entry.getValue(), out);
        }
        append("}", out);
        if (!alternative.unknownFields().isEmpty()) {
          append("; unknown ", out);
          node(new DraftNode.ObjectValue(alternative.unknownFields()), out);
        }
      }
      case DraftNode.Opaque opaque -> {
        append("raw ", out);
        quote(opaque.json(), out);
      }
      case DraftNode.Missing ignored -> append("<missing>", out);
      case DraftNode.NullValue ignored -> append("<null>", out);
    }
  }

  private void rows(java.util.List<DraftNode.Row> rows, StringBuilder out) {
    boolean first = true;
    for (var row : rows) {
      if (!first) append(", ", out);
      first = false;
      node(row.value(), out);
    }
  }

  private void quote(String text, StringBuilder out) {
    append("\"", out);
    for (int index = 0; index < text.length(); index++) {
      char value = text.charAt(index);
      if (value == '"' || value == '\\') {
        append('\\', out);
        append(value, out);
      } else if (value == '\u00a7'
          || Character.isISOControl(value)
          || Character.isSurrogate(value)
          || Character.getType(value) == Character.FORMAT) {
        append("\\u", out);
        for (int shift = 12; shift >= 0; shift -= 4)
          append("0123456789abcdef".charAt((value >> shift) & 15), out);
      } else append(value, out);
    }
    append("\"", out);
  }

  private void append(String value, StringBuilder out) {
    if (value.length() > maximumCharacters - characters) throw exhausted();
    characters += value.length();
    out.append(value);
  }

  private void append(char value, StringBuilder out) {
    if (characters >= maximumCharacters) throw exhausted();
    characters++;
    out.append(value);
  }

  private PolicyRefusal exhausted() {
    return new PolicyRefusal(refusalCode, refusalMessage);
  }
}
