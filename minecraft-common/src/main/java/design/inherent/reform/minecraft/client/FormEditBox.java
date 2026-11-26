package design.inherent.reform.minecraft.client;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.narration.NarratedElementType;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.network.chat.Component;

/** Observe the native selection setter without reflection or a second text/value authority. */
class FormEditBox extends NativeTextInput {
  private int anchor;
  private int maximumCharacters;
  private boolean acceptsInput;
  private Component completionNarration = Component.empty();

  void completionNarration(Component value) {
    completionNarration = value.copy();
  }

  @Override
  public void updateWidgetNarration(NarrationElementOutput output) {
    super.updateWidgetNarration(output);
    if (!completionNarration.getString().isBlank())
      output.add(NarratedElementType.HINT, completionNarration);
  }

  FormEditBox(Font font, int x, int y, int width, int height, Component label) {
    super(font, x, y, width, height, label);
    setEditable(true);
  }

  @Override
  public void setMaxLength(int value) {
    super.setMaxLength(value);
    maximumCharacters = value;
  }

  @Override
  public void setEditable(boolean value) {
    super.setEditable(value);
    acceptsInput = value;
  }

  int maximumCharacters() {
    return maximumCharacters;
  }

  boolean acceptsInput() {
    return acceptsInput;
  }

  @Override
  public void setHighlightPos(int value) {
    super.setHighlightPos(value);
    anchor = Math.clamp(value, 0, getValue().length());
  }

  int selection() {
    return anchor;
  }
}
