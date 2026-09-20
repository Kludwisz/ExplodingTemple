import com.seedfinding.mccore.rand.ChunkRand;
import com.seedfinding.mccore.rand.seed.RegionSeed;
import com.seedfinding.mccore.util.block.BlockBox;
import com.seedfinding.mccore.util.pos.BPos;
import com.seedfinding.mccore.util.pos.CPos;
import com.seedfinding.mccore.version.MCVersion;
import com.seedfinding.mcfeature.structure.DesertPyramid;
import com.seedfinding.mcfeature.structure.PillagerOutpost;
import com.seedfinding.mcmath.util.Mth;
import profotoce59.generator.OutpostGenerator;

import java.util.concurrent.atomic.AtomicInteger;

public class ExplodingTempleFinder {
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
                CPos shiftedTemple = templePos.add(regX * 32, regZ * 32);

                gen.generateSuperflatUnchecked(structureSeed, shiftedOutpost.getX(), shiftedOutpost.getZ(), rand);
                boolean goodPlacement = gen.getIronGolems().stream()
                        .anyMatch(golem -> isWithinTempleShaft(golem, shiftedTemple));

                if (goodPlacement) {
                    resultCount.incrementAndGet();
                    // TODO check ravine, biomes, full outpost gen (with terrain)
                    //System.out.println(baseSeed + " in region " + regX + "," + regZ);
                }
            }
        }
    }

    private static boolean isWithinTempleShaft(BlockBox golemHitbox, CPos desertTempleChunk) {
        if (!desertTempleChunk.equals(new BPos(golemHitbox.getCenter()).toChunkPos())) {
            return false;
        }
        return Utils.withinShaftFootprint(golemHitbox.minX, golemHitbox.minZ)
                && Utils.withinShaftFootprint(golemHitbox.maxX, golemHitbox.maxZ);
    }
}
