package design.inherent.reform.neoforge.client;

import design.inherent.reform.owner.CommitReceipt;
import design.inherent.reform.owner.EditorActor;
import design.inherent.reform.validation.ValidationReport;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.network.chat.Component;

/** Native presentation of actual receipts; it never derives effects from an operation state. */
final class CommitFeedback {
  private CommitFeedback() {}

  private static Component text(String key, String fallback) {
    return Component.translatableWithFallback("reform.settings." + key, fallback);
  }

  static OperationScreen.Outcome details(EditorActor.Commit<?> result) {
    var rows = new ArrayList<OperationScreen.Detail>();
    rows.add(
        new OperationScreen.Detail(
            text("result_scope", "Result scope"),
            text(
                "result_scope_help",
                "These facts belong to this operation. Later draft edits and other operations remain separate.")));
    result
        .receipt()
        .ifPresentOrElse(
            receipt -> {
              rows.add(
                  new OperationScreen.Detail(text("result", "Owner result"), summary(receipt)));
              rows.add(
                  new OperationScreen.Detail(
                      text("persistence", "Persistence"),
                      Component.literal(receipt.persistence().name())));
              rows.add(
                  new OperationScreen.Detail(
                      text("application", "Application"),
                      Component.literal(receipt.application().name())));
              rows.add(
                  new OperationScreen.Detail(
                      text("acknowledgement", "Baseline acknowledgement"),
                      Component.literal(Boolean.toString(receipt.acknowledgesBaseline()))));
              receipt
                  .savedRevision()
                  .ifPresent(
                      value ->
                          rows.add(
                              new OperationScreen.Detail(
                                  text("saved_revision", "Saved revision"),
                                  Component.literal(value))));
              if (receipt.baselineOutcomeUnknown())
                rows.add(
                    new OperationScreen.Detail(
                        text("reconcile", "Reconciliation required"),
                        text(
                            "reconcile_help",
                            "Do not repeat persistence or admission blindly. Return to the editor and review the actual source or creation context.")));
              report(
                  rows,
                  text("persistence_diagnostics", "Persistence diagnostics"),
                  receipt.persistenceReport());
              report(
                  rows,
                  text("application_diagnostics", "Application diagnostics"),
                  receipt.applicationReport());
              for (var requirement : receipt.requirements())
                rows.add(
                    new OperationScreen.Detail(
                        Component.literal(requirement.path() + " · " + requirement.kind()),
                        Component.literal(requirement.explanation())));
            },
            () ->
                rows.add(
                    new OperationScreen.Detail(
                        text("receipt_absent", "No owner receipt"),
                        text(
                            "receipt_absent_help",
                            "No persistence or application result was returned. A terminal operation does not establish completed effects."))));
    report(
        rows,
        text("editor_diagnostics", "Editor acknowledgement diagnostics"),
        result.editorReport());
    return new OperationScreen.Outcome(
        result
            .receipt()
            .map(CommitFeedback::summary)
            .orElseGet(() -> text("receipt_absent", "No owner receipt")),
        rows);
  }

  private static void report(
      List<OperationScreen.Detail> rows, Component label, ValidationReport report) {
    rows.add(
        new OperationScreen.Detail(
            label,
            Component.literal("complete=" + report.complete() + ", valid=" + report.valid())));
    for (var issue : report.issues())
      rows.add(
          new OperationScreen.Detail(
              label.copy().append(" · ").append(issue.path().toString()),
              Component.literal(issue.fallback())));
  }

  static Component summary(CommitReceipt<?> receipt) {
    if (receipt.persistence() == CommitReceipt.Persistence.NOT_ATTEMPTED
        && receipt.acknowledgesBaseline())
      return receipt.applicationReport().valid()
          ? text("recipe_applied", "Applied to this creation context; no file saved")
          : text(
              "recipe_applied_unobserved",
              "Application confirmed; the accepted recipe could not be observed");
    if (receipt.baselineOutcomeUnknown())
      return receipt.persistence() == CommitReceipt.Persistence.NOT_ATTEMPTED
          ? text(
              "context_outcome_unknown",
              "Application outcome needs review; reopen the recipe from the current creation context")
          : text("outcome_unknown", "The operation outcome needs review; do not repeat it blindly");
    if (receipt.persistence() == CommitReceipt.Persistence.NOT_ATTEMPTED
        && receipt.application() == CommitReceipt.Application.APPLIED)
      return text("applied_without_persistence", "Values applied without attempting persistence.");
    if (receipt.persistence() == CommitReceipt.Persistence.SAVED
        || receipt.persistence() == CommitReceipt.Persistence.UNCHANGED) {
      if (receipt.application() == CommitReceipt.Application.STAGED)
        return text(
            "saved_staged", "Saved; review the owner's application and restart requirements.");
      if (receipt.application() == CommitReceipt.Application.PARTIAL)
        return text("saved_partial", "Saved; only part of the application was confirmed.");
      if (receipt.persistence() == CommitReceipt.Persistence.UNCHANGED
          && receipt.application() == CommitReceipt.Application.NOT_ATTEMPTED)
        return text("unchanged", "No changes to save");
      return receipt.application() == CommitReceipt.Application.APPLIED
          ? text("saved", "Saved and applied")
          : text(
              "saved_not_applied", "Saved; application did not complete. Review the owner result.");
    }
    return receipt.persistenceReport().issues().isEmpty()
        ? text(
            "receipt_unconfirmed",
            "No successful persistence is confirmed; inspect the actual receipt.")
        : Component.literal(receipt.persistenceReport().issues().getFirst().fallback());
  }
}
