package design.inherent.reform.presentation;

import static org.junit.jupiter.api.Assertions.*;

import com.mojang.serialization.Codec;
import design.inherent.reform.definition.DocumentDefinition;
import design.inherent.reform.definition.DocumentId;
import design.inherent.reform.definition.FieldKey;
import design.inherent.reform.definition.FieldPath;
import design.inherent.reform.definition.RecordType;
import design.inherent.reform.edit.DraftLimits;
import design.inherent.reform.edit.DraftNode;
import design.inherent.reform.edit.DraftTree;
import design.inherent.reform.edit.EditSession;
import design.inherent.reform.serialization.JsonLimits;
import design.inherent.reform.validation.Issue;
import design.inherent.reform.validation.ValidationReport;
import design.inherent.reform.value.SnapshotPolicy;
import design.inherent.reform.value.ValueShape;
import design.inherent.reform.value.ValueType;
import design.inherent.reform.value.ValueTypes;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class FormDependenciesTest {
  enum Mode {
    LAND,
    WATER
  }

  record Profile(boolean enabled, Mode mode, int height, Optional<Boolean> optional) {}

  record Section(boolean flag) {}

  record SectionProfile(boolean enabled, Section section) {}

  record TextProfile(String enabled, int height) {}

  private static final FieldPath ENABLED = FieldPath.ROOT.field("enabled");
  private static final FieldPath MODE = FieldPath.ROOT.field("mode");
  private static final FieldPath HEIGHT = FieldPath.ROOT.field("height");
  private static final FieldPath OPTIONAL = FieldPath.ROOT.field("optional");
  private static final Profile DEFAULTS = new Profile(true, Mode.LAND, 10, Optional.empty());
  private static final JsonLimits JSON = new JsonLimits(8192, 32, 1024, 512, 128, 4096);
  private static final RecordType<Profile> TYPE = type(ValueTypes.bool());

  private static RecordType<Profile> type(ValueType<Boolean> toggle) {
    var enabled = FieldKey.of("enabled", toggle);
    var mode = FieldKey.of("mode", ValueTypes.enumeration(Mode.class));
    var height = FieldKey.of("height", ValueTypes.integer(0, 100));
    var optional =
        FieldKey.of(
            "optional",
            ValueTypes.optional(ValueTypes.bool(), ValueShape.OptionalEncoding.NULL_OR_VALUE));
    return RecordType.<Profile>builder(
            v -> new Profile(v.get(enabled), v.get(mode), v.get(height), v.get(optional)))
        .field(enabled, Profile::enabled)
        .field(mode, Profile::mode)
        .field(height, Profile::height)
        .field(optional, Profile::optional)
        .build();
  }

  private static DocumentDefinition<Profile> definition() {
    return new DocumentDefinition<>(
        DocumentId.parse("fixture:dependencies"),
        1,
        TYPE,
        () -> DEFAULTS,
        value ->
            value.height() == 90
                ? new ValidationReport(
                    List.of(Issue.error("fixture.height", HEIGHT, "Choose another height")), true)
                : ValidationReport.VALID);
  }

  private static FormDependencies.Rule rule(String id, FieldPath target, FieldPath source) {
    return new FormDependencies.Rule(
        id, target, source, Set.of("true"), "fixture.dependencies." + id, "Enable its input");
  }

  private static FormDependencies.Bound bound(FormDependencies.Rule... rules) {
    return new FormDependencies(List.of(rules)).bind(TYPE);
  }

  private static DraftTree draft() {
    return DraftTree.from(TYPE, DEFAULTS, DraftLimits.CONFIGURATION);
  }

  private static FormDependencies.Decision evaluate(
      FormDependencies.Bound bound, DraftTree draft, FieldPath target) {
    return bound.evaluate(draft, draft.inspect(definition(), JSON).inputs()).at(target);
  }

  @Test
  void aKnownControllerStaysUsableDespiteUnrelatedCompleteCandidateFailure() {
    var program = bound(rule("height", HEIGHT, ENABLED));
    var bad = draft().change(HEIGHT, ignored -> new DraftNode.Scalar("90"));
    var inspection = bad.inspect(definition(), JSON);
    assertTrue(inspection.candidate().isEmpty());
    assertEquals(DraftTree.InputState.VALID, inspection.inputs().get(ENABLED));
    assertEquals(FormDependencies.State.AVAILABLE, evaluate(program, bad, HEIGHT).state());
    var malformed = bad.change(HEIGHT, ignored -> new DraftNode.Scalar("-"));
    assertFalse(malformed.inspect(definition(), JSON).report().valid());
    assertEquals(FormDependencies.State.AVAILABLE, evaluate(program, malformed, HEIGHT).state());
  }

  @Test
  void falseNeverErasesTheTargetOrSuppressesOwnerValidationAndUndoRepairsIt() {
    var program = bound(rule("height", HEIGHT, ENABLED));
    try (var editor =
        EditSession.fromDraft(
            definition(), UUID.randomUUID(), "one", draft(), JSON, DraftLimits.CONFIGURATION)) {
      editor.setRaw(HEIGHT, "90");
      var retained = editor.snapshot().draft().at(HEIGHT);
      editor.setRaw(ENABLED, "false");
      var current = editor.snapshot();
      var result = program.evaluate(current.draft(), current.inspect().inputs());
      assertEquals(FormDependencies.State.DISABLED, result.at(HEIGHT).state());
      assertEquals("dependency.disabled", result.at(HEIGHT).reasons().getFirst().code());
      assertEquals(ENABLED, result.at(HEIGHT).reasons().getFirst().input());
      assertEquals(retained, current.draft().at(HEIGHT));
      assertTrue(current.inspect().candidate().isEmpty());
      assertEquals("fixture.height", current.inspect().report().issues().getFirst().code());
      editor.undo();
      current = editor.snapshot();
      assertEquals(
          FormDependencies.State.AVAILABLE,
          program.evaluate(current.draft(), current.inspect().inputs()).at(HEIGHT).state());
      assertEquals(retained, current.draft().at(HEIGHT));
    }
  }

  @Test
  void invalidMissingUnobservedPendingAndInactiveInputAreUnknownNotFalse() {
    var program = bound(rule("height", HEIGHT, ENABLED));
    var bad = draft().change(ENABLED, ignored -> new DraftNode.Scalar("tru"));
    assertEquals(FormDependencies.State.UNKNOWN, evaluate(program, bad, HEIGHT).state());
    var missing = draft().change(ENABLED, ignored -> new DraftNode.Missing());
    assertEquals(FormDependencies.State.UNKNOWN, evaluate(program, missing, HEIGHT).state());
    assertEquals(
        FormDependencies.State.UNKNOWN, program.evaluate(draft(), Map.of()).at(HEIGHT).state());
    assertEquals(
        "dependency.input_pending",
        program
            .evaluate(draft(), Map.of(ENABLED, DraftTree.InputState.VALID), Set.of(ENABLED))
            .at(HEIGHT)
            .reasons()
            .getFirst()
            .code());
    var optionalSource = OPTIONAL.append(FieldPath.Part.VALUE);
    var optionalProgram = bound(rule("height", HEIGHT, optionalSource));
    assertEquals(
        "dependency.input_inactive",
        optionalProgram
            .evaluate(draft(), Map.of(optionalSource, DraftTree.InputState.VALID))
            .at(HEIGHT)
            .reasons()
            .getFirst()
            .code());
    assertFalse(draft().activeAt(optionalSource));
  }

  @Test
  void conjunctionAndUpstreamGatesHaveExplicitBoundedUnknownSemantics() {
    var modeGate =
        new FormDependencies.Rule(
            "mode", MODE, ENABLED, Set.of("true"), "fixture.mode", "Enable mode selection");
    var heightGate =
        new FormDependencies.Rule(
            "height", HEIGHT, MODE, Set.of("LAND"), "fixture.height", "Select land mode");
    var program = bound(heightGate, modeGate); // Declaration order is not dependency order.
    assertEquals(FormDependencies.State.AVAILABLE, evaluate(program, draft(), HEIGHT).state());
    var disabled = draft().change(ENABLED, ignored -> new DraftNode.Scalar("false"));
    assertEquals(FormDependencies.State.DISABLED, evaluate(program, disabled, MODE).state());
    assertEquals(FormDependencies.State.UNKNOWN, evaluate(program, disabled, HEIGHT).state());
    var water = draft().change(MODE, ignored -> new DraftNode.Scalar("WATER"));
    assertEquals(FormDependencies.State.DISABLED, evaluate(program, water, HEIGHT).state());
    var both = bound(heightGate, modeGate, rule("direct", HEIGHT, ENABLED));
    assertEquals(FormDependencies.State.DISABLED, evaluate(both, disabled, HEIGHT).state());
    assertEquals(2, evaluate(both, disabled, HEIGHT).reasons().size());
  }

  @Test
  void gatesAffectDescendantsButDoNotDisableTheirOwnController() {
    var child = FieldKey.of("flag", ValueTypes.bool());
    var nested =
        RecordType.<Section>builder(v -> new Section(v.get(child)))
            .field(child, Section::flag)
            .build();
    var toggle = FieldKey.of("enabled", ValueTypes.bool());
    var section = FieldKey.of("section", nested);
    var type =
        RecordType.<SectionProfile>builder(v -> new SectionProfile(v.get(toggle), v.get(section)))
            .field(toggle, SectionProfile::enabled)
            .field(section, SectionProfile::section)
            .build();
    var target = FieldPath.ROOT.field("section");
    var program = new FormDependencies(List.of(rule("section", target, ENABLED))).bind(type);
    var current =
        DraftTree.from(
            type, new SectionProfile(false, new Section(true)), DraftLimits.CONFIGURATION);
    var observed = program.evaluate(current, Map.of(ENABLED, DraftTree.InputState.VALID));
    assertEquals(FormDependencies.State.DISABLED, observed.at(target.field("flag")).state());
    assertEquals(FormDependencies.State.AVAILABLE, observed.at(ENABLED).state());
    assertEquals(FormDependencies.State.AVAILABLE, observed.at(FieldPath.ROOT).state());
    assertTrue(program.affects(target.field("flag")));
    assertFalse(program.affects(ENABLED));
  }

  @Test
  void registrationDiagnosesMissingPathsInputKindsMatchesAndCycles() {
    assertCode("dependency.undeclared", rule("missing", FieldPath.ROOT.field("absent"), ENABLED));
    assertCode("dependency.input_type", rule("numeric", MODE, HEIGHT));
    assertCode(
        "dependency.value",
        new FormDependencies.Rule("value", HEIGHT, MODE, Set.of("absent"), "key", "Choose land"));
    assertCode("dependency.cycle", rule("self", ENABLED, ENABLED));
    var first = rule("one", HEIGHT, ENABLED);
    var second =
        new FormDependencies.Rule("two", ENABLED, MODE, Set.of("LAND"), "key", "Select land");
    var third = new FormDependencies.Rule("three", MODE, HEIGHT, Set.of("true"), "key", "Reason");
    // Numeric controller is independently diagnosed before graph traversal.
    assertCode("dependency.input_type", first, second, third);
    var cycle =
        new FormDependencies.Rule("loop", ENABLED, MODE, Set.of("LAND"), "key", "Select land");
    assertCode("dependency.cycle", rule("forward", MODE, ENABLED), cycle);
    var duplicate =
        assertThrows(FormDependencies.InvalidDefinition.class, () -> bound(first, first));
    assertEquals("dependency.duplicate", duplicate.report().issues().getFirst().code());
  }

  @Test
  void ancestorTargetCycleIsNotMissedByExactFieldComparison() {
    var inner = FieldKey.of("enabled", ValueTypes.bool());
    var section = RecordType.<Boolean>builder(v -> v.get(inner)).field(inner, v -> v).build();
    var field = FieldKey.of("section", section);
    var root = RecordType.<Boolean>builder(v -> v.get(field)).field(field, v -> v).build();
    var path = FieldPath.ROOT.field("section");
    var failure =
        assertThrows(
            FormDependencies.InvalidDefinition.class,
            () ->
                new FormDependencies(List.of(rule("self", path, path.field("enabled"))))
                    .bind(root));
    assertEquals("dependency.cycle", failure.report().issues().getFirst().code());
  }

  @Test
  void zeroLengthTextAcceptsOnlyTheEmptyLiteralAndRuntimeKeepsItKnown() {
    var selector = FieldKey.of("enabled", ValueTypes.text(0));
    var target = FieldKey.of("height", ValueTypes.integer(0, 100));
    var type =
        RecordType.<TextProfile>builder(v -> new TextProfile(v.get(selector), v.get(target)))
            .field(selector, TextProfile::enabled)
            .field(target, TextProfile::height)
            .build();
    var bad =
        new FormDependencies.Rule("text", HEIGHT, ENABLED, Set.of("x"), "key", "Use empty text");
    var failure =
        assertThrows(
            FormDependencies.InvalidDefinition.class,
            () -> new FormDependencies(List.of(bad)).bind(type));
    assertEquals("dependency.value", failure.report().issues().getFirst().code());
    var allowed =
        new FormDependencies.Rule("text", HEIGHT, ENABLED, Set.of(""), "key", "Use empty text");
    var draft = DraftTree.from(type, new TextProfile("", 1), DraftLimits.CONFIGURATION);
    assertEquals(
        FormDependencies.State.AVAILABLE,
        new FormDependencies(List.of(allowed))
            .bind(type)
            .evaluate(draft, Map.of(ENABLED, DraftTree.InputState.VALID))
            .at(HEIGHT)
            .state());
  }

  private static void assertCode(String code, FormDependencies.Rule... rules) {
    var failure = assertThrows(FormDependencies.InvalidDefinition.class, () -> bound(rules));
    assertEquals(code, failure.report().issues().getFirst().code());
    assertFalse(failure.report().valid());
    assertFalse(failure.report().issues().getFirst().related().isEmpty());
  }

  @Test
  void metadataAndCachedEvaluationNeverInvokeCodecsSnapshotsOrOwnerConstructors() {
    var calls = new AtomicInteger();
    var toggle =
        new ValueType<Boolean>() {
          public Codec<Boolean> codec() {
            calls.incrementAndGet();
            throw new AssertionError("Decoder called");
          }

          public SnapshotPolicy<Boolean> snapshotPolicy() {
            calls.incrementAndGet();
            throw new AssertionError("Snapshot called");
          }

          public ValueShape shape() {
            return ValueTypes.bool().shape();
          }
        };
    var program = new FormDependencies(List.of(rule("height", HEIGHT, ENABLED))).bind(type(toggle));
    var observed = program.evaluate(draft(), Map.of(ENABLED, DraftTree.InputState.VALID));
    for (int i = 0; i < 100; i++)
      assertEquals(FormDependencies.State.AVAILABLE, observed.at(HEIGHT).state());
    assertEquals(0, calls.get());
    assertThrows(UnsupportedOperationException.class, () -> program.inputs().clear());
  }

  @Test
  void declaredRuleDataAndEvaluationResultsAreImmutableAndBounded() {
    var rows = new ArrayList<>(List.of(rule("height", HEIGHT, ENABLED)));
    var declarations = new FormDependencies(rows);
    rows.clear();
    assertEquals(1, declarations.rules().size());
    assertThrows(UnsupportedOperationException.class, () -> declarations.rules().clear());
    var decision =
        evaluate(
            declarations.bind(TYPE),
            draft().change(ENABLED, ignored -> new DraftNode.Scalar("false")),
            HEIGHT);
    assertThrows(UnsupportedOperationException.class, () -> decision.reasons().clear());
    assertThrows(
        IllegalArgumentException.class,
        () -> new FormDependencies(java.util.Collections.nCopies(129, rowsForLimit())));
    assertThrows(
        IllegalArgumentException.class,
        () -> rule("row", HEIGHT, FieldPath.ROOT.field("rows").row(UUID.randomUUID())));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new FormDependencies.Rule(
                "root", FieldPath.ROOT, ENABLED, Set.of("true"), "key", "Reason"));
  }

  @Test
  void maximumReverseDeclaredChainHasBoundedReasonsAndNoRecursiveExpansion() {
    var rules = new ArrayList<FormDependencies.Rule>();
    var fields = new ArrayList<FieldKey<?>>();
    var nodes = new java.util.LinkedHashMap<String, DraftNode>();
    var states = new java.util.LinkedHashMap<FieldPath, DraftTree.InputState>();
    for (int i = 0; i <= 128; i++) {
      String id = "flag" + i;
      fields.add(FieldKey.of(id, ValueTypes.bool()));
      nodes.put(id, new DraftNode.Scalar(i == 0 ? "false" : "true"));
      states.put(FieldPath.ROOT.field(id), DraftTree.InputState.VALID);
      if (i > 0)
        rules.addFirst(rule(id, FieldPath.ROOT.field(id), FieldPath.ROOT.field("flag" + (i - 1))));
    }
    var root =
        new ValueType<Boolean>() {
          public Codec<Boolean> codec() {
            throw new AssertionError("No codec needed");
          }

          public SnapshotPolicy<Boolean> snapshotPolicy() {
            throw new AssertionError("No snapshot needed");
          }

          public ValueShape shape() {
            return new ValueShape.ObjectValue(fields);
          }
        };
    var draft = new DraftTree(new DraftNode.ObjectValue(nodes), DraftLimits.CONFIGURATION);
    var result = new FormDependencies(rules).bind(root).evaluate(draft, states);
    assertEquals(FormDependencies.State.DISABLED, result.at(FieldPath.ROOT.field("flag1")).state());
    var tail = result.at(FieldPath.ROOT.field("flag128"));
    assertEquals(FormDependencies.State.UNKNOWN, tail.state());
    assertEquals(1, tail.reasons().size());
    assertEquals(FieldPath.ROOT.field("flag127"), tail.reasons().getFirst().input());
  }

  private static FormDependencies.Rule rowsForLimit() {
    return rule("height", HEIGHT, ENABLED);
  }
}
