package com.example.slenderman;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.entity.player.Player;

/**
 * The link to the flashlight mod (slenderflashlight), WITHOUT depending on its classes.
 *
 * The flashlight mod tells the server (a packet from the client) in what state the flashlight of the player is, and the server
 * writes it into the player's own data under these keys. Slenderman only reads them here:
 *   state 0 = no lit flashlight in the main hand, 1 = lit, 2 = lit AND focused (the left mouse button is held).
 * The state counts only while it is fresh (the client repeats it every 10 ticks); a stale value means "no flashlight".
 * If the flashlight mod is not installed, the state is always 0 (the original game without a focused flashlight).
 */
public final class FlashlightLink {
    public static final String STATE_KEY = "slenderflashlight_state";
    public static final String TIME_KEY = "slenderflashlight_state_time";
    /** how long (ticks) a received state stays valid. */
    private static final long FRESH_TICKS = 40L;

    public static final int NONE = 0;
    public static final int ON = 1;
    public static final int FOCUSED = 2;

    private FlashlightLink() {}

    public static int state(Player player) {
        CompoundTag data = player.getPersistentData();
        if (!data.contains(STATE_KEY)) return NONE;
        long age = player.level.getGameTime() - data.getLong(TIME_KEY);
        if (age < 0L || age > FRESH_TICKS) return NONE;
        return data.getInt(STATE_KEY);
    }

    /** the lit flashlight is in the main hand. */
    public static boolean isOn(Player player) {
        return state(player) >= ON;
    }

    /** the original flashlight.isOnAndFocused(): lit and concentrated. */
    public static boolean isOnAndFocused(Player player) {
        return state(player) >= FOCUSED;
    }

    /**
     * Turns off every lit flashlight of the player (the finale: she lies, the flashlight is off). We do not depend on the classes of the flashlight mod:
     * first a static method setOn/setLit/... (ItemStack, boolean) of the item class, else the usual boolean tags of the stack.
     */
    public static void turnOffAll(Player player) {
        java.util.List<net.minecraft.world.item.ItemStack> all = new java.util.ArrayList<>();
        all.addAll(player.getInventory().items);
        all.addAll(player.getInventory().offhand);
        for (net.minecraft.world.item.ItemStack stack : all) {
            if (stack.isEmpty() || !stack.getItem().getClass().getSimpleName().contains("Flashlight")) continue;
            boolean done = false;
            for (java.lang.reflect.Method m : stack.getItem().getClass().getMethods()) {
                String n = m.getName();
                Class<?>[] pt = m.getParameterTypes();
                if (!(n.equals("setOn") || n.equals("setLit") || n.equals("setActive") || n.equals("setEnabled") || n.equals("setState"))) continue;
                try {
                    if (java.lang.reflect.Modifier.isStatic(m.getModifiers()) && pt.length == 2
                            && pt[0] == net.minecraft.world.item.ItemStack.class && pt[1] == boolean.class) {
                        m.invoke(null, stack, false);
                        done = true;
                        break;
                    }
                } catch (Throwable ignored) {
                }
            }
            CompoundTag tag = stack.getTag();
            if (tag == null) continue;
            for (String key : new String[]{"on", "On", "ON", "lit", "Lit", "active", "Active", "enabled", "isOn", "turnedOn", "flashlight_on"}) {
                if (tag.contains(key)) {
                    byte type = tag.getTagType(key);
                    if (type == net.minecraft.nbt.Tag.TAG_BYTE) tag.putBoolean(key, false);
                    else if (type == net.minecraft.nbt.Tag.TAG_INT) tag.putInt(key, 0);
                }
            }
        }
    }
}
