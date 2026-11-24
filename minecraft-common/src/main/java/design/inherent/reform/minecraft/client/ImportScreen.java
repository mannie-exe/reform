package design.inherent.reform.minecraft.client;

import design.inherent.reform.importing.ImportCatalog;
import design.inherent.reform.importing.ImportFormat;
import design.inherent.reform.importing.ImportSource;
import design.inherent.reform.operation.DocumentActions;
import design.inherent.reform.operation.DocumentImports;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.function.BooleanSupplier;
import net.minecraft.network.chat.Component;

/** Native standalone host for the same guarded import view borrowed by a matching workspace. */
public final class ImportScreen<T> extends FormHostScreen implements ClientImportInspection {
  private final ImportView<T> view;
  private boolean borrowed, mounted;

  public ImportScreen(
      Component title,
      Component contextLabel,
      DocumentActions<T> actions,
      List<? extends ImportFormat<T>> formats,
      BooleanSupplier available,
      Runnable returned,
      Runnable abandoned) {
    this(title, contextLabel, actions, formats, available, returned, abandoned, Optional.empty());
  }

  public ImportScreen(
      Component title,
      Component contextLabel,
      DocumentActions<T> actions,
      List<? extends ImportFormat<T>> formats,
      BooleanSupplier available,
      Runnable returned,
      Runnable abandoned,
      Optional<ImportCatalog<T>> catalog) {
    super(title);
    view =
        new ImportView<>(
            title, contextLabel, actions, formats, available, returned, abandoned, catalog);
  }

  /** Presentation order only; the source format and actual comparison remain unchanged. */
  public ImportScreen<T> withFieldOrder(
      java.util.List<design.inherent.reform.definition.FieldPath> paths) {
    view.withFieldOrder(paths);
    return this;
  }

  /** Retain the invoking form's declared field captions without changing serialized IDs. */
  public ImportScreen<T> withFieldPresentation(
      design.inherent.reform.presentation.FormPresentation presentation) {
    view.withFieldPresentation(presentation);
    return this;
  }

  @Override
  public Optional<DocumentImports.Preview> reviewedInput() {
    return view.reviewedInput();
  }

  @Override
  public void onFilesDrop(List<Path> paths) {
    view.onFilesDrop(paths);
  }

  public void selectSource(ImportSource source) {
    view.selectSource(source);
  }

  boolean borrows(DocumentActions<?> actions) {
    return view.borrows(actions);
  }

  ImportView<T> borrowView() {
    if (mounted || borrowed) throw new IllegalStateException("Import already has a native host");
    borrowed = true;
    view.presentation(theme(), taskPresentation());
    sealPresentation();
    return view;
  }

  @Override
  protected void init() {
    if (borrowed) throw new IllegalStateException("Import belongs to its retained workspace");
    mounted = true;
    suspendView();
    view.presentation(theme(), taskPresentation());
    mount(view);
  }

  @Override
  protected void beforeRebuild() {
    view.preserve();
  }
}
