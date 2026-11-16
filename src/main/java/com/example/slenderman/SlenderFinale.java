package com.example.slenderman;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.event.entity.living.LivingAttackEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.network.PacketDistributor;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * THE FINALE (after the 8th page). It runs on the server, one state per player (in memory: a relog or a death ends it).
 *
 *   CALM   the 8th page is taken: sanity 0 at once, every Slenderman is gone, the page music is cut, the camera flashes for 1 s
 *          (the camera mod, see PageAudio / CameraEffects.finaleFlash), then a calm of a few seconds.
 *   TURN   he appears right behind the player (2 blocks, the tentacles grow with the sanity loss), the player cannot walk, the view
 *          slowly turns to him (FinaleClient) until the sanity is 100 (the normal rule: that is fast so close, but no fixed seconds).
 *   RUN    at 100: flash (1 s of Static Heavy + grain + colour split), the player is thrown 20-40 blocks away, he stands 3-4 blocks
 *          behind, and the player RUNS by himself for 5 s (only the view can be turned). Then flash + teleport again.
 *          4-5 runs in all.
 *   BLACK  after the last run: flash, the screen goes black (3 s, then 2 s of fading in); under the black the player is moved to his
 *          spawn point, the day becomes night / the night becomes day and he is gone.
 */
@Mod.EventBusSubscriber(modid = SlendermanMod.ID)
public final class SlenderFinale {
    /** false = the finale of the 8th page is switched off (the 8th page then only counts as a page). */
    public static final boolean ENABLED = true;

    /** the calm after the flash, in SECONDS (the player said "5-7 seconds"; the first idea was minutes: change the two numbers to 300 / 420). */
    public static final int CALM_MIN_SECONDS = 5;
    public static final int CALM_MAX_SECONDS = 7;
    /** how far BEHIND the player he appears for the turn (blocks). There is no fixed time: the sanity goes by the normal rule and the stage ends when it is 100. */
    public static final double TURN_DISTANCE = 2.0D;
    /** one run, seconds; the number of runs is drawn between the two values. */
    public static final double RUN_SECONDS = 5.0D;
    public static final int RUNS_MIN = 4;
    public static final int RUNS_MAX = 5;
    /** the player is thrown this far (blocks) away at every flash. */
    public static final double TP_MIN = 20.0D, TP_MAX = 40.0D;
    /** he stands this far behind the player while he runs (blocks): 3-4. */
    public static final double CHASE_DISTANCE = 3.5D;
    /** the black screen: full black, then fading in (seconds). */
    public static final double BLACK_SECONDS = 3.0D;
    public static final double FADE_SECONDS = 2.0D;
    /** after the black screen the camera mod wakes the heroine up: she lies on her side (3 s, from the start of the fade), then the raising (the longest file is 2.5 s). The player cannot move all this time. */
    public static final double WAKE_LIE_SECONDS = 3.0D;
    public static final double WAKE_RAISE_SECONDS = 2.5D;
    public static final double WAKE_MARGIN_SECONDS = 0.2D;

    public static final int STAGE_NONE = 0;
    public static final int STAGE_CALM = 1;
    public static final int STAGE_TURN = 2;
    public static final int STAGE_RUN = 3;
    public static final int STAGE_BLACK = 4;

    private static final class State {
        int stage = STAGE_CALM;
        int ticks;
        int run, runs;
        UUID slender;
        Vec3 last;
    }

    private static final Map<UUID, State> STATES = new HashMap<>();

    private SlenderFinale() {}

    public static int stage(Player player) {
        State st = STATES.get(player.getUUID());
        return st == null ? STAGE_NONE : st.stage;
    }

    public static boolean active(Player player) {
        return STATES.containsKey(player.getUUID());
    }

    // ------------------------------------------------------------------ start / end

    /** the 8th page has just been taken. */
    public static void start(ServerPlayer player) {
        SlenderSanity.reset(player);
        for (SlendermanEntity slender : player.level.getEntitiesOfClass(SlendermanEntity.class,
                player.getBoundingBox().inflate(256.0D))) {
            slender.discard();
        }
        State st = new State();
        int seconds = CALM_MIN_SECONDS + player.getRandom().nextInt(CALM_MAX_SECONDS - CALM_MIN_SECONDS + 1);
        st.ticks = seconds * 20;
        STATES.put(player.getUUID(), st);
    }

    /** a new life, /reset, a relog, a death: no finale is running any more (the client is told, his entity goes away). */
    public static void clear(Player player) {
        State st = STATES.remove(player.getUUID());
        if (st == null) return;
        discardSlender(player.level, st);
        if (player instanceof ServerPlayer sp) sendStage(sp, STAGE_NONE, 0);
    }

    private static void discardSlender(Level level, State st) {
        if (st.slender != null && level instanceof ServerLevel sl) {
            net.minecraft.world.entity.Entity e = sl.getEntity(st.slender);
            if (e != null) e.discard();
        }
        st.slender = null;
    }

    private static void sendStage(ServerPlayer player, int stage, int entityId) {
        SlenderNetwork.CHANNEL.send(PacketDistributor.PLAYER.with(() -> player),
                new SlenderFinaleMessage(SlenderFinaleMessage.TYPE_STAGE, stage, entityId));
    }

    private static void sendFlash(ServerPlayer player) {
        SlenderNetwork.CHANNEL.send(PacketDistributor.PLAYER.with(() -> player),
                new SlenderFinaleMessage(SlenderFinaleMessage.TYPE_FLASH, 0, 0));
    }

    // ------------------------------------------------------------------ the tick

    @SubscribeEvent
    public static void onPlayerTick(TickEvent.PlayerTickEvent event) {
        if (event.phase != TickEvent.Phase.END || !(event.player instanceof ServerPlayer player)) return;
        State st = STATES.get(player.getUUID());
        if (st == null) return;
        if (!player.isAlive() || player.isSpectator()) {
            clear(player);
            return;
        }
        switch (st.stage) {
            case STAGE_CALM -> {
                if (--st.ticks <= 0) startTurn(player, st);
            }
            case STAGE_TURN -> tickTurn(player, st);
            case STAGE_RUN -> tickRun(player, st);
            case STAGE_BLACK -> {
                if (--st.ticks <= 0) clear(player);
            }
            default -> clear(player);
        }
    }

    // ---- TURN
    private static void startTurn(ServerPlayer player, State st) {
        ServerLevel level = (ServerLevel) player.level;
        SlendermanEntity slender = SlendermanMod.SLENDERMAN.get().create(level);
        if (slender == null) { clear(player); return; }
        // 2 blocks directly behind the player's back (a free place near that point if the exact one is taken)
        Vec3 look = player.getLookAngle();
        double lx = look.x, lz = look.z;
        double len = Math.sqrt(lx * lx + lz * lz);
        if (len < 1.0E-3D) { lx = 0.0D; lz = 1.0D; len = 1.0D; }
        lx /= len; lz /= len;
        Vec3 spot = null;
        for (int i = 0; i < 24 && spot == null; i++) {
            double spread = i == 0 ? 0.0D : (player.getRandom().nextDouble() - 0.5D) * 1.2D;
            double d = TURN_DISTANCE + (i == 0 ? 0.0D : player.getRandom().nextDouble() * 0.8D);
            spot = standSpot(level, player.getX() - lx * d - lz * spread, player.getZ() - lz * d + lx * spread, player.getY() + 2.0D, 6);
        }
        if (spot == null) spot = new Vec3(player.getX() - lx * TURN_DISTANCE, player.getY(), player.getZ() - lz * TURN_DISTANCE);
        slender.setFinaleMode(true);
        slender.setFinaleRealSanity(true);
        slender.moveTo(spot.x, spot.y, spot.z, yawToward(spot, player.position()), 0.0F);
        level.addFreshEntity(slender);
        st.slender = slender.getUUID();
        st.stage = STAGE_TURN;
        st.ticks = 0;
        sendStage(player, STAGE_TURN, slender.getId());
    }

    private static void tickTurn(ServerPlayer player, State st) {
        player.setDeltaMovement(0.0D, Math.min(0.0D, player.getDeltaMovement().y), 0.0D);
        // the sanity is moved by the Slenderman entity (finale mode, normal rule); at 100 the runs begin
        if (SlenderSanity.get(player) >= SlenderSanity.MAX) {
            net.minecraft.world.entity.Entity e = st.slender == null ? null : ((ServerLevel) player.level).getEntity(st.slender);
            if (e instanceof SlendermanEntity sl) sl.setFinaleRealSanity(false);
            beginRun(player, st, true);
        }
    }

    // ---- RUN
    private static void beginRun(ServerPlayer player, State st, boolean first) {
        ServerLevel level = (ServerLevel) player.level;
        if (first) {
            st.run = 0;
            st.runs = RUNS_MIN + player.getRandom().nextInt(RUNS_MAX - RUNS_MIN + 1);
        }
        st.run++;
        st.stage = STAGE_RUN;
        st.ticks = (int) (RUN_SECONDS * 20.0D);
        SlenderSanity.set(player, SlenderSanity.MAX);
        sendFlash(player);
        net.minecraft.world.entity.Entity e = st.slender == null ? null : level.getEntity(st.slender);

        // the player is thrown 20-40 blocks away ...
        Vec3 to = null;
        for (int i = 0; i < 60 && to == null; i++) {
            double a = player.getRandom().nextDouble() * Math.PI * 2.0D;
            double d = TP_MIN + player.getRandom().nextDouble() * (TP_MAX - TP_MIN);
            double x = player.getX() + Math.cos(a) * d, z = player.getZ() + Math.sin(a) * d;
            if (!level.hasChunkAt(new BlockPos(Mth.floor(x), 64, Mth.floor(z)))) continue;
            int top = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, Mth.floor(x), Mth.floor(z));
            to = standSpot(level, x, z, top + 1.0D, 6);
        }
        if (to == null) to = player.position();
        // ... and he is 3-4 blocks from him, so the player runs AWAY from him
        Vec3 sl = null;
        for (int i = 0; i < 24 && sl == null; i++) {
            double a = player.getRandom().nextDouble() * Math.PI * 2.0D;
            double d = 3.0D + player.getRandom().nextDouble();                  // 3-4 blocks
            sl = standSpot(level, to.x + Math.cos(a) * d, to.z + Math.sin(a) * d, to.y + 2.0D, 5);
        }
        if (sl == null) sl = new Vec3(to.x + CHASE_DISTANCE, to.y, to.z);
        float yaw = yawToward(sl, to);          // he is behind, the player looks the other way
        player.connection.teleport(to.x, to.y, to.z, yaw, 8.0F);
        player.setDeltaMovement(Vec3.ZERO);
        player.fallDistance = 0.0F;
        if (e != null) {
            e.moveTo(sl.x, sl.y, sl.z, yawToward(sl, to), 0.0F);
        }
        st.last = to;
        sendStage(player, STAGE_RUN, e == null ? 0 : e.getId());
    }

    private static void tickRun(ServerPlayer player, State st) {
        ServerLevel level = (ServerLevel) player.level;
        // the heroine must be able to run: the hunger bar never stops the sprint
        if (player.getFoodData().getFoodLevel() < 20) player.getFoodData().setFoodLevel(20);
        player.fallDistance = 0.0F;
        SlenderSanity.set(player, SlenderSanity.MAX);

        // he does NOT follow: he stands where the flash put him (3-4 blocks from the place of the teleport) until the next flash
        st.last = player.position();

        if (--st.ticks > 0) return;
        if (st.run < st.runs) beginRun(player, st, false);
        else beginBlack(player, st);
    }

    // ---- BLACK
    private static void beginBlack(ServerPlayer player, State st) {
        st.stage = STAGE_BLACK;
        st.ticks = (int) ((BLACK_SECONDS + WAKE_LIE_SECONDS + WAKE_RAISE_SECONDS + WAKE_MARGIN_SECONDS) * 20.0D);
        sendStage(player, STAGE_BLACK, 0);                  // the client: black at once + the flash sound
        discardSlender(player.level, st);
        SlenderSanity.reset(player);
        // under the black screen: the spawn point, the other half of the day, no Slenderman
        moveToSpawn(player);
        flipDayNight((ServerLevel) player.level);
    }

    private static void moveToSpawn(ServerPlayer player) {
        ServerLevel target = player.server.getLevel(player.getRespawnDimension());
        if (target == null) target = player.server.overworld();
        BlockPos rp = player.getRespawnPosition();
        double x, y, z;
        float yaw = player.getRespawnAngle();
        if (rp != null && target.getBlockState(rp).getBlock() != null) {
            // a bed or an anchor: the first free spot next to it; in a plain world: the point itself
            x = rp.getX() + 0.5D; y = rp.getY() + 0.6D; z = rp.getZ() + 0.5D;
            Vec3 free = standSpot(target, x, z, rp.getY() + 2.0D, 4);
            if (free != null) { x = free.x; y = free.y; z = free.z; }
        } else {
            BlockPos sp = target.getSharedSpawnPos();
            int top = target.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, sp.getX(), sp.getZ());
            x = sp.getX() + 0.5D; y = top; z = sp.getZ() + 0.5D;
            yaw = 0.0F;
        }
        player.setDeltaMovement(Vec3.ZERO);
        player.fallDistance = 0.0F;
        if (target == player.level) {
            player.connection.teleport(x, y, z, yaw, 0.0F);
        } else {
            player.teleportTo(target, x, y, z, yaw, 0.0F);
        }
    }

    /** day -> night, night -> day (only in the overworld, where the time is real). */
    private static void flipDayNight(ServerLevel level) {
        if (level.dimension() != Level.OVERWORLD) return;
        long t = level.getDayTime();
        long tod = t % 24000L;
        long base = t - tod;
        boolean night = tod >= 12600L;
        level.setDayTime(night ? base + 24000L + 1000L : base + 14500L);
    }

    // ------------------------------------------------------------------ helpers

    /** where a person can stand near (x, z): the first solid floor with 2 free blocks above it, looking down from fromY. */
    private static Vec3 standSpot(ServerLevel level, double x, double z, double fromY, int down) {
        int bx = Mth.floor(x), bz = Mth.floor(z);
        if (!level.hasChunkAt(new BlockPos(bx, 64, bz))) return null;
        int top = Mth.floor(fromY);
        for (int y = top; y > top - down && y > level.getMinBuildHeight(); y--) {
            BlockPos feet = new BlockPos(bx, y, bz);
            BlockState below = level.getBlockState(feet.below());
            if (below.getCollisionShape(level, feet.below()).isEmpty() || !below.getFluidState().isEmpty()) continue;
            if (!level.getBlockState(feet).getCollisionShape(level, feet).isEmpty()) continue;
            if (!level.getBlockState(feet.above()).getCollisionShape(level, feet.above()).isEmpty()) continue;
            if (!level.getFluidState(feet).isEmpty()) continue;
            return new Vec3(bx + 0.5D, y, bz + 0.5D);
        }
        return null;
    }

    /** the yaw (degrees) that looks from "from" to "to". */
    private static float yawToward(Vec3 from, Vec3 to) {
        return (float) (Mth.atan2(-(to.x - from.x), to.z - from.z) * 57.29577951308232D);
    }

    // ------------------------------------------------------------------ safety

    /** nobody can hurt the player during the finale (it must not end in a death by a zombie or a fall). */
    @SubscribeEvent
    public static void onAttacked(LivingAttackEvent event) {
        if (event.getEntity() instanceof ServerPlayer p && active(p) && stage(p) != STAGE_CALM
                && !event.getSource().isBypassInvul()) {
            event.setCanceled(true);
        }
    }

    @SubscribeEvent
    public static void onLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        clear(event.getEntity());
    }

    @SubscribeEvent
    public static void onRespawn(PlayerEvent.PlayerRespawnEvent event) {
        clear(event.getEntity());
    }
}
