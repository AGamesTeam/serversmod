package com.andruspro6446.servermod.client;

// Client-side cache of the local player's money balance, kept up to date by MoneySyncPacket.
public class ClientMoneyData
{
    private static int money = 0;

    public static int getMoney()
    {
        return money;
    }

    public static void setMoney(int value)
    {
        money = value;
    }
}
