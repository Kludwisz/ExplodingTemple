import com.seedfinding.mccore.rand.ChunkRand;
import com.seedfinding.mccore.util.pos.CPos;
import com.seedfinding.mccore.version.MCVersion;

import java.util.ArrayList;
import java.util.List;

/*
Mineshaft pieces near the temple. Corridors that cross the ravine put plank floors and cobwebs into it, which break
a fall, and their air lets springs flood it. This is the 1.16.1 MineShaftPieces layout (only the bounding boxes)
for normal mineshafts, which is what generates around deserts.
 */
public class Mineshafts {
    // MineshaftConfiguration(0.004f, NORMAL)
    private static final double CHANCE = 0.004f;
    // pieces never get further than this from the room they started at
    private static final int MAX_PIECE_DISTANCE = 80;
    private static final int SEA_LEVEL = 63;

    public record Box(int x0, int y0, int z0, int x1, int y1, int z1) {
        public boolean intersects(Box o) {
            return x1 >= o.x0 && x0 <= o.x1 && z1 >= o.z0 && z0 <= o.z1 && y1 >= o.y0 && y0 <= o.y1;
        }

        Box moved(int dy) {
            return new Box(x0, y0 + dy, z0, x1, y1 + dy, z1);
        }
    }

    private enum Dir { NORTH, SOUTH, WEST, EAST }

    // the boxes of every mineshaft piece that can reach blocks within the given chunk radius of the centre
    public static List<Box> near(long structureSeed, CPos center, int chunkRadius) {
        int startRadius = chunkRadius + MAX_PIECE_DISTANCE / 16 + 2;
        ChunkRand rand = new ChunkRand();
        List<Box> boxes = new ArrayList<>();
        for (int chunkX = center.getX() - startRadius; chunkX <= center.getX() + startRadius; chunkX++) {
            for (int chunkZ = center.getZ() - startRadius; chunkZ <= center.getZ() + startRadius; chunkZ++) {
                rand.setCarverSeed(structureSeed, chunkX, chunkZ, MCVersion.v1_16_1);
                if (rand.nextDouble() < CHANCE) {
                    boxes.addAll(generate(structureSeed, chunkX, chunkZ));
                }
            }
        }
        return boxes;
    }

    static List<Box> generate(long structureSeed, int chunkX, int chunkZ) {
        ChunkRand random = new ChunkRand();
        random.setCarverSeed(structureSeed, chunkX, chunkZ, MCVersion.v1_16_1);

        int x = (chunkX << 4) + 2;
        int z = (chunkZ << 4) + 2;
        Piece room = new Piece(0, new Box(x, 50, z, x + 7 + random.nextInt(6), 54 + random.nextInt(6), z + 7 + random.nextInt(6)), null, Kind.ROOM);
        List<Piece> pieces = new ArrayList<>();
        pieces.add(room);
        room.addChildren(room, pieces, random);

        // StructureStart#moveBelowSeaLevel
        int minY = Integer.MAX_VALUE, maxY = Integer.MIN_VALUE;
        for (Piece piece : pieces) {
            minY = Math.min(minY, piece.box.y0);
            maxY = Math.max(maxY, piece.box.y1);
        }
        int top = SEA_LEVEL - 10;
        int newMaxY = maxY - minY + 1 + 1;
        if (newMaxY < top) newMaxY += random.nextInt(top - newMaxY);
        int dy = newMaxY - maxY;

        List<Box> boxes = new ArrayList<>(pieces.size());
        for (Piece piece : pieces) {
            boxes.add(piece.box.moved(dy));
        }
        return boxes;
    }

    private enum Kind { ROOM, CORRIDOR, CROSSING, STAIRS }

    private static final class Piece {
        final int depth;
        final Box box;
        final Dir dir;
        final Kind kind;

        Piece(int depth, Box box, Dir dir, Kind kind) {
            this.depth = depth;
            this.box = box;
            this.dir = dir;
            this.kind = kind;
        }

        void addChildren(Piece start, List<Piece> pieces, ChunkRand random) {
            switch (kind) {
                case ROOM -> addRoomChildren(start, pieces, random);
                case CORRIDOR -> addCorridorChildren(start, pieces, random);
                case CROSSING -> addCrossingChildren(start, pieces, random);
                case STAIRS -> addStairsChildren(start, pieces, random);
            }
        }

        private void addRoomChildren(Piece start, List<Piece> pieces, ChunkRand random) {
            int ySpan = box.y1 - box.y0 + 1;
            int xSpan = box.x1 - box.x0 + 1;
            int zSpan = box.z1 - box.z0 + 1;
            int yRange = Math.max(ySpan - 3 - 1, 1);
            for (int k = 0; k < xSpan; k += 4) {
                if ((k += random.nextInt(xSpan)) + 3 > xSpan) break;
                add(start, pieces, random, box.x0 + k, box.y0 + random.nextInt(yRange) + 1, box.z0 - 1, Dir.NORTH, depth);
            }
            for (int k = 0; k < xSpan; k += 4) {
                if ((k += random.nextInt(xSpan)) + 3 > xSpan) break;
                add(start, pieces, random, box.x0 + k, box.y0 + random.nextInt(yRange) + 1, box.z1 + 1, Dir.SOUTH, depth);
            }
            for (int k = 0; k < zSpan; k += 4) {
                if ((k += random.nextInt(zSpan)) + 3 > zSpan) break;
                add(start, pieces, random, box.x0 - 1, box.y0 + random.nextInt(yRange) + 1, box.z0 + k, Dir.WEST, depth);
            }
            for (int k = 0; k < zSpan; k += 4) {
                if ((k += random.nextInt(zSpan)) + 3 > zSpan) break;
                add(start, pieces, random, box.x1 + 1, box.y0 + random.nextInt(yRange) + 1, box.z0 + k, Dir.EAST, depth);
            }
        }

        private void addCorridorChildren(Piece start, List<Piece> pieces, ChunkRand random) {
            int branch = random.nextInt(4);
            switch (dir) {
                case NORTH -> {
                    if (branch <= 1) add(start, pieces, random, box.x0, box.y0 - 1 + random.nextInt(3), box.z0 - 1, dir, depth);
                    else if (branch == 2) add(start, pieces, random, box.x0 - 1, box.y0 - 1 + random.nextInt(3), box.z0, Dir.WEST, depth);
                    else add(start, pieces, random, box.x1 + 1, box.y0 - 1 + random.nextInt(3), box.z0, Dir.EAST, depth);
                }
                case SOUTH -> {
                    if (branch <= 1) add(start, pieces, random, box.x0, box.y0 - 1 + random.nextInt(3), box.z1 + 1, dir, depth);
                    else if (branch == 2) add(start, pieces, random, box.x0 - 1, box.y0 - 1 + random.nextInt(3), box.z1 - 3, Dir.WEST, depth);
                    else add(start, pieces, random, box.x1 + 1, box.y0 - 1 + random.nextInt(3), box.z1 - 3, Dir.EAST, depth);
                }
                case WEST -> {
                    if (branch <= 1) add(start, pieces, random, box.x0 - 1, box.y0 - 1 + random.nextInt(3), box.z0, dir, depth);
                    else if (branch == 2) add(start, pieces, random, box.x0, box.y0 - 1 + random.nextInt(3), box.z0 - 1, Dir.NORTH, depth);
                    else add(start, pieces, random, box.x0, box.y0 - 1 + random.nextInt(3), box.z1 + 1, Dir.SOUTH, depth);
                }
                case EAST -> {
                    if (branch <= 1) add(start, pieces, random, box.x1 + 1, box.y0 - 1 + random.nextInt(3), box.z0, dir, depth);
                    else if (branch == 2) add(start, pieces, random, box.x1 - 3, box.y0 - 1 + random.nextInt(3), box.z0 - 1, Dir.NORTH, depth);
                    else add(start, pieces, random, box.x1 - 3, box.y0 - 1 + random.nextInt(3), box.z1 + 1, Dir.SOUTH, depth);
                }
            }
            if (depth >= 8) return;

            if (dir == Dir.NORTH || dir == Dir.SOUTH) {
                for (int k = box.z0 + 3; k + 3 <= box.z1; k += 5) {
                    int side = random.nextInt(5);
                    if (side == 0) add(start, pieces, random, box.x0 - 1, box.y0, k, Dir.WEST, depth + 1);
                    else if (side == 1) add(start, pieces, random, box.x1 + 1, box.y0, k, Dir.EAST, depth + 1);
                }
            } else {
                for (int k = box.x0 + 3; k + 3 <= box.x1; k += 5) {
                    int side = random.nextInt(5);
                    if (side == 0) add(start, pieces, random, k, box.y0, box.z0 - 1, Dir.NORTH, depth + 1);
                    else if (side == 1) add(start, pieces, random, k, box.y0, box.z1 + 1, Dir.SOUTH, depth + 1);
                }
            }
        }

        private void addCrossingChildren(Piece start, List<Piece> pieces, ChunkRand random) {
            switch (dir) {
                case NORTH -> {
                    add(start, pieces, random, box.x0 + 1, box.y0, box.z0 - 1, Dir.NORTH, depth);
                    add(start, pieces, random, box.x0 - 1, box.y0, box.z0 + 1, Dir.WEST, depth);
                    add(start, pieces, random, box.x1 + 1, box.y0, box.z0 + 1, Dir.EAST, depth);
                }
                case SOUTH -> {
                    add(start, pieces, random, box.x0 + 1, box.y0, box.z1 + 1, Dir.SOUTH, depth);
                    add(start, pieces, random, box.x0 - 1, box.y0, box.z0 + 1, Dir.WEST, depth);
                    add(start, pieces, random, box.x1 + 1, box.y0, box.z0 + 1, Dir.EAST, depth);
                }
                case WEST -> {
                    add(start, pieces, random, box.x0 + 1, box.y0, box.z0 - 1, Dir.NORTH, depth);
                    add(start, pieces, random, box.x0 + 1, box.y0, box.z1 + 1, Dir.SOUTH, depth);
                    add(start, pieces, random, box.x0 - 1, box.y0, box.z0 + 1, Dir.WEST, depth);
                }
                case EAST -> {
                    add(start, pieces, random, box.x0 + 1, box.y0, box.z0 - 1, Dir.NORTH, depth);
                    add(start, pieces, random, box.x0 + 1, box.y0, box.z1 + 1, Dir.SOUTH, depth);
                    add(start, pieces, random, box.x1 + 1, box.y0, box.z0 + 1, Dir.EAST, depth);
                }
            }
            if (box.y1 - box.y0 + 1 > 3) {
                if (random.nextBoolean()) add(start, pieces, random, box.x0 + 1, box.y0 + 3 + 1, box.z0 - 1, Dir.NORTH, depth);
                if (random.nextBoolean()) add(start, pieces, random, box.x0 - 1, box.y0 + 3 + 1, box.z0 + 1, Dir.WEST, depth);
                if (random.nextBoolean()) add(start, pieces, random, box.x1 + 1, box.y0 + 3 + 1, box.z0 + 1, Dir.EAST, depth);
                if (random.nextBoolean()) add(start, pieces, random, box.x0 + 1, box.y0 + 3 + 1, box.z1 + 1, Dir.SOUTH, depth);
            }
        }

        private void addStairsChildren(Piece start, List<Piece> pieces, ChunkRand random) {
            switch (dir) {
                case NORTH -> add(start, pieces, random, box.x0, box.y0, box.z0 - 1, Dir.NORTH, depth);
                case SOUTH -> add(start, pieces, random, box.x0, box.y0, box.z1 + 1, Dir.SOUTH, depth);
                case WEST -> add(start, pieces, random, box.x0 - 1, box.y0, box.z0, Dir.WEST, depth);
                case EAST -> add(start, pieces, random, box.x1 + 1, box.y0, box.z0, Dir.EAST, depth);
            }
        }
    }

    // MineShaftPieces#generateAndAddPiece
    private static void add(Piece start, List<Piece> pieces, ChunkRand random, int x, int y, int z, Dir dir, int depth) {
        if (depth > 8) return;
        if (Math.abs(x - start.box.x0) > MAX_PIECE_DISTANCE || Math.abs(z - start.box.z0) > MAX_PIECE_DISTANCE) return;

        Piece piece = createRandomPiece(pieces, random, x, y, z, dir, depth + 1);
        if (piece != null) {
            pieces.add(piece);
            piece.addChildren(start, pieces, random);
        }
    }

    // MineShaftPieces#createRandomShaftPiece
    private static Piece createRandomPiece(List<Piece> pieces, ChunkRand random, int x, int y, int z, Dir dir, int depth) {
        int roll = random.nextInt(100);
        if (roll >= 80) {
            Box box = findCrossing(pieces, random, x, y, z, dir);
            return box == null ? null : new Piece(depth, box, dir, Kind.CROSSING);
        } else if (roll >= 70) {
            Box box = findStairs(pieces, x, y, z, dir);
            return box == null ? null : new Piece(depth, box, dir, Kind.STAIRS);
        }
        Box box = findCorridor(pieces, random, x, y, z, dir);
        if (box == null) return null;
        // the corridor constructor rolls for rails and a spider corridor
        if (random.nextInt(3) != 0) random.nextInt(23);
        return new Piece(depth, box, dir, Kind.CORRIDOR);
    }

    private static Box findCrossing(List<Piece> pieces, ChunkRand random, int x, int y, int z, Dir dir) {
        int y1 = y + 3 - 1;
        if (random.nextInt(4) == 0) y1 += 4;
        Box box = switch (dir) {
            case NORTH -> new Box(x - 1, y, z - 4, x + 3, y1, z);
            case SOUTH -> new Box(x - 1, y, z, x + 3, y1, z + 3 + 1);
            case WEST -> new Box(x - 4, y, z - 1, x, y1, z + 3);
            case EAST -> new Box(x, y, z - 1, x + 3 + 1, y1, z + 3);
        };
        return collides(pieces, box) ? null : box;
    }

    private static Box findStairs(List<Piece> pieces, int x, int y, int z, Dir dir) {
        Box box = switch (dir) {
            case NORTH -> new Box(x, y - 5, z - 8, x + 3 - 1, y + 3 - 1, z);
            case SOUTH -> new Box(x, y - 5, z, x + 3 - 1, y + 3 - 1, z + 8);
            case WEST -> new Box(x - 8, y - 5, z, x, y + 3 - 1, z + 3 - 1);
            case EAST -> new Box(x, y - 5, z, x + 8, y + 3 - 1, z + 3 - 1);
        };
        return collides(pieces, box) ? null : box;
    }

    private static Box findCorridor(List<Piece> pieces, ChunkRand random, int x, int y, int z, Dir dir) {
        for (int sections = random.nextInt(3) + 2; sections > 0; sections--) {
            int length = sections * 5;
            Box box = switch (dir) {
                case NORTH -> new Box(x, y, z - (length - 1), x + 3 - 1, y + 3 - 1, z);
                case SOUTH -> new Box(x, y, z, x + 3 - 1, y + 3 - 1, z + length - 1);
                case WEST -> new Box(x - (length - 1), y, z, x, y + 3 - 1, z + 3 - 1);
                case EAST -> new Box(x, y, z, x + length - 1, y + 3 - 1, z + 3 - 1);
            };
            if (!collides(pieces, box)) return box;
        }
        return null;
    }

    private static boolean collides(List<Piece> pieces, Box box) {
        for (Piece piece : pieces) {
            if (piece.box.intersects(box)) return true;
        }
        return false;
    }
}
