package design.inherent.reform.minecraft.client;

import java.util.Optional;

/** Pure measured popup placement in the actual native root, independent of body clipping. */
record CompletionPlacement(int x, int y, int width, int height, int listY, int rows) {
  static Optional<CompletionPlacement> fit(
      int screenWidth,
      int screenHeight,
      int anchorX,
      int anchorY,
      int anchorWidth,
      int anchorHeight,
      int rowHeight,
      int requestedRows,
      int headingHeight,
      int footerHeight) {
    if (screenWidth < 1
        || screenHeight < 1
        || anchorWidth < 1
        || anchorHeight < 1
        || rowHeight < 1
        || requestedRows < 1
        || headingHeight < 0
        || footerHeight < 0)
      throw new IllegalArgumentException("Use measured finite completion geometry");
    int margin = 4;
    int width = Math.min(Math.max(anchorWidth, 180), Math.max(0, screenWidth - 2 * margin));
    if (width < 1
        || anchorX < 0
        || (long) anchorX + anchorWidth > screenWidth
        || anchorY < 0
        || (long) anchorY + anchorHeight > screenHeight) return Optional.empty();
    int below = Math.max(0, screenHeight - margin - anchorY - anchorHeight);
    int above = Math.max(0, anchorY - margin);
    int fixed = Math.addExact(Math.addExact(headingHeight, footerHeight), 4);
    int desired = Math.addExact(fixed, Math.multiplyExact(rowHeight, requestedRows));
    boolean down = below >= desired || below >= above;
    int room = down ? below : above;
    int rows = Math.min(requestedRows, Math.max(0, room - fixed) / rowHeight);
    if (rows < 1) return Optional.empty();
    int height = fixed + rows * rowHeight;
    int x = Math.clamp(anchorX, margin, Math.max(margin, screenWidth - margin - width));
    int y = down ? anchorY + anchorHeight : anchorY - height;
    return Optional.of(new CompletionPlacement(x, y, width, height, y + 2 + headingHeight, rows));
  }

  boolean contains(double px, double py) {
    return px >= x && px < (long) x + width && py >= y && py < (long) y + height;
  }
}
