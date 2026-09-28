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
import generator.CarveRegion;
import generator.CubiomesRavineGenerator;
import generator.VanillaCarver;
import profotoce59.generator.OutpostGenerator;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.IntStream;

public class ExplodingTempleFinder {
    public static final AtomicInteger resultCount = new AtomicInteger(0);
    public static final AtomicLong ravineCount = new AtomicLong(0);
    public static final AtomicLong structureSeedCount = new AtomicLong(0);
    public static final AtomicLong dryStructureSeedCount = new AtomicLong(0);

    // Offsets from the outpost chunk to the golem's chunk that LayoutTest found, (0,-2) is ~4x rarer, with the
    // base plate rotation (the first nextInt(4) after the carver seed) each one needs. Out of 3M layouts every
    // offset only ever came up with that one rotation, so the other 3/4 of layouts can be skipped.
    private static final int[][] GOLEM_OFFSETS = {
            {0, 1, 1}, {-1, 1, 0}, {1, 1, 0}, {-2, 0, 2}, {-2, 1, 1}, {1, -1, 0}, {-1, -2, 3}, {0, -2, 2}
    };
    private static final int[][] GOLEM_ROTATION = new int[5][5];

    static {
        for (int[] row : GOLEM_ROTATION) Arrays.fill(row, -1);
        for (int[] offset : GOLEM_OFFSETS) {
            GOLEM_ROTATION[offset[0] + 2][offset[1] + 2] = offset[2];
        }
    }

    // The last 3 bits of a nextInt(24) are bits 17-19 of the LCG state, which only depend on the low 20 bits of the
    // seed as the LCG never carries downwards. So the low 20 bits of a base seed already rule out the 7/8 whose
    // outpost to temple offset can't be any of the golem offsets, even mod 8. These are the rest, in order.
    private static final int LOW_BITS = 20;
    private static final int[] PROMISING_LOW_BITS = promisingLowBits(
            new PillagerOutpost(MCVersion.v1_16_1).getSalt(), new DesertPyramid(MCVersion.v1_16_1).getSalt());

    // 1.16 puts the world spawn in a random spawn biome cell within 256 blocks of (0,0), then spirals
    // out chunk by chunk from there for a grass block (almost always found in the first chunk)
    private static final int SPAWN_SEARCH_RADIUS = 256 + 16;
    // the cubiomes estimate is only the spawn biome cell, the real spawn can be a few chunks away
    private static final int SPAWN_ESTIMATE_SLACK = 64;

    // The temple floor at Y=64 caps the shaft. The golem's feet start 1 block above the cage origin, which
    // is the first free block above the surface (water counts), so it only drops into the shaft when the
    // cage origin is at Y<=63: its feet start inside the floor block, which doesn't stop it falling.
    private static final int MAX_GOLEM_CAGE_Y = 63;
    // caves, ravines and water springs are modelled this many chunks around the temple, water from springs
    // further away would have to flow ~60 blocks along the ravine to reach it
    private static final int CARVE_CHUNK_RADIUS = 4;
    // DesertPyramidPiece is 21x21 with a solid base from Y=60 to the floor at Y=64, and fills each column of its
    // footprint from Y=59 down through air and liquid
    private static final int PYRAMID_SIZE = 21;
    private static final int PYRAMID_FILL_TOP = 59;

    // outposts and desert pyramids both have one attempt per 32x32 chunk region
    private static final int REGION_CHUNKS = 32;

    private static final long LCG_MULTIPLIER = 0x5DEECE66DL;
    private static final long LCG_ADDEND = 0xBL;
    // nextInt(24) re-rolls a next(31) value this large
    private static final int NEXT_INT_24_REROLL = Integer.MAX_VALUE - Integer.MAX_VALUE % 24;

    private final ChunkRand rand = new ChunkRand();
    private final MCVersion version = MCVersion.v1_16_1;
    private final PillagerOutpost outpost = new PillagerOutpost(version);
    private final DesertPyramid temple = new DesertPyramid(version);
    private final OutpostGenerator gen = new OutpostGenerator(version);
    private final OutpostGolems golems = new OutpostGolems();

    private final long seedMin;
    private final long seedMax;
    private final int maxSpawnDistance;
    private final int maxTempleCoord;

    public ExplodingTempleFinder(long seedMin, long seedMax, int maxSpawnDistance) {
        this.seedMin = seedMin;
        this.seedMax = seedMax;
        this.maxSpawnDistance = maxSpawnDistance;
        // no temple further out than this can be within maxSpawnDistance of the spawn
        this.maxTempleCoord = SPAWN_SEARCH_RADIUS + maxSpawnDistance;
    }

    public void run() {
        int outpostSalt = outpost.getSalt();
        int templeSalt = temple.getSalt();

        try (CubiomesBiomeChecker biomes = new CubiomesBiomeChecker()) {
            for (long high = seedMin >>> LOW_BITS; high <= (seedMax - 1) >>> LOW_BITS; high++) {
                for (int low : PROMISING_LOW_BITS) {
                    long baseSeed = high << LOW_BITS | low;
                    if (baseSeed < seedMin || baseSeed >= seedMax) continue;

                    // Positions in region (0,0) without the outpost start checks - those depend on the region shift,
                    // so they are only checked for the shifted seeds. Same as rand.setRegionSeed(baseSeed, 0, 0,
                    // salt, version) followed by nextInt(24) for x and z, inlined as this runs for every base seed,
                    // with x compared first as most seeds already fail there.
                    long outpostState = nextState((baseSeed + outpostSalt ^ LCG_MULTIPLIER) & Mth.MASK_48);
                    long templeState = nextState((baseSeed + templeSalt ^ LCG_MULTIPLIER) & Mth.MASK_48);
                    int outpostX = nextInt24(outpostState);
                    int templeX = nextInt24(templeState);
                    int offsetX = templeX - outpostX;
                    if (outpostX < 0 || templeX < 0 || offsetX < -2 || offsetX > 2) continue;

                    outpostState = nextState(outpostState);
                    templeState = nextState(templeState);
                    int outpostZ = nextInt24(outpostState);
                    int templeZ = nextInt24(templeState);
                    int offsetZ = templeZ - outpostZ;
                    if (outpostZ < 0 || templeZ < 0 || offsetZ < -2 || offsetZ > 2
                            || GOLEM_ROTATION[offsetX + 2][offsetZ + 2] < 0) { continue; }

                    CPos outpostChunk = new CPos(outpostX, outpostZ);
                    checkRegionShifts(baseSeed, outpostChunk, outpostChunk.add(offsetX, offsetZ),
                            GOLEM_ROTATION[offsetX + 2][offsetZ + 2], biomes);
                }
            }
        }
    }

    private static int[] promisingLowBits(int outpostSalt, int templeSalt) {
        boolean[][] golemOffsetMod8 = new boolean[8][8];
        for (int[] offset : GOLEM_OFFSETS) {
            golemOffsetMod8[offset[0] & 7][offset[1] & 7] = true;
        }
        int mask = (1 << LOW_BITS) - 1;
        return IntStream.rangeClosed(0, mask).filter(low -> {
            long outpostState = nextState((low + outpostSalt ^ LCG_MULTIPLIER) & mask) & mask;
            long templeState = nextState((low + templeSalt ^ LCG_MULTIPLIER) & mask) & mask;
            int offsetX = (int) ((templeState >>> 17) - (outpostState >>> 17)) & 7;
            outpostState = nextState(outpostState) & mask;
            templeState = nextState(templeState) & mask;
            int offsetZ = (int) ((templeState >>> 17) - (outpostState >>> 17)) & 7;
            return golemOffsetMod8[offsetX][offsetZ];
        }).toArray();
    }

    // one step of the java.util.Random LCG
    private static long nextState(long state) {
        return state * LCG_MULTIPLIER + LCG_ADDEND & Mth.MASK_48;
    }

    // nextInt(24) for the state, or -1 in the ~1e-8 case where it would re-roll
    private static int nextInt24(long state) {
        int bits = (int) (state >>> 17);
        return bits >= NEXT_INT_24_REROLL ? -1 : bits % 24;
    }

    // shifting the structure seed by whole regions moves the structures by whole regions, so only the
    // few regions around the origin are checked - anything further away can't be near the world spawn
    private void checkRegionShifts(long baseSeed, CPos outpostPos, CPos templePos, int rotation, CubiomesBiomeChecker biomes) {
        BPos shaft = templeShaftCenter(templePos);
        for (int regX = minRegionShift(shaft.getX()); regX <= maxRegionShift(shaft.getX()); regX++) {
            for (int regZ = minRegionShift(shaft.getZ()); regZ <= maxRegionShift(shaft.getZ()); regZ++) {
                long structureSeed = (baseSeed - regX * RegionSeed.A - regZ * RegionSeed.B) & Mth.MASK_48;
                int outpostX = outpostPos.getX() + regX * REGION_CHUNKS;
                int outpostZ = outpostPos.getZ() + regZ * REGION_CHUNKS;
                // cheapest check first: the weak seed passes 1 in 5, the base plate rotation 1 in 4
                if (!passesWeakSeedCheck(structureSeed, outpostX, outpostZ)) {
                    continue;
                }
                rand.setCarverSeed(structureSeed, outpostX, outpostZ, version);
                if (rand.nextInt(4) != rotation) {
                    continue;
                }
                CPos shiftedOutpost = new CPos(outpostX, outpostZ);
                if (outpost.hasNearbyVillage(structureSeed, outpostX, outpostZ, rand)) {
                    continue;
                }

                CPos shiftedTemple = templePos.add(regX * REGION_CHUNKS, regZ * REGION_CHUNKS);

                if (!golemInShaft(structureSeed, shiftedOutpost, shiftedTemple)
                        || !ravineCarvesBelowShaft(structureSeed, shiftedTemple)) {
                    continue;
                }
                ravineCount.incrementAndGet();

                CarveRegion carve = CarveRegion.cubiomes(structureSeed, shiftedTemple, CARVE_CHUNK_RADIUS);
                DeadlyFall fall = deadlyFall(structureSeed, shiftedTemple, carve);
                if (fall != null) {
                    finalCheck(structureSeed, shiftedOutpost, shiftedTemple, biomes, carve, fall);
                }
            }
        }
    }

    // the first and last region shift that keep the temple shaft within maxTempleCoord of the origin on one axis
    private int minRegionShift(int shaftCoord) {
        return Math.ceilDiv(-maxTempleCoord - shaftCoord, REGION_CHUNKS * 16);
    }

    private int maxRegionShift(int shaftCoord) {
        return Math.floorDiv(maxTempleCoord - shaftCoord, REGION_CHUNKS * 16);
    }

    // Besides its region position, an outpost needs this and no village nearby to start
    private boolean passesWeakSeedCheck(long structureSeed, int outpostX, int outpostZ) {
        rand.setWeakSeed(structureSeed, outpostX, outpostZ, version);
        rand.nextInt();
        return rand.nextInt(5) == 0;
    }

    private static boolean ravineCarvesBelowShaft(long structureSeed, CPos templePos) {
        // create the block lists
        // Y=59 - required solid: the pyramid fills its footprint with sandstone from Y=59 down through any air,
        //        which would plug the ravine under the shaft
        // Y=48 down to Y=11 - required air

        var targetAirList = new ArrayList<BPos>();
        var targetSolidList = new ArrayList<BPos>();
        var basePos = templePos.toBlockPos(0);

        for (int dx = 9; dx <= 11; dx++) {
            for (int dz = 9; dz <= 11; dz++) {
                targetSolidList.add(basePos.add(dx, PYRAMID_FILL_TOP, dz));
                for (int y = 11; y <= 48; y++)
                    targetAirList.add(basePos.add(dx, y, dz));
            }
        }

        return CubiomesRavineGenerator.canyonGivesRequiredAir(structureSeed, targetAirList, targetSolidList);
    }

    // The fall's shape: nothing to land on, even in the worst case after the explosion (see DeadlyFall#isRobust),
    // and no mineshaft in reach. Adds the pyramid and the mineshafts to the carve, and returns null if the fall isn't
    // deadly.
    private static DeadlyFall deadlyFall(long structureSeed, CPos templePos, CarveRegion carve) {
        // the pyramid's sandstone base and the columns it fills under it (sandstone never holds springs)
        int pyramidX = templePos.getX() << 4, pyramidZ = templePos.getZ() << 4;
        carve.fillBox(pyramidX, PYRAMID_FILL_TOP + 1, pyramidZ, pyramidX + PYRAMID_SIZE - 1, 64, pyramidZ + PYRAMID_SIZE - 1);
        carve.fillColumnsDown(pyramidX, pyramidZ, PYRAMID_SIZE, PYRAMID_FILL_TOP);
        DeadlyFall fall = new DeadlyFall(carve, templePos);
        if (!fall.hasNoSurvivableLedge() || !fall.isRobust()) {
            return null;
        }

        var mineshafts = Mineshafts.near(structureSeed, templePos, CARVE_CHUNK_RADIUS);
        if (!fall.isClearOf(mineshafts)) {
            return null;
        }
        // mineshaft air can hold springs and lead their water into the ravine
        mineshafts.forEach(box -> carve.carve(box.x0(), box.y0(), box.z0(), box.x1(), box.y1(), box.z1()));
        return fall;
    }

    // Cubiomes carves caves the game skips next to rivers and oceans, which is where the golem drops (the ground
    // has to be at sea level), so every result is checked again with the game's own carvers and terrain.
    // Returns the fall as the game carves it, or null if it isn't deadly there.
    private static DeadlyFall deadlyWithVanillaCarvers(long structureSeed, long worldSeed, CPos templePos,
                                                       TerrainGenerator terrain, CubiomesBiomeChecker biomes) {
        CarveRegion carve = VanillaCarver.carve(worldSeed, templePos, CARVE_CHUNK_RADIUS, terrain, biomes::getCarverBiome);
        DeadlyFall fall = deadlyFall(structureSeed, templePos, carve);
        if (fall == null) {
            return null;
        }
        var springs = WaterSprings.predict(structureSeed, carve, biomes::getStructureBiome);
        return fall.staysDry(WaterSprings.flow(springs, carve)) ? fall : null;
    }

    private void finalCheck(long structureSeed, CPos outpostPos, CPos templePos, CubiomesBiomeChecker biomes,
                            CarveRegion carve, DeadlyFall fall) {
        structureSeedCount.incrementAndGet();
        System.out.printf("got a candidate structure seed: %d (temple chunk %d %d, closest wall below the crater"
                        + " %s blocks beyond reach), checking its 65536 world seeds%n", structureSeed,
                templePos.getX(), templePos.getZ(), formatMargin(fall.wallMarginBelowCrater()));

        // how many sister seeds get through each check, to see what rules a structure seed out
        Dryness dryness = new Dryness(structureSeed, carve, fall);
        int[] passed = new int[7];
        BPos shaft = templeShaftCenter(templePos);
        for (long upperBits = 0; upperBits < 1L << 16; upperBits++) {
            long worldSeed = upperBits << 48 | structureSeed;

            // cheap native biome, water and spawn biome checks first, spring water floods almost every ravine
            if (!hasStructureBiomes(worldSeed, outpostPos, templePos, biomes)) {
                continue;
            }
            passed[0]++;
            if (!dryness.staysDry(biomes)) {
                continue;
            }
            passed[1]++;
            if (!spawnCanBeClose(biomes.estimateSpawn(), shaft)) {
                continue;
            }
            passed[2]++;

            BiomeSource obs = BiomeSource.of(Dimension.OVERWORLD, version, worldSeed);
            if (!temple.canSpawn(templePos, obs) || !outpost.canSpawn(outpostPos, obs)) {
                continue;
            }
            passed[3]++;

            TerrainGenerator otg = TerrainGenerator.of(obs);
            Optional<BlockBox> golem = findDroppingGolem(otg, outpostPos, templePos);
            if (golem.isEmpty()) {
                continue;
            }
            passed[4]++;

            BPos spawn = SpawnPoint.getSpawn((OverworldTerrainGenerator) otg);
            double distance = horizontalDistance(spawn, shaft);
            if (distance > maxSpawnDistance) {
                continue;
            }
            passed[5]++;

            DeadlyFall vanillaFall = deadlyWithVanillaCarvers(structureSeed, worldSeed, templePos, otg, biomes);
            if (vanillaFall == null) {
                System.out.println("  " + worldSeed + " is only deadly with the cubiomes carvers");
                continue;
            }

            passed[6]++;
            resultCount.incrementAndGet();
            System.out.printf("Got full world seed: %d %s | spawn %d %d %d | %.0f blocks from spawn | golem cage Y=%d"
                            + " | lands in %s | closest wall below the crater %s blocks beyond reach%n",
                    worldSeed, Utils.tp(templePos), spawn.getX(), spawn.getY(), spawn.getZ(), distance, golem.get().minY,
                    vanillaFall.landsInLava() ? "lava" : "stone", formatMargin(vanillaFall.wallMarginBelowCrater()));
        }
        if (passed[1] > 0) {
            dryStructureSeedCount.incrementAndGet();
        }
        System.out.printf("  structure seed %d, sister seeds left after each check: biomes %d, dry %d, spawn estimate"
                        + " close %d, structures spawn %d, golem drops %d, spawn close %d, deadly with vanilla carvers"
                        + " %d%n", structureSeed, passed[0], passed[1], passed[2], passed[3], passed[4], passed[5],
                passed[6]);
    }

    // With no spawn biome within 256 blocks the game spirals out from 0 0 for grass instead, and cubiomes estimates
    // 8 8, but in a desert the nearest grass can be 90 blocks from there, so then only the real spawn tells
    private boolean spawnCanBeClose(BPos estimate, BPos shaft) {
        boolean noSpawnBiome = estimate.getX() == 8 && estimate.getZ() == 8;
        return noSpawnBiome || horizontalDistance(estimate, shaft) <= maxSpawnDistance + SPAWN_ESTIMATE_SLACK;
    }

    private static String formatMargin(double margin) {
        return margin > 4 ? "4+" : String.format("%.1f", margin);
    }

    // The springs only depend on the biome each chunk around the temple decorates with, and sister seeds share a
    // few hundred layouts at most, so the water is only simulated once per layout.
    private static final class Dryness {
        private final long structureSeed;
        private final CarveRegion carve;
        private final DeadlyFall fall;
        private final Map<String, Boolean> byLayout = new HashMap<>();

        Dryness(long structureSeed, CarveRegion carve, DeadlyFall fall) {
            this.structureSeed = structureSeed;
            this.carve = carve;
            this.fall = fall;
        }

        // for the world seed last applied to the biome checker
        boolean staysDry(CubiomesBiomeChecker biomes) {
            StringBuilder layout = new StringBuilder();
            for (int i = 0; i < carve.getWidth(); i++) {
                for (int j = 0; j < carve.getWidth(); j++) {
                    CPos chunk = new CPos(carve.getMinChunkX() + i, carve.getMinChunkZ() + j);
                    layout.append((char) WaterSprings.springFeatureIndex(biomes.getStructureBiome(chunk)));
                }
            }
            return byLayout.computeIfAbsent(layout.toString(), key -> {
                var springs = WaterSprings.predict(structureSeed, carve, biomes::getStructureBiome);
                return fall.staysDry(WaterSprings.flow(springs, carve));
            });
        }
    }

    private static boolean hasStructureBiomes(long worldSeed, CPos outpostPos, CPos templePos, CubiomesBiomeChecker biomes) {
        biomes.applySeed(worldSeed);
        return CubiomesBiomeChecker.isDesert(biomes.getStructureBiome(templePos))
                && CubiomesBiomeChecker.isOutpostBiome(biomes.getStructureBiome(outpostPos));
    }

    // whether the outpost, laid out on flat ground, puts a golem in the temple shaft
    private boolean golemInShaft(long structureSeed, CPos outpostPos, CPos templePos) {
        int shaftX = (templePos.getX() << 4) + 9, shaftZ = (templePos.getZ() << 4) + 9;
        return golems.hasGolemIn(structureSeed, outpostPos.getX(), outpostPos.getZ(), shaftX, shaftZ, shaftX + 2, shaftZ + 2);
    }

    // generates the outpost on the real terrain and returns the golem that spawns low enough in the shaft to drop
    private Optional<BlockBox> findDroppingGolem(TerrainGenerator otg, CPos outpostPos, CPos templePos) {
        try {
            if (!gen.generate(otg, outpostPos)) {
                return Optional.empty();
            }
        } catch (NullPointerException e) {
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
