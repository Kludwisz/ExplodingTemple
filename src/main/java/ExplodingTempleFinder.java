import com.seedfinding.mcbiome.source.BiomeSource;
import com.seedfinding.mccore.rand.ChunkRand;
import com.seedfinding.mccore.rand.seed.RegionSeed;
import com.seedfinding.mccore.state.Dimension;
import com.seedfinding.mccore.util.block.BlockBox;
import com.seedfinding.mccore.util.pos.BPos;
import com.seedfinding.mccore.util.pos.CPos;
import com.seedfinding.mccore.version.MCVersion;
import com.seedfinding.mcfeature.misc.SpawnPoint;
import com.seedfinding.mcfeature.structure.DesertPyramid;
import com.seedfinding.mcfeature.structure.PillagerOutpost;
import com.seedfinding.mcmath.util.Mth;
import com.seedfinding.mcterrain.TerrainGenerator;
import com.seedfinding.mcterrain.terrain.OverworldTerrainGenerator;
import generator.CubiomesBiomeChecker;
import generator.CubiomesRavineGenerator;
import profotoce59.generator.OutpostGenerator;

import java.util.ArrayList;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

public class ExplodingTempleFinder {
    public static final AtomicInteger resultCount = new AtomicInteger(0);
    public static final AtomicLong structureSeedCount = new AtomicLong(0);

    // offsets from the outpost chunk to the golem's chunk that LayoutTest found, (0,-2) is ~4x rarer
    private static final int[][] GOLEM_OFFSETS = {
            {0, 1}, {-1, 1}, {1, 1}, {-2, 0}, {-2, 1}, {1, -1}, {-1, -2}, {0, -2}
    };
    private static final boolean[][] IS_GOLEM_OFFSET = new boolean[5][5];

    static {
        for (int[] offset : GOLEM_OFFSETS) {
            IS_GOLEM_OFFSET[offset[0] + 2][offset[1] + 2] = true;
        }
    }

    // 1.16 puts the world spawn in a random spawn biome cell within 256 blocks of (0,0), then spirals
    // out chunk by chunk from there for a grass block (almost always found in the first chunk)
    private static final int SPAWN_SEARCH_RADIUS = 256 + 16;
    // the cubiomes estimate is only the spawn biome cell, the real spawn can be a few chunks away
    private static final int SPAWN_ESTIMATE_SLACK = 64;

    // The temple floor at Y=64 caps the shaft. The golem's feet start 1 block above the cage origin, which
    // is the first free block above the surface (water counts), so it only drops into the shaft when the
    // cage origin is at Y<=63: its feet start inside the floor block, which doesn't stop it falling.
    private static final int MAX_GOLEM_CAGE_Y = 63;
    // The terrain noise only depends on the structure seed, so if the golem spawns too high for this many
    // sister seeds with the right biomes, it almost certainly does for all of them.
    private static final int TERRAIN_PROBES = 64;

    private static final long LCG_MULTIPLIER = 0x5DEECE66DL;
    private static final long LCG_ADDEND = 0xBL;
    // nextInt(24) re-rolls a next(31) value this large
    private static final int NEXT_INT_24_REROLL = Integer.MAX_VALUE - Integer.MAX_VALUE % 24;

    private final ChunkRand rand = new ChunkRand();
    private final MCVersion version = MCVersion.v1_16_1;
    private final PillagerOutpost outpost = new PillagerOutpost(version);
    private final DesertPyramid temple = new DesertPyramid(version);
    private final OutpostGenerator gen = new OutpostGenerator(version);

    private final long seedMin;
    private final long seedMax;
    private final int maxSpawnDistance;
    private final int maxTempleCoord;
    private final int regionRadius;

    public ExplodingTempleFinder(long seedMin, long seedMax, int maxSpawnDistance) {
        this.seedMin = seedMin;
        this.seedMax = seedMax;
        this.maxSpawnDistance = maxSpawnDistance;
        // no temple further out than this can be within maxSpawnDistance of the spawn
        this.maxTempleCoord = SPAWN_SEARCH_RADIUS + maxSpawnDistance;
        this.regionRadius = maxTempleCoord / (32 * 16) + 1;
    }

    public void run() {
        int outpostSalt = outpost.getSalt();
        int templeSalt = temple.getSalt();

        try (CubiomesBiomeChecker biomes = new CubiomesBiomeChecker()) {
            for (long baseSeed = seedMin; baseSeed < seedMax; baseSeed++) {
                // positions in region (0,0) without the outpost start checks - those depend on the region
                // shift, so they are only checked for the shifted seeds
                int outpostPos = regionOffset(baseSeed, outpostSalt);
                int templePos = regionOffset(baseSeed, templeSalt);
                if (outpostPos < 0 || templePos < 0) continue;

                int offsetX = (templePos >> 8) - (outpostPos >> 8);
                int offsetZ = (templePos & 0xFF) - (outpostPos & 0xFF);
                if (offsetX < -2 || offsetX > 2 || offsetZ < -2 || offsetZ > 2
                        || !IS_GOLEM_OFFSET[offsetX + 2][offsetZ + 2]) { continue; }

                CPos outpostChunk = new CPos(outpostPos >> 8, outpostPos & 0xFF);
                checkRegionShifts(baseSeed, outpostChunk, outpostChunk.add(offsetX, offsetZ), biomes);
            }
        }
    }

    // Same as rand.setRegionSeed(baseSeed, 0, 0, salt, version) followed by two nextInt(24) calls, inlined as
    // this runs for every base seed. Returns x << 8 | z, or -1 in the ~1e-8 case where nextInt would re-roll.
    private static int regionOffset(long baseSeed, int salt) {
        long seed = (baseSeed + salt ^ LCG_MULTIPLIER) & Mth.MASK_48;
        seed = seed * LCG_MULTIPLIER + LCG_ADDEND & Mth.MASK_48;
        int x = (int) (seed >>> 17);
        seed = seed * LCG_MULTIPLIER + LCG_ADDEND & Mth.MASK_48;
        int z = (int) (seed >>> 17);
        if (x >= NEXT_INT_24_REROLL || z >= NEXT_INT_24_REROLL) return -1;
        return x % 24 << 8 | z % 24;
    }

    // shifting the structure seed by whole regions moves the structures by whole regions, so only the
    // few regions around the origin are checked - anything further away can't be near the world spawn
    private void checkRegionShifts(long baseSeed, CPos outpostPos, CPos templePos, CubiomesBiomeChecker biomes) {
        for (int regX = -regionRadius; regX <= regionRadius; regX++) {
            for (int regZ = -regionRadius; regZ <= regionRadius; regZ++) {
                CPos shiftedTemple = templePos.add(regX * 32, regZ * 32);
                if (!mayBeNearSpawn(shiftedTemple)) {
                    continue;
                }

                long structureSeed = (baseSeed - regX * RegionSeed.A - regZ * RegionSeed.B) & Mth.MASK_48;
                CPos shiftedOutpost = outpostPos.add(regX * 32, regZ * 32);
                if (!outpostCanStart(structureSeed, shiftedOutpost)) {
                    continue;
                }

                gen.generateSuperflatUnchecked(structureSeed, shiftedOutpost.getX(), shiftedOutpost.getZ(), rand);
                boolean goodPlacement = gen.getIronGolems().stream()
                        .anyMatch(golem -> isWithinTempleShaft(golem, shiftedTemple));

                if (goodPlacement && ravineCarvesBelowShaft(structureSeed, shiftedTemple)) {
                    finalCheck(structureSeed, shiftedOutpost, shiftedTemple, biomes);
                }
            }
        }
    }

    private boolean mayBeNearSpawn(CPos templeChunk) {
        BPos shaft = templeShaftCenter(templeChunk);
        return Math.abs(shaft.getX()) <= maxTempleCoord && Math.abs(shaft.getZ()) <= maxTempleCoord;
    }

    // the region position is already known to match, so only the weak seed and village checks are left
    private boolean outpostCanStart(long structureSeed, CPos outpostPos) {
        rand.setWeakSeed(structureSeed, outpostPos.getX(), outpostPos.getZ(), version);
        rand.nextInt();
        if (rand.nextInt(5) != 0) return false;
        return !outpost.hasNearbyVillage(structureSeed, outpostPos.getX(), outpostPos.getZ(), rand);
    }

    private static boolean ravineCarvesBelowShaft(long structureSeed, CPos templePos) {
        // create the block lists
        // Y=50 - required solid
        // Y=48 down to Y=11 - required air

        var targetAirList = new ArrayList<BPos>();
        var targetSolidList = new ArrayList<BPos>();
        var basePos = templePos.toBlockPos(0);

        for (int dx = 9; dx <= 11; dx++) {
            for (int dz = 9; dz <= 11; dz++) {
                targetSolidList.add(basePos.add(dx, 50, dz));
                for (int y = 11; y <= 48; y++)
                    targetAirList.add(basePos.add(dx, y, dz));
            }
        }

        return CubiomesRavineGenerator.canyonGivesRequiredAir(structureSeed, targetAirList, targetSolidList);
    }

    private void finalCheck(long structureSeed, CPos outpostPos, CPos templePos, CubiomesBiomeChecker biomes) {
        structureSeedCount.incrementAndGet();
        System.out.println("got a candidate structure seed: " + structureSeed
                + " (temple chunk " + templePos.getX() + " " + templePos.getZ() + ")");

        if (!golemDropsInSomeSisterSeed(structureSeed, outpostPos, templePos, biomes)) {
            return;
        }

        BPos shaft = templeShaftCenter(templePos);
        for (long upperBits = 0; upperBits < 1L << 16; upperBits++) {
            long worldSeed = upperBits << 48 | structureSeed;

            // cheap native biome and spawn biome checks first
            if (!hasStructureBiomes(worldSeed, outpostPos, templePos, biomes)) {
                continue;
            }
            if (horizontalDistance(biomes.estimateSpawn(), shaft) > maxSpawnDistance + SPAWN_ESTIMATE_SLACK) {
                continue;
            }

            BiomeSource obs = BiomeSource.of(Dimension.OVERWORLD, version, worldSeed);
            if (!temple.canSpawn(templePos, obs) || !outpost.canSpawn(outpostPos, obs)) {
                continue;
            }

            TerrainGenerator otg = TerrainGenerator.of(obs);
            Optional<BlockBox> golem = findDroppingGolem(otg, outpostPos, templePos);
            if (golem.isEmpty()) {
                continue;
            }

            BPos spawn = SpawnPoint.getSpawn((OverworldTerrainGenerator) otg);
            double distance = horizontalDistance(spawn, shaft);
            if (distance > maxSpawnDistance) {
                continue;
            }

            resultCount.incrementAndGet();
            System.out.printf("Got full world seed: %d %s | spawn %d %d %d | %.0f blocks from spawn | golem cage Y=%d%n",
                    worldSeed, Utils.tp(templePos), spawn.getX(), spawn.getY(), spawn.getZ(), distance, golem.get().minY);
        }
    }

    private boolean golemDropsInSomeSisterSeed(long structureSeed, CPos outpostPos, CPos templePos, CubiomesBiomeChecker biomes) {
        int probes = 0;
        for (long upperBits = 0; upperBits < 1L << 16 && probes < TERRAIN_PROBES; upperBits++) {
            long worldSeed = upperBits << 48 | structureSeed;
            if (!hasStructureBiomes(worldSeed, outpostPos, templePos, biomes)) {
                continue;
            }

            probes++;
            TerrainGenerator otg = TerrainGenerator.of(BiomeSource.of(Dimension.OVERWORLD, version, worldSeed));
            if (findDroppingGolem(otg, outpostPos, templePos).isPresent()) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasStructureBiomes(long worldSeed, CPos outpostPos, CPos templePos, CubiomesBiomeChecker biomes) {
        biomes.applySeed(worldSeed);
        return CubiomesBiomeChecker.isDesert(biomes.getStructureBiome(templePos))
                && CubiomesBiomeChecker.isOutpostBiome(biomes.getStructureBiome(outpostPos));
    }

    // generates the outpost on the real terrain and returns the golem that spawns low enough in the shaft to drop
    private Optional<BlockBox> findDroppingGolem(TerrainGenerator otg, CPos outpostPos, CPos templePos) {
        if (!gen.generate(otg, outpostPos)) {
            return Optional.empty();
        }
        return gen.getIronGolems().stream()
                .filter(golem -> isWithinTempleShaft(golem, templePos) && golem.minY <= MAX_GOLEM_CAGE_Y)
                .findFirst();
    }

    private static boolean isWithinTempleShaft(BlockBox golemHitbox, CPos desertTempleChunk) {
        if (!desertTempleChunk.equals(new BPos(golemHitbox.getCenter()).toChunkPos())) {
            return false;
        }
        return Utils.withinShaftFootprint(golemHitbox.minX, golemHitbox.minZ)
                && Utils.withinShaftFootprint(golemHitbox.maxX, golemHitbox.maxZ);
    }

    private static BPos templeShaftCenter(CPos templeChunk) {
        return templeChunk.toBlockPos(0).add(10, 0, 10);
    }

    private static double horizontalDistance(BPos a, BPos b) {
        return Math.hypot(a.getX() - b.getX(), a.getZ() - b.getZ());
    }
}
