package generator;

import com.seedfinding.mccore.rand.ChunkRand;
import com.seedfinding.mccore.util.pos.BPos;
import com.seedfinding.mccore.util.pos.CPos;
import com.seedfinding.mccore.version.MCVersion;
import dev.xpple.cubiomes.*;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

public class CubiomesRavineGenerator {
    private static final ChunkRand rand = new ChunkRand();

    public static boolean canyonGivesRequiredAir(long structureSeed, List<BPos> requiredAir, List<BPos> requiredSolid) {
        Set<CPos> chunkPoses = requiredAir.stream().map(BPos::toChunkPos).collect(Collectors.toSet());
        chunkPoses.addAll(requiredAir.stream().map(BPos::toChunkPos).collect(Collectors.toSet()));

        for (CPos cPos : chunkPoses) {
            var airInChunk = requiredAir.stream().filter(pos -> pos.toChunkPos().equals(cPos)).toList();
            var solidInChunk = requiredSolid.stream().filter(pos -> pos.toChunkPos().equals(cPos)).toList();
            if (!canyonGivesRequiredAir(structureSeed, cPos.getX(), cPos.getZ(), airInChunk, solidInChunk)) {
                return false;
            }
        }

        return true;
    }

    public static boolean canyonGivesRequiredAir(long structureSeed, int chunkX, int chunkZ, List<BPos> requiredAir, List<BPos> requiredSolid) {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment pos3ListPointer = arena.allocate(Pos3List.layout().byteSize()).reinterpret(Pos3List.layout().byteSize());
            MemorySegment cccPointer = arena.allocate(CanyonCarverConfig.layout().byteSize());
            MemorySegment biomesArray = arena.allocate(Cubiomes.C_INT.byteSize() * 17 * 17);

            Cubiomes.createPos3List(pos3ListPointer, 65536);
            Cubiomes.getCanyonCarverConfig(Cubiomes.CANYON_CARVER(), Cubiomes.MC_1_16_1(), cccPointer);

            Cubiomes.carveCanyon(
                    structureSeed,
                    Cubiomes.MC_1_16_1(),
                    chunkX, chunkZ,
                    cccPointer,
                    Cubiomes.CANYON_CARVER(),
                    biomesArray, pos3ListPointer
            );

            // iterate over air positions in the pos list
            HashSet<BPos> requiredAirSet = new HashSet<>(requiredAir);
            HashSet<BPos> requiredSolidSet = new HashSet<>(requiredSolid);
            int gotAir = 0;

            int posListSize = Pos3List.size(pos3ListPointer);
            MemorySegment innerList = Pos3List.pos3s(pos3ListPointer);
            for (int i = 0; i < posListSize; i++) {
                MemorySegment pos = Pos3.asSlice(innerList, i);
                int x = Pos3.x(pos);
                int y = Pos3.y(pos);
                int z = Pos3.z(pos);
                BPos airPos = new BPos(x, y, z);
                if (requiredAirSet.contains(airPos)) {
                    requiredAirSet.remove(airPos);
                    gotAir++;
                }
                if (requiredSolidSet.contains(airPos)) {
                    gotAir = -1;
                    break;
                }
            }

            Cubiomes.freePos3List(pos3ListPointer);

            return gotAir == requiredAir.size();
        }
        catch (Exception ex) {
            System.err.println("Caught exception: " + ex.getMessage());
        }

        return false;
    }

    public static boolean startsAt(long seed, int chunkX, int chunkZ) {
        rand.setCarverSeed(seed + 2, chunkX, chunkZ, MCVersion.v1_16_1);
        return rand.nextFloat() < 0.01F;
    }


    static {
        CubiomesInit.load();
    }
}
