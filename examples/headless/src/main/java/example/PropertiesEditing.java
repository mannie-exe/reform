package example;

import design.inherent.reform.definition.DocumentDefinition;
import design.inherent.reform.definition.DocumentId;
import design.inherent.reform.definition.FieldKey;
import design.inherent.reform.definition.FieldPath;
import design.inherent.reform.definition.RecordType;
import design.inherent.reform.edit.DraftEdit;
import design.inherent.reform.edit.DraftLimits;
import design.inherent.reform.edit.DraftTree;
import design.inherent.reform.operation.DocumentActions;
import design.inherent.reform.operation.OperationDescriptor.EntryPoint;
import design.inherent.reform.owner.CommitReceipt;
import design.inherent.reform.owner.EditorActor;
import design.inherent.reform.owner.FileOwner;
import design.inherent.reform.owner.OwnerContext;
import design.inherent.reform.serialization.JsonLimits;
import design.inherent.reform.storage.OwnedFile;
import design.inherent.reform.storage.PropertiesStore;
import design.inherent.reform.storage.StorageBinding;
import design.inherent.reform.value.ValueTypes;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.Optional;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/** Ordinary public-artifact consumer of immutable startup flags, with no Minecraft classes. */
final class PropertiesEditing {
  private record Features(boolean terrain, boolean captions) {}

  static void verify() throws Exception {
    var terrain = FieldKey.of("terrain", ValueTypes.defaultedField(ValueTypes.bool(), true));
    var captions = FieldKey.of("captions", ValueTypes.defaultedField(ValueTypes.bool(), false));
    var type =
        RecordType.<Features>builder(v -> new Features(v.get(terrain), v.get(captions)))
            .field(terrain, Features::terrain)
            .field(captions, Features::captions)
            .build();
    var definition =
        new DocumentDefinition<>(
            DocumentId.parse("example:startup"), 1, type, () -> new Features(true, false));
    var limits = new JsonLimits(8192, 16, 512, 256, 64, 1024);
    var active = new Features(true, true);
    Path fixture = Files.createTempDirectory("reform-public-properties-");
    try (var nativeExecutor = Executors.newSingleThreadExecutor();
        var worker = Executors.newFixedThreadPool(2)) {
      long nativeThread = nativeExecutor.submit(() -> Thread.currentThread().threadId()).get();
      var source =
          new PropertiesStore<>(
              definition,
              new OwnedFile(StorageBinding.relative(fixture, "features.properties"), 8192),
              limits,
              DraftLimits.CONFIGURATION);
      var context =
          new OwnerContext(
              nativeExecutor,
              worker,
              () -> {
                if (Thread.currentThread().threadId() != nativeThread)
                  throw new IllegalStateException("Wrong public owner executor");
              },
              OwnerContext.Limits.STANDARD);
      try {
        var actualStartup =
            DraftTree.from(type, active, DraftLimits.CONFIGURATION)
                .inspect(definition, limits)
                .candidate()
                .orElseThrow();
        var owner =
            await(
                nativeExecutor
                    .submit(() -> FileOwner.startup(context, source, 8, Optional.of(actualStartup)))
                    .get());
        if (Files.exists(source.binding().path()))
          throw new IllegalStateException("Startup registration created a file");
        Files.writeString(source.binding().path(), "# author\rcaptions=false\r");
        var access = nativeExecutor.submit(() -> context.access(() -> true, () -> true)).get();
        var editor =
            nativeExecutor
                .submit(
                    () ->
                        new EditorActor<>(
                            context,
                            owner.open(access),
                            definition,
                            limits,
                            DraftLimits.CONFIGURATION,
                            16))
                .get();
        var actions =
            nativeExecutor.submit(() -> new DocumentActions<>(context, access, editor)).get();
        try {
          await(editor.load());
          var path = FieldPath.ROOT.field("terrain");
          await(
              editor.edit(
                  new DraftEdit(
                      editor.view().snapshot().orElseThrow().target(path),
                      new DraftEdit.Override(true))));
          await(editor.setRaw(path.append(FieldPath.Part.VALUE), "false"));
          var review =
              await(
                  DocumentActions.result(
                      nativeExecutor.submit(() -> actions.review(EntryPoint.COMMAND)).get()));
          if (!review.source().kind().equals("properties")
              || !review.effective().orElseThrow().valuesCaptured())
            throw new IllegalStateException("Properties review lost source or startup identity");
          var saved =
              await(
                  DocumentActions.result(
                      nativeExecutor
                          .submit(() -> actions.commit(EntryPoint.GUI, review.target()))
                          .get()));
          var receipt = saved.receipt().orElseThrow();
          if (receipt.persistence() != CommitReceipt.Persistence.SAVED
              || receipt.application() != CommitReceipt.Application.STAGED
              || receipt.requirements().getFirst().kind()
                  != CommitReceipt.RequirementKind.GAME_RESTART
              || !owner.handle().view().value().orElseThrow().equals(active)
              || editor.view().dirty()
              || !Files.readString(source.binding().path())
                  .equals("# author\rcaptions=false\rterrain=false\r"))
            throw new IllegalStateException("Startup save changed active values or source format");
        } finally {
          nativeExecutor.submit(actions::close).get();
          editor.close();
        }
      } finally {
        nativeExecutor.submit(context::close).get(10, TimeUnit.SECONDS);
      }
    } finally {
      try (var paths = Files.walk(fixture)) {
        for (var path : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
      }
    }
  }

  private static <T> T await(CompletionStage<T> stage) throws Exception {
    return stage.toCompletableFuture().get(10, TimeUnit.SECONDS);
  }

  private PropertiesEditing() {}
}
