package design.inherent.reform.minecraft.client.ui;

import java.util.Objects;

/** Named disclosure content and an explicit finite scope-retention policy. */
public record DisclosureParts(
    UiText label,
    UiText description,
    UiText collapsedSummary,
    ViewSpec.Branch content,
    Retention retention) {
  public enum Retention {
    RETAIN,
    DISPOSE
  }

  public DisclosureParts {
    Objects.requireNonNull(label);
    Objects.requireNonNull(description);
    Objects.requireNonNull(collapsedSummary);
    Objects.requireNonNull(content);
    Objects.requireNonNull(retention);
  }

  public static DisclosureParts retained(
      UiText label, UiText description, UiText collapsedSummary, ViewSpec.Branch content) {
    return new DisclosureParts(label, description, collapsedSummary, content, Retention.RETAIN);
  }

  public static DisclosureParts disposable(
      UiText label, UiText description, UiText collapsedSummary, ViewSpec.Branch content) {
    return new DisclosureParts(label, description, collapsedSummary, content, Retention.DISPOSE);
  }
}
