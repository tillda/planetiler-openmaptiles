/*
xplatform fork addition (not part of upstream OpenMapTiles).

A GENERIC convention for plugging per-OSM-id derived data into the OpenMapTiles build, instead
of a bespoke command-line flag per data source. The xplatform "derive" library (DuckDB/Python
pipelines under server/derive/) computes small per-id tables — e.g. which tertiary ways are
"lonely" enough to promote onto the overview, which roads to hand-promote, a baked label zoom
per place node — and this build applies them uniformly.

Wire: one argument `--derives=<manifest.json>` lists the derives to apply this build. Each
derive is a CSV table keyed by an OSM id, targeting one layer, and declares how to apply it:

  {
    "derives": [
      { "layer": "transportation", "name": "loneliness", "path": "/abs/lonely.csv",
        "id_column": "osm_way_id",
        "apply": { "min_zoom": { "column": "min_zoom", "mode": "lower" }, "attrs": [] } }
    ]
  }

Two effect kinds, both optional per derive:
  • min_zoom — override the feature's tile min-zoom from a column. mode "lower" = min(natural,
    value) (a promotion; the default), mode "set" = absolute override. An optional "added_attr"
    (lower mode only) names a flag attribute the fork stamps =1 only on ways whose NATURAL zoom
    the override actually lowered (value < natural) — i.e. the ones the promotion truly ADDED to a
    lower zoom, as opposed to ways it targeted that were already there. Computed here because only
    the build knows each way's natural OMT zoom.
  • attrs   — attach the listed columns to the feature as attributes (values typed by content:
    long, else double, else string), so style filters can read them.

A layer self-wires by calling {@code DeriveStore.fromConfig(config).forLayer("<layer>")} in its
constructor — no change to the generated profile. Absent `--derives` → an empty store (every
hook is a no-op), so a stock build is unaffected. A manifest that fails to load FAILS the build
(a requested derive silently not applying would be a hidden-cartography trap).
*/
package org.openmaptiles.derives;

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

public class DeriveStore {
  private static final Logger LOGGER = LoggerFactory.getLogger(DeriveStore.class);
  // Built once per manifest path and shared by every layer that asks for it.
  private static final Map<String, DeriveStore> CACHE = new ConcurrentHashMap<>();
  private static final DeriveStore EMPTY = new DeriveStore(Map.of());

  private final Map<String, LayerDerives> byLayer;

  private DeriveStore(Map<String, LayerDerives> byLayer) {
    this.byLayer = byLayer;
  }

  /** The derive store for this build, from {@code --derives=<manifest.json>}; cached + shared. */
  public static DeriveStore fromConfig(PlanetilerConfig config) {
    String manifest = config.arguments().getString(
      "derives",
      "derives: path to a JSON manifest of derived-data tables to apply (xplatform fork)",
      ""
    );
    if (manifest == null || manifest.isBlank()) {
      return EMPTY;
    }
    return CACHE.computeIfAbsent(manifest, DeriveStore::load);
  }

  /** The (aggregated) derive slice targeting {@code layer}; never null. */
  public LayerDerives forLayer(String layer) {
    return byLayer.getOrDefault(layer, LayerDerives.EMPTY);
  }

  private static DeriveStore load(String manifestPath) {
    try {
      JsonNode root = new ObjectMapper().readTree(Files.readString(Path.of(manifestPath)));
      Map<String, LayerBuilder> builders = new HashMap<>();
      for (JsonNode d : root.path("derives")) {
        String layer = req(d, "layer", manifestPath);
        String path = req(d, "path", manifestPath);
        String name = d.path("name").asText(layer);
        String idCol = d.path("id_column").asText("osm_way_id");
        JsonNode apply = d.path("apply");
        JsonNode mz = apply.path("min_zoom");
        String mzCol = mz.isMissingNode() ? null : mz.path("column").asText(null);
        boolean mzLower = !"set".equals(mz.path("mode").asText("lower"));
        String mzAdded = mz.isMissingNode() ? null : mz.path("added_attr").asText(null);
        List<String> attrCols = new ArrayList<>();
        for (JsonNode a : apply.path("attrs")) {
          attrCols.add(a.asText());
        }
        builders.computeIfAbsent(layer, k -> new LayerBuilder())
          .ingest(name, Path.of(path), idCol, mzCol, mzLower, mzAdded, attrCols);
      }
      Map<String, LayerDerives> byLayer = new HashMap<>();
      for (var e : builders.entrySet()) {
        byLayer.put(e.getKey(), e.getValue().build());
      }
      LOGGER.info("[derives] loaded {} ({} layer(s))", manifestPath, byLayer.size());
      return new DeriveStore(byLayer);
    } catch (Exception ex) {
      throw new IllegalStateException("failed to load derives manifest " + manifestPath, ex);
    }
  }

  private static String req(JsonNode d, String field, String manifestPath) {
    String v = d.path(field).asText(null);
    if (v == null || v.isBlank()) {
      throw new IllegalStateException("derive in " + manifestPath + " missing required '" + field + "'");
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

  /** The aggregated derive slice for one layer: a min-zoom map + zero or more attribute tables. */
  public static final class LayerDerives {
    static final LayerDerives EMPTY = new LayerDerives(null, true, null, null, List.of());

    private final LongIntHashMap minZoom; // null → no min-zoom derive for this layer
    private final boolean minZoomLower; // true → min(natural, value); false → absolute set
    // The per-id promote zoom of ONLY the derive(s) that opted into an "added" flag (lower mode),
    // kept separate from the aggregated minZoom so the flag tracks that derive — not any other
    // min-zoom derive on the same layer. null → no derive on this layer requested an added flag.
    private final LongIntHashMap addedZoom;
    private final String addedAttr; // the flag attribute name to stamp; null with addedZoom == null
    private final List<AttrTable> attrTables;

    private LayerDerives(LongIntHashMap minZoom, boolean minZoomLower, LongIntHashMap addedZoom,
      String addedAttr, List<AttrTable> attrTables) {
      this.minZoom = minZoom;
      this.minZoomLower = minZoomLower;
      this.addedZoom = addedZoom;
      this.addedAttr = addedAttr;
      this.attrTables = attrTables;
    }

    /** The feature's tile min-zoom after any derive override for {@code id}; else {@code natural}. */
    public int minZoom(long id, int natural) {
      if (minZoom == null || !minZoom.containsKey(id)) {
        return natural;
      }
      int v = minZoom.get(id);
      return minZoomLower ? Math.min(natural, v) : v;
    }

    /** Attach this layer's derive attribute columns for {@code id} (if any) to {@code feature}. */
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

    /**
     * If a min-zoom derive that opted into an "added" flag actually pulled {@code id} earlier than
     * its {@code natural} zoom, stamp that flag (=1) on {@code feature}. Lets a style show the ways
     * a promotion ADDED to a low zoom, as distinct from ways it targeted that were already there.
     * No-op when no derive on this layer requested an added flag.
     */
    public void applyMinZoomAdded(long id, int natural, FeatureCollector.Feature feature) {
      if (addedAttr == null || addedZoom == null || !addedZoom.containsKey(id)) {
        return;
      }
      if (addedZoom.get(id) < natural) {
        feature.setAttr(addedAttr, 1);
      }
    }
  }

  /** Accumulates one or more derives that target the same layer into a single {@link LayerDerives}. */
  private static final class LayerBuilder {
    private LongIntHashMap minZoom;
    private boolean minZoomLower = true;
    private boolean minZoomModeSeen = false;
    private LongIntHashMap addedZoom; // per-id promote zoom of the derive that opted into an added flag
    private String addedAttr;
    private final List<AttrTable> attrTables = new ArrayList<>();

    void ingest(String name, Path csv, String idCol, String mzCol, boolean mzLower, String mzAdded,
      List<String> attrCols) throws Exception {
      List<String> lines = Files.readAllLines(csv);
      if (lines.isEmpty()) {
        LOGGER.warn("[derives] {} is empty ({})", name, csv);
        return;
      }
      String[] header = lines.get(0).split(",", -1);
      int idIdx = indexOf(header, idCol);
      if (idIdx < 0) {
        throw new IllegalStateException("derive " + name + ": no id column '" + idCol + "' in " + csv);
      }
      int mzIdx = mzCol == null ? -1 : indexOf(header, mzCol);
      if (mzCol != null && mzIdx < 0) {
        throw new IllegalStateException("derive " + name + ": no min_zoom column '" + mzCol + "' in " + csv);
      }
      int[] attrIdx = new int[attrCols.size()];
      for (int i = 0; i < attrCols.size(); i++) {
        attrIdx[i] = indexOf(header, attrCols.get(i));
        if (attrIdx[i] < 0) {
          throw new IllegalStateException("derive " + name + ": no attr column '" + attrCols.get(i) + "' in " + csv);
        }
      }

      boolean wantsAdded = mzIdx >= 0 && mzAdded != null && !mzAdded.isBlank() && mzLower;
      if (mzIdx >= 0) {
        if (minZoom == null) {
          minZoom = new LongIntHashMap();
        }
        if (minZoomModeSeen && minZoomLower != mzLower) {
          LOGGER.warn("[derives] {}: mixed min_zoom modes on one layer; keeping '{}'", name,
            minZoomLower ? "lower" : "set");
        } else {
          minZoomLower = mzLower;
          minZoomModeSeen = true;
        }
      }
      if (wantsAdded) {
        if (addedZoom == null) {
          addedZoom = new LongIntHashMap();
        }
        addedAttr = mzAdded;
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
          if (wantsAdded) {
            addedZoom.put(id, addedZoom.containsKey(id) ? Math.min(addedZoom.get(id), z) : z);
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
      LOGGER.info("[derives] {} → {} ({} rows{}{}{})", name, csv.getFileName(), rows,
        mzIdx >= 0 ? ", min_zoom=" + (mzLower ? "lower" : "set") : "",
        wantsAdded ? ", added_attr=" + mzAdded : "",
        attrCols.isEmpty() ? "" : ", attrs=" + attrCols);
    }

    LayerDerives build() {
      return new LayerDerives(minZoom, minZoomLower, addedZoom, addedAttr, List.copyOf(attrTables));
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
