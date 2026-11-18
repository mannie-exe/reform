package design.inherent.reform.edit;

import static org.junit.jupiter.api.Assertions.*;

import design.inherent.reform.definition.DocumentDefinition;
import design.inherent.reform.definition.DocumentId;
import design.inherent.reform.definition.FieldKey;
import design.inherent.reform.definition.FieldPath;
import design.inherent.reform.definition.RecordType;
import design.inherent.reform.serialization.ExactCodecs;
import design.inherent.reform.serialization.JsonInput;
import design.inherent.reform.serialization.JsonLimits;
import design.inherent.reform.serialization.SourcePath;
import design.inherent.reform.value.SnapshotPolicy;
import design.inherent.reform.value.ValueType;
import design.inherent.reform.value.ValueTypes;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class MappingDraftTest {
  private static final JsonLimits JSON = new JsonLimits(32768, 32, 4096, 2048, 128, 1024);
  private static final DraftLimits DRAFT = DraftLimits.CONFIGURATION;
  private static final ValueType<Map<String, Integer>> TYPE =
      ValueTypes.objectMap(ValueTypes.text(64), ValueTypes.integer(0, 100), 16);
  private static final DocumentDefinition<Map<String, Integer>> DEFINITION = definition(TYPE);

  private static <T> DocumentDefinition<Map<T, Integer>> definition(
      ValueType<Map<T, Integer>> type) {
    return new DocumentDefinition<>(DocumentId.parse("fixture:map"), 1, type, Map::of);
  }

  private static DraftTree draft(ValueType<?> type, String json) throws Exception {
    return DraftTree.fromParsed(type, JsonInput.read(json, JSON), DRAFT);
  }

  private static DraftNode.Mapping mapping(DraftTree tree) {
    return (DraftNode.Mapping) tree.root();
  }

  private static FieldPath part(DraftNode.Row row, FieldPath.Part part) {
    return FieldPath.ROOT.row(row.id()).append(part);
  }

  private static EditSession<Map<String, Integer>> session(String source) throws Exception {
    return EditSession.fromSource(
        DEFINITION, UUID.randomUUID(), "source-1", JsonInput.read(source, JSON), JSON, DRAFT);
  }

  @Test
  void objectKeysAndValuesHaveDistinctStableSourceAddresses() throws Exception {
    var tree = draft(TYPE, "{\"\":1,\"a/~key.0\":2}");
    var first = mapping(tree).rows().getFirst();
    var second = mapping(tree).rows().get(1);
    var candidate = tree.inspect(DEFINITION, JSON).candidate().orElseThrow();
    assertEquals(
        part(first, FieldPath.Part.KEY), candidate.addresses().get(SourcePath.ROOT.key("")));
    assertEquals(
        part(first, FieldPath.Part.VALUE), candidate.addresses().get(SourcePath.ROOT.property("")));
    assertEquals(
        part(second, FieldPath.Part.KEY),
        candidate.addresses().get(SourcePath.ROOT.key("a/~key.0")));
    assertEquals(
        part(second, FieldPath.Part.VALUE),
        candidate.addresses().get(SourcePath.ROOT.property("a/~key.0")));
    assertNotEquals(SourcePath.ROOT.key("a"), SourcePath.ROOT.property("a"));
    assertNotEquals(
        SourcePath.ROOT.key("a").toString(), SourcePath.ROOT.property("~ka").toString());
    assertFalse(tree.contains(FieldPath.ROOT.row(first.id()).field("key")));
    assertFalse(tree.contains(FieldPath.ROOT.append(FieldPath.Part.KEY)));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            tree.change(
                FieldPath.ROOT.row(first.id()).field("key"), n -> new DraftNode.Scalar("wrong")));
  }

  @Test
  void duplicateAndIncompleteRawKeysSurviveUntilExplicitRepair() throws Exception {
    var type = ValueTypes.entryMap(ValueTypes.integer(0, 100), ValueTypes.integer(0, 100), 16);
    var definition = definition(type);
    var tree = draft(type, "[{\"key\":1,\"value\":10},{\"key\":1.00,\"value\":20}]");
    var rows = mapping(tree).rows();
    var inspection = tree.inspect(definition, JSON);
    assertTrue(inspection.candidate().isEmpty());
    assertEquals(2, rows.size());
    var issue =
        inspection.report().issues().stream()
            .filter(i -> i.code().equals("input.duplicate_key"))
            .findFirst()
            .orElseThrow();
    assertEquals(part(rows.get(1), FieldPath.Part.KEY), issue.path());
    assertEquals(List.of(part(rows.getFirst(), FieldPath.Part.KEY)), issue.related());
    var incomplete =
        tree.change(part(rows.get(1), FieldPath.Part.KEY), n -> new DraftNode.Scalar("-"));
    assertEquals(
        DraftTree.InputState.INCOMPLETE,
        incomplete.inspect(definition, JSON).inputs().get(part(rows.get(1), FieldPath.Part.KEY)));
    var repaired =
        incomplete.change(part(rows.get(1), FieldPath.Part.KEY), n -> new DraftNode.Scalar("2"));
    assertEquals(
        Map.of(1, 10, 2, 20), repaired.inspect(definition, JSON).candidate().orElseThrow().value());
    assertEquals(rows.get(1).id(), mapping(repaired).rows().get(1).id());
    assertEquals("1.00", ((DraftNode.Scalar) tree.at(part(rows.get(1), FieldPath.Part.KEY))).raw());
  }

  @Test
  void normalizationCollisionsAreDiagnosedBeforeConstructingAMap() throws Exception {
    var key =
        ValueType.of(
            ExactCodecs.STRING.xmap(s -> s.toLowerCase(Locale.ROOT), s -> s),
            ValueTypes.text(16).shape(),
            SnapshotPolicy.<String>immutable());
    var type = ValueTypes.objectMap(key, ValueTypes.integer(0, 100), 16);
    var tree = draft(type, "{\"A\":1,\"a\":2}");
    var result = tree.inspect(definition(type), JSON);
    assertTrue(result.candidate().isEmpty());
    assertEquals(2, mapping(tree).rows().size());
    assertTrue(
        result.report().issues().stream()
            .anyMatch(i -> i.code().equals("input.duplicate_key") && !i.related().isEmpty()));
  }

  @Test
  void movingAndRenamingKeepDiagnosticsOnTheirOriginalRow() throws Exception {
    var session = session("{\"a\":1,\"b\":2}");
    var a = mapping(session.snapshot().draft()).rows().getFirst();
    var submitted = session.prepareSubmission().submission().orElseThrow();
    session.edit(FieldPath.ROOT, n -> ((DraftNode.Mapping) n).move(a.id(), 1), "Move map row");
    assertFalse(session.dirty(), "Map display order must not become a persisted change");
    assertEquals(
        EditSession.TargetState.CURRENT,
        session.locate(submitted, SourcePath.ROOT.key("a")).state());
    session.setRaw(part(a, FieldPath.Part.KEY), "renamed");
    assertEquals(
        EditSession.TargetState.STALE_VALUE,
        session.locate(submitted, SourcePath.ROOT.key("a")).state());
    assertEquals(
        EditSession.TargetState.CURRENT,
        session.locate(submitted, SourcePath.ROOT.property("a")).state());
    assertEquals(
        part(a, FieldPath.Part.KEY), session.locate(submitted, SourcePath.ROOT.key("a")).path());
    session.edit(FieldPath.ROOT, n -> ((DraftNode.Mapping) n).remove(a.id()), "Remove map row");
    assertEquals(
        EditSession.TargetState.REMOVED,
        session.locate(submitted, SourcePath.ROOT.key("a")).state());
    assertTrue(session.undo());
    assertEquals(
        "renamed",
        ((DraftNode.Scalar) session.snapshot().draft().at(part(a, FieldPath.Part.KEY))).raw());
    assertTrue(session.undo());
    assertFalse(session.dirty());
  }

  @Test
  void acknowledgementMatchesLogicalKeysDespiteBackendReorderingAndPreservesLateEdits()
      throws Exception {
    var session = session("{\"a\":1,\"b\":2}");
    var a = mapping(session.snapshot().draft()).rows().getFirst();
    var b = mapping(session.snapshot().draft()).rows().get(1);
    session.setRaw(part(a, FieldPath.Part.VALUE), "3.00");
    var submitted = session.prepareSubmission().submission().orElseThrow();
    session.markInFlight(submitted);
    session.setRaw(part(b, FieldPath.Part.KEY), "later");
    session.setRaw(part(b, FieldPath.Part.VALUE), "-");
    var actual = draft(TYPE, "{\"b\":2,\"a\":3}");
    assertEquals(
        EditSession.Acknowledgement.ACCEPTED, session.acknowledge(submitted, actual, "source-2"));
    assertEquals(
        List.of(b.id(), a.id()),
        mapping(session.baseline()).rows().stream().map(DraftNode.Row::id).toList());
    assertEquals(
        "later",
        ((DraftNode.Scalar) session.snapshot().draft().at(part(b, FieldPath.Part.KEY))).raw());
    assertEquals(
        "-",
        ((DraftNode.Scalar) session.snapshot().draft().at(part(b, FieldPath.Part.VALUE))).raw());
    assertTrue(session.undo());
    assertTrue(session.dirty());
    assertTrue(session.undo());
    assertFalse(session.dirty());
    assertEquals(
        Map.of("a", 3, "b", 2), session.snapshot().inspect().candidate().orElseThrow().value());
    assertTrue(session.redo());
    assertEquals(
        "later",
        ((DraftNode.Scalar) session.snapshot().draft().at(part(b, FieldPath.Part.KEY))).raw());
  }

  @Test
  void encodedAliasesAlignByDecodedKeyRatherThanRawSpelling() throws Exception {
    var key =
        ValueType.of(
            ExactCodecs.STRING.xmap(s -> s.toLowerCase(Locale.ROOT), s -> s),
            ValueTypes.text(16).shape(),
            SnapshotPolicy.<String>immutable());
    var type = ValueTypes.objectMap(key, ValueTypes.integer(0, 100), 16);
    var definition = definition(type);
    var session =
        EditSession.fromSource(
            definition,
            UUID.randomUUID(),
            "first",
            JsonInput.read("{\"A\":1,\"B\":2}", JSON),
            JSON,
            DRAFT);
    var rows = mapping(session.snapshot().draft()).rows();
    var submitted = session.prepareSubmission().submission().orElseThrow();
    assertEquals(
        EditSession.Acknowledgement.ACCEPTED,
        session.acknowledge(submitted, draft(type, "{\"b\":2,\"a\":1}"), "next"));
    assertEquals(
        List.of(rows.get(1).id(), rows.getFirst().id()),
        mapping(session.snapshot().draft()).rows().stream().map(DraftNode.Row::id).toList());
    assertEquals(
        "a",
        ((DraftNode.Scalar)
                session.snapshot().draft().at(part(rows.getFirst(), FieldPath.Part.KEY)))
            .raw());
    assertFalse(session.dirty());
  }

  @Test
  void compositeKeysRetainNestedNumericSpellingAndRowAddresses() throws Exception {
    var key = ValueTypes.list(ValueTypes.integer(0, 100), 8);
    var type = ValueTypes.entryMap(key, ValueTypes.integer(0, 100), 16);
    var tree = draft(type, "[{\"key\":[1.00e1],\"value\":2.00}]");
    var entry = mapping(tree).rows().getFirst();
    var keyRow = ((DraftNode.Sequence) tree.at(part(entry, FieldPath.Part.KEY))).rows().getFirst();
    var path = part(entry, FieldPath.Part.KEY).row(keyRow.id());
    var candidate = tree.inspect(definition(type), JSON).candidate().orElseThrow();
    assertEquals(Map.of(List.of(10), 2), candidate.value());
    assertTrue(candidate.authoredJson().contains("[1.00e1]"));
    assertEquals(
        path, candidate.addresses().get(SourcePath.ROOT.index(0).property("key").index(0)));
    assertEquals("1.00e1", ((DraftNode.Scalar) tree.at(path)).raw());
  }

  @Test
  void nestedMapRecordAndListPathsDoNotConfuseMapPartsWithLiteralFieldNames() throws Exception {
    record Value(int key) {}
    var keyField = FieldKey.of("key", ValueTypes.integer(0, 100));
    var value =
        RecordType.<Value>builder(v -> new Value(v.get(keyField)))
            .field(keyField, Value::key)
            .build();
    var type = ValueTypes.objectMap(ValueTypes.text(16), ValueTypes.list(value, 4), 4);
    var definition =
        new DocumentDefinition<>(DocumentId.parse("fixture:nested_map"), 1, type, Map::of);
    var tree = draft(type, "{\"x\":[{\"key\":3}]}");
    var row = mapping(tree).rows().getFirst();
    var listPath = part(row, FieldPath.Part.VALUE);
    var child = ((DraftNode.Sequence) tree.at(listPath)).rows().getFirst();
    var field = listPath.row(child.id()).field("key");
    var edited = tree.change(field, n -> new DraftNode.Scalar("4"));
    assertEquals(
        Map.of("x", List.of(new Value(4))),
        edited.inspect(definition, JSON).candidate().orElseThrow().value());
    assertFalse(edited.contains(listPath.row(child.id()).append(FieldPath.Part.KEY)));
  }

  @Test
  void malformedEntryRowsAndUnknownDataAreRetainedForExplicitRepair() throws Exception {
    var type = ValueTypes.entryMap(ValueTypes.text(16), ValueTypes.integer(0, 100), 8);
    var tree = draft(type, "[null,{\"value\":2,\"unknown\":1},false]");
    var rows = mapping(tree).rows();
    assertEquals(3, rows.size());
    assertInstanceOf(DraftNode.Opaque.class, rows.getFirst().value());
    assertInstanceOf(DraftNode.Missing.class, tree.at(part(rows.get(1), FieldPath.Part.KEY)));
    assertTrue(((DraftNode.ObjectValue) rows.get(1).value()).fields().containsKey("unknown"));
    assertTrue(tree.inspect(definition(type), JSON).candidate().isEmpty());
    var repaired =
        tree.change(FieldPath.ROOT, n -> new DraftNode.Mapping(List.of(rows.get(1))))
            .change(
                FieldPath.ROOT.row(rows.get(1).id()),
                n -> ((DraftNode.ObjectValue) n).without("unknown"))
            .change(part(rows.get(1), FieldPath.Part.KEY), n -> new DraftNode.Scalar("fixed"));
    assertEquals(
        Map.of("fixed", 2),
        repaired.inspect(definition(type), JSON).candidate().orElseThrow().value());
  }

  @Test
  void mapValueEqualityIgnoresSessionIdentityAndOrderWithoutDiscardingDuplicateCounts() {
    var first =
        DraftNode.Row.create(
            new DraftNode.ObjectValue(
                Map.of("key", new DraftNode.Scalar("a"), "value", new DraftNode.Scalar("1"))));
    var second =
        DraftNode.Row.create(
            new DraftNode.ObjectValue(
                Map.of("key", new DraftNode.Scalar("b"), "value", new DraftNode.Scalar("2"))));
    var a = new DraftNode.Mapping(List.of(first, second));
    var b =
        new DraftNode.Mapping(
            List.of(
                new DraftNode.Row(UUID.randomUUID(), second.value()),
                new DraftNode.Row(UUID.randomUUID(), first.value())));
    assertTrue(DraftNode.sameValue(a, b));
    assertFalse(
        DraftNode.sameValue(
            a,
            new DraftNode.Mapping(
                List.of(first, new DraftNode.Row(UUID.randomUUID(), first.value())))));
    assertThrows(
        IllegalArgumentException.class, () -> new DraftNode.Mapping(List.of(first, first)));
  }

  @Test
  void sourceDuplicateNamesAreRejectedBeforeDomConversionAndDraftBudgetDoesNotMutate()
      throws Exception {
    assertThrows(
        design.inherent.reform.serialization.JsonInputException.class,
        () -> JsonInput.read("{\"a\":1,\"a\":2}", JSON));
    var tree = draft(TYPE, "{\"a\":1}");
    var row = mapping(tree).rows().getFirst();
    var bounded = new DraftTree(tree.root(), new DraftLimits(8, 32, 16, 4, 2048));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            bounded.change(
                part(row, FieldPath.Part.KEY), n -> new DraftNode.Scalar("a".repeat(100))));
    assertEquals("a", ((DraftNode.Scalar) bounded.at(part(row, FieldPath.Part.KEY))).raw());
    var tooMany =
        draft(
            ValueTypes.objectMap(ValueTypes.text(16), ValueTypes.integer(0, 100), 1),
            "{\"a\":1,\"b\":2}");
    assertEquals(2, mapping(tooMany).rows().size());
    assertTrue(
        tooMany
            .inspect(
                definition(
                    ValueTypes.objectMap(ValueTypes.text(16), ValueTypes.integer(0, 100), 1)),
                JSON)
            .candidate()
            .isEmpty());
  }
}
