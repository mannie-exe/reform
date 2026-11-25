package design.inherent.reform.minecraft.client.ui;

import design.inherent.reform.presentation.ui.*;
import java.util.*;

/**
 * Active navigation and accepted value are independent. Acceptance is always an explicit command.
 */
public record ChoiceListParts(
    UiText label,
    UiText help,
    UiRead<ChoiceListData> data,
    UiBinding<Optional<String>> active,
    UiRead<Set<String>> accepted,
    UiCommand<String> accept,
    int visibleRows,
    int overscan) {
  public ChoiceListParts {
    Objects.requireNonNull(label);
    Objects.requireNonNull(help);
    Objects.requireNonNull(data);
    Objects.requireNonNull(active);
    Objects.requireNonNull(accepted);
    Objects.requireNonNull(accept);
    if (visibleRows < 1 || visibleRows > 12 || overscan < 0 || overscan > 4)
      throw new IllegalArgumentException("A choice list needs finite visible rows and overscan");
  }
}
