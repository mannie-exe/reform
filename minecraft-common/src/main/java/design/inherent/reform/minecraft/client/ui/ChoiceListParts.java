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
    int overscan,
    Optional<UiCommand<Boundary>> paging,
    Optional<UiBinding<Optional<String>>> viewport) {
  public enum Boundary {
    BEFORE,
    AFTER
  }

  public ChoiceListParts(
      UiText label,
      UiText help,
      UiRead<ChoiceListData> data,
      UiBinding<Optional<String>> active,
      UiRead<Set<String>> accepted,
      UiCommand<String> accept,
      int visibleRows,
      int overscan,
      Optional<UiCommand<Boundary>> paging) {
    this(
        label,
        help,
        data,
        active,
        accepted,
        accept,
        visibleRows,
        overscan,
        paging,
        Optional.empty());
  }

  public ChoiceListParts(
      UiText label,
      UiText help,
      UiRead<ChoiceListData> data,
      UiBinding<Optional<String>> active,
      UiRead<Set<String>> accepted,
      UiCommand<String> accept,
      int visibleRows,
      int overscan) {
    this(label, help, data, active, accepted, accept, visibleRows, overscan, Optional.empty());
  }

  /** An optional owned top-row key; this never changes active or accepted values. */
  public ChoiceListParts withViewport(UiBinding<Optional<String>> position) {
    return new ChoiceListParts(
        label,
        help,
        data,
        active,
        accepted,
        accept,
        visibleRows,
        overscan,
        paging,
        Optional.of(position));
  }

  public ChoiceListParts {
    Objects.requireNonNull(label);
    Objects.requireNonNull(help);
    Objects.requireNonNull(data);
    Objects.requireNonNull(active);
    Objects.requireNonNull(accepted);
    Objects.requireNonNull(accept);
    Objects.requireNonNull(paging);
    Objects.requireNonNull(viewport);
    if (visibleRows < 1 || visibleRows > 12 || overscan < 0 || overscan > 4)
      throw new IllegalArgumentException("A choice list needs finite visible rows and overscan");
  }
}
