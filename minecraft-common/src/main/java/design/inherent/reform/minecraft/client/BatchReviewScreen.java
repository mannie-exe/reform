package design.inherent.reform.minecraft.client;

import design.inherent.reform.definition.DocumentId;
import design.inherent.reform.definition.FieldPath;
import design.inherent.reform.operation.DocumentBatch;
import design.inherent.reform.operation.OperationHandle;
import design.inherent.reform.presentation.FormPresentation;
import design.inherent.reform.validation.ValidationReport;
import java.util.*;
import java.util.function.*;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** Standalone host for complete batch review; the invoking workflow can borrow the same body. */
public final class BatchReviewScreen extends FormHostScreen implements ClientBatchInspection {
  /** Copied presentation facts; they do not change schema or captured value order. */
  public record Fields(List<FieldPath> order, FormPresentation presentation) {
    public Fields {
      order = List.copyOf(order);
      Objects.requireNonNull(presentation);
      if (new HashSet<>(order).size() != order.size())
        throw new IllegalArgumentException("Field order needs distinct paths");
    }
  }

  private final BatchReviewView view;
  private boolean borrowed, mounted;

  public BatchReviewScreen(
      Component title,
      DocumentBatch batch,
      BooleanSupplier available,
      Function<DocumentId, Component> label,
      BiConsumer<DocumentBatch.Entry, Screen> inspect,
      Runnable returned,
      Runnable abandoned) {
    super(title);
    Objects.requireNonNull(inspect);
    view =
        new BatchReviewView(
            title,
            batch,
            available,
            label,
            entry -> inspect.accept(entry, this),
            returned,
            abandoned);
  }

  @Override
  public Optional<DocumentBatch.Review> reviewedInput() {
    return view.reviewedInput();
  }

  @Override
  public Optional<OperationHandle<DocumentBatch.Result>> submission() {
    return view.submission();
  }

  @Override
  public Optional<DocumentId> selectedDocument() {
    return view.selectedDocument();
  }

  boolean borrows(DocumentBatch actual) {
    return view.borrows(actual);
  }

  public BatchReviewScreen withFieldPresentations(Map<DocumentId, Fields> fields) {
    if (borrowed || mounted)
      throw new IllegalStateException("Declare batch presentation before binding");
    view.withFieldPresentations(fields);
    return this;
  }

  /** Cached native-input readiness for complete capture and submission; never source authority. */
  public BatchReviewScreen withInputGuard(Supplier<ValidationReport> guard) {
    if (borrowed || mounted)
      throw new IllegalStateException("Declare the batch guard before binding");
    view.withInputGuard(guard);
    return this;
  }

  BatchReviewView borrowView() {
    if (borrowed || mounted)
      throw new IllegalStateException("Batch review already has a native host");
    borrowed = true;
    view.presentation(theme(), taskPresentation());
    sealPresentation();
    return view;
  }

  @Override
  protected void init() {
    if (borrowed) throw new IllegalStateException("Batch review belongs to its invoking workspace");
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
