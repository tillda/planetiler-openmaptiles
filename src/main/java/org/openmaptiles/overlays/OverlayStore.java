/*
xplatform fork addition (not part of upstream OpenMapTiles).

A GENERIC convention for plugging per-OSM-id derived data into the OpenMapTiles build, instead
of a bespoke command-line flag per data source. The xplatform "derive" library (DuckDB/Python
pipelines under server/derive/) computes small per-id tables — e.g. which tertiary ways are
"lonely" enough to promote onto the overview, which roads to hand-promote, a baked label zoom
per place node — and this build applies them uniformly.

Wire: one argument `--overlays=<manifest.json>` lists the overlays to apply this build. Each
overlay is a CSV table keyed by an OSM id, targeting one layer, and declares how to apply it:

  {
    "overlays": [
      { "layer": "transportation", "name": "loneliness", "path": "/abs/lonely.csv",
        "id_column": "osm_way_id",
        "apply": { "min_zoom": { "column": "min_zoom", "mode": "lower" }, "attrs": [] } }
    ]
  }

Two effect kinds, both optional per overlay:
  • min_zoom — override the feature's tile min-zoom from a column. mode "lower" = min(natural,
    value) (a promotion; the default), mode "set" = absolute override.
  • attrs   — attach the listed columns to the feature as attributes (values typed by content:
    long, else double, else string), so style filters can read them.

A layer self-wires by calling {@code OverlayStore.fromConfig(config).forLayer("<layer>")} in its
constructor — no change to the generated profile. Absent `--overlays` → an empty store (every
hook is a no-op), so a stock build is unaffected. A manifest that fails to load FAILS the build
(a requested overlay silently not applying would be a hidden-cartography trap).
*/
package org.openmaptiles.overlays;

import com.carrotsearch.hppc.LongIntHashMap;
import com.carrotsearch.hppc.LongObjectHashMap;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.onthegomap.planetiler.FeatureCollector;
import com.onthegomap.planetiler.config.PlanetilerConfig;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class OverlayStore {
  private static final Logger LOGGER = LoggerFactory.getLogger(OverlayStore.class);
  // Built once per manifest path and shared by every layer that asks for it.
  private static final Map<String, OverlayStore> CACHE = new ConcurrentHashMap<>();
  private static final OverlayStore EMPTY = new OverlayStore(Map.of());

  private final Map<String, LayerOverlays> byLayer;

  private OverlayStore(Map<String, LayerOverlays> byLayer) {
    this.byLayer = byLayer;
  }

  /** The overlay store for this build, from {@code --overlays=<manifest.json>}; cached + shared. */
  public static OverlayStore fromConfig(PlanetilerConfig config) {
    String manifest = config.arguments().getString(
      "overlays",
      "overlays: path to a JSON manifest of derived-data overlays to apply (xplatform fork)",
      ""
    );
    if (manifest == null || manifest.isBlank()) {
      return EMPTY;
    }
    return CACHE.computeIfAbsent(manifest, OverlayStore::load);
  }

  /** The (aggregated) overlay slice targeting {@code layer}; never null. */
  public LayerOverlays forLayer(String layer) {
    return byLayer.getOrDefault(layer, LayerOverlays.EMPTY);
  }

  private static OverlayStore load(String manifestPath) {
    try {
      JsonNode root = new ObjectMapper().readTree(Files.readString(Path.of(manifestPath)));
      Map<String, LayerBuilder> builders = new HashMap<>();
      for (JsonNode ov : root.path("overlays")) {
        String layer = req(ov, "layer", manifestPath);
        String path = req(ov, "path", manifestPath);
        String name = ov.path("name").asText(layer);
        String idCol = ov.path("id_column").asText("osm_way_id");
        JsonNode apply = ov.path("apply");
        JsonNode mz = apply.path("min_zoom");
        String mzCol = mz.isMissingNode() ? null : mz.path("column").asText(null);
        boolean mzLower = !"set".equals(mz.path("mode").asText("lower"));
        List<String> attrCols = new ArrayList<>();
        for (JsonNode a : apply.path("attrs")) {
          attrCols.add(a.asText());
        }
        builders.computeIfAbsent(layer, k -> new LayerBuilder())
          .ingest(name, Path.of(path), idCol, mzCol, mzLower, attrCols);
      }
      Map<String, LayerOverlays> byLayer = new HashMap<>();
      for (var e : builders.entrySet()) {
        byLayer.put(e.getKey(), e.getValue().build());
      }
      LOGGER.info("[overlays] loaded {} ({} layer(s))", manifestPath, byLayer.size());
      return new OverlayStore(byLayer);
    } catch (Exception ex) {
      throw new IllegalStateException("failed to load overlays manifest " + manifestPath, ex);
    }
  }

  private static String req(JsonNode ov, String field, String manifestPath) {
    String v = ov.path(field).asText(null);
    if (v == null || v.isBlank()) {
      throw new IllegalStateException("overlay in " + manifestPath + " missing required '" + field + "'");
    }
    return v;
  }

  // CSV value typing so style filters see numbers as numbers: long, else double, else string.
  static Object typed(String s) {
    if (s == null || s.isEmpty()) {
      return null;
    }
    try {
      return Long.parseLong(s);
    } catch (NumberFormatException ignored) {
      // fall through
    }
    try {
      return Double.parseDouble(s);
    } catch (NumberFormatException ignored) {
      return s;
    }
  }

  /** One attribute table: the column names + a sparse id→values map. */
  private record AttrTable(String[] cols, LongObjectHashMap<Object[]> rows) {}

  /** The aggregated overlay slice for one layer: a min-zoom map + zero or more attribute tables. */
  public static final class LayerOverlays {
    static final LayerOverlays EMPTY = new LayerOverlays(null, true, List.of());

    private final LongIntHashMap minZoom; // null → no min-zoom overlay for this layer
    private final boolean minZoomLower; // true → min(natural, value); false → absolute set
    private final List<AttrTable> attrTables;

    private LayerOverlays(LongIntHashMap minZoom, boolean minZoomLower, List<AttrTable> attrTables) {
      this.minZoom = minZoom;
      this.minZoomLower = minZoomLower;
      this.attrTables = attrTables;
    }

    /** The feature's tile min-zoom after any overlay override for {@code id}; else {@code natural}. */
    public int minZoom(long id, int natural) {
      if (minZoom == null || !minZoom.containsKey(id)) {
        return natural;
      }
      int v = minZoom.get(id);
      return minZoomLower ? Math.min(natural, v) : v;
    }

    /** Attach this layer's overlay attribute columns for {@code id} (if any) to {@code feature}. */
    public void applyAttrs(long id, FeatureCollector.Feature feature) {
      for (AttrTable t : attrTables) {
        Object[] row = t.rows().get(id);
        if (row != null) {
          for (int i = 0; i < t.cols().length; i++) {
            feature.setAttr(t.cols()[i], row[i]);
          }
        }
      }
    }
  }

  /** Accumulates one or more overlays that target the same layer into a single {@link LayerOverlays}. */
  private static final class LayerBuilder {
    private LongIntHashMap minZoom;
    private boolean minZoomLower = true;
    private boolean minZoomModeSeen = false;
    private final List<AttrTable> attrTables = new ArrayList<>();

    void ingest(String name, Path csv, String idCol, String mzCol, boolean mzLower, List<String> attrCols)
      throws Exception {
      List<String> lines = Files.readAllLines(csv);
      if (lines.isEmpty()) {
        LOGGER.warn("[overlays] {} is empty ({})", name, csv);
        return;
      }
      String[] header = lines.get(0).split(",", -1);
      int idIdx = indexOf(header, idCol);
      if (idIdx < 0) {
        throw new IllegalStateException("overlay " + name + ": no id column '" + idCol + "' in " + csv);
      }
      int mzIdx = mzCol == null ? -1 : indexOf(header, mzCol);
      if (mzCol != null && mzIdx < 0) {
        throw new IllegalStateException("overlay " + name + ": no min_zoom column '" + mzCol + "' in " + csv);
      }
      int[] attrIdx = new int[attrCols.size()];
      for (int i = 0; i < attrCols.size(); i++) {
        attrIdx[i] = indexOf(header, attrCols.get(i));
        if (attrIdx[i] < 0) {
          throw new IllegalStateException("overlay " + name + ": no attr column '" + attrCols.get(i) + "' in " + csv);
        }
      }

      if (mzIdx >= 0) {
        if (minZoom == null) {
          minZoom = new LongIntHashMap();
        }
        if (minZoomModeSeen && minZoomLower != mzLower) {
          LOGGER.warn("[overlays] {}: mixed min_zoom modes on one layer; keeping '{}'", name,
            minZoomLower ? "lower" : "set");
        } else {
          minZoomLower = mzLower;
          minZoomModeSeen = true;
        }
      }
      LongObjectHashMap<Object[]> attrRows = attrCols.isEmpty() ? null : new LongObjectHashMap<>();

      long rows = 0;
      for (int li = 1; li < lines.size(); li++) {
        String line = lines.get(li);
        if (line.isEmpty()) {
          continue;
        }
        String[] f = line.split(",", -1);
        long id = Long.parseLong(f[idIdx].trim());
        if (mzIdx >= 0) {
          int z = Integer.parseInt(f[mzIdx].trim());
          // Combine duplicate ids (e.g. a way both lonely and hand-promoted): keep the
          // most-promoting (lowest) when lowering, else last-wins.
          if (minZoom.containsKey(id) && minZoomLower) {
            minZoom.put(id, Math.min(minZoom.get(id), z));
          } else {
            minZoom.put(id, z);
          }
        }
        if (attrRows != null) {
          Object[] vals = new Object[attrIdx.length];
          for (int i = 0; i < attrIdx.length; i++) {
            vals[i] = typed(f[attrIdx[i]].trim());
          }
          attrRows.put(id, vals);
        }
        rows++;
      }
      if (attrRows != null) {
        attrTables.add(new AttrTable(attrCols.toArray(new String[0]), attrRows));
      }
      LOGGER.info("[overlays] {} → {} ({} rows{}{})", name, csv.getFileName(), rows,
        mzIdx >= 0 ? ", min_zoom=" + (mzLower ? "lower" : "set") : "",
        attrCols.isEmpty() ? "" : ", attrs=" + attrCols);
    }

    LayerOverlays build() {
      return new LayerOverlays(minZoom, minZoomLower, List.copyOf(attrTables));
    }

    private static int indexOf(String[] arr, String v) {
      for (int i = 0; i < arr.length; i++) {
        if (arr[i].trim().equals(v)) {
          return i;
        }
      }
      return -1;
    }
  }
}
