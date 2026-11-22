package design.inherent.reform.minecraft.client;

import com.mojang.blaze3d.platform.InputConstants;
import design.inherent.reform.definition.DocumentDefinition;
import design.inherent.reform.definition.DocumentId;
import design.inherent.reform.definition.FieldKey;
import design.inherent.reform.definition.FieldPath;
import design.inherent.reform.definition.RecordType;
import design.inherent.reform.edit.Candidate;
import design.inherent.reform.edit.DraftLimits;
import design.inherent.reform.edit.DraftTree;
import design.inherent.reform.owner.ApplicationEffects;
import design.inherent.reform.owner.CommitReceipt;
import design.inherent.reform.owner.DocumentRead;
import design.inherent.reform.owner.DocumentSession;
import design.inherent.reform.owner.OwnerContext;
import design.inherent.reform.owner.OwnerObservation;
import design.inherent.reform.owner.PolicyRefusal;
import design.inherent.reform.owner.PreparedEdit;
import design.inherent.reform.owner.SerialLane;
import design.inherent.reform.owner.SourceInfo;
import design.inherent.reform.serialization.JsonLimits;
import design.inherent.reform.storage.NativeFile;
import design.inherent.reform.validation.Issue;
import design.inherent.reform.validation.ValidationReport;
import design.inherent.reform.value.ValueTypes;
import java.io.IOException;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Options;

/** Explicit physical-client owner for registered primary mappings in the actual native Options. */
public final class KeyBindingOptions implements AutoCloseable {
  /** Native modifier identities; support is declared by the selected loader, never emulated. */
  public enum Modifier {
    CONTROL,
    CONTROL_OR_COMMAND,
    SHIFT,
    ALT,
    NONE;

    public boolean matches(InputConstants.Key key) {
      return NativeKeyBindings.matches(this, key);
    }
  }

  public static Set<Modifier> supportedModifiers() {
    return NativeKeyBindings.modifiers();
  }

  public record Binding(String key, Modifier modifier) {
    public Binding {
      Objects.requireNonNull(key);
      Objects.requireNonNull(modifier);
      if (key.length() > 128) throw new IllegalArgumentException("Native key name is too long");
    }
  }

  public record Limits(
      int maximumBytes,
      int maximumMappings,
      int maximumSessions,
      int maximumOperations,
      int maximumConflictMappings) {
    public static final Limits STANDARD = new Limits(262144, 64, 64, 16, 4096);

    public Limits(
        int maximumBytes, int maximumMappings, int maximumSessions, int maximumOperations) {
      this(maximumBytes, maximumMappings, maximumSessions, maximumOperations, 4096);
    }

    public Limits {
      if (maximumBytes < 1
          || maximumBytes > 16_777_216
          || maximumMappings < 1
          || maximumMappings > 1024
          || maximumSessions < 1
          || maximumOperations < 1
          || maximumConflictMappings < 1
          || maximumConflictMappings > 16384)
        throw new IllegalArgumentException("Invalid native key limits");
    }
  }

  /** A warning snapshot, not permission to save or a promise that no mod has custom dispatch. */
  public record Conflicts(
      String mapping,
      Binding proposed,
      List<String> mappings,
      int inspected,
      boolean nativeCurrent) {
    public Conflicts {
      Objects.requireNonNull(mapping);
      Objects.requireNonNull(proposed);
      mappings = List.copyOf(mappings);
      if (inspected < mappings.size()) throw new IllegalArgumentException("Invalid conflict count");
    }
  }

  private record ConflictInput(KeyMapping mapping, Binding binding, Object context) {}

  private record Capture(Map<String, Binding> values, Map<String, Object> contexts) {}

  private record Source(Optional<NativeFile> file) {
    String revision() {
      return file.map(NativeFile::revision).orElse("missing");
    }

    boolean same(Source other) {
      return file.isPresent() == other.file.isPresent()
          && (file.isEmpty() || file.orElseThrow().same(other.file.orElseThrow()));
    }
  }

  private record Read(
      String revision,
      Optional<DraftTree> draft,
      Optional<Candidate<Map<String, Binding>>> candidate,
      ValidationReport report)
      implements DocumentRead<Map<String, Binding>> {}

  public static final class Observation implements OwnerObservation<Map<String, Binding>> {
    private final KeyBindingOptions owner;
    private final UUID session;
    private final Capture nativeInput;
    private final Source source;
    private final Read read;

    private Observation(
        KeyBindingOptions owner, UUID session, Capture nativeInput, Source source, Read read) {
      this.owner = owner;
      this.session = session;
      this.nativeInput = nativeInput;
      this.source = source;
      this.read = read;
    }

    public UUID context() {
      return owner.context.id();
    }

    public UUID session() {
      return session;
    }

    public DocumentRead<Map<String, Binding>> document() {
      return read;
    }

    public SourceInfo provenance() {
      return new SourceInfo(
          "native-options",
          "Registered native Controls bindings",
          Optional.of(owner.path.toString()));
    }

    @Override
    public ApplicationEffects applicationEffects() {
      return INPUT_EFFECTS;
    }

    public Map<String, Binding> currentBindings() {
      return nativeInput.values();
    }
  }

  private static final ApplicationEffects INPUT_EFFECTS =
      ApplicationEffects.root(
          ApplicationEffects.Timing.OWNER_PUBLICATION,
          "Refreshes admitted native Controls after successful save; custom mod dispatch remains mod-owned.");

  private record Proposal(Observation baseline, Candidate<Map<String, Binding>> candidate) {}

  public static final class Prepared implements PreparedEdit<Map<String, Binding>> {
    private final KeyBindingOptions owner;
    private final UUID session;
    private final AtomicReference<Proposal> proposal;

    private Prepared(KeyBindingOptions owner, UUID session, Proposal proposal) {
      this.owner = owner;
      this.session = session;
      this.proposal = new AtomicReference<>(proposal);
    }

    public DocumentId document() {
      return owner.definition.id();
    }

    public UUID context() {
      return owner.context.id();
    }

    public UUID session() {
      return session;
    }

    private Proposal take(KeyBindingOptions expected, UUID actor) {
      if (owner != expected || !session.equals(actor))
        throw refusal("owner.session", "Native key proposal belongs to another session");
      var value = proposal.getAndSet(null);
      if (value == null)
        throw refusal("owner.consumed", "Native key proposal was already submitted");
      return value;
    }
  }

  public record Receipt(
      Persistence persistence,
      Application application,
      Optional<String> savedRevision,
      Optional<Observation> observation,
      ValidationReport persistenceReport,
      ValidationReport applicationReport,
      boolean mutationStarted,
      boolean mappingRefreshed,
      boolean nativeSaveReturned)
      implements CommitReceipt<Map<String, Binding>> {}

  private record Attempt(
      Proposal proposal, boolean started, boolean refreshed, boolean returned, boolean failed) {}

  public final class Session implements DocumentSession<Map<String, Binding>> {
    private final UUID id = UUID.randomUUID();
    private final OwnerContext.Access access;
    private volatile boolean open = true;

    private Session(OwnerContext.Access access) {
      this.access = access;
    }

    public DocumentId document() {
      return definition.id();
    }

    public DocumentDefinition<Map<String, Binding>> definition() {
      return definition;
    }

    public UUID context() {
      return context.id();
    }

    public UUID id() {
      return id;
    }

    public boolean isOpen() {
      return open && !closed && access.isOpen();
    }

    public Set<Operation> operations() {
      return Set.of(Operation.READ, Operation.COMMIT);
    }

    private void current() {
      access.requireCurrent();
      if (!isOpen()) throw refusal("owner.closed", "Native key session is closed");
    }

    private void require(OwnerContext.Right right) {
      current();
      access.require(right);
      current();
    }

    public CompletionStage<Observation> read() {
      return lane.submit(() -> observe(this));
    }

    /** Actual registered defaults, without resetting the native mapping or creating a file. */
    public Binding defaultBinding(String name) {
      context.requireOwner();
      require(OwnerContext.Right.READ);
      capture();
      var value = defaults.get(name);
      if (value == null) throw refusal("keys.mapping", "Select an admitted native mapping");
      require(OwnerContext.Right.READ);
      return value;
    }

    public List<String> mappingNames() {
      context.requireOwner();
      require(OwnerContext.Right.READ);
      capture();
      return List.copyOf(defaults.keySet());
    }

    /**
     * Explicit bounded review of this proposed binding and the rest of the same draft. It changes
     * no mapping. Unchanged bindings use actual native conflict methods; changed drafts project the
     * pinned default context/modifier rules, without creating globally registered preview mappings.
     */
    public CompletionStage<Conflicts> conflicts(String name, Binding proposed, DraftTree draft) {
      Objects.requireNonNull(name);
      Objects.requireNonNull(proposed);
      Objects.requireNonNull(draft);
      return lane.submit(
          () ->
              context
                  .onOwner(
                      () -> {
                        require(OwnerContext.Right.READ);
                        capture();
                        if (!defaults.containsKey(name))
                          throw refusal("keys.mapping", "Select an admitted native mapping");
                        nativeKey(proposed);
                        return null;
                      })
                  .thenCompose(
                      ignored ->
                          context.onWorker(
                              () -> {
                                var next =
                                    draft.change(
                                        FieldPath.ROOT.field(name),
                                        node -> {
                                          if (!(node
                                              instanceof
                                              design.inherent.reform.edit.DraftNode.ObjectValue
                                                  record))
                                            throw refusal(
                                                "keys.input",
                                                "Repair this binding's record fields first");
                                          var fields = new LinkedHashMap<>(record.fields());
                                          fields.put(
                                              "key",
                                              new design.inherent.reform.edit.DraftNode.Scalar(
                                                  proposed.key()));
                                          fields.put(
                                              "modifier",
                                              new design.inherent.reform.edit.DraftNode.Scalar(
                                                  proposed.modifier().name()));
                                          return new design.inherent.reform.edit.DraftNode
                                              .ObjectValue(fields);
                                        });
                                var inspected = next.inspect(definition, json);
                                if (inspected.candidate().isEmpty())
                                  throw new PolicyRefusal(inspected.report());
                                return inspected.candidate().orElseThrow().value();
                              }))
                  .thenCompose(
                      values ->
                          context.onOwner(
                              () -> {
                                require(OwnerContext.Right.READ);
                                var before = capture();
                                validate(values);
                                if (options.keyMappings.length > limits.maximumConflictMappings())
                                  throw refusal(
                                      "keys.conflict_budget",
                                      "Native conflict review exceeds its mapping budget; no complete result is available");
                                var inputs = new ArrayList<ConflictInput>();
                                KeyMapping selected = null;
                                for (var mapping : options.keyMappings) {
                                  var value =
                                      new Binding(
                                          NativeKeyBindings.key(mapping).getName(),
                                          NativeKeyBindings.modifier(mapping));
                                  nativeKey(value);
                                  inputs.add(
                                      new ConflictInput(
                                          mapping, value, NativeKeyBindings.context(mapping)));
                                  if (mapping.getName().equals(name)) selected = mapping;
                                }
                                if (selected == null)
                                  throw refusal(
                                      "keys.mapping", "Native mapping is no longer available");
                                var selectedMapping = selected;
                                var key = nativeKey(proposed);
                                var ownContext = NativeKeyBindings.context(selected);
                                boolean current = before.values().equals(values);
                                var result = new ArrayList<String>();
                                if (!key.equals(InputConstants.UNKNOWN)) {
                                  for (var input : inputs) {
                                    var other = input.mapping();
                                    if (other == selectedMapping) continue;
                                    var otherValue =
                                        values.getOrDefault(other.getName(), input.binding());
                                    boolean collision =
                                        current
                                            ? NativeKeyBindings.currentConflict(
                                                selectedMapping, other)
                                            : NativeKeyBindings.projectedConflict(
                                                key,
                                                proposed.modifier(),
                                                ownContext,
                                                nativeKey(otherValue),
                                                otherValue.modifier(),
                                                input.context());
                                    if (collision) {
                                      if (other.getName().length() > 512)
                                        throw refusal(
                                            "keys.conflict_name",
                                            "A native conflict name exceeds the review budget");
                                      result.add(other.getName());
                                    }
                                  }
                                }
                                // Conflict-context implementations are consumer callbacks, not
                                // trusted pure functions.
                                verify(before);
                                if (options.keyMappings.length != inputs.size())
                                  throw refusal(
                                      "keys.native_changed",
                                      "Native Controls changed during conflict review");
                                for (int index = 0; index < inputs.size(); index++) {
                                  var input = inputs.get(index);
                                  var actual = options.keyMappings[index];
                                  if (actual != input.mapping()
                                      || NativeKeyBindings.context(actual) != input.context()
                                      || !new Binding(
                                              NativeKeyBindings.key(actual).getName(),
                                              NativeKeyBindings.modifier(actual))
                                          .equals(input.binding()))
                                    throw refusal(
                                        "keys.native_changed",
                                        "Native Controls changed during conflict review");
                                }
                                require(OwnerContext.Right.READ);
                                return new Conflicts(
                                    name, proposed, result, inputs.size(), current);
                              })));
    }

    public CompletionStage<Prepared> prepare(
        OwnerObservation<Map<String, Binding>> value, DraftTree draft) {
      final Observation baseline;
      try {
        current();
        if (!(value instanceof Observation actual)
            || actual.owner != KeyBindingOptions.this
            || !id.equals(actual.session))
          throw refusal("owner.session", "Native key observation belongs to another session");
        baseline = (Observation) value;
      } catch (RuntimeException failed) {
        return CompletableFuture.failedStage(failed);
      }
      return lane.submit(
          () ->
              context
                  .onOwner(
                      () -> {
                        require(OwnerContext.Right.READ);
                        if (!baseline.read.report().valid())
                          throw refusal(
                              "keys.baseline",
                              "Observe matching saved and native bindings before preparing");
                        return null;
                      })
                  .thenCompose(
                      ignored ->
                          context.onWorker(
                              () -> {
                                current();
                                var inspected =
                                    Objects.requireNonNull(draft).inspect(definition, json);
                                if (inspected.candidate().isEmpty())
                                  throw new PolicyRefusal(inspected.report());
                                return inspected.candidate().orElseThrow();
                              }))
                  .thenCompose(
                      candidate ->
                          context.onOwner(
                              () -> {
                                require(OwnerContext.Right.READ);
                                validate(candidate.value());
                                return new Prepared(
                                    KeyBindingOptions.this, id, new Proposal(baseline, candidate));
                              })));
    }

    public CompletionStage<Receipt> commit(PreparedEdit<Map<String, Binding>> value) {
      final Proposal proposal;
      try {
        if (!(value instanceof Prepared issued))
          throw refusal("owner.session", "Use the actual issued native key proposal");
        proposal = issued.take(KeyBindingOptions.this, id);
      } catch (RuntimeException refused) {
        return CompletableFuture.failedStage(refused);
      }
      return lane.submit(
          () ->
              context
                  .onOwner(
                      () -> {
                        require(OwnerContext.Right.WRITE);
                        verify(proposal.baseline.nativeInput);
                        validate(proposal.candidate.value());
                        return proposal;
                      })
                  .thenCompose(
                      admitted ->
                          context.onWorker(
                              () -> {
                                current();
                                if (!readSource().same(admitted.baseline.source))
                                  throw refusal(
                                      "keys.source_changed",
                                      "Native options changed; reload and review");
                                return admitted;
                              }))
                  .thenCompose(admitted -> context.onOwner(() -> attempt(this, admitted)))
                  .<CompletionStage<Receipt>>handle(
                      (attempt, failed) ->
                          failed == null
                              ? finish(this, attempt)
                              : CompletableFuture.completedStage(unattempted(failed)))
                  .thenCompose(stage -> stage));
    }

    public void close() {
      open = false;
      sessions.remove(this);
    }
  }

  private final OwnerContext context;
  private final Options options;
  private final List<KeyMapping> mappings;
  private final Map<String, Binding> defaults;
  private final DocumentDefinition<Map<String, Binding>> definition;
  private final Path path;
  private final Limits limits;
  private final JsonLimits json;
  private final SerialLane lane;
  private final Set<Session> sessions = ConcurrentHashMap.newKeySet();
  private final OwnerContext.CloseHook cleanup;
  private volatile boolean closed;

  public KeyBindingOptions(
      OwnerContext context,
      Options options,
      DocumentId id,
      Collection<? extends KeyMapping> mappings) {
    this(context, options, id, mappings, Limits.STANDARD);
  }

  public KeyBindingOptions(
      OwnerContext context,
      Options options,
      DocumentId id,
      Collection<? extends KeyMapping> mappings,
      Limits limits) {
    this.context = Objects.requireNonNull(context);
    context.requireOwner();
    this.options = Objects.requireNonNull(options);
    this.limits = Objects.requireNonNull(limits);
    this.mappings = List.copyOf(mappings);
    if (this.mappings.isEmpty() || this.mappings.size() > limits.maximumMappings())
      throw new IllegalArgumentException(
          "Admit a bounded nonempty set of existing native mappings");
    path = NativeKeyBindings.file(options).toAbsolutePath().normalize();
    var names = new java.util.HashSet<String>();
    var baseline = new LinkedHashMap<String, Binding>();
    var fields = new ArrayList<FieldKey<Binding>>();
    var key = FieldKey.of("key", ValueTypes.text(128));
    var modifier = FieldKey.of("modifier", ValueTypes.enumeration(Modifier.class));
    var binding =
        RecordType.<Binding>builder(values -> new Binding(values.get(key), values.get(modifier)))
            .field(key, Binding::key)
            .field(modifier, Binding::modifier)
            .build();
    for (var mapping : this.mappings) {
      var name = mapping.getName();
      if (name.isEmpty()
          || name.length() > 512
          || name.indexOf(':') >= 0
          || name.chars().anyMatch(Character::isISOControl)
          || !names.add(name))
        throw new IllegalArgumentException(
            "Use unique native mapping names that retain native options syntax");
      baseline.put(
          name,
          new Binding(
              mapping.getDefaultKey().getName(), NativeKeyBindings.defaultModifier(mapping)));
      fields.add(FieldKey.of(name, binding));
    }
    defaults = Collections.unmodifiableMap(baseline);
    var record =
        RecordType.<Map<String, Binding>>builder(
            values -> {
              var result = new LinkedHashMap<String, Binding>();
              for (var field : fields) result.put(field.id(), values.get(field));
              return Collections.unmodifiableMap(result);
            });
    for (var field : fields) record.field(field, values -> values.get(field.id()));
    definition =
        new DocumentDefinition<>(Objects.requireNonNull(id), 1, record.build(), () -> defaults);
    json = new JsonLimits(limits.maximumBytes(), 16, limits.maximumMappings() * 32, 1024, 32, 32);
    lane = new SerialLane(limits.maximumOperations());
    capture();
    validate(defaults);
    cleanup = context.onClose(this::close);
  }

  public DocumentDefinition<Map<String, Binding>> definition() {
    return definition;
  }

  public JsonLimits jsonLimits() {
    return json;
  }

  public Session session(OwnerContext.Access access) {
    context.requireOwner();
    Objects.requireNonNull(access);
    if (closed || !access.isOpen() || !access.context().equals(context.id()))
      throw refusal("owner.context", "Use actual access for this native key context");
    if (sessions.size() >= limits.maximumSessions())
      throw refusal("owner.busy", "Native key session budget is full");
    var result = new Session(access);
    sessions.add(result);
    return result;
  }

  private Capture capture() {
    context.requireOwner();
    if (closed
        || Minecraft.getInstance().options != options
        || !NativeKeyBindings.file(options).toAbsolutePath().normalize().equals(path))
      throw refusal("keys.context", "The actual native Options owner changed");
    var values = new LinkedHashMap<String, Binding>();
    var contexts = new LinkedHashMap<String, Object>();
    for (var mapping : mappings) {
      int matches = 0;
      for (var actual : options.keyMappings)
        if (actual.getName().equals(mapping.getName())) {
          if (actual != mapping)
            throw refusal(
                "keys.mapping", "An equal-named native mapping replaced the admitted instance");
          matches++;
        }
      if (matches != 1)
        throw refusal("keys.mapping", "The admitted mapping must occur once in native Controls");
      values.put(
          mapping.getName(),
          new Binding(
              NativeKeyBindings.key(mapping).getName(), NativeKeyBindings.modifier(mapping)));
      contexts.put(mapping.getName(), NativeKeyBindings.context(mapping));
    }
    context.requireOwner();
    if (closed) throw refusal("keys.context", "Native key owner closed during observation");
    return new Capture(Collections.unmodifiableMap(values), Collections.unmodifiableMap(contexts));
  }

  private void verify(Capture expected) {
    var now = capture();
    if (!now.values.equals(expected.values)
        || mappings.stream()
            .anyMatch(
                mapping ->
                    now.contexts.get(mapping.getName())
                        != expected.contexts.get(mapping.getName())))
      throw refusal(
          "keys.native_changed", "Native binding or conflict context changed; reload and review");
  }

  private Source readSource() throws IOException {
    try {
      return new Source(Optional.of(NativeFile.read(path, limits.maximumBytes())));
    } catch (NoSuchFileException missing) {
      return new Source(Optional.empty());
    }
  }

  static InputConstants.Key nativeKey(Binding binding) {
    return nativeKey(binding, true);
  }

  private static InputConstants.Key nativeKey(Binding binding, boolean canonical) {
    if (!supportedModifiers().contains(binding.modifier()))
      throw refusal(
          "keys.unsupported_modifier",
          "This loader cannot save that native modifier chord; the binding was retained");
    final InputConstants.Key key;
    try {
      key = NativeKeys.parse(binding.key());
    } catch (IllegalArgumentException invalid) {
      throw refusal("keys.key", "Select a native key within this game's supported input range");
    }
    if (canonical && !key.getName().equals(binding.key()) || binding.modifier().matches(key))
      throw refusal("keys.key", "Use a canonical native key and a distinct native modifier");
    return key;
  }

  private void validate(Map<String, Binding> values) {
    context.requireOwner();
    for (var entry : values.entrySet()) {
      try {
        nativeKey(entry.getValue());
      } catch (PolicyRefusal invalid) {
        var issue = invalid.report().issues().getFirst();
        var field = issue.code().equals("keys.unsupported_modifier") ? "modifier" : "key";
        throw new PolicyRefusal(
            new ValidationReport(
                List.of(
                    Issue.error(
                        issue.code(),
                        FieldPath.ROOT.field(entry.getKey()).field(field),
                        issue.fallback())),
                true));
      }
    }
  }

  private Map<String, Binding> saved(Source source) throws IOException {
    var values = new LinkedHashMap<>(defaults);
    var found = new java.util.HashSet<String>();
    if (source.file.isEmpty()) return defaults;
    for (var line : source.file.orElseThrow().text().lines().toList()) {
      int colon = line.indexOf(':');
      if (colon < 0) continue;
      var prefix = line.substring(0, colon);
      if (!prefix.startsWith("key_")) continue;
      var name = prefix.substring(4);
      if (!defaults.containsKey(name)) continue;
      if (!found.add(name))
        throw refusal("keys.source", "Duplicate admitted key in native options");
      var raw = line.substring(colon + 1).split(":", -1);
      var modifier = NativeKeyBindings.savedModifier(raw);
      var key = nativeKey(new Binding(raw[0], Modifier.NONE), false);
      if (modifier.matches(key)) modifier = Modifier.NONE;
      values.put(name, new Binding(key.getName(), modifier));
    }
    return Collections.unmodifiableMap(values);
  }

  private Observation observation(Session session, Capture capture, Source source) {
    try {
      var value = saved(source);
      var draft = DraftTree.from(definition.type(), value, DraftLimits.CONFIGURATION);
      var inspected = draft.inspect(definition, json);
      var report = inspected.report();
      if (!value.equals(capture.values))
        report =
            report.and(
                issue(
                    "keys.source_mismatch",
                    "Saved and native bindings differ; reload through native Controls",
                    true));
      return new Observation(
          this,
          session.id,
          capture,
          source,
          new Read(source.revision(), Optional.of(draft), inspected.candidate(), report));
    } catch (IOException | RuntimeException failed) {
      return new Observation(
          this,
          session.id,
          capture,
          source,
          new Read(
              source.revision(),
              Optional.empty(),
              Optional.empty(),
              issue("keys.source", "Admitted native key source is unavailable or invalid", false)));
    }
  }

  private CompletionStage<Observation> observe(Session session) {
    return context
        .onOwner(
            () -> {
              session.require(OwnerContext.Right.READ);
              return capture();
            })
        .thenCompose(
            captured ->
                context
                    .onWorker(this::readSource)
                    .thenCompose(
                        source ->
                            context.onOwner(
                                () -> {
                                  session.require(OwnerContext.Right.READ);
                                  verify(captured);
                                  return observation(session, captured, source);
                                })));
  }

  private Attempt attempt(Session session, Proposal proposal) throws IOException {
    session.require(OwnerContext.Right.WRITE);
    verify(proposal.baseline.nativeInput);
    validate(proposal.candidate.value());
    if (!readSource().same(proposal.baseline.source))
      throw refusal(
          "keys.source_changed", "Native options changed before commit; reload and review");
    session.require(OwnerContext.Right.WRITE);
    verify(proposal.baseline.nativeInput);
    if (proposal.candidate.value().equals(proposal.baseline.nativeInput.values))
      return new Attempt(proposal, false, false, false, false);
    boolean started = false, refreshed = false;
    var expected = new LinkedHashMap<>(proposal.baseline.nativeInput.values);
    try {
      for (var mapping : mappings) {
        session.require(OwnerContext.Right.WRITE);
        verify(new Capture(Map.copyOf(expected), proposal.baseline.nativeInput.contexts));
        var next = proposal.candidate.value().get(mapping.getName());
        if (next.equals(proposal.baseline.nativeInput.values.get(mapping.getName()))) continue;
        started = true;
        NativeKeyBindings.set(mapping, next.modifier(), nativeKey(next));
        expected.put(mapping.getName(), next);
        session.require(OwnerContext.Right.WRITE);
        verify(new Capture(Map.copyOf(expected), proposal.baseline.nativeInput.contexts));
      }
      session.require(OwnerContext.Right.WRITE);
      verify(new Capture(Map.copyOf(expected), proposal.baseline.nativeInput.contexts));
      KeyMapping.resetMapping();
      refreshed = true;
      session.require(OwnerContext.Right.WRITE);
      verify(new Capture(Map.copyOf(expected), proposal.baseline.nativeInput.contexts));
      if (!readSource().same(proposal.baseline.source))
        throw refusal(
            "keys.source_changed",
            "Native options changed during key mutation; inspect the actual result");
      options.save();
      return new Attempt(proposal, started, refreshed, true, false);
    } catch (IOException | RuntimeException | Error failed) {
      return new Attempt(proposal, started, refreshed, false, true);
    }
  }

  private CompletionStage<Receipt> finish(Session session, Attempt attempt) {
    return context
        .onWorker(this::readSource)
        .<CompletionStage<Receipt>>handle(
            (source, failed) ->
                context.onOwner(
                    () -> {
                      if (failed != null) return unknown(attempt);
                      final Capture current;
                      try {
                        session.require(OwnerContext.Right.READ);
                        current = capture();
                      } catch (RuntimeException unavailable) {
                        return unknown(attempt);
                      }
                      var observed = observation(session, current, source);
                      boolean intended =
                          observed.read.candidate().isPresent()
                              && observed
                                  .read
                                  .candidate()
                                  .orElseThrow()
                                  .value()
                                  .equals(attempt.proposal.candidate.value());
                      var persistence =
                          !attempt.started
                              ? CommitReceipt.Persistence.UNCHANGED
                              : intended
                                  ? CommitReceipt.Persistence.SAVED
                                  : source.same(attempt.proposal.baseline.source)
                                      ? CommitReceipt.Persistence.FAILED
                                      : CommitReceipt.Persistence.UNKNOWN;
                      var application =
                          !attempt.started
                              ? CommitReceipt.Application.NOT_ATTEMPTED
                              : attempt.refreshed
                                      && current.values.equals(attempt.proposal.candidate.value())
                                  ? CommitReceipt.Application.APPLIED
                                  : CommitReceipt.Application.PARTIAL;
                      return new Receipt(
                          persistence,
                          application,
                          intended ? Optional.of(source.revision()) : Optional.empty(),
                          Optional.of(observed),
                          persistence == CommitReceipt.Persistence.SAVED
                                  || persistence == CommitReceipt.Persistence.UNCHANGED
                              ? ValidationReport.VALID
                              : issue(
                                  "keys.persistence_unconfirmed",
                                  "Native save did not confirm the intended key source",
                                  false),
                          attempt.failed
                              ? issue(
                                  "keys.native_failed",
                                  "Native key commit failed after mutation; inspect its actual result",
                                  false)
                              : ValidationReport.VALID,
                          attempt.started,
                          attempt.refreshed,
                          attempt.returned);
                    }))
        .thenCompose(stage -> stage)
        .exceptionally(failed -> unknown(attempt));
  }

  private static Receipt unknown(Attempt attempt) {
    var report =
        issue(
            "keys.readback_unavailable",
            "Native key readback is unavailable; no rollback is claimed",
            false);
    return new Receipt(
        attempt.started ? CommitReceipt.Persistence.UNKNOWN : CommitReceipt.Persistence.UNCHANGED,
        attempt.started
            ? CommitReceipt.Application.UNAVAILABLE
            : CommitReceipt.Application.NOT_ATTEMPTED,
        Optional.empty(),
        Optional.empty(),
        report,
        report,
        attempt.started,
        attempt.refreshed,
        attempt.returned);
  }

  private static Receipt unattempted(Throwable failed) {
    while (failed instanceof java.util.concurrent.CompletionException && failed.getCause() != null)
      failed = failed.getCause();
    var report =
        failed instanceof PolicyRefusal refused
            ? refused.report()
            : issue("keys.unavailable", "Native key commit was not admitted", false);
    return new Receipt(
        CommitReceipt.Persistence.REFUSED,
        CommitReceipt.Application.NOT_ATTEMPTED,
        Optional.empty(),
        Optional.empty(),
        report,
        report,
        false,
        false,
        false);
  }

  private static ValidationReport issue(String code, String message, boolean complete) {
    return new ValidationReport(List.of(Issue.error(code, FieldPath.ROOT, message)), complete);
  }

  private static PolicyRefusal refusal(String code, String message) {
    return new PolicyRefusal(code, message);
  }

  public void close() {
    if (closed) return;
    context.checkOwnerThread();
    closed = true;
    lane.close();
    for (var session : List.copyOf(sessions)) session.close();
    cleanup.close();
  }
}
