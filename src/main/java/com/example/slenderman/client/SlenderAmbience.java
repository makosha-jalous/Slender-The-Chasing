package com.example.slenderman.client;

import com.example.slenderman.SlendermanEntity;
import com.example.slenderman.SlendermanMod;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.AbstractTickableSoundInstance;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.client.sounds.SoundManager;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * Background sounds of the mod (CLIENT ONLY). The files live in their own sound namespace "slenderambient"
 * (assets/slenderambient/sounds.json + assets/slenderambient/sounds/*.ogg), so sounds.json of the mod is not touched.
 *
 *  - WIND (wind.ogg): a loop that plays OUTSIDE. Inside a building it is 70 % quieter, deep underground it is silent.
 *    Going inside it fades out in 2 s, coming out it fades in again in 3 s.
 *  - BASS (bassambiance.ogg): a loop that plays only INSIDE buildings and in caves, and only while a Slenderman exists.
 *  - BUSH (bush_move.ogg): a sound in the world (not in the head): at a random point up to 45 blocks from the player, the loudness falls with
 *    the distance like in the original; the same timer range as the footsteps. The leaves (footsteps and bush) are silent indoors and in caves.
 *  - FOOTSTEPS (foot_*.ogg): only while a Slenderman exists. Every 30 s - 2 min, at random: left only, right only,
 *    both channels (steady or getting louder), or sliding from left to right / from right to left.
 *    The pitch and the speed are a little different every time.
 *
 * All of them are in the sound category "Ambient/Environment" (the slider in the sound options).
 */
@Mod.EventBusSubscriber(modid = SlendermanMod.ID, value = Dist.CLIENT)
public final class SlenderAmbience {
    // ---------------------------------------------------------------- settings
    /** basic volume of all three sounds: 0.6 = 40 % quieter than the files are. Change it here if needed. */
    private static final float VOLUME = 0.6F;
    /** extra volume of the WIND only (1 = as the basic volume, 0.5 = half as loud). Lower it to make the wind quieter. */
    private static final float WIND_VOLUME = 0.2F;
    /** extra volume of the BASS only. */
    private static final float BASS_VOLUME = 1.0F;
    /** wind inside a building (1 = as loud as outside). 0.30 = 70 % quieter. Use 0 to switch the wind off inside. */
    private static final float WIND_INDOOR = 0.1F;
    /** how long it takes to fade (ticks, 20 ticks = 1 second). */
    private static final int WIND_FADE_DOWN = 40;   // going inside: 2 s
    private static final int WIND_FADE_UP = 60;     // going outside: 3 s
    private static final int BASS_FADE_IN = 40;
    private static final int BASS_FADE_OUT = 60;
    /** the wind plays only when a Slenderman exists in the world? (false = always) */
    private static final boolean WIND_ONLY_WITH_SLENDER = false;
    /** a roof higher than this above the head (blocks) means "cave / deep underground" and not "a building". */
    private static final int CAVE_DEPTH = 12;
    /** footsteps: pause between two plays, in ticks (30 s .. 2 min). */
    private static final int FOOT_MIN_TICKS = 20 * 30;
    private static final int FOOT_RANDOM_TICKS = 20 * 90;
    /** footsteps: pitch range (the "tone"; the speed changes a bit with it). */
    private static final float FOOT_PITCH_MIN = 0.84F;
    private static final float FOOT_PITCH_MAX = 1.16F;
    /** he is still counted as "in the world" this long after the client saw him last (he can be parked far away). */
    private static final int SLENDER_GRACE_TICKS = 20 * 30;

    /** the leaves / bushes (the footsteps and the bush sound) are heard only OUTSIDE: not inside buildings, not in caves (no leaves = nobody walks there). */
    private static final boolean LEAVES_OUTSIDE_ONLY = true;
    /** the bush sound (bush_move.ogg): pause between two plays, the same range as the footsteps (30 s .. 2 min). */
    private static final int BUSH_MIN_TICKS = 20 * 30;
    private static final int BUSH_RANDOM_TICKS = 20 * 90;
    /** extra volume of the bush sound (1 = as the basic volume). */
    private static final float BUSH_VOLUME = 1.0F;
    /** as in the original: it plays at a random point up to 45 blocks from the player; full loudness inside 20 blocks, then it fades like 20 / distance. */
    private static final int BUSH_MAX_DIST = 45;
    private static final float BUSH_FULL_DIST = 20.0F;
    private static final float BUSH_PITCH_VAR = 0.1F;

    private static final String NS = "slenderambient";
    private static final String[] FOOT_EVENTS = {
            "footsteps_l", "footsteps_r", "footsteps_both", "footsteps_rise", "footsteps_lr", "footsteps_rl"};

    // ---------------------------------------------------------------- state
    private static final RandomSource RNG = RandomSource.create();
    private static final Fader WIND = new Fader();
    private static final Fader BASS = new Fader();
    private static LoopSound windSound;
    private static LoopSound bassSound;
    private static int zone = 0;            // 0 outside, 1 inside a building, 2 cave / deep underground
    private static int pendingZone = 0;
    private static int pendingCount = 0;
    private static int envTimer = 0;
    private static int slenderTimer = 0;
    private static int graceLeft = 0;
    private static boolean dying = false;
    private static int footWait = -1;
    private static int bushWait = -1;
    /** counts every client tick: the age of a loop is measured with it (a loop whose start was muted never ticks by itself). */
    private static int tickCounter = 0;

    private SlenderAmbience() {}

    /** a value that moves to a goal in a fixed time with a soft start and end. */
    private static final class Fader {
        float cur;
        float from;
        float goal;
        int t;
        int dur = 1;

        void to(float newGoal, int ticksWhenDown, int ticksWhenUp) {
            if (Math.abs(newGoal - goal) < 1.0E-4F) return;
            goal = newGoal;
            from = cur;
            t = 0;
            dur = Math.max(1, newGoal < cur ? ticksWhenDown : ticksWhenUp);
        }

        void tick() {
            if (t < dur) {
                t++;
                float k = (float) t / dur;
                k = k * k * (3.0F - 2.0F * k);
                cur = from + (goal - from) * k;
            } else {
                cur = goal;
            }
        }

        void reset() {
            cur = 0.0F;
            from = 0.0F;
            goal = 0.0F;
            t = 0;
            dur = 1;
        }
    }

    /** a looping sound that is not placed in the world (always the same loudness in both ears). */
    private static final class LoopSound extends AbstractTickableSoundInstance {
        private final Fader fader;
        private final float gain;
        int age = 0;
        final int bornTick = tickCounter;

        LoopSound(String event, Fader fader, float gain) {
            super(new SoundEvent(new ResourceLocation(NS, event)), SoundSource.AMBIENT, RandomSource.create());
            this.fader = fader;
            this.gain = gain;
            this.looping = true;
            this.delay = 0;
            this.volume = 0.0001F;
            this.relative = true;
            this.attenuation = SoundInstance.Attenuation.NONE;
            this.x = 0.0D;
            this.y = 0.0D;
            this.z = 0.0D;
        }

        @Override
        public void tick() {
            age++;
            // never exactly 0, otherwise the engine would end the loop
            this.volume = Math.max(fader.cur * VOLUME * gain, 0.0001F);
        }

        void halt() {
            this.stop();
        }
    }

    // ---------------------------------------------------------------- the tick
    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent e) {
        if (e.phase != TickEvent.Phase.END) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) {
            reset();
            return;
        }
        if (mc.isPaused()) return;
        tickCounter++;

        // ---- is there a Slenderman, and is the death scene running?
        if (--slenderTimer <= 0) {
            slenderTimer = 5;
            boolean found = false;
            boolean death = false;
            for (Entity en : mc.level.entitiesForRendering()) {
                if (en instanceof SlendermanEntity s && !s.isRemoved()) {
                    found = true;
                    if (s.getDeathPhase() >= 2) death = true;
                }
            }
            dying = death;
            if (found) graceLeft = SLENDER_GRACE_TICKS;
            if (death) graceLeft = 0;       // the scene is over soon, he is gone afterwards: everything stops
        }
        if (graceLeft > 0) graceLeft--;
        boolean slender = graceLeft > 0 && !dying;
        // the Retry menu: the bass (inside a structure) and the wind (outside) go on playing quietly behind it
        boolean retryOpen = mc.screen instanceof SlenderRetryScreen;

        // ---- where is the player: outside / inside / cave (checked 4 times a second, must be stable for 0.5 s)
        if (--envTimer <= 0) {
            envTimer = 5;
            int z = detectZone(mc);
            if (z == zone) {
                pendingCount = 0;
            } else if (z == pendingZone) {
                if (++pendingCount >= 2) {
                    zone = z;
                    pendingCount = 0;
                }
            } else {
                pendingZone = z;
                pendingCount = 1;
            }
        }

        // ---- goals
        float windGoal = zone == 0 ? 1.0F : (zone == 1 ? WIND_INDOOR : 0.0F);
        if (WIND_ONLY_WITH_SLENDER && !slender) windGoal = 0.0F;
        if (dying) windGoal = 0.0F;
        float bassGoal = ((slender || retryOpen) && zone >= 1) ? 1.0F : 0.0F;
        WIND.to(windGoal, WIND_FADE_DOWN, WIND_FADE_UP);
        BASS.to(bassGoal, BASS_FADE_OUT, BASS_FADE_IN);
        WIND.tick();
        BASS.tick();

        SoundManager sm = mc.getSoundManager();
        windSound = manageLoop(sm, windSound, WIND, "wind", WIND_VOLUME);
        bassSound = manageLoop(sm, bassSound, BASS, "bass", BASS_VOLUME);

        // ---- footsteps (leaves) and the bush sound: each has its own timer; nothing is played indoors / in caves
        boolean leavesOk = !LEAVES_OUTSIDE_ONLY || zone == 0;
        if (slender) {
            if (footWait < 0) footWait = FOOT_MIN_TICKS + RNG.nextInt(FOOT_RANDOM_TICKS + 1);
            else if (--footWait == 0) {
                if (leavesOk) playFootsteps(sm);
                footWait = -1;
            }
            if (bushWait < 0) bushWait = BUSH_MIN_TICKS + RNG.nextInt(BUSH_RANDOM_TICKS + 1);
            else if (--bushWait == 0) {
                if (leavesOk) playBush(sm, mc);
                bushWait = -1;
            }
        } else {
            footWait = -1;
            bushWait = -1;
        }
    }

    /** starts the loop when it is audible, ends it when it has faded out, restarts it if something killed it. */
    private static LoopSound manageLoop(SoundManager sm, LoopSound sound, Fader fader, String event, float gain) {
        boolean audible = fader.cur > 0.002F || fader.goal > 0.0F;
        if (!audible) {
            if (sound != null) sound.halt();
            return null;
        }
        if (sound != null && !sound.isStopped() && (tickCounter - sound.bornTick < 30 || sm.isActive(sound))) return sound;
        if (sound != null) sound.halt();
        LoopSound fresh = new LoopSound(event, fader, gain);
        sm.play(fresh);
        return fresh;
    }

    private static void playFootsteps(SoundManager sm) {
        String event = FOOT_EVENTS[RNG.nextInt(FOOT_EVENTS.length)];
        float pitch = FOOT_PITCH_MIN + RNG.nextFloat() * (FOOT_PITCH_MAX - FOOT_PITCH_MIN);
        float volume = VOLUME * (0.85F + 0.15F * RNG.nextFloat());
        // a plain 2D sound: the stereo files keep their left / right channels exactly as they were made
        sm.play(new SimpleSoundInstance(new ResourceLocation(NS, event), SoundSource.AMBIENT, volume, pitch,
                RandomSource.create(), false, 0, SoundInstance.Attenuation.NONE, 0.0D, 0.0D, 0.0D, true));
    }

    /** the bush sound is placed in the world: a random point up to 45 blocks around the player (as in the original), it stays there. */
    private static void playBush(SoundManager sm, Minecraft mc) {
        Player p = mc.player;
        if (p == null) return;
        double a = RNG.nextDouble() * Math.PI * 2.0D;
        double dx = Math.cos(a) * RNG.nextInt(BUSH_MAX_DIST + 1);
        double dz = Math.sin(a) * RNG.nextInt(BUSH_MAX_DIST + 1);
        float pitch = 1.0F + (RNG.nextFloat() * 2.0F - 1.0F) * BUSH_PITCH_VAR;
        sm.play(new BushSound(p.getX() + dx, p.getY(), p.getZ() + dz, pitch));
    }

    /** a sound standing at one point of the world: the direction comes from the position, the loudness is set by us (the distance changes while the player walks). */
    private static final class BushSound extends AbstractTickableSoundInstance {
        BushSound(double px, double py, double pz, float pitch) {
            super(new SoundEvent(new ResourceLocation(NS, "bush")), SoundSource.AMBIENT, RandomSource.create());
            this.looping = false;
            this.delay = 0;
            this.relative = false;
            this.attenuation = SoundInstance.Attenuation.NONE;   // no engine fading: we do it below
            this.x = px;
            this.y = py;
            this.z = pz;
            this.pitch = pitch;
            this.volume = 0.0001F;
            this.tick();
        }

        @Override
        public void tick() {
            Player p = Minecraft.getInstance().player;
            if (p == null) {
                this.stop();
                return;
            }
            double d = Math.sqrt(p.distanceToSqr(this.x, this.y, this.z));
            float fall = d <= BUSH_FULL_DIST ? 1.0F : (float) (BUSH_FULL_DIST / d);
            this.volume = Math.max(VOLUME * BUSH_VOLUME * fall, 0.0001F);
        }
    }

    private static void reset() {
        if (windSound != null) windSound.halt();
        if (bassSound != null) bassSound.halt();
        windSound = null;
        bassSound = null;
        WIND.reset();
        BASS.reset();
        zone = 0;
        pendingZone = 0;
        pendingCount = 0;
        envTimer = 0;
        slenderTimer = 0;
        graceLeft = 0;
        dying = false;
        footWait = -1;
        bushWait = -1;
    }

    // ---------------------------------------------------------------- outside / inside / cave
    /**
     * Looks straight up from the head: leaves, grass and air are no roof, every block with a solid shape is.
     * No roof (or only a roof under which it is still bright, like a porch) = outside. A roof within CAVE_DEPTH
     * blocks = a building. A higher roof, or a world without sky (Nether, End) = a cave.
     */
    private static int detectZone(Minecraft mc) {
        Level level = mc.level;
        Player p = mc.player;
        if (level == null || p == null) return 0;
        if (!level.dimensionType().hasSkyLight()) return 2;
        int x = Mth.floor(p.getX());
        int y = Mth.floor(p.getEyeY());
        int z = Mth.floor(p.getZ());
        int limit = Math.min(level.getMaxBuildHeight(), y + 64);
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
        int topRoof = Integer.MIN_VALUE;
        for (int yy = y + 1; yy < limit; yy++) {
            m.set(x, yy, z);
            BlockState s = level.getBlockState(m);
            if (s.isAir() || s.is(BlockTags.LEAVES)) continue;
            if (s.getCollisionShape(level, m).isEmpty()) continue;
            topRoof = yy;
        }
        if (topRoof == Integer.MIN_VALUE) return 0;
        int sky = level.getBrightness(LightLayer.SKY, new BlockPos(x, y, z));
        if (sky > 12) return 0;                 // an awning / porch: the sky is still right there
        if ((topRoof - y) > CAVE_DEPTH) return 2;
        // a roof alone is not a building: one block over the head (a tree, a bridge, an overhang) is still "outside";
        // the walls are needed too (the same rule as for Slenderman, see Enclosure)
        return com.example.slenderman.Enclosure.closedDirections(level, p.getX(), p.getEyeY(), p.getZ())
                >= com.example.slenderman.Enclosure.WALLS_NEEDED ? 1 : 0;
    }

}
