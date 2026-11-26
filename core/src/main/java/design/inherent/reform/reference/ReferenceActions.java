package design.inherent.reform.reference;

import com.mojang.serialization.JsonOps;
import design.inherent.reform.definition.DocumentId;
import design.inherent.reform.definition.FieldKey;
import design.inherent.reform.definition.RecordType;
import design.inherent.reform.operation.OperationDescriptor;
import design.inherent.reform.operation.OperationHandle;
import design.inherent.reform.operation.OperationScope;
import design.inherent.reform.owner.OwnerContext;
import design.inherent.reform.owner.PolicyRefusal;
import design.inherent.reform.value.SnapshotContext;
import design.inherent.reform.value.SnapshotPolicy;
import design.inherent.reform.value.ValueShape;
import design.inherent.reform.value.ValueType;
import design.inherent.reform.value.ValueTypes;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.Function;

/** Actual-owner admission and fresh result delivery over the existing local operation services. */
public final class ReferenceActions<K, V> implements AutoCloseable {
  public record Request<K>(K key, Optional<UUID> expectedSnapshot) {
    public Request {
      Objects.requireNonNull(key);
      Objects.requireNonNull(expectedSnapshot);
    }
  }

  private record Active<K, V>(ReferenceStamp stamp, ReferenceResolver<K, V> resolver) {}

  private record Preparation<K, V>(Active<K, V> pending, ReferenceStamp desired) {}

  private final OwnerContext context;
  private final OwnerContext.Access access;
  private final DocumentId source;
  private final ValueType<K> keys;
  private final SnapshotPolicy<V> values;
  private final ReferenceLimits limits;
  private final OperationScope scope;
  private final OperationDescriptor<Request<K>, ReferenceResolution<V>> resolve;
  private final OperationDescriptor<ReferenceQuery, ReferencePage<K>> search;
  private final OwnerContext.CloseHook closeHook;
  private volatile Active<K, V> active;
  private volatile boolean closed;

  public ReferenceActions(
      OwnerContext context,
      OwnerContext.Access access,
      DocumentId source,
      ValueType<K> keys,
      SnapshotPolicy<V> values,
      ReferenceLimits limits) {
    this(context, access, source, keys, values, limits, OperationScope.Limits.STANDARD);
  }

  public ReferenceActions(
      OwnerContext context,
      OwnerContext.Access access,
      DocumentId source,
      ValueType<K> keys,
      SnapshotPolicy<V> values,
      ReferenceLimits limits,
      OperationScope.Limits operations) {
    this.context = Objects.requireNonNull(context);
    context.requireOwner();
    this.access = Objects.requireNonNull(access);
    this.source = Objects.requireNonNull(source);
    this.keys = Objects.requireNonNull(keys);
    this.values = Objects.requireNonNull(values);
    this.limits = Objects.requireNonNull(limits);
    if (!access.context().equals(context.id()) || !access.isOpen())
      throw new IllegalArgumentException("Reference actions require their actual owner access");
    resolve = descriptor("resolve", requestType(keys));
    search = descriptor("search", queryType(limits));
    active =
        unavailable(
            "unavailable",
            ReferencePage.Availability.UNAVAILABLE_CONTEXT,
            "Reference context is not available");
    scope = new OperationScope(context, access, operations);
    try {
      scope.register(
          resolve,
          (execution, request) -> {
            var selected = current(request.expectedSnapshot());
            return context
                .onWorker(
                    () -> {
                      execution.checkpoint();
                      return seal(selected, selected.resolver().resolve(request.key()));
                    })
                .thenCompose(result -> deliver(selected, execution, result));
          });
      scope.register(
          search,
          (execution, query) -> {
            limits.query(query);
            var selected = current(query.cursor().map(ReferenceQuery.Cursor::snapshot));
            if (query
                .cursor()
                .filter(
                    cursor ->
                        !cursor.filter().equals(query.filter()) || cursor.match() != query.match())
                .isPresent())
              throw new PolicyRefusal(
                  "reference.cursor", "The filter changed; restart this reference query");
            return context
                .onWorker(
                    () -> {
                      execution.checkpoint();
                      if (!selected.resolver().supports(query.match()))
                        return ReferencePage.<K>unavailable(
                            selected.stamp(),
                            ReferencePage.Availability.UNSUPPORTED,
                            query.match() == ReferenceQuery.Match.KEY_PREFIX
                                ? "This source does not provide ID-prefix suggestions."
                                : "This source does not provide text search.");
                      return seal(selected, query, selected.resolver().search(query));
                    })
                .thenCompose(result -> deliver(selected, execution, result));
          });
      scope.freeze();
      closeHook = context.onClose(this::close);
    } catch (RuntimeException | Error failure) {
      scope.close();
      throw failure;
    }
  }

  /** Invalidate first: a failed or reentrant producer must never leave the old index ready. */
  public ReferenceStamp bind(
      String revision, Function<ReferenceStamp, ReferenceResolver<K, V>> factory) {
    Objects.requireNonNull(factory);
    var prepared = prepare(revision);
    return publish(prepared, Objects.requireNonNull(factory.apply(prepared.desired())));
  }

  /** Capture native inputs at entry, compile off-thread and admit only the still-current result. */
  public CompletionStage<ReferenceStamp> bindAsync(
      String revision,
      Function<ReferenceStamp, CompletionStage<? extends ReferenceResolver<K, V>>> factory) {
    Objects.requireNonNull(factory);
    var prepared = prepare(revision);
    final CompletionStage<? extends ReferenceResolver<K, V>> produced;
    try {
      produced = Objects.requireNonNull(factory.apply(prepared.desired()));
    } catch (RuntimeException failure) {
      return CompletableFuture.<ReferenceStamp>failedFuture(failure).minimalCompletionStage();
    }
    return produced.thenCompose(resolver -> context.onOwner(() -> publish(prepared, resolver)));
  }

  /** Capture on the actual owner, compile on its bounded worker lane, then publish freshly. */
  public <I> CompletionStage<ReferenceStamp> bindCaptured(
      String revision,
      java.util.function.BiFunction<OwnerContext, ReferenceStamp, I> capture,
      Function<I, ? extends ReferenceResolver<K, V>> compile) {
    Objects.requireNonNull(capture);
    Objects.requireNonNull(compile);
    return bindAsync(
        revision,
        stamp -> {
          var input = Objects.requireNonNull(capture.apply(context, stamp));
          return context.onWorker(() -> Objects.requireNonNull(compile.apply(input)));
        });
  }

  public DocumentId source() {
    return source;
  }

  public ReferenceLimits limits() {
    return limits;
  }

  /** Cached identity only. Neither authorization nor a lookup, including after source closure. */
  public Optional<ReferenceStamp> observedStamp() {
    var selected = active;
    return selected == null ? Optional.empty() : Optional.of(selected.stamp());
  }

  public boolean isOpen() {
    return !closed && context.isOpen() && access.isOpen();
  }

  /** Fresh native READ admission; consumers must call on this source's actual owner thread. */
  public ReferenceStamp requireStamp() {
    return current(Optional.empty()).stamp();
  }

  private Preparation<K, V> prepare(String revision) {
    context.requireOwner();
    requireOpen();
    var pending =
        unavailable(
            revision,
            ReferencePage.Availability.UNAVAILABLE_CONTEXT,
            "Reference source is unavailable until a complete replacement is admitted");
    active = pending;
    return new Preparation<>(pending, stamp(revision));
  }

  private ReferenceStamp publish(Preparation<K, V> prepared, ReferenceResolver<K, V> resolver) {
    context.requireOwner();
    requireOpen();
    if (active != prepared.pending())
      throw new PolicyRefusal(
          "reference.stale", "Reference context changed during source preparation");
    if (!Objects.requireNonNull(resolver).stamp().equals(prepared.desired()))
      throw new IllegalArgumentException("Resolver must retain its actual assigned source stamp");
    active = new Active<>(prepared.desired(), resolver);
    return prepared.desired();
  }

  public ReferenceStamp invalidate(
      String revision, ReferencePage.Availability availability, String reason) {
    context.requireOwner();
    requireOpen();
    var next = unavailable(revision, availability, reason);
    active = next;
    return next.stamp();
  }

  public OperationHandle<ReferenceResolution<V>> resolve(
      OperationDescriptor.EntryPoint entry, K key) {
    return resolve(entry, new Request<>(key, Optional.empty()));
  }

  public OperationHandle<ReferenceResolution<V>> resolve(
      OperationDescriptor.EntryPoint entry, Request<K> request) {
    return scope.submit(resolve, entry, request);
  }

  public OperationHandle<ReferencePage<K>> search(
      OperationDescriptor.EntryPoint entry, ReferenceQuery query) {
    return scope.submit(search, entry, query);
  }

  private Active<K, V> current(Optional<UUID> expected) {
    access.require(OwnerContext.Right.READ);
    requireOpen();
    var selected = active;
    if (expected.filter(snapshot -> !snapshot.equals(selected.stamp().snapshot())).isPresent())
      throw new PolicyRefusal("reference.stale", "Reference source changed; refresh its choices");
    return selected;
  }

  private <T> CompletionStage<T> deliver(
      Active<K, V> selected, OperationScope.Execution execution, T result) {
    return context.onOwner(
        () -> {
          access.require(OwnerContext.Right.READ);
          requireOpen();
          execution.checkpoint();
          if (active != selected || !selected.stamp().equals(selected.resolver().stamp()))
            throw new PolicyRefusal(
                "reference.stale", "Reference source changed before lookup completed");
          return result;
        });
  }

  private ReferenceResolution<V> seal(Active<K, V> selected, ReferenceResolution<V> result) {
    if (!result.stamp().equals(selected.stamp()))
      throw new IllegalArgumentException("Resolver returned a foreign source stamp");
    limits.text(result.reason(), result.preview());
    var context = new SnapshotContext(SnapshotContext.Limits.CONFIGURATION);
    return new ReferenceResolution<>(
        result.stamp(),
        result.status(),
        result.value().map(value -> context.copy(value, () -> values.freeze(value, context))),
        result.preview(),
        result.reason());
  }

  private ReferencePage<K> seal(
      Active<K, V> selected, ReferenceQuery query, ReferencePage<K> result) {
    if (!result.stamp().equals(selected.stamp())
        || result.choices().size() > query.limit()
        || result.examined() > limits.maximumScan())
      throw new IllegalArgumentException("Resolver page exceeds its actual source/query bounds");
    int start = query.cursor().map(ReferenceQuery.Cursor::offset).orElse(0);
    long end = (long) start + result.examined();
    if (end > limits.maximumEntries())
      throw new IllegalArgumentException("Resolver page exceeds the complete index budget");
    if (result
        .next()
        .filter(
            cursor ->
                !cursor.snapshot().equals(selected.stamp().snapshot())
                    || !cursor.filter().equals(query.filter())
                    || cursor.match() != query.match()
                    || cursor.offset() <= start
                    || cursor.offset() != end)
        .isPresent())
      throw new IllegalArgumentException("Resolver continuation is not bound to its actual query");
    long characters = result.reason().length();
    var context = new SnapshotContext(SnapshotContext.Limits.CONFIGURATION);
    var unique = new HashSet<K>();
    var choices = new ArrayList<ReferencePage.Choice<K>>();
    for (var choice : result.choices()) {
      K key = keys.freeze(choice.key(), context);
      if (query.match() == ReferenceQuery.Match.KEY_PREFIX
          && (!(key instanceof String text) || !text.startsWith(query.filter())))
        throw new IllegalArgumentException("Resolver choice is outside its actual ID prefix");
      var encoded = keys.codec().encodeStart(JsonOps.INSTANCE, key);
      if (encoded.error().isPresent() || encoded.result().isEmpty())
        throw new IllegalArgumentException("Resolver choice cannot encode completely");
      if (!keys.decode(
                  JsonOps.INSTANCE,
                  encoded.result().orElseThrow(),
                  design.inherent.reform.definition.FieldPath.ROOT)
              .report()
              .valid()
          || !unique.add(key))
        throw new IllegalArgumentException("Resolver choices must have complete unique keys");
      characters +=
          (long) choice.label().length() + encoded.result().orElseThrow().toString().length();
      for (var preview : choice.preview().entrySet())
        characters += (long) preview.getKey().length() + preview.getValue().length();
      if (characters > limits.maximumTextCharacters())
        throw new IllegalArgumentException("Resolver presentation budget exceeded");
      choices.add(new ReferencePage.Choice<>(key, choice.label(), choice.preview()));
    }
    if (characters > limits.maximumTextCharacters())
      throw new IllegalArgumentException("Resolver presentation budget exceeded");
    return new ReferencePage<>(
        result.stamp(),
        result.availability(),
        choices,
        result.next(),
        result.examined(),
        result.exhausted(),
        result.reason());
  }

  private Active<K, V> unavailable(
      String revision, ReferencePage.Availability availability, String reason) {
    var stamp = stamp(revision);
    return new Active<>(
        stamp, ReferenceIndex.unavailable(stamp, keys, values, limits, availability, reason));
  }

  private ReferenceStamp stamp(String revision) {
    return new ReferenceStamp(context.id(), source, UUID.randomUUID(), revision);
  }

  private <I, O> OperationDescriptor<I, O> descriptor(String suffix, ValueType<I> type) {
    return new OperationDescriptor<>(
        new DocumentId(source.namespace(), source.path() + "/" + suffix),
        type,
        OwnerContext.Right.READ,
        Set.of(OperationDescriptor.Effect.INSPECT),
        Set.of(OperationDescriptor.EntryPoint.values()),
        Optional.of(source));
  }

  private void requireOpen() {
    if (closed) throw new PolicyRefusal("owner.closed", "Reference actions are closed");
  }

  @Override
  public void close() {
    if (closed) return;
    context.checkOwnerThread();
    closed = true;
    active = null;
    scope.close();
    closeHook.close();
  }

  private static final ValueType<UUID> UUID_TYPE = ValueTypes.uuid();

  private static <K> ValueType<Request<K>> requestType(ValueType<K> keys) {
    var key = FieldKey.of("key", keys);
    var expected =
        FieldKey.of(
            "expected_snapshot",
            ValueTypes.optional(UUID_TYPE, ValueShape.OptionalEncoding.SINGLETON_LIST));
    return RecordType.<Request<K>>builder(v -> new Request<>(v.get(key), v.get(expected)))
        .field(key, Request::key)
        .field(expected, Request::expectedSnapshot)
        .build();
  }

  private static ValueType<ReferenceQuery> queryType(ReferenceLimits limits) {
    var snapshot = FieldKey.of("snapshot", UUID_TYPE);
    var filter = FieldKey.of("filter", ValueTypes.text(limits.maximumQueryLength()));
    var offset = FieldKey.of("offset", ValueTypes.integer(0, limits.maximumEntries()));
    var match = FieldKey.of("match", ValueTypes.enumeration(ReferenceQuery.Match.class));
    var cursorType =
        RecordType.<ReferenceQuery.Cursor>builder(
                v ->
                    new ReferenceQuery.Cursor(
                        v.get(snapshot), v.get(filter), v.get(offset), v.get(match)))
            .field(snapshot, ReferenceQuery.Cursor::snapshot)
            .field(filter, ReferenceQuery.Cursor::filter)
            .field(offset, ReferenceQuery.Cursor::offset)
            .field(match, ReferenceQuery.Cursor::match)
            .build();
    var count = FieldKey.of("limit", ValueTypes.integer(1, limits.maximumPageSize()));
    var cursor =
        FieldKey.of(
            "cursor", ValueTypes.optional(cursorType, ValueShape.OptionalEncoding.SINGLETON_LIST));
    return RecordType.<ReferenceQuery>builder(
            v -> new ReferenceQuery(v.get(filter), v.get(count), v.get(cursor), v.get(match)))
        .field(filter, ReferenceQuery::filter)
        .field(count, ReferenceQuery::limit)
        .field(cursor, ReferenceQuery::cursor)
        .field(match, ReferenceQuery::match)
        .build();
  }
}
