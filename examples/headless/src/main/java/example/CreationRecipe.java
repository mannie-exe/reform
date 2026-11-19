package example;

import design.inherent.reform.definition.DocumentDefinition;
import design.inherent.reform.definition.DocumentId;
import design.inherent.reform.definition.FieldKey;
import design.inherent.reform.definition.RecordType;
import design.inherent.reform.edit.DraftLimits;
import design.inherent.reform.operation.DocumentActions;
import design.inherent.reform.operation.OperationDescriptor.EntryPoint;
import design.inherent.reform.operation.OperationScope;
import design.inherent.reform.operation.OperationSnapshot;
import design.inherent.reform.owner.CommitReceipt;
import design.inherent.reform.owner.ContextRecipeOwner;
import design.inherent.reform.owner.EditorActor;
import design.inherent.reform.owner.OwnerContext;
import design.inherent.reform.serialization.JsonLimits;
import design.inherent.reform.value.SnapshotPolicy;
import design.inherent.reform.value.ValueTypes;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/** Public packaged API, with a small immutable headless context rather than a Minecraft world. */
final class CreationRecipe {
  record Recipe(int size) {}

  record Context(Recipe recipe, Object unedited) {}

  record Compiled(int size) {}

  static void verify() throws Exception {
    var key = FieldKey.of("size", ValueTypes.integer(1, 16));
    var definition =
        new DocumentDefinition<>(
            DocumentId.parse("example:creation"),
            1,
            RecordType.<Recipe>builder(v -> new Recipe(v.get(key)))
                .field(key, Recipe::size)
                .build(),
            () -> new Recipe(4));
    try (var nativeExecutor = Executors.newSingleThreadExecutor();
        var workers = Executors.newFixedThreadPool(2)) {
      long nativeThread = nativeExecutor.submit(() -> Thread.currentThread().threadId()).get();
      var context =
          new OwnerContext(
              nativeExecutor,
              workers,
              () -> {
                if (Thread.currentThread().threadId() != nativeThread)
                  throw new IllegalStateException("Wrong native executor");
              },
              OwnerContext.Limits.STANDARD);
      try {
        var current = new AtomicReference<>(new Context(new Recipe(4), new Object()));
        var original = current.get();
        var domain =
            new ContextRecipeOwner.Domain<Recipe, Context, Compiled>() {
              @Override
              public Context current() {
                return current.get();
              }

              @Override
              public Recipe read(Context source) {
                return source.recipe();
              }

              @Override
              public Compiled compile(Context source, Recipe recipe) {
                return new Compiled(recipe.size());
              }

              @Override
              public Context admit(Context expected, Compiled compiled) {
                var next = new Context(new Recipe(compiled.size()), expected.unedited());
                if (!current.compareAndSet(expected, next))
                  throw new IllegalStateException("Stale actual context");
                return next;
              }
            };
        var actions =
            nativeExecutor
                .submit(
                    () -> {
                      var limits = new JsonLimits(8192, 32, 1024, 512, 64, 1024);
                      var owner =
                          new ContextRecipeOwner<>(
                              context,
                              definition,
                              original,
                              domain,
                              SnapshotPolicy.<Compiled>immutable(),
                              limits,
                              DraftLimits.CONFIGURATION,
                              16);
                      var access = context.access(() -> true, () -> true);
                      return DocumentActions.owningContext(
                          context,
                          access,
                          new EditorActor<>(
                              context,
                              owner.open(access),
                              definition,
                              limits,
                              DraftLimits.CONFIGURATION,
                              16),
                          OperationScope.Limits.STANDARD,
                          8192);
                    })
                .get();
        DocumentActions.result(
                nativeExecutor.submit(() -> actions.edit(EntryPoint.COMMAND, "size", "8")).get())
            .toCompletableFuture()
            .get(10, TimeUnit.SECONDS);
        var reviewed =
            DocumentActions.result(
                    nativeExecutor.submit(() -> actions.review(EntryPoint.GUI)).get())
                .toCompletableFuture()
                .get(10, TimeUnit.SECONDS);
        var committed =
            DocumentActions.result(
                    nativeExecutor
                        .submit(() -> actions.commit(EntryPoint.GUI, reviewed.target()))
                        .get())
                .toCompletableFuture()
                .get(10, TimeUnit.SECONDS);
        var receipt = committed.receipt().orElseThrow();
        if (receipt.persistence() != CommitReceipt.Persistence.NOT_ATTEMPTED
            || receipt.savedRevision().isPresent()
            || receipt.application() != CommitReceipt.Application.APPLIED
            || actions.view().dirty()
            || current.get().recipe().size() != 8
            || current.get().unedited() != original.unedited())
          throw new IllegalStateException("Public creation receipt or context was incorrect");
        var refused =
            nativeExecutor
                .submit(() -> actions.save(EntryPoint.COMMAND))
                .get()
                .completion()
                .toCompletableFuture()
                .get(10, TimeUnit.SECONDS);
        if (refused.state() != OperationSnapshot.State.REFUSED)
          throw new IllegalStateException("A creation context pretended to save a file");
        nativeExecutor.submit(actions::close).get(10, TimeUnit.SECONDS);
        if (context.isOpen())
          throw new IllegalStateException("The owning editor leaked its context");
      } finally {
        nativeExecutor.submit(context::close).get(10, TimeUnit.SECONDS);
      }
    }
  }

  private CreationRecipe() {}
}
