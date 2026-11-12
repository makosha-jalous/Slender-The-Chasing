package com.example.slenderman;

import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ai.goal.AvoidEntityGoal;
import net.minecraftforge.event.entity.EntityJoinLevelEvent;
import net.minecraftforge.event.entity.living.LivingChangeTargetEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * Almost every mob (the hostile ones and the villagers, also animals) is afraid of Slenderman: close to him it keeps as far away
 * from him as it can (it even runs away) and never attacks him.
 */
@Mod.EventBusSubscriber(modid = SlendermanMod.ID)
public final class SlenderMobFear {
    /** a mob that is closer than this (blocks) to him tries to get away. */
    public static final float FEAR_DISTANCE = 14.0F;
    /** how fast it walks away when he is still far / when he is near (1.0 = its normal speed). */
    public static final double WALK_SPEED = 1.0D, RUN_SPEED = 1.4D;

    private SlenderMobFear() {}

    @SubscribeEvent
    public static void onJoin(EntityJoinLevelEvent event) {
        if (event.getLevel().isClientSide()) return;
        if (event.getEntity() instanceof PathfinderMob mob && !(mob instanceof SlendermanEntity)) {
            mob.goalSelector.addGoal(0, new AvoidEntityGoal<>(mob, SlendermanEntity.class, FEAR_DISTANCE, WALK_SPEED, RUN_SPEED));
        }
    }

    /** nobody targets him (iron golems, wolves ... that would attack anything that looks like a monster). */
    @SubscribeEvent
    public static void onTarget(LivingChangeTargetEvent event) {
        if (event.getNewTarget() instanceof SlendermanEntity) event.setCanceled(true);
    }
}
