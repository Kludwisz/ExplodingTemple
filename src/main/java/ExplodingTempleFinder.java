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
import generator.CubiomesCarveRegion;
import generator.CubiomesRavineGenerator;
import profotoce59.generator.OutpostGenerator;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

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
    // caves, ravines and water springs are modelled this many chunks around the temple, water from springs
    // further away would have to flow ~60 blocks along the ravine to reach it
    private static final int CARVE_CHUNK_RADIUS = 4;
    // DesertPyramidPiece is 21x21 with a solid base from Y=60 to the floor at Y=64, and fills each column of its
    // footprint from Y=59 down through air and liquid
    private static final int PYRAMID_SIZE = 21;
    private static final int PYRAMID_FILL_TOP = 59;

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
                        || GOLEM_ROTATION[offsetX + 2][offsetZ + 2] < 0) { continue; }

                CPos outpostChunk = new CPos(outpostPos >> 8, outpostPos & 0xFF);
                checkRegionShifts(baseSeed, outpostChunk, outpostChunk.add(offsetX, offsetZ),
                        GOLEM_ROTATION[offsetX + 2][offsetZ + 2], biomes);
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
    private void checkRegionShifts(long baseSeed, CPos outpostPos, CPos templePos, int rotation, CubiomesBiomeChecker biomes) {
        for (int regX = -regionRadius; regX <= regionRadius; regX++) {
            for (int regZ = -regionRadius; regZ <= regionRadius; regZ++) {
                CPos shiftedTemple = templePos.add(regX * 32, regZ * 32);
                if (!mayBeNearSpawn(shiftedTemple)) {
                    continue;
                }

                long structureSeed = (baseSeed - regX * RegionSeed.A - regZ * RegionSeed.B) & Mth.MASK_48;
                CPos shiftedOutpost = outpostPos.add(regX * 32, regZ * 32);
                rand.setCarverSeed(structureSeed, shiftedOutpost.getX(), shiftedOutpost.getZ(), version);
                if (rand.nextInt(4) != rotation || !outpostCanStart(structureSeed, shiftedOutpost)) {
                    continue;
                }

                boolean goodPlacement = generateSuperflat(structureSeed, shiftedOutpost) && gen.getIronGolems().stream()
                        .anyMatch(golem -> isWithinTempleShaft(golem, shiftedTemple));

                if (!goodPlacement || !ravineCarvesBelowShaft(structureSeed, shiftedTemple)) {
                    continue;
                }
                ravineCount.incrementAndGet();

                CubiomesCarveRegion carve = new CubiomesCarveRegion(structureSeed, shiftedTemple, CARVE_CHUNK_RADIUS);
                // the pyramid's sandstone base and the columns it fills under it (sandstone never holds springs)
                int pyramidX = shiftedTemple.getX() << 4, pyramidZ = shiftedTemple.getZ() << 4;
                carve.fillBox(pyramidX, PYRAMID_FILL_TOP + 1, pyramidZ, pyramidX + PYRAMID_SIZE - 1, 64, pyramidZ + PYRAMID_SIZE - 1);
                carve.fillColumnsDown(pyramidX, pyramidZ, PYRAMID_SIZE, PYRAMID_FILL_TOP);
                DeadlyFall fall = new DeadlyFall(carve, shiftedTemple);
                if (!fall.hasLavaPoolAndOpenFall() || !fall.hasNoSurvivableLedge()) {
                    continue;
                }

                var mineshafts = Mineshafts.near(structureSeed, shiftedTemple, CARVE_CHUNK_RADIUS);
                if (!fall.isClearOf(mineshafts)) {
                    continue;
                }
                // mineshaft air can hold springs and lead their water into the ravine
                mineshafts.forEach(box -> carve.carve(box.x0(), box.y0(), box.z0(), box.x1(), box.y1(), box.z1()));

                finalCheck(structureSeed, shiftedOutpost, shiftedTemple, biomes, carve, fall);
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

    private void finalCheck(long structureSeed, CPos outpostPos, CPos templePos, CubiomesBiomeChecker biomes,
                            CubiomesCarveRegion carve, DeadlyFall fall) {
        structureSeedCount.incrementAndGet();
        System.out.println("got a candidate structure seed: " + structureSeed
                + " (temple chunk " + templePos.getX() + " " + templePos.getZ() + ")");

        // spring water floods almost every ravine, so that is ruled out first
        Dryness dryness = new Dryness(structureSeed, carve, fall);
        if (!someSisterSeedStaysDry(structureSeed, outpostPos, templePos, biomes, dryness)) {
            return;
        }
        dryStructureSeedCount.incrementAndGet();
        if (!golemDropsInSomeSisterSeed(structureSeed, outpostPos, templePos, biomes)) {
            return;
        }

        BPos shaft = templeShaftCenter(templePos);
        for (long upperBits = 0; upperBits < 1L << 16; upperBits++) {
            long worldSeed = upperBits << 48 | structureSeed;

            // cheap native biome, water and spawn biome checks first
            if (!hasStructureBiomes(worldSeed, outpostPos, templePos, biomes) || !dryness.staysDry(biomes)) {
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

    private static boolean someSisterSeedStaysDry(long structureSeed, CPos outpostPos, CPos templePos,
                                                  CubiomesBiomeChecker biomes, Dryness dryness) {
        for (long upperBits = 0; upperBits < 1L << 16; upperBits++) {
            if (hasStructureBiomes(upperBits << 48 | structureSeed, outpostPos, templePos, biomes) && dryness.staysDry(biomes)) {
                return true;
            }
        }
        return false;
    }

    // The springs only depend on the biome each chunk around the temple decorates with, and sister seeds share a
    // few hundred layouts at most, so the water is only simulated once per layout.
    private static final class Dryness {
        private final long structureSeed;
        private final CubiomesCarveRegion carve;
        private final DeadlyFall fall;
        private final Map<String, Boolean> byLayout = new HashMap<>();

        Dryness(long structureSeed, CubiomesCarveRegion carve, DeadlyFall fall) {
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

    // OutpostGenerator throws a NullPointerException on a few rare layouts, those are skipped
    private boolean generateSuperflat(long structureSeed, CPos outpostPos) {
        try {
            gen.generateSuperflatUnchecked(structureSeed, outpostPos.getX(), outpostPos.getZ(), rand);
            return true;
        } catch (NullPointerException e) {
            return false;
        }
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
