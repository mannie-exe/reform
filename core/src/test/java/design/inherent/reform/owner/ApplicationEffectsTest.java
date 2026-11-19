package design.inherent.reform.owner;

import static org.junit.jupiter.api.Assertions.*;

import design.inherent.reform.definition.FieldPath;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class ApplicationEffectsTest {
  private static final FieldPath PROFILE = FieldPath.ROOT.field("profile");

  private static ApplicationEffects.Rule rule(FieldPath path, String explanation) {
    return new ApplicationEffects.Rule(
        path, Set.of(ApplicationEffects.Timing.NEW_WORLD), explanation);
  }

  @Test
  void capturesOwnedRulesAndChoosesTheNearestContainingDeclaration() {
    var timings =
        EnumSet.of(
            ApplicationEffects.Timing.OWNER_PUBLICATION, ApplicationEffects.Timing.REOPEN_VIEW);
    var root = new ApplicationEffects.Rule(FieldPath.ROOT, timings, "Owner value and next view");
    var nested = rule(PROFILE, "Only a new world");
    var rules = new ArrayList<>(List.of(root, nested));
    var captured = new ApplicationEffects(rules);
    timings.clear();
    rules.clear();
    assertEquals(
        Set.of(ApplicationEffects.Timing.OWNER_PUBLICATION, ApplicationEffects.Timing.REOPEN_VIEW),
        captured.forField(FieldPath.ROOT).orElseThrow().timings());
    assertSame(nested, captured.forField(PROFILE.field("size")).orElseThrow());
    assertSame(root, captured.forField(FieldPath.ROOT.field("other")).orElseThrow());
    assertThrows(UnsupportedOperationException.class, () -> captured.rules().clear());
    assertThrows(UnsupportedOperationException.class, () -> root.timings().clear());
  }

  @Test
  void childOnlyMetadataCannotCertifyAnEntireCollectionOrAnUndeclaredSibling() {
    var captured = new ApplicationEffects(List.of(rule(PROFILE.field("size"), "Size only")));
    assertTrue(captured.forField(PROFILE).isEmpty());
    assertTrue(captured.forField(PROFILE.field("palette")).isEmpty());
    assertTrue(ApplicationEffects.UNDECLARED.forField(FieldPath.ROOT).isEmpty());
    assertTrue(captured.forField(PROFILE.field("size")).isPresent());
  }

  @Test
  void ambiguousOrExhaustedDeclarationsRefuseWithoutAPartialPolicy() {
    var root = rule(FieldPath.ROOT, "Declared effect");
    assertThrows(IllegalArgumentException.class, () -> new ApplicationEffects(List.of(root, root)));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new ApplicationEffects(
                List.of(root, rule(PROFILE, "Nested")),
                new ApplicationEffects.Limits(1, 128, 1024)));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new ApplicationEffects(
                List.of(rule(PROFILE.field("size"), "Deep")),
                new ApplicationEffects.Limits(1, 1, 1024)));
    assertThrows(
        IllegalArgumentException.class,
        () -> new ApplicationEffects(List.of(root), new ApplicationEffects.Limits(1, 1, 4)));
    assertThrows(
        IllegalArgumentException.class,
        () -> new ApplicationEffects.Rule(PROFILE, Set.of(), "No timing"));
    assertThrows(IllegalArgumentException.class, () -> rule(PROFILE, "x".repeat(4097)));
    assertThrows(
        IllegalArgumentException.class, () -> new ApplicationEffects.Limits(16385, 128, 262144));
  }
}
