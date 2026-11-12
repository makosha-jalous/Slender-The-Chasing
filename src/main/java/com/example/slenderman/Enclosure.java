package com.example.slenderman;

import net.minecraft.core.BlockPos;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.Mth;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;

/**
 * "Is the player inside a structure?" - ONE rule for Slenderman (near teleports inside) and for the ambience (wind / bass).
 * A structure = a roof above the head AND walls around: a lone tree, a bridge, an overhang or an awning is NOT a structure.
 *
 *   roof : a solid block (leaves and plants do not count) within ROOF_HEIGHT blocks above the head;
 *   walls: 8 rays around the head (every 45 degrees), a ray is "closed" when it meets a solid block (not leaves) within WALL_DISTANCE
 *          blocks at the height of the eyes or of the chest;
 *   a structure: the roof and at least WALLS_NEEDED of 8 rays closed.
 */
public final class Enclosure {
    public static final int ROOF_HEIGHT = 12;
    public static final double WALL_DISTANCE = 5.0D;
    public static final int WALLS_NEEDED = 5;

    private Enclosure() {}

    /** the number of the first solid roof block above (blocks above the eyes, 1 = right over the head), or -1 if there is none within maxUp. */
    public static int roofAbove(Level level, double x, double eyeY, double z, int maxUp) {
        int ix = Mth.floor(x), iz = Mth.floor(z), y0 = Mth.floor(eyeY) + 1;
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
        for (int dy = 0; dy < maxUp; dy++) {
            m.set(ix, y0 + dy, iz);
            if (!level.hasChunkAt(m)) return -1;
            if (isSolid(level, m)) return dy + 1;
        }
        return -1;
    }

    /** how many of the 8 directions are closed by a wall within WALL_DISTANCE blocks. */
    public static int closedDirections(Level level, double x, double eyeY, double z) {
        int closed = 0;
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
        double[] heights = { eyeY, eyeY - 0.9D };
        for (int d = 0; d < 8; d++) {
            double a = d * Math.PI / 4.0D;
            double dx = Math.cos(a), dz = Math.sin(a);
            boolean hit = false;
            for (double t = 0.7D; t <= WALL_DISTANCE && !hit; t += 0.5D) {
                for (double h : heights) {
                    m.set(Mth.floor(x + dx * t), Mth.floor(h), Mth.floor(z + dz * t));
                    if (level.hasChunkAt(m) && isSolid(level, m)) { hit = true; break; }
                }
            }
            if (hit) closed++;
        }
        return closed;
    }

    /** the roof within ROOF_HEIGHT + enough walls. */
    public static boolean isEnclosed(Level level, double x, double eyeY, double z) {
        return roofAbove(level, x, eyeY, z, ROOF_HEIGHT) > 0 && closedDirections(level, x, eyeY, z) >= WALLS_NEEDED;
    }

    private static boolean isSolid(Level level, BlockPos pos) {
        BlockState s = level.getBlockState(pos);
        if (s.isAir() || s.is(BlockTags.LEAVES)) return false;
        return !s.getCollisionShape(level, pos).isEmpty();
    }
}
