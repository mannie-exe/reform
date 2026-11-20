package design.inherent.reform.neoforge.configuration;

import com.electronwill.nightconfig.core.CommentedConfig;
import com.electronwill.nightconfig.toml.TomlParser;
import design.inherent.reform.definition.DocumentDefinition;
import design.inherent.reform.definition.DocumentId;
import design.inherent.reform.definition.FieldPath;
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
import design.inherent.reform.serialization.JsonInput;
import design.inherent.reform.serialization.JsonLimits;
import design.inherent.reform.validation.Issue;
import design.inherent.reform.validation.ValidationReport;
import design.inherent.reform.value.ValueType;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumSet;
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
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.ModList;
import net.neoforged.fml.config.IConfigSpec;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.config.ModConfigs;
import net.neoforged.neoforge.common.ModConfigSpec;

/**
 * An explicitly admitted registered native owner. This never registers, initializes, corrects or
 * privately serializes a native configuration. Construct and close it on the actual owner executor.
 */
public final class RegisteredConfig implements AutoCloseable {
  /** One exact, explicitly supported native source and its admitted leaf paths. */
  public record Delegation(
      String modId, String version, ModConfig.Type type, String fileName, Set<FieldPath> fields) {
    public Delegation {
      Objects.requireNonNull(modId);
      Objects.requireNonNull(version);
      Objects.requireNonNull(type);
      Objects.requireNonNull(fileName);
      fields = Set.copyOf(fields);
      if (modId.isBlank()
          || modId.length() > 64
          || version.isBlank()
          || version.length() > 128
          || fileName.isBlank()
          || fileName.length() > 1024
          || fields.isEmpty()
          || fields.size() > 16384
          || fields.stream()
              .anyMatch(
                  path ->
                      path.segments().isEmpty()
                          || path.segments().size() > 32
                          || path.segments().stream()
                              .anyMatch(segment -> !(segment instanceof FieldPath.Field))))
        throw new IllegalArgumentException(
            "Specify an exact native source and bounded leaf selection");
    }

    /**
     * Validate the registered native identity without parsing a file or selecting another source.
     */
    public ModContainer requireTarget(ModConfig config) {
      Objects.requireNonNull(config);
      if (!config.getModId().equals(modId)
          || config.getType() != type
          || !config.getFileName().equals(fileName)
          || ModConfigs.getConfigSet(type).stream().noneMatch(registered -> registered == config))
        throw refusal("native.delegate_target", "The selected native registration is unavailable");
      return ModList.get()
          .getModContainerById(modId)
          .filter(container -> container.getModInfo().getVersion().toString().equals(version))
          .orElseThrow(
              () ->
                  refusal("native.delegate_version", "The selected native version is unavailable"));
    }
  }

  public record Limits(
      int maximumBytes, int maximumFields, int maximumSessions, int maximumOperations) {
    public static final Limits STANDARD = new Limits(262144, 1024, 64, 16);

    public Limits {
      if (maximumBytes < 1
          || maximumBytes > 16_777_216
          || maximumFields < 1
          || maximumFields > 16384
          || maximumSessions < 1
          || maximumOperations < 1)
        throw new IllegalArgumentException("Invalid native owner admission limits");
    }
  }

  /** Pure complete-value validation and domain timing for an actual native registration. */
  public record Policy(
      Function<Map<String, Object>, ValidationReport> validate, ApplicationEffects effects) {
    public static final Policy STANDARD =
        new Policy(value -> ValidationReport.VALID, ApplicationEffects.UNDECLARED);

    public Policy {
      Objects.requireNonNull(validate);
      Objects.requireNonNull(effects);
    }
  }

  public record ReadValue(
      String revision,
      Optional<DraftTree> draft,
      Optional<Candidate<Map<String, Object>>> candidate,
      ValidationReport report)
      implements DocumentRead<Map<String, Object>> {}

  /** Disk and uncached native values are observations; neither is a domain-effective value. */
  public static final class Observation implements OwnerObservation<Map<String, Object>> {
    private final RegisteredConfig owner;
    private final UUID session;
    private final Capture nativeSource;
    private final Parsed saved;
    private final ReadValue document;

    private Observation(
        RegisteredConfig owner,
        UUID session,
        Capture nativeSource,
        Parsed saved,
        ValidationReport report) {
      this.owner = owner;
      this.session = session;
      this.nativeSource = nativeSource;
      this.saved = saved;
      document = new ReadValue(saved.source().revision, saved.draft(), saved.candidate(), report);
    }

    @Override
    public UUID context() {
      return owner.context.id();
    }

    @Override
    public UUID session() {
      return session;
    }

    @Override
    public ReadValue document() {
      return document;
    }

    public Path path() {
      return nativeSource.path();
    }

    public ModConfig.Type nativeType() {
      return owner.config.getType();
    }

    @Override
    public SourceInfo provenance() {
      return new SourceInfo(
          "neoforge",
          "Registered " + nativeType().name() + " configuration",
          Optional.of(path().toString()));
    }

    @Override
    public ApplicationEffects applicationEffects() {
      return owner.applicationEffects;
    }

    public Map<String, Object> nativeValues() {
      return owner.definition().type().freeze(nativeSource.raw());
    }
  }

  public static final class Prepared implements PreparedEdit<Map<String, Object>> {
    private final RegisteredConfig owner;
    private final UUID session;
    private Proposal proposal;

    private Prepared(RegisteredConfig owner, UUID session, Proposal proposal) {
      this.owner = owner;
      this.session = session;
      this.proposal = proposal;
    }

    @Override
    public DocumentId document() {
      return owner.definition().id();
    }

    @Override
    public UUID context() {
      return owner.context.id();
    }

    @Override
    public UUID session() {
      return session;
    }

    private synchronized Proposal take(RegisteredConfig expected, UUID actor) {
      if (owner != expected || !session.equals(actor))
        throw refusal("owner.session", "Proposal belongs to another native document or session");
      if (proposal == null)
        throw refusal("owner.consumed", "Native proposal was already submitted");
      var result = proposal;
      proposal = null;
      return result;
    }
  }

  /**
   * A native save exception cannot establish rollback of setters, persistence or listener effects.
   */
  public record Receipt(
      Persistence persistence,
      Application application,
      Optional<String> savedRevision,
      Optional<Observation> observation,
      ValidationReport persistenceReport,
      ValidationReport applicationReport,
      List<Requirement> requirements,
      boolean mutationStarted,
      boolean nativeSaveReturned)
      implements CommitReceipt<Map<String, Object>> {
    public Receipt {
      requirements = List.copyOf(requirements);
    }
  }

  private record Capture(IConfigSpec.ILoadedConfig loaded, Path path, Map<String, Object> raw) {}

  private record Parsed(
      NativeFile source,
      CommentedConfig toml,
      Optional<DraftTree> draft,
      Optional<Candidate<Map<String, Object>>> candidate,
      ValidationReport report) {}

  private record Proposal(Observation baseline, Candidate<Map<String, Object>> candidate) {}

  private record Attempt(
      Proposal proposal,
      boolean mutationStarted,
      boolean saveReturned,
      List<CommitReceipt.Requirement> requirements,
      boolean failed) {}

  public final class Session implements DocumentSession<Map<String, Object>> {
    private final UUID id = UUID.randomUUID();
    private final OwnerContext.Access access;
    private final AtomicBoolean open = new AtomicBoolean(true);

    private Session(OwnerContext.Access access) {
      this.access = access;
    }

    @Override
    public DocumentId document() {
      return definition().id();
    }

    @Override
    public DocumentDefinition<Map<String, Object>> definition() {
      return RegisteredConfig.this.definition();
    }

    @Override
    public UUID context() {
      return context.id();
    }

    @Override
    public UUID id() {
      return id;
    }

    @Override
    public boolean isOpen() {
      return open.get() && !closed && access.isOpen();
    }

    @Override
    public Set<Operation> operations() {
      return Set.of(Operation.READ, Operation.COMMIT);
    }

    private void current() {
      access.requireCurrent();
      if (!isOpen()) throw refusal("owner.closed", "Native document session is closed");
    }

    private void require(OwnerContext.Right right) {
      current();
      access.require(right);
      current();
    }

    @Override
    public CompletionStage<Observation> read() {
      return lane.submit(() -> observe(this));
    }

    @Override
    public CompletionStage<Prepared> prepare(
        OwnerObservation<Map<String, Object>> observed, DraftTree draft) {
      final Observation baseline;
      try {
        baseline = own(observed);
      } catch (RuntimeException failure) {
        return failed(failure);
      }
      return lane.submit(
          () ->
              context
                  .onOwner(
                      () -> {
                        require(OwnerContext.Right.READ);
                        if (!baseline.document().report().valid())
                          throw refusal(
                              "native.baseline",
                              "Resolve the saved/native observation before preparing a change");
                        return baseline;
                      })
                  .thenCompose(
                      ignored ->
                          context.onWorker(
                              () -> {
                                current();
                                var inspected =
                                    Objects.requireNonNull(draft).inspect(definition(), jsonLimits);
                                if (inspected.candidate().isEmpty())
                                  throw new PolicyRefusal(
                                      inspected
                                          .report()
                                          .and(
                                              error(
                                                  "native.validation",
                                                  "Complete candidate validation failed")));
                                return inspected.candidate().orElseThrow();
                              }))
                  .thenCompose(
                      candidate ->
                          context.onOwner(
                              () -> {
                                require(OwnerContext.Right.READ);
                                validateNative(candidate.value());
                                return new Prepared(
                                    RegisteredConfig.this, id, new Proposal(baseline, candidate));
                              })));
    }

    @Override
    public CompletionStage<Receipt> commit(PreparedEdit<Map<String, Object>> value) {
      if (!(value instanceof Prepared prepared))
        return failed(refusal("owner.session", "Proposal was not issued by this native owner"));
      final Proposal proposal;
      try {
        proposal = prepared.take(RegisteredConfig.this, id);
      } catch (RuntimeException refused) {
        return failed(refused);
      }
      return lane.submit(
          () ->
              context
                  .onOwner(
                      () -> {
                        require(OwnerContext.Right.WRITE);
                        verify(proposal.baseline().nativeSource);
                        validateNative(proposal.candidate().value());
                        return proposal;
                      })
                  .thenCompose(
                      admitted ->
                          context.onWorker(
                              () -> {
                                current();
                                var fresh =
                                    NativeFile.read(
                                        admitted.baseline().path(), limits.maximumBytes());
                                if (!fresh.same(admitted.baseline().saved.source()))
                                  throw refusal(
                                      "native.source_changed",
                                      "Native source changed; reload and review the draft");
                                return admitted;
                              }))
                  .thenCompose(admitted -> context.onOwner(() -> attempt(this, admitted)))
                  .<CompletionStage<Receipt>>handle(
                      (attempt, failure) ->
                          failure == null
                              ? finish(this, attempt)
                              : CompletableFuture.completedStage(unattempted(failure)))
                  .thenCompose(stage -> stage));
    }

    private Observation own(OwnerObservation<Map<String, Object>> value) {
      current();
      if (!(value instanceof Observation observed)
          || observed.owner != RegisteredConfig.this
          || !id.equals(observed.session))
        throw refusal("owner.session", "Observation belongs to another native document or session");
      return observed;
    }

    @Override
    public void close() {
      open.set(false);
      sessions.remove(this);
    }
  }

  private final OwnerContext context;
  private final ModConfig config;
  private final ModConfigSpec spec;
  private final NativeSchema schema;
  private final ApplicationEffects applicationEffects;
  private final Map<FieldPath, List<CommitReceipt.Requirement>> requirementsByField;
  private final Limits limits;
  private final JsonLimits jsonLimits;
  private final SerialLane lane;
  private final Set<Session> sessions = ConcurrentHashMap.newKeySet();
  private final OwnerContext.CloseHook closeHook;
  private volatile boolean closed;

  public RegisteredConfig(OwnerContext context, ModConfig config, DocumentId id) {
    this(context, config, id, Map.of(), Limits.STANDARD);
  }

  public RegisteredConfig(
      OwnerContext context,
      ModConfig config,
      DocumentId id,
      Map<FieldPath, ValueType<?>> explicitTypes,
      Limits limits) {
    this(context, config, id, explicitTypes, limits, Policy.STANDARD);
  }

  public RegisteredConfig(
      OwnerContext context,
      ModConfig config,
      DocumentId id,
      Map<FieldPath, ValueType<?>> explicitTypes,
      Limits limits,
      Policy policy) {
    this(context, config, id, explicitTypes, limits, Set.of(), policy);
  }

  /**
   * Keep the definition in the native owner's namespace. Consumers may give their route an alias.
   */
  public static RegisteredConfig delegated(
      OwnerContext context,
      ModConfig config,
      DocumentId nativeId,
      Delegation delegation,
      Map<FieldPath, ValueType<?>> explicitTypes,
      Limits limits) {
    return delegated(context, config, nativeId, delegation, explicitTypes, limits, Policy.STANDARD);
  }

  public static RegisteredConfig delegated(
      OwnerContext context,
      ModConfig config,
      DocumentId nativeId,
      Delegation delegation,
      Map<FieldPath, ValueType<?>> explicitTypes,
      Limits limits,
      Policy policy) {
    Objects.requireNonNull(context).requireOwner();
    Objects.requireNonNull(delegation).requireTarget(config);
    return new RegisteredConfig(
        context, config, nativeId, explicitTypes, limits, delegation.fields(), policy);
  }

  private RegisteredConfig(
      OwnerContext context,
      ModConfig config,
      DocumentId id,
      Map<FieldPath, ValueType<?>> explicitTypes,
      Limits limits,
      Set<FieldPath> selected,
      Policy policy) {
    this.context = Objects.requireNonNull(context);
    context.requireOwner();
    this.config = Objects.requireNonNull(config);
    this.limits = Objects.requireNonNull(limits);
    if (!(config.getSpec() instanceof ModConfigSpec declared))
      throw new IllegalArgumentException(
          "Native owner requires the actual registered ModConfigSpec");
    if (!config.getModId().equals(id.namespace()))
      throw new IllegalArgumentException(
          "Native document namespace must belong to its registered mod");
    spec = declared;
    Objects.requireNonNull(policy);
    schema =
        new NativeSchema(
            id,
            spec,
            Map.copyOf(explicitTypes),
            limits.maximumFields(),
            selected,
            policy.validate());
    for (var rule : policy.effects().rules())
      if (schema.fields.stream().noneMatch(field -> field.address().startsWith(rule.path())))
        throw new IllegalArgumentException(
            "Application timing needs an admitted native field scope");
    var effects = new ArrayList<ApplicationEffects.Rule>();
    var requirements = new LinkedHashMap<FieldPath, List<CommitReceipt.Requirement>>();
    for (var field : schema.fields) {
      var timing =
          switch (field.nativeValue().getSpec().restartType()) {
            case NONE -> ApplicationEffects.Timing.OWNER_MANAGED;
            case WORLD -> ApplicationEffects.Timing.WORLD_RESTART;
            case GAME -> ApplicationEffects.Timing.GAME_RESTART;
          };
      var declaredRule = policy.effects().forField(field.address());
      var timings = EnumSet.of(timing);
      String explanation =
          timing == ApplicationEffects.Timing.OWNER_MANAGED
              ? "Native raw values and reload callbacks do not certify domain-effective caches."
              : "The native owner retains this field's declared restart requirement.";
      if (declaredRule.isPresent()) {
        if (timing == ApplicationEffects.Timing.OWNER_MANAGED) timings.clear();
        timings.addAll(declaredRule.orElseThrow().timings());
        explanation = declaredRule.orElseThrow().explanation();
      }
      var rule = new ApplicationEffects.Rule(field.address(), timings, explanation);
      effects.add(rule);
      requirements.put(
          field.address(),
          timings.stream()
              .map(RegisteredConfig::requirementKind)
              .distinct()
              .map(kind -> new CommitReceipt.Requirement(field.address(), kind, rule.explanation()))
              .toList());
    }
    applicationEffects =
        new ApplicationEffects(
            effects,
            new ApplicationEffects.Limits(
                limits.maximumFields(),
                128,
                limits.maximumBytes() * 32 + limits.maximumFields() * 4096));
    requirementsByField = Map.copyOf(requirements);
    jsonLimits = new JsonLimits(limits.maximumBytes(), 64, 16384, 32768, 128, 4096);
    lane = new SerialLane(limits.maximumOperations());
    closeHook = context.onClose(this::close);
  }

  public DocumentDefinition<Map<String, Object>> definition() {
    return schema.definition;
  }

  private static CommitReceipt.RequirementKind requirementKind(ApplicationEffects.Timing timing) {
    return switch (timing) {
      case NEW_WORLD -> CommitReceipt.RequirementKind.NEW_WORLD;
      case WORLD_RESTART -> CommitReceipt.RequirementKind.WORLD_RESTART;
      case GAME_RESTART -> CommitReceipt.RequirementKind.GAME_RESTART;
      case INCOMPATIBLE -> CommitReceipt.RequirementKind.INCOMPATIBLE;
      default -> CommitReceipt.RequirementKind.OWNER_MANAGED;
    };
  }

  public Session session(OwnerContext.Access access) {
    context.requireOwner();
    if (closed || !access.isOpen() || !access.context().equals(context.id()))
      throw refusal("owner.context", "Actual native access belongs to another or closed context");
    if (sessions.size() >= limits.maximumSessions())
      throw refusal("owner.busy", "Native session admission is full");
    var session = new Session(access);
    sessions.add(session);
    return session;
  }

  private Capture capture() {
    context.requireOwner();
    if (closed || !spec.isLoaded() || config.getLoadedConfig() == null)
      throw refusal("native.unavailable", "The registered native configuration is not loaded");
    final Path path;
    try {
      path = config.getFullPath().toAbsolutePath().normalize();
    } catch (RuntimeException unavailable) {
      throw refusal("native.no_file", "This native context has no admitted file backing");
    }
    var loaded = config.getLoadedConfig();
    var raw = schema.raw();
    if (loaded != config.getLoadedConfig())
      throw refusal("native.context_changed", "Native loaded context changed during observation");
    return new Capture(loaded, path, raw);
  }

  private void verify(Capture baseline) {
    var now = capture();
    if (now.loaded() != baseline.loaded()
        || !now.path().equals(baseline.path())
        || !definition().type().equivalent(now.raw(), baseline.raw()))
      throw refusal(
          "native.context_changed", "Native loaded context or values changed; reload and review");
  }

  private CompletionStage<Observation> observe(Session session) {
    return context
        .onOwner(
            () -> {
              session.require(OwnerContext.Right.READ);
              return capture();
            })
        .thenCompose(
            nativeSource ->
                context
                    .onWorker(() -> parse(nativeSource.path()))
                    .thenCompose(
                        saved ->
                            context.onOwner(
                                () -> {
                                  session.require(OwnerContext.Right.READ);
                                  verify(nativeSource);
                                  return observation(session, nativeSource, saved);
                                })));
  }

  private Parsed parse(Path path) throws IOException {
    var source = NativeFile.read(path, limits.maximumBytes());
    try {
      var toml = new TomlParser().parse(source.text());
      var json = schema.sourceJson(toml);
      var parsed = JsonInput.read(json.toString(), jsonLimits);
      var draft = DraftTree.fromParsed(definition().type(), parsed, DraftLimits.CONFIGURATION);
      var inspected = draft.inspect(definition(), jsonLimits);
      return new Parsed(
          source, toml, Optional.of(draft), inspected.candidate(), inspected.report());
    } catch (RuntimeException | StackOverflowError malformed) {
      return new Parsed(
          source,
          null,
          Optional.empty(),
          Optional.empty(),
          unavailable(
              "native.source_invalid", "Native TOML could not be read within its declared limits"));
    }
  }

  private Observation observation(Session session, Capture nativeSource, Parsed saved) {
    var report = saved.report();
    if (saved.toml() != null) {
      try {
        if (!spec.isCorrect(saved.toml()))
          report =
              report.and(
                  error(
                      "native.specification",
                      "Saved source fails its actual native specification"));
      } catch (RuntimeException failed) {
        report =
            report.and(
                unavailable(
                    "native.validation_unavailable",
                    "Native source validation could not complete"));
      }
    }
    if (saved.candidate().isPresent()
        && !definition()
            .type()
            .equivalent(nativeSource.raw(), saved.candidate().orElseThrow().value()))
      report =
          report.and(
              error(
                  "native.source_mismatch",
                  "Saved and loaded native values differ; reload through their owner"));
    if (schema.projected()
        && saved.toml() != null
        && !schema.sameNativeValues(saved.toml(), nativeSource.loaded().config(), false))
      report =
          report.and(
              error("native.delegate_source", "The full saved and loaded native sources differ"));
    return new Observation(this, session.id, nativeSource, saved, report);
  }

  private void validateNative(Map<String, Object> candidate) {
    context.requireOwner();
    var issues = new ArrayList<Issue>();
    boolean complete = true;
    for (var field : schema.fields) {
      try {
        if (!field.nativeValue().getSpec().test(field.value(candidate)))
          issues.add(
              Issue.error(
                  "native.validation",
                  field.address(),
                  "Value fails its actual native specification"));
      } catch (RuntimeException failed) {
        complete = false;
        issues.add(
            Issue.error(
                "native.validation_unavailable",
                field.address(),
                "Actual native field validation could not complete"));
      }
    }
    var report = new ValidationReport(issues, complete);
    if (!report.valid()) throw new PolicyRefusal(report);
  }

  private Attempt attempt(Session session, Proposal proposal) throws IOException {
    session.require(OwnerContext.Right.WRITE);
    verify(proposal.baseline().nativeSource);
    var intended = proposal.candidate().value();
    validateNative(intended);
    // Exact bounded last check on the owner executor closes the worker-to-owner queue gap.
    // No owned-file lock is held across native save/listeners. This is not cross-process CAS.
    if (!NativeFile.read(proposal.baseline().path(), limits.maximumBytes())
        .same(proposal.baseline().saved.source()))
      throw refusal("native.source_changed", "Native source changed; reload and review the draft");
    session.require(OwnerContext.Right.WRITE);
    verify(proposal.baseline().nativeSource);
    if (schema.projected()
        && !schema.sameNativeValues(
            proposal.baseline().saved.toml(),
            proposal.baseline().nativeSource.loaded().config(),
            false))
      throw refusal("native.delegate_source", "The full saved and loaded native sources differ");
    var changed = new ArrayList<NativeSchema.Field>();
    var requirements = new ArrayList<CommitReceipt.Requirement>();
    for (var field : schema.fields) {
      if (field
          .type()
          .equivalent(field.value(proposal.baseline().nativeSource.raw()), field.value(intended)))
        continue;
      changed.add(field);
      requirements.addAll(requirementsByField.get(field.address()));
    }
    if (changed.isEmpty()) return new Attempt(proposal, false, false, List.of(), false);
    boolean mutationStarted = false;
    try {
      for (var field : changed) {
        // The public native setter can mutate before throwing; claim no generic rollback.
        mutationStarted = true;
        field.nativeValue().set(field.value(intended));
      }
      spec.save();
      return new Attempt(proposal, true, true, List.copyOf(requirements), false);
    } catch (RuntimeException | Error failed) {
      return new Attempt(proposal, mutationStarted, false, List.copyOf(requirements), true);
    }
  }

  private CompletionStage<Receipt> finish(Session session, Attempt attempt) {
    return context
        .onWorker(() -> parse(attempt.proposal().baseline().path()))
        .thenCompose(
            saved ->
                context.onOwner(
                    () -> {
                      boolean readable;
                      try {
                        session.require(OwnerContext.Right.READ);
                        readable = true;
                      } catch (PolicyRefusal refused) {
                        readable = false;
                      }
                      if (!readable) return withoutReadback(attempt);
                      final Capture now;
                      try {
                        now = capture();
                      } catch (RuntimeException unavailable) {
                        return withoutReadback(attempt);
                      }
                      if (now.loaded() != attempt.proposal().baseline().nativeSource.loaded()
                          || !now.path().equals(attempt.proposal().baseline().path()))
                        return withoutReadback(attempt);
                      var observed = observation(session, now, saved);
                      boolean preserved =
                          !schema.projected()
                              || saved.toml() != null
                                  && schema.sameNativeValues(
                                      attempt.proposal().baseline().saved.toml(),
                                      saved.toml(),
                                      true);
                      boolean intendedSaved =
                          preserved
                              && saved.candidate().isPresent()
                              && definition()
                                  .type()
                                  .equivalent(
                                      saved.candidate().orElseThrow().value(),
                                      attempt.proposal().candidate().value());
                      var persistence =
                          !attempt.mutationStarted()
                              ? CommitReceipt.Persistence.UNCHANGED
                              : intendedSaved
                                  ? CommitReceipt.Persistence.SAVED
                                  : saved
                                          .source()
                                          .same(attempt.proposal().baseline().saved.source())
                                      ? CommitReceipt.Persistence.FAILED
                                      : CommitReceipt.Persistence.UNKNOWN;
                      var application =
                          !attempt.mutationStarted()
                              ? CommitReceipt.Application.NOT_ATTEMPTED
                              : !attempt.saveReturned()
                                  ? CommitReceipt.Application.UNAVAILABLE
                                  : preserved
                                          && definition()
                                              .type()
                                              .equivalent(
                                                  now.raw(), attempt.proposal().candidate().value())
                                      ? CommitReceipt.Application.STAGED
                                      : CommitReceipt.Application.PARTIAL;
                      var persistenceReport =
                          persistence == CommitReceipt.Persistence.SAVED
                                  || persistence == CommitReceipt.Persistence.UNCHANGED
                              ? ValidationReport.VALID
                              : unavailable(
                                  "native.persistence_unconfirmed",
                                  "Native save did not establish the intended saved source");
                      var applicationReport =
                          attempt.failed()
                              ? unavailable(
                                  "native.save_failed",
                                  "Native mutation/save or a listener failed; effects were not rolled back")
                              : !preserved
                                  ? unavailable(
                                      "native.delegate_readback",
                                      "Other native values could not be confirmed")
                                  : ValidationReport.VALID;
                      return new Receipt(
                          persistence,
                          application,
                          Optional.of(saved.source().revision),
                          Optional.of(observed),
                          persistenceReport,
                          applicationReport,
                          attempt.requirements(),
                          attempt.mutationStarted(),
                          attempt.saveReturned());
                    }))
        .exceptionally(failed -> withoutReadback(attempt));
  }

  private static Receipt withoutReadback(Attempt attempt) {
    return new Receipt(
        attempt.mutationStarted()
            ? CommitReceipt.Persistence.UNKNOWN
            : CommitReceipt.Persistence.UNCHANGED,
        attempt.mutationStarted()
            ? CommitReceipt.Application.UNAVAILABLE
            : CommitReceipt.Application.NOT_ATTEMPTED,
        Optional.empty(),
        Optional.empty(),
        unavailable(
            "native.readback_unavailable",
            "Native readback is unavailable; retained execution facts only"),
        attempt.failed()
            ? unavailable("native.save_failed", "Native save failed; effects were not rolled back")
            : ValidationReport.VALID,
        attempt.requirements(),
        attempt.mutationStarted(),
        attempt.saveReturned());
  }

  private static Receipt unattempted(Throwable failure) {
    while (failure instanceof java.util.concurrent.CompletionException
        && failure.getCause() != null) failure = failure.getCause();
    var report =
        failure instanceof PolicyRefusal refused
            ? refused.report()
            : unavailable(
                "native.preflight_unavailable",
                "Native preflight could not complete; no mutation was attempted");
    return new Receipt(
        CommitReceipt.Persistence.REFUSED,
        CommitReceipt.Application.NOT_ATTEMPTED,
        Optional.empty(),
        Optional.empty(),
        report,
        ValidationReport.VALID,
        List.of(),
        false,
        false);
  }

  @Override
  public void close() {
    if (closed) return;
    context.checkOwnerThread();
    closed = true;
    lane.close();
    for (var session : sessions) session.close();
    closeHook.close();
  }

  private static ValidationReport error(String code, String text) {
    return new ValidationReport(List.of(Issue.error(code, FieldPath.ROOT, text)), true);
  }

  private static ValidationReport unavailable(String code, String text) {
    return new ValidationReport(List.of(Issue.error(code, FieldPath.ROOT, text)), false);
  }

  private static PolicyRefusal refusal(String code, String text) {
    return new PolicyRefusal(code, text);
  }

  private static <T> CompletionStage<T> failed(Throwable failure) {
    return CompletableFuture.<T>failedFuture(failure).minimalCompletionStage();
  }
}
