package dev.bigpaste;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CancellationException;

import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.block.Block;
import net.minecraft.block.BlockEntityProvider;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.registry.Registries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerChunkManager;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.state.property.Property;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.Heightmap;
import net.minecraft.world.chunk.ChunkSection;
import net.minecraft.world.chunk.WorldChunk;

/**
 * One streaming paste.
 *
 * Memory model (the "no leak" part):
 *  - the schematic is never loaded; BlockStates sits in a temp file (see LitematicStream)
 *  - we paste one chunk-row "tile" at a time: (up to 16 z-rows) x (all y) x (a group of chunk columns).
 *    The tile is a single short[] capped at MAX_TILE_ENTRIES (64 MB) and reused for the whole region
 *  - every chunk is touched once, modified, marked dirty, then left for vanilla to unload and save
 *  - back-pressure: if loaded chunks run ahead of vanilla's unloading we pause until they drain
 *  - temp files and big arrays are released on finish / cancel / failure / server stop
 *  - nothing is stored in statics except the one active job, which BigPasteMod nulls on completion
 */
final class PasteJob {

    enum Phase { PARSING, PASTING, DONE, FAILED, CANCELLED }

    private static final long TICK_BUDGET_NANOS = 20_000_000L;      // ~20 ms of work per server tick
    private static final long MAX_TILE_ENTRIES = 32L * 1024 * 1024; // 32M shorts = 64 MB
    private static final int MAX_EXTRA_LOADED_CHUNKS = 400;
    private static final VarHandle LONG_BE =
            MethodHandles.byteArrayViewVarHandle(long[].class, ByteOrder.BIG_ENDIAN);

    static Path spoolDir() {
        return FabricLoader.getInstance().getGameDir().resolve("bigpaste-tmp");
    }

    /** Removes temp files left behind by a crashed run. */
    static void cleanSpoolDir() {
        Path dir = spoolDir();
        if (!Files.isDirectory(dir)) return;
        try (var s = Files.list(dir)) {
            s.filter(p -> p.getFileName().toString().startsWith("bigpaste-")).forEach(p -> {
                try { Files.deleteIfExists(p); } catch (IOException ignored) {}
            });
        } catch (IOException ignored) {}
    }

    private final MinecraftServer server;
    private final ServerWorld world;
    private final Path file;
    private final BlockPos origin;
    private final boolean replaceAir;
    private final UUID notifyUuid;

    private volatile Phase phase = Phase.PARSING;
    private volatile boolean cancelled;
    private volatile String failure;
    private volatile long parsedBytes;
    private volatile List<LitematicStream.Region> regions = List.of();

    // server-thread-only state
    private boolean initialised, reported;
    private int enclMinX, enclMinY, enclMinZ;
    private int regionIdx = -1;
    private RegionPlan plan;
    private long columnsTotal, columnsDone, blocksWritten;
    private int baselineLoaded, unknownBlocks;
    private long startedNanos, lastReportNanos;
    private boolean yieldThisTick;
    private final Set<String> warnedBlocks = new HashSet<>();

    PasteJob(MinecraftServer server, ServerWorld world, Path file, BlockPos origin,
             boolean replaceAir, UUID notifyUuid) {
        this.server = server;
        this.world = world;
        this.file = file;
        this.origin = origin;
        this.replaceAir = replaceAir;
        this.notifyUuid = notifyUuid;
    }

    // ---- lifecycle --------------------------------------------------------------------

    /** Decompress + spool on a background thread so the server never stalls. */
    void startParsing() {
        Thread t = new Thread(() -> {
            try {
                List<LitematicStream.Region> r = LitematicStream.read(
                        file, spoolDir(), () -> cancelled, n -> parsedBytes = n);
                regions = r;
                phase = cancelled ? Phase.CANCELLED : Phase.PASTING;
            } catch (CancellationException e) {
                phase = Phase.CANCELLED;
            } catch (Throwable e) {
                BigPasteMod.LOG.error("BigPaste: failed to read {}", file, e);
                failure = e.toString();
                phase = Phase.FAILED;
            }
        }, "bigpaste-parse");
        t.setDaemon(true);
        t.start();
    }

    void cancel() { cancelled = true; }

    boolean isFinished() {
        Phase p = phase;
        return p == Phase.DONE || p == Phase.FAILED || p == Phase.CANCELLED;
    }

    boolean isActive() { return !isFinished(); }

    /** Frees everything. Safe to call more than once. */
    void dispose() {
        cancelled = true;
        closePlan();
        deleteSpools();
        regions = List.of();
    }

    String statusLine() {
        return switch (phase) {
            case PARSING -> "Unpacking schematic to temp file: " + (parsedBytes >> 20) + " MB";
            case PASTING -> String.format("Pasting: %d/%d chunk columns (%.1f%%), %,d blocks written",
                    columnsDone, columnsTotal, columnsTotal == 0 ? 0.0 : 100.0 * columnsDone / columnsTotal,
                    blocksWritten);
            case DONE -> "Finished";
            case FAILED -> "Failed: " + failure;
            case CANCELLED -> "Cancelled";
        };
    }

    // ---- server tick ------------------------------------------------------------------

    void tick() {
        Phase p = phase;
        if (p == Phase.PARSING) {
            long now = System.nanoTime();
            if (now - lastReportNanos > 2_000_000_000L) {
                lastReportNanos = now;
                say(statusLine(), true);
            }
        } else if (p == Phase.PASTING) {
            pasteTick();
        }

        p = phase;
        if (isFinished() && !reported) {
            reported = true;
            closePlan();
            deleteSpools();
            switch (p) {
                case DONE -> {
                    double secs = (System.nanoTime() - startedNanos) / 1e9;
                    say(String.format("BigPaste done: %,d blocks in %.0fs.%s Rejoin the world (or fly far away and back) "
                                    + "so your client gets the new chunks; light is rebuilt when chunks reload.",
                            blocksWritten, secs,
                            unknownBlocks > 0 ? " (" + unknownBlocks + " unknown block types became air, see log.)" : ""),
                            false);
                }
                case FAILED -> say("BigPaste failed: " + failure, false);
                default -> say("BigPaste cancelled.", false);
            }
        }
    }

    private void pasteTick() {
        long deadline = System.nanoTime() + TICK_BUDGET_NANOS;
        try {
            if (!initialised) init();
            while (!yieldThisTick && System.nanoTime() < deadline) {
                if (cancelled) { phase = Phase.CANCELLED; return; }
                if (plan == null && !nextRegion()) { phase = Phase.DONE; return; }
                if (!plan.step()) closePlan();
            }
            yieldThisTick = false;

            long now = System.nanoTime();
            if (now - lastReportNanos > 5_000_000_000L) {
                lastReportNanos = now;
                double secs = (now - startedNanos) / 1e9;
                double perSec = columnsDone / Math.max(1.0, secs);
                long etaSec = perSec <= 0 ? 0 : (long) ((columnsTotal - columnsDone) / perSec);
                say(String.format("BigPaste %.0f%% - %d/%d chunk columns - ~%dm%02ds left",
                        100.0 * columnsDone / Math.max(1, columnsTotal), columnsDone, columnsTotal,
                        etaSec / 60, etaSec % 60), true);
            }
        } catch (Throwable t) {
            BigPasteMod.LOG.error("BigPaste: paste failed", t);
            failure = t.toString();
            phase = Phase.FAILED;
        }
    }

    private void init() {
        initialised = true;
        startedNanos = System.nanoTime();
        lastReportNanos = startedNanos;
        int mx = Integer.MAX_VALUE, my = Integer.MAX_VALUE, mz = Integer.MAX_VALUE;
        for (LitematicStream.Region r : regions) {
            mx = Math.min(mx, r.minX());
            my = Math.min(my, r.minY());
            mz = Math.min(mz, r.minZ());
        }
        enclMinX = mx; enclMinY = my; enclMinZ = mz;
        for (LitematicStream.Region r : regions) {
            int wx0 = origin.getX() + (r.minX() - enclMinX);
            int wz0 = origin.getZ() + (r.minZ() - enclMinZ);
            columnsTotal += (long) span(wx0, r.absX()) * span(wz0, r.absZ());
        }
        baselineLoaded = world.getChunkManager().getLoadedChunkCount();
    }

    private static int span(int w0, int size) {
        return Math.floorDiv(w0 + size - 1, 16) - Math.floorDiv(w0, 16) + 1;
    }

    private boolean nextRegion() throws IOException {
        closePlan();
        regionIdx++;
        if (regionIdx >= regions.size()) return false;
        plan = new RegionPlan(regions.get(regionIdx));
        return true;
    }

    private void closePlan() {
        if (plan != null) {
            plan.close();
            plan = null;
        }
    }

    private void deleteSpools() {
        for (LitematicStream.Region r : regions) {
            if (r.spool != null) {
                try { Files.deleteIfExists(r.spool); } catch (IOException e) {
                    BigPasteMod.LOG.warn("BigPaste: could not delete {}", r.spool);
                }
            }
        }
    }

    private void say(String msg, boolean overlay) {
        BigPasteMod.LOG.info("[BigPaste] {}", msg);
        if (notifyUuid == null) return;
        ServerPlayerEntity p = server.getPlayerManager().getPlayer(notifyUuid);
        if (p != null) p.sendMessage(Text.literal(msg), overlay);
    }

    // ---- palette ----------------------------------------------------------------------

    private BlockState[] resolvePalette(LitematicStream.Region r) {
        BlockState[] out = new BlockState[r.palette.size()];
        for (int i = 0; i < out.length; i++) {
            LitematicStream.PaletteEntry e = r.palette.get(i);
            Block block = resolveBlock(e.name());
            if (block == null) {
                unknownBlocks++;
                if (warnedBlocks.add(e.name())) BigPasteMod.LOG.warn("BigPaste: unknown block {} -> air", e.name());
                out[i] = Blocks.AIR.getDefaultState();
                continue;
            }
            BlockState state = block.getDefaultState();
            for (var prop : e.props().entrySet()) {
                Property<?> p = block.getStateManager().getProperty(prop.getKey());
                if (p != null) state = applyProp(state, p, prop.getValue());
            }
            out[i] = state;
        }
        return out;
    }

    /** Version-sensitive registry lookup is isolated here. */
    private static Block resolveBlock(String name) {
        Identifier id = Identifier.tryParse(name);
        if (id == null || !Registries.BLOCK.containsId(id)) return null;
        return Registries.BLOCK.get(id);
    }

    private static <T extends Comparable<T>> BlockState applyProp(BlockState s, Property<T> p, String raw) {
        Optional<T> v = p.parse(raw);
        return v.isPresent() ? s.with(p, v.get()) : s;
    }

    // ---- per-region work --------------------------------------------------------------

    private final class RegionPlan {
        final LitematicStream.Region r;
        final BlockState[] palette;
        final boolean[] skip;
        final int bits, sx, sy, sz, wx0, wy0, wz0;
        final int layerFrom, layers;
        final int cxMin, cxMax, czMin, czMax, group;
        final RandomAccessFile raf;
        final short[] tile;
        byte[] scratch = new byte[0];

        int cz, groupStartCx;
        boolean tileReady;
        int tileZS, tileXS, rows, width, groupEndCx, loadLayer, writeCx;

        RegionPlan(LitematicStream.Region r) throws IOException {
            this.r = r;
            if (r.palette.size() > 65536) throw new IOException("Palette too large: " + r.palette.size());
            bits = r.bits();
            palette = resolvePalette(r);
            skip = new boolean[palette.length];
            for (int i = 0; i < skip.length; i++) skip[i] = !replaceAir && palette[i].isAir();

            sx = r.absX(); sy = r.absY(); sz = r.absZ();
            wx0 = origin.getX() + (r.minX() - enclMinX);
            wy0 = origin.getY() + (r.minY() - enclMinY);
            wz0 = origin.getZ() + (r.minZ() - enclMinZ);

            int bottom = world.getBottomY();
            int top = bottom + world.getHeight();
            int yFrom = Math.max(wy0, bottom);
            int yTo = Math.min(wy0 + sy, top);
            layerFrom = Math.max(0, yFrom - wy0);
            layers = Math.max(0, yTo - yFrom);

            cxMin = Math.floorDiv(wx0, 16);
            cxMax = Math.floorDiv(wx0 + sx - 1, 16);
            czMin = Math.floorDiv(wz0, 16);
            czMax = Math.floorDiv(wz0 + sz - 1, 16);
            long perColumn = 256L * Math.max(1, layers);
            group = (int) Math.max(1, Math.min(cxMax - cxMin + 1, MAX_TILE_ENTRIES / perColumn));

            if (layers > 0) {
                raf = new RandomAccessFile(r.spool.toFile(), "r");
                tile = new short[(int) (perColumn * group)];
            } else {
                raf = null;
                tile = new short[0];
                BigPasteMod.LOG.warn("BigPaste: region {} is entirely outside the world height", r.name);
            }
            cz = czMin;
            groupStartCx = cxMin;
        }

        void close() {
            try { if (raf != null) raf.close(); } catch (IOException ignored) {}
        }

        /** One small unit of work. Returns false once the region is complete. */
        boolean step() throws IOException {
            if (layers == 0 || cz > czMax) return false;
            if (!tileReady) beginTile();

            if (loadLayer < layers) {
                loadOneLayer();
                return true;
            }
            if (writeCx <= groupEndCx) {
                ServerChunkManager cm = world.getChunkManager();
                if (cm.getLoadedChunkCount() > baselineLoaded + MAX_EXTRA_LOADED_CHUNKS) {
                    yieldThisTick = true; // let vanilla unload + save before we load more
                    return true;
                }
                writeColumn(writeCx++);
                columnsDone++;
                return true;
            }
            tileReady = false;
            groupStartCx = groupEndCx + 1;
            if (groupStartCx > cxMax) {
                groupStartCx = cxMin;
                cz++;
            }
            return true;
        }

        private void beginTile() {
            tileZS = Math.max(wz0, cz * 16);
            int zE = Math.min(wz0 + sz, cz * 16 + 16);
            rows = zE - tileZS;
            groupEndCx = Math.min(cxMax, groupStartCx + group - 1);
            tileXS = Math.max(wx0, groupStartCx * 16);
            int xE = Math.min(wx0 + sx, groupEndCx * 16 + 16);
            width = xE - tileXS;
            loadLayer = 0;
            writeCx = groupStartCx;
            tileReady = true;
        }

        private void loadOneLayer() throws IOException {
            int ly = layerFrom + loadLayer;
            for (int row = 0; row < rows; row++) {
                long lz = (tileZS - wz0) + row;
                long entry = ((long) ly * sz + lz) * sx + (tileXS - wx0);
                readEntries(entry, width, (loadLayer * rows + row) * width);
            }
            loadLayer++;
        }

        /** Unpacks `count` palette indices starting at schematic entry `startEntry` into tile[outOff..]. */
        private void readEntries(long startEntry, int count, int outOff) throws IOException {
            long startBit = startEntry * bits;
            long endBit = (startEntry + count) * bits;
            long firstLong = startBit >>> 6;
            long lastLong = (endBit - 1) >>> 6;
            int nBytes = (int) (lastLong - firstLong + 1) * 8;
            if (scratch.length < nBytes) scratch = new byte[Math.max(nBytes, scratch.length * 2)];
            raf.seek(firstLong * 8);
            raf.readFully(scratch, 0, nBytes);

            long mask = (1L << bits) - 1;
            long rel = startBit - firstLong * 64;
            for (int i = 0; i < count; i++, rel += bits) {
                int li = (int) (rel >>> 6);
                int off = (int) (rel & 63);
                long v = (long) LONG_BE.get(scratch, li * 8) >>> off;
                if (off + bits > 64) v |= (long) LONG_BE.get(scratch, (li + 1) * 8) << (64 - off);
                tile[outOff + i] = (short) (v & mask);
            }
        }

        private void writeColumn(int cx) {
            // Loads (or generates) the chunk; the temporary ticket expires on its own afterwards.
            WorldChunk chunk = world.getChunk(cx, cz);

            int xFrom = Math.max(tileXS, cx * 16);
            int xTo = Math.min(tileXS + width, cx * 16 + 16);
            BlockPos.Mutable mp = new BlockPos.Mutable();
            ChunkSection section = null;
            int sectionCoord = Integer.MIN_VALUE;
            long changed = 0;

            for (int k = 0; k < layers; k++) {
                int wy = wy0 + layerFrom + k;
                if ((wy >> 4) != sectionCoord) {
                    sectionCoord = wy >> 4;
                    section = chunk.getSection(chunk.getSectionIndex(wy));
                }
                for (int row = 0; row < rows; row++) {
                    int wz = tileZS + row;
                    int rowBase = (k * rows + row) * width - tileXS;
                    for (int wx = xFrom; wx < xTo; wx++) {
                        int idx = tile[rowBase + wx] & 0xFFFF;
                        if (skip[idx]) continue;
                        BlockState state = palette[idx];
                        BlockState old = section.setBlockState(wx & 15, wy & 15, wz & 15, state, false);
                        if (old == state) continue;
                        changed++;
                        if (old.hasBlockEntity() || state.hasBlockEntity()) {
                            mp.set(wx, wy, wz);
                            if (old.hasBlockEntity()) chunk.removeBlockEntity(mp);
                            if (state.hasBlockEntity() && state.getBlock() instanceof BlockEntityProvider provider) {
                                BlockEntity be = provider.createBlockEntity(mp.toImmutable(), state);
                                if (be != null) chunk.setBlockEntity(be);
                            }
                        }
                    }
                }
            }
            blocksWritten += changed;
            if (changed == 0) return;

            Heightmap.populateHeightmaps(chunk, EnumSet.of(
                    Heightmap.Type.MOTION_BLOCKING, Heightmap.Type.MOTION_BLOCKING_NO_LEAVES,
                    Heightmap.Type.OCEAN_FLOOR, Heightmap.Type.WORLD_SURFACE));
            chunk.setLightOn(false);   // vanilla relights the chunk the next time it loads from disk
            chunk.markNeedsSaving();
        }
    }
}
