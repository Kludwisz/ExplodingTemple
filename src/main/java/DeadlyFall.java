import com.seedfinding.mccore.util.pos.BPos;
import com.seedfinding.mccore.util.pos.CPos;
import generator.CarveRegion;

import java.util.List;
import java.util.Map;

/*
Checks that a player with an empty inventory who digs into the temple shaft after the explosion can't survive the
fall into the ravine.

They fall from the temple floor (feet at Y=65). Fall damage is the distance minus 3 against 20 HP, so landing on
anything at Y<=42 kills them, lava or not. What can save them is water, cobwebs, or something to land on above
that.
 */
public class DeadlyFall {
    private static final int LAVA_LEVEL = 10;
    // 65 - 43 = 22 blocks is 19 damage
    private static final int LOWEST_SURVIVABLE_FEET_Y = 43;
    // the TNT at Y=51 blows the cut sandstone chamber (5x5 around the shaft) open down to the ravine
    private static final int CRATER_BOTTOM = 49;
    private static final int CRATER_TOP = 55;
    // once in lava they only get ~1 block further before burning, water that close still saves them
    private static final double WATER_MARGIN = 2.0;
    private static final double PLAYER_HALF_WIDTH = 0.3;

    // How far a player's position can get from the shaft by the time their feet pass each Y, strafing at full sprint
    // air speed (0.026/tick, 0.91 drag, LivingEntity#travel) from leaving the shaft - taken as Y=57 since the
    // explosion blows the lower shaft walls away - with the 0.22 blocks/tick they can build up inside it.
    private static final double[] REACH = reach(57.0, 0.22);
    // The farthest they could get. Sprint strafing in the air tops out at 0.29 blocks/tick, but crossing the 3x3
    // shaft without hitting a wall on the way down they can leave it at 0.26 at most. When they can leave it depends
    // on how high the explosion opens the shaft walls: in 140 test explosions at three temples that was Y=53 to 56,
    // three times Y=57 and once Y=58. TNT knocked up the shaft could open it higher still, rarely.
    public static final int DEFAULT_WORST_SHAFT_EXIT = 57;
    private static double[] WORST_REACH = reach(DEFAULT_WORST_SHAFT_EXIT, 0.26);
    // a tenth of a block more than that, like the in-game check
    private static final double REACH_MARGIN = 0.1;
    // how high sand or gravel shaken loose by the explosion could pile up on a ledge
    private static final int MAX_PILE = 10;

    // how high up the shaft isRobust assumes the explosion can open the walls, set before any search starts
    public static void setWorstShaftExit(int y) {
        WORST_REACH = reach(y, 0.26);
    }

    private static double[] reach(double exitY, double exitSpeed) {
        double[] reach = new double[66];
        double y = 65.0, vy = 0.0, vh = exitSpeed, drift = 0.0;
        int height = 65;
        while (height >= 0) {
            if (y <= exitY) {
                vh += 0.026;
                drift += vh;
                vh *= 0.91;
            }
            y += vy;
            vy = (vy - 0.08) * 0.98;
            for (; height >= 0 && y <= height; height--) {
                reach[height] = drift;
            }
        }
        return reach;
    }

    private final CarveRegion carve;
    private final int shaftMinX;
    private final int shaftMinZ;

    public DeadlyFall(CarveRegion carve, CPos templeChunk) {
        this.carve = carve;
        this.shaftMinX = (templeChunk.getX() << 4) + 9;
        this.shaftMinZ = (templeChunk.getZ() << 4) + 9;
    }

    // whether falling straight down ends in the lava at the bottom of the ravine rather than on stone
    public boolean landsInLava() {
        for (int x = shaftMinX; x <= shaftMinX + 2; x++) {
            for (int z = shaftMinZ; z <= shaftMinZ + 2; z++) {
                if (!carve.isCarved(x, LAVA_LEVEL, z)) return false;
            }
        }
        return true;
    }

    // Whether the fall stays deadly however the TNT goes off. The TNT the first explosion knocks into the ravine goes
    // off below the crater and blows holes into any rock close by, and the bottom of a hole is a ledge. So below the
    // crater there must be no rock at all within the farthest the player could ever steer. Around the chamber the
    // rock has to start above Y=50, so that whatever the explosion blows out of it has nothing underneath. There
    // must be no ledge in that reach to begin with, and none a little below it where loose sand and gravel could
    // pile up.
    public boolean isRobust() {
        for (int feetY = LOWEST_SURVIVABLE_FEET_Y; feetY <= CRATER_BOTTOM + 2; feetY++) {
            double reach = WORST_REACH[feetY] + REACH_MARGIN;
            int range = (int) Math.ceil(reach) + 1;
            for (int x = shaftMinX - range; x <= shaftMinX + 2 + range; x++) {
                for (int z = shaftMinZ - range; z <= shaftMinZ + 2 + range; z++) {
                    if (need(x, z) > reach) continue;
                    int y = feetY - 1;
                    // the chamber's floor, which the explosion blows away
                    if (y == CRATER_BOTTOM + 1 && isChamber(x, z)) continue;
                    if (!carve.isCarved(x, y, z)) return false;
                }
            }
        }
        // Sand and gravel the explosion shakes loose fall straight down and pile up where they land. A pile on a
        // ledge a little below Y=42 can reach up to where landing is survivable (2 of 100 test explosions at one
        // temple piled gravel 3 high on a ledge at Y=39), so there must be no ledge that close below it either.
        double pileReach = WORST_REACH[LOWEST_SURVIVABLE_FEET_Y] + REACH_MARGIN;
        int pileRange = (int) Math.ceil(pileReach) + 1;
        for (int y = LOWEST_SURVIVABLE_FEET_Y - 1 - MAX_PILE; y < LOWEST_SURVIVABLE_FEET_Y - 1; y++) {
            for (int x = shaftMinX - pileRange; x <= shaftMinX + 2 + pileRange; x++) {
                for (int z = shaftMinZ - pileRange; z <= shaftMinZ + 2 + pileRange; z++) {
                    if (need(x, z) <= pileReach && !carve.isCarved(x, y, z)) return false;
                }
            }
        }
        for (int feetY = LOWEST_SURVIVABLE_FEET_Y; feetY <= CRATER_TOP + 3; feetY++) {
            double reach = WORST_REACH[feetY] + REACH_MARGIN;
            int range = (int) Math.ceil(reach) + 1;
            for (int x = shaftMinX - range; x <= shaftMinX + 2 + range; x++) {
                for (int z = shaftMinZ - range; z <= shaftMinZ + 2 + range; z++) {
                    if (need(x, z) <= reach && isAir(x, feetY, z) && !isAir(x, feetY - 1, z)) return false;
                }
            }
        }
        return true;
    }

    // How much farther than the worst-case reach the closest rock below the crater is, in blocks (the smallest
    // margin over Y=42 to 50 with the chamber floor left out). isRobust needs at least REACH_MARGIN.
    public double wallMarginBelowCrater() {
        double margin = Double.MAX_VALUE;
        for (int feetY = LOWEST_SURVIVABLE_FEET_Y; feetY <= CRATER_BOTTOM + 2; feetY++) {
            int range = (int) Math.ceil(WORST_REACH[feetY]) + 4;
            for (int x = shaftMinX - range; x <= shaftMinX + 2 + range; x++) {
                for (int z = shaftMinZ - range; z <= shaftMinZ + 2 + range; z++) {
                    int y = feetY - 1;
                    if (y == CRATER_BOTTOM + 1 && isChamber(x, z)) continue;
                    if (!carve.isCarved(x, y, z)) margin = Math.min(margin, need(x, z) - WORST_REACH[feetY]);
                }
            }
        }
        return margin;
    }

    // nothing within reach to land on high enough to survive the fall
    public boolean hasNoSurvivableLedge() {
        for (int feetY = LOWEST_SURVIVABLE_FEET_Y; feetY <= CRATER_TOP + 2; feetY++) {
            int range = (int) Math.ceil(REACH[feetY] + PLAYER_HALF_WIDTH) + 2;
            for (int x = shaftMinX - range; x <= shaftMinX + 2 + range; x++) {
                for (int z = shaftMinZ - range; z <= shaftMinZ + 2 + range; z++) {
                    if (distanceToShaft(x, z) > REACH[feetY] + PLAYER_HALF_WIDTH) continue;
                    if (isAir(x, feetY, z) && !isAir(x, feetY - 1, z)) return false;
                }
            }
        }
        return true;
    }

    // no mineshaft plank floors or cobwebs (pieces place bridge planks one block under their box) within reach
    public boolean isClearOf(List<Mineshafts.Box> mineshaftPieces) {
        for (Mineshafts.Box box : mineshaftPieces) {
            double minCenter = PLAYER_HALF_WIDTH;
            double maxCenter = 3 - PLAYER_HALF_WIDTH;
            double dx = Math.max(0, Math.max(shaftMinX + minCenter - (box.x1() + 1), box.x0() - (shaftMinX + maxCenter)));
            double dz = Math.max(0, Math.max(shaftMinZ + minCenter - (box.z1() + 1), box.z0() - (shaftMinZ + maxCenter)));
            double distance = Math.hypot(dx, dz);
            for (int y = Math.max(box.y0() - 1, LAVA_LEVEL); y <= Math.min(box.y1(), CRATER_TOP + 2); y++) {
                if (distance <= REACH[y] + PLAYER_HALF_WIDTH + WATER_MARGIN) return false;
            }
        }
        return true;
    }

    // none of the water gets anywhere the player can fall into, or reach after landing in the lava
    public boolean staysDry(Map<BPos, Integer> water) {
        for (BPos pos : water.keySet()) {
            int y = pos.getY();
            if (y <= LAVA_LEVEL || y > CRATER_TOP + 2) continue;
            if (distanceToShaft(pos.getX(), pos.getZ()) <= REACH[y] + PLAYER_HALF_WIDTH + WATER_MARGIN) return false;
        }
        return true;
    }

    private boolean isAir(int x, int y, int z) {
        if (isChamber(x, z)) {
            // the crater, and above it the 3x3 shaft inside its sandstone walls up to the floor
            if (y >= CRATER_BOTTOM && y <= CRATER_TOP) return true;
            if (y > CRATER_TOP && y < 64) return x >= shaftMinX && x <= shaftMinX + 2 && z >= shaftMinZ && z <= shaftMinZ + 2;
        }
        return y > LAVA_LEVEL && carve.isCarved(x, y, z);
    }

    // the chamber and the blocks around it that the explosion blows away, 5x5 around the shaft
    private boolean isChamber(int x, int z) {
        return x >= shaftMinX - 1 && x <= shaftMinX + 3 && z >= shaftMinZ - 1 && z <= shaftMinZ + 3;
    }

    // How far the player's position has to move from anywhere in the shaft for their 0.6 wide hitbox to overlap
    // the block column, horizontally in any direction.
    private double need(int x, int z) {
        double dx = Math.max(0, Math.max(shaftMinX - x - 1.0, x - shaftMinX - 3.0));
        double dz = Math.max(0, Math.max(shaftMinZ - z - 1.0, z - shaftMinZ - 3.0));
        return Math.hypot(dx, dz);
    }

    // distance from a block column to the positions a player can have inside the shaft
    private double distanceToShaft(int x, int z) {
        double minCenter = PLAYER_HALF_WIDTH;
        double maxCenter = 3 - PLAYER_HALF_WIDTH;
        double dx = Math.max(0, Math.max(shaftMinX + minCenter - (x + 1), x - (shaftMinX + maxCenter)));
        double dz = Math.max(0, Math.max(shaftMinZ + minCenter - (z + 1), z - (shaftMinZ + maxCenter)));
        return Math.hypot(dx, dz);
    }
}
