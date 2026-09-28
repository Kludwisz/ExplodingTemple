package generator;

import com.seedfinding.mccore.block.Block;
import com.seedfinding.mccore.block.Blocks;
import com.seedfinding.mccore.util.pos.CPos;
import com.seedfinding.mcterrain.TerrainGenerator;

import java.util.BitSet;
import java.util.Random;
import java.util.function.IntBinaryOperator;

/*
A port of the 1.16.1 cave and ravine carvers (WorldCarver, CaveWorldCarver, CanyonWorldCarver and the underwater
ones ocean chunks get). Unlike cubiomes it knows the terrain, which matters next to rivers and oceans: the game
skips every sphere of a cave whose box touches water, and in ocean chunks the underwater carvers fill everything
they carve below sea level with water.
 */
public final class VanillaCarver {
    private static final int SEA_LEVEL = 63;
    private static final int GEN_HEIGHT = 256;
    private static final int RANGE = (4 * 2 - 1) * 16;
    private static final float CAVE_CHANCE = 0.14285715f;
    private static final float OCEAN_CAVE_CHANCE = 0.06666667f;
    private static final float CANYON_CHANCE = 0.02f;
    // Mth.sin and Mth.cos use a lookup table, which the carvers have to match exactly
    private static final float[] SIN = new float[65536];

    static {
        for (int i = 0; i < SIN.length; i++) {
            SIN[i] = (float) Math.sin(i * Math.PI * 2.0 / 65536.0);
        }
    }

    private interface Skip {
        boolean skip(double dx, double dy, double dz, int y);
    }

    private static final Skip CAVE_SKIP = (dx, dy, dz, y) -> dy <= -0.7 || dx * dx + dy * dy + dz * dz >= 1.0;

    private final long seed;
    private final CarveRegion region;
    private final IntBinaryOperator carverBiome;
    // the terrain before carving: per column, which blocks are solid and which are water
    private final long[][] stone;
    private final long[][] water;
    private final int minX;
    private final int minZ;
    private final int size;

    private VanillaCarver(long seed, CarveRegion region, TerrainGenerator terrain, IntBinaryOperator carverBiome) {
        this.seed = seed;
        this.region = region;
        this.carverBiome = carverBiome;
        this.minX = region.getMinChunkX() << 4;
        this.minZ = region.getMinChunkZ() << 4;
        this.size = region.getWidth() << 4;
        this.stone = new long[size * size][];
        this.water = new long[size * size][];
        for (int x = 0; x < size; x++) {
            for (int z = 0; z < size; z++) {
                Block[] column = terrain.getColumnAt(minX + x, minZ + z);
                long[] solid = new long[4];
                long[] wet = new long[4];
                for (int y = 0; y < GEN_HEIGHT && y < column.length; y++) {
                    if (column[y].getId() == Blocks.WATER.getId()) {
                        wet[y >> 6] |= 1L << y;
                        region.setWater(minX + x, y, minZ + z);
                    } else if (column[y].getId() != Blocks.AIR.getId()) {
                        solid[y >> 6] |= 1L << y;
                    }
                }
                stone[x * size + z] = solid;
                water[x * size + z] = wet;
            }
        }
    }

    /*
    Carves a square of chunks around center for the world seed the terrain generator was made for. carverBiome
    gives the biome at the corner of each chunk, the one 1.16 picks the carvers of that chunk by.
     */
    public static CarveRegion carve(long worldSeed, CPos center, int chunkRadius, TerrainGenerator terrain,
                                    IntBinaryOperator carverBiome) {
        CarveRegion region = new CarveRegion(center, chunkRadius);
        VanillaCarver carver = new VanillaCarver(worldSeed, region, terrain, carverBiome);
        for (int i = 0; i < region.getWidth(); i++) {
            for (int j = 0; j < region.getWidth(); j++) {
                carver.carveChunk(region.getMinChunkX() + i, region.getMinChunkZ() + j);
            }
        }
        return region;
    }

    // ChunkGenerator#applyCarvers for the AIR and then the LIQUID step
    private void carveChunk(int chunkX, int chunkZ) {
        boolean ocean = CubiomesBiomeChecker.isOcean(carverBiome.applyAsInt(chunkX, chunkZ));
        Random random = new Random();
        BitSet mask = new BitSet(16 * 16 * GEN_HEIGHT);
        for (int x = chunkX - 8; x <= chunkX + 8; x++) {
            for (int z = chunkZ - 8; z <= chunkZ + 8; z++) {
                setLargeFeatureSeed(random, seed, x, z);
                if (random.nextFloat() <= (ocean ? OCEAN_CAVE_CHANCE : CAVE_CHANCE)) {
                    cave(random, x, z, chunkX, chunkZ, mask, false);
                }
                setLargeFeatureSeed(random, seed + 1, x, z);
                if (random.nextFloat() <= CANYON_CHANCE) {
                    canyon(random, x, z, chunkX, chunkZ, mask, false);
                }
            }
        }
        if (!ocean) return;

        mask = new BitSet(16 * 16 * GEN_HEIGHT);
        for (int x = chunkX - 8; x <= chunkX + 8; x++) {
            for (int z = chunkZ - 8; z <= chunkZ + 8; z++) {
                setLargeFeatureSeed(random, seed, x, z);
                if (random.nextFloat() <= CANYON_CHANCE) {
                    canyon(random, x, z, chunkX, chunkZ, mask, true);
                }
                setLargeFeatureSeed(random, seed + 1, x, z);
                if (random.nextFloat() <= OCEAN_CAVE_CHANCE) {
                    cave(random, x, z, chunkX, chunkZ, mask, true);
                }
            }
        }
    }

    private static void setLargeFeatureSeed(Random random, long seed, int x, int z) {
        random.setSeed(seed);
        long a = random.nextLong();
        long b = random.nextLong();
        random.setSeed((long) x * a ^ (long) z * b ^ seed);
    }

    // CaveWorldCarver#carve
    private void cave(Random random, int startX, int startZ, int chunkX, int chunkZ, BitSet mask, boolean liquid) {
        int count = random.nextInt(random.nextInt(random.nextInt(15) + 1) + 1);
        for (int c = 0; c < count; c++) {
            double x = startX * 16 + random.nextInt(16);
            double y = random.nextInt(random.nextInt(120) + 8);
            double z = startZ * 16 + random.nextInt(16);
            int tunnels = 1;
            if (random.nextInt(4) == 0) {
                float radius = 1.0f + random.nextFloat() * 6.0f;
                long seed = random.nextLong();
                double horizontalRadius = 1.5 + sin(1.5707964f) * radius;
                carveSphere(seed, chunkX, chunkZ, x + 1.0, y, z, horizontalRadius, horizontalRadius * 0.5, mask, CAVE_SKIP, liquid);
                tunnels += random.nextInt(4);
            }
            for (int t = 0; t < tunnels; t++) {
                float yaw = random.nextFloat() * ((float) Math.PI * 2);
                float pitch = (random.nextFloat() - 0.5f) / 4.0f;
                float thickness = random.nextFloat() * 2.0f + random.nextFloat();
                if (random.nextInt(10) == 0) {
                    thickness *= random.nextFloat() * random.nextFloat() * 3.0f + 1.0f;
                }
                int branchCount = RANGE - random.nextInt(RANGE / 4);
                tunnel(random.nextLong(), chunkX, chunkZ, x, y, z, thickness, yaw, pitch, 0, branchCount, 1.0, mask, liquid);
            }
        }
    }

    // CaveWorldCarver#genTunnel
    private void tunnel(long seed, int chunkX, int chunkZ, double x, double y, double z, float thickness, float yaw,
                        float pitch, int branchIndex, int branchCount, double yScale, BitSet mask, boolean liquid) {
        Random random = new Random(seed);
        int split = random.nextInt(branchCount / 2) + branchCount / 4;
        boolean steep = random.nextInt(6) == 0;
        float yawChange = 0.0f;
        float pitchChange = 0.0f;
        for (int i = branchIndex; i < branchCount; i++) {
            double horizontalRadius = 1.5 + (double) (sin((float) Math.PI * (float) i / (float) branchCount) * thickness);
            double verticalRadius = horizontalRadius * yScale;
            float horizontal = cos(pitch);
            x += cos(yaw) * horizontal;
            y += sin(pitch);
            z += sin(yaw) * horizontal;
            pitch *= steep ? 0.92f : 0.7f;
            pitch += pitchChange * 0.1f;
            yaw += yawChange * 0.1f;
            pitchChange *= 0.9f;
            yawChange *= 0.75f;
            pitchChange += (random.nextFloat() - random.nextFloat()) * random.nextFloat() * 2.0f;
            yawChange += (random.nextFloat() - random.nextFloat()) * random.nextFloat() * 4.0f;
            if (i == split && thickness > 1.0f) {
                tunnel(random.nextLong(), chunkX, chunkZ, x, y, z, random.nextFloat() * 0.5f + 0.5f, yaw - 1.5707964f,
                        pitch / 3.0f, i, branchCount, 1.0, mask, liquid);
                tunnel(random.nextLong(), chunkX, chunkZ, x, y, z, random.nextFloat() * 0.5f + 0.5f, yaw + 1.5707964f,
                        pitch / 3.0f, i, branchCount, 1.0, mask, liquid);
                return;
            }
            if (random.nextInt(4) == 0) continue;
            if (!canReach(chunkX, chunkZ, x, z, i, branchCount, thickness)) return;
            carveSphere(seed, chunkX, chunkZ, x, y, z, horizontalRadius, verticalRadius, mask, CAVE_SKIP, liquid);
        }
    }

    // CanyonWorldCarver#carve and #genCanyon
    private void canyon(Random startRandom, int startX, int startZ, int chunkX, int chunkZ, BitSet mask, boolean liquid) {
        double x = startX * 16 + startRandom.nextInt(16);
        double y = startRandom.nextInt(startRandom.nextInt(40) + 8) + 20;
        double z = startZ * 16 + startRandom.nextInt(16);
        float yaw = startRandom.nextFloat() * ((float) Math.PI * 2);
        float pitch = (startRandom.nextFloat() - 0.5f) * 2.0f / 8.0f;
        float thickness = (startRandom.nextFloat() * 2.0f + startRandom.nextFloat()) * 2.0f;
        int branchCount = RANGE - startRandom.nextInt(RANGE / 4);
        long seed = startRandom.nextLong();

        Random random = new Random(seed);
        float[] widths = new float[GEN_HEIGHT];
        float width = 1.0f;
        for (int i = 0; i < GEN_HEIGHT; i++) {
            if (i == 0 || random.nextInt(3) == 0) {
                width = 1.0f + random.nextFloat() * random.nextFloat();
            }
            widths[i] = width * width;
        }
        Skip skip = (dx, dy, dz, blockY) -> (dx * dx + dz * dz) * (double) widths[blockY - 1] + dy * dy / 6.0 >= 1.0;

        float yawChange = 0.0f;
        float pitchChange = 0.0f;
        for (int i = 0; i < branchCount; i++) {
            double horizontalRadius = 1.5 + (double) (sin((float) i * (float) Math.PI / (float) branchCount) * thickness);
            double verticalRadius = horizontalRadius * 3.0;
            horizontalRadius *= (double) random.nextFloat() * 0.25 + 0.75;
            verticalRadius *= (double) random.nextFloat() * 0.25 + 0.75;
            float horizontal = cos(pitch);
            float vertical = sin(pitch);
            x += cos(yaw) * horizontal;
            y += vertical;
            z += sin(yaw) * horizontal;
            pitch *= 0.7f;
            pitch += pitchChange * 0.05f;
            yaw += yawChange * 0.05f;
            pitchChange *= 0.8f;
            yawChange *= 0.5f;
            pitchChange += (random.nextFloat() - random.nextFloat()) * random.nextFloat() * 2.0f;
            yawChange += (random.nextFloat() - random.nextFloat()) * random.nextFloat() * 4.0f;
            if (random.nextInt(4) == 0) continue;
            if (!canReach(chunkX, chunkZ, x, z, i, branchCount, thickness)) return;
            carveSphere(seed, chunkX, chunkZ, x, y, z, horizontalRadius, verticalRadius, mask, skip, liquid);
        }
    }

    private static boolean canReach(int chunkX, int chunkZ, double x, double z, int branch, int branchCount, float thickness) {
        double dx = x - (chunkX * 16 + 8);
        double dz = z - (chunkZ * 16 + 8);
        double remaining = branchCount - branch;
        double reach = thickness + 2.0f + 16.0f;
        return dx * dx + dz * dz - remaining * remaining <= reach * reach;
    }

    // WorldCarver#carveSphere, with carveBlock of the regular or the underwater carvers
    private void carveSphere(long seed, int chunkX, int chunkZ, double x, double y, double z, double horizontalRadius,
                             double verticalRadius, BitSet mask, Skip skip, boolean liquid) {
        double midX = chunkX * 16 + 8;
        double midZ = chunkZ * 16 + 8;
        if (x < midX - 16.0 - horizontalRadius * 2.0 || z < midZ - 16.0 - horizontalRadius * 2.0
                || x > midX + 16.0 + horizontalRadius * 2.0 || z > midZ + 16.0 + horizontalRadius * 2.0) {
            return;
        }
        int minX = Math.max(floor(x - horizontalRadius) - chunkX * 16 - 1, 0);
        int maxX = Math.min(floor(x + horizontalRadius) - chunkX * 16 + 1, 16);
        int minY = Math.max(floor(y - verticalRadius) - 1, 1);
        int maxY = Math.min(floor(y + verticalRadius) + 1, GEN_HEIGHT - 8);
        int minZ = Math.max(floor(z - horizontalRadius) - chunkZ * 16 - 1, 0);
        int maxZ = Math.min(floor(z + horizontalRadius) - chunkZ * 16 + 1, 16);
        if (!liquid && hasWater(chunkX, chunkZ, minX, maxX, minY, maxY, minZ, maxZ)) {
            return;
        }
        for (int i = minX; i < maxX; i++) {
            int blockX = i + chunkX * 16;
            double dx = (blockX + 0.5 - x) / horizontalRadius;
            for (int j = minZ; j < maxZ; j++) {
                int blockZ = j + chunkZ * 16;
                double dz = (blockZ + 0.5 - z) / horizontalRadius;
                if (dx * dx + dz * dz >= 1.0) continue;
                for (int k = maxY; k > minY; k--) {
                    double dy = (k - 0.5 - y) / verticalRadius;
                    if (skip.skip(dx, dy, dz, k)) continue;
                    if (liquid && k >= SEA_LEVEL) continue;
                    int index = i | j << 4 | k << 8;
                    if (mask.get(index)) continue;
                    mask.set(index);
                    if (liquid) {
                        // everything but bedrock can be replaced, the obsidian and magma at Y=10 are solid
                        if (k == 10) region.setSolid(blockX, k, blockZ);
                        else if (k < 10) region.setCarved(blockX, k, blockZ);
                        else region.setWater(blockX, k, blockZ);
                    } else if (isReplaceable(blockX, k, blockZ)) {
                        region.setCarved(blockX, k, blockZ);
                    }
                }
            }
        }
    }

    // WorldCarver#hasWater: water anywhere on the sides of the box, or in the layers just above and below it
    private boolean hasWater(int chunkX, int chunkZ, int minX, int maxX, int minY, int maxY, int minZ, int maxZ) {
        for (int i = minX; i < maxX; i++) {
            for (int j = minZ; j < maxZ; j++) {
                boolean edge = i == minX || i == maxX - 1 || j == minZ || j == maxZ - 1;
                for (int y = minY - 1; y <= maxY + 1; y++) {
                    if (isTerrainWater(i + chunkX * 16, y, j + chunkZ * 16)) return true;
                    if (y != maxY + 1 && !edge) y = maxY;
                }
            }
        }
        return false;
    }

    // stone, or the sand/sandstone/dirt the surface builder turns it into - except sand and gravel under water
    private boolean isReplaceable(int x, int y, int z) {
        return isTerrain(stone, x, y, z) && !isTerrainWater(x, y + 1, z) && !region.isCarved(x, y, z);
    }

    private boolean isTerrainWater(int x, int y, int z) {
        return isTerrain(water, x, y, z);
    }

    private boolean isTerrain(long[][] layer, int x, int y, int z) {
        int i = x - minX;
        int j = z - minZ;
        if (i < 0 || j < 0 || i >= size || j >= size || y < 0 || y >= GEN_HEIGHT) return false;
        return (layer[i * size + j][y >> 6] >>> y & 1L) != 0;
    }

    private static int floor(double d) {
        int i = (int) d;
        return d < i ? i - 1 : i;
    }

    private static float sin(float f) {
        return SIN[(int) (f * 10430.378f) & 0xFFFF];
    }

    private static float cos(float f) {
        return SIN[(int) (f * 10430.378f + 16384.0f) & 0xFFFF];
    }
}
