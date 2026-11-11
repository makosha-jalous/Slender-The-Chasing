package com.example.slenderman;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * THE NEW FINALE (after the 8th page). It runs on the server, per player, and the stage is kept in the player's own data
 * (so it is saved with the world and cleared with a new life).
 *
 *   PART 1 (this file now): the 8th page is taken -> the sanity is 0 at ONCE, every Slenderman is gone, the page music is cut,
 *           the camera flashes for one second (the camera mod does that, see PageAudio / CameraEffects.finaleFlash),
 *           then a CALM of a few seconds.
 *   PART 2 (later):  the player stops, the view turns to Slenderman until the sanity is gone.
 *   PART 3 (later):  4-5 runs of 5 s with teleports, the black screen, day <-> night, the spawn point.
 *
 * ENABLED = false brings the OLD finale back (he hides for 10 s, stands behind for 7 s, goes away).
 */
@Mod.EventBusSubscriber(modid = SlendermanMod.ID)
public final class SlenderFinale {
    public static final boolean ENABLED = true;

    /** the calm after the flash, in SECONDS (the player said "5-7 seconds"; the first idea was minutes: change the two numbers to 300 / 420). */
    public static final int CALM_MIN_SECONDS = 5;
    public static final int CALM_MAX_SECONDS = 7;

    public static final int STAGE_NONE = 0;
    public static final int STAGE_CALM = 1;
    /** the calm is over: the place where part 2 begins. */
    public static final int STAGE_AFTER_CALM = 2;

    private static final String KEY_STAGE = "slenderman_finale_stage";
    private static final String KEY_TICKS = "slenderman_finale_ticks";

    private SlenderFinale() {}

    public static int stage(Player player) {
        return player.getPersistentData().getInt(KEY_STAGE);
    }

    /** the 8th page has just been taken. */
    public static void start(ServerPlayer player) {
        SlenderSanity.reset(player);
        for (SlendermanEntity slender : player.level.getEntitiesOfClass(SlendermanEntity.class,
                player.getBoundingBox().inflate(256.0D))) {
            slender.discard();
        }
        int seconds = CALM_MIN_SECONDS + player.getRandom().nextInt(CALM_MAX_SECONDS - CALM_MIN_SECONDS + 1);
        CompoundTag tag = player.getPersistentData();
        tag.putInt(KEY_STAGE, STAGE_CALM);
        tag.putInt(KEY_TICKS, seconds * 20);
    }

    /** a new life, /reset: no finale is running any more. */
    public static void clear(Player player) {
        CompoundTag tag = player.getPersistentData();
        tag.putInt(KEY_STAGE, STAGE_NONE);
        tag.putInt(KEY_TICKS, 0);
    }

    @SubscribeEvent
    public static void onPlayerTick(TickEvent.PlayerTickEvent event) {
        if (event.phase != TickEvent.Phase.END || !(event.player instanceof ServerPlayer player)) return;
        CompoundTag tag = player.getPersistentData();
        if (tag.getInt(KEY_STAGE) != STAGE_CALM) return;
        int left = tag.getInt(KEY_TICKS) - 1;
        if (left > 0) {
            tag.putInt(KEY_TICKS, left);
            return;
        }
        tag.putInt(KEY_TICKS, 0);
        tag.putInt(KEY_STAGE, STAGE_AFTER_CALM);       // part 2 will start from here
    }
}
