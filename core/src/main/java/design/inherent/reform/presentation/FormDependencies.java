package design.inherent.reform.presentation;

import design.inherent.reform.definition.FieldPath;
import design.inherent.reform.edit.DraftNode;
import design.inherent.reform.edit.DraftTree;
import design.inherent.reform.edit.DraftTypes;
import design.inherent.reform.validation.Issue;
import design.inherent.reform.validation.ValidationReport;
import design.inherent.reform.value.ValueShape;
import design.inherent.reform.value.ValueType;
import java.io.NotSerializableException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.Serial;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Bounded local availability metadata, not validation, authorization or an expression engine. */
public final class FormDependencies {
  public static final FormDependencies NONE = new FormDependencies(List.of());

  public enum State {
    AVAILABLE,
    DISABLED,
    UNKNOWN
  }

  /** Multiple rules affecting a field (including an ancestor) are combined with AND. */
  public record Rule(
      String id,
      FieldPath target,
      FieldPath input,
      Set<String> accepted,
      String translationKey,
      String reason) {
    public Rule {
      if (!Objects.requireNonNull(id).matches("[a-z][a-z0-9._/-]{0,127}"))
        throw new IllegalArgumentException("Dependency needs a bounded stable ID");
      path(target);
      path(input);
      if (target.equals(FieldPath.ROOT))
        throw new IllegalArgumentException("A dependency cannot disable the entire document");
      Objects.requireNonNull(accepted);
      if (accepted.isEmpty()
          || accepted.size() > 64
          || accepted.stream().anyMatch(value -> value.length() > 1024)
          || Objects.requireNonNull(translationKey).isBlank()
          || translationKey.length() > 256
          || Objects.requireNonNull(reason).isBlank()
          || reason.length() > 4096)
        throw new IllegalArgumentException("Dependency values and explanation must be bounded");
      accepted = Set.copyOf(accepted);
    }
  }

  /** A bounded explanation names its immediate input; it never recursively expands a graph. */
  public record Reason(String code, FieldPath input, String translationKey, String fallback) {}

  public record Decision(State state, List<Reason> reasons) {
    public static final Decision AVAILABLE = new Decision(State.AVAILABLE, List.of());

    public Decision {
      Objects.requireNonNull(state);
      reasons = List.copyOf(reasons);
    }
  }

  /** Registration diagnostics retain stable field identities, not just a formatted message. */
  public static final class InvalidDefinition extends IllegalArgumentException {
    @Serial private static final long serialVersionUID = 1L;
    private final transient ValidationReport report;

    private InvalidDefinition(String code, Rule rule, String message) {
      super(code + ": " + message);
      report =
          new ValidationReport(
              List.of(
                  new Issue(
                      code,
                      Issue.Severity.ERROR,
                      rule.target(),
                      List.of(rule.input()),
                      Map.of("rule", rule.id()),
                      message)),
              true);
    }

    public ValidationReport report() {
      return report;
    }

    @Serial
    private void writeObject(ObjectOutputStream stream) throws NotSerializableException {
      throw new NotSerializableException("Use explicit diagnostic values, not Java serialization");
    }

    @Serial
    private void readObject(ObjectInputStream stream) throws NotSerializableException {
      throw new NotSerializableException("Local definition diagnostics cannot be deserialized");
    }
  }

  /** Immutable observations only; holding a result retains neither draft nor owner. */
  public static final class Evaluation {
    private final List<Rule> rules;
    private final List<Decision> decisions;

    private Evaluation(List<Rule> rules, List<Decision> decisions) {
      this.rules = rules;
      this.decisions = List.copyOf(decisions);
    }

    public Decision at(FieldPath field) {
      return combine(rules, decisions, field);
    }
  }

  /** Shape-qualified immutable program. Evaluation performs no decoding or external lookup. */
  public static final class Bound {
    private final List<Rule> rules;
    private final List<Integer> order;
    private final Set<FieldPath> inputs;
    private final Set<FieldPath> unavailableInputs;

    private Bound(List<Rule> rules, List<Integer> order, Set<FieldPath> unavailable) {
      this.rules = rules;
      this.order = List.copyOf(order);
      inputs = Set.copyOf(rules.stream().map(Rule::input).toList());
      unavailableInputs =
          inputs.stream()
              .filter(input -> unavailable.stream().anyMatch(input::startsWith))
              .collect(java.util.stream.Collectors.toUnmodifiableSet());
    }

    public Set<FieldPath> inputs() {
      return inputs;
    }

    public boolean affects(FieldPath field) {
      return rules.stream().anyMatch(rule -> field.startsWith(rule.target()));
    }

    public Evaluation evaluate(DraftTree draft, Map<FieldPath, DraftTree.InputState> states) {
      return evaluate(draft, states, Set.of());
    }

    /** Unadmitted local input is unknown, even if its previous worker observation was valid. */
    public Evaluation evaluate(
        DraftTree draft,
        Map<FieldPath, DraftTree.InputState> states,
        Set<FieldPath> pendingInputs) {
      Objects.requireNonNull(draft);
      Objects.requireNonNull(states);
      Objects.requireNonNull(pendingInputs);
      var results = new ArrayList<Decision>(java.util.Collections.nCopies(rules.size(), null));
      for (int index : order) {
        var rule = rules.get(index);
        Decision upstream = combine(rules, results, rule.input());
        final Decision decision;
        if (unavailableInputs.contains(rule.input())) {
          decision =
              unknown(rule, "input_unavailable", "The owner's required input is unavailable");
        } else if (upstream.state() != State.AVAILABLE || !draft.activeAt(rule.input())) {
          decision = unknown(rule, "input_inactive", "The required input is inactive or absent");
        } else if (pendingInputs.contains(rule.input())) {
          decision = unknown(rule, "input_pending", "The required input is awaiting admission");
        } else if (states.get(rule.input()) != DraftTree.InputState.VALID) {
          decision = unknown(rule, "input_unknown", "The required input is invalid or unavailable");
        } else if (draft.at(rule.input()) instanceof DraftNode.Scalar scalar) {
          decision =
              rule.accepted().contains(scalar.raw())
                  ? Decision.AVAILABLE
                  : new Decision(
                      State.DISABLED,
                      List.of(
                          new Reason(
                              "dependency.disabled",
                              rule.input(),
                              rule.translationKey(),
                              rule.reason())));
        } else {
          decision = unknown(rule, "input_unknown", "The required scalar input is unavailable");
        }
        results.set(index, decision);
      }
      return new Evaluation(rules, results);
    }
  }

  private final List<Rule> rules;

  public FormDependencies(List<Rule> rules) {
    Objects.requireNonNull(rules);
    if (rules.size() > 128) throw new IllegalArgumentException("Too many local dependency rules");
    this.rules = List.copyOf(rules);
    var ids = new HashSet<String>();
    for (var rule : this.rules)
      if (!ids.add(rule.id()))
        throw new InvalidDefinition("dependency.duplicate", rule, "Duplicate dependency ID");
  }

  public List<Rule> rules() {
    return rules;
  }

  /** Diagnose unknown paths, incompatible inputs and prefix-aware cycles before opening a form. */
  public Bound bind(ValueType<?> root) {
    return bind(root, Set.of());
  }

  Bound bind(ValueType<?> root, Set<FieldPath> unavailable) {
    Objects.requireNonNull(root);
    for (var rule : rules) {
      final ValueShape input;
      try {
        DraftTypes.declaredAt(root, rule.target());
        input = DraftTypes.declaredAt(root, rule.input()).shape();
      } catch (IllegalArgumentException missing) {
        throw new InvalidDefinition(
            "dependency.undeclared", rule, "Dependency refers to an undeclared field");
      }
      if (!(input instanceof ValueShape.Scalar scalar)
          || !(scalar.kind() == ValueShape.ScalarKind.BOOLEAN
              || scalar.kind() == ValueShape.ScalarKind.CHOICE
              || scalar.kind() == ValueShape.ScalarKind.TEXT))
        throw new InvalidDefinition(
            "dependency.input_type", rule, "Dependency input must be boolean, choice or text");
      for (String value : rule.accepted())
        if (scalar.kind() == ValueShape.ScalarKind.BOOLEAN
                && !value.equals("true")
                && !value.equals("false")
            || scalar.kind() == ValueShape.ScalarKind.CHOICE && !scalar.choices().contains(value)
            || scalar.maximumLength() >= 0 && value.length() > scalar.maximumLength())
          throw new InvalidDefinition(
              "dependency.value", rule, "Dependency match is outside its declared input shape");
    }
    var order = new ArrayList<Integer>();
    var visited = new byte[rules.size()];
    for (int i = 0; i < rules.size(); i++) visit(i, visited, order);
    return new Bound(rules, order, unavailable);
  }

  private void visit(int index, byte[] visited, List<Integer> order) {
    if (visited[index] == 2) return;
    if (visited[index] == 1)
      throw new InvalidDefinition(
          "dependency.cycle", rules.get(index), "Local dependency cycle includes this field");
    visited[index] = 1;
    for (int i = 0; i < rules.size(); i++)
      if (rules.get(index).input().startsWith(rules.get(i).target())) visit(i, visited, order);
    visited[index] = 2;
    order.add(index);
  }

  private static Decision combine(List<Rule> rules, List<Decision> decisions, FieldPath field) {
    var reasons = new LinkedHashSet<Reason>();
    State state = State.AVAILABLE;
    for (int i = 0; i < rules.size(); i++) {
      if (!field.startsWith(rules.get(i).target())) continue;
      var current = decisions.get(i);
      // During topological evaluation only an already evaluated ancestor may affect an input.
      if (current == null) throw new IllegalStateException("Dependency order is incomplete");
      if (current.state() == State.DISABLED) state = State.DISABLED;
      else if (state == State.AVAILABLE && current.state() == State.UNKNOWN) state = State.UNKNOWN;
      reasons.addAll(current.reasons());
    }
    return reasons.isEmpty() ? Decision.AVAILABLE : new Decision(state, List.copyOf(reasons));
  }

  private static Decision unknown(Rule rule, String code, String fallback) {
    return new Decision(
        State.UNKNOWN,
        List.of(
            new Reason("dependency." + code, rule.input(), "reform.dependency." + code, fallback)));
  }

  static void path(FieldPath path) {
    Objects.requireNonNull(path);
    if (path.segments().size() > 128)
      throw new IllegalArgumentException("Dependency field path is too deep");
    int characters = 0;
    for (var segment : path.segments()) {
      String id =
          switch (segment) {
            case FieldPath.Field f -> f.id();
            case FieldPath.Variant v -> v.id();
            case FieldPath.Part ignored -> "";
            case FieldPath.Row ignored ->
                throw new IllegalArgumentException(
                    "Static dependency declarations cannot bind session row identities");
          };
      if (id.length() > 256 || (characters += id.length()) > 4096)
        throw new IllegalArgumentException("Dependency field path is too long");
    }
  }
}
