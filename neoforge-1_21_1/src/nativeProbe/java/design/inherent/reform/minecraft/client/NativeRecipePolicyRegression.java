package design.inherent.reform.minecraft.client;

import design.inherent.reform.definition.FieldPath;
import design.inherent.reform.presentation.RecipeLinkPolicy;
import design.inherent.reform.presentation.RecipeLinkRequest;
import design.inherent.reform.validation.Issue;
import design.inherent.reform.validation.ValidationReport;
import java.util.List;
import java.util.Set;

/** Shared client API on the real native classpath, without a client or optional viewer. */
public final class NativeRecipePolicyRegression {
  public static void main(String[] args) {
    check(ValidationReport.VALID, true, "complete available viewer");
    check(new ValidationReport(List.of(), false), false, "incomplete readiness is not available");
    check(
        new ValidationReport(
            List.of(Issue.error("fixture.refused", FieldPath.ROOT, "Unavailable")), true),
        false,
        "a failed readiness check is not available");
    System.out.println("Recipe availability: complete, incomplete, and refused controls pass");
  }

  private static void check(ValidationReport report, boolean expected, String label) {
    var actual =
        new ClientRecipeLinks.Availability(
            RecipeLinkPolicy.Provider.JEI, Set.of(RecipeLinkRequest.Kind.PRODUCING), report);
    if (actual.ready() != expected) throw new AssertionError(label);
  }
}
