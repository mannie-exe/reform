package design.inherent.reform.reference;

import com.google.gson.Gson;
import com.google.gson.JsonPrimitive;
import com.mojang.serialization.JsonOps;
import design.inherent.reform.definition.*;
import design.inherent.reform.edit.*;
import design.inherent.reform.operation.DocumentActions;
import design.inherent.reform.owner.EditorActor;
import design.inherent.reform.owner.PolicyRefusal;
import design.inherent.reform.presentation.graphics.*;
import design.inherent.reform.serialization.JsonLimits;
import design.inherent.reform.validation.*;
import design.inherent.reform.value.ValueType;
import java.util.*;
import java.util.function.Consumer;

/** Borrowed inspected-reference admission, not a setter, source resolver or portable authority. */
public final class ReferenceAudition<T> {
  private static final Gson JSON = new Gson();
  private static final DocumentId TRANSFORM = DocumentId.parse("reform:reference_field_audition");
  private final DocumentActions<T> document;
  private final ReferenceBrowser<?> browser;
  private final FieldPath field;
  private final ValueType<?> type;
  private final String role, key;
  private final ReferenceStamp stamp;
  private final Object inspected;

  private ReferenceAudition(
      String role, DocumentActions<T> document, FieldPath path, ReferenceBrowser<?> browser) {
    this.document = Objects.requireNonNull(document);
    this.browser = Objects.requireNonNull(browser);
    this.field = Objects.requireNonNull(path);
    if (Objects.requireNonNull(role).isBlank() || role.length() > 128)
      throw new IllegalArgumentException("An audition role must be nonblank and bounded");
    this.role = role;
    document.requireInspection();
    var revision = DraftRevision.of(document.view().snapshot().orElseThrow());
    document.requirePreviewCurrent(revision);
    browser.requireCurrent();
    stamp = browser.stamp();
    key =
        browser
            .selectedKey()
            .orElseThrow(
                () ->
                    new PolicyRefusal(
                        "reference.unresolved", "Inspect a resolved reference first"));
    if (key.length() > 32768)
      throw new PolicyRefusal(
          "preview.budget", "The reference key exceeds preview input admission");
    inspected = browser.selectedEpoch();
    type = ReferenceSelection.field(document, field, stamp.source()).type();
    requireCurrent(document, revision);
  }

  static <T> ReferenceAudition<T> capture(
      String role, DocumentActions<T> document, FieldPath path, ReferenceBrowser<?> browser) {
    return new ReferenceAudition<>(role, document, path, browser);
  }

  /** Opaque equality of the admitted inspection and role, not authority or value equality. */
  public boolean sameSelection(ReferenceAudition<?> other) {
    return other != null
        && document == other.document
        && browser == other.browser
        && inspected == other.inspected
        && field.equals(other.field)
        && type == other.type
        && role.equals(other.role)
        && stamp.equals(other.stamp)
        && key.equals(other.key);
  }

  public void requireCurrent(DocumentActions<T> actual, DraftRevision expected) {
    if (actual != document)
      throw new IllegalArgumentException("Use the audition's actual document service");
    document.requirePreviewCurrent(expected);
    browser.requireCurrent();
    if (!browser.stamp().equals(stamp)
        || browser.selectedEpoch() != inspected
        || browser.selectedKey().filter(key::equals).isEmpty())
      throw new PolicyRefusal(
          "reference.selection_stale", "The inspected reference changed; inspect it again");
    if (ReferenceSelection.field(document, field, stamp.source()).type() != type)
      throw new PolicyRefusal("preview.stale", "The audition field changed");
  }

  /**
   * Owner-thread admission yields detached data only; worker projection never enters the browser.
   */
  public Plan<T> plan(DocumentActions<T> actual, DraftRevision expected) {
    requireCurrent(actual, expected);
    var snapshot = actual.view().snapshot().orElseThrow();
    return new Plan<>(
        actual.definition(), expected, snapshot.target(field), type, stamp, role, key);
  }

  public static final class Plan<T> {
    private final DocumentDefinition<T> definition;
    private final DraftRevision expected;
    private final DraftTarget target;
    private final ValueType<?> type;
    private final ReferenceStamp reference;
    private final String role, key;

    private Plan(
        DocumentDefinition<T> definition,
        DraftRevision expected,
        DraftTarget target,
        ValueType<?> type,
        ReferenceStamp reference,
        String role,
        String key) {
      this.definition = definition;
      this.expected = expected;
      this.target = target;
      this.type = type;
      this.reference = reference;
      this.role = role;
      this.key = key;
    }

    /** Known serialized base/transformed input payload is charged before the transformed copy. */
    public Overlay<T> project(
        EditorActor.Preflight<T> base,
        JsonLimits json,
        GraphicLedger inputs,
        Consumer<GraphicLedger.Allocation> charged,
        Runnable checkpoint) {
      return project(base, json, inputs, charged, checkpoint, null);
    }

    /** An admitted base block grows before the transformed copy; it is never double-counted. */
    public Overlay<T> project(
        EditorActor.Preflight<T> base,
        JsonLimits json,
        GraphicLedger inputs,
        Consumer<GraphicLedger.Allocation> charged,
        Runnable checkpoint,
        GraphicLedger.Allocation admittedBase) {
      if (inputs.limits().nativeBytes() != inputs.limits().inputBytes())
        throw new IllegalArgumentException("Use a dedicated captured-input ledger");
      if (!expected.equals(base.target())
          || base.observation().snapshot().definition() != definition)
        throw new PolicyRefusal("preview.stale", "The base capture belongs to another draft");
      if (!base.report().valid() || base.candidate().isEmpty())
        throw new PolicyRefusal(
            "preview.input_invalid", "Repair the complete draft before auditioning a profile");
      checkpoint.run();
      var original = base.candidate().orElseThrow();
      if (DraftTypes.at(definition.type(), original.draft(), target.path()) != type)
        throw new PolicyRefusal("preview.stale", "The audition field type changed");
      var encoded = encode(type, key, original.draft().limits());
      if (!(original.draft().at(target.path()) instanceof DraftNode.Scalar before)
          || !(encoded instanceof DraftNode.Scalar after))
        throw new PolicyRefusal(
            "preview.reference_type", "This field has no scalar reference preview");
      long baseBytes = GraphicInputBasis.Content.of(original.authoredJson()).bytes();
      long projectedBytes =
          Math.addExact(
              baseBytes - GraphicInputBasis.Content.of(JSON.toJson(before.raw())).bytes(),
              GraphicInputBasis.Content.of(JSON.toJson(after.raw())).bytes());
      long metadata =
          Math.addExact(
              256L,
              GraphicInputBasis.Content.of(key).bytes()
                  + GraphicInputBasis.Content.of(role).bytes()
                  + GraphicInputBasis.Content.of(target.path().toString()).bytes()
                  + GraphicInputBasis.Content.of(reference.toString()).bytes());
      long copiedBytes = Math.addExact(Math.addExact(baseBytes, projectedBytes), metadata);
      if (copiedBytes > inputs.limits().inputBytes())
        throw new PolicyRefusal(
            "preview.budget", "The complete audition input exceeds its copied-input allowance");
      if (admittedBase == null) {
        var allocation = inputs.reserve(copiedBytes, 1);
        try {
          charged.accept(allocation);
        } catch (RuntimeException | Error failed) {
          allocation.close();
          throw failed;
        }
      } else inputs.grow(admittedBase, copiedBytes);
      checkpoint.run();
      var changed = original.draft().change(target.path(), ignored -> encoded);
      var inspected = changed.inspect(definition, json);
      if (!inspected.report().valid() || inspected.candidate().isEmpty())
        throw new PolicyRefusal(
            new ValidationReport(
                    List.of(
                        Issue.error(
                            "preview.audition_invalid",
                            target.path(),
                            "This profile does not produce a valid complete preview")),
                    true)
                .and(inspected.report()));
      var candidate = inspected.candidate().orElseThrow();
      var content = GraphicInputBasis.Content.of(candidate.authoredJson());
      if (content.bytes() != projectedBytes)
        throw new PolicyRefusal(
            "preview.input_size_changed", "The audition's encoded input changed during projection");
      checkpoint.run();
      return new Overlay<>(
          base,
          candidate,
          type,
          new GraphicInputBasis.Audition(target, reference, role, key, TRANSFORM, 1, content),
          copiedBytes);
    }
  }

  private static <V> DraftNode encode(ValueType<V> type, String key, DraftLimits limits) {
    var decoded = type.codec().parse(JsonOps.INSTANCE, new JsonPrimitive(key));
    if (decoded.error().isPresent() || decoded.result().isEmpty())
      throw new PolicyRefusal(
          "preview.reference_key",
          "The inspected key is not accepted by this field's declared codec");
    return DraftTree.from(type, decoded.result().orElseThrow(), limits).root();
  }

  /** Validated frozen data; owner backend verification and currentness are still separate. */
  public static final class Overlay<T> {
    private final EditorActor.Preflight<T> base;
    private final Candidate<T> candidate;
    private final ValueType<?> targetType;
    private final GraphicInputBasis.Audition basis;
    private final long copiedBytes;

    private Overlay(
        EditorActor.Preflight<T> base,
        Candidate<T> candidate,
        ValueType<?> targetType,
        GraphicInputBasis.Audition basis,
        long copiedBytes) {
      this.base = base;
      this.candidate = candidate;
      this.targetType = targetType;
      this.basis = basis;
      this.copiedBytes = copiedBytes;
    }

    public EditorActor.Preflight<T> base() {
      return base;
    }

    public Candidate<T> candidate() {
      return candidate;
    }

    public ValueType<?> targetType() {
      return targetType;
    }

    public GraphicInputBasis.Audition basis() {
      return basis;
    }

    public long copiedBytes() {
      return copiedBytes;
    }
  }
}
