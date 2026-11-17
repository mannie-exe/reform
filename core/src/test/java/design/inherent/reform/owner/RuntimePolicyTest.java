package design.inherent.reform.owner;

import static org.junit.jupiter.api.Assertions.*;

import com.mojang.serialization.Codec;
import design.inherent.reform.definition.DocumentDefinition;
import design.inherent.reform.definition.DocumentId;
import design.inherent.reform.definition.FieldPath;
import design.inherent.reform.edit.Candidate;
import design.inherent.reform.edit.DraftLimits;
import design.inherent.reform.edit.DraftTree;
import design.inherent.reform.serialization.JsonLimits;
import design.inherent.reform.validation.Issue;
import design.inherent.reform.validation.ValidationReport;
import design.inherent.reform.value.SnapshotContext;
import design.inherent.reform.value.SnapshotPolicy;
import design.inherent.reform.value.ValueShape;
import design.inherent.reform.value.ValueType;
import design.inherent.reform.value.ValueTypes;
import java.io.ByteArrayOutputStream;
import java.io.NotSerializableException;
import java.io.ObjectOutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class RuntimePolicyTest {
  private static final JsonLimits JSON = new JsonLimits(8192, 16, 512, 512, 64, 1024);
  private static final DocumentDefinition<Integer> DEFINITION =
      new DocumentDefinition<>(
          DocumentId.parse("fixture:policy"), 1, ValueTypes.integer(0, 100), () -> 1);

  static <T> Candidate<T> candidate(DocumentDefinition<T> definition, T value) {
    return DraftTree.from(definition.type(), value, DraftLimits.CONFIGURATION)
        .inspect(definition, JSON)
        .candidate()
        .orElseThrow();
  }

  static <T> RuntimePolicy<T> policy(DocumentDefinition<T> definition) {
    long owner = Thread.currentThread().threadId();
    return new RuntimePolicy<>(
        definition,
        () -> {
          if (Thread.currentThread().threadId() != owner)
            throw new IllegalStateException("Wrong owner thread");
        });
  }

  static <T> SettingsHandle.View<T> publish(RuntimePolicy<T> policy, Candidate<T> value) {
    return policy.prepare(policy.handle().view().revision(), value, () -> true).publish(() -> true);
  }

  static String code(PolicyRefusal failure) {
    return failure.report().issues().getFirst().code();
  }

  @Test
  void aBoundNativeAuthorizationGateRetainsItsStructuredRefusal() {
    var policy = policy(DEFINITION);
    var denied = new PolicyRefusal("owner.permission", "Actual actor no longer has write access");
    assertSame(
        denied,
        assertThrows(
            PolicyRefusal.class,
            () ->
                policy.read(
                    () -> {
                      throw denied;
                    })));
    assertTrue(policy.handle().view().value().isEmpty());
  }

  @Test
  void noLoadedValueIsNotPresentedAsFactoryDefaults() {
    var policy = policy(DEFINITION);
    var initial = policy.handle().view();
    assertEquals(SettingsHandle.Availability.UNINITIALIZED, initial.availability());
    assertTrue(initial.value().isEmpty());
    assertEquals(
        "owner.permission",
        code(assertThrows(PolicyRefusal.class, () -> policy.read(() -> false))));
    var loaded = publish(policy, candidate(DEFINITION, 2));
    assertEquals(2, loaded.value().orElseThrow());
    assertEquals(SettingsHandle.Availability.AVAILABLE, loaded.availability());
    assertEquals(1, loaded.revision().sequence());
    assertEquals(initial.context(), loaded.context());
    assertTrue(initial.value().isEmpty());
  }

  @Test
  void publicationRechecksPermissionAndConsumesEveryAttempt() {
    var policy = policy(DEFINITION);
    publish(policy, candidate(DEFINITION, 1));
    var allowed = new AtomicBoolean(true);
    var request =
        policy.prepare(policy.handle().view().revision(), candidate(DEFINITION, 2), allowed::get);
    var before = policy.handle().view();
    allowed.set(false);
    assertEquals(
        "owner.permission",
        code(assertThrows(PolicyRefusal.class, () -> request.publish(allowed::get))));
    assertSame(before, policy.handle().view());
    allowed.set(true);
    assertEquals(
        "owner.consumed",
        code(assertThrows(PolicyRefusal.class, () -> request.publish(allowed::get))));
    assertEquals(2, publish(policy, candidate(DEFINITION, 2)).value().orElseThrow());
  }

  @Test
  void authorizationFailureIsUnavailableAndDoesNotPublish() {
    var policy = policy(DEFINITION);
    var refusal =
        assertThrows(
            PolicyRefusal.class,
            () ->
                policy.read(
                    () -> {
                      throw new IllegalStateException("actor detached");
                    }));
    assertEquals("owner.authorization_unavailable", code(refusal));
    assertFalse(refusal.report().complete());
    assertTrue(policy.handle().view().value().isEmpty());
  }

  @Test
  void authorizationIsFreshAtTheFinalPublicationBoundary() {
    var policy = policy(DEFINITION);
    publish(policy, candidate(DEFINITION, 1));
    var prepared =
        policy.prepare(policy.handle().view().revision(), candidate(DEFINITION, 2), () -> true);
    var before = policy.handle().view();
    var calls = new AtomicInteger();
    assertEquals(
        "owner.permission",
        code(
            assertThrows(
                PolicyRefusal.class, () -> prepared.publish(() -> calls.incrementAndGet() == 1))));
    assertEquals(2, calls.get());
    assertSame(before, policy.handle().view());
  }

  @Test
  void oldAndForeignEffectiveRevisionsCannotPublish() {
    var first = policy(DEFINITION);
    var second = policy(DEFINITION);
    var a = first.handle().view();
    var b = second.handle().view();
    assertNotEquals(a.context(), b.context());
    assertEquals(a.revision().sequence(), b.revision().sequence());
    assertEquals(
        "owner.revision",
        code(
            assertThrows(
                PolicyRefusal.class,
                () -> first.prepare(b.revision(), candidate(DEFINITION, 2), () -> true))));
    var old = first.prepare(a.revision(), candidate(DEFINITION, 2), () -> true);
    publish(first, candidate(DEFINITION, 3));
    assertEquals(
        "owner.revision", code(assertThrows(PolicyRefusal.class, () -> old.publish(() -> true))));
    assertEquals(3, first.handle().view().value().orElseThrow());
  }

  @Test
  void matchingLogicalIdsDoNotReplaceTheActualDefinition() {
    var policy = policy(DEFINITION);
    var other = new DocumentDefinition<>(DEFINITION.id(), 1, ValueTypes.integer(0, 100), () -> 1);
    assertEquals(
        "owner.definition",
        code(
            assertThrows(
                PolicyRefusal.class,
                () ->
                    policy.prepare(
                        policy.handle().view().revision(), candidate(other, 2), () -> true))));
  }

  @Test
  void semanticNoOpRetainsThePublishedValueAndRevision() {
    var insensitive =
        ValueType.of(
            Codec.STRING,
            new ValueShape.Custom("fixture:case"),
            new SnapshotPolicy<String>() {
              @Override
              public String freeze(String value, SnapshotContext context) {
                return value;
              }

              @Override
              public boolean equivalent(String a, String b) {
                return a.equalsIgnoreCase(b);
              }
            });
    var definition =
        new DocumentDefinition<>(DocumentId.parse("fixture:text"), 1, insensitive, () -> "old");
    var policy = policy(definition);
    var before = publish(policy, candidate(definition, "old"));
    var after = publish(policy, candidate(definition, "OLD"));
    assertSame(before.revision(), after.revision());
    assertEquals("old", after.value().orElseThrow());
  }

  @Test
  void failedReloadRetainsOnlyQualifiedValuesInTheirContext() {
    var policy = policy(DEFINITION);
    var report =
        new ValidationReport(
            List.of(Issue.error("load.invalid", FieldPath.ROOT, "Invalid saved source")), true);
    policy.observationFailed(report);
    assertEquals(SettingsHandle.Availability.UNAVAILABLE, policy.handle().view().availability());
    assertTrue(policy.handle().view().value().isEmpty());
    var qualified = publish(policy, candidate(DEFINITION, 2));
    policy.observationFailed(report);
    assertEquals(2, policy.handle().view().value().orElseThrow());
    assertSame(qualified.revision(), policy.handle().view().revision());
    assertSame(report, policy.handle().view().observationReport());
    assertThrows(
        IllegalArgumentException.class, () -> policy.observationFailed(ValidationReport.VALID));
    var prepared = policy.prepare(qualified.revision(), candidate(DEFINITION, 3), () -> true);
    policy.close();
    assertTrue(policy.handle().view().value().isEmpty());
    assertEquals(SettingsHandle.Availability.CLOSED, policy.handle().view().availability());
    assertEquals(
        "owner.closed",
        code(assertThrows(PolicyRefusal.class, () -> prepared.publish(() -> true))));
    assertEquals(2, qualified.value().orElseThrow());
  }

  @Test
  void snapshotsAndCandidatesDoNotExposeMutableRuntimeData() {
    var type =
        ValueType.of(
            Codec.STRING.listOf().xmap(ArrayList::new, List::copyOf),
            new ValueShape.Custom("fixture:mutable"),
            (ArrayList<String> value, SnapshotContext context) -> new ArrayList<>(value));
    var definition =
        new DocumentDefinition<>(
            DocumentId.parse("fixture:mutable"),
            1,
            type,
            () -> new ArrayList<>(List.of("initial")));
    var policy = policy(definition);
    var input = new ArrayList<>(List.of("saved"));
    var candidate = candidate(definition, input);
    input.clear();
    candidate.value().clear();
    var view = publish(policy, candidate);
    view.value().orElseThrow().clear();
    assertEquals(List.of("saved"), policy.handle().view().value().orElseThrow());
  }

  @Test
  void requiredValidationIsRepeatedBeforePublication() {
    var valid = new AtomicBoolean(true);
    var definition =
        new DocumentDefinition<>(
            DocumentId.parse("fixture:validation"),
            1,
            ValueTypes.integer(0, 100),
            () -> 1,
            value ->
                valid.get()
                    ? ValidationReport.VALID
                    : new ValidationReport(
                        List.of(Issue.error("fixture.invalid", FieldPath.ROOT, "Invalid")), true));
    var policy = policy(definition);
    publish(policy, candidate(definition, 1));
    var request =
        policy.prepare(policy.handle().view().revision(), candidate(definition, 2), () -> true);
    var before = policy.handle().view();
    valid.set(false);
    assertEquals(
        "fixture.invalid",
        code(assertThrows(PolicyRefusal.class, () -> request.publish(() -> true))));
    assertSame(before, policy.handle().view());
  }

  @Test
  void reentrantValidatorCannotOverwriteACompetingPublication() {
    var reenter = new AtomicBoolean();
    var reference = new AtomicReference<RuntimePolicy<Integer>>();
    var third = new AtomicReference<Candidate<Integer>>();
    var definition =
        new DocumentDefinition<>(
            DocumentId.parse("fixture:reentrant"),
            1,
            ValueTypes.integer(0, 100),
            () -> 1,
            value -> {
              if (value == 2 && reenter.getAndSet(false)) publish(reference.get(), third.get());
              return ValidationReport.VALID;
            });
    var policy = policy(definition);
    reference.set(policy);
    third.set(candidate(definition, 3));
    publish(policy, candidate(definition, 1));
    var request =
        policy.prepare(policy.handle().view().revision(), candidate(definition, 2), () -> true);
    reenter.set(true);
    assertEquals(
        "owner.revision",
        code(assertThrows(PolicyRefusal.class, () -> request.publish(() -> true))));
    assertEquals(3, policy.handle().view().value().orElseThrow());
  }

  @Test
  void actorCheckCannotCloseTheContextAndStillReturnAUsableRead() {
    var policy = policy(DEFINITION);
    publish(policy, candidate(DEFINITION, 1));
    assertEquals(
        "owner.closed",
        code(
            assertThrows(
                PolicyRefusal.class,
                () ->
                    policy.read(
                        () -> {
                          policy.close();
                          return true;
                        }))));
    assertTrue(policy.handle().view().value().isEmpty());
  }

  @Test
  void callbackFailureIsUnavailableWithoutChangingThePublication() {
    var failEquality = new AtomicBoolean();
    var type =
        ValueType.of(
            ValueTypes.integer(0, 100).codec(),
            new ValueShape.Custom("fixture:throwing"),
            new SnapshotPolicy<Integer>() {
              @Override
              public Integer freeze(Integer value, SnapshotContext context) {
                return value;
              }

              @Override
              public boolean equivalent(Integer a, Integer b) {
                if (failEquality.get()) throw new IllegalStateException("Unavailable equality");
                return a.equals(b);
              }
            });
    var definition =
        new DocumentDefinition<>(DocumentId.parse("fixture:throwing"), 1, type, () -> 1);
    var policy = policy(definition);
    publish(policy, candidate(definition, 1));
    var request =
        policy.prepare(policy.handle().view().revision(), candidate(definition, 2), () -> true);
    var before = policy.handle().view();
    failEquality.set(true);
    var refusal = assertThrows(PolicyRefusal.class, () -> request.publish(() -> true));
    assertEquals("owner.publication_unavailable", code(refusal));
    assertFalse(refusal.report().complete());
    assertSame(before, policy.handle().view());
  }

  @Test
  void validationCanCloseAContextWithoutAPartialPublication() {
    var close = new AtomicBoolean();
    var reference = new AtomicReference<RuntimePolicy<Integer>>();
    var definition =
        new DocumentDefinition<>(
            DocumentId.parse("fixture:closing"),
            1,
            ValueTypes.integer(0, 100),
            () -> 1,
            value -> {
              if (close.getAndSet(false)) reference.get().close();
              return ValidationReport.VALID;
            });
    var policy = policy(definition);
    reference.set(policy);
    publish(policy, candidate(definition, 1));
    var request =
        policy.prepare(policy.handle().view().revision(), candidate(definition, 2), () -> true);
    close.set(true);
    assertEquals(
        "owner.closed", code(assertThrows(PolicyRefusal.class, () -> request.publish(() -> true))));
    assertEquals(SettingsHandle.Availability.CLOSED, policy.handle().view().availability());
    assertTrue(policy.handle().view().value().isEmpty());
  }

  @Test
  void nativeOwnerMethodsStayConfinedWhileImmutableViewsCanCrossThreads() throws Exception {
    var policy = policy(DEFINITION);
    publish(policy, candidate(DEFINITION, 2));
    var handle = policy.handle();
    assertEquals(2, CompletableFuture.supplyAsync(() -> handle.view().value().orElseThrow()).get());
    assertTrue(
        CompletableFuture.supplyAsync(
                () -> {
                  try {
                    policy.read(() -> true);
                    return false;
                  } catch (IllegalStateException expected) {
                    return true;
                  }
                })
            .get());
  }

  @Test
  void structuredLocalRefusalDoesNotBecomeAnObjectStreamProtocol() throws Exception {
    var failure = assertThrows(PolicyRefusal.class, () -> policy(DEFINITION).read(() -> false));
    try (var stream = new ObjectOutputStream(new ByteArrayOutputStream())) {
      assertThrows(NotSerializableException.class, () -> stream.writeObject(failure));
    }
  }
}
