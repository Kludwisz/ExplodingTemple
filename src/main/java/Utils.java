import com.seedfinding.mccore.util.pos.BPos;
import com.seedfinding.mccore.util.pos.CPos;

public class Utils {
    public static String tp(BPos pos) {
        return String.format("/tp %d %d %d", pos.getX(), pos.getY(), pos.getZ());
    }

    public static String tp(CPos pos) {
        return String.format("/tp %d %d %d", pos.getX()*16, 100, pos.getZ()*16);
    }

    public static boolean withinShaftFootprint(int x, int z) {
        x &= 15;
        z &= 15;
        return x >= 9 && x <= 11 && z >= 9 && z <= 11;
    }
}
