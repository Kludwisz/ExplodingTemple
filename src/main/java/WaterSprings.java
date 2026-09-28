import com.seedfinding.mccore.rand.ChunkRand;
import com.seedfinding.mccore.util.pos.BPos;
import com.seedfinding.mccore.util.pos.CPos;
import com.seedfinding.mccore.version.MCVersion;
import generator.CubiomesCarveRegion;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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
    public static List<BPos> predict(long structureSeed, CubiomesCarveRegion carve, ToIntFunction<CPos> decorationBiome) {
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

    private static boolean isSpring(CubiomesCarveRegion carve, int x, int y, int z) {
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

    private static boolean isRock(CubiomesCarveRegion carve, int x, int y, int z) {
        return y >= MIN_ROCK_Y && y <= MAX_ROCK_Y && carve.contains(x, z) && !carve.isCarved(x, y, z)
                && !carve.isFilled(x, y, z);
    }

    private static boolean isAir(CubiomesCarveRegion carve, int x, int y, int z) {
        return y > LAVA_LEVEL && carve.isCarved(x, y, z);
    }

    /*
    Everywhere the springs' water can end up. Water falls while there is air below, otherwise it spreads 7 blocks
    sideways through air, and lava it touches turns into obsidian it can flow over. Vanilla only spreads towards the
    nearest drop within 4 blocks, spreading every way instead only makes this cover more.
     */
    public static Map<BPos, Integer> flow(List<BPos> springs, CubiomesCarveRegion carve) {
        Map<BPos, Integer> water = new HashMap<>();
        ArrayDeque<BPos> queue = new ArrayDeque<>();
        for (BPos spring : springs) {
            water.put(spring, MAX_SPREAD);
            queue.add(spring);
        }

        while (!queue.isEmpty()) {
            BPos pos = queue.poll();
            int spread = water.get(pos);
            BPos below = pos.add(0, -1, 0);
            if (isAir(carve, below.getX(), below.getY(), below.getZ())) {
                offer(water, queue, below, MAX_SPREAD);
                continue;
            }
            if (spread == 0) continue;

            for (BPos side : new BPos[]{pos.add(-1, 0, 0), pos.add(1, 0, 0), pos.add(0, 0, -1), pos.add(0, 0, 1)}) {
                if (isAir(carve, side.getX(), side.getY(), side.getZ())) {
                    offer(water, queue, side, spread - 1);
                }
            }
        }
        return water;
    }

    private static void offer(Map<BPos, Integer> water, ArrayDeque<BPos> queue, BPos pos, int spread) {
        Integer known = water.get(pos);
        if (known == null || known < spread) {
            water.put(pos, spread);
            queue.add(pos);
        }
    }
}
