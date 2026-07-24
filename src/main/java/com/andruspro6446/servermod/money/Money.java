package com.andruspro6446.servermod.money;

// Balances and trade totals are stored/passed around as integer cents (never as a fractional dollar amount)
// so repeated buy/sell/reversion math can't drift from floating-point rounding. This is the only place that
// converts between that internal cents representation and the dollars-and-cents strings/inputs players see.
public final class Money
{
    private Money()
    {
    }

    public static int toCents(double dollars)
    {
        return (int) Math.round(dollars * 100.0);
    }

    public static String format(int cents)
    {
        int abs = Math.abs(cents);
        return (cents < 0 ? "-$" : "$") + (abs / 100) + "." + String.format("%02d", abs % 100);
    }
}
