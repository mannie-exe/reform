package design.inherent.reform.neoforge.client;

import design.inherent.reform.definition.FieldPath;
import java.util.Map;
import java.util.Objects;

/** Native key controls composed into the existing generated form, never a second settings owner. */
public final class KeyBindingEditors {
  public static FieldEditors<Map<String, KeyBindingOptions.Binding>> forSession(
      KeyBindingOptions.Session session) {
    Objects.requireNonNull(session);
    var editors = FieldEditors.builder(session.definition());
    for (var name : session.mappingNames())
      editors.field(
          FieldPath.ROOT.field(name), request -> new KeyBindingScreen(request, session, name));
    return editors.build();
  }

  private KeyBindingEditors() {}
}
