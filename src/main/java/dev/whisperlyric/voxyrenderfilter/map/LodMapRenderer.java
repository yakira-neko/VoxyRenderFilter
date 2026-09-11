package dev.whisperlyric.voxyrenderfilter.map;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.GpuDevice;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.AddressMode;
import com.mojang.blaze3d.textures.FilterMode;
import com.mojang.blaze3d.textures.GpuSampler;
import dev.whisperlyric.voxyrenderfilter.config.VrfConfig;
import dev.whisperlyric.voxyrenderfilter.config.VrfConfig.ScanMode;
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
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.OptionalDouble;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Generates the real terrain map from the on-disk voxy LOD cache: one 512x512 texture per TLN
 * column. Background worker threads read every LOD level (lvl0-4) and fill the topmost non-air block
 * per voxel column (lvl0 first, coarse LOD only patches pixels left empty); the render thread
 * uploads finished textures and blits them. Only visible columns are generated, capped with an LRU
 * limit; invalidate() rebuilds everything when the world engine changes or the cache is deleted.
 */
public final class LodMapRenderer {

    public static final LodMapRenderer INSTANCE = new LodMapRenderer();

    private static final int COLUMN_PX = 512; // 1 TLN column = 512x512 blocks -> pixels
    private static final int SECTION_PX = 32; // 1 lvl0 section = 32x32 blocks -> pixels
    private static final int SECTION_VOL = 32 * 32 * 32;
    private static final int MAX_PENDING = 96;
    /** Max generation radius per request (in columns); prevents an infinite generate-evict loop when many columns are visible at low zoom. */
    private static final int GEN_RADIUS_MAX = 5;
    private static final int MAX_UPLOADS_PER_FRAME = 4;
    /** Generation worker count: tile generation is deserialize/scan CPU work, so a small pool
     * multiplies throughput; the cap keeps concurrent disk reads reasonable. */
    private static final int GENERATION_THREADS = Math.max(2, Math.min(4, Runtime.getRuntime().availableProcessors() / 2));
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
    /** Columns whose texture was replaced by a duplicate generation; the render thread frees their GL resources. */
    private final ConcurrentLinkedQueue<Column> staleColumns = new ConcurrentLinkedQueue<>();
    private final Object lock = new Object();
    private final LongOpenHashSet pendingSet = new LongOpenHashSet();
    /** Columns that failed to generate: never retried this session (cleared by invalidate on disk change), so a bad column cannot block the queue. */
    private final LongOpenHashSet failedColumns = new LongOpenHashSet();
    private final java.util.ArrayDeque<Long> pendingQueue = new java.util.ArrayDeque<>();
    private final ThreadLocalMemoryBuffer scratchBuffer =
            new ThreadLocalMemoryBuffer(BIGGEST_SERIALIZED_SECTION_SIZE + 1024);

    private volatile WorldEngine engine;
    /** True while viewing another dimension's cache: only cached images load, no engine reads/generation. */
    private volatile boolean cacheOnly;
    private volatile Long2ObjectOpenHashMap<LongArrayList> secKeys; // packed(colX,colZ) -> keys of all LOD levels in that column
    /** Full disk rescan requested but not yet run; runs on a dedicated scan thread. */
    private volatile boolean rescanRequested;
    /** True while a full disk scan is running on the dedicated thread. */
    private volatile boolean scanning;
    /** Frame number of the last on-disk new-column check (every 40 frames ~ 2s). */
    private long lastStaleCheckTick;
    /** AUTO mode: last time the disk index was refreshed (picks up in-place changes). */
    private long lastAutoScanTime;
    private static final long AUTO_RESCAN_INTERVAL_MS = 5000;
    /** One-shot re-validation of rendered columns requested (map open / view distance change). */
    private volatile boolean validationPending;
    private volatile boolean closed;
    /** Generation worker pool, managed on the main thread only (prune dead, top up to the target count). */
    private final List<Thread> workers = new ArrayList<>();
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

    /** Clears generated columns/textures but keeps the engine and disk index (used on dimension switch). */
    private void clearColumns() {
        synchronized (this.lock) {
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
        this.tick++; // frame counter for LRU bookkeeping
        if (this.engine != engine) {
            this.invalidate();
            this.engine = engine;
            // A new world/server is always viewed in its live dimension
            this.cacheOnly = false;
            MapCache.INSTANCE.setWorld(Minecraft.getInstance().level);
            // The full disk index is scanned on a dedicated thread, never blocking rendering
            this.requestScan();
        }
        // Render first, then validate once (map open / view distance change) once the index is ready
        if (this.validationPending && !this.cacheOnly && this.secKeys != null) {
            this.validationPending = false;
            this.runValidationPass();
        }
        // Detect new disk columns: a ring column outside the last scan triggers a background rescan,
        // so freshly generated/re-ingested regions show up without manual cache deletion
        if (this.tick - this.lastStaleCheckTick >= STALE_CHECK_INTERVAL) {
            this.lastStaleCheckTick = this.tick;
            if (this.hasColumnsMissingFromScan()) {
                this.requestScan();
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
        boolean cacheOnly = this.cacheOnly;
        int visHalfX = (int) Math.ceil(halfW / (double) zoom);
        int visHalfZ = (int) Math.ceil(halfH / (double) zoom);
        int genHalfX = Math.min(GEN_RADIUS_MAX, visHalfX);
        int genHalfZ = Math.min(GEN_RADIUS_MAX, visHalfZ);
        List<Long> visible = new ArrayList<>();
        for (int cx = centerColX - genHalfX; cx <= centerColX + genHalfX; cx++) {
            for (int cz = centerColZ - genHalfZ; cz <= centerColZ + genHalfZ; cz++) {
                long packed = pack(cx, cz);
                if (cacheOnly) {
                    // Other dimension: only columns that already have a cached image are loadable
                    if (!MapCache.INSTANCE.hasCached(cx, cz)) {
                        continue;
                    }
                } else if (secs != null) {
                    // Any LOD level (lvl0-4) having data is enough to generate: distant columns may only have coarse LOD
                    if (!secs.containsKey(packed)) {
                        continue;
                    }
                } else if (!MapCache.INSTANCE.hasCached(cx, cz)) {
                    // Disk index not built yet (engine just switched): still render-first the cached
                    // tiles so the map appears instantly; the scan fills the rest next frames
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
        // No LRU eviction: every generated column stays rendered. Only the pending queue is
        // depth-limited so the worker stays responsive; extra visible columns are enqueued next frame.
        synchronized (this.lock) {
            for (long packed : visible) {
                if (this.pendingSet.size() >= MAX_PENDING) {
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

    /** Section keys of the finest lvl0 level only; falls back to all keys when a column has no lvl0 data. */
    private static List<Long> lvl0Keys(List<Long> keys) {
        List<Long> lvl0 = null;
        for (long key : keys) {
            if (WorldEngine.getLevel(key) == 0) {
                if (lvl0 == null) {
                    lvl0 = new ArrayList<>();
                }
                lvl0.add(key);
            }
        }
        return lvl0 != null ? lvl0 : keys;
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
        this.workers.removeIf(t -> !t.isAlive());
        while (this.workers.size() < GENERATION_THREADS) {
            Thread t = new Thread(this::workerLoop, "VRF lod map-" + this.workers.size());
            t.setDaemon(true);
            this.workers.add(t);
            t.start();
        }
    }

    private void workerLoop() {
        while (!this.closed) {
            Long packed = this.nextPending();
            if (packed == null) {
                return;
            }
            // enqueueVisible re-queues a column while it is still generating (it left pendingSet at
            // dequeue, so the dedup guard cannot see it); once the original finishes, queued
            // duplicates only need to be dropped
            if (this.columns.containsKey(packed)) {
                continue;
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

    /**
     * Requests a full disk rescan. The scan runs on a DEDICATED thread so it never blocks the
     * generation worker: render-first (loading cached images) stays responsive even while the
     * scan is heavy. Concurrent requests coalesce: an active scan re-runs on completion.
     */
    private void requestScan() {
        synchronized (this.lock) {
            if (this.scanning) {
                this.rescanRequested = true;
                return;
            }
            this.scanning = true;
            Thread t = new Thread(this::scanLoop, "VRF lod scan");
            t.setDaemon(true);
            t.start();
        }
    }

    private void scanLoop() {
        try {
            WorldEngine eng = this.engine;
            if (eng != null) {
                // Runs in parallel with the coverage overlay's scan (LMDB read cursors are concurrent-safe)
                this.secKeys = this.scanKeys(eng);
            }
        } catch (Throwable ignored) {
            // A failed scan is retried on the next request
        } finally {
            synchronized (this.lock) {
                this.scanning = false;
                if (this.rescanRequested) {
                    this.rescanRequested = false;
                    Thread t = new Thread(this::scanLoop, "VRF lod scan");
                    t.setDaemon(true);
                    t.start();
                }
                this.lock.notifyAll();
            }
        }
    }

    /**
     * AUTO mode background refresh, called from the client tick: keeps the map cache around the
     * player (vanilla render distance) in sync with the disk, so opening the map is instant.
     * This only pre-generates missing columns; re-validation of rendered columns happens once
     * per trigger (map open, view distance change) via {@link #requestValidation()}.
     */
    public void refreshNearPlayer() {
        if (VrfConfig.INSTANCE.scanMode != ScanMode.AUTO || this.cacheOnly) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        WorldEngine cur = VoxyAccess.getCurrentEngine();
        if (cur == null || player == null) {
            return;
        }
        // Associate the engine even when the map is closed, so the background sync keeps running
        if (this.engine != cur) {
            this.invalidate();
            this.engine = cur;
            // A new world/server is always viewed in its live dimension
            this.cacheOnly = false;
            MapCache.INSTANCE.setWorld(mc.level);
            this.requestScan();
        }
        // Periodically refresh the disk index so in-place changes (new chunks saved inside an
        // existing column) are picked up, not only brand-new ring columns
        long now = System.currentTimeMillis();
        if (now - this.lastAutoScanTime >= AUTO_RESCAN_INTERVAL_MS) {
            this.lastAutoScanTime = now;
            this.requestScan();
        }
        Long2ObjectOpenHashMap<LongArrayList> secs = this.secKeys;
        if (secs == null) {
            return; // initial scan still in flight
        }
        int radius = Math.max(1, mc.options.getEffectiveRenderDistance() / 32 + 1);
        int pcx = (int) Math.floor(player.getX() / 512.0);
        int pcz = (int) Math.floor(player.getZ() / 512.0);
        // Pre-generate missing columns around the player so the map opens ready
        this.enqueueVisible(pcx, pcz, radius * 512, radius * 512, 512);
        this.startWorkerIfNeeded();
    }

    /** Requests a one-shot re-validation of the rendered columns (map open / view distance change). */
    public void requestValidation() {
        this.validationPending = true;
    }

    /** One-shot pass: drops rendered columns whose cached hash no longer matches the disk, so they regenerate. */
    private void runValidationPass() {
        if (this.cacheOnly) {
            return;
        }
        Long2ObjectOpenHashMap<LongArrayList> secs = this.secKeys;
        if (secs == null) {
            return;
        }
        java.util.List<Long> stale = new ArrayList<>();
        synchronized (this.lock) {
            for (long packed : this.columns.keySet()) {
                int cx = (int) (packed >> 32);
                int cz = (int) packed;
                LongArrayList list = secs.get(packed);
                if (list == null || list.isEmpty()) {
                    continue;
                }
                long hash = MapCache.computeHash(lvl0Keys(new ArrayList<>(list)));
                if (!MapCache.INSTANCE.hashMatches(cx, cz, hash)) {
                    stale.add(packed);
                }
            }
            for (long packed : stale) {
                Column c = this.columns.remove(packed);
                if (c != null) {
                    if (c.texture != null) {
                        c.texture.close();
                    }
                    c.uploaded = false;
                }
                this.pendingSet.add(packed);
                this.pendingQueue.addLast(packed);
            }
            if (!stale.isEmpty()) {
                this.lock.notifyAll();
            }
        }
    }

    /** Drops and re-generates the given columns, deleting their cached images first (region rescan).
     * A new rescan terminates pending regeneration from any previous one. */
    public void rescanColumns(LongOpenHashSet columns) {
        if (columns.isEmpty()) {
            return;
        }
        synchronized (this.lock) {
            // Supersede the previous rescan: drop its still-pending columns and start fresh
            this.pendingQueue.clear();
            this.pendingSet.clear();
            LongIterator it = columns.iterator();
            while (it.hasNext()) {
                long packed = it.nextLong();
                int cx = ActiveTopLevelTracker.columnX(packed);
                int cz = ActiveTopLevelTracker.columnZ(packed);
                MapCache.INSTANCE.deleteImage(cx, cz);
                Column c = this.columns.remove(packed);
                if (c != null) {
                    if (c.texture != null) {
                        c.texture.close();
                    }
                    c.uploaded = false;
                }
                this.failedColumns.remove(packed);
                this.pendingSet.add(packed);
                this.pendingQueue.addLast(packed);
            }
            this.lock.notifyAll();
        }
        this.startWorkerIfNeeded();
    }

    /** Switches the map to another dimension's cached data; the current dimension restores normal mode. */
    public void viewDimension(String dimId) {
        // "Other" means different from the player's LIVE dimension, not the previously viewed one:
        // cycling back to the live dimension must restore arrow, coverage and normal rendering
        String live = liveDimensionId();
        boolean other = dimId != null && !dimId.equals(live);
        this.cacheOnly = other;
        MapCache.INSTANCE.setDimension(dimId);
        this.clearColumns();
        if (!other) {
            // Back to the live dimension: re-point the cache and rescan the disk index
            MapCache.INSTANCE.setWorld(Minecraft.getInstance().level);
            this.requestScan();
        }
    }

    /** Sanitized dimension id the player is currently in, e.g. "minecraft_the_nether". */
    private static String liveDimensionId() {
        Level level = Minecraft.getInstance().level;
        if (level == null) {
            return null;
        }
        return level.dimension().identifier().toString().replace(':', '_');
    }

    /** Whether the map is currently showing another dimension's cached data (engine reads disabled). */
    public boolean isCacheOnly() {
        return this.cacheOnly;
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
        int colX = (int) (packed >> 32);
        int colZ = (int) packed;
        if (this.cacheOnly) {
            // Other dimension: only load what is already cached, never touch engine data
            NativeImage cached = MapCache.INSTANCE.loadImage(colX, colZ);
            if (cached != null) {
                Column col = new Column(packed);
                col.image = cached;
                this.publish(col);
            }
            return;
        }
        // Render first: a cached image needs no disk index, so it can appear immediately even
        // before the async scan finishes. Re-validation happens in a one-shot pass or triggers.
        NativeImage cached = MapCache.INSTANCE.loadImage(colX, colZ);
        if (cached != null) {
            Column col = new Column(packed);
            col.image = cached;
            this.publish(col);
            return;
        }
        Long2ObjectOpenHashMap<LongArrayList> secs = this.secKeys;
        if (secs == null) {
            return; // no cache and no disk index yet; retry next frame
        }
        boolean cacheOnlyAtStart = this.cacheOnly;
        String dimAtStart = MapCache.INSTANCE.getDimension();
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
            if (this.engine != eng
                    || this.cacheOnly != cacheOnlyAtStart
                    || !Objects.equals(dimAtStart, MapCache.INSTANCE.getDimension())) {
                img.close();
                return; // engine/dimension switched, discard
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
        // Persist the freshly generated texture for the next session (stable lvl0-key hash)
        MapCache.INSTANCE.saveImage(colX, colZ, img, MapCache.computeHash(lvl0Keys(keys)));
        Column col = new Column(packed);
        col.image = img;
        this.publish(col);
    }

    /** Publishes a finished column and queues its texture upload; a duplicate generation that
     * replaces an existing column parks the old one in staleColumns for the render thread to free. */
    private void publish(Column col) {
        Column prev = this.columns.put(col.packed, col);
        if (prev != null) {
            this.staleColumns.add(prev);
        }
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
        // GL resources may only be freed on the render thread: close textures of replaced duplicates
        Column stale;
        while ((stale = this.staleColumns.poll()) != null) {
            if (stale.texture != null) {
                stale.texture.close();
            }
        }
        int n = 0;
        Column c;
        while (n < MAX_UPLOADS_PER_FRAME && (c = this.uploads.poll()) != null) {
            n++;
            final Column cur = c;
            // A duplicate generation may have finished first; only the column still in the map
            // uploads, the loser's image is dropped before any texture wraps it
            if (this.columns.get(cur.packed) != cur) {
                if (cur.texture == null && cur.image != null) {
                    cur.image.close();
                }
                continue;
            }
            DynamicTexture tex = new DynamicTexture(
                    () -> "voxyrenderfilter-map-" + cur.colX + "_" + cur.colZ, cur.image);
            tex.upload();
            cur.texture = tex;
            cur.uploaded = true;
            cur.lastUsed = this.tick;
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
            c.lastUsed = this.tick;
            // 26.x blit: 4 ints are absolute (x0,y0,x1,y1), 4 floats are normalized (u0,u1,v0,v1) UVs
            extractor.blit(c.texture.getTextureView(), this.sampler, sx, sy,
                    sx + size, sy + size, 0f, 1f, 0f, 1f);
        }
    }

    private static long pack(int x, int z) {
        return ((long) x << 32) | (z & 0xFFFFFFFFL);
    }
}
