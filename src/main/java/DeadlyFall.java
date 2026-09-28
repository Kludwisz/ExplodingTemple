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
    private static final double[] REACH = new double[66];

    static {
        double y = 65.0, vy = 0.0, vh = 0.22, drift = 0.0;
        int height = 65;
        while (height >= 0) {
            if (y <= 57.0) {
                vh += 0.026;
                drift += vh;
                vh *= 0.91;
            }
            y += vy;
            vy = (vy - 0.08) * 0.98;
            for (; height >= 0 && y <= height; height--) {
                REACH[height] = drift;
            }
        }
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

    // Solid blocks within reach while the fall is still survivable. None of them has air above it, but the TNT
    // blows bits out of the walls when it goes off, and any of these could end up as a ledge.
    public int wallBlocksInReach() {
        int count = 0;
        for (int feetY = LOWEST_SURVIVABLE_FEET_Y; feetY <= CRATER_TOP + 2; feetY++) {
            int range = (int) Math.ceil(REACH[feetY] + PLAYER_HALF_WIDTH) + 2;
            for (int x = shaftMinX - range; x <= shaftMinX + 2 + range; x++) {
                for (int z = shaftMinZ - range; z <= shaftMinZ + 2 + range; z++) {
                    if (distanceToShaft(x, z) > REACH[feetY] + PLAYER_HALF_WIDTH) continue;
                    if (!isAir(x, feetY - 1, z)) count++;
                }
            }
        }
        return count;
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
        if (y > LAVA_LEVEL && carve.isCarved(x, y, z)) return true;
        return y >= CRATER_BOTTOM && y <= CRATER_TOP
                && x >= shaftMinX - 1 && x <= shaftMinX + 3 && z >= shaftMinZ - 1 && z <= shaftMinZ + 3;
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
