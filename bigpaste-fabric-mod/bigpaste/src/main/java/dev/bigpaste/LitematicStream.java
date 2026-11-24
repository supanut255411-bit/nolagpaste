package dev.bigpaste;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;
import java.util.function.LongConsumer;
import java.util.zip.GZIPInputStream;

/**
 * Minimal streaming reader for Litematica (.litematic) files.
 *
 * Vanilla NBT readers materialise the whole file; a 2000x300x2000 region has a
 * ~1.2 GB BlockStates long[] and that is what blows the heap. This reader walks the
 * gzip stream once, keeps only the small stuff in memory (positions, sizes, palette)
 * and copies the giant BlockStates array straight to a temp file in 1 MB pieces.
 *
 * Key order inside a region compound is not guaranteed, so BlockStates can show up
 * before the palette/size are known. Spooling to disk makes that irrelevant.
 */
final class LitematicStream {

    record PaletteEntry(String name, Map<String, String> props) {}

    static final class Region {
        final String name;
        int posX, posY, posZ;
        int sizeX, sizeY, sizeZ; // may be negative (region extends towards -axis)
        final List<PaletteEntry> palette = new ArrayList<>();
        Path spool;              // big-endian long[] dump of BlockStates
        long longCount = -1;

        Region(String name) { this.name = name; }

        int absX() { return Math.abs(sizeX); }
        int absY() { return Math.abs(sizeY); }
        int absZ() { return Math.abs(sizeZ); }
        int minX() { return sizeX < 0 ? posX + sizeX + 1 : posX; }
        int minY() { return sizeY < 0 ? posY + sizeY + 1 : posY; }
        int minZ() { return sizeZ < 0 ? posZ + sizeZ + 1 : posZ; }
        long volume() { return (long) absX() * absY() * absZ(); }
        int bits() { return Math.max(2, 32 - Integer.numberOfLeadingZeros(palette.size() - 1)); }
    }

    private LitematicStream() {}

    /** Parses the file. Big arrays go to spoolDir; returns regions that have block data. */
    static List<Region> read(Path file, Path spoolDir, BooleanSupplier cancelled, LongConsumer progress)
            throws IOException {
        Files.createDirectories(spoolDir);
        List<Region> regions = new ArrayList<>();
        try (DataInputStream in = new DataInputStream(new BufferedInputStream(
                new GZIPInputStream(Files.newInputStream(file), 1 << 16), 1 << 16))) {

            if (in.readUnsignedByte() != 10) throw new IOException("Not an NBT compound root");
            in.skipNBytes(in.readUnsignedShort()); // root name

            while (true) {
                int type = in.readUnsignedByte();
                if (type == 0) break;
                String key = in.readUTF();
                if (type == 10 && key.equals("Regions")) {
                    readRegions(in, spoolDir, regions, cancelled, progress);
                } else {
                    skipPayload(in, type, 0);
                }
            }
            regions.removeIf(r -> r.spool == null);
            for (Region r : regions) validate(r);
            return regions;
        } catch (Throwable t) {
            for (Region r : regions) {
                if (r.spool != null) {
                    try { Files.deleteIfExists(r.spool); } catch (IOException ignored) {}
                }
            }
            throw t;
        }
    }

    private static void validate(Region r) throws IOException {
        if (r.sizeX == 0 || r.sizeY == 0 || r.sizeZ == 0) throw new IOException("Region " + r.name + " has zero size");
        if (r.palette.isEmpty()) throw new IOException("Region " + r.name + " has no palette");
        long needBits = r.volume() * r.bits();
        if (r.longCount * 64L < needBits) {
            throw new IOException("Region " + r.name + " BlockStates too short (corrupt file?)");
        }
    }

    private static void readRegions(DataInputStream in, Path spoolDir, List<Region> out,
                                    BooleanSupplier cancelled, LongConsumer progress) throws IOException {
        while (true) {
            int type = in.readUnsignedByte();
            if (type == 0) return;
            String name = in.readUTF();
            if (type != 10) { skipPayload(in, type, 0); continue; }
            Region r = new Region(name);
            out.add(r); // added early so a failure mid-region still cleans its spool
            readRegion(in, r, spoolDir, cancelled, progress);
        }
    }

    private static void readRegion(DataInputStream in, Region r, Path spoolDir,
                                   BooleanSupplier cancelled, LongConsumer progress) throws IOException {
        while (true) {
            int type = in.readUnsignedByte();
            if (type == 0) return;
            String key = in.readUTF();
            if (type == 10 && key.equals("Position")) {
                int[] v = readXYZ(in);
                r.posX = v[0]; r.posY = v[1]; r.posZ = v[2];
            } else if (type == 10 && key.equals("Size")) {
                int[] v = readXYZ(in);
                r.sizeX = v[0]; r.sizeY = v[1]; r.sizeZ = v[2];
            } else if (type == 9 && key.equals("BlockStatePalette")) {
                readPalette(in, r);
            } else if (type == 12 && key.equals("BlockStates")) {
                spool(in, r, spoolDir, cancelled, progress);
            } else {
                skipPayload(in, type, 0); // TileEntities, Entities, ticks...
            }
        }
    }

    private static int[] readXYZ(DataInputStream in) throws IOException {
        int[] v = new int[3];
        while (true) {
            int type = in.readUnsignedByte();
            if (type == 0) return v;
            String k = in.readUTF();
            if (type == 3 && k.length() == 1 && k.charAt(0) >= 'x' && k.charAt(0) <= 'z') {
                v[k.charAt(0) - 'x'] = in.readInt();
            } else {
                skipPayload(in, type, 0);
            }
        }
    }

    private static void readPalette(DataInputStream in, Region r) throws IOException {
        int elemType = in.readUnsignedByte();
        int n = in.readInt();
        if (n < 0 || n > (1 << 20)) throw new IOException("Bad palette size " + n);
        if (elemType != 10) {
            for (int i = 0; i < n; i++) skipPayload(in, elemType, 1);
            return;
        }
        for (int i = 0; i < n; i++) {
            String name = "minecraft:air";
            Map<String, String> props = new LinkedHashMap<>();
            while (true) {
                int type = in.readUnsignedByte();
                if (type == 0) break;
                String key = in.readUTF();
                if (type == 8 && key.equals("Name")) {
                    name = in.readUTF();
                } else if (type == 10 && key.equals("Properties")) {
                    while (true) {
                        int pt = in.readUnsignedByte();
                        if (pt == 0) break;
                        String pk = in.readUTF();
                        if (pt == 8) props.put(pk, in.readUTF());
                        else skipPayload(in, pt, 2);
                    }
                } else {
                    skipPayload(in, type, 2);
                }
            }
            r.palette.add(new PaletteEntry(name, props));
        }
    }

    /** Copies the long array to disk without ever holding more than 1 MB of it in memory. */
    private static void spool(DataInputStream in, Region r, Path spoolDir,
                              BooleanSupplier cancelled, LongConsumer progress) throws IOException {
        int n = in.readInt();
        if (n < 0) throw new IOException("Bad BlockStates length");
        long bytes = n * 8L;
        long free = Files.getFileStore(spoolDir).getUsableSpace();
        if (free < bytes + (256L << 20)) {
            throw new IOException("Not enough disk space for temp file: need "
                    + (bytes >> 20) + " MB, have " + (free >> 20) + " MB free");
        }
        Path tmp = Files.createTempFile(spoolDir, "bigpaste-", ".bin");
        r.spool = tmp;
        byte[] buf = new byte[1 << 20];
        long remaining = bytes, done = 0;
        try (OutputStream os = new BufferedOutputStream(Files.newOutputStream(tmp), 1 << 20)) {
            while (remaining > 0) {
                if (cancelled.getAsBoolean()) throw new CancellationException();
                int chunk = (int) Math.min(buf.length, remaining);
                in.readFully(buf, 0, chunk);
                os.write(buf, 0, chunk);
                remaining -= chunk;
                done += chunk;
                progress.accept(done);
            }
        }
        r.longCount = n;
    }

    // ---- generic skipping ------------------------------------------------------------

    private static int fixedSize(int type) {
        return switch (type) {
            case 1 -> 1;
            case 2 -> 2;
            case 3, 5 -> 4;
            case 4, 6 -> 8;
            default -> -1;
        };
    }

    static void skipPayload(DataInputStream in, int type, int depth) throws IOException {
        if (depth > 512) throw new IOException("NBT nested too deeply");
        int fixed = fixedSize(type);
        if (fixed > 0) { in.skipNBytes(fixed); return; }
        switch (type) {
            case 7 -> in.skipNBytes(nonNegative(in.readInt()));
            case 8 -> in.skipNBytes(in.readUnsignedShort());
            case 9 -> {
                int et = in.readUnsignedByte();
                int n = nonNegative(in.readInt());
                int es = fixedSize(et);
                if (es > 0) {
                    in.skipNBytes((long) es * n);
                } else {
                    for (int i = 0; i < n; i++) skipPayload(in, et, depth + 1);
                }
            }
            case 10 -> {
                while (true) {
                    int t = in.readUnsignedByte();
                    if (t == 0) break;
                    in.skipNBytes(in.readUnsignedShort());
                    skipPayload(in, t, depth + 1);
                }
            }
            case 11 -> in.skipNBytes(4L * nonNegative(in.readInt()));
            case 12 -> in.skipNBytes(8L * nonNegative(in.readInt()));
            default -> throw new IOException("Unknown NBT tag type " + type);
        }
    }

    private static int nonNegative(int v) throws IOException {
        if (v < 0) throw new IOException("Negative NBT length");
        return v;
    }
}
