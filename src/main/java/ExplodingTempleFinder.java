import com.seedfinding.mcbiome.source.BiomeSource;
import com.seedfinding.mccore.rand.ChunkRand;
import com.seedfinding.mccore.rand.seed.RegionSeed;
import com.seedfinding.mccore.rand.seed.WorldSeed;
import com.seedfinding.mccore.state.Dimension;
import com.seedfinding.mccore.util.block.BlockBox;
import com.seedfinding.mccore.util.pos.BPos;
import com.seedfinding.mccore.util.pos.CPos;
import com.seedfinding.mccore.version.MCVersion;
import com.seedfinding.mcfeature.structure.DesertPyramid;
import com.seedfinding.mcfeature.structure.PillagerOutpost;
import com.seedfinding.mcmath.util.Mth;
import com.seedfinding.mcterrain.TerrainGenerator;
import generator.CubiomesRavineGenerator;
import profotoce59.generator.OutpostGenerator;

import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicInteger;

public class ExplodingTempleFinder {
    private static final Object carverLock = new Object();
    public static final AtomicInteger resultCount = new AtomicInteger(0);

    private final ChunkRand rand = new ChunkRand();
    private final MCVersion version = MCVersion.v1_16_1;
    private final PillagerOutpost outpost = new PillagerOutpost(version);
    private final DesertPyramid temple = new DesertPyramid(version);
    private final OutpostGenerator gen = new OutpostGenerator(version);

    private final long seedMin;
    private final long seedMax;
    private final int regionRadius;

    public ExplodingTempleFinder(long seedMin, long seedMax, int regionRadius) {
        this.seedMin = seedMin;
        this.seedMax = seedMax;
        this.regionRadius = regionRadius;
    }

    public void run() {
        for (long baseSeed = seedMin; baseSeed < seedMax; baseSeed++) {
            var outpostPos = outpost.getInRegion(baseSeed, 0, 0, rand);
            if (outpostPos == null) continue;

            // aiming for the best offset - (0,1) outpost to temple
            var templePos = temple.getInRegion(baseSeed, 0, 0, rand);
            var offset = templePos.subtract(outpostPos);
            if (offset.getX() != 0 || offset.getZ() != 1) continue;

            checkRegionShifts(baseSeed, outpostPos, templePos);
        }
    }

    private void checkRegionShifts(long baseSeed, CPos outpostPos, CPos templePos) {
        for (int regX = -regionRadius; regX <= regionRadius; regX++) {
            for (int regZ = -regionRadius; regZ <= regionRadius; regZ++) {
                long structureSeed = (baseSeed - regX * RegionSeed.A - regZ * RegionSeed.B) & Mth.MASK_48;

                CPos shiftedOutpost = outpostPos.add(regX * 32, regZ * 32);
                if (!outpost.canStart(
                        new PillagerOutpost.Data<>(outpost, shiftedOutpost.getX(), shiftedOutpost.getZ()),
                        structureSeed, rand
                )) { continue; }

                CPos shiftedTemple = templePos.add(regX * 32, regZ * 32);
                gen.generateSuperflatUnchecked(structureSeed, shiftedOutpost.getX(), shiftedOutpost.getZ(), rand);
                boolean goodPlacement = gen.getIronGolems().stream()
                        .anyMatch(golem -> isWithinTempleShaft(golem, shiftedTemple));

                if (goodPlacement) {
                    // create the block lists
                    // Y=50 - required solid
                    // Y=48 down to Y=11 - required air

                    var targetAirList = new ArrayList<BPos>();
                    var targetSolidList = new ArrayList<BPos>();
                    var basePos = shiftedTemple.toBlockPos(0);

                    for (int dx = 9; dx <= 11; dx++) {
                        for (int dz = 9; dz <= 11; dz++) {
                            targetSolidList.add(basePos.add(dx, 50, dz));
                            for (int y = 11; y <= 48; y++)
                                targetAirList.add(basePos.add(dx, y, dz));
                        }
                    }

                    synchronized (carverLock) {
                        boolean goodAir = CubiomesRavineGenerator.canyonGivesRequiredAir(
                                structureSeed, targetAirList, targetSolidList);
                        if (!goodAir) {
                            continue;
                        }

                        finalCheck(structureSeed, shiftedOutpost, shiftedTemple);
                    }
                }
            }
        }
    }

    private void finalCheck(long structureSeed, CPos outpostPos, CPos templePos) {
        System.out.println("got a candidate structure seed: " + structureSeed);

        WorldSeed.getSisterSeeds(structureSeed).asStream().boxed().limit(1024)
                .forEach(worldSeed -> {
                    BiomeSource obs = BiomeSource.of(Dimension.OVERWORLD, version, worldSeed);

                    if (!temple.canSpawn(templePos, obs) || !outpost.canSpawn(outpostPos, obs)) {
                        return;
                    }

                    TerrainGenerator otg = TerrainGenerator.of(obs);
                    if (!gen.generate(otg, outpostPos)) {
                        return;
                    }

                    if (gen.getIronGolems().stream().anyMatch(golem -> isWithinTempleShaft(golem, templePos))) {
                        System.out.println("Got full world seed: " + worldSeed + " " + Utils.tp(templePos));
                    }
                });
    }

    private static boolean isWithinTempleShaft(BlockBox golemHitbox, CPos desertTempleChunk) {
        if (!desertTempleChunk.equals(new BPos(golemHitbox.getCenter()).toChunkPos())) {
            return false;
        }
        return Utils.withinShaftFootprint(golemHitbox.minX, golemHitbox.minZ)
                && Utils.withinShaftFootprint(golemHitbox.maxX, golemHitbox.maxZ);
    }
}
