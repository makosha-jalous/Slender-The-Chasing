package com.example.slenderman.client;

import com.example.slenderman.SlendermanMod;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RegisterClientCommandsEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.loading.FMLPaths;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Which model Slenderman has on THIS computer (only the picture changes, the game logic is the same):
 *   mesh    = the new 3D model (the mesh from the game, edited in Blockbench)
 *   classic = our old model made of cubes
 *
 * Switch in the game with the client command  /slenderskin mesh  or  /slenderskin classic
 * (it works at once, and it is remembered in config/slenderman_skin.txt).
 */
@Mod.EventBusSubscriber(modid = SlendermanMod.ID, value = Dist.CLIENT)
public class SlenderSkin {
    /** the default when there is no saved choice: the new model (this is the test version). */
    private static final boolean DEFAULT_MESH = true;
    private static boolean mesh = DEFAULT_MESH;
    private static boolean loaded = false;

    private static Path file() {
        return FMLPaths.CONFIGDIR.get().resolve("slenderman_skin.txt");
    }

    private static void load() {
        loaded = true;
        try {
            Path f = file();
            if (Files.exists(f)) {
                String s = Files.readString(f, StandardCharsets.UTF_8).trim();
                mesh = !s.equalsIgnoreCase("classic");
            }
        } catch (Exception ignored) {
        }
    }

    private static void save() {
        try {
            Files.writeString(file(), mesh ? "mesh" : "classic", StandardCharsets.UTF_8);
        } catch (Exception ignored) {
        }
    }

    public static boolean useMesh() {
        if (!loaded) load();
        return mesh;
    }

    private static int set(CommandSourceStack source, boolean value) {
        if (!loaded) load();
        mesh = value;
        save();
        source.sendSuccess(Component.literal(value
                ? "Slenderman: the new mesh model"
                : "Slenderman: the old model made of cubes"), false);
        return 1;
    }

    @SubscribeEvent
    public static void commands(RegisterClientCommandsEvent event) {
        LiteralArgumentBuilder<CommandSourceStack> root = Commands.literal("slenderskin")
                .then(Commands.literal("mesh").executes(c -> set(c.getSource(), true)))
                .then(Commands.literal("classic").executes(c -> set(c.getSource(), false)));
        event.getDispatcher().register(root);
    }
}
