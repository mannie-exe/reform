package example;

import design.inherent.reform.definition.DocumentDefinition;
import design.inherent.reform.definition.DocumentId;
import design.inherent.reform.definition.FieldKey;
import design.inherent.reform.definition.FieldPath;
import design.inherent.reform.definition.RecordType;
import design.inherent.reform.serialization.JsonInput;
import design.inherent.reform.serialization.JsonLimits;
import design.inherent.reform.validation.Issue;
import design.inherent.reform.validation.ValidationReport;
import design.inherent.reform.value.ValueTypes;
import java.util.List;

/**
 * Public packaged API only. This is a headless fixture, not a production world/profile compiler.
 */
public final class ReviewProfile {
  private record Profile(String name, int minimum, int maximum, List<String> references) {}

  public static void main(String[] args) throws Exception {
    refuseGameClasspath("net.minecraft.world.level.Level");
    refuseGameClasspath("net.neoforged.fml.ModContainer");
    var name = FieldKey.of("name", ValueTypes.text(64));
    var minimum = FieldKey.of("minimum", ValueTypes.integer(0, 512));
    var maximum = FieldKey.of("maximum", ValueTypes.integer(0, 512));
    var references = FieldKey.of("references", ValueTypes.list(ValueTypes.text(128), 16));
    var type =
        RecordType.<Profile>builder(
                values ->
                    new Profile(
                        values.get(name),
                        values.get(minimum),
                        values.get(maximum),
                        values.get(references)))
            .field(name, Profile::name)
            .field(minimum, Profile::minimum)
            .field(maximum, Profile::maximum)
            .field(references, Profile::references)
            .build();
    var definition =
        new DocumentDefinition<>(
            DocumentId.parse("example:profile"),
            1,
            type,
            () -> new Profile("Default", 16, 128, List.of()),
            profile ->
                profile.minimum() <= profile.maximum()
                    ? ValidationReport.VALID
                    : new ValidationReport(
                        List.of(
                            new Issue(
                                "range.order",
                                Issue.Severity.ERROR,
                                FieldPath.ROOT.field("minimum"),
                                List.of(FieldPath.ROOT.field("maximum")),
                                java.util.Map.of(),
                                "Minimum must not exceed maximum")),
                        true));
    var limits = new JsonLimits(8192, 16, 512, 256, 64, 1024);
    var good =
        definition.decode(
            JsonInput.read(
                "{\"name\":\"Review\",\"minimum\":32,\"maximum\":96,\"references\":[\"optional:missing\"]}",
                limits));
    if (!good.report().valid() || good.value().orElseThrow().minimum() != 32) {
      throw new IllegalStateException("Public valid candidate failed");
    }
    var invalid =
        definition.decode(
            JsonInput.read(
                "{\"name\":\"Review\",\"minimum\":96,\"maximum\":32,\"references\":[]}", limits));
    if (invalid.value().isPresent()
        || !invalid.report().issues().getFirst().code().equals("range.order")) {
      throw new IllegalStateException("Public cross-field diagnostics failed");
    }
    var fraction =
        definition.decode(
            JsonInput.read(
                "{\"name\":\"Review\",\"minimum\":32.5,\"maximum\":96,\"references\":[]}", limits));
    if (fraction.value().isPresent())
      throw new IllegalStateException("Fraction was silently truncated");
    if (definition.defaults().minimum() != 16) throw new IllegalStateException("Defaults changed");
    System.out.println(
        "Packaged public consumer passed: valid/invalid/fraction/defaults; game classes absent");
  }

  private static void refuseGameClasspath(String name) {
    try {
      Class.forName(name, false, ReviewProfile.class.getClassLoader());
      throw new IllegalStateException("Headless fixture unexpectedly contains " + name);
    } catch (ClassNotFoundException expected) {
      // The combined JAR's core API must resolve without loading its NeoForge entry point.
    }
  }

  private ReviewProfile() {}
}
