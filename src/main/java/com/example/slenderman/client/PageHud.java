package com.example.slenderman.client;

import com.example.slenderman.SlenderPages;
import com.example.slenderman.SlendermanMod;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.gui.overlay.ForgeGui;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * The text "N of 8 Pages Collected" in the lower part of the screen, in the hand-scratched font of the original (Disgusting Behavior),
 * white. It stays for 4 seconds after every pickup (a new pickup restarts it with the new number). It comes in with a light flicker and goes
 * out with a light flicker, as if a weak lamp is being switched off.
 *
 * The font file is NOT in the code: put the .ttf of the font at assets/slenderman/font/disgusting.ttf (the file assets/slenderman/font/pages.json
 * already points to it). Without that file the text is drawn in the normal Minecraft font, nothing breaks.
 */
@Mod.EventBusSubscriber(modid = SlendermanMod.ID, value = Dist.CLIENT)
public class PageHud {
    /** how long the text stays: 80 ticks = 4 seconds. */
    private static final int SHOW_TICKS = 80;
    private static final float FADE_IN_TICKS = 4.0F;
    private static final float FADE_OUT_TICKS = 12.0F;
    /** the BASE LINE of the text (the line the letters stand on) as a part of the screen height: 0 = top edge, 1 = bottom. In the original it is in the lower part. */
    private static final float BASELINE_Y = 0.81F;
    /** how wide the whole text is, as a part of the screen width (in the original about a third). */
    private static final float TEXT_WIDTH = 0.31F;
    /** the flicker: the light one when the text comes in (ticks), and the stronger one while it goes out (ticks). */
    private static final float FLICKER_IN_TICKS = 10.0F;
    private static final float FLICKER_OUT_TICKS = 24.0F;
    /** how often the flicker changes its value (ms). */
    private static final long FLICKER_MS = 45L;

    private static final ResourceLocation FONT_ID = new ResourceLocation(SlendermanMod.ID, "pages");
    private static final ResourceLocation TTF_FILE = new ResourceLocation(SlendermanMod.ID, "font/disgusting.ttf");
    private static final Style STYLE = Style.EMPTY.withFont(FONT_ID);
    private static boolean ttfPresent;

    private static int ticksLeft;
    private static int shownCount;

    /** called when the server says the player collected a page. */
    public static void show(int count) {
        shownCount = count;
        ticksLeft = SHOW_TICKS;
        try {
            ttfPresent = Minecraft.getInstance().getResourceManager().getResource(TTF_FILE).isPresent();
        } catch (Throwable t) {
            ttfPresent = false;
        }
    }

    /** a random brightness 0..1 that changes every FLICKER_MS: mostly bright, with short dips (the bigger the depth, the deeper the dips). */
    private static float flicker(float depth) {
        long step = System.currentTimeMillis() / FLICKER_MS;
        long x = step * 6364136223846793005L + 1442695040888963407L;
        x ^= x >>> 29;
        x *= 0xBF58476D1CE4E5B9L;
        x ^= x >>> 32;
        float r = (x & 0xFFFFFFL) / (float) 0x1000000;
        return 1.0F - depth * r * r;
    }

    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase == TickEvent.Phase.END && ticksLeft > 0 && !Minecraft.getInstance().isPaused()) {
            ticksLeft--;
        }
    }

    /** registered as a gui overlay in PageClientSetup. */
    public static void render(ForgeGui gui, PoseStack poseStack, float partialTick, int width, int height) {
        if (ticksLeft <= 0) return;

        float left = ticksLeft - partialTick;
        float passed = SHOW_TICKS - left;
        float fade = 1.0F;
        if (passed < FADE_IN_TICKS) fade = passed / FADE_IN_TICKS;
        if (left < FADE_OUT_TICKS) fade = Math.min(fade, left / FADE_OUT_TICKS);
        // the flicker: light when it comes in, stronger and stronger while it goes out
        if (passed < FLICKER_IN_TICKS) {
            fade *= flicker(0.10F + 0.50F * (1.0F - passed / FLICKER_IN_TICKS));
        }
        if (left < FLICKER_OUT_TICKS) {
            fade *= flicker(0.30F + 0.50F * (1.0F - left / FLICKER_OUT_TICKS));
        }
        int alpha = (int) (Math.max(0.0F, Math.min(1.0F, fade)) * 255.0F);
        if (alpha < 8) return;   // very low alpha would be drawn fully solid by the font renderer

        Font font = Minecraft.getInstance().font;
        String text = shownCount + " of " + SlenderPages.TOTAL + " Pages Collected";
        Component line = Component.literal(text).withStyle(ttfPresent ? STYLE : Style.EMPTY);
        // the size does not depend on the number: it is measured on the widest text, so the letters do not jump from 1 to 8 pages
        Component ref = Component.literal(SlenderPages.TOTAL + " of " + SlenderPages.TOTAL + " Pages Collected").withStyle(ttfPresent ? STYLE : Style.EMPTY);
        float scale = Math.max(0.5F, TEXT_WIDTH * width / Math.max(1, font.width(ref)));

        poseStack.pushPose();
        // the letters stand on the base line (in the Minecraft font it lies 7 pixels under the top of the line)
        poseStack.translate(width / 2.0F, height * BASELINE_Y, 0.0F);
        poseStack.scale(scale, scale, 1.0F);
        font.draw(poseStack, line, -font.width(line) / 2.0F, -7.0F, (alpha << 24) | 0xFFFFFF);
        poseStack.popPose();
    }
}
