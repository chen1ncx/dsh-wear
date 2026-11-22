package io.dsh.watch;

/**
 * Minimal QR encoder: byte mode, error correction L or M, versions 1-10.
 *
 * Small enough to live inside a watch app with no dependencies, and complete
 * enough that every phone camera reads it: finder/timing/alignment patterns,
 * BCH format and version information, Reed-Solomon blocks with interleaving,
 * and the eight data masks scored with the standard penalty rules.
 */
public final class Qr {

    /** Error correction level. */
    public static final int L = 0;
    public static final int M = 1;

    // Data codewords per block and EC codewords per block, indexed by version-1.
    private static final int[][] EC_PER_BLOCK = {
            /* 1 */ {7, 10},
            /* 2 */ {10, 16},
            /* 3 */ {15, 26},
            /* 4 */ {20, 18},
            /* 5 */ {26, 24},
            /* 6 */ {18, 16},
            /* 7 */ {20, 18},
            /* 8 */ {24, 22},
            /* 9 */ {30, 22},
            /* 10 */ {18, 26},
    };

    private static final int[][][] DATA_BLOCKS = {
            /* 1 */ {{19}, {16}},
            /* 2 */ {{34}, {28}},
            /* 3 */ {{55}, {44}},
            /* 4 */ {{80}, {32, 32}},
            /* 5 */ {{108}, {43, 43}},
            /* 6 */ {{68, 68}, {27, 27, 27, 27}},
            /* 7 */ {{78, 78}, {31, 31, 31, 31}},
            /* 8 */ {{97, 97}, {38, 38, 39, 39}},
            /* 9 */ {{116, 116}, {36, 36, 36, 36, 37}},
            /* 10 */ {{68, 68, 69, 69}, {43, 43, 43, 43, 44}},
    };

    private static final int[][] ALIGNMENT = {
            /* 1 */ {},
            /* 2 */ {6, 18},
            /* 3 */ {6, 22},
            /* 4 */ {6, 26},
            /* 5 */ {6, 30},
            /* 6 */ {6, 34},
            /* 7 */ {6, 22, 38},
            /* 8 */ {6, 24, 42},
            /* 9 */ {6, 26, 46},
            /* 10 */ {6, 28, 50},
    };

    private Qr() {
    }

    /** Encode text into a square module grid; true = dark. */
    public static boolean[][] encode(String text, int level) {
        byte[] bytes;
        try {
            bytes = text.getBytes("UTF-8");
        } catch (Exception no) {
            bytes = text.getBytes();
        }
        int version = pickVersion(bytes.length, level);
        if (version < 0) return null;
        return encode(bytes, level, version, -1);
    }

    /** Test hook: a fixed mask (-1 = choose the lowest penalty). */
    static boolean[][] encode(byte[] bytes, int level, int version, int forcedMask) {
        int size = 17 + 4 * version;
        boolean[][] grid = new boolean[size][size];
        boolean[][] reserved = new boolean[size][size];

        drawFunctionPatterns(grid, reserved, version);
        int[] bits = payloadBits(bytes, level, version);

        int[] data = addEc(bits, level, version);
        placeData(grid, reserved, data);

        int mask = forcedMask;
        if (mask < 0) {
            int best = 0;
            int bestScore = Integer.MAX_VALUE;
            for (int candidate = 0; candidate < 8; candidate += 1) {
                boolean[][] trial = copy(grid);
                applyMask(trial, reserved, candidate);
                drawFormat(trial, level, candidate);
                int score = penalty(trial);
                if (score < bestScore) {
                    bestScore = score;
                    best = candidate;
                }
            }
            mask = best;
        }
        applyMask(grid, reserved, mask);
        drawFormat(grid, level, mask);
        if (version >= 7) drawVersion(grid, version);
        return grid;
    }

    // ------------------------------------------------------------------ bits

    static int[] debugCodewords(byte[] bytes, int level, int version) {
        return addEc(payloadBits(bytes, level, version), level, version);
    }

    private static int pickVersion(int byteLength, int level) {
        for (int version = 1; version <= 10; version += 1) {
            int dataCodewords = 0;
            for (int n : DATA_BLOCKS[version - 1][level]) dataCodewords += n;
            int lengthBits = version <= 9 ? 8 : 16;
            int needed = 4 + lengthBits + byteLength * 8;
            if (needed <= dataCodewords * 8) return version;
        }
        return -1;
    }

    private static int[] payloadBits(byte[] bytes, int level, int version) {
        int dataCodewords = 0;
        for (int n : DATA_BLOCKS[version - 1][level]) dataCodewords += n;
        int capacity = dataCodewords * 8;
        int[] bits = new int[capacity];
        int at = 0;
        at = put(bits, at, 0b0100, 4);
        at = put(bits, at, bytes.length, version <= 9 ? 8 : 16);
        for (byte value : bytes) at = put(bits, at, value & 0xFF, 8);
        int terminator = Math.min(4, capacity - at);
        at = put(bits, at, 0, terminator);
        while (at % 8 != 0) bits[at++] = 0;
        while (at < capacity) at = put(bits, at, 0xEC, 8);
        return bits;
    }

    private static int put(int[] bits, int at, int value, int count) {
        for (int i = count - 1; i >= 0; i -= 1) {
            if (at < bits.length) bits[at] = (value >>> i) & 1;
            at += 1;
        }
        return at;
    }

    // --------------------------------------------------------- Reed-Solomon

    private static int[] addEc(int[] bits, int level, int version) {
        int[] sizes = DATA_BLOCKS[version - 1][level];
        int ecc = EC_PER_BLOCK[version - 1][level];
        int blocks = sizes.length;

        byte[][] dataBlocks = new byte[blocks][];
        int cursor = 0;
        for (int b = 0; b < blocks; b += 1) {
            dataBlocks[b] = new byte[sizes[b]];
            for (int i = 0; i < sizes[b]; i += 1) {
                int value = 0;
                for (int k = 0; k < 8; k += 1) value = (value << 1) | bits[cursor++];
                dataBlocks[b][i] = (byte) value;
            }
        }
        byte[][] ecBlocks = new byte[blocks][];
        int[] gen = generator(ecc);
        for (int b = 0; b < blocks; b += 1) ecBlocks[b] = remainder(dataBlocks[b], gen);

        int total = 0;
        for (byte[] block : dataBlocks) total += block.length;
        for (byte[] block : ecBlocks) total += block.length;
        int[] out = new int[total];
        int at = 0;
        int maxData = 0;
        for (byte[] block : dataBlocks) maxData = Math.max(maxData, block.length);
        for (int i = 0; i < maxData; i += 1) {
            for (byte[] block : dataBlocks) {
                if (i < block.length) out[at++] = block[i] & 0xFF;
            }
        }
        for (int i = 0; i < ecc; i += 1) {
            for (byte[] block : ecBlocks) out[at++] = block[i] & 0xFF;
        }
        return out;
    }

    private static int[] generator(int degree) {
        int[] poly = new int[degree];
        poly[degree - 1] = 1;
        int root = 1;
        for (int i = 0; i < degree; i += 1) {
            for (int j = 0; j < degree; j += 1) {
                poly[j] = mul(poly[j], root);
                if (j + 1 < degree) poly[j] ^= poly[j + 1];
            }
            root = mul(root, 2);
        }
        return poly;
    }

    private static byte[] remainder(byte[] data, int[] gen) {
        byte[] result = new byte[gen.length];
        for (byte value : data) {
            int factor = (value & 0xFF) ^ result[0];
            System.arraycopy(result, 1, result, 0, result.length - 1);
            result[result.length - 1] = 0;
            for (int i = 0; i < gen.length; i += 1) result[i] ^= (byte) mul(gen[i], factor);
        }
        return result;
    }

    private static int mul(int a, int b) {
        int result = 0;
        int x = a & 0xFF;
        int y = b & 0xFF;
        while (y != 0) {
            if ((y & 1) != 0) result ^= x;
            x <<= 1;
            if ((x & 0x100) != 0) x ^= 0x11D;
            y >>>= 1;
        }
        return result & 0xFF;
    }

    // --------------------------------------------------------------- drawing

    private static void drawFunctionPatterns(boolean[][] grid, boolean[][] reserved, int version) {
        int size = grid.length;
        for (int i = 0; i < size; i += 1) {
            set(grid, reserved, i, 6, i % 2 == 0);
            set(grid, reserved, 6, i, i % 2 == 0);
        }
        finder(grid, reserved, 0, 0);
        finder(grid, reserved, size - 7, 0);
        finder(grid, reserved, 0, size - 7);

        int[] points = ALIGNMENT[version - 1];
        for (int y : points) {
            for (int x : points) {
                if (reserved[y][x]) continue;
                for (int dy = -2; dy <= 2; dy += 1) {
                    for (int dx = -2; dx <= 2; dx += 1) {
                        boolean dark = Math.max(Math.abs(dx), Math.abs(dy)) != 1;
                        set(grid, reserved, x + dx, y + dy, dark);
                    }
                }
            }
        }
        // Reserve the format areas and the dark module before data placement.
        for (int i = 0; i < 9; i += 1) {
            reserve(reserved, 8, i);
            reserve(reserved, i, 8);
        }
        for (int i = 0; i < 8; i += 1) {
            reserve(reserved, 8, size - 1 - i);
            reserve(reserved, size - 1 - i, 8);
        }
        set(grid, reserved, 8, size - 8, true);
        if (version >= 7) {
            for (int i = 0; i < 6; i += 1) {
                for (int j = 0; j < 3; j += 1) {
                    reserve(reserved, size - 11 + j, i);
                    reserve(reserved, i, size - 11 + j);
                }
            }
        }
    }

    private static void finder(boolean[][] grid, boolean[][] reserved, int x, int y) {
        for (int dy = -1; dy <= 7; dy += 1) {
            for (int dx = -1; dx <= 7; dx += 1) {
                int px = x + dx;
                int py = y + dy;
                if (px < 0 || py < 0 || px >= grid.length || py >= grid.length) continue;
                boolean dark = dx >= 0 && dx <= 6 && dy >= 0 && dy <= 6
                        && (dx == 0 || dx == 6 || dy == 0 || dy == 6
                        || (dx >= 2 && dx <= 4 && dy >= 2 && dy <= 4));
                set(grid, reserved, px, py, dark);
            }
        }
    }

    private static void set(boolean[][] grid, boolean[][] reserved, int x, int y, boolean dark) {
        grid[y][x] = dark;
        reserved[y][x] = true;
    }

    private static void reserve(boolean[][] reserved, int x, int y) {
        if (x >= 0 && y >= 0 && y < reserved.length && x < reserved.length) {
            reserved[y][x] = true;
        }
    }

    private static void placeData(boolean[][] grid, boolean[][] reserved, int[] data) {
        int size = grid.length;
        int bit = 0;
        boolean upward = true;
        for (int right = size - 1; right >= 1; right -= 2) {
            if (right == 6) right = 5;
            for (int step = 0; step < size; step += 1) {
                int y = upward ? size - 1 - step : step;
                for (int i = 0; i < 2; i += 1) {
                    int x = right - i;
                    if (reserved[y][x]) continue;
                    boolean dark = bit < data.length * 8
                            && ((data[bit >> 3] >>> (7 - (bit & 7))) & 1) != 0;
                    grid[y][x] = dark;
                    bit += 1;
                }
            }
            upward = !upward;
        }
    }

    private static boolean[][] copy(boolean[][] source) {
        boolean[][] out = new boolean[source.length][source.length];
        for (int y = 0; y < source.length; y += 1) {
            System.arraycopy(source[y], 0, out[y], 0, source.length);
        }
        return out;
    }

    private static void applyMask(boolean[][] grid, boolean[][] reserved, int mask) {
        int size = grid.length;
        for (int y = 0; y < size; y += 1) {
            for (int x = 0; x < size; x += 1) {
                if (reserved[y][x]) continue;
                boolean flip;
                switch (mask) {
                    case 0: flip = (x + y) % 2 == 0; break;
                    case 1: flip = y % 2 == 0; break;
                    case 2: flip = x % 3 == 0; break;
                    case 3: flip = (x + y) % 3 == 0; break;
                    case 4: flip = (y / 2 + x / 3) % 2 == 0; break;
                    case 5: flip = (x * y) % 2 + (x * y) % 3 == 0; break;
                    case 6: flip = ((x * y) % 2 + (x * y) % 3) % 2 == 0; break;
                    default: flip = ((x + y) % 2 + (x * y) % 3) % 2 == 0; break;
                }
                if (flip) grid[y][x] = !grid[y][x];
            }
        }
    }

    private static void drawFormat(boolean[][] grid, int level, int mask) {
        int size = grid.length;
        int data = ((level == L ? 1 : 0) << 3) | mask;
        int value = data << 10;
        int poly = 0x537;
        for (int i = 14; i >= 10; i -= 1) {
            if (((value >>> i) & 1) != 0) value ^= poly << (i - 10);
        }
        int bits = ((data << 10) | value) ^ 0x5412;

        // One copy wraps the top-left finder, the other splits along row/column 8.
        for (int i = 0; i < 15; i += 1) {
            boolean dark = ((bits >>> i) & 1) != 0;
            int y = i < 6 ? i : (i < 8 ? i + 1 : size - 15 + i);
            grid[y][8] = dark;
            int x = i < 8 ? size - 1 - i : (i == 8 ? 7 : 15 - i - 1);
            grid[8][x] = dark;
        }
        grid[size - 8][8] = true;
    }

    private static void drawVersion(boolean[][] grid, int version) {
        int value = version << 12;
        int poly = 0x1F25;
        for (int i = 17; i >= 12; i -= 1) {
            if (((value >>> i) & 1) != 0) value ^= poly << (i - 12);
        }
        int bits = (version << 12) | value;
        int size = grid.length;
        for (int i = 0; i < 18; i += 1) {
            boolean dark = ((bits >>> i) & 1) != 0;
            int a = size - 11 + i % 3;
            int b = i / 3;
            grid[b][a] = dark;
            grid[a][b] = dark;
        }
    }

    // -------------------------------------------------------------- penalty

    private static int penalty(boolean[][] grid) {
        int size = grid.length;
        int score = 0;
        for (int y = 0; y < size; y += 1) score += linePenalty(grid, y, true);
        for (int x = 0; x < size; x += 1) score += linePenalty(grid, x, false);

        for (int y = 0; y < size - 1; y += 1) {
            for (int x = 0; x < size - 1; x += 1) {
                boolean v = grid[y][x];
                if (v == grid[y][x + 1] && v == grid[y + 1][x] && v == grid[y + 1][x + 1]) {
                    score += 3;
                }
            }
        }
        int dark = 0;
        for (boolean[] row : grid) {
            for (boolean cell : row) if (cell) dark += 1;
        }
        int percent = dark * 100 / (size * size);
        score += Math.abs(percent - 50) / 5 * 10;
        return score;
    }

    private static int linePenalty(boolean[][] grid, int index, boolean horizontal) {
        int size = grid.length;
        int score = 0;
        int run = 1;
        boolean last = horizontal ? grid[index][0] : grid[0][index];
        for (int i = 1; i < size; i += 1) {
            boolean value = horizontal ? grid[index][i] : grid[i][index];
            if (value == last) {
                run += 1;
            } else {
                if (run >= 5) score += 3 + (run - 5);
                run = 1;
                last = value;
            }
        }
        if (run >= 5) score += 3 + (run - 5);
        return score;
    }
}
