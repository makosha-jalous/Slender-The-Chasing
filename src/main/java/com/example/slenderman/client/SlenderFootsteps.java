package com.example.slenderman.client;

import com.example.slenderman.SlendermanMod;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.sound.PlaySoundEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * Our own footsteps instead of the vanilla ones (grass, dirt, gravel, wood, snow ... everything EXCEPT sand, stone and metal).
 *
 * One step = two sounds at once: a random "clothing" + a random "step" (sounds.json: footstep_clothing / footstep_step).
 * Volume: walking 20 %, running 50 %.
 *
 * It does not make its own rhythm: it replaces every vanilla step sound of the player ("block.xxx.step" right at the player),
 * so the pace is the vanilla pace, and in the camera mode - the pace of the camera mod (it plays its own steps through the
 * same sound events). Our handler runs LAST, so when the camera mod has muted the vanilla step we see nothing to replace.
 */
@Mod.EventBusSubscriber(modid = SlendermanMod.ID, value = Dist.CLIENT)
public class SlenderFootsteps {
    /** switch for the whole thing */
    public static final boolean ENABLED = true;
    public static final float WALK_VOLUME = 0.20F;
    public static final float RUN_VOLUME = 0.50F;

    /** the surfaces that keep their vanilla step: sand, stone and metal (and the things that sound like them) */
    private static final String[] KEEP_VANILLA = {
            "sand", "stone", "deepslate", "tuff", "calcite", "basalt", "bricks", "netherrack", "gilded", "ancient_debris",
            "metal", "chain", "copper", "anvil", "lantern", "netherite", "iron", "glass", "amethyst", "bone_block", "coral"
    };

    private static final SoundEvent CLOTHING = new SoundEvent(new ResourceLocation(SlendermanMod.ID, "footstep_clothing"));
    private static final SoundEvent STEP = new SoundEvent(new ResourceLocation(SlendermanMod.ID, "footstep_step"));

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onPlaySound(PlaySoundEvent e) {
        if (!ENABLED) return;
        SoundInstance si = e.getSound();
        if (si == null) return;
        String path = si.getLocation().getPath();
        if (!path.startsWith("block.") || !path.endsWith(".step")) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) return;
        double dx = si.getX() - mc.player.getX(), dy = si.getY() - mc.player.getY(), dz = si.getZ() - mc.player.getZ();
        if (dx * dx + dy * dy + dz * dz > 0.25) return;            // somebody else's step: not ours to replace
        for (String k : KEEP_VANILLA) if (path.contains(k)) return;

        e.setSound(null);                                          // the vanilla step is replaced by the pair
        float vol = mc.player.isSprinting() ? RUN_VOLUME : WALK_VOLUME;
        play(mc, CLOTHING, vol);
        play(mc, STEP, vol);
    }

    private static void play(Minecraft mc, SoundEvent ev, float vol) {
        mc.getSoundManager().play(new SimpleSoundInstance(ev.getLocation(), SoundSource.PLAYERS, vol, 1.0F,
                RandomSource.create(), false, 0, SoundInstance.Attenuation.NONE,
                0.0, 0.0, 0.0, true));   // relative to the listener: (0,0,0) = right in the head, both ears
    }
}
