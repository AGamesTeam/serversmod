package com.andruspro6446.servermod.network;

import com.andruspro6446.servermod.ServerMod;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.PacketDistributor;
import net.minecraftforge.network.simple.SimpleChannel;

public class NetworkHandler
{
    private static final String PROTOCOL_VERSION = "1";

    public static final SimpleChannel CHANNEL = NetworkRegistry.newSimpleChannel(
            new ResourceLocation(ServerMod.MODID, "main"),
            () -> PROTOCOL_VERSION,
            PROTOCOL_VERSION::equals,
            PROTOCOL_VERSION::equals
    );

    public static void register()
    {
        CHANNEL.registerMessage(0, MoneySyncPacket.class, MoneySyncPacket::encode, MoneySyncPacket::decode, MoneySyncPacket::handle);
    }

    public static void sendMoneyUpdate(ServerPlayer player, int money)
    {
        CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), new MoneySyncPacket(money));
    }
}
