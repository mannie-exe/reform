package design.inherent.reform.storage;

import static org.junit.jupiter.api.Assertions.*;

import design.inherent.reform.definition.DocumentDefinition;
import design.inherent.reform.definition.DocumentId;
import design.inherent.reform.definition.FieldKey;
import design.inherent.reform.definition.FieldPath;
import design.inherent.reform.definition.RecordType;
import design.inherent.reform.edit.Candidate;
import design.inherent.reform.edit.DraftLimits;
import design.inherent.reform.edit.DraftNode;
import design.inherent.reform.edit.DraftTree;
import design.inherent.reform.serialization.JsonLimits;
import design.inherent.reform.value.ValueTypes;
import java.io.StringReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PropertiesStoreTest {
  @TempDir Path root;

  record Flags(boolean world, boolean client, int count, String label) {}

  private static final FieldKey<Boolean> WORLD =
      FieldKey.of("world", ValueTypes.defaultedField(ValueTypes.bool(), true));
  private static final FieldKey<Boolean> CLIENT =
      FieldKey.of("client", ValueTypes.defaultedField(ValueTypes.bool(), false));
  private static final FieldKey<Integer> COUNT = FieldKey.of("count", ValueTypes.integer(0, 100));
  private static final FieldKey<String> LABEL = FieldKey.of("label", ValueTypes.text(128));
  private static final RecordType<Flags> TYPE =
      RecordType.<Flags>builder(
              v -> new Flags(v.get(WORLD), v.get(CLIENT), v.get(COUNT), v.get(LABEL)))
          .field(WORLD, Flags::world)
          .field(CLIENT, Flags::client)
          .field(COUNT, Flags::count)
          .field(LABEL, Flags::label)
          .build();
  private static final DocumentDefinition<Flags> DEFINITION =
      new DocumentDefinition<>(
          DocumentId.parse("fixture:properties"),
          1,
          TYPE,
          () -> new Flags(true, false, 2, "Default"));
  private static final JsonLimits JSON = new JsonLimits(32768, 32, 2048, 4096, 128, 4096);

  private PropertiesStore<Flags> store(String name) {
    return new PropertiesStore<>(
        DEFINITION,
        new OwnedFile(StorageBinding.relative(root, name), 32768),
        JSON,
        DraftLimits.CONFIGURATION);
  }

  private static Properties nativeValues(String text) throws Exception {
    var p = new Properties();
    p.load(new StringReader(text));
    return p;
  }

  private Candidate<Flags> candidate(Flags flags) {
    return DraftTree.from(TYPE, flags, DraftLimits.CONFIGURATION)
        .inspect(DEFINITION, JSON)
        .candidate()
        .orElseThrow();
  }

  @Test
  void preservesCommentsEscapedKeysAndUnchangedContinuationsForAllNativeLineEndings()
      throws Exception {
    int index = 0;
    for (String nl : List.of("\n", "\r\n", "\r")) {
      var store = store("flags-" + (index++) + ".properties");
      String source =
          "# author"
              + nl
              + "w\\u006Frld=tr\\u0075e"
              + nl
              + "! comment\\"
              + nl
              + "label=first\\"
              + nl
              + "  second"
              + nl
              + "count=2"
              + nl;
      Files.writeString(store.binding().path(), source);
      var before = store.read();
      assertEquals(TextStore.State.READY, before.state(), () -> before.report().toString());
      assertEquals(
          new Flags(true, false, 2, "firstsecond"), before.candidate().orElseThrow().value());
      var time = Files.getLastModifiedTime(store.binding().path());
      assertEquals(
          OwnedFile.Persistence.UNCHANGED,
          store.write(before, before.candidate().orElseThrow()).persistence().state());
      assertEquals(time, Files.getLastModifiedTime(store.binding().path()));
      assertEquals(source, Files.readString(store.binding().path()));
      var edited =
          before
              .draft()
              .orElseThrow()
              .change(FieldPath.ROOT.field("count"), ignored -> new DraftNode.Scalar("3"));
      var written = store.write(before, edited.inspect(DEFINITION, JSON).candidate().orElseThrow());
      assertEquals(OwnedFile.Persistence.SAVED, written.persistence().state());
      String after = Files.readString(store.binding().path());
      assertEquals(source.replace("count=2", "count=3"), after);
      assertEquals("3", nativeValues(after).getProperty("count"));
      assertEquals("firstsecond", nativeValues(after).getProperty("label"));
      assertFalse(nativeValues(after).containsKey("client"));
    }
  }

  @Test
  void missingReadDoesNotCreateAndExplicitInitializationRetainsDeclaredOmissionDefaults()
      throws Exception {
    var store = store("new.properties");
    assertEquals(TextStore.State.MISSING, store.read().state());
    assertFalse(Files.exists(store.binding().path()));
    var initialized = store.initializeDefaultsIfMissing();
    assertEquals(OwnedFile.Persistence.SAVED, initialized.persistence().state());
    String source = Files.readString(store.binding().path());
    assertFalse(source.contains("schemaVersion"));
    assertFalse(nativeValues(source).containsKey("world"));
    assertFalse(nativeValues(source).containsKey("client"));
    assertEquals(DEFINITION.defaults(), store.read().candidate().orElseThrow().value());
    assertTrue(
        store.read().draft().orElseThrow().at(FieldPath.ROOT.field("world"))
            instanceof DraftNode.Defaulted);
  }

  @Test
  void nativeBooleanSpellingDuplicatesEscapedDuplicatesAndMalformedUnicodeStayInvalid()
      throws Exception {
    var cases =
        Map.of(
            "upper", "world=TRUE\nlabel=ok\ncount=2\n",
            "space", "world=true \nlabel=ok\ncount=2\n",
            "duplicate", "world=true\nworld=false\nlabel=ok\ncount=2\n",
            "escaped", "world=true\n\\u0077orld=false\nlabel=ok\ncount=2\n",
            "unicode", "world=true\nlabel=\\uNOTHEX\ncount=2\n");
    for (var entry : cases.entrySet()) {
      var store = store(entry.getKey() + ".properties");
      Files.writeString(store.binding().path(), entry.getValue());
      var before = store.read();
      assertEquals(TextStore.State.INVALID, before.state(), entry.getKey());
      assertTrue(before.candidate().isEmpty());
      assertFalse(before.report().valid());
      assertEquals(
          OwnedFile.Persistence.UNCHANGED,
          store.initializeDefaultsIfMissing().persistence().state());
      assertEquals(entry.getValue(), Files.readString(store.binding().path()));
    }
  }

  @Test
  void unknownPropertyRemainsUntilExplicitRepairAndOnlyThatLogicalPropertyIsRemoved()
      throws Exception {
    var store = store("unknown.properties");
    String source = "# retained\rworld=false\rremoved=false\rlabel=ok\rcount=2\r";
    Files.writeString(store.binding().path(), source);
    var before = store.read();
    assertEquals(TextStore.State.INVALID, before.state());
    var repaired =
        before
            .draft()
            .orElseThrow()
            .change(FieldPath.ROOT, node -> ((DraftNode.ObjectValue) node).without("removed"));
    assertEquals(source, Files.readString(store.binding().path()));
    var written = store.write(before, repaired.inspect(DEFINITION, JSON).candidate().orElseThrow());
    assertEquals(OwnedFile.Persistence.SAVED, written.persistence().state());
    assertEquals(source.replace("removed=false\r", ""), Files.readString(store.binding().path()));
  }

  @Test
  void appendedSettingCannotBeAbsorbedByAnUnfinishedNativeEofContinuation() throws Exception {
    int index = 0;
    for (String suffix : List.of("label=last\\", "label=last\\\n", "label=last\n# comment\\")) {
      var store = store("eof-" + (index++) + ".properties");
      String source = "count=2\n" + suffix;
      Files.writeString(store.binding().path(), source);
      var before = store.read();
      assertEquals(TextStore.State.READY, before.state());
      var written = store.write(before, candidate(new Flags(true, true, 2, "last")));
      assertEquals(OwnedFile.Persistence.SAVED, written.persistence().state());
      var actual = nativeValues(Files.readString(store.binding().path()));
      assertEquals("last", actual.getProperty("label"));
      assertEquals("true", actual.getProperty("client"));
      assertEquals("2", actual.getProperty("count"));
    }
  }

  @Test
  void changedContinuationUsesNativeEscapingWithoutTimestampOrLosingOtherProperties()
      throws Exception {
    var store = store("unicode.properties");
    String source = "# owned\nlabel=first\\\n second\ncount=2\nworld=false\n";
    Files.writeString(store.binding().path(), source);
    String value = "café 🐈 \t\n\\:=#! trailing ";
    var written = store.write(store.read(), candidate(new Flags(false, false, 2, value)));
    assertEquals(OwnedFile.Persistence.SAVED, written.persistence().state());
    String after = Files.readString(store.binding().path());
    assertTrue(after.startsWith("# owned\n"));
    assertTrue(after.endsWith("count=2\nworld=false\n"));
    assertEquals(1, after.lines().filter(s -> s.startsWith("#")).count());
    assertEquals(value, nativeValues(after).getProperty("label"));
    assertEquals(value, store.read().candidate().orElseThrow().value().label());
  }

  @Test
  void propertyAdmissionRefusesCompleteInputAtEntryLineKeyAndValueBounds() throws Exception {
    int index = 0;
    for (var limits :
        List.of(
            new PropertiesStore.Limits(4, 16, 16, 16),
            new PropertiesStore.Limits(4, 2, 16, 16),
            new PropertiesStore.Limits(4, 16, 3, 16),
            new PropertiesStore.Limits(4, 16, 16, 1))) {
      var binding = StorageBinding.relative(root, "limit-" + (index++) + ".properties");
      String source =
          index == 1
              ? "world=true\nclient=false\ncount=2\nlabel=ok\nextra=x\n"
              : "world=true\ncount=2\nlabel=ok\n";
      Files.writeString(binding.path(), source);
      var store =
          new PropertiesStore<>(
              DEFINITION, new OwnedFile(binding, 32768), JSON, DraftLimits.CONFIGURATION, limits);
      var observed = store.read();
      assertEquals(TextStore.State.UNAVAILABLE, observed.state());
      assertFalse(observed.report().complete());
      assertTrue(observed.candidate().isEmpty());
      assertTrue(observed.draft().isEmpty());
      assertEquals(source, Files.readString(binding.path()));
    }
  }

  @Test
  void registrationRejectsStructuralPropertiesWithoutCallingDefaultsAgain() {
    var calls = new AtomicInteger();
    var nested = FieldKey.of("values", ValueTypes.list(ValueTypes.bool(), 8));
    record Nested(List<Boolean> values) {}
    var type =
        RecordType.<Nested>builder(v -> new Nested(v.get(nested)))
            .field(nested, Nested::values)
            .build();
    var definition =
        new DocumentDefinition<>(
            DocumentId.parse("fixture:unsupported_properties"),
            1,
            type,
            () -> {
              calls.incrementAndGet();
              return new Nested(List.of());
            });
    assertEquals(1, calls.get());
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new PropertiesStore<>(
                definition,
                new OwnedFile(StorageBinding.relative(root, "unsupported.properties"), 32768),
                JSON,
                DraftLimits.CONFIGURATION));
    assertEquals(1, calls.get());
    assertFalse(Files.exists(root.resolve("unsupported.properties")));
  }

  @Test
  void formatsDoNotShareObservedTokensEvenWithTheSameDefinition() throws Exception {
    var json =
        new JsonStore<>(
            DEFINITION,
            new OwnedFile(StorageBinding.relative(root, "foreign.json"), 32768),
            JsonStore.Format.EXISTING_BARE,
            JSON,
            DraftLimits.CONFIGURATION);
    var before = json.initializeDefaultsIfMissing().observation().orElseThrow();
    var properties = store("foreign.properties");
    assertThrows(
        IllegalArgumentException.class,
        () -> properties.write(before, candidate(DEFINITION.defaults())));
    assertFalse(Files.exists(properties.binding().path()));
  }
}
