package design.inherent.reform.presentation.ui;

/** Aggregate admission for one presentation root, independent of any document's legal domain. */
public record UiLimits(
    int maximumScopes,
    int maximumDepth,
    int maximumStates,
    int maximumProjections,
    int maximumDependencies,
    int maximumCloseHooks,
    long maximumStateBytes) {
  public static final UiLimits STANDARD = new UiLimits(1024, 32, 1024, 2048, 8192, 4096, 1_048_576);

  public UiLimits {
    if (maximumScopes < 1
        || maximumDepth < 1
        || maximumStates < 1
        || maximumProjections < 1
        || maximumDependencies < 1
        || maximumCloseHooks < 1
        || maximumStateBytes < 1)
      throw new IllegalArgumentException("Presentation limits must be positive");
  }
}
