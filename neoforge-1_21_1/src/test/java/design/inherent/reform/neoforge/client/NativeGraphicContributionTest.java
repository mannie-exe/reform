package design.inherent.reform.minecraft.client;

import static org.junit.jupiter.api.Assertions.*;

import design.inherent.reform.definition.DocumentId;
import design.inherent.reform.presentation.graphics.*;
import design.inherent.reform.reference.ReferenceStamp;
import design.inherent.reform.value.ValueType;
import design.inherent.reform.value.ValueTypes;
import java.util.*;
import org.junit.jupiter.api.Test;

class NativeGraphicContributionTest {
  private static LoadedGraphicRequest.Stamp stamp() {
    var context = UUID.randomUUID();
    return new LoadedGraphicRequest.Stamp(
        DocumentId.parse("test:native"),
        UUID.randomUUID(),
        UUID.randomUUID(),
        context,
        new ReferenceStamp(context, DocumentId.parse("test:resources"), UUID.randomUUID(), "one"));
  }

  @Test
  void constructionFreezesAndAccountsForInputWithoutEnteringNativePreparation() {
    var input = new ArrayList<>(List.of("snowman ☃"));
    var facts =
        new ArrayList<>(
            List.of(new GraphicSceneData.Fact(DocumentId.parse("test:input"), "Input", "Copied")));
    var adapter =
        new NativeGraphicAdapter<List<String>>() {
          public DocumentId id() {
            return DocumentId.parse("test:adapter");
          }

          public ValueType<List<String>> inputType() {
            return ValueTypes.list(ValueTypes.text(64), 2);
          }

          public Prepared prepare(List<String> value, NativeGraphicPreparationContext context) {
            throw new AssertionError("Construction entered a native stage");
          }
        };
    var contribution =
        new NativeGraphicContribution<>(adapter, input, stamp(), GraphicCamera.STANDARD, facts);
    input.clear();
    facts.clear();
    assertEquals(List.of("snowman ☃"), contribution.input());
    assertThrows(UnsupportedOperationException.class, () -> contribution.input().clear());
    long encoded = "[\"snowman ☃\"]".getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
    assertEquals(
        encoded + GraphicSceneData.factBytes(contribution.facts()), contribution.outputBytes());
    assertEquals(DocumentId.parse("test:adapter"), contribution.adapterId());
  }

  @Test
  void unknownContributionCannotBecomeAReadyNativeLease() {
    var value =
        new GraphicScenePreparation.Contribution() {
          public GraphicSourceStamp stamp() {
            return NativeGraphicContributionTest.stamp();
          }

          public GraphicCamera camera() {
            return GraphicCamera.STANDARD;
          }

          public List<GraphicSceneData.Fact> facts() {
            return List.of();
          }

          public long outputBytes() {
            return 0;
          }
        };
    var result = ClientGraphicHost.Result.from(value);
    assertEquals(ClientGraphicHost.State.UNAVAILABLE, result.state());
    assertTrue(
        result.report().issues().stream()
            .anyMatch(issue -> issue.code().equals("preview.provider_unavailable")));
    assertTrue(result.stamp().isEmpty());
  }
}
