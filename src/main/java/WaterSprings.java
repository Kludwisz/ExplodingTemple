import com.seedfinding.mccore.rand.ChunkRand;
import com.seedfinding.mccore.util.pos.BPos;
import com.seedfinding.mccore.util.pos.CPos;
import com.seedfinding.mccore.version.MCVersion;
import generator.CarveRegion;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.HashSet;
import java.util.function.ToIntFunction;

/*
Predicts where water springs flood the caves and ravines around the temple, and where their water flows.

Every chunk tries to place 50 water springs in VEGETAL_DECORATION (count_biased_range 50, 8, 8, 256). A spring
goes into a stone block (or cave air) with stone above and below, exactly 4 stone and 1 air neighbour among the
four sides and below. The attempt positions match a vanilla 1.16.1 world exactly; validity is decided on the
cubiomes carve with every other block treated as stone, so ore next to a spring can make this predict a spring
that isn't there (never the other way around, bar mineshafts).
 */
public class WaterSprings {
    private static final int VEGETAL_DECORATION = 8;
    // springs never generate in bedrock, and near the surface the blocks are sand/sandstone anyway
    private static final int MIN_ROCK_Y = 5;
    private static final int MAX_ROCK_Y = 62;
    private static final int LAVA_LEVEL = 10;
    private static final int MAX_SPREAD = 7;

    // index of the water spring feature in each biome's VEGETAL_DECORATION list, dumped from the 1.16.1 registry
    static int springFeatureIndex(int biome) {
        return switch (biome) {
            case 16, 25, 26 -> 6;
            case 0, 3, 7, 10, 11, 12, 13, 14, 15, 20, 23, 24, 34, 37, 39, 44, 45, 46, 47, 48, 49, 50,
                 131, 140, 149, 151, 162, 163, 164, 165, 167 -> 7;
            case 129 -> 9;
            case 5, 19, 30, 31, 133, 158 -> 10;
            case 6, 32, 33, 134, 160, 161 -> 11;
            default -> 8;
        };
    }

    // decorationBiome gives the biome each chunk decorates with (the 1:4 cell at its centre)
    public static List<BPos> predict(long structureSeed, CarveRegion carve, ToIntFunction<CPos> decorationBiome) {
        ChunkRand rand = new ChunkRand();
        List<BPos> springs = new ArrayList<>();
        for (int i = 0; i < carve.getWidth(); i++) {
            for (int j = 0; j < carve.getWidth(); j++) {
                CPos chunk = new CPos(carve.getMinChunkX() + i, carve.getMinChunkZ() + j);
                int index = springFeatureIndex(decorationBiome.applyAsInt(chunk));
                rand.setDecoratorSeed(structureSeed, chunk.getX() << 4, chunk.getZ() << 4, index, VEGETAL_DECORATION, MCVersion.v1_16_1);
                for (int attempt = 0; attempt < 50; attempt++) {
                    int x = rand.nextInt(16) + (chunk.getX() << 4);
                    int z = rand.nextInt(16) + (chunk.getZ() << 4);
                    int y = rand.nextInt(rand.nextInt(248) + 8);
                    if (isSpring(carve, x, y, z)) {
                        springs.add(new BPos(x, y, z));
                    }
                }
            }
        }
        return springs;
    }

    private static boolean isSpring(CarveRegion carve, int x, int y, int z) {
        if (!isRock(carve, x, y + 1, z) || !isRock(carve, x, y - 1, z)) return false;
        if (!isAir(carve, x, y, z) && !isRock(carve, x, y, z)) return false;

        int rock = 1;
        int air = 0;
        int[][] sides = {{-1, 0}, {1, 0}, {0, -1}, {0, 1}};
        for (int[] side : sides) {
            if (isRock(carve, x + side[0], y, z + side[1])) rock++;
            if (isAir(carve, x + side[0], y, z + side[1])) air++;
        }
        return rock == 4 && air == 1;
    }

    private static boolean isRock(CarveRegion carve, int x, int y, int z) {
        return y >= MIN_ROCK_Y && y <= MAX_ROCK_Y && carve.contains(x, z) && !carve.isCarved(x, y, z)
                && !carve.isFilled(x, y, z);
    }

    private static boolean isAir(CarveRegion carve, int x, int y, int z) {
        return y > LAVA_LEVEL && carve.isCarved(x, y, z);
    }

    /*
    Where the springs' water ends up, following FlowingFluid: water falls while it can, and where it can't it spreads
    sideways with one level less per block (7 blocks from a spring or from where it lands), but only towards the
    nearest spot within 4 blocks where it can fall again - every way if there is none. Lava it lands on turns into
    obsidian it spreads over. Returns every water block with how far it could still spread.
     */
    public static Map<BPos, Integer> flow(List<BPos> springs, CarveRegion carve) {
        Map<BPos, Integer> water = new HashMap<>();
        ArrayDeque<BPos> queue = new ArrayDeque<>();
        Set<BPos> sources = new HashSet<>(springs);
        // water that was already there (from the terrain, or the underwater carvers in ocean chunks) flows into
        // any carved air next to it once something updates it, so it counts as a source as well
        carve.forEachWater((x, y, z) -> sources.add(new BPos(x, y, z)));
        for (BPos source : sources) {
            water.put(source, MAX_SPREAD + 1);
            queue.add(source);
        }

        while (!queue.isEmpty()) {
            BPos pos = queue.poll();
            int amount = water.get(pos);
            BPos below = pos.add(0, -1, 0);
            if (canHold(carve, below)) {
                // falling water spreads 7 blocks again where it lands
                if (!sources.contains(below)) offer(water, queue, below, MAX_SPREAD + 1);
                if (!sources.contains(pos)) continue;
            }
            int spread = amount - 1;
            if (spread <= 0) continue;

            int nearest = Integer.MAX_VALUE;
            List<BPos> targets = new ArrayList<>(4);
            for (int[] side : SIDES) {
                BPos next = pos.add(side[0], 0, side[1]);
                if (!canPassThrough(carve, sources, next)) continue;
                int distance = isHole(carve, next) ? 0 : slopeDistance(carve, sources, next, 1, side);
                if (distance < nearest) {
                    nearest = distance;
                    targets.clear();
                }
                if (distance == nearest) targets.add(next);
            }
            for (BPos target : targets) {
                offer(water, queue, target, spread);
            }
        }
        return water;
    }

    private static final int[][] SIDES = {{-1, 0}, {1, 0}, {0, -1}, {0, 1}};
    private static final int SLOPE_FIND_DISTANCE = 4;

    // FlowingFluid#getSlopeDistance: steps to the nearest spot the water can fall from, not going back the way it came
    private static int slopeDistance(CarveRegion carve, Set<BPos> sources, BPos pos, int depth, int[] cameFrom) {
        int nearest = 1000;
        for (int[] side : SIDES) {
            if (side[0] == -cameFrom[0] && side[1] == -cameFrom[1]) continue;
            BPos next = pos.add(side[0], 0, side[1]);
            if (!canPassThrough(carve, sources, next)) continue;
            if (isHole(carve, next)) return depth;
            if (depth >= SLOPE_FIND_DISTANCE) continue;
            nearest = Math.min(nearest, slopeDistance(carve, sources, next, depth + 1, side));
        }
        return nearest;
    }

    // air water can flow into (lava below Y=11 counts, it turns into obsidian or stone), but not a source block
    private static boolean canPassThrough(CarveRegion carve, Set<BPos> sources, BPos pos) {
        return canHold(carve, pos) && !sources.contains(pos);
    }

    private static boolean canHold(CarveRegion carve, BPos pos) {
        int x = pos.getX(), y = pos.getY(), z = pos.getZ();
        return carve.isCarved(x, y, z) && y > LAVA_LEVEL || carve.isWater(x, y, z);
    }

    // water next to this spot would fall into it
    private static boolean isHole(CarveRegion carve, BPos pos) {
        BPos below = pos.add(0, -1, 0);
        return canHold(carve, below) || carve.isCarved(below.getX(), below.getY(), below.getZ());
    }

    private static void offer(Map<BPos, Integer> water, ArrayDeque<BPos> queue, BPos pos, int amount) {
        Integer known = water.get(pos);
        if (known == null || known < amount) {
            water.put(pos, amount);
            queue.add(pos);
        }
    }
}
