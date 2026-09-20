import com.seedfinding.mccore.rand.ChunkRand;
import com.seedfinding.mccore.util.pos.BPos;
import com.seedfinding.mccore.util.pos.CPos;
import com.seedfinding.mccore.version.MCVersion;
import com.seedfinding.mcfeature.structure.PillagerOutpost;
import profotoce59.generator.OutpostGenerator;

import java.util.HashMap;
import java.util.Random;

/*
Sample size: 10000000
Generated: 249185
--------------
Pos{x=-1, y=0, z=1} : 463
Pos{x=-2, y=0, z=0} : 476
Pos{x=0, y=0, z=1} : 493
Pos{x=0, y=0, z=-2} : 122
Pos{x=-1, y=0, z=-2} : 451
Pos{x=1, y=0, z=1} : 461
Pos{x=1, y=0, z=-1} : 475
Pos{x=-2, y=0, z=1} : 457

Pos{x=-1, y=0, z=1} : 510
Pos{x=-2, y=0, z=0} : 502
Pos{x=0, y=0, z=-2} : 104
Pos{x=0, y=0, z=1} : 488
Pos{x=-1, y=0, z=-2} : 451
Pos{x=1, y=0, z=1} : 430
Pos{x=-2, y=0, z=1} : 427
Pos{x=1, y=0, z=-1} : 488

Pos{x=-1, y=0, z=1} : 528
Pos{x=0, y=0, z=1} : 507 <-- seems most consistent, will go with that
Pos{x=-2, y=0, z=0} : 471
Pos{x=0, y=0, z=-2} : 119
Pos{x=1, y=0, z=1} : 466
Pos{x=-1, y=0, z=-2} : 520
Pos{x=1, y=0, z=-1} : 472
Pos{x=-2, y=0, z=1} : 469

Sample size: 50000000
Generated: 5240548
--------------
Pos{x=-1, y=0, z=1} : 21576
Pos{x=-2, y=0, z=0} : 21600
Pos{x=0, y=0, z=1} : 21752 <-- still slightly higher than others
Pos{x=0, y=0, z=-2} : 5251
Pos{x=1, y=0, z=1} : 21557
Pos{x=-1, y=0, z=-2} : 21577
Pos{x=-2, y=0, z=1} : 21369
Pos{x=1, y=0, z=-1} : 21441
 */

public class LayoutTest {
    /*
    Looking for a cage_1 such that the inner 2x2 x-z box is contained within (9,9), (11,11)
     */
    public static void main(String[] args) {
        var rand = new ChunkRand();
        var outpost = new PillagerOutpost(MCVersion.v1_16_1);
        var gen = new OutpostGenerator(MCVersion.v1_16_1);

        long sampleSize = 50_000_000L;
        long generatedCount = 0;
        HashMap<CPos, Integer> heatmap = new HashMap<>();

        Random r = new Random();
        for (long i = 0; i < sampleSize; i++) {
            long seed = r.nextLong();
            CPos pos = outpost.getInRegion(seed, 0, 0, rand);
            if (pos == null) continue;

            gen.generateSuperflatUnchecked(seed, pos.getX(), pos.getZ(), rand);
            generatedCount++;
            var goodGolem = gen.getIronGolems().stream()
                    .filter(golem -> Utils.withinShaftFootprint(golem.minX, golem.minZ)
                            && Utils.withinShaftFootprint(golem.maxX, golem.maxZ))
                    .findFirst();

            if (goodGolem.isPresent()) {
                var chunkOffset = new BPos(goodGolem.get().getCenter()).toChunkPos().subtract(pos);
                int currentCount = heatmap.getOrDefault(chunkOffset, 0);
                heatmap.put(chunkOffset, currentCount + 1);
            }
        }

        System.out.println("Sample size: " + sampleSize);
        System.out.println("Generated: " + generatedCount);
        System.out.println("--------------");
        for (var entry : heatmap.entrySet()) {
            System.out.println(entry.getKey() + " : " + entry.getValue());
        }
    }
}
