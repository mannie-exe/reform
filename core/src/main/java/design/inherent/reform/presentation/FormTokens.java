package design.inherent.reform.presentation;

/**
 * Shared native form roles. Dimensions use logical GUI pixels unless explicitly named otherwise.
 */
public final class FormTokens {
  private static final int UNIT = 2;

  public static final class Space {
    public static final int INLINE = UNIT * 2;
    public static final int TEXT = UNIT * 2;
    public static final int CONTENT = UNIT * 4;
    public static final int FIELD = UNIT * 5;
    public static final int SECTION = UNIT * 6;
    public static final int RELATED = UNIT * 6;
    public static final int SCREEN = UNIT * 6;
    public static final int NESTED_SECTION = UNIT * 3;

    private Space() {}
  }

  public static final class Control {
    public static final int HEIGHT = UNIT * 10;
    public static final int MINIMUM_WIDTH = UNIT * 28;
    public static final int CAPTION_PADDING = UNIT * 10;
    public static final int NUMERIC_INPUT = UNIT * 50;
    public static final int TEXT_INPUT = UNIT * 80;
    public static final int SLIDER_PREFERRED = UNIT * 80;
    public static final int CHOICE_INDICATOR = UNIT * 2;
    public static final int STEPPER = UNIT * 12;
    public static final int INFORMATION = UNIT * 10;
    public static final int UNIT_MINIMUM = UNIT * 24;
    public static final int UNIT_PREFERRED_LIMIT = UNIT * 120;

    private Control() {}
  }

  public static final class Measure {
    /** A font-measured preferred reading offer, never a limit on authored text. */
    public static final int READING_COLUMNS = 64;

    public static final int TITLE_MINIMUM_HEIGHT = UNIT * 10;
    public static final int STATUS_MINIMUM_HEIGHT = UNIT * 10;

    public static final int TEXT_MINIMUM = UNIT * 50;
    public static final int TEXT_PREFERRED_LIMIT = UNIT * 100;
    public static final int LABEL_PREFERRED_LIMIT = UNIT * 60;
    public static final int NAVIGATION_MINIMUM = UNIT * 56;
    public static final int EDITOR_MINIMUM = UNIT * 90;
    public static final int INFORMATION_MINIMUM = UNIT * 90;
    public static final int INFORMATION_PREFERRED = UNIT * 110;
    public static final int GRAPHIC_PREFERRED = UNIT * 110;
    public static final int CONTENT_MINIMUM_HEIGHT = UNIT * 16;

    private Measure() {}
  }

  /** Surfaces use ARGB; text retains Minecraft's native RGB convention. */
  public static final class Color {
    public static final int SURFACE_FORM = 0xec101010;
    public static final int SURFACE_NAVIGATION = 0xec202020;
    public static final int SURFACE_SELECTED = 0xff3d3d3d;
    public static final int SURFACE_HOVER = 0x88555555;
    public static final int LINE = 0xff555555;
    public static final int LINE_SELECTED = 0xff888888;
    public static final int FOCUS = 0xffffffff;
    public static final int TEXT = 0xffffff;
    public static final int TEXT_SUPPORTING = 0xaaaaaa;
    public static final int TEXT_ACTIVE = 0xffff55;
    public static final int TEXT_ERROR = 0xff5555;

    private Color() {}
  }

  private FormTokens() {}
}
