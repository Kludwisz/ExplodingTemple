import java.util.Arrays;

/*
Where a pillager outpost puts its iron golems, the same as OutpostGenerator#generateSuperflatUnchecked followed by
getIronGolems, without building any pieces. The outpost is a base plate with a watchtower and up to three feature
plates, and each feature plate gets one feature, which is the golem's cage 1 time in 12. Those pieces are placed
with the same random calls and collision checks as the generator, then it stops: the features themselves only
shuffle their jigsaw blocks, which changes nothing.
 */
public final class OutpostGolems {
    private static final long MULTIPLIER = 0x5DEECE66DL;
    private static final long ADDEND = 0xBL;
    private static final long MASK = (1L << 48) - 1;
    private static final int MAX_DEPTH = 7;

    // directions: the horizontal ones in clockwise order, so a rotation by r clockwise quarter turns adds r
    private static final int NORTH = 0, EAST = 1, SOUTH = 2, WEST = 3, UP = 4, DOWN = 5;
    private static final int[][] VECTOR = {{0, 0, -1}, {1, 0, 0}, {0, 0, 1}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}};

    // pools
    private static final int BASE_PLATES = 0, TOWERS = 1, FEATURE_PLATES = 2, FEATURES = 3, EMPTY_POOL = 4;
    private static final boolean[] POOL_RIGID = {true, true, false, true, true};
    // the most a piece from each pool reaches above its jigsaw block, for the expansion hack
    private static final int[] POOL_Y_MAX = {30, 21, 4, 4, -1};

    // templates, EMPTY_PIECE ends the feature list
    private static final int BASE_PLATE = 0, WATCHTOWER = 1, FEATURE_PLATE = 2, CAGE_1 = 3, CAGE_2 = 4, LOGS = 5,
            TENT_1 = 6, TENT_2 = 7, TARGETS = 8, EMPTY_PIECE = 9;
    private static final int[][] SIZE = {
            {16, 30, 16}, {15, 21, 15}, {16, 4, 32}, {7, 4, 7}, {7, 4, 7}, {6, 3, 7}, {6, 4, 7}, {6, 4, 7}, {3, 3, 7}
    };
    private static final int[][] POOL_TEMPLATES = {
            {BASE_PLATE}, {WATCHTOWER}, {FEATURE_PLATE},
            {CAGE_1, CAGE_2, LOGS, TENT_1, TENT_2, TARGETS, EMPTY_PIECE, EMPTY_PIECE, EMPTY_PIECE, EMPTY_PIECE,
                    EMPTY_PIECE, EMPTY_PIECE},
            {}
    };

    // jigsaw blocks of each template: pool, name (0 plate_entry, 1 entrance, 2 feature), facing, x, y, z
    private static final int PLATE_ENTRY = 0, ENTRANCE = 1, FEATURE = 2;
    private static final int[][][] JIGSAWS = {
            {{FEATURE_PLATES, PLATE_ENTRY, WEST, 0, 0, 7}, {FEATURE_PLATES, PLATE_ENTRY, SOUTH, 7, 0, 15},
                    {FEATURE_PLATES, PLATE_ENTRY, EAST, 15, 0, 8}, {TOWERS, ENTRANCE, NORTH, 7, 1, 14},
                    {TOWERS, ENTRANCE, NORTH, 8, 1, 14}},
            {{EMPTY_POOL, ENTRANCE, SOUTH, 7, 1, 13}},
            {{FEATURES, FEATURE, UP, 7, 0, 23}, {EMPTY_POOL, PLATE_ENTRY, EAST, 15, 0, 7}},
            feature(1, 1, 1, 6, 4, 3, 6, 1, 6, 6),
            feature(1, 1, 1, 6, 4, 3, 6, 1, 6, 6),
            feature(0, 0, 0, 6, 5, 0, 5, 6),
            feature(0, 1, 0, 5, 5, 1, 5, 5),
            feature(0, 1, 0, 5, 5, 1, 5, 5),
            feature(0, 0, 0, 6, 1, 3, 2, 0, 2, 6),
            {}
    };

    private static int[][] feature(int... xz) {
        int[][] jigsaws = new int[xz.length / 2][];
        for (int i = 0; i < jigsaws.length; i++) {
            jigsaws[i] = new int[]{EMPTY_POOL, FEATURE, DOWN, xz[2 * i], 0, xz[2 * i + 1]};
        }
        return jigsaws;
    }

    private long seed;

    // the pieces placed so far, the base plate's children in the order they were placed
    private final int[] template = new int[8], rotation = new int[8], depth = new int[8], shapeOf = new int[8];
    private final boolean[] rigid = new boolean[8];
    private final int[][] pos = new int[8][3], box = new int[8][6];
    private int pieceCount;

    // collision shapes: bounds (min inclusive, max exclusive) and the boxes placed in them (max exclusive)
    private final int[][] shapeBounds = new int[8][6];
    private final int[][] shapeBoxes = new int[8][6 * 8];
    private final int[] shapeBoxCount = new int[8];
    private int shapeCount;

    // cage 1 golems found: minX, minZ, maxX, maxZ
    private final int[] golems = new int[4 * 3];
    private int golemCount;

    // scratch
    private final int[] rotations = new int[4];
    private final int[] templates = new int[12];
    private final int[][] jigsaws = new int[5][6];
    private final int[][] childJigsaws = new int[5][6];

    /** Lays out the outpost starting in the chunk and returns how many golems it has, see {@link #golem}. */
    public int layOut(long structureSeed, int chunkX, int chunkZ) {
        startLayout(structureSeed, chunkX, chunkZ);
        // breadth first: the base plate, then its children in order. Only the feature plates' children matter.
        for (int i = 0; i < pieceCount; i++) {
            if (template[i] == BASE_PLATE || template[i] == FEATURE_PLATE) {
                tryPlacing(i);
            }
        }
        return golemCount;
    }

    // places the base plate, piece 0
    private void startLayout(long structureSeed, int chunkX, int chunkZ) {
        pieceCount = 0;
        shapeCount = 0;
        golemCount = 0;

        // ChunkRand#setCarverSeed
        setSeed(structureSeed);
        long a = nextLong();
        long b = nextLong();
        setSeed((long) chunkX * a ^ (long) chunkZ * b ^ structureSeed);

        int baseRotation = nextInt(4);
        nextInt(1); // the base plate pool has one template
        int[] baseBox = boundingBox(chunkX << 4, 0, chunkZ << 4, baseRotation, SIZE[BASE_PLATE]);
        int centerX = (baseBox[0] + baseBox[3]) / 2;
        int centerZ = (baseBox[2] + baseBox[5]) / 2;
        int shiftY = 64 - (baseBox[1] + 1);
        baseBox[1] += shiftY;
        baseBox[4] += shiftY;

        int global = newShape(centerX - 80, 64 - 80, centerZ - 80, centerX + 80 + 1, 64 + 80 + 1, centerZ + 80 + 1);
        addBox(global, baseBox[0], baseBox[1], baseBox[2], baseBox[3] + 1, baseBox[4] + 1, baseBox[5] + 1);
        int base = addPiece(BASE_PLATE, chunkX << 4, shiftY, chunkZ << 4, baseBox, baseRotation, true, 0);
        shapeOf[base] = global;
    }

    /** minX, minZ, maxX, maxZ of the golem's hitbox blocks */
    public int golem(int index, int coordinate) {
        return golems[4 * index + coordinate];
    }

    /**
     * Whether the outpost starting in the chunk has a golem whose hitbox blocks are all within x0..x1, z0..z1. Same
     * as looking through {@link #layOut}, but only lays out the feature plates up to the last one close enough.
     */
    public boolean hasGolemIn(long structureSeed, int chunkX, int chunkZ, int x0, int z0, int x1, int z1) {
        startLayout(structureSeed, chunkX, chunkZ);
        tryPlacing(0);
        // a cage's golem is at most 3 blocks from the block above the plate's feature jigsaw it hangs from
        int last = -1;
        for (int i = 1; i < pieceCount; i++) {
            if (template[i] != FEATURE_PLATE) continue;
            int[] jigsaw = JIGSAWS[FEATURE_PLATE][0];
            int x = rotateX(jigsaw[3], jigsaw[5], rotation[i]) + pos[i][0];
            int z = rotateZ(jigsaw[3], jigsaw[5], rotation[i]) + pos[i][2];
            if (x + 3 >= x0 && x - 3 <= x1 && z + 3 >= z0 && z - 3 <= z1) last = i;
        }
        for (int i = 1; i <= last; i++) {
            if (template[i] != FEATURE_PLATE) continue;
            int before = golemCount;
            tryPlacing(i);
            if (golemCount > before) {
                int g = 4 * before;
                if (golems[g] >= x0 && golems[g + 2] <= x1 && golems[g + 1] >= z0 && golems[g + 3] <= z1) return true;
            }
        }
        return false;
    }

    private void tryPlacing(int piece) {
        int pieceDepth = depth[piece];
        boolean isRigid = rigid[piece];
        int[] pieceBox = box[piece];
        int minY = pieceBox[1];
        int local = -1;

        int jigsawCount = rotatedJigsaws(template[piece], rotation[piece], pos[piece], jigsaws);
        shuffle(jigsaws, jigsawCount);
        for (int j = 0; j < jigsawCount; j++) {
            int[] jigsaw = jigsaws[j];
            int pool = jigsaw[0];
            int front = jigsaw[2];
            int relX = jigsaw[3] + VECTOR[front][0], relY = jigsaw[4] + VECTOR[front][1], relZ = jigsaw[5] + VECTOR[front][2];
            int y = jigsaw[4] - minY;
            int state = -1;

            int shape;
            if (contains(pieceBox, relX, relY, relZ)) {
                if (local < 0) {
                    local = newShape(pieceBox[0], pieceBox[1], pieceBox[2], pieceBox[3] + 1, pieceBox[4] + 1, pieceBox[5] + 1);
                }
                shape = local;
            } else {
                shape = shapeOf[piece];
            }

            // the pool's templates shuffled, then the empty fallback pool's (which has none)
            int templateCount = 0;
            if (pieceDepth != MAX_DEPTH && POOL_TEMPLATES[pool].length > 0) {
                templateCount = POOL_TEMPLATES[pool].length;
                System.arraycopy(POOL_TEMPLATES[pool], 0, templates, 0, templateCount);
                shuffle(templates, templateCount);
                advance();
            }

            placed:
            for (int t = 0; t < templateCount && templates[t] != EMPTY_PIECE; t++) {
                int child = templates[t];
                int[] size = SIZE[child];
                shuffleRotations();
                for (int r = 0; r < 4; r++) {
                    int childRotation = rotations[r];
                    int[] childBox = boundingBox(0, 0, 0, childRotation, size);
                    int childJigsawCount = rotatedJigsaws(child, childRotation, null, childJigsaws);
                    shuffle(childJigsaws, childJigsawCount);
                    int expansion = childBox[4] - childBox[1] + 1 <= 16 ? maxHeight(childJigsaws, childJigsawCount, childBox) : 0;

                    for (int k = 0; k < childJigsawCount; k++) {
                        int[] childJigsaw = childJigsaws[k];
                        if (front != opposite(childJigsaw[2]) || jigsaw[1] != childJigsaw[1]) continue;
                        int anchorX = relX - childJigsaw[3], anchorY = relY - childJigsaw[4], anchorZ = relZ - childJigsaw[5];
                        int[] placedBox = boundingBox(anchorX, anchorY, anchorZ, childRotation, size);
                        int k1 = childJigsaw[4];
                        int i2;
                        if (isRigid && POOL_RIGID[pool]) {
                            i2 = minY + y - k1 + VECTOR[front][1];
                        } else {
                            if (state == -1) state = 64;
                            i2 = state - k1;
                        }
                        int shiftY = i2 - placedBox[1];
                        placedBox[1] += shiftY;
                        placedBox[4] += shiftY;
                        if (expansion > 0) {
                            placedBox[4] = placedBox[1] + Math.max(expansion + 1, placedBox[4] - placedBox[1]);
                        }
                        if (!fits(shape, placedBox)) continue;

                        addBox(shape, placedBox[0], placedBox[1], placedBox[2], placedBox[3] + 1, placedBox[4] + 1, placedBox[5] + 1);
                        if (pieceDepth + 1 > MAX_DEPTH) break placed;
                        int placed = addPiece(child, anchorX, anchorY + shiftY, anchorZ, placedBox, childRotation,
                                POOL_RIGID[pool], pieceDepth + 1);
                        shapeOf[placed] = shape;
                        if (child == CAGE_1) {
                            addGolem(placed);
                        }
                        break placed;
                    }
                }
            }
        }
    }

    // OutpostGenerator#getIronGolems: blocks (3, 0, 3) to (4, 2, 4) of the cage
    private void addGolem(int cage) {
        int[] p = pos[cage];
        int r = rotation[cage];
        int x1 = rotateX(3, 3, r) + p[0], z1 = rotateZ(3, 3, r) + p[2];
        int x2 = rotateX(4, 4, r) + p[0], z2 = rotateZ(4, 4, r) + p[2];
        golems[4 * golemCount] = Math.min(x1, x2);
        golems[4 * golemCount + 1] = Math.min(z1, z2);
        golems[4 * golemCount + 2] = Math.max(x1, x2);
        golems[4 * golemCount + 3] = Math.max(z1, z2);
        golemCount++;
    }

    private int addPiece(int t, int x, int y, int z, int[] pieceBox, int r, boolean isRigid, int d) {
        int i = pieceCount++;
        template[i] = t;
        pos[i][0] = x;
        pos[i][1] = y;
        pos[i][2] = z;
        System.arraycopy(pieceBox, 0, box[i], 0, 6);
        rotation[i] = r;
        rigid[i] = isRigid;
        depth[i] = d;
        return i;
    }

    private int newShape(int x0, int y0, int z0, int x1, int y1, int z1) {
        int[] bounds = shapeBounds[shapeCount];
        bounds[0] = x0;
        bounds[1] = y0;
        bounds[2] = z0;
        bounds[3] = x1;
        bounds[4] = y1;
        bounds[5] = z1;
        shapeBoxCount[shapeCount] = 0;
        return shapeCount++;
    }

    private void addBox(int shape, int x0, int y0, int z0, int x1, int y1, int z1) {
        int[] boxes = shapeBoxes[shape];
        int i = 6 * shapeBoxCount[shape]++;
        boxes[i] = x0;
        boxes[i + 1] = y0;
        boxes[i + 2] = z0;
        boxes[i + 3] = x1;
        boxes[i + 4] = y1;
        boxes[i + 5] = z1;
    }

    // OutpostGenerator.Assembler#isNotEmpty
    private boolean fits(int shape, int[] b) {
        int[] bounds = shapeBounds[shape];
        if (b[0] < bounds[0] || b[1] < bounds[1] || b[2] < bounds[2] || b[3] >= bounds[3] || b[4] >= bounds[4] || b[5] >= bounds[5]) {
            return false;
        }
        int[] boxes = shapeBoxes[shape];
        for (int i = 0; i < 6 * shapeBoxCount[shape]; i += 6) {
            if (b[3] >= boxes[i] && b[0] < boxes[i + 3] && b[5] >= boxes[i + 2] && b[2] < boxes[i + 5]
                    && b[4] >= boxes[i + 1] && b[1] < boxes[i + 4]) {
                return false;
            }
        }
        return true;
    }

    private static boolean contains(int[] b, int x, int y, int z) {
        return x >= b[0] && x <= b[3] && z >= b[2] && z <= b[5] && y >= b[1] && y <= b[4];
    }

    // OutpostGenerator.Assembler#maxHeightOfList
    private static int maxHeight(int[][] list, int count, int[] b) {
        int max = 0;
        for (int i = 0; i < count; i++) {
            int[] jigsaw = list[i];
            int front = jigsaw[2];
            if (contains(b, jigsaw[3] + VECTOR[front][0], jigsaw[4] + VECTOR[front][1], jigsaw[5] + VECTOR[front][2])) {
                max = Math.max(POOL_Y_MAX[jigsaw[0]], max);
            }
        }
        return max;
    }

    // the template's jigsaw blocks rotated around the origin and moved to pos (null for the origin)
    private static int rotatedJigsaws(int t, int r, int[] offset, int[][] out) {
        int[][] list = JIGSAWS[t];
        for (int i = 0; i < list.length; i++) {
            int[] src = list[i];
            int[] dst = out[i];
            dst[0] = src[0];
            dst[1] = src[1];
            dst[2] = rotate(src[2], r);
            dst[3] = rotateX(src[3], src[5], r) + (offset == null ? 0 : offset[0]);
            dst[4] = src[4] + (offset == null ? 0 : offset[1]);
            dst[5] = rotateZ(src[3], src[5], r) + (offset == null ? 0 : offset[2]);
        }
        return list.length;
    }

    // BlockBox#getBoundingBox with the origin as pivot, returns minX, minY, minZ, maxX, maxY, maxZ
    private static int[] boundingBox(int x, int y, int z, int r, int[] size) {
        int sx = size[0] - 1, sy = size[1] - 1, sz = size[2] - 1;
        return switch (r) {
            case 1 -> new int[]{x - sz, y, z, x, y + sy, z + sx};
            case 2 -> new int[]{x - sx, y, z - sz, x, y + sy, z};
            case 3 -> new int[]{x, y, z - sx, x + sz, y + sy, z};
            default -> new int[]{x, y, z, x + sx, y + sy, z + sz};
        };
    }

    // BlockRotation#rotate around the origin: NONE, CLOCKWISE_90, CLOCKWISE_180, COUNTERCLOCKWISE_90
    private static int rotateX(int x, int z, int r) {
        return switch (r) {
            case 1 -> -z;
            case 2 -> -x;
            case 3 -> z;
            default -> x;
        };
    }

    private static int rotateZ(int x, int z, int r) {
        return switch (r) {
            case 1 -> x;
            case 2 -> -z;
            case 3 -> -x;
            default -> z;
        };
    }

    private static int rotate(int direction, int r) {
        return direction >= UP ? direction : (direction + r) & 3;
    }

    private static int opposite(int direction) {
        return direction >= UP ? UP + DOWN - direction : (direction + 2) & 3;
    }

    // BlockRotation#getShuffled
    private void shuffleRotations() {
        for (int i = 0; i < 4; i++) rotations[i] = i;
        for (int i = 4; i > 1; i--) {
            int j = nextInt(i);
            int tmp = rotations[i - 1];
            rotations[i - 1] = rotations[j];
            rotations[j] = tmp;
        }
    }

    // JRand#shuffle
    private void shuffle(int[] list, int size) {
        for (int i = size; i > 1; i--) {
            int j = nextInt(i);
            int tmp = list[i - 1];
            list[i - 1] = list[j];
            list[j] = tmp;
        }
    }

    private void shuffle(int[][] list, int size) {
        for (int i = size; i > 1; i--) {
            int j = nextInt(i);
            int[] tmp = list[i - 1];
            list[i - 1] = list[j];
            list[j] = tmp;
        }
    }

    private void setSeed(long s) {
        seed = (s ^ MULTIPLIER) & MASK;
    }

    private void advance() {
        seed = seed * MULTIPLIER + ADDEND & MASK;
    }

    private int next(int bits) {
        seed = seed * MULTIPLIER + ADDEND & MASK;
        return (int) (seed >>> 48 - bits);
    }

    private long nextLong() {
        return ((long) next(32) << 32) + next(32);
    }

    private int nextInt(int bound) {
        if ((bound & -bound) == bound) {
            return (int) (bound * (long) next(31) >> 31);
        }
        int bits, value;
        do {
            bits = next(31);
            value = bits % bound;
        } while (bits - value + (bound - 1) < 0);
        return value;
    }

    @Override
    public String toString() {
        return Arrays.toString(Arrays.copyOf(golems, 4 * golemCount));
    }
}
