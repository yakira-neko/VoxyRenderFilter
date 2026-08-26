package dev.whisperlyric.voxyrenderfilter.map;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.GpuDevice;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.AddressMode;
import com.mojang.blaze3d.textures.FilterMode;
import com.mojang.blaze3d.textures.GpuSampler;
import dev.whisperlyric.voxyrenderfilter.tracker.ActiveTopLevelTracker;
import dev.whisperlyric.voxyrenderfilter.util.VoxyAccess;
import it.unimi.dsi.fastutil.ints.Int2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongIterator;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import me.cortex.voxy.common.config.storage.StorageBackend;
import me.cortex.voxy.common.util.MemoryBuffer;
import me.cortex.voxy.common.util.ThreadLocalMemoryBuffer;
import me.cortex.voxy.common.world.SaveLoadSystem3;
import me.cortex.voxy.common.world.WorldEngine;
import me.cortex.voxy.common.world.WorldSection;
import me.cortex.voxy.common.world.other.Mapper;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.OptionalDouble;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Generates the real terrain map from the on-disk voxy LOD cache: one 512x512 texture per TLN
 * column. A background thread reads every LOD level (lvl0-4) and fills the topmost non-air block
 * per voxel column (lvl0 first, coarse LOD only patches pixels left empty); the render thread
 * uploads finished textures and blits them. Only visible columns are generated, capped with an LRU
 * limit; invalidate() rebuilds everything when the world engine changes or the cache is deleted.
 */
public final class LodMapRenderer {

    public static final LodMapRenderer INSTANCE = new LodMapRenderer();

    private static final int COLUMN_PX = 512; // 1 TLN column = 512x512 blocks -> pixels
    private static final int SECTION_PX = 32; // 1 lvl0 section = 32x32 blocks -> pixels
    private static final int SECTION_VOL = 32 * 32 * 32;
    private static final int MAX_TEXTURES = 192; // upload cap (each 512x512 RGBA texture = 1MB)
    private static final int MAX_PENDING = 96;
    /** Max generation radius per request (in columns); prevents an infinite generate-evict loop when many columns are visible at low zoom. */
    private static final int GEN_RADIUS_MAX = 5;
    private static final int MAX_UPLOADS_PER_FRAME = 4;
    private static final int BIGGEST_SERIALIZED_SECTION_SIZE = SECTION_VOL * 8 * 2 + 8;
    /** Interval between on-disk new-column checks (in frames, ~2s at 60fps). */
    private static final int STALE_CHECK_INTERVAL = 40;
    /** Height shadow strength: brightness factor for peaks/dips vs. neighbors (FTB-Chunks style). */
    private static final float SHADOW_STRENGTH = 0.3F;

    /** A column's terrain texture, generated (or being generated). */
    private static final class Column {
        final long packed;
        final int colX;
        final int colZ;
        volatile NativeImage image;      // filled by the background thread
        volatile DynamicTexture texture; // uploaded by the render thread
        volatile boolean uploaded;
        long lastUsed;

        Column(long packed) {
            this.packed = packed;
            this.colX = (int) (packed >> 32);
            this.colZ = (int) packed;
        }
    }

    private final ConcurrentHashMap<Long, Column> columns = new ConcurrentHashMap<>();
    private final ConcurrentLinkedQueue<Column> uploads = new ConcurrentLinkedQueue<>();
    private final Object lock = new Object();
    private final LongOpenHashSet pendingSet = new LongOpenHashSet();
    /** Columns that failed to generate: never retried this session (cleared by invalidate on disk change), so a bad column cannot block the queue. */
    private final LongOpenHashSet failedColumns = new LongOpenHashSet();
    private final java.util.ArrayDeque<Long> pendingQueue = new java.util.ArrayDeque<>();
    private final ThreadLocalMemoryBuffer scratchBuffer =
            new ThreadLocalMemoryBuffer(BIGGEST_SERIALIZED_SECTION_SIZE + 1024);

    private volatile WorldEngine engine;
    private volatile Long2ObjectOpenHashMap<LongArrayList> secKeys; // packed(colX,colZ) -> keys of all LOD levels in that column
    /** Full disk rescan requested but not yet run (set by the render thread, executed by the worker thread to avoid blocking rendering). */
    private volatile boolean rescanRequested;
    /** Frame number of the last on-disk new-column check (every 40 frames ~ 2s). */
    private long lastStaleCheckTick;
    private volatile boolean closed;
    private Thread worker;
    private GpuSampler sampler;
    private long tick;

    private LodMapRenderer() {
    }

    /** Whether the column already has a drawable terrain texture (read by the render thread). */
    public boolean hasUploaded(int colX, int colZ) {
        Column c = this.columns.get(pack(colX, colZ));
        return c != null && c.uploaded;
    }

    /** Whether columns are still being generated (pending queue or uploads not empty). */
    public boolean isGenerating() {
        synchronized (this.lock) {
            return !this.pendingQueue.isEmpty() || !this.uploads.isEmpty();
        }
    }

    /** Generation stats: {generated columns, queued, awaiting upload, failed}. Called by the render thread. */
    public int[] stats() {
        synchronized (this.lock) {
            return new int[] {this.columns.size(), this.pendingSet.size(),
                    this.uploads.size(), this.failedColumns.size()};
        }
    }

    /** Clears all terrain cache (after world switch / cache deletion). Must be called on the render thread. */
    public void invalidate() {
        synchronized (this.lock) {
            this.engine = null;
            this.secKeys = null;
            this.pendingSet.clear();
            this.pendingQueue.clear();
            this.failedColumns.clear();
            this.lock.notifyAll();
        }
        this.uploads.clear();
        for (Column c : this.columns.values()) {
            if (c.texture != null) {
                c.texture.close();
            }
            c.uploaded = false;
        }
        this.columns.clear();
    }

    /**
     * Called every frame (render thread): requests generation of visible columns, uploads and draws.
     *
     * @param guiWidth/ guiHeight map screen dimensions
     */
    public void update(GuiGraphicsExtractor extractor, WorldEngine engine,
                       double centerTlnX, double centerTlnZ, int zoom, int guiWidth, int guiHeight) {
        if (engine == null) {
            return;
        }
        if (this.engine != engine) {
            this.invalidate();
            this.engine = engine;
            this.secKeys = this.scanKeys(engine); // full-LOD disk index, data source for requests
        }
        // Detect new disk columns: a ring column outside the last scan triggers a background rescan,
        // so freshly generated/re-ingested regions show up without manual cache deletion
        if (++this.tick - this.lastStaleCheckTick >= STALE_CHECK_INTERVAL) {
            this.lastStaleCheckTick = this.tick;
            if (this.hasColumnsMissingFromScan()) {
                this.rescanRequested = true;
                synchronized (this.lock) {
                    this.lock.notifyAll();
                }
            }
        }
        this.processUploads();
        // Visible TLN column range (includes a 1-column margin)
        int halfW = guiWidth / 2;
        int halfH = guiHeight / 2;
        int centerColX = (int) Math.floor(centerTlnX);
        int centerColZ = (int) Math.floor(centerTlnZ);
        this.enqueueVisible(centerColX, centerColZ, halfW, halfH, zoom);
        this.startWorkerIfNeeded();
        this.draw(extractor, centerTlnX, centerTlnZ, zoom, guiWidth, guiHeight);
    }

    /** Enqueues visible TLN columns near the center, generation radius capped to fit the texture budget. */
    private void enqueueVisible(int centerColX, int centerColZ, int halfW, int halfH, int zoom) {
        Long2ObjectOpenHashMap<LongArrayList> secs = this.secKeys;
        if (secs == null) {
            return; // all-level index not built yet (engine just switched); retry next frame
        }
        int visHalfX = (int) Math.ceil(halfW / (double) zoom);
        int visHalfZ = (int) Math.ceil(halfH / (double) zoom);
        int genHalfX = Math.min(GEN_RADIUS_MAX, visHalfX);
        int genHalfZ = Math.min(GEN_RADIUS_MAX, visHalfZ);
        List<Long> visible = new ArrayList<>();
        for (int cx = centerColX - genHalfX; cx <= centerColX + genHalfX; cx++) {
            for (int cz = centerColZ - genHalfZ; cz <= centerColZ + genHalfZ; cz++) {
                long packed = pack(cx, cz);
                // Any LOD level (lvl0-4) having data is enough to generate: distant columns may only have coarse LOD
                if (!secs.containsKey(packed)) {
                    continue;
                }
                if (this.columns.containsKey(packed) || this.pendingSet.contains(packed)) {
                    continue;
                }
                synchronized (this.lock) {
                    if (this.failedColumns.contains(packed)) {
                        continue;
                    }
                }
                visible.add(packed);
            }
        }
        visible.sort(Comparator.comparingLong(a -> distSq(a, centerColX, centerColZ)));
        // VRAM budget: generated + queued columns must not exceed MAX_TEXTURES; the rest is requested next frame
        int budget = Math.max(0, MAX_TEXTURES - this.columns.size() - this.pendingSet.size());
        synchronized (this.lock) {
            for (long packed : visible) {
                if (this.pendingSet.size() >= MAX_PENDING || this.pendingSet.size() >= budget) {
                    break;
                }
                this.pendingSet.add(packed);
                this.pendingQueue.addLast(packed);
            }
            if (!visible.isEmpty()) {
                this.lock.notifyAll();
            }
        }
    }

    private static long distSq(long packed, int cx, int cz) {
        long dx = (packed >> 32) - cx;
        long dz = (int) packed - cz;
        return dx * dx + dz * dz;
    }

    /** Maps every stored section key to its TLN column; coarse-only columns are included so distant areas render. */
    private Long2ObjectOpenHashMap<LongArrayList> scanKeys(WorldEngine eng) {
        final Long2ObjectOpenHashMap<LongArrayList> scan = new Long2ObjectOpenHashMap<>();
        eng.storage.iteratePositions(-1, key -> {
            int lvl = WorldEngine.getLevel(key);
            int colX = WorldEngine.getX(key) >> (4 - lvl);
            int colZ = WorldEngine.getZ(key) >> (4 - lvl);
            scan.computeIfAbsent(pack(colX, colZ), k -> new LongArrayList()).add(key);
        });
        return scan;
    }

    private void startWorkerIfNeeded() {
        if (this.worker != null && this.worker.isAlive()) {
            return;
        }
        this.worker = new Thread(this::workerLoop, "VRF lod map");
        this.worker.setDaemon(true);
        this.worker.start();
    }

    private void workerLoop() {
        while (!this.closed) {
            this.performRescanIfRequested();
            Long packed = this.nextPending();
            if (packed == null) {
                return;
            }
            try {
                this.generateColumn(packed);
            } catch (Throwable t) {
                // Disk/engine released mid-read etc.: skip the column and keep the thread alive
                synchronized (this.lock) {
                    this.failedColumns.add(packed.longValue());
                }
            }
        }
    }

    /** Executes the full disk rescan on the worker thread (replaces the secKeys reference), avoiding a blocking scan on the render thread. */
    private void performRescanIfRequested() {
        if (!this.rescanRequested) {
            return;
        }
        this.rescanRequested = false;
        WorldEngine eng = this.engine;
        if (eng == null) {
            return;
        }
        this.secKeys = this.scanKeys(eng);
    }

    /** Whether any ring column (actually created by voxy) is missing from the disk scan, i.e. the disk has new data. */
    private boolean hasColumnsMissingFromScan() {
        Long2ObjectOpenHashMap<LongArrayList> secs = this.secKeys;
        if (secs == null) {
            return false;
        }
        LongOpenHashSet active = ActiveTopLevelTracker.INSTANCE.getColumns();
        if (active.isEmpty()) {
            return false;
        }
        LongIterator it = active.iterator();
        while (it.hasNext()) {
            if (!secs.containsKey(it.nextLong())) {
                return true;
            }
        }
        return false;
    }

    private Long nextPending() {
        synchronized (this.lock) {
            while (this.pendingQueue.isEmpty()) {
                if (this.closed) {
                    return null;
                }
                try {
                    this.lock.wait();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return null;
                }
            }
            long packed = this.pendingQueue.removeFirst();
            this.pendingSet.remove(packed);
            return packed;
        }
    }

    private void generateColumn(long packed) {
        WorldEngine eng = this.engine;
        if (eng == null) {
            return;
        }
        StorageBackend backend = VoxyAccess.getStorageBackend(eng);
        if (backend == null) {
            return;
        }
        Long2ObjectOpenHashMap<LongArrayList> secs = this.secKeys;
        if (secs == null) {
            return; // full-level index not built yet, try next frame
        }
        int colX = (int) (packed >> 32);
        int colZ = (int) packed;
        LongArrayList list = secs.get(packed);
        if (list == null || list.isEmpty()) {
            return;
        }
        List<Long> keys = new ArrayList<>(list);
        // Fine levels first (coarse LOD only patches pixels left empty), high y first
        keys.sort((a, b) -> {
            int la = WorldEngine.getLevel(a);
            int lb = WorldEngine.getLevel(b);
            if (la != lb) {
                return Integer.compare(la, lb);
            }
            return Integer.compare(WorldEngine.getY(b), WorldEngine.getY(a));
        });
        // In-column lvl0 sections (local index 0-15). If the column has fine data, coarse LOD only
        // patches inside these sections so deleted lvl0 areas stay blank (no leftovers on the map)
        LongOpenHashSet lvl0Local = null;
        for (long key : keys) {
            if (WorldEngine.getLevel(key) == 0) {
                if (lvl0Local == null) {
                    lvl0Local = new LongOpenHashSet();
                }
                int lx = WorldEngine.getX(key) - (colX << 4);
                int lz = WorldEngine.getZ(key) - (colZ << 4);
                lvl0Local.add(((long) lx << 32) | (lz & 0xFFFFFFFFL));
            }
        }

        NativeImage img = new NativeImage(COLUMN_PX, COLUMN_PX, true);
        int[] colors = new int[COLUMN_PX * COLUMN_PX];       // base ARGB color, 0 = no data
        short[] heights = new short[COLUMN_PX * COLUMN_PX];  // world height of top block, 0 = no data
        Int2IntOpenHashMap colorCache = new Int2IntOpenHashMap();
        colorCache.defaultReturnValue(-1);
        for (long key : keys) {
            if (this.engine != eng) {
                img.close();
                return; // engine switched, discard
            }
            int lvl = WorldEngine.getLevel(key);
            // Must use the real lvl in the section key, and a fresh scratch reference per call
            // (the backend subSize()s the passed buffer; reusing one double-frees, see loadSection)
            WorldSection sec = WorldSection._createRawUntrackedUnsafeSection(
                    lvl, WorldEngine.getX(key), WorldEngine.getY(key), WorldEngine.getZ(key));
            MemoryBuffer data = backend.getSectionData(key,
                    this.scratchBuffer.get().createUntrackedUnfreeableReference());
            if (data == null) {
                continue;
            }
            if (!SaveLoadSystem3.deserialize(sec, data)) {
                continue;
            }
            // In-column section index (0..16>>lvl-1) * section edge, avoids int overflow
            int secMask = (1 << (4 - lvl)) - 1;
            int px0 = (WorldEngine.getX(key) & secMask) << (5 + lvl);
            int pz0 = (WorldEngine.getZ(key) & secMask) << (5 + lvl);
            fillSection(eng, colors, heights, colorCache, sec._unsafeGetRawDataArray(),
                    lvl, px0, pz0, WorldEngine.getY(key), lvl0Local);
        }
        applyShadows(img, colors, heights);
        Column col = new Column(packed);
        col.image = img;
        this.columns.put(packed, col);
        this.uploads.add(col);
    }

    /**
     * Writes a section into the column texture: covers (32<<lvl)^2 pixels, one voxel = 2^lvl blocks.
     * Picks the top non-air block per voxel column; coarse LOD only patches pixels left unfilled.
     * With lvl0Local, coarse LOD skips pixels outside the column's lvl0 sections (deleted area).
     */
    private void fillSection(WorldEngine eng, int[] colors, short[] heights, Int2IntOpenHashMap colorCache,
                             long[] data, int lvl, int px0, int pz0, int worldYBase, LongOpenHashSet lvl0Local) {
        int voxPx = 1 << lvl;               // pixels (blocks) covered by one voxel
        int worldYScale = SECTION_PX << lvl; // blocks per section slot
        for (int vz = 0; vz < SECTION_PX; vz++) {
            int pz = pz0 + (vz << lvl);
            int lz = pz >> 5; // in-column lvl0 section index of this voxel row (px0 is a 32<<lvl multiple)
            for (int vx = 0; vx < SECTION_PX; vx++) {
                int px = px0 + (vx << lvl);
                if (lvl0Local != null) {
                    int lx = px >> 5;
                    if (!lvl0Local.contains(((long) lx << 32) | (lz & 0xFFFFFFFFL))) {
                        continue; // that lvl0 section was deleted: no fine data, and no coarse patch either
                    }
                }
                long topId = 0;
                int topY = -1;
                for (int y = SECTION_PX - 1; y >= 0; y--) {
                    long id = data[WorldSection.getIndex(vx, y, vz)];
                    if (!Mapper.isAir(id)) {
                        topId = id;
                        topY = y;
                        break;
                    }
                }
                if (topId == 0) {
                    continue;
                }
                int blockId = Mapper.getBlockId(topId);
                int color = colorCache.get(blockId);
                if (color == -1) {
                    BlockState state = eng.getMapper().getBlockStateFromBlockId(blockId);
                    color = BlockColors.argbFor(state);
                    colorCache.put(blockId, color);
                }
                // World height of the voxel bottom; a coarse voxel is 2^lvl blocks tall, use the bottom as an approximation
                int height = worldYBase * worldYScale + topY * voxPx;
                if (height > Short.MAX_VALUE) {
                    height = Short.MAX_VALUE; // clamp to prevent short overflow at extreme coordinates
                }
                for (int dz = 0; dz < voxPx; dz++) {
                    int gz = pz + dz;
                    int rowBase = gz * COLUMN_PX + px;
                    for (int dx = 0; dx < voxPx; dx++) {
                        int pIdx = rowBase + dx;
                        if (colors[pIdx] != 0) {
                            continue;
                        }
                        colors[pIdx] = color;
                        heights[pIdx] = (short) height;
                    }
                }
            }
        }
    }

    /**
     * Height shading (FTB-Chunks style): each pixel compares its height with the north (above) and
     * west (left) neighbors; peaks brighten, dips darken. Edge pixels use their own height.
     */
    private void applyShadows(NativeImage img, int[] colors, short[] heights) {
        for (int pz = 0; pz < COLUMN_PX; pz++) {
            for (int px = 0; px < COLUMN_PX; px++) {
                int idx = pz * COLUMN_PX + px;
                int base = colors[idx];
                if (base == 0) {
                    continue;
                }
                int h = heights[idx];
                int hn = (pz == 0) ? h : heights[(pz - 1) * COLUMN_PX + px];
                int hw = (px == 0) ? h : heights[pz * COLUMN_PX + (px - 1)];
                float factor = 1F;
                if (h > hn || h > hw) {
                    factor += SHADOW_STRENGTH;
                }
                if (h < hn || h < hw) {
                    factor -= SHADOW_STRENGTH;
                }
                if (factor != 1F) {
                    int r = Math.min(255, (int) (((base >> 16) & 0xFF) * factor));
                    int g = Math.min(255, (int) (((base >> 8) & 0xFF) * factor));
                    int b = Math.min(255, (int) ((base & 0xFF) * factor));
                    base = 0xFF000000 | (r << 16) | (g << 8) | b;
                }
                img.setPixelABGR(px, pz, BlockColors.abgr(base));
            }
        }
    }

    private void processUploads() {
        int n = 0;
        Column c;
        while (n < MAX_UPLOADS_PER_FRAME && (c = this.uploads.poll()) != null) {
            n++;
            final Column cur = c;
            DynamicTexture tex = new DynamicTexture(
                    () -> "voxyrenderfilter-map-" + cur.colX + "_" + cur.colZ, cur.image);
            tex.upload();
            cur.texture = tex;
            cur.uploaded = true;
            cur.lastUsed = ++this.tick;
        }
    }

    private void draw(GuiGraphicsExtractor extractor, double centerTlnX, double centerTlnZ,
                      int zoom, int guiWidth, int guiHeight) {
        if (this.columns.isEmpty()) {
            return;
        }
        if (this.sampler == null) {
            GpuDevice device = RenderSystem.getDevice();
            if (device == null) {
                return;
            }
            // Nearest-neighbor sampling keeps block edges crisp beyond 512px magnification
            this.sampler = device.createSampler(AddressMode.CLAMP_TO_EDGE, AddressMode.CLAMP_TO_EDGE,
                    FilterMode.NEAREST, FilterMode.NEAREST, 1, OptionalDouble.empty());
        }
        int halfW = guiWidth / 2;
        int halfH = guiHeight / 2;
        // Native 512x512 texture (1px = 1 block); draw size = zoom so it aligns with overlays/player
        int size = zoom;
        for (Column c : this.columns.values()) {
            if (!c.uploaded || c.texture == null) {
                continue;
            }
            int sx = (int) Math.floor((c.colX - centerTlnX) * zoom + halfW);
            int sy = (int) Math.floor((c.colZ - centerTlnZ) * zoom + halfH);
            if (sx + size < 0 || sy + size < 0 || sx > guiWidth || sy > guiHeight) {
                continue;
            }
            c.lastUsed = ++this.tick;
            // 26.x blit: 4 ints are absolute (x0,y0,x1,y1), 4 floats are normalized (u0,u1,v0,v1) UVs
            extractor.blit(c.texture.getTextureView(), this.sampler, sx, sy,
                    sx + size, sy + size, 0f, 1f, 0f, 1f);
        }
        this.evictIfNeeded();
    }

    /** Close and evict the least recently used columns once the texture cap is exceeded. */
    private void evictIfNeeded() {
        if (this.columns.size() <= MAX_TEXTURES) {
            return;
        }
        List<Column> uploaded = new ArrayList<>();
        for (Column c : this.columns.values()) {
            if (c.uploaded) {
                uploaded.add(c);
            }
        }
        uploaded.sort(Comparator.comparingLong(c -> c.lastUsed));
        int toRemove = this.columns.size() - MAX_TEXTURES;
        for (int i = 0; i < toRemove && i < uploaded.size(); i++) {
            Column c = uploaded.get(i);
            if (c.texture != null) {
                c.texture.close();
            }
            c.uploaded = false;
            this.columns.remove(c.packed, c);
        }
    }

    private static long pack(int x, int z) {
        return ((long) x << 32) | (z & 0xFFFFFFFFL);
    }
}
