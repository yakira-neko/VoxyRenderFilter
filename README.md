# VoxyRenderFilter

Shows the voxy LOD cache coverage on an in-game map, renders only selected regions and deletes region LOD cache.

## Features

### Cache Map
- Real terrain map generated from the on-disk voxy LOD cache (all levels, lvl0–lvl4), visible even far from the player
- Coverage overlays: pale = disk-cached regions, green = currently rendering columns with usable data, orange = filtered/blocked regions
- Translucent player arrow showing your position and facing
- Chunk grid drawn inside selections at high zoom
- Top-right button bar: clear filter / delete cache / clear selection / rescan / center / overlay toggle
- Overlay toggle hides the pale/green/orange overlays to view the raw terrain
- Auto re-scan detects newly cached columns (every ~2s) — no manual refresh required

### Region Selection
- Left-drag to box-select; **right-drag** subtracts from the existing selection; **Ctrl** toggles multi-select; hold **Shift** for square selection
- Selection snaps to region files (32x32 chunks) at low zoom and to lvl0 sections (2x2 chunks) at high zoom
- Right-click a selection to open the operation menu; right-click empty space to clear
- Scroll to zoom (anchored at cursor); **WASD** / arrow keys to pan; **R** rescan; **H** center on player; **C** clear selection
- Selection coordinates and size (blocks/chunks/sections/regions) shown live on the map

### Render Filter
- Block rendering in the selected regions, render only the selected regions, invert, or unblock
- Takes effect immediately — no need to disable and re-enable voxy
- Fully blocked columns are removed from the render ring; partially blocked sections render as empty holes
- Invert is confined to the voxy render distance
- Filter state (mode, rect, blocked count) is shown at the top-left of the HUD while active

### Cache Deletion
- Delete the LOD cache of all levels (lvl0–lvl4) inside the selection; coarse LOD sections cover the selected area too and must be removed, otherwise the deleted data lingers
- Render nodes are refreshed immediately and rebuilt from the current disk state
- Loaded neighbors that are accidentally purged are re-ingested from the live world

### Commands
- `/voxyrenderfilter purge <x1> <z1> <x2> <z2>` — delete LOD cache inside a block-coordinate rectangle
- `/voxyrenderfilter filter rect <x1> <z1> <x2> <z2>` — set the render filter rectangle
- `/voxyrenderfilter filter clear` — clear the filter
- `/voxyrenderfilter filter status` — show the current filter state

## Supported Versions

| Minecraft | Loader | Java |
|-----------|--------|------|
| 26.1      | Fabric | 25+  |

## Usage

1. Bind a key under **Controls → VoxyRenderFilter** (unbound by default), or use the commands above
2. Open the map and drag to select a region
3. Right-click the selection for **Block / Only-render / Invert / Delete cache / Clear**
4. Press **Del** to delete the cache of the selection, **Enter** to block rendering of it

## Dependencies

- [Fabric API](https://modrinth.com/mod/fabric-api) (required)
- [voxy](https://modrinth.com/mod/voxy) (required)

## License

[LGPL-3.0](LICENSE)
