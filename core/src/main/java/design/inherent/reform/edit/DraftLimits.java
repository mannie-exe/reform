package design.inherent.reform.edit;

/** Logical retained content budgets, not claims about exact heap bytes or remote packet limits. */
public record DraftLimits(
    int maximumDepth,
    int maximumNodes,
    int maximumCharacters,
    int maximumHistoryEntries,
    long maximumHistoryCost) {
  public static final DraftLimits CONFIGURATION =
      new DraftLimits(128, 16_384, 131_072, 128, 1_048_576);

  public DraftLimits {
    if (maximumDepth < 1
        || maximumDepth > 256
        || maximumNodes < 1
        || maximumCharacters < 1
        || maximumHistoryEntries < 0
        || maximumHistoryCost < 0) {
      throw new IllegalArgumentException("Invalid draft/history limits");
    }
  }

  public void check(DraftNode node) {
    var size = node.size();
    if (size.depth() > maximumDepth
        || size.nodes() > maximumNodes
        || size.characters() > maximumCharacters) {
      throw new IllegalArgumentException("Draft exceeds its declared depth/node/text limit");
    }
  }
}
