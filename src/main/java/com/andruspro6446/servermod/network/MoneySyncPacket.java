package com.andruspro6446.servermod.network;

import com.andruspro6446.servermod.client.ClientMoneyData;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

// Sent from the server to a single client to tell it what its current money balance is.
public class MoneySyncPacket
{
    private final int money;

    public MoneySyncPacket(int money)
    {
        this.money = money;
    }

    public static void encode(MoneySyncPacket packet, FriendlyByteBuf buf)
    {
        buf.writeInt(packet.money);
    }

    public static MoneySyncPacket decode(FriendlyByteBuf buf)
    {
        return new MoneySyncPacket(buf.readInt());
    }

    public static void handle(MoneySyncPacket packet, Supplier<NetworkEvent.Context> contextSupplier)
    {
        NetworkEvent.Context context = contextSupplier.get();
        context.enqueueWork(() -> ClientMoneyData.setMoney(packet.money));
        context.setPacketHandled(true);
    }
}
