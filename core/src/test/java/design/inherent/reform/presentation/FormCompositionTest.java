package design.inherent.reform.presentation;

import static org.junit.jupiter.api.Assertions.*;

import com.mojang.serialization.JsonOps;
import design.inherent.reform.definition.FieldKey;
import design.inherent.reform.definition.FieldPath;
import design.inherent.reform.definition.RecordType;
import design.inherent.reform.edit.DraftLimits;
import design.inherent.reform.edit.DraftTree;
import design.inherent.reform.serialization.JsonInput;
import design.inherent.reform.serialization.JsonLimits;
import design.inherent.reform.value.ValueTypes;
import java.util.List;
import org.junit.jupiter.api.Test;

class FormCompositionTest {
  private record Values(int a, int b, int c, int d) {}

  private static final FieldPath A = FieldPath.ROOT.field("a"),
      B = FieldPath.ROOT.field("b"),
      C = FieldPath.ROOT.field("c"),
      D = FieldPath.ROOT.field("d");
  private static final FieldKey<Integer> KA = FieldKey.of("a", ValueTypes.integer(0, 100)),
      KB = FieldKey.of("b", ValueTypes.integer(0, 100)),
      KC = FieldKey.of("c", ValueTypes.integer(0, 100)),
      KD = FieldKey.of("d", ValueTypes.integer(0, 100));
  private static final RecordType<Values> TYPE =
      RecordType.<Values>builder(v -> new Values(v.get(KA), v.get(KB), v.get(KC), v.get(KD)))
          .field(KA, Values::a)
          .field(KB, Values::b)
          .field(KC, Values::c)
          .field(KD, Values::d)
          .build();

  private static FormLayout.Group group(String id, List<FieldPath> fields) {
    return new FormLayout.Group(id, "fixture." + id, id, "Purpose", fields);
  }

  private static FormModel model() {
    return new FormModel(
        TYPE, DraftTree.from(TYPE, new Values(1, 2, 3, 4), DraftLimits.CONFIGURATION));
  }

  private static FormCatalog catalog() {
    return new FormCatalog(
        List.of(A, B, C, D).stream()
            .map(
                path ->
                    new FormCatalog.Section(
                        path, "fixture." + FormModel.key(path), path.toString(), "Help"))
            .toList());
  }

  private static List<FieldPath> paths(List<FormComposition.Part> flow) {
    return FormComposition.fields(flow).stream().map(FormModel.Node::path).toList();
  }

  @Test
  void bodyAndCatalogShareAuthoredGroupRowOrderWithoutChangingCompressedCodec() {
    var value = new Values(1, 2, 3, 4);
    var encoded = TYPE.codec().encodeStart(JsonOps.COMPRESSED, value).getOrThrow();
    var catalog = catalog();
    var presentation =
        new FormPresentation(List.of(), FormDependencies.NONE, catalog)
            .withLayout(
                new FormLayout(
                    List.of(new FormLayout.Row("profiles", List.of(B, C))),
                    List.of(group("styling", List.of(A, D)), group("selection", List.of(B, C)))));
    var model = model();
    var flow = FormComposition.resolve(model.root().children(), presentation);
    assertEquals(List.of(A, D, B, C), paths(flow));
    assertEquals("group:styling", ((FormComposition.Box) flow.getFirst()).id());
    assertInstanceOf(
        FormComposition.Row.class, ((FormComposition.Box) flow.get(1)).children().getFirst());
    assertEquals(
        List.of(FieldPath.ROOT, A, D, B, C),
        catalog.snapshot(model, presentation).entries().stream()
            .map(FormCatalog.Entry::path)
            .toList());
    assertEquals(
        List.of(FieldPath.ROOT, A, B, C, D),
        catalog.snapshot(model).entries().stream().map(FormCatalog.Entry::path).toList());
    assertEquals(encoded, TYPE.codec().encodeStart(JsonOps.COMPRESSED, value).getOrThrow());
    assertEquals("[1,2,3,4]", encoded.toString());
    assertThrows(UnsupportedOperationException.class, () -> flow.clear());
  }

  @Test
  void rangeOrderAndPartialActiveCompositionPreserveOnlyActualNodes() {
    var presentation =
        new FormPresentation(
                List.of(
                    new FormPresentation.Range(
                        "limits", "fixture.limits", "Bounds", "Owner validation", C, B)))
            .withLayout(
                new FormLayout(
                    List.of(),
                    List.of(group("styling", List.of(A, D)), group("selection", List.of(B, C)))));
    var siblings = model().root().children();
    var flow = FormComposition.resolve(siblings, presentation);
    assertEquals(List.of(A, D, C, B), paths(flow));
    var box = (FormComposition.Box) flow.get(1);
    assertEquals("range:limits", ((FormComposition.Box) box.children().getFirst()).id());
    var partial =
        FormComposition.resolve(
            siblings.stream().filter(n -> n.path().equals(B)).toList(), presentation);
    assertEquals(List.of(B), paths(partial));
    assertSame(siblings.get(1), FormComposition.fields(partial).getFirst());
    assertThrows(
        IllegalArgumentException.class,
        () ->
            FormComposition.resolve(
                List.of(siblings.getFirst(), siblings.getFirst()), presentation));
  }

  @Test
  void invalidUnknownDraftInputSurvivesPresentationOrdering() throws Exception {
    var draft =
        DraftTree.fromParsed(
            TYPE,
            JsonInput.read(
                "{\"a\":1,\"b\":\"bad\",\"c\":3,\"d\":4,\"extra\":false}",
                new JsonLimits(8192, 32, 1024, 512, 64, 1024)),
            DraftLimits.CONFIGURATION);
    var model = new FormModel(TYPE, draft);
    var presentation =
        FormPresentation.EMPTY.withLayout(
            new FormLayout(List.of(), List.of(group("style", List.of(D, A)))));
    var actual =
        FormComposition.fields(FormComposition.resolve(model.root().children(), presentation));
    assertEquals(
        List.of(D, A, B, C, FieldPath.ROOT.field("extra")),
        actual.stream().map(FormModel.Node::path).toList());
    assertEquals(FormModel.Role.UNKNOWN, actual.getLast().role());
    assertSame(model.root().children().get(1).input(), actual.get(2).input());
    assertSame(model.root().children().getLast().input(), actual.getLast().input());
  }
}
