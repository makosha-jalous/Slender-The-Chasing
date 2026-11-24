package com.example.slenderman;

import net.minecraft.core.BlockPos;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.Mth;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.EntityDamageSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityDimensions;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.control.MoveControl;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.common.world.ForgeChunkManager;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.registries.RegistryObject;

import java.util.List;
import java.util.UUID;

public class SlendermanEntity extends PathfinderMob {
    /** 0..1: the head tilted to his right side while he waits (a short animation of the waiting mode). */
    private static final EntityDataAccessor<Float> DATA_TILT = SynchedEntityData.defineId(SlendermanEntity.class, EntityDataSerializers.FLOAT);
    private static final EntityDataAccessor<Float> DATA_TENT = SynchedEntityData.defineId(SlendermanEntity.class, EntityDataSerializers.FLOAT);
    private static final EntityDataAccessor<Integer> DATA_ACTION = SynchedEntityData.defineId(SlendermanEntity.class, EntityDataSerializers.INT);
    private static final EntityDataAccessor<Float> DATA_PROGRESS = SynchedEntityData.defineId(SlendermanEntity.class, EntityDataSerializers.FLOAT);
    /** which way the head tilts during the bow: +1 or -1. */
    private static final EntityDataAccessor<Integer> DATA_SIDE = SynchedEntityData.defineId(SlendermanEntity.class, EntityDataSerializers.INT);
    /** true for a few ticks after every teleport: the client does not draw him, so he cannot "glide" to the new spot. */
    /** aggression level 0..1 (synced, so client side mods such as slendercam can read it with getAggression()). */
    private static final EntityDataAccessor<Float> DATA_AGGRESSION = SynchedEntityData.defineId(SlendermanEntity.class, EntityDataSerializers.FLOAT);
    /** which screamer / sight sound to play right now: (counter * 100 + sound id); the client plays it as a plain 2D sound. */
    private static final EntityDataAccessor<Integer> DATA_CUE = SynchedEntityData.defineId(SlendermanEntity.class, EntityDataSerializers.INT);
    private static final EntityDataAccessor<Boolean> DATA_HIDDEN = SynchedEntityData.defineId(SlendermanEntity.class, EntityDataSerializers.BOOLEAN);
    /** sanity LOSS of the hunted player, 0..100 (synced, so camerahud can read it with getSanity()). */
    private static final EntityDataAccessor<Float> DATA_SANITY = SynchedEntityData.defineId(SlendermanEntity.class, EntityDataSerializers.FLOAT);
    /** teleport cue for the camera: counter * 4 + flags (flags: 1 = he appeared in the view of the player, 2 = in front of him). */
    private static final EntityDataAccessor<Integer> DATA_TELEPORT = SynchedEntityData.defineId(SlendermanEntity.class, EntityDataSerializers.INT);
    /** death scene for the client: 0 = none, 1 = the view turns to his face (3 s), 2 = black screen (2 s), 3 = portrait (3 s), 4 = last black screen. */
    /** watcher mode: changes when he vanishes in front of the player (the camera answers with a very short static). */
    private static final EntityDataAccessor<Integer> DATA_BLIP = SynchedEntityData.defineId(SlendermanEntity.class, EntityDataSerializers.INT);
    /** how long the view turns to his face in this death (ticks): the more sanity is already lost, the shorter. */
    private static final EntityDataAccessor<Integer> DATA_TURN = SynchedEntityData.defineId(SlendermanEntity.class, EntityDataSerializers.INT);
    private static final EntityDataAccessor<Integer> DATA_DEATH = SynchedEntityData.defineId(SlendermanEntity.class, EntityDataSerializers.INT);

    /** switches the creeping teleports on/off (kept public: other classes may use it). */
    public static boolean autoTeleport = true;

    private static final double SEEN_COS = 0.55D;

    // ---- creeping: while nobody looks at him he jumps closer ----
    /** ticks the client hides him after a teleport (disappear -> appear, no flying). */
    private static final int TELEPORT_HIDE_TICKS = 0;   // 0 = a teleport is instant: he is gone here and there at the same moment (was 5 ticks of nothing)

    // ---- "critical" closeness ----
    /** closer than this his aggression level rises (blocks). */
    private static final double AGGRESSION_RANGE = 5.0D;
    /** aggression 0..1: full after 3.5 s close to the player, falls back slowly (10 s from full to zero). */
    private static final float AGGRESSION_RISE = 1.0F / 70.0F;
    private static final float AGGRESSION_FALL = 1.0F / 200.0F;

    // ---- screamers ----
    /** after ANY screamer sound no other one can play for 20..25 seconds (the sound itself may come without an animation). */
    private static final int SCARE_COOLDOWN_MIN = 20 * 20;
    private static final int SCARE_COOLDOWN_RANDOM = 20 * 5;
    /** chance that a screamer sound also comes with the reaching animation / the slow bow (the rest: sound only). */
    private static final float REACH_CHANCE = 0.70F;
    private static final float BOW_CHANCE = 0.60F;
    /** after an animation the next one has to wait 40..60 seconds (he tries to "take" the player, not all the time). */
    private static final int ANIM_COOLDOWN_MIN = 20 * 40;
    private static final int ANIM_COOLDOWN_RANDOM = 20 * 20;
    /** while "vanished" he is parked this far away (blocks), so he leaves no phantom next to the player. */
    private static final double PARK_MIN = 60.0D;

    // ---- "sight" sounds: he is seen far away (but not too far) ----

    // ---- chasing ----
    /** the player is "high" (hiding on a pillar, tree, roof) when he is this much higher than Slenderman (blocks). */
    private static final double HIGH_DY = 3.0D;

    // ---- aggression stages: one per collected page (index = pages, 1..8; index 0 is never used) ----
    // 1-2 pages: a watcher 20-25 blocks away, never attacks | 3-4: stalks 13-17 blocks away, steps into the way when the player runs
    // 5-6: hunter 7-9 / 5-7 blocks away, appears in front of the player | 7: open hunt 3-5 blocks away, steps into the way (50 %)
    // 8: the finale (he is gone for 10 s, then stands behind the player, then disappears)
    //                                                     0     1     2     3     4     5     6     7     8
    /** the distance he keeps to the player while stalking (blocks). */
    private static final double[] KEEP_DIST     = {22.5, 22.5, 22.5, 15.0, 15.0,  9.0,  9.0,  6.0,  6.0};
    /** walking speed multiplier (0 = he does not walk, he only appears at a new place). */
    private static final double[] WALK_MOD      = { 0.0,  0.3,  0.3,  0.3,  0.3,  0.3,  0.3,  0.3,  0.3};
    /** ticks between two "ambush" teleports (0 = none) and the chance that such a check really teleports. */
    private static final int[]    AMBUSH_EVERY  = {   0,    0,    0,    0,    0,  200,  160,  100,  100};
    /** chance per tick to vanish after a long stare (0 = he never vanishes). */
    private static final float[]  VANISH_BY     = {0.012F, 0.012F, 0.008F, 0.004F, 0.002F, 0.001F, 0.0005F, 0F, 0F};
    /** the aggression circle grows (blocks) and fills faster with every stage. */
    private static final double[] AGG_RANGE_ADD = { 0.0,  0.0,  0.0,  1.0,  3.0,  5.0,  8.0, 11.0, 11.0};
    private static final float[]  AGG_RISE_MUL  = { 1F,   1F,   1F,   1.2F, 1.5F, 1.8F, 2.2F, 2.6F, 2.6F};
    /** screamer / animation waiting times are multiplied by this (smaller = more often). */
    private static final float[]  SCARE_SCALE   = {1.6F, 1.6F, 1.3F, 1.0F, 0.9F, 0.8F, 0.65F, 0.5F, 0.5F};
    /** chance that a screamer sound comes with the reach / the bow (no animations at all in stages 1-2). */
    private static final float[]  REACH_BY      = { 0F,   0F,   0F,  0.25F, 0.35F, 0.40F, 0.45F, 0.50F, 0.50F};
    private static final float[]  BOW_BY        = { 0F,   0F,   0F,  0.20F, 0.25F, 0.30F, 0.30F, 0.30F, 0.30F};
    /** damage (half hearts) every second while he stands this close (blocks); doubled when he is really on top of the player. */
    private static final double   DAMAGE_RANGE  = 2.0D;
    private static final float[]  DAMAGE_BY     = { 0F,   0F,   0F,  0F,   1F,   2F,   3F,   5F,   0F};
    /** stage 1-2: how often (ticks) the watcher repositions and how far he jumps toward the player at most. */
    private static final int[]    WATCH_EVERY   = { 160,  160,  100,  0,    0,    0,    0,    0,    0};
    private static final double   WATCH_STEP    = 8.0D;

    // ---- teleport ability ----
    /** ticks between two rolls = CHECK_MIN + random(CHECK_RAND): 1-4 pages 30-50 s, 5-6 pages 15-20 s, 7 pages 5-10 s. */
    private static final int[] CHECK_MIN  = {0, 600, 600, 600, 600, 300, 300, 100, 100};
    private static final int[] CHECK_RAND = {0, 400, 400, 400, 400, 100, 100, 100, 100};
    /** how close he stands "right behind" the player (blocks, centre to centre). Touching him (hitboxes, about 0.7) kills; he never comes closer than about 2 blocks himself. */
    private static final double CONTACT_DIST = 1.6D;
    // The timed close-in after page 7 has been removed: normal stalking distance is always used.
    /** no teleport ever puts him closer than this to the player (blocks, horizontal): the hitboxes must never touch by a teleport. */
    private static final double TELEPORT_MIN_DIST = 1.1D;
    /** the distance band he keeps while stalking (blocks), index = pages collected: pages 1-2: 30-35, 3: 13-17, 4: 10-13, 5: 7-9, 6: 5-7, 7: exactly 2. */
    private static final double[] BAND_MIN = {30.0, 30.0, 30.0, 13.0, 10.0,  7.0,  5.0,  2.0,  3.0};
    private static final double[] BAND_MAX = {35.0, 35.0, 35.0, 17.0, 13.0,  9.0,  7.0,  2.0,  4.0};
    /** the START distance (the page-1 band), kept apart and never changed: the angry teleport takes an eighth of it (as the original's minRangeCache / maxRangeCache). */
    private static final double START_MIN = 30.0D;
    private static final double START_MAX = 35.0D;
    private static final int REPOSITION_TICKS = 80;
    private static final double REPOSITION_STEP = 12.0D;

    // ======================================================================================================
    // THE ORIGINAL GAME'S LOGIC (SlenderMan.cs of Slender: The Arrival). the code below is used for the
    // kill, the teleports, the walking, the sight sounds and the tentacles (the old logic was removed).
    // The original runs in Unity physics steps (50 per second); Minecraft has 20 ticks per second, so 1 tick = 2.5 steps.
    // ======================================================================================================
    /** true = the stage also grows with time: +1 stage every TIME_STAGE_MINUTES after the first page (see timedPages). */
    private static final boolean TIME_STAGES = true;
    private static final double TIME_STAGE_MINUTES = 5.0D;
    private static final long TIME_STAGE_TICKS = (long) (TIME_STAGE_MINUTES * 60.0D * 20.0D);
    /** time alone never goes beyond this stage (the 8th is the finale: it needs the real 8th page). */
    private static final int TIME_STAGE_MAX = 7;
    public static final String FIRST_PAGE_KEY = "slender_first_page_time";
    /** view.level of the original (the difficulty): it is added to the page count in every formula. */
    private static final int LEVEL = 0;
    private static final double STEPS_PER_TICK = 2.5D;
    /** jumptime 550 steps minus 50 steps for every page: a teleport check every 11 s (page 0) ... 4 s (page 7) ... 3 s (page 8). */
    private static final int JUMP_TIME_STEPS = 550;
    private static final int JUMP_STEPS_PER_PAGE = 50;
    /** porttime 100 steps = 2 s: later than this after the last teleport a "real" teleport (move 2) may roll again. */
    private static final int PORT_TIME_TICKS = 40;
    /** chasing (he is in line of sight closer than 30 blocks): no teleport, unless he is farther than 10 blocks (then 20 % per STEP). */
    private static final double PROXIMITY_DIST = 30.0D;
    private static final double CHASE_TELEPORT_DIST = 10.0D;
    private static final float CHASE_TELEPORT_CHANCE_PER_TICK = (float) (1.0D - Math.pow(0.8D, STEPS_PER_TICK));
    /** the original catches the player at this distance with a clear line (no look needed). */
    private static final double CATCH_DIST = 2.0D;
    /** false = NO instant kill at 2 blocks (it felt unfair in Minecraft): only a touch kills at once; standing behind him drains the sanity. */
    private static final boolean KILL_AT_CATCH_DIST = false;
    /** from this sum (pages + level) he may teleport even while the player looks at him (the focused flashlight no longer stops it). */
    private static final int OPEN_TELEPORT_STAGE = 6;
    /** the chance of a "real" teleport even when he is not far: 10 % at every check; after porttime 50 %. */
    private static final float FAR_ROLL = 0.10F;
    private static final float PORT_ROLL = 0.50F;
    /** walking speed of the original: (pages + level) + 0.5 blocks per second (from the 2nd page, only while he is not seen). */
    private static final double ORIGINAL_WALK_BASE = 0.5D;
    /** 1.0 = the speed of the original in blocks per second; make it smaller to slow him down. */
    private static final double ORIGINAL_WALK_SCALE = 1.0D;
    /** pages 2-7: his walking speed, blocks per second (the same on all of these pages; only while he is not seen). */
    private static final double FIXED_WALK_BPS = 2.0D;
    /**
     * Keeping the page distance: when he is farther than the band of the page (BAND_MAX), he walks at least as fast as the player times this
     * (index = pages), so he always manages to come back to his distance; the more pages, the faster than the player he is.
     */
    private static final double[] CATCH_PLAYER_MUL = {1.0, 1.0, 1.0, 1.05, 1.1, 1.2, 1.3, 1.4, 1.4};
    /** and for every block beyond the band he adds this much (blocks per second), at most CATCH_GAP_MAX. */
    private static final double CATCH_GAP_BPS = 0.35D;
    private static final double CATCH_GAP_MAX = 6.0D;
    /** the walking speed never goes beyond this (blocks per second). */
    private static final double WALK_BPS_MAX = 12.0D;
    /** a teleport IN FRONT of the player (in his view cone or in the direction he walks) never puts him closer than this (3.0 .. 4.0, random): a moment to turn away. */
    private static final double FRONT_MIN_DIST = 3.0D;
    private static final double FRONT_MIN_RANDOM = 1.0D;
    /** "in front": the angle to the view / walking direction is smaller than about 60 degrees. */
    private static final double FRONT_COS = 0.5D;
    /** blocks per tick of this entity at speed modifier 1.0 (MOVEMENT_SPEED 0.12 * 2.158). */
    private static final double BLOCKS_PER_TICK_AT_ONE = 0.12D * 2.158D;
    /** "angry" (4 sightings) or the player is inside a structure (50 %): the teleport distance is an eighth of the page distance ... */
    private static final double NEAR_DIVISOR = 8.0D;
    /** (not used any more: the angry teleport now takes an eighth of the fixed start distance). */
    private static final double NEAR_MIN_DIST = 3.0D;
    /** true = as in the original: only a FOCUSED flashlight stops him from appearing in view before page 6; false = he never appears in view. */
    private static final boolean UNFOCUSED_MAY_APPEAR_IN_VIEW = true;
    /** sight sounds of the original: one cooldown of 25 s, three distances. */
    private static final int SIGHT_COOLDOWN_TICKS = 20 * 25;
    /** the screamer (the dramatic sound and its animation) only plays closer than this (8 blocks; the original has 15). */
    private static final double SIGHT_NEAR = 8.0D;
    /** from this distance (4 blocks) and closer he REACHES out with his hand (3 s); farther (4-8 blocks) he slowly BOWS to the player. */
    private static final double SCARE_REACH_DIST = 4.0D;
    private static final double SIGHT_MIDDLE = 25.0D;
    private static final double SIGHT_FAR = 45.0D;
    /** the tentacles start to grow when 20 of 100 sanity is lost and are full at 100 lost. */
    private static final float TENTACLE_FROM = 20.0F;
    /** death scene after the black screen: the portrait (3 s), then a last black screen (about 1.2 s), then the death. */
    public static final int DEATH_PORTRAIT_TICKS = 30;
    /** the portrait lasts a random 2 .. 2.5 s (40 .. 50 ticks). */
    public static final int DEATH_PORTRAIT_MAX_TICKS = 40;
    public static final int DEATH_END_TICKS = 24;
    /** watcher mode: how far he stands (blocks), and when he vanishes because the player comes closer. */
    private static final double WATCH_MIN = 80.0D;
    private static final double WATCH_RANDOM = 20.0D;
    private static final double WATCH_FAR_CHANCE = 0.20D;
    private static final double WATCH_VANISH_DIST = 15.0D;
    private static final double WATCH_LOST_DIST = 100.0D;
    /** death scene: the view turns to his face for exactly 1.5 s (only when he was in view), then a sharp black screen for 1 s, then the portrait, then the death. */
    public static final int DEATH_TURN_TICKS = 30;
    /** the shortest turn (a loss close to 100): 1 s. */
    public static final int DEATH_TURN_MIN_TICKS = 20;
    /** the black screen before the portrait is ALWAYS there (also when the player ran into him) and lasts a random 1 .. 1.5 s. */
    public static final int DEATH_BLACK_MIN_TICKS = 20;
    public static final int DEATH_BLACK_MAX_TICKS = 30;
    /** blocks per tick (walking = 0.215, sprinting = 0.28). */
    private static final double RUN_SPEED = 0.24D;
    /** closer than this and behind the player's back (roaming.ogg plays) he takes the sanity without being looked at (1.7 = about one empty block between them). */
    private static final double BEHIND_RANGE = 1.7D;

    // ---- sanity ----
    /** farther than this he is not "looked at" any more (blocks). */
    private static final double SANITY_MAX_DIST = 64.0D;
    /** at 100 he is in "kill" mode until the sanity has fallen below this. */
    private static final float SANITY_RUSH_OFF = 50.0F;
    private static final float SANITY_KILL_DAMAGE = 10000.0F;
    // ---- animations ----
    public static final int ACTION_NONE = 0;
    /** quick reach with the right arm over his head (1 s). */
    public static final int ACTION_LUNGE = 1;
    /** slow bow, played in front of the player like the reach (3 s). */
    public static final int ACTION_PEEK = 2;
    public static final int LUNGE_TICKS = 60;      // the reach lasts 3 s (the player must see that he really reached out)
    /**
     * the reach: a SHARP jump into the pose, a slow growth for the rest of the 3 s (LUNGE_TICKS), a sharp jump back to the normal stance.
     * 60 ms is a little more than one game tick (50 ms), so the jump is one tick; the client smooths it between the two ticks.
     */
    public static final int REACH_TICKS = 1;
    /** how much of the full reach pose is reached by the first sharp jump. */
    private static final float REACH_FIRST = 0.7F;
    private static final int PEEK_TICKS = 60;

    /** he never walks; if the player is farther than this he jumps back to the player (any distance, no limit). */
    private static final double FOLLOW_DISTANCE = 48.0D;
    /** ticks the player has to stay far away before he catches up (2 seconds). */
    private static final int FOLLOW_DELAY = 40;

    private float tentPrev, tentNow, progPrev, progNow, tiltPrev, tiltNow;
    private int action, actionTimer, actionDuration;
    private int vanishCooldown = 20 * 15 + this.random.nextInt(20 * 16); // 15-30 sec
    private int vanishTicks = 0;
    private boolean vanished = false;
    private int observedTicks;
    private float tent;
    private boolean tentGoal;
    private float aggression;
    private int farTicks = 0;
    private ChunkPos forcedChunk;

    private int approachTicks = 0;
    private int tpHideTicks = 0;
    /** a much wider cone than "seen": while he is inside it (or was a moment ago) he does NOT move, jump or teleport at all. */
    private static final double WIDE_SEEN_COS = 0.25D;         // about 75 degrees to each side: wider than any screen
    /** how many ticks the player must have looked away before he may move (the server sees the player's turn a little late). */
    private static final int MOVE_GRACE_TICKS = 4;
    private int wideUnseen = 99;
    private int lastTeleportTick = -100;
    // ---- the original's counters (ticks): makejump / mightport / chasing / ported / lastsawrange / lastsighttime ----
    private int makeJump = 99999;                 // jumptime at the start: the first check comes at once
    private int mightPort = PORT_TIME_TICKS;
    private boolean chasing = false;
    private boolean ported = false;
    private int forcedAnim = 0;                   // 0 = chosen by chance (old logic); otherwise ACTION_LUNGE / ACTION_PEEK
    private int lastSawRange = 0;
    private int lastSightTick = -SIGHT_COOLDOWN_TICKS;
    private boolean prevSeen = false;
    private double prevDist = -1.0D;
    private int unseenTicks = 0;
    private int scareCooldown = 0;
    private int sightCooldown = 0;
    /** how many times the player has turned to him (a new sighting after he was unseen); the 4th makes him angry. */
    private int seenCount = 0;
    /** angry: the next teleport puts him very close (a fraction of the usual distance), like in the original game. */
    private boolean angry = false;
    private int reachTimer = 0;
    private boolean hiding = false;
    private int huntTicks = 0;
    private int farJumpTicks = 0;
    /** aggression stage = pages the nearest player has collected (1..8). */
    private int stage = 1;
    private int ambushTicks = 0;
    private int watchTicks = 0;
    private int damageTicks = 10;
    /** the NEW finale (SlenderFinale): the server of the finale places him, he only stands, shows the tentacles and never kills. */
    private boolean finaleMode = false;
    /** in the finale mode: true = the sanity of the player goes by the normal rule (right behind his back it falls without looking); false = it is set by the finale. */
    private boolean finaleRealSanity = false;
    private boolean finaleScared = false;
    private int finaleTurnTicks = 0;
    private int animCooldown = 0;
    private int idleTicks = 0;
    private Vec3 lastPlayerPos = null;
    private double playerSpeed = 0.0D;
    private double moveX = 0.0D, moveZ = 0.0D;
    private int sanityDelay = 0;
    private boolean sanityRush = false;
    private int rushGrace = 10;
    private int tpSeq = 0;
    private boolean suppressTeleportCue = false;
    private int teleportCheckTicks = 20 * 30 + this.random.nextInt(20 * 20 + 1);
    private float deathSanityStart = 0.0F;
    private float recoverRate = 0.0F;
    private int pathRetry = 0;
    private boolean pathOk = false;
    private int lagTicks = 0;
    private int stuckProbe = 0;
    private int stuckSeconds = 0;
    private Vec3 stuckPos = null;
    private int deathPhase = 0;
    /** the player ran into him: after the turn the black screen is skipped. */
    private boolean deathSkipBlack = false;
    /** how long the black screen lasts this time (ticks). */
    private int blackTicks = DEATH_BLACK_MIN_TICKS;
    /** /slender animations walk: he walks to the player for this many ticks, nothing else. */
    private int demoWalkTicks = 0;
    /** how long the portrait lasts this time (ticks). */
    private int portraitTicks = DEATH_PORTRAIT_TICKS;
    private int hopStepTicks = 0;
    private boolean watchPlaced = false;
    private int watchRepos = 0;
    private int watchWait = 0;
    private int blipSeq = 0;
    private int deathTicks = 0;
    private UUID deathVictim = null;
    private int cueSeq = 0;
    /** client only: the last cue that was already played. */
    private int lastCue = 0;
    private boolean cueInit = false;

    /** client only: the running roaming sound (typed as Object so the server never loads client classes). */
    public Object roamingSound;

    public SlendermanEntity(EntityType<? extends PathfinderMob> type, Level level) {
        super(type, level);
        this.maxUpStep = 1.5F;
        this.xpReward = 0;
        this.setPersistenceRequired();
        this.moveControl = new SlenderMoveControl(this);
    }

    /** true while he walks up to the player (originalWalk): the body keeps facing the player and he moves SIDEWAYS along the path (never turned along the zigzags). */
    private boolean strafeWalking;
    /** the walking animation, 0..1: grows and dies away in 300 ms (6 ticks); the model reads it. */
    private float walkAnim, walkAnimO;
    private static final float WALK_ANIM_STEP = 1.0F / 6.0F;
    /** how fast the body turns to the player while he walks (degrees per tick): smooth, never a snap. */
    private static final float WALK_TURN_DEG = 12.0F;

    public float getWalkAnim(float partial) { return Mth.lerp(partial, walkAnimO, walkAnim); }

    /**
     * The movement of the vanilla mob, except that while strafeWalking he does NOT turn to the way he goes: the wanted point is
     * converted into "forward" and "sideways" relative to where he faces (the body keeps looking at the player).
     */
    private static final class SlenderMoveControl extends MoveControl {
        private final SlendermanEntity slender;

        SlenderMoveControl(SlendermanEntity mob) {
            super(mob);
            this.slender = mob;
        }

        @Override
        public void tick() {
            if (!this.slender.strafeWalking || this.operation != MoveControl.Operation.MOVE_TO) {
                super.tick();
                return;
            }
            this.operation = MoveControl.Operation.WAIT;
            double dx = this.wantedX - this.mob.getX();
            double dz = this.wantedZ - this.mob.getZ();
            double dy = this.wantedY - this.mob.getY();
            if (dx * dx + dy * dy + dz * dz < 2.5000003E-7D) {
                this.mob.setZza(0.0F);
                this.mob.setXxa(0.0F);
                return;
            }
            double len = Math.sqrt(dx * dx + dz * dz);
            this.mob.setSpeed((float) (this.speedModifier * this.mob.getAttributeValue(Attributes.MOVEMENT_SPEED)));
            if (len > 1.0E-6D) {
                double ux = dx / len, uz = dz / len;
                float yaw = this.mob.getYRot() * 0.017453292F;
                double sin = Math.sin(yaw), cos = Math.cos(yaw);
                // vanilla: forward = (-sin, cos), right-left axis = (cos, sin)
                this.mob.setZza((float) (-ux * sin + uz * cos));
                this.mob.setXxa((float) (ux * cos + uz * sin));
            }
            // the same jump as the vanilla mob: a step higher than he can climb right in front of him
            net.minecraft.core.BlockPos pos = this.mob.blockPosition();
            net.minecraft.world.level.block.state.BlockState state = this.mob.level.getBlockState(pos);
            net.minecraft.world.phys.shapes.VoxelShape shape = state.getCollisionShape(this.mob.level, pos);
            if (dy > (double) this.mob.maxUpStep && dx * dx + dz * dz < (double) Math.max(1.0F, this.mob.getBbWidth())
                    || !shape.isEmpty() && this.mob.getY() < shape.max(net.minecraft.core.Direction.Axis.Y) + (double) pos.getY()
                    && !state.is(net.minecraft.tags.BlockTags.DOORS) && !state.is(net.minecraft.tags.BlockTags.FENCES)) {
                this.mob.getJumpControl().jump();
                this.operation = MoveControl.Operation.JUMPING;
            }
        }
    }

    public static AttributeSupplier.Builder createAttributes() {
        return Mob.createMobAttributes()
                .add(Attributes.MAX_HEALTH, 100.0D)
                .add(Attributes.MOVEMENT_SPEED, 0.12D)
                .add(Attributes.FOLLOW_RANGE, 64.0D); // path search range only (a bigger value makes every path search very slow)
    }

    @Override
    protected void defineSynchedData() {
        super.defineSynchedData();
        this.entityData.define(DATA_TENT, 0.0F);
        this.entityData.define(DATA_TILT, 0.0F);
        this.entityData.define(DATA_ACTION, 0);
        this.entityData.define(DATA_PROGRESS, 0.0F);
        this.entityData.define(DATA_SIDE, 1);
        this.entityData.define(DATA_HIDDEN, false);
        this.entityData.define(DATA_AGGRESSION, 0.0F);
        this.entityData.define(DATA_CUE, 0);
        this.entityData.define(DATA_SANITY, 0.0F);
        this.entityData.define(DATA_TELEPORT, 0);
        this.entityData.define(DATA_DEATH, 0);
        this.entityData.define(DATA_TURN, DEATH_TURN_TICKS);
        this.entityData.define(DATA_BLIP, 0);
    }

    public int getAction() { return this.entityData.get(DATA_ACTION); }

    /** 0..1: how aggressive he is right now (rises while the player is close, falls slowly afterwards). */
    public float getAggression() { return this.entityData.get(DATA_AGGRESSION); }

    /** watcher mode (/slender watch): synced as aggression -1. No roaming sound and no camera noise for him. */
    public boolean isWatcher() { return this.entityData.get(DATA_AGGRESSION) < 0.0F; }

    /** 0..100: how much sanity the hunted player has LOST (100 = he kills with one hit). Readable on the client. */
    public float getSanity() { return this.entityData.get(DATA_SANITY); }

    /** changes with every real teleport (counter * 4 + flags); the camera plays its jumpscare when it changes. */
    public int getTeleportCue() { return this.entityData.get(DATA_TELEPORT); }

    /** 0 = nothing, 1 = the view of the victim turns to his face, 2 = black screen before the death. */
    public int getDeathPhase() { return this.entityData.get(DATA_DEATH); }
    /** the length of the turn to his face in ticks (read by the client camera). */
    public int getTurnTicks() { return this.entityData.get(DATA_TURN); }
    /** DEATH_TURN_TICKS with no loss, DEATH_TURN_MIN_TICKS when the sanity is already at 100: running at him for long = he is on you quicker. */
    public static int turnTicksFor(float lost) {
        float k = Mth.clamp(lost / SlenderSanity.MAX, 0.0F, 1.0F);
        return Math.round(Mth.lerp(k, (float) DEATH_TURN_TICKS, (float) DEATH_TURN_MIN_TICKS));
    }

    /** changes when the watcher vanishes in front of the player (camera: a ~60 ms static). */
    public int getBlipCue() { return this.entityData.get(DATA_BLIP); }

    public int getActionSide() { return this.entityData.get(DATA_SIDE); }

    /** true right after a teleport: the renderer skips him for a few ticks. */
    public boolean isTeleportHidden() { return this.entityData.get(DATA_HIDDEN); }

    public float getProgress(float partial) {
        if (progNow <= 0.0F) return 0.0F;
        return Mth.lerp(partial, progPrev, progNow);
    }

    public float getHeadTilt(float partial) { return Mth.lerp(partial, tiltPrev, tiltNow); }

    public float getTentacles(float partial) { return Mth.lerp(partial, tentPrev, tentNow); }

    @Override
    public void tick() {
        super.tick();
        // the walking animation fades in / out in 300 ms (he moved this tick = he walks)
        this.walkAnimO = this.walkAnim;
        boolean moved = Math.hypot(this.getX() - this.xo, this.getZ() - this.zo) > 0.02D;
        if (moved) this.walkAnim += Mth.clamp(1.0F - this.walkAnim, -WALK_ANIM_STEP, WALK_ANIM_STEP);
        else this.walkAnim = 0.0F;          // he stopped (seen): straight back to the stance at once, no fading
        if (this.level.isClientSide) {
            tentPrev = tentNow;
            tentNow = this.entityData.get(DATA_TENT);
            tiltPrev = tiltNow;
            tiltNow = this.entityData.get(DATA_TILT);
            progPrev = progNow;
            progNow = this.entityData.get(DATA_PROGRESS);
            DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> com.example.slenderman.client.SlendermanRoamingSound.clientTick(this));
            // a new cue from the server = play that sound as a plain 2D sound (not positioned in the world)
            int cue = this.entityData.get(DATA_CUE);
            if (!this.cueInit) {
                this.cueInit = true;
                this.lastCue = cue;          // a sound that was started before we joined is not replayed
            } else if (cue != this.lastCue) {
                this.lastCue = cue;
                final int soundId = cue % 100;
                DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> com.example.slenderman.client.SlendermanRoamingSound.playCue(this, soundId));
            }
        } else {
            if (scareCooldown > 0) scareCooldown--;
            if (sightCooldown > 0) sightCooldown--;
            if (animCooldown > 0) animCooldown--;
            if (tpHideTicks > 0 && --tpHideTicks == 0) {
                this.entityData.set(DATA_HIDDEN, false);
            }
        }
    }

    @Override
    protected float getStandingEyeHeight(Pose pose, EntityDimensions dimensions) { return 3.7F; }

    /**
     * The client must NOT slide him from the old spot to the new one (that is the "flies through the air" you could see):
     * a jump of more than one block moves him there at once, also for the picture between two ticks.
     */
    @Override
    public void lerpTo(double x, double y, double z, float yRot, float xRot, int steps, boolean teleport) {
        double dx = x - this.getX(), dy = y - this.getY(), dz = z - this.getZ();
        if (dx * dx + dy * dy + dz * dz > 1.0D) {
            this.setPos(x, y, z);
            this.xo = x; this.yo = y; this.zo = z;
            this.xOld = x; this.yOld = y; this.zOld = z;
            this.setYRot(yRot); this.setXRot(xRot);
            this.yRotO = yRot; this.xRotO = xRot;
            this.yBodyRot = yRot; this.yBodyRotO = yRot;
            this.yHeadRot = yRot; this.yHeadRotO = yRot;
            this.lerpSteps = 0;
            return;
        }
        super.lerpTo(x, y, z, yRot, xRot, steps, teleport);
    }

    @Override
    public boolean isPushable() { return false; }

    @Override
    public boolean isInvulnerableTo(DamageSource source) { return !source.isBypassInvul(); }

    @Override
    protected void playStepSound(BlockPos pos, BlockState state) {}

    @Override
    public boolean removeWhenFarAway(double distance) { return false; }

    @Override
    public boolean canBeLeashed(Player player) { return false; }

    @Override
    public AABB getBoundingBoxForCulling() { return super.getBoundingBoxForCulling().inflate(8.0D, 4.0D, 8.0D); }

    /** the chunk he stands in is kept loaded and ticking, so he never freezes when the player is far away or teleports. */
    private void keepChunkLoaded() {
        if (!(this.level instanceof ServerLevel serverLevel)) return;
        ChunkPos now = this.chunkPosition();
        if (now.equals(this.forcedChunk)) return;
        if (this.forcedChunk != null) {
            ForgeChunkManager.forceChunk(serverLevel, SlendermanMod.ID, this, this.forcedChunk.x, this.forcedChunk.z, false, true);
        }
        ForgeChunkManager.forceChunk(serverLevel, SlendermanMod.ID, this, now.x, now.z, true, true);
        this.forcedChunk = now;
    }

    @Override
    public void remove(Entity.RemovalReason reason) {
        if (!this.level.isClientSide && this.forcedChunk != null && reason.shouldDestroy()
                && this.level instanceof ServerLevel serverLevel) {
            ForgeChunkManager.forceChunk(serverLevel, SlendermanMod.ID, this, this.forcedChunk.x, this.forcedChunk.z, false, true);
            this.forcedChunk = null;
        }
        super.remove(reason);
    }

    /** nearest player in this dimension, at ANY distance (no 512 block limit any more). */
    private Player findTarget() {
        Player best = null;
        double bestDist = Double.MAX_VALUE;
        for (Player p : this.level.players()) {
            if (p.isSpectator() || !p.isAlive()) continue;
            double d = this.distanceToSqr(p);
            if (d < bestDist) {
                bestDist = d;
                best = p;
            }
        }
        return best;
    }

    @Override
    protected void customServerAiStep() {
        super.customServerAiStep();
        this.strafeWalking = false;
        this.keepChunkLoaded();

        // ---- death scene running: nothing else matters ----
        if (this.deathPhase != 0) {
            this.runDeath();
            return;
        }

        Player player = this.findTarget();
        if (player == null) {
            this.standStill();
            return;
        }
        if (this.finaleMode) {             // the finale of the 8th page
            this.runFinaleMode(player);
            return;
        }
        if (this.demoWalkTicks > 0) {      // /slender animations walk
            this.demoWalkStep(player);
            return;
        }
        boolean seen = this.isSeenBy(player);
        double dist = this.distanceTo(player);
        // the wide cone: he moves ONLY when the player has not had him anywhere near the screen for a few ticks
        this.wideUnseen = this.isSeenBy(player, WIDE_SEEN_COS) ? 0 : this.wideUnseen + 1;

        // watcher mode: he only watches from far away, nothing else
        if (SlenderModes.isWatch(player)) {
            this.runWatcher(player, seen, dist);
            return;
        }
        this.watchPlaced = false;
        final boolean god = SlenderModes.isGod(player);

        this.updateStage(player);

        // ---- sanity: rises while the player looks at him, falls when he does not; at 100 he kills ----
        this.trackPlayerMovement(player);
        // the original's ProximityTest: "chasing" (line of sight, closer than 30) and "caught" (closer than 2 with a clear line)
        final boolean caught = this.proximityTest(player, dist);
        if (god) {
            // God Mode: the sanity is never lost, nothing can kill
            this.calmSanity(player);
        } else {
            this.updateSanity(player, seen, dist);
            // original: 2 blocks or closer with a clear line = caught (no look needed); a touch below stays as it was
            if (caught && this.beginDeath(player, seen, false)) return;
            // the player walks into him: instant death (the view turns to his face first)
            // the view turns to his face (exactly 2 s) ONLY when the player ran into him while he was in view; from behind: no turn
            // running into him: the view turns to his face, then the black screen (it is never skipped), then the portrait
            if (this.touchesPlayer(player)) {
                boolean sudden = player.isSprinting() || this.playerSpeed > RUN_SPEED;
                if (this.beginDeath(player, seen || sudden, sudden)) return;
            }
            if (this.sanityRush) {
                this.sanityKill(player, dist);
                return;
            }
        }

        // ---- he never loses the player: far away (or player teleported) -> he jumps to the player ----
        if (dist > FOLLOW_DISTANCE && !this.vanished) {
            if (++this.farTicks >= FOLLOW_DELAY) {
                this.vanished = false;
                this.vanishTicks = 0;
                boolean ok = this.teleportNear(player, TeleportKind.FAR);
                if (ok) {
                    this.farTicks = 0;
                    this.approachTicks = 0;
                } else {
                    this.farTicks = FOLLOW_DELAY - 20; // no free spot found, try again in a second
                }
                return;
            }
        } else {
            this.farTicks = 0;
        }

        // ---- bookkeeping about what the player sees ----
        boolean wasSeen = this.prevSeen;
        double lastDist = this.prevDist;
        int unseenBefore = this.unseenTicks;
        this.prevSeen = seen;
        this.prevDist = dist;
        this.unseenTicks = seen ? 0 : this.unseenTicks + 1;
        this.observedTicks = seen ? this.observedTicks + 1 : 0;
        if (seen && !wasSeen && !this.vanished && !god && this.tickCount - this.lastTeleportTick > 10) {
            // the original's "seencount": after 4 sightings the next jump is very close
            if (++this.seenCount >= 4) {
                this.seenCount = 0;
                this.angry = true;
            }
        }

        // ---- aggression: rises while the player is close, falls slowly when not; tentacles follow it ----
        double aggRange = AGGRESSION_RANGE + AGG_RANGE_ADD[this.stage];
        if (dist <= aggRange) this.aggression = Math.min(1.0F, this.aggression + AGGRESSION_RISE * AGG_RISE_MUL[this.stage]);
        else this.aggression = Math.max(0.0F, this.aggression - AGGRESSION_FALL);
        this.entityData.set(DATA_AGGRESSION, this.aggression);
        // the tentacles come out when 60 of 100 sanity is lost and go back when it has fallen below 55
        float lost = this.entityData.get(DATA_SANITY);
        // original GrowTentacles: the scale is (80 - sanity) * 0.01, i.e. they grow smoothly from 20 lost and are full at 100
        this.updateTentaclesTo(Mth.clamp((lost - TENTACLE_FROM) / (SlenderSanity.MAX - TENTACLE_FROM), 0.0F, 1.0F));
        // (no damage in hearts from touching him any more: the sanity goes down, and a touch is always the one-hit death above)

        // ---- the original's sight sounds (15 / 25 / 45 blocks, one cooldown of 25 s) ----
        if (!this.vanished && this.action == ACTION_NONE) {
            this.originalSight(player, seen, wasSeen, dist);
        }

        // ---- running animation ----
        if (this.action != ACTION_NONE) {
            this.updateHeadTilt(seen, false);
            this.actionTimer++;
            float progress;
            if (this.action == ACTION_LUNGE) {
                if (this.actionTimer >= this.actionDuration) {
                    this.endAction();
                    return;
                }
                // 1 s smooth rise (the arm and the torso go together), a hold, 1 s smooth return; he does NOT turn to the player
                progress = reachProgress(this.actionTimer, this.actionDuration);
            } else {
                if (this.actionTimer >= this.actionDuration) {
                    this.endAction(); // snaps back to the normal pose at once
                    return;
                }
                // slow, smooth bow that keeps growing until the very end
                float frac = (float) this.actionTimer / (float) this.actionDuration;
                progress = frac * frac * (3.0F - 2.0F * frac);
            }
            this.entityData.set(DATA_PROGRESS, progress);
            this.standStill();
            return;
        }

        if (this.vanished) {
            this.standStill();
            this.vanishTicks--;
            if (this.vanishTicks <= 0) {
                this.vanished = false;
                this.setInvisible(false);
                this.approachTicks = 0;
                this.reappear(player);
            }
            return;
        }

        if (this.vanishCooldown > 0) this.vanishCooldown--;

        // Rare disappearance after the player has stared at him for a while.
        if (seen && this.observedTicks > 80 && this.vanishCooldown <= 0
                && this.random.nextFloat() < VANISH_BY[this.stage]) {
            this.beginVanish(player);
            return;
        }

        // ---- teleport ability: checked every 30-50 s with the chance of the current page ----
        if (this.originalSchedule(player, dist)) {
            this.approachTicks = 0;
            return;
        }

        boolean walking = false;
        if (seen || this.wideUnseen < MOVE_GRACE_TICKS) {
            // the player is watching: he stops and stands dead still, head level and straight ahead (does NOT look at the player)
            this.approachTicks = 0;
            this.huntTicks = 0;
            this.farJumpTicks = 0;
            this.lagTicks = 0;
            this.stuckSeconds = 0;
            this.stuckProbe = 0;
            this.stuckPos = null;
            this.hopStepTicks = 0;
            this.setYHeadRot(this.yBodyRot);
        } else {
            // the player looks away: behind their back he turns to face them and comes for them
            this.updateReachability(player, dist);
            walking = this.originalWalk(player, dist);
            if (!walking) {
                this.faceToward(player.position(), 60.0F);
            }
            Vec3 eye = player.getEyePosition();
            this.getLookControl().setLookAt(eye.x, eye.y, eye.z, 60.0F, 60.0F);
        }
        this.updateHeadTilt(seen, walking);
        if (!walking) {
            this.standStill();
        }
    }

    // ---- walking inside structures: a path to the player, followed node by node ----
    private Path indoorPath;
    private int indoorPathTimer, indoorCheckTimer;
    private boolean indoorCached;

    /** the player or he himself is inside a structure (checked twice a second only). */
    private boolean indoorsNow(Player player) {
        if (--this.indoorCheckTimer <= 0) {
            this.indoorCheckTimer = 10;
            this.indoorCached = this.playerIndoors(player)
                    || Enclosure.isEnclosed(this.level, this.getX(), this.getEyeY(), this.getZ());
            if (!this.indoorCached) this.indoorPath = null;
        }
        return this.indoorCached;
    }

    /** the next point of the path to the player (null = no path: then he walks straight as before). */
    private Vec3 nextPathGoal(Player player) {
        if (--this.indoorPathTimer <= 0 || this.indoorPath == null || this.indoorPath.isDone()) {
            this.indoorPathTimer = 10;
            this.indoorPath = this.getNavigation().createPath(player, 0);
        }
        Path path = this.indoorPath;
        if (path == null || path.isDone()) return null;
        // skip the nodes he has already reached
        while (!path.isDone()) {
            BlockPos n = path.getNextNodePos();
            double dx = n.getX() + 0.5D - this.getX(), dz = n.getZ() + 0.5D - this.getZ();
            if (dx * dx + dz * dz < 0.5D && Math.abs(n.getY() - this.getY()) < 1.5D) path.advance();
            else break;
        }
        if (path.isDone()) return null;
        BlockPos n = path.getNextNodePos();
        return new Vec3(n.getX() + 0.5D, n.getY(), n.getZ() + 0.5D);
    }

    /** every second: can he walk to the player at all? A player who is high up or shut away "hides". */
    private void updateReachability(Player player, double dist) {
        if (--this.reachTimer > 0) return;
        this.reachTimer = 20;
        boolean high = player.getY() - this.getY() >= HIGH_DY;
        boolean blocked = false;
        if (!high && dist <= 40.0D) {
            Path path = this.getNavigation().createPath(player, 0);
            blocked = path == null || !path.canReach();
        }
        this.hiding = high || blocked;
    }

    /** walks to a point: along a path when the path finder finds one, otherwise (and when the path ends) straight at it. */
    private void walkToward(double x, double y, double z, double speed) {
        if (--this.pathRetry <= 0) {
            this.pathRetry = 10;
            this.pathOk = this.getNavigation().moveTo(x, y, z, speed);
        }
        if (!this.pathOk || this.getNavigation().isDone()) {
            this.getMoveControl().setWantedPosition(x, y, z, speed);
        }
    }

    /** the side he steps to while something is in the way (+1 / -1), and for how many more ticks. */
    private int sideDir, sideTicks;

    /**
     * Straight at the player, always (no path finding, no zigzags). When something blocks the way he steps sideways (the body still faces the player),
     * keeps going along the obstacle and goes straight again as soon as the way is free.
     */
    private void straightWalk(Vec3 goal, double speed) {
        this.getNavigation().stop();
        double dx = goal.x - this.getX(), dz = goal.z - this.getZ();
        double len = Math.sqrt(dx * dx + dz * dz);
        if (len < 1.0E-4D) return;
        double ux = dx / len, uz = dz / len;
        double px = -uz, pz = ux;                         // to his left, looking at the player
        if (this.sideTicks > 0) {
            this.sideTicks--;
        } else if (this.horizontalCollision && len > 1.0D) {
            boolean leftFree = this.sideFree(px, pz), rightFree = this.sideFree(-px, -pz);
            if (leftFree || rightFree) {
                this.sideDir = leftFree && (!rightFree || this.random.nextBoolean()) ? 1 : -1;
                this.sideTicks = 12;
            }
        }
        double tx = ux, tz = uz;
        if (this.sideTicks > 0 && this.sideDir != 0) {
            // mostly sideways, a little forward: along the obstacle
            tx = px * this.sideDir * 0.9D + ux * 0.3D;
            tz = pz * this.sideDir * 0.9D + uz * 0.3D;
            if (this.horizontalCollision && this.sideTicks < 8) { this.sideDir = -this.sideDir; this.sideTicks = 12; }   // blocked on that side too: the other one
        }
        this.getMoveControl().setWantedPosition(this.getX() + tx * 3.0D, goal.y, this.getZ() + tz * 3.0D, speed);
    }

    /** is the block one step to this side (at the feet and at the head) free? */
    private boolean sideFree(double sx, double sz) {
        net.minecraft.core.BlockPos feet = new net.minecraft.core.BlockPos(this.getX() + sx * 1.0D, this.getY() + 0.1D, this.getZ() + sz * 1.0D);
        return this.level.getBlockState(feet).getCollisionShape(this.level, feet).isEmpty()
                && this.level.getBlockState(feet.above()).getCollisionShape(this.level, feet.above()).isEmpty();
    }

    /** a random distance inside the band of the current stage. */
    private double bandDistance() {
        int st = Mth.clamp(this.stage, 1, SlenderPages.TOTAL);
        return BAND_MIN[st] + this.random.nextDouble() * (BAND_MAX[st] - BAND_MIN[st]);
    }

    /** far behind the band, or not moving although he wants to: after 4 s he steps closer (silently, no teleport sound / glitch). */
    private void watchProgress(Player player, double dist, double wantMax) {
        if (++this.stuckProbe >= 20) {
            this.stuckProbe = 0;
            Vec3 now = this.position();
            if (this.stuckPos != null && now.distanceToSqr(this.stuckPos) < 0.36D) this.stuckSeconds++;
            else this.stuckSeconds = 0;
            this.stuckPos = now;
        }
        // the original has no "catching up" hops: the teleport timers bring him back; only being stuck is helped
        if (this.stuckSeconds < 3) {
            this.lagTicks = 0;
            return;
        }
        if (++this.lagTicks < REPOSITION_TICKS) return;
        this.lagTicks = 0;
        this.stuckSeconds = 0;
        if (!autoTeleport) return;
        this.suppressTeleportCue = true;
        this.creepCloser(player, REPOSITION_STEP, wantMax + 3.0D);
        this.suppressTeleportCue = false;
    }

    private void standStill() {
        this.getNavigation().stop();
        this.pathRetry = 0;
        this.indoorPath = null;
        this.sideTicks = 0;
        this.setZza(0.0F);
        this.setXxa(0.0F);
        this.setSpeed(0.0F);
        this.setDeltaMovement(0.0D, this.getDeltaMovement().y, 0.0D);
    }

    /**
     * /slender animations: kind 1 = the reach, 2 = the bow, 3 = the walk, 0 = stop. He is put in front of the player (7 blocks, 12 for the walk),
     * facing him, and shows the animation. Returns false if no place was found.
     */
    public boolean playDemo(Player player, int kind) {
        this.demoWalkTicks = 0;
        if (this.action != ACTION_NONE) this.endAction();
        this.getNavigation().stop();
        if (kind == 0) {
            this.standStill();
            return true;
        }
        this.vanished = false;
        this.setInvisible(false);
        Vec3 look = player.getLookAngle();
        double len = Math.sqrt(look.x * look.x + look.z * look.z);
        if (len < 1.0E-3D) return false;
        double dist = kind == 3 ? 12.0D : 7.0D;
        double x = player.getX() + look.x / len * dist;
        double z = player.getZ() + look.z / len * dist;
        double y = this.findGround(x, z, player.getY(), 4.0D, 3);
        if (Double.isNaN(y)) return false;
        float yaw = (float) (Mth.atan2(player.getZ() - z, player.getX() - x) * 57.29577951308232D) - 90.0F;
        this.suppressTeleportCue = true;
        this.relocate(x, y, z, yaw);
        this.suppressTeleportCue = false;
        this.animCooldown = 0;
        if (kind == 1) {
            this.startAction(ACTION_LUNGE, LUNGE_TICKS);
        } else if (kind == 2) {
            this.startAction(ACTION_PEEK, PEEK_TICKS);
            this.entityData.set(DATA_SIDE, this.random.nextBoolean() ? 1 : -1);
        } else {
            this.demoWalkTicks = 20 * 8;
        }
        return true;
    }

    private void demoWalkStep(Player player) {
        this.demoWalkTicks--;
        if (this.distanceTo(player) <= 3.0D || this.demoWalkTicks <= 0) {
            this.demoWalkTicks = 0;
            this.standStill();
            return;
        }
        this.setYHeadRot(this.yBodyRot);
        this.walkToward(player.getX(), player.getY(), player.getZ(), 1.2D);
    }

    /**
     * 0..1 of the reach at a tick of the action (the torso, the head and the arm follow it together): in the first moment (60 ms) he jumps into the pose
     * (REACH_FIRST of it), then for the rest of the 3 s he slowly goes on (leans, tilts the head, stretches the arm) up to the full pose,
     * and in the last moment (60 ms) he jumps back to the normal stance.
     */
    private static float reachProgress(int timer, int duration) {
        float up = Mth.clamp((float) timer / (float) REACH_TICKS, 0.0F, 1.0F);
        float p;
        if (timer < REACH_TICKS) {
            p = REACH_FIRST * (up * up * (3.0F - 2.0F * up));
        } else {
            float slow = Mth.clamp((float) (timer - REACH_TICKS) / (float) Math.max(1, duration - 2 * REACH_TICKS), 0.0F, 1.0F);
            p = REACH_FIRST + (1.0F - REACH_FIRST) * slow;
        }
        float down = Mth.clamp((float) (duration - timer) / (float) REACH_TICKS, 0.0F, 1.0F);
        return p * (down * down * (3.0F - 2.0F * down));
    }

    // ---- the head tilt of the waiting mode ----
    private static final float TILT_CHANCE = 0.5F;       // one roll per "standing": about half of the waits end with a tilted head
    private static final int TILT_TICKS = 10;            // the tilt takes 0.5 s
    private boolean tiltGoal, tiltRolled;
    private int tiltWait, tiltDelay = 40;
    private Vec3 tiltSpot;

    /**
     * Waiting mode: he stands somewhere and nobody looks at him. Once per standing (1-4 s after he stands, only while he is NOT seen)
     * there is a 50 % chance that he leans his head to his right side, in 0.5 s. He keeps it so (also while he is seen)
     * until he moves away (a teleport, a walk): then the head comes back in 0.5 s. It has nothing to do with the reach.
     */
    private void updateHeadTilt(boolean seen, boolean walking) {
        if (this.tiltSpot == null || this.position().distanceToSqr(this.tiltSpot) > 2.25D || walking || this.vanished) {
            this.tiltSpot = this.position();
            this.tiltGoal = false;
            this.tiltRolled = false;
            this.tiltWait = 0;
            this.tiltDelay = 20 + this.random.nextInt(61);
        } else if (!this.tiltRolled && this.action == ACTION_NONE && !seen && this.wideUnseen >= MOVE_GRACE_TICKS) {
            if (++this.tiltWait >= this.tiltDelay) {
                this.tiltRolled = true;
                this.tiltGoal = this.random.nextFloat() < TILT_CHANCE;
            }
        }
        float tilt = this.entityData.get(DATA_TILT);
        float next = Mth.clamp(tilt + (this.tiltGoal ? 1.0F : -1.0F) / (float) TILT_TICKS, 0.0F, 1.0F);
        if (next != tilt) this.entityData.set(DATA_TILT, next);
    }

    private void startAction(int newAction, int duration) {
        this.action = newAction;
        this.actionTimer = 0;
        this.actionDuration = duration;
        this.entityData.set(DATA_ACTION, newAction);
        this.entityData.set(DATA_PROGRESS, 0.0F);
    }

    private void endAction() {
        this.action = ACTION_NONE;
        this.actionTimer = 0;
        this.entityData.set(DATA_ACTION, ACTION_NONE);
        this.entityData.set(DATA_PROGRESS, 0.0F);
        this.standStill();
    }

    /** the tentacles follow a value 0..1 (the sanity loss), a little smoothed so they never jump. */
    private void updateTentaclesTo(float target) {
        this.tent += Mth.clamp(target - this.tent, -0.05F, 0.05F);
        this.entityData.set(DATA_TENT, this.tent);
    }

    private void updateTentacles(boolean growing) {
        this.tent += Mth.clamp((growing ? 1.0F : 0.0F) - this.tent, -0.01F, 0.02F);
        this.entityData.set(DATA_TENT, this.tent);
    }

    // ------------------------------------------------------------------ screamers

    /**
     * One screamer = exactly one random sound. After it NO screamer sound can play for 20-25 seconds, however often
     * the player turns to him. The animation is tied to the sound: when a sound plays it may come with the reach
     * (40 %) or the slow bow (30 %), but only if the animation cooldown (40-60 s) is over; otherwise sound only.
     */
    private void scare(boolean turned, int unseenBefore) {
        if (this.scareCooldown > 0 || this.vanished) return;
        this.scareCooldown = (int) ((SCARE_COOLDOWN_MIN + this.random.nextInt(SCARE_COOLDOWN_RANDOM + 1)) * SCARE_SCALE[this.stage]);
        int idx = this.random.nextInt(SlendermanMod.SCREAMS.size());
        this.sendCue(1 + idx);

        // EVERY screamer comes with an animation: the old chances (REACH_BY / BOW_BY) now only decide WHICH one (the reach or the bow)
        if (this.action != ACTION_NONE) return;
        float reachW = REACH_BY[this.stage], bowW = BOW_BY[this.stage];
        if (reachW + bowW <= 0F) { reachW = REACH_CHANCE; bowW = BOW_CHANCE; }     // pages 1-2 have no chances: use the general ones
        int anim = this.forcedAnim != 0 ? this.forcedAnim
                : (this.random.nextFloat() * (reachW + bowW) < reachW ? ACTION_LUNGE : ACTION_PEEK);
        this.animCooldown = (int) ((ANIM_COOLDOWN_MIN + this.random.nextInt(ANIM_COOLDOWN_RANDOM + 1)) * SCARE_SCALE[this.stage]);
        if (anim == ACTION_LUNGE) {
            this.startAction(ACTION_LUNGE, LUNGE_TICKS);
        } else {
            this.startAction(ACTION_PEEK, PEEK_TICKS);
            this.entityData.set(DATA_SIDE, this.random.nextBoolean() ? 1 : -1);
        }
    }

    /**
     * Tells the client to play a sound as a plain 2D sound (it is NOT placed in the world, so it does not get
     * quieter or stay behind when the player runs). ids: 1..3 screamers, 10.. far sight, 30.. middle sight.
     */
    private void sendCue(int soundId) {
        this.cueSeq = (this.cueSeq + 1) % 20000 + 1;
        this.entityData.set(DATA_CUE, this.cueSeq * 100 + soundId);
    }

    // ------------------------------------------------------------------ aggression stages

    /**
     * The aggression of the original also grows with TIME, so nobody can relax: from the first page on, every 5 minutes raise
     * the stage by one, even if the next page was not found (5 min = the 2nd page, 10 min = the 3rd, ... at most the 7th: the 8th
     * needs the real page). The real pages count as before; the higher of the two wins. The clock is the game time of the
     * server (it stands still while the game is paused), it starts with the first page and is cleared when the pages are reset.
     */
    private int timedPages(Player player, int pages) {
        net.minecraft.nbt.CompoundTag data = player.getPersistentData();
        if (!TIME_STAGES || pages <= 0 || pages >= SlenderPages.TOTAL) {
            if (pages <= 0 && data.contains(FIRST_PAGE_KEY)) data.remove(FIRST_PAGE_KEY);
            return pages;
        }
        long now = player.level.getGameTime();
        if (!data.contains(FIRST_PAGE_KEY) || data.getLong(FIRST_PAGE_KEY) > now) data.putLong(FIRST_PAGE_KEY, now);
        long since = now - data.getLong(FIRST_PAGE_KEY);
        int timed = 1 + (int) (since / TIME_STAGE_TICKS);
        return Math.max(pages, Math.min(TIME_STAGE_MAX, timed));
    }

    /** the stage follows the pages of the player he hunts. */
    private void updateStage(Player player) {
        int pages = SlenderPages.get(player);
        if (pages <= 0) {            // no pages (a reset, a new life): the anger and the sightings of the last game do not carry over
            this.seenCount = 0;
            this.angry = false;
        }
        this.stage = Mth.clamp(this.timedPages(player, pages), 1, SlenderPages.TOTAL);
    }

    /** the finale of the 8th page (SlenderFinale) switches this on: he is placed by the finale, shows the tentacles and kills nobody. */
    public void setFinaleMode(boolean on) {
        this.finaleMode = on;
        this.vanished = false;
        this.setInvisible(false);
        this.entityData.set(DATA_HIDDEN, false);
        if (on) this.endAction();
    }

    public void setFinaleRealSanity(boolean on) {
        this.finaleRealSanity = on;
        this.finaleScared = false;
        this.finaleTurnTicks = 0;
    }

    /** the screamer of the finale: ALWAYS one screamer sound and ALWAYS the reaching of the hand (no cooldowns, no chances). */
    private void finaleScare() {
        this.sendCue(1 + this.random.nextInt(SlendermanMod.SCREAMS.size()));
        this.startAction(ACTION_LUNGE, LUNGE_TICKS);
    }

    /** in the finale mode: stands, looks at the player, the aggression is full and the tentacles grow with the sanity loss of the player. */
    private void runFinaleMode(Player player) {
        this.standStill();
        this.aggression = 1.0F;
        this.entityData.set(DATA_AGGRESSION, 1.0F);
        if (this.finaleRealSanity && !this.finaleScared && this.action == ACTION_NONE
                && (this.isSeenBy(player) || ++this.finaleTurnTicks > 60)) {
            this.finaleScared = true;                      // the first moment the player sees him (or after 3 s at the latest)
            this.finaleScare();
        }
        if (this.action == ACTION_LUNGE) {                 // the reach of the hand goes on its own timer
            if (++this.actionTimer >= this.actionDuration) {
                this.endAction();
            } else {
                this.entityData.set(DATA_PROGRESS, reachProgress(this.actionTimer, this.actionDuration));
            }
        }
        if (this.finaleRealSanity) this.updateSanity(player, this.isSeenBy(player), this.distanceTo(player));
        float lost = SlenderSanity.get(player);
        this.entityData.set(DATA_SANITY, lost);
        this.tentGoal = lost > 1.0F;
        this.updateTentaclesTo(Mth.clamp(lost / SlenderSanity.MAX, 0.0F, 1.0F));
        this.faceToward(player.position(), 20.0F);
        Vec3 eye = player.getEyePosition();
        this.getLookControl().setLookAt(eye.x, eye.y, eye.z, 30.0F, 30.0F);
    }

    // ------------------------------------------------------------------ the original's logic (the only logic)

    /** the original's check interval in ticks: (550 - 50 * (pages + level)) steps / 2.5 steps per tick. */
    private int jumpThreshold(int st) {
        return Math.max(1, (int) Math.round((JUMP_TIME_STEPS - JUMP_STEPS_PER_PAGE * (st + LEVEL)) / STEPS_PER_TICK));
    }

    /**
     * ProximityTest of the original. While the model is there and the line is clear: closer than 30 blocks = "chasing",
     * 2 blocks or closer = caught (returns true). Farther than 30 blocks "chasing" is off again.
     */
    private boolean proximityTest(Player player, double dist) {
        if (!this.isModelShown() || this.deathPhase != 0 || player.isSpectator() || !player.isAlive()) return false;
        if (dist < PROXIMITY_DIST) {
            if (this.hasClearLine(player)) {
                this.chasing = true;
                if (dist <= CATCH_DIST && KILL_AT_CATCH_DIST) {
                    this.chasing = false;
                    return true;
                }
            }
        } else {
            this.chasing = false;
        }
        return false;
    }

    /**
     * SlenderLogic of the original. Both counters run all the time. He may act when the player does not see him (or from
     * page 6 on, also in view). When the interval of the current page is over:
     *  - he is not chasing (no line of sight within 30 blocks), or he is farther than 10 blocks (20 % per step): the check is made;
     *  - farther than the page distance (or a 10 % roll): the real teleport next to the player ("move 2");
     *  - otherwise, after porttime (2 s) since the last one: the real teleport with 50 %;
     *  - otherwise a small step of 4 blocks towards the player ("move 1").
     * Returns true if he teleported (then nothing else happens in this tick).
     */
    private boolean originalSchedule(Player player, double dist) {
        this.makeJump++;
        this.mightPort++;
        if (!autoTeleport || this.action != ACTION_NONE || this.vanished) return false;
        int st = Mth.clamp(this.stage, 1, SlenderPages.TOTAL);
        boolean hidden = this.wideUnseen >= MOVE_GRACE_TICKS;
        if (!hidden && st + LEVEL < OPEN_TELEPORT_STAGE) {
            this.mightPort = 0;
            return false;
        }
        int threshold = this.jumpThreshold(st);
        if (this.makeJump < threshold) return false;
        if (this.chasing && !(dist > CHASE_TELEPORT_DIST && this.random.nextFloat() < CHASE_TELEPORT_CHANCE_PER_TICK)) return false;
        this.makeJump = 0;
        boolean tried = true;
        boolean done;
        if ((dist > BAND_MAX[st] || this.random.nextFloat() <= FAR_ROLL) && st < SlenderPages.TOTAL) {
            done = this.moveTwo(player, st);
        } else if (this.mightPort > PORT_TIME_TICKS) {
            this.mightPort = 0;
            tried = this.random.nextFloat() <= PORT_ROLL;
            done = tried && this.moveTwo(player, st);
        } else {
            done = this.moveOne(player, st);
        }
        if (tried && !done) this.makeJump = Math.max(0, threshold - 20);   // no spot found: the next try comes in a second
        return done;
    }

    /**
     * CalculateMove(2): he appears at a random angle around the player at a distance of the page band (BAND_MIN..BAND_MAX).
     * "Angry" (4 sightings) or the player inside a structure (50 %): an eighth of the START distance (START_MIN..START_MAX), the same on every page.
     * Before page 6 a FOCUSED flashlight only lets him appear where the player cannot see him; if that fails, he appears
     * behind the player (the side away from him), like the original's retry.
     */
    private boolean moveTwo(Player player, int st) {
        boolean indoors = this.playerIndoors(player);
        double lo = BAND_MIN[st];
        double hi = BAND_MAX[st];
        boolean near = this.angry || (indoors && this.random.nextFloat() > 0.5F);
        if (near) {
            // the same on every page: an eighth of the START distance (30-35 / 8 = 3.75 - 4.4 blocks)
            lo = START_MIN / NEAR_DIVISOR;
            hi = START_MAX / NEAR_DIVISOR;
        }
        boolean needHidden = st + LEVEL < OPEN_TELEPORT_STAGE
                && (!UNFOCUSED_MAY_APPEAR_IN_VIEW || FlashlightLink.isOnAndFocused(player));
        int planeDy = indoors ? 1 : 3;
        double awayAngle = Math.atan2(player.getZ() - this.getZ(), player.getX() - this.getX());
        for (int attempt = 0; attempt < 72; attempt++) {
            boolean behind = attempt >= 60;
            double angle = behind ? awayAngle + Math.toRadians((this.random.nextDouble() - 0.5D) * 50.0D)
                    : this.random.nextDouble() * Math.PI * 2.0D;
            double distance = lo + this.random.nextDouble() * (hi - lo);
            double x = player.getX() + Math.cos(angle) * distance;
            double z = player.getZ() + Math.sin(angle) * distance;
            if (this.isInFront(player, x, z)) {
                // in front of the player: as far as the band allows (the upper quarter of it), never closer than 3-4 blocks
                distance = Math.max(FRONT_MIN_DIST + this.random.nextDouble() * FRONT_MIN_RANDOM, hi - this.random.nextDouble() * 0.25D * (hi - lo));
                x = player.getX() + Math.cos(angle) * distance;
                z = player.getZ() + Math.sin(angle) * distance;
            }
            if (this.tooCloseToPlayer(player, x, z)) continue;
            double need = attempt < 24 ? 4.0D : (attempt < 44 ? 3.0D : 2.0D);
            double y = this.findGround(x, z, player.getY(), need, planeDy);
            if (Double.isNaN(y)) continue;
            // inside a structure he appears inside it, on the same floor (a roof above the spot)
            if (indoors && attempt < 48 && !this.hasRoof(x, y + 1.0D, z)) continue;
            if (needHidden && !behind && this.isSpotSeen(player, x, y, z)) continue;
            float yaw = (float) (Mth.atan2(player.getZ() - z, player.getX() - x) * 57.29577951308232D) - 90.0F;
            this.relocate(x, y, z, yaw);
            if (near) this.angry = false;
            return true;
        }
        return false;
    }

    /** the spot (x, z) is in front of the player: in his view cone, or in the direction he is walking. */
    private boolean isInFront(Player player, double x, double z) {
        double dx = x - player.getX();
        double dz = z - player.getZ();
        double len = Math.sqrt(dx * dx + dz * dz);
        if (len < 1.0E-3D) return true;
        dx /= len;
        dz /= len;
        Vec3 look = player.getViewVector(1.0F);
        double ll = Math.sqrt(look.x * look.x + look.z * look.z);
        if (ll > 1.0E-3D && (look.x * dx + look.z * dz) / ll > FRONT_COS) return true;
        Vec3 mv = player.getDeltaMovement();
        double ml = Math.sqrt(mv.x * mv.x + mv.z * mv.z);
        return ml > 0.03D && (mv.x * dx + mv.z * dz) / ml > FRONT_COS;
    }

    /** CalculateMove(1): a step of 4 blocks towards the player; when he is already inside the band, a random spot around himself. */
    private boolean moveOne(Player player, int st) {
        double dist = this.distanceTo(player);
        if (dist > BAND_MIN[st]) {
            // the step brings him closer; when he is in front of the player he stays at least 3-4 blocks away
            double keep = this.isInFront(player, this.getX(), this.getZ())
                    ? FRONT_MIN_DIST + this.random.nextDouble() * FRONT_MIN_RANDOM : Math.min(CATCH_DIST + 0.6D, BAND_MIN[st]);
            return this.creepCloser(player, 4.0D, keep);
        }
        for (int attempt = 0; attempt < 24; attempt++) {
            double angle = this.random.nextDouble() * Math.PI * 2.0D;
            double r = BAND_MIN[st] + 1.0D + this.random.nextDouble() * 3.0D;
            double x = this.getX() + Math.cos(angle) * r;
            double z = this.getZ() + Math.sin(angle) * r;
            double dx = x - player.getX();
            double dz = z - player.getZ();
            double minD = Math.min(CATCH_DIST + 0.6D, BAND_MIN[st]);
            if (dx * dx + dz * dz < minD * minD) continue;
            double y = this.findGround(x, z, this.getY(), 4.0D, this.planeDy(player));
            if (Double.isNaN(y)) continue;
            float yaw = (float) (Mth.atan2(player.getZ() - z, player.getX() - x) * 57.29577951308232D) - 90.0F;
            this.relocate(x, y, z, yaw);
            return true;
        }
        return false;
    }

    /**
     * From page 2 through page 7 he walks at a fixed FIXED_WALK_BPS (2 blocks per second), only while unseen.
     * The player-distance catch-up bonus is deliberately disabled for these stages so it cannot raise that speed.
     */
    private boolean originalWalk(Player player, double dist) {
        int st = Mth.clamp(this.stage, 1, SlenderPages.TOTAL);
        if (!this.ported || st <= 1 || this.hiding || dist <= CONTACT_DIST) {
            this.getNavigation().stop();
            return false;
        }
        boolean fixedWalkSpeed = st >= 2 && st <= 7;
        double blocksPerSecond = fixedWalkSpeed
                ? FIXED_WALK_BPS
                : (st + LEVEL + ORIGINAL_WALK_BASE) * ORIGINAL_WALK_SCALE;
        if (!fixedWalkSpeed && dist > BAND_MAX[st]) {
            // Outside pages 2-7, retain the original distance-based catch-up behavior.
            double playerBps = this.playerSpeed * 20.0D;
            blocksPerSecond = Math.max(blocksPerSecond, playerBps * CATCH_PLAYER_MUL[st])
                    + Math.min(CATCH_GAP_MAX, (dist - BAND_MAX[st]) * CATCH_GAP_BPS);
        }
        blocksPerSecond = Math.min(blocksPerSecond, WALK_BPS_MAX);
        double mul = blocksPerSecond / 20.0D / BLOCKS_PER_TICK_AT_ONE;
        Vec3 goal = player.position();
        // inside a structure (corridors, rooms, caves) he follows a path around the walls instead of walking straight into them
        if (this.indoorsNow(player)) {
            Vec3 node = this.nextPathGoal(player);
            if (node != null) goal = node;
        }
        // the body and the head turn smoothly to the player all the time; he walks straight at the player and goes around things SIDEWAYS
        this.faceToward(player.position(), WALK_TURN_DEG);
        this.strafeWalking = true;
        this.straightWalk(goal, mul);
        this.watchProgress(player, dist, CATCH_DIST);
        return true;
    }

    /**
     * LowerSanity's sounds of the original, one cooldown of 25 s for all of them:
     *  - closer than 15 blocks (a screamer, with its animation): needs the cooldown, or a sound of a farther distance before it;
     *  - closer than 25 blocks, the moment he comes into view: the "middle" sight sounds (the cooldown, or a "far" sound before it);
     *  - closer than 45 blocks, the moment he comes into view: the "far" sight sounds.
     * The animation of the screamer is chosen as before.
     */
    private void originalSight(Player player, boolean seen, boolean wasSeen, double dist) {
        if (!seen || !this.isModelShown()) return;
        boolean cooldownOver = this.tickCount - this.lastSightTick >= SIGHT_COOLDOWN_TICKS;
        boolean fresh = !wasSeen;
        if (dist < SIGHT_NEAR && (cooldownOver || this.lastSawRange > 0)) {
            this.lastSawRange = 0;
            this.lastSightTick = this.tickCount;
            this.scareCooldown = 0;                 // the shared cooldown of the original replaces the old one
            this.forcedAnim = dist <= SCARE_REACH_DIST ? ACTION_LUNGE : ACTION_PEEK;   // the animation fits the distance
            this.scare(true, this.unseenTicks);
            this.forcedAnim = 0;
        } else if (fresh && dist < SIGHT_MIDDLE && (cooldownOver || this.lastSawRange > 1)) {
            this.lastSawRange = 1;
            this.lastSightTick = this.tickCount;
            this.blipSeq = (this.blipSeq + 1) % 100000;
            this.entityData.set(DATA_BLIP, this.blipSeq);
            this.sendCue(30 + this.random.nextInt(SlendermanMod.MID_SIGHTS.size()));
        } else if (fresh && cooldownOver && dist < SIGHT_FAR) {
            this.lastSawRange = 2;
            this.lastSightTick = this.tickCount;
            this.blipSeq = (this.blipSeq + 1) % 100000;
            this.entityData.set(DATA_BLIP, this.blipSeq);
            this.sendCue(10 + this.random.nextInt(SlendermanMod.SIGHTS.size()));
        }
    }

    /** a spot (where he would stand) that the player could see now: inside the wide view cone with a clear line to it. */
    private boolean isSpotSeen(Player player, double x, double y, double z) {
        Vec3 eye = player.getEyePosition();
        Vec3 view = player.getViewVector(1.0F).normalize();
        for (double h : new double[]{3.6D, 2.0D, 0.6D}) {
            Vec3 p = new Vec3(x, y + h, z);
            Vec3 to = p.subtract(eye);
            double len = to.length();
            if (len < 0.5D) return true;
            if (view.dot(to.scale(1.0D / len)) < WIDE_SEEN_COS) continue;
            BlockHitResult hit = this.level.clip(new ClipContext(eye, p, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, player));
            if (hit.getType() == HitResult.Type.MISS || hit.getLocation().distanceToSqr(p) < 0.25D) return true;
        }
        return false;
    }

    // ------------------------------------------------------------------ teleport ability

    /**
     * The player is inside a structure / a building / a cave: a roof within 12 blocks above his head AND walls around him (see Enclosure);
     * a lone tree, a bridge or an overhang is not a structure.
     * Inside he does not walk, he teleports to spots he has worked out.
     */
    private boolean playerIndoors(Player player) {
        return Enclosure.isEnclosed(this.level, player.getX(), player.getEyeY(), player.getZ());
    }

    private boolean hasRoof(double x, double fromY, double z) {
        int ix = Mth.floor(x);
        int iz = Mth.floor(z);
        int y0 = Mth.floor(fromY) + 1;
        for (int dy = 0; dy < 12; dy++) {
            BlockPos pos = new BlockPos(ix, y0 + dy, iz);
            if (!this.level.hasChunkAt(pos)) return false;
            BlockState state = this.level.getBlockState(pos);
            if (state.isAir() || state.is(BlockTags.LEAVES)) continue;
            if (!state.getCollisionShape(this.level, pos).isEmpty()) return true;
        }
        return false;
    }

    /** how far above / below the player a teleport spot may be: inside a structure the SAME floor (1 block), outside 3 blocks (no roofs). */
    private int planeDy(Player player) {
        return this.playerIndoors(player) ? 1 : 3;
    }

    /** a teleport target that would be closer than TELEPORT_MIN_DIST to the player (horizontal distance). */
    private boolean tooCloseToPlayer(Player player, double x, double z) {
        double dx = x - player.getX();
        double dz = z - player.getZ();
        return dx * dx + dz * dz < TELEPORT_MIN_DIST * TELEPORT_MIN_DIST;
    }

    /** a random distance a little closer than the band up to its far end (for "in front of / behind the player" teleports). */
    private double aheadDistance() {
        int st = Mth.clamp(this.stage, 1, SlenderPages.TOTAL);
        double lo = Math.max(2.5D, BAND_MIN[st] - 2.0D);
        return lo + this.random.nextDouble() * (BAND_MAX[st] - lo);
    }

    private boolean visibleFrom(Vec3 eye, double x, double y, double z) {
        for (double h : new double[]{3.6D, 2.0D, 0.6D}) {
            Vec3 p = new Vec3(x, y + h, z);
            BlockHitResult hit = this.level.clip(new ClipContext(eye, p, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, this));
            if (hit.getType() == HitResult.Type.MISS || hit.getLocation().distanceToSqr(p) < 0.25D) return true;
        }
        return false;
    }

    /** he is in the half of the world behind the player's back. */
    private boolean isBehind(Player player) {
        Vec3 look = player.getLookAngle();
        Vec3 flatLook = new Vec3(look.x, 0.0D, look.z);
        Vec3 toMe = new Vec3(this.getX() - player.getX(), 0.0D, this.getZ() - player.getZ());
        if (flatLook.lengthSqr() < 1.0E-4D || toMe.lengthSqr() < 1.0E-4D) return false;
        return flatLook.normalize().dot(toMe.normalize()) < -0.2D;
    }

    // ------------------------------------------------------------------ sanity and helpers

    /** nothing solid between his chest and the player's body: no damage through walls. */
    private boolean hasClearLine(Player player) {
        Vec3 a = new Vec3(this.getX(), this.getY() + 1.4D, this.getZ());
        Vec3 b = new Vec3(player.getX(), player.getY() + 1.0D, player.getZ());
        return this.level.clip(new ClipContext(a, b, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, this)).getType() == HitResult.Type.MISS;
    }

    /** how long the player stands still and how fast / in which direction he runs (every tick). */
    private void trackPlayerMovement(Player player) {
        Vec3 pos = player.position();
        if (this.lastPlayerPos == null) this.lastPlayerPos = pos;
        double dx = pos.x - this.lastPlayerPos.x;
        double dz = pos.z - this.lastPlayerPos.z;
        double inst = Math.sqrt(dx * dx + dz * dz);
        if (inst > 2.0D) {          // the player was teleported: not running
            inst = 0.0D;
            dx = 0.0D;
            dz = 0.0D;
        }
        this.playerSpeed = this.playerSpeed * 0.7D + inst * 0.3D;
        if (inst > 0.05D) {
            this.moveX = dx / inst;
            this.moveZ = dz / inst;
        }
        this.idleTicks = pos.distanceToSqr(this.lastPlayerPos) < 0.0004D ? this.idleTicks + 1 : 0;
        this.lastPlayerPos = pos;
    }

    /** sanity loss (0..100) of the player: rises while he looks at Slenderman, falls when he does not. */
    private void updateSanity(Player player, boolean seen, double dist) {
        float value = SlenderSanity.get(player);
        // only a model that is really there counts: no phantom while he is vanished, invisible or just teleported
        boolean modelVisible = !this.vanished && !this.isInvisible() && !this.entityData.get(DATA_HIDDEN);
        boolean looking = seen && modelVisible && dist <= SANITY_MAX_DIST;
        // right behind the player's back (roaming.ogg plays): the sanity goes down even without looking at him
        boolean behind = modelVisible && dist <= BEHIND_RANGE && this.isBehind(player) && this.hasClearLine(player);
        if (looking || behind) {
            // the flashlight of the original: focused = 2^(-d/10), otherwise 2^(-d*2/10)
            value += SlenderSanity.lossPerTick(dist, FlashlightLink.isOnAndFocused(player));
            this.sanityDelay = 0;     // the original recovers at once, without a pause
            this.recoverRate = 0.0F;
        } else if (this.sanityDelay > 0) {
            this.sanityDelay--;
        } else {
            // the speed is fixed when the recovery starts: 15 s from a loss near 100, about 10 s from a small one
            if (this.recoverRate <= 0.0F) this.recoverRate = SlenderSanity.recoverPerTick(value);
            value -= this.recoverRate;
        }
        value = Mth.clamp(value, 0.0F, SlenderSanity.MAX);
        if (value <= 0.0F) this.recoverRate = 0.0F;
        SlenderSanity.set(player, value);
        this.entityData.set(DATA_SANITY, value);
        if (value >= SlenderSanity.MAX) this.sanityRush = true;
        else if (value <= SANITY_RUSH_OFF) this.sanityRush = false;
    }

    /**
     * Sanity is at 100: the player dies at once, WHEREVER he stands (as in the original: the sanity is lost while the player
     * looks at him, also from far away; he does not have to come closer). Only the death scene starts: the black screen, the portrait, the hit.
     */
    private void sanityKill(Player player, double dist) {
        this.vanished = false;
        this.setInvisible(false);
        this.standStill();
        if (this.rushGrace > 0) {
            this.rushGrace--;
            return;
        }
        this.faceToward(player.position(), 90.0F);
        this.beginDeath(player, false);   // 2 s of black screen, then the hit
    }

    // ------------------------------------------------------------------ death scene

    /** the model is really there for the player: not "vanished", not invisible, not in the first ticks after a teleport. */
    private boolean isModelShown() {
        return !this.vanished && !this.isInvisible() && !this.entityData.get(DATA_HIDDEN);
    }

    /** the player walked into him (the boxes touch). */
    private boolean touchesPlayer(Player player) {
        if (!this.isModelShown() || player.isSpectator() || !player.isAlive()) return false;
        return this.getBoundingBox().inflate(0.05D, 0.0D, 0.05D).intersects(player.getBoundingBox());
    }

    /** the killing hit: survival players are killed even through a totem, creative players are not hurt. */
    private DamageSource killSource(Player victim) {
        final boolean bypass = !victim.isCreative() && !victim.isSpectator();
        return new EntityDamageSource("mob", this) {
            {
                if (bypass) this.bypassInvul();
            }

            @Override
            public boolean scalesWithDifficulty() { return false; }
        };
    }

    /**
     * Starts the death scene. turn = true (the player walked into him): the view turns to his face for exactly 3 s, then
     * 2 s of black screen, then the death. turn = false (sanity loss): only the 2 s of black screen, then the death.
     */
    private boolean beginDeath(Player player, boolean turn) {
        return this.beginDeath(player, turn, false);
    }

    /** sudden is kept only for the callers: the black screen before the portrait is NEVER skipped any more. */
    private boolean beginDeath(Player player, boolean turn, boolean sudden) {
        if (this.deathPhase != 0 || !player.isAlive()) return false;
        if (SlenderModes.isGod(player)) return false;     // God Mode: no death scene at all
        this.deathVictim = player.getUUID();
        this.deathSkipBlack = false;                       // the black screen is never skipped any more
        this.blackTicks = DEATH_BLACK_MIN_TICKS + this.random.nextInt(DEATH_BLACK_MAX_TICKS - DEATH_BLACK_MIN_TICKS + 1);
        this.deathPhase = turn ? 1 : 2;
        this.deathTicks = 0;
        this.deathSanityStart = SlenderSanity.get(player);
        // the turn lasts exactly as long as the sanity needs to fall from where it is to 100 at his distance (close: about a second)
        float rate = SlenderSanity.lossPerTick(this.distanceTo(player), FlashlightLink.isOnAndFocused(player));
        int turnTicks = rate > 0.0F ? (int) Math.ceil((SlenderSanity.MAX - this.deathSanityStart) / rate) : DEATH_TURN_TICKS;
        this.entityData.set(DATA_TURN, Mth.clamp(turnTicks, 12, DEATH_TURN_TICKS));
        this.entityData.set(DATA_DEATH, this.deathPhase);
        this.vanished = false;
        this.setInvisible(false);
        if (this.action != ACTION_NONE) this.endAction();
        this.standStill();
        // the player cannot walk away during the scene
        int total = (turn ? DEATH_TURN_TICKS : 0) + DEATH_BLACK_MAX_TICKS + DEATH_PORTRAIT_MAX_TICKS + DEATH_END_TICKS + 20;
        player.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SLOWDOWN, total, 6, false, false, false));
        return true;
    }

    /** God Mode / watcher: the loss is zero all the time. */
    private void calmSanity(Player player) {
        SlenderSanity.set(player, 0.0F);
        this.entityData.set(DATA_SANITY, 0.0F);
        this.sanityRush = false;
        this.recoverRate = 0.0F;
    }

    // ------------------------------------------------------------------ watcher mode

    /**
     * Watcher mode (/slender watch): he stands very far away (50-62 blocks, sometimes up to 80), on hills, trees and
     * roofs, in the view of the player, and only watches. He does not move, nothing is lost. When the player comes
     * closer than 15 blocks he vanishes (a very short static in the camera), stays away for a while and then watches
     * from a new place.
     */
    private void runWatcher(Player player, boolean seen, double dist) {
        this.calmSanity(player);
        this.aggression = 0.0F;
        // -1 = "I am only a watcher": the client mods (roaming sound, camera) read it and stay quiet
        this.entityData.set(DATA_AGGRESSION, -1.0F);
        this.tentGoal = false;
        this.updateTentacles(false);
        if (this.action != ACTION_NONE) this.endAction();
        this.standStill();

        if (this.vanished) {
            if (--this.vanishTicks <= 0) {
                this.vanished = false;
                this.setInvisible(false);
                this.watchPlaced = false;
                this.watchWait = 0;
            }
            return;
        }
        if (!this.watchPlaced) {
            if (--this.watchWait > 0) return;
            if (this.placeWatchSpot(player)) {
                this.watchPlaced = true;
                this.watchRepos = 20 * 40 + this.random.nextInt(20 * 40);   // he changes the place now and then
            } else {
                this.watchWait = 20;
            }
            return;
        }
        if (dist < WATCH_VANISH_DIST) {
            // the player came too close: he is gone, the camera gets a very short static
            this.blipSeq = (this.blipSeq + 1) % 100000;
            this.entityData.set(DATA_BLIP, this.blipSeq);
            this.vanished = true;
            this.vanishTicks = 20 * 6 + this.random.nextInt(20 * 10);
            this.parkFarAway(player);
            this.setInvisible(true);
            return;
        }
        if (dist > WATCH_LOST_DIST || (!seen && --this.watchRepos <= 0)) {
            this.watchPlaced = false;       // the player went far away / a long time unseen: a new place
            this.watchWait = 0;
            return;
        }
        // he just stands and looks at the player
        this.faceToward(player.position(), 6.0F);
        Vec3 eye = player.getEyePosition();
        this.getLookControl().setLookAt(eye.x, eye.y, eye.z, 30.0F, 30.0F);
    }

    /** a far spot (on top of the highest block: hills, treetops, roofs) in front of the player where the player can see him. */
    private boolean placeWatchSpot(Player player) {
        Vec3 eye = player.getEyePosition();
        Vec3 look = player.getLookAngle();
        double base = Math.atan2(look.z, look.x);
        for (int attempt = 0; attempt < 90; attempt++) {
            double distance;
            if (attempt < 60) distance = this.random.nextDouble() < WATCH_FAR_CHANCE ? WATCH_MIN + WATCH_RANDOM + this.random.nextDouble() * 18.0D
                    : WATCH_MIN + this.random.nextDouble() * WATCH_RANDOM;
            else distance = 30.0D + this.random.nextDouble() * 20.0D;       // nothing found: a bit closer
            double angle = attempt < 60 ? base + Math.toRadians((this.random.nextDouble() - 0.5D) * 100.0D)
                    : this.random.nextDouble() * Math.PI * 2.0D;
            double x = player.getX() + Math.cos(angle) * distance;
            double z = player.getZ() + Math.sin(angle) * distance;
            int ix = Mth.floor(x);
            int iz = Mth.floor(z);
            if (!this.level.hasChunkAt(new BlockPos(ix, 64, iz))) continue;
            int top = this.level.getHeight(Heightmap.Types.MOTION_BLOCKING, ix, iz);
            BlockPos pos = new BlockPos(ix, top, iz);
            BlockState floor = this.level.getBlockState(pos.below());
            if (floor.getCollisionShape(this.level, pos.below()).isEmpty()) continue;
            AABB box = new AABB(x - 0.4D, top, z - 0.4D, x + 0.4D, top + 4.0D, z + 0.4D);
            if (!this.level.noCollision(this, box) || this.level.containsAnyLiquid(box)) continue;
            if (attempt < 60 && !this.visibleFrom(eye, x, top, z)) continue;
            float yaw = (float) (Mth.atan2(player.getZ() - z, player.getX() - x) * 57.29577951308232D) - 90.0F;
            this.suppressTeleportCue = true;
            this.relocate(x, top, z, yaw);
            this.suppressTeleportCue = false;
            return true;
        }
        return false;
    }

    private void runDeath() {
        Player victim = this.deathVictim == null ? null : this.level.getPlayerByUUID(this.deathVictim);
        if (victim == null || !victim.isAlive()) {
            this.endDeath(null);
            return;
        }
        if (SlenderModes.isGod(victim)) {      // God Mode was switched on during the scene
            this.endDeath(victim);
            return;
        }
        this.standStill();
        this.faceToward(victim.position(), 60.0F);
        Vec3 eye = victim.getEyePosition();
        this.getLookControl().setLookAt(eye.x, eye.y, eye.z, 60.0F, 60.0F);
        // during the turn the sanity rises evenly to 100 (the camera follows it), afterwards it stays at 100
        float lostNow = SlenderSanity.MAX;
        if (this.deathPhase == 1) {
            lostNow = Mth.lerp(Mth.clamp((float) this.deathTicks / (float) Math.max(1, this.getTurnTicks()), 0.0F, 1.0F),
                    this.deathSanityStart, SlenderSanity.MAX);
        }
        SlenderSanity.set(victim, lostNow);
        this.entityData.set(DATA_SANITY, lostNow);
        int limit = this.deathPhase == 1 ? Math.max(1, this.getTurnTicks())
                : this.deathPhase == 2 ? this.blackTicks
                : this.deathPhase == 3 ? this.portraitTicks : DEATH_END_TICKS;
        if (++this.deathTicks < limit) return;
        if (this.deathPhase < 4) {
            // 1 turn -> 2 black screen -> 3 portrait -> 4 last black screen -> the death
            this.deathPhase++;
            this.deathTicks = 0;
            if (this.deathPhase == 3) this.portraitTicks = DEATH_PORTRAIT_TICKS + this.random.nextInt(DEATH_PORTRAIT_MAX_TICKS - DEATH_PORTRAIT_TICKS + 1);
            this.entityData.set(DATA_DEATH, this.deathPhase);
            return;
        }
        victim.hurt(this.killSource(victim), SANITY_KILL_DAMAGE);   // -10000
        this.endDeath(victim);
        // after the respawn there is no monster anywhere: the next trigger (a page) brings him back
        for (SlendermanEntity other : this.level.getEntitiesOfClass(SlendermanEntity.class, this.getBoundingBox().inflate(1024.0D))) {
            if (other != this) other.discard();
        }
        this.discard();
    }

    private void endDeath(Player victim) {
        this.deathPhase = 0;
        this.deathTicks = 0;
        this.deathVictim = null;
        this.entityData.set(DATA_DEATH, 0);
        this.sanityRush = false;
        this.rushGrace = 10;
        this.damageTicks = 10;
        if (victim != null) {
            SlenderSanity.set(victim, 0.0F);
            this.entityData.set(DATA_SANITY, 0.0F);
            if (victim.isAlive()) victim.removeEffect(MobEffects.MOVEMENT_SLOWDOWN);
        }
    }

    // ------------------------------------------------------------------ teleports

    public enum TeleportKind { FAR, MID, CLOSE, VANISH, AHEAD, HUNT, BEHIND, WATCH, PUNISH, CHASE }

    /** the creeping step: 2 blocks closer along the straight line to the player. */
    private boolean creepCloser(Player player, double maxStep, double keep) {
        double dx = player.getX() - this.getX();
        double dz = player.getZ() - this.getZ();
        double len = Math.sqrt(dx * dx + dz * dz);
        double step = Math.min(maxStep, len - keep);
        if (len < 1.0E-3D || step < 0.5D) return true; // already as close as he gets
        dx /= len;
        dz /= len;
        // straight line first, then slightly to the sides if the spot is blocked
        double[] turns = {0.0D, 0.35D, -0.35D, 0.7D, -0.7D};
        for (double turn : turns) {
            double cos = Math.cos(turn);
            double sin = Math.sin(turn);
            double sx = dx * cos - dz * sin;
            double sz = dx * sin + dz * cos;
            double x = this.getX() + sx * step;
            double z = this.getZ() + sz * step;
            if (this.tooCloseToPlayer(player, x, z)) continue;
            double y = this.findGround(x, z, this.getY(), 4.0D, 2);
            if (Double.isNaN(y)) continue;
            float yaw = (float) (Mth.atan2(player.getZ() - z, player.getX() - x) * 57.29577951308232D) - 90.0F;
            this.relocate(x, y, z, yaw);
            return true;
        }
        return false;
    }

    public boolean teleportNear(Player player, TeleportKind kind) {
        Vec3 look = player.getLookAngle();
        double baseAngle = Math.atan2(look.z, look.x);
        // in front of / behind / next to the player he may also appear in low rooms and structures (4 -> 3 -> 2 blocks of free height)
        boolean tight = kind == TeleportKind.HUNT || kind == TeleportKind.AHEAD || kind == TeleportKind.BEHIND || kind == TeleportKind.CLOSE
                || kind == TeleportKind.MID || kind == TeleportKind.PUNISH || kind == TeleportKind.CHASE;
        int attempts = tight ? 48 : 32;
        boolean indoors = this.playerIndoors(player);
        int planeDy = indoors ? 1 : 3;
        for (int attempt = 0; attempt < attempts; attempt++) {
            double distance, offsetDeg;
            switch (kind) {
                case AHEAD -> {
                    // in front of the player, at the distance of his stage
                    distance = this.aheadDistance();
                    offsetDeg = (this.random.nextDouble() - 0.5D) * 70.0D;
                }
                case HUNT -> {
                    // the player hides: anywhere around them, even in a low room (4 -> 3 -> 2 blocks of free height)
                    distance = this.aheadDistance();
                    offsetDeg = this.random.nextDouble() * 360.0D;
                }
                case PUNISH -> {
                    // the punishment: 3-4 blocks in front of the player (later attempts: any side, a small room may have no spot in front)
                    distance = 3.0D + this.random.nextDouble();
                    offsetDeg = attempt < 24 ? (this.random.nextDouble() - 0.5D) * 50.0D : this.random.nextDouble() * 360.0D;
                }
                case CHASE -> {
                    // the player runs away: he appears behind / beside the runner, at the distance of his stage
                    distance = this.bandDistance();
                    offsetDeg = 180.0D + (this.random.nextDouble() - 0.5D) * 140.0D;
                }
                case FAR -> {
                    distance = this.bandDistance();
                    offsetDeg = (this.random.nextDouble() - 0.5D) * 70.0D;
                }
                case MID -> {
                    distance = this.bandDistance();
                    offsetDeg = (this.random.nextDouble() - 0.5D) * 60.0D;
                }
                case BEHIND -> {
                    // behind the player's back, at the distance of his stage
                    distance = this.aheadDistance();
                    offsetDeg = 180.0D + (this.random.nextDouble() - 0.5D) * 40.0D;
                }
                case WATCH -> {
                    // the watcher: on the horizon in front of the player, at his usual watching distance
                    distance = this.bandDistance();
                    offsetDeg = (this.random.nextDouble() - 0.5D) * 110.0D;
                }
                case CLOSE -> {
                    distance = 2.2D + this.random.nextDouble() * 0.8D;      // 2.2-3.0 blocks, never closer
                    offsetDeg = (this.random.nextDouble() - 0.5D) * 14.0D;
                }
                default -> {
                    distance = 20.0D + this.random.nextDouble() * 15.0D;
                    offsetDeg = (this.random.nextBoolean() ? 1 : -1) * (110.0D + this.random.nextDouble() * 70.0D);
                }
            }
            double angle = baseAngle + Math.toRadians(offsetDeg);
            double x = player.getX() + Math.cos(angle) * distance;
            double z = player.getZ() + Math.sin(angle) * distance;
            if (this.tooCloseToPlayer(player, x, z)) continue;
            double need = tight ? (attempt < 16 ? 4.0D : (attempt < 32 ? 3.0D : 2.0D)) : 4.0D;
            double y = this.findGround(x, z, player.getY(), need, planeDy);
            if (Double.isNaN(y)) continue;
            // the player is inside a structure: he appears INSIDE it, on the same floor (a roof above the spot), not on the roof
            if (indoors && attempt < 36 && !this.hasRoof(x, y + 1.0D, z)) continue;
            float yaw = (float) (Mth.atan2(player.getZ() - z, player.getX() - x) * 57.29577951308232D) - 90.0F;
            this.relocate(x, y, z, yaw);
            return true;
        }
        return false;
    }

    /** the actual jump: he vanishes from the old spot and appears at the new one (the client hides him in between). */
    private void relocate(double x, double y, double z, float yaw) {
        boolean longJump = this.position().distanceTo(new Vec3(x, y, z)) > 4.0D;   // creeping steps are not "teleports"
        this.moveTo(x, y, z, yaw, 0.0F);
        this.ported = true;       // the original's "ported": from now on he may walk
        this.setInvisible(false);
        this.yBodyRot = yaw;
        this.yHeadRot = yaw;
        this.getNavigation().stop();
        this.setDeltaMovement(Vec3.ZERO);
        this.fallDistance = 0.0F;
        this.action = ACTION_NONE;
        this.entityData.set(DATA_ACTION, ACTION_NONE);
        this.entityData.set(DATA_PROGRESS, 0.0F);
        if (TELEPORT_HIDE_TICKS > 0) {
            this.tpHideTicks = TELEPORT_HIDE_TICKS;
            this.entityData.set(DATA_HIDDEN, true);
        }
        this.lastTeleportTick = this.tickCount;
        this.keepChunkLoaded();
        if (longJump && !this.suppressTeleportCue) this.signalTeleport();
    }

    /** tells the camera that he jumped, and whether that happened in the view of the player / in front of him. */
    private void signalTeleport() {
        int flags = 0;
        Player p = this.findTarget();
        if (p != null) {
            double d = this.distanceTo(p);
            if (d < 80.0D && this.isSeenBy(p)) flags |= 1;
            if (d < 30.0D && this.isInFront(p)) flags |= 2;
        }
        this.tpSeq = (this.tpSeq + 1) % 100000;
        this.entityData.set(DATA_TELEPORT, this.tpSeq * 4 + flags);
    }

    /**
     * He does not just turn invisible on the spot any more (that left a phantom entity next to the player that
     * other mods, like slendercam, still reacted to): he is moved far away and stays invisible there.
     */
    private void beginVanish(Player player) {
        this.vanished = true;
        this.vanishTicks = 20 * 15 + this.random.nextInt(20 * 16); // 15-30 sec
        this.vanishCooldown = 20 * 15 + this.random.nextInt(20 * 16);
        this.standStill();
        this.parkFarAway(player);
        this.setInvisible(true);
        this.entityData.set(DATA_ACTION, ACTION_NONE);
        this.entityData.set(DATA_PROGRESS, 0.0F);
    }

    private void parkFarAway(Player player) {
        double[] bands = {PARK_MIN, PARK_MIN * 0.7D, PARK_MIN * 0.45D};
        for (double band : bands) {
            for (int attempt = 0; attempt < 12; attempt++) {
                double angle = this.random.nextDouble() * Math.PI * 2.0D;
                double distance = band + this.random.nextDouble() * 25.0D;
                double x = player.getX() + Math.cos(angle) * distance;
                double z = player.getZ() + Math.sin(angle) * distance;
                double y = this.findGround(x, z, player.getY());
                if (Double.isNaN(y)) continue;
                this.suppressTeleportCue = true;
                this.relocate(x, y, z, this.getYRot());
                this.suppressTeleportCue = false;
                return;
            }
        }
    }

    /** he comes back from the vanish with a teleport (far, mid or critically close in front of the player). */
    private void reappear(Player player) {
        if (!this.teleportNear(player, TeleportKind.FAR)) {
            this.teleportNear(player, TeleportKind.WATCH);
        }
    }

    /** looks for solid ground close to baseY (nearest first), with 4 blocks of free air above it. */
    private double findGround(double x, double z, double baseY) {
        return this.findGround(x, z, baseY, 4.0D);
    }

    private double findGround(double x, double z, double baseY, double height) {
        return this.findGround(x, z, baseY, height, 8);
    }

    /** the same, but only up to maxDy blocks above / below baseY (nearest first): the same floor as the player, not a roof or a floor above. */
    private double findGround(double x, double z, double baseY, double height, int maxDy) {
        int ix = Mth.floor(x);
        int iz = Mth.floor(z);
        int iy = Mth.floor(baseY);
        for (int i = 0; i <= 2 * maxDy; i++) {
            int dy = (i % 2 == 0) ? i / 2 : -((i + 1) / 2);
            BlockPos pos = new BlockPos(ix, iy + dy, iz);
            if (!this.level.hasChunkAt(pos)) continue;
            BlockState floor = this.level.getBlockState(pos.below());
            // he stands on the ground, never on leaves or on tree trunks
            if (floor.is(net.minecraft.tags.BlockTags.LEAVES) || floor.is(net.minecraft.tags.BlockTags.LOGS)) continue;
            if (!floor.getCollisionShape(this.level, pos.below()).isEmpty()) {
                AABB box = new AABB(x - 0.4D, pos.getY(), z - 0.4D, x + 0.4D, pos.getY() + height, z + 0.4D);
                if (this.level.noCollision(this, box) && !this.level.containsAnyLiquid(box)) return pos.getY();
            }
        }
        return Double.NaN;
    }

    /** he is in the half of the world the player is facing (a wide cone, wider than "seen"). */
    private boolean isInFront(Player player) {
        Vec3 look = player.getLookAngle();
        Vec3 flatLook = new Vec3(look.x, 0.0D, look.z);
        Vec3 toMe = new Vec3(this.getX() - player.getX(), 0.0D, this.getZ() - player.getZ());
        if (flatLook.lengthSqr() < 1.0E-4D || toMe.lengthSqr() < 1.0E-4D) return false;
        return flatLook.normalize().dot(toMe.normalize()) > 0.3D;
    }

    private boolean isSeenBy(Player player) {
        return this.isSeenBy(player, SEEN_COS);
    }

    private boolean isSeenBy(Player player, double minCos) {
        Vec3 eye = player.getEyePosition();
        Vec3 view = player.getViewVector(1.0F).normalize();
        for (double h : new double[]{3.6D, 2.6D, 1.4D, 0.4D}) {
            Vec3 p = new Vec3(this.getX(), this.getY() + h, this.getZ());
            Vec3 to = p.subtract(eye);
            double len = to.length();
            if (len < 0.5D) return true;
            if (view.dot(to.scale(1.0D / len)) < minCos) continue;
            BlockHitResult hit = this.level.clip(new ClipContext(eye, p, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, player));
            if (hit.getType() == HitResult.Type.MISS || hit.getLocation().distanceToSqr(p) < 0.25D) return true;
        }
        return false;
    }

    private void faceToward(Vec3 target, float maxStep) {
        double dx = target.x - this.getX();
        double dz = target.z - this.getZ();
        float yaw = (float) (Mth.atan2(dz, dx) * 57.29577951308232D) - 90.0F;
        float newYaw = Mth.approachDegrees(this.getYRot(), yaw, maxStep);
        this.setYRot(newYaw);
        this.yBodyRot = newYaw;
        this.setYHeadRot(newYaw);
    }
    /**
     * Вызывается из SlenderPages, когда ближайший игрок подбирает страницу.
     * Сбрасывает таймеры, чтобы Слендермен быстрее устроил засаду.
     */
    public void onPageTaken(int count) {
        this.stage = Mth.clamp(count, 1, SlenderPages.TOTAL);
        this.ambushTicks = AMBUSH_EVERY[this.stage] - 20;
        this.teleportCheckTicks = Math.min(this.teleportCheckTicks, CHECK_MIN[this.stage] + this.random.nextInt(CHECK_RAND[this.stage] + 1));
        this.watchTicks = 0;
        this.farTicks = 0;
        
    }
}
