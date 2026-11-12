package com.example.slenderman;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/** server -> client: the stage of the finale (TYPE_STAGE: a = stage, b = id of his entity) or one flash of a teleport (TYPE_FLASH). */
public class SlenderFinaleMessage {
    public static final int TYPE_STAGE = 1;
    public static final int TYPE_FLASH = 2;

    private final int type, a, b;

    public SlenderFinaleMessage(int type, int a, int b) {
        this.type = type;
        this.a = a;
        this.b = b;
    }

    public static void encode(SlenderFinaleMessage m, FriendlyByteBuf buf) {
        buf.writeVarInt(m.type);
        buf.writeVarInt(m.a);
        buf.writeVarInt(m.b);
    }

    public static SlenderFinaleMessage decode(FriendlyByteBuf buf) {
        return new SlenderFinaleMessage(buf.readVarInt(), buf.readVarInt(), buf.readVarInt());
    }

    public static void handle(SlenderFinaleMessage m, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> {
            if (m.type == TYPE_STAGE) com.example.slenderman.client.FinaleClient.onStage(m.a, m.b);
            else com.example.slenderman.client.FinaleClient.onFlash();
        }));
        ctx.get().setPacketHandled(true);
    }
}
