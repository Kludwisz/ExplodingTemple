package generator;

import com.seedfinding.mccore.util.pos.BPos;
import com.seedfinding.mccore.util.pos.CPos;
import dev.xpple.cubiomes.*;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SegmentAllocator;

/*
Fast native biome checks used to throw away sister seeds before running the slow Java terrain checks.
Holds native memory confined to the creating thread, so use one instance per thread.
 */
public class CubiomesBiomeChecker implements AutoCloseable {
    private final Arena arena = Arena.ofConfined();
    private final MemorySegment generator = Generator.allocate(arena);
    private final SegmentAllocator posAllocator = SegmentAllocator.prefixAllocator(arena.allocate(Pos.layout()));

    public CubiomesBiomeChecker() {
        Cubiomes.setupGenerator(generator, Cubiomes.MC_1_16_1(), 0);
    }

    public void applySeed(long worldSeed) {
        Cubiomes.applySeed(generator, Cubiomes.DIM_OVERWORLD(), worldSeed);
    }

    // the biome 1.16 checks when deciding whether a structure can start in this chunk
    public int getStructureBiome(CPos chunk) {
        return Cubiomes.getBiomeAt(generator, 4, (chunk.getX() << 2) + 2, 0, (chunk.getZ() << 2) + 2);
    }

    // the biome whose carvers 1.16 runs when carving this chunk
    public int getCarverBiome(int chunkX, int chunkZ) {
        return Cubiomes.getBiomeAt(generator, 4, chunkX << 2, 0, chunkZ << 2);
    }

    // the result of the spawn biome search - the world spawn ends up in (or very close to) this chunk
    public BPos estimateSpawn() {
        MemorySegment pos = Cubiomes.estimateSpawn(posAllocator, generator, MemorySegment.NULL);
        return new BPos(Pos.x(pos), 0, Pos.z(pos));
    }

    public static boolean isDesert(int biome) {
        return biome == Cubiomes.desert() || biome == Cubiomes.desert_hills();
    }

    // the biomes with the ocean carvers (fewer caves, and caves below sea level filled with water)
    public static boolean isOcean(int biome) {
        return biome == Cubiomes.ocean() || biome == Cubiomes.deep_ocean() || biome == Cubiomes.frozen_ocean()
                || biome == Cubiomes.deep_frozen_ocean() || biome == Cubiomes.cold_ocean() || biome == Cubiomes.deep_cold_ocean()
                || biome == Cubiomes.lukewarm_ocean() || biome == Cubiomes.deep_lukewarm_ocean()
                || biome == Cubiomes.warm_ocean() || biome == Cubiomes.deep_warm_ocean();
    }

    public static boolean isOutpostBiome(int biome) {
        return biome == Cubiomes.desert() || biome == Cubiomes.plains() || biome == Cubiomes.savanna()
                || biome == Cubiomes.taiga() || biome == Cubiomes.snowy_tundra();
    }

    @Override
    public void close() {
        arena.close();
    }

    static {
        CubiomesInit.load();
    }
}
