package design.inherent.reform.owner;

import design.inherent.reform.definition.FieldPath;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/** Immutable owner declarations about future application, never an effect or success receipt. */
public final class ApplicationEffects {
  public enum Timing {
    OWNER_PUBLICATION,
    EXPLICIT_RELOAD,
    REOPEN_VIEW,
    NEXT_OPERATION,
    NEXT_JOIN,
    WORLD_RESTART,
    GAME_RESTART,
    NEW_WORLD,
    NEW_CHUNKS,
    INCOMPATIBLE,
    OWNER_MANAGED
  }

  public record Limits(int maximumRules, int maximumDepth, int maximumCharacters) {
    public static final Limits STANDARD = new Limits(1024, 128, 262144);

    public Limits {
      if (maximumRules < 1
          || maximumRules > 16384
          || maximumDepth < 1
          || maximumDepth > 128
          || maximumCharacters < 1)
        throw new IllegalArgumentException("Invalid application-declaration limits");
    }
  }

  /** Multiple boundaries can apply to one scope; explanations must identify their actual target. */
  public record Rule(FieldPath path, Set<Timing> timings, String explanation) {
    public Rule {
      Objects.requireNonNull(path);
      timings = Set.copyOf(timings);
      Objects.requireNonNull(explanation);
      if (timings.isEmpty() || explanation.isBlank() || explanation.length() > 4096)
        throw new IllegalArgumentException("A declaration needs bounded timings and explanation");
    }

    public List<CommitReceipt.Requirement> requirements() {
      return timings.stream()
          .sorted()
          .map(
              timing ->
                  switch (timing) {
                    case NEW_WORLD -> CommitReceipt.RequirementKind.NEW_WORLD;
                    case WORLD_RESTART -> CommitReceipt.RequirementKind.WORLD_RESTART;
                    case GAME_RESTART -> CommitReceipt.RequirementKind.GAME_RESTART;
                    case INCOMPATIBLE -> CommitReceipt.RequirementKind.INCOMPATIBLE;
                    default -> CommitReceipt.RequirementKind.OWNER_MANAGED;
                  })
          .distinct()
          .map(kind -> new CommitReceipt.Requirement(path, kind, explanation))
          .toList();
    }
  }

  public static final ApplicationEffects UNDECLARED = new ApplicationEffects(List.of());
  public static final ApplicationEffects OWNER_PUBLICATION =
      root(
          Timing.OWNER_PUBLICATION,
          "Publishes this owner's value. Other caches and running-world changes are not certified.");

  private final List<Rule> rules;

  public ApplicationEffects(List<Rule> rules) {
    this(rules, Limits.STANDARD);
  }

  public ApplicationEffects(List<Rule> rules, Limits limits) {
    Objects.requireNonNull(rules);
    Objects.requireNonNull(limits);
    if (rules.size() > limits.maximumRules())
      throw new IllegalArgumentException("Application declarations exceed their rule limit");
    Map<FieldPath, Rule> unique = new LinkedHashMap<>();
    long characters = 0;
    for (var rule : rules) {
      Objects.requireNonNull(rule);
      if (rule.path().segments().size() > limits.maximumDepth())
        throw new IllegalArgumentException("Application declaration path is too deep");
      characters += rule.path().toString().length() + rule.explanation().length();
      for (var timing : rule.timings()) characters += timing.name().length();
      if (characters > limits.maximumCharacters())
        throw new IllegalArgumentException("Application declarations exceed their text limit");
      if (unique.putIfAbsent(rule.path(), rule) != null)
        throw new IllegalArgumentException("Duplicate application-declaration scope");
    }
    this.rules = List.copyOf(unique.values());
  }

  public static ApplicationEffects root(Timing timing, String explanation) {
    return new ApplicationEffects(List.of(new Rule(FieldPath.ROOT, Set.of(timing), explanation)));
  }

  public List<Rule> rules() {
    return rules;
  }

  /** The nearest containing rule applies; child-only rules do not certify a whole collection. */
  public Optional<Rule> forField(FieldPath path) {
    Objects.requireNonNull(path);
    Rule selected = null;
    for (var rule : rules)
      if (path.startsWith(rule.path())
          && (selected == null
              || rule.path().segments().size() > selected.path().segments().size()))
        selected = rule;
    return Optional.ofNullable(selected);
  }
}
