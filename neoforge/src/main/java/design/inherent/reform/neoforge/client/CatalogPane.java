package design.inherent.reform.neoforge.client;

import design.inherent.reform.definition.DocumentId;
import design.inherent.reform.definition.FieldPath;
import design.inherent.reform.presentation.FormCatalog;
import design.inherent.reform.presentation.FormGeometry;
import design.inherent.reform.presentation.FormModel;
import design.inherent.reform.presentation.SectionNavigation;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.locale.Language;
import net.minecraft.network.chat.Component;

/** One form's cached navigation/presentation; no owner, operation, serializer or session. */
final class CatalogPane {
  interface Host {
    boolean active();

    boolean change(Runnable navigation);

    void add(FormPanel panel);

    void add(AbstractWidget widget);

    void remove(GuiEventListener widget);

    GuiEventListener focused();

    void focus(GuiEventListener widget);
  }

  record Layout(int x, int y, int width, int height, boolean listOnly, boolean allFields) {}

  enum Area {
    SEARCH,
    LIST,
    CRUMBS,
    OTHER
  }

  record Focus(Area area, String key, SectionNavigation.Bookmark scroll) {}

  private record Position(SectionNavigation.Bookmark scroll, String focus) {}

  private final FormCatalog definition;
  private final DocumentId document;
  private final Host host;
  private final Map<FieldPath, Position> positions = new LinkedHashMap<>();
  private FormCatalog.Snapshot projection;
  private FormCatalog.Navigation navigation;
  private FormModel model;
  private Language language;
  private UUID session;
  private long generation;
  private Component failure;
  private FormPanel list, crumbs;
  private FormEditBox search;
  private Button clear;
  private Minecraft client;
  private Object widgets, listWidgets, crumbWidgets;
  private String query = "";
  private int queryCursor, querySelection;
  private FieldPath displayedDetail;
  private boolean detailOpen, forceAll, mounted, dirty;
  private int listX, listY, listWidth, listHeight, crumbX, crumbY, crumbWidth;

  CatalogPane(FormCatalog definition, DocumentId document, Host host) {
    this.definition = Objects.requireNonNull(definition);
    this.document = Objects.requireNonNull(document);
    this.host = Objects.requireNonNull(host);
  }

  /** Return whether a projection/selection change needs the detail body rebuilt. */
  boolean project(FormModel model, UUID session, long generation) {
    var currentLanguage = Language.getInstance();
    if (this.model == model
        && this.language == currentLanguage
        && Objects.equals(this.session, session)
        && this.generation == generation) return false;
    var previous = selectedPath();
    var previousFailure = failure;
    boolean hadProjection = projection != null;
    if (!Objects.equals(this.session, session) || this.generation != generation) {
      navigation = null;
      positions.clear();
      displayedDetail = null;
    }
    this.model = model;
    this.session = session;
    this.generation = generation;
    dirty = true;
    language = currentLanguage;
    projection = null;
    failure = null;
    if (model != null) {
      try {
        projection = definition.snapshot(model).relabel(this::label);
        if (navigation == null) {
          navigation = new FormCatalog.Navigation(projection);
          definition.sections().stream()
              .findFirst()
              .ifPresent(section -> navigation.select(section.path()));
        } else navigation.update(projection);
      } catch (RuntimeException unavailable) {
        projection = null;
        failure =
            Component.literal(
                visible(
                    Objects.toString(
                        unavailable.getMessage(), "The complete catalog is unavailable"),
                    256));
      }
    }
    return hadProjection != (projection != null)
        || !previous.equals(selectedPath())
        || !Objects.equals(previousFailure, failure);
  }

  boolean languageChanged() {
    return language != Language.getInstance();
  }

  Optional<FormCatalog.Entry> selected() {
    return projection == null
        ? Optional.empty()
        : forceAll ? projection.entry(FieldPath.ROOT) : Optional.of(navigation.selected());
  }

  Optional<FieldPath> selectedPath() {
    return selected().map(FormCatalog.Entry::path);
  }

  FieldPath displayedDetail() {
    return displayedDetail;
  }

  Optional<FormCatalog.Entry> nearest(FieldPath path) {
    return projection == null ? Optional.empty() : Optional.of(projection.nearest(path));
  }

  boolean contains(FieldPath path) {
    return projection != null && projection.entry(path).isPresent();
  }

  Optional<FormModel.Node> parent() {
    return selected()
        .flatMap(FormCatalog.Entry::parent)
        .flatMap(path -> projection.entry(path))
        .flatMap(FormCatalog.Entry::node);
  }

  boolean select(FieldPath path) {
    if (projection == null || projection.entry(path).isEmpty()) return false;
    navigation.select(path);
    detailOpen = true;
    forceAll = false;
    return true;
  }

  boolean boundary(FieldPath path) {
    return !forceAll
        && projection != null
        && !navigation.selected().path().equals(path)
        && projection.entry(path).isPresent();
  }

  Component caption(FieldPath path) {
    return projection == null
        ? Component.empty()
        : projection
            .entry(path)
            .map(entry -> Component.literal(visible(entry.label(), 160)))
            .orElse(Component.empty());
  }

  Component unavailable() {
    return failure == null
        ? text("catalog_inactive", "This section is absent or inactive in the current draft.")
        : text("catalog_unavailable", "Catalog unavailable: ").copy().append(failure);
  }

  Optional<Component> help() {
    if (projection == null) return Optional.empty();
    return projection.breadcrumbs(selected().orElseThrow().path()).reversed().stream()
        .flatMap(entry -> entry.section().stream())
        .filter(section -> !section.help().isBlank())
        .findFirst()
        .map(
            section ->
                Component.translatableWithFallback(
                    section.translationKey() + ".help", section.help()));
  }

  void remember(FormPanel panel) {
    if (panel == null || displayedDetail == null) return;
    positions.remove(displayedDetail);
    positions.put(displayedDetail, new Position(panel.bookmark(), panel.focusKey()));
    while (positions.size() > definition.limits().maximumHistoryEntries())
      positions.remove(positions.keySet().iterator().next());
  }

  void restore(FormPanel panel) {
    var position = positions.get(displayedDetail);
    if (position == null) return;
    panel.restore(position.scroll());
    if (position.focus() != null) {
      host.focus(panel);
      panel.restoreFocus(position.focus());
    }
  }

  Layout init(Minecraft client, int x, int top, int width, int bottom) {
    this.client = client;
    if (search != null) {
      queryCursor = search.getCursorPosition();
      querySelection = search.selection();
    }
    boolean restoreSearch = host.focused() == search && search != null;
    widgets = new Object();
    mounted = true;
    dirty = false;
    list = null;
    crumbs = null;
    search = null;
    clear = null;
    displayedDetail = null;
    if (model == null) return new Layout(x, top, width, Math.max(1, bottom - top), false, true);
    if (projection == null || forceAll) {
      crumbX = x;
      crumbY = top;
      crumbWidth = width;
      buildCrumbs();
      displayedDetail = forceAll ? FieldPath.ROOT : null;
      return new Layout(x, top + 36, width, Math.max(1, bottom - top - 36), false, forceAll);
    }
    int minimumList =
        Math.max(112, client.font.width(text("catalog_search", "Search catalog")) + 20);
    int minimumDetail = Math.max(180, client.font.width(text("edit_field", "Edit…")) + 32);
    var columns =
        FormGeometry.columns(
            width, minimumList, minimumDetail, Math.max(112, Math.min(200, width / 3)), 8);
    boolean wide = columns.isPresent();
    boolean listing = wide || !detailOpen;
    int detailX = wide ? x + columns.orElseThrow().secondX() : x;
    int detailWidth = wide ? columns.orElseThrow().secondWidth() : width;
    if (listing) {
      listX = x;
      listY = top + 24;
      listWidth = wide ? columns.orElseThrow().firstWidth() : width;
      listHeight = Math.max(1, bottom - listY);
      var token = widgets;
      search =
          new FormEditBox(
              client.font,
              listX,
              top,
              Math.max(1, listWidth - 24),
              20,
              text("catalog_search", "Search catalog"));
      search.setMaxLength(definition.limits().maximumQueryCharacters());
      search.setHint(text("catalog_search", "Search catalog"));
      search.setValue(query);
      search.setCursorPosition(Math.min(queryCursor, query.length()));
      search.setHighlightPos(Math.min(querySelection, query.length()));
      search.setResponder(
          value -> {
            if (!host.active() || widgets != token) return;
            query = value;
            rebuildList(true);
          });
      host.add(search);
      clear =
          Button.builder(
                  Component.literal("×"),
                  ignored -> {
                    if (host.active() && widgets == token) search.setValue("");
                  })
              .bounds(listX + listWidth - 20, top, 20, 20)
              .createNarration(ignored -> text("catalog_clear", "Clear catalog filter").copy())
              .build();
      clear.setTooltip(Tooltip.create(text("catalog_clear", "Clear catalog filter")));
      host.add(clear);
      rebuildList(false);
      if (restoreSearch) host.focus(search);
    }
    if (!wide && listing) return new Layout(x, top, width, Math.max(1, bottom - top), true, false);
    crumbX = detailX;
    crumbY = top;
    crumbWidth = detailWidth;
    buildCrumbs();
    displayedDetail = navigation.selected().path();
    return new Layout(detailX, top + 36, detailWidth, Math.max(1, bottom - top - 36), false, false);
  }

  void refresh() {
    if (!dirty || !mounted || !host.active()) return;
    if (list != null) rebuildList(false);
    if (crumbs != null) buildCrumbs();
    dirty = false;
  }

  boolean focusSearch() {
    if (search == null) return false;
    host.focus(search);
    return true;
  }

  boolean openSearch() {
    if (focusSearch()) return true;
    if (projection == null
        || !host.change(
            () -> {
              detailOpen = false;
              forceAll = false;
            })) return false;
    return focusSearch();
  }

  void close() {
    mounted = false;
    widgets = listWidgets = crumbWidgets = null;
    projection = null;
    navigation = null;
    model = null;
    list = crumbs = null;
    search = null;
    clear = null;
    query = "";
    queryCursor = querySelection = 0;
    positions.clear();
  }

  Focus focus() {
    if (search != null && host.focused() == search) return new Focus(Area.SEARCH, null, null);
    if (list != null && host.focused() == list)
      return new Focus(Area.LIST, list.focusKey(), list.bookmark());
    if (crumbs != null && host.focused() == crumbs)
      return new Focus(Area.CRUMBS, crumbs.focusKey(), crumbs.bookmark());
    return new Focus(Area.OTHER, null, null);
  }

  void restore(Focus focus) {
    if (focus.area() == Area.SEARCH) focusSearch();
    var panel = focus.area() == Area.LIST ? list : focus.area() == Area.CRUMBS ? crumbs : null;
    if (panel != null) {
      panel.restore(focus.scroll());
      host.focus(panel);
      panel.restoreFocus(focus.key());
      if (panel.getFocused() == null) panel.focusVisibleControl();
    }
  }

  void preserveMode(boolean bodyFocused) {
    if (bodyFocused) detailOpen = true;
    else if (focus().area() == Area.SEARCH || focus().area() == Area.LIST) detailOpen = false;
  }

  boolean clearFocusedFilter() {
    if (search == null || host.focused() != search || query.isEmpty()) return false;
    search.setValue("");
    return true;
  }

  Component narration() {
    if (projection == null) return unavailable();
    var result =
        Component.literal(
            String.join(
                " / ",
                projection.breadcrumbs(selected().orElseThrow().path()).stream()
                    .map(FormCatalog.Entry::label)
                    .toList()));
    help().ifPresent(value -> result.append(". ").append(value));
    return selected().orElseThrow().available()
        ? result
        : result.append(". ").append(unavailable());
  }

  boolean history(boolean forward) {
    if (projection == null || !(forward ? navigation.canForward() : navigation.canBack()))
      return false;
    if (forward) navigation.forward();
    else navigation.back();
    detailOpen = true;
    forceAll = false;
    return true;
  }

  private void rebuildList(boolean filtering) {
    if (projection == null || search == null) return;
    float scroll = filtering || list == null ? 0 : list.scroll();
    String focus = list == null ? null : list.focusKey();
    boolean focused = list != null && host.focused() == list;
    if (list != null) host.remove(list);
    listWidgets = new Object();
    var token = listWidgets;
    list = new FormPanel(client, listX, listY, listWidth, listHeight, true);
    var matches = projection.search(query);
    list.heading(
        Component.translatableWithFallback(
            "reform.settings.catalog_count",
            "%s of %s entries",
            matches.size(),
            projection.entries().size()));
    if (matches.isEmpty())
      list.row(Component.empty(), text("catalog_no_matches", "No matching entries"));
    for (var entry : matches) {
      var button =
          FormPanel.navigationButton(
              Component.literal(visible(entry.label(), 160)),
              Math.max(1, listWidth - 20),
              entry.section().isPresent() || entry.path().equals(FieldPath.ROOT),
              entry.path().equals(navigation.selected().path()),
              () -> {
                if (host.active() && listWidgets == token) host.change(() -> select(entry.path()));
              });
      var path = projection.breadcrumbs(entry.path());
      String context =
          path.stream()
              .skip(Math.max(0, path.size() - 4))
              .map(value -> visible(value.label(), 80))
              .reduce((a, b) -> a + " / " + b)
              .orElse("");
      button.context(Component.literal(context));
      var tooltip = Component.literal(context);
      entry
          .section()
          .filter(section -> !section.help().isBlank())
          .ifPresent(
              section ->
                  tooltip
                      .append("\n")
                      .append(
                          Component.translatableWithFallback(
                              section.translationKey() + ".help", section.help())));
      if (!entry.available()) tooltip.append(". ").append(unavailable());
      button.setTooltip(Tooltip.create(tooltip));
      list.row(FormModel.key(entry.path()), Component.empty(), Component.empty(), button);
    }
    list.finish(scroll);
    host.add(list);
    if (focused) {
      host.focus(list);
      list.restoreFocus(focus);
      if (list.getFocused() == null) list.focusVisibleControl();
    }
    clear.active = !query.isEmpty();
  }

  private void buildCrumbs() {
    String focus = crumbs == null ? null : crumbs.focusKey();
    var scroll = crumbs == null ? null : crumbs.bookmark();
    boolean focused = crumbs != null && host.focused() == crumbs;
    if (crumbs != null) host.remove(crumbs);
    crumbWidgets = new Object();
    var token = crumbWidgets;
    crumbs = new FormPanel(client, crumbX, crumbY, crumbWidth, 32, false);
    var buttons = new java.util.ArrayList<AbstractWidget>();
    if (projection == null) {
      buttons.add(
          button(
              forceAll ? text("catalog", "Catalog") : text("catalog_all_fields", "Show all fields"),
              token,
              () -> forceAll = !forceAll));
    } else if (forceAll) {
      buttons.add(
          button(
              text("catalog", "Catalog"),
              token,
              () -> {
                detailOpen = false;
                forceAll = false;
              }));
      buttons.add(button(text("catalog_all", "All settings"), token, () -> select(FieldPath.ROOT)));
    } else {
      buttons.add(
          button(
              text("catalog", "Catalog"),
              token,
              () -> {
                detailOpen = false;
                forceAll = false;
              }));
      var back = button(text("catalog_back", "Back"), token, () -> history(false));
      back.active = navigation.canBack();
      buttons.add(back);
      var forward = button(text("catalog_forward", "Forward"), token, () -> history(true));
      forward.active = navigation.canForward();
      buttons.add(forward);
      for (var entry : projection.breadcrumbs(navigation.selected().path()))
        buttons.add(
            button(
                Component.literal(visible(entry.label(), 80)), token, () -> select(entry.path())));
    }
    crumbs.row(
        "catalog:breadcrumbs",
        Component.empty(),
        Component.empty(),
        buttons.toArray(AbstractWidget[]::new));
    crumbs.finish(0);
    host.add(crumbs);
    if (focused) {
      crumbs.restore(scroll);
      host.focus(crumbs);
      crumbs.restoreFocus(focus);
      if (crumbs.getFocused() == null) crumbs.focusVisibleControl();
    }
  }

  private Button button(Component label, Object token, Runnable change) {
    return Button.builder(
            label,
            ignored -> {
              if (host.active() && crumbWidgets == token) host.change(change);
            })
        .bounds(0, 0, Math.max(56, Math.min(160, client.font.width(label) + 20)), 20)
        .build();
  }

  private String label(FormCatalog.Entry entry) {
    if (entry.section().isPresent()) {
      var section = entry.section().orElseThrow();
      return Component.translatableWithFallback(section.translationKey(), section.label())
          .getString();
    }
    if (entry.caption().isPresent()) {
      var value = entry.caption().orElseThrow();
      return value.isBlank() ? text("catalog_blank", "(blank name)").getString() : value;
    }
    if (entry.path().equals(FieldPath.ROOT)) return text("catalog_all", "All settings").getString();
    var node = entry.node().orElseThrow();
    if (node.role() == FormModel.Role.ROW || node.role() == FormModel.Role.MAP_ROW)
      return Component.translatableWithFallback("reform.settings.entry", "Entry %s", node.name())
          .getString();
    if (node.role() == FormModel.Role.VARIANT)
      return Component.translatableWithFallback(
              FieldLabels.key(document, entry.path()), FieldLabels.readable(node.name()))
          .getString();
    return FieldLabels.input(document, entry.path(), Component.literal(entry.label())).getString();
  }

  static String visible(String value, int maximum) {
    var text = new StringBuilder();
    int examined = 0;
    for (int i = 0; i < value.length() && examined < maximum; examined++) {
      int cp = value.codePointAt(i);
      i += Character.charCount(cp);
      if (cp == '\n') text.append("\\n");
      else if (cp == '\r') text.append("\\r");
      else if (cp == '\t') text.append("\\t");
      else if (Character.isISOControl(cp)) text.append('?');
      else text.appendCodePoint(cp);
    }
    return text + (value.codePointCount(0, value.length()) > examined ? "…" : "");
  }

  private static Component text(String key, String fallback) {
    return Component.translatableWithFallback("reform.settings." + key, fallback);
  }
}
