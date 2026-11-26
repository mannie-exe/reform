package design.inherent.reform.minecraft.client;

import static org.junit.jupiter.api.Assertions.*;

import design.inherent.reform.definition.*;
import design.inherent.reform.minecraft.client.ui.UiText;
import design.inherent.reform.minecraft.client.ui.Views;
import design.inherent.reform.presentation.*;
import design.inherent.reform.presentation.ui.UiLimits;
import design.inherent.reform.value.ValueTypes;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.network.chat.Component;
import org.junit.jupiter.api.Test;

/** Metadata validation is headless; actual admission/retirement is a packaged native fixture. */
class ClientCustomViewTest {
  @Test
  void documentComponentValidatesRouteAndOwnerWithoutOpeningEitherFactory() {
    var definition =
        new DocumentDefinition<>(
            DocumentId.parse("example:owner"), 1, ValueTypes.text(128), () -> "Default");
    var route = DocumentId.parse("example:alias");
    var destination = new DestinationId(DocumentId.parse("example:task"));
    var factories = new AtomicInteger();
    var view =
        ClientCustomView.documentComponent(
            new ViewKey<>(
                destination,
                new ViewStateKey<>(DocumentId.parse("example:view_state"), ValueTypes.bool())),
            false,
            UiLimits.STANDARD,
            route,
            definition,
            (scope, editor, state) -> {
              factories.incrementAndGet();
              return Views.text(
                  "content", UiText.of(Component.empty()), UiText.of(Component.empty()));
            });
    var form =
        ClientForm.of(
            definition.id(),
            Component.literal("Owner"),
            () -> {
              throw new AssertionError("Metadata must not enter availability");
            },
            parent -> {
              throw new AssertionError("Metadata must not open an editor");
            },
            Optional.empty());
    var layout =
        new ClientWorkflowLayout(
            List.of(
                new ClientWorkflowLayout.Group(
                    List.of(
                        new ClientWorkflowLayout.Category(
                            destination.value(), Component.literal("Task"), Component.empty())),
                    List.of(
                        new ClientWorkflowLayout.Placement(
                            route, route, Component.literal("Owner"), Optional.empty())),
                    List.of())),
            List.of());
    var shell =
        ClientWorkspacePresentation.standard(
                new WorkspaceIdentity(
                    DocumentId.parse("example:workspace"), Component.literal("Workspace")))
            .withView(view);
    assertTrue(shell.validate(List.of(form.atRoute(route)), layout).valid());
    assertFalse(shell.validate(List.of(form), layout).valid());
    var other =
        ClientForm.of(
                DocumentId.parse("example:other_owner"),
                Component.literal("Other"),
                () -> {
                  throw new AssertionError("Metadata must stay lazy");
                },
                parent -> {
                  throw new AssertionError();
                },
                Optional.empty())
            .atRoute(route);
    assertFalse(shell.validate(List.of(other), layout).valid());
    assertEquals(route, view.owner().orElseThrow().route());
    assertSame(definition, view.owner().orElseThrow().definition());
    assertEquals(0, factories.get());
  }
}
