package com.andruspro6446.servermod.market;

// A single tradeable item's pricing: the admin-set "fair value" and the live, fluctuating trading price.
public class MarketEntry
{
    public double basePrice;
    public double currentPrice;

    public MarketEntry(double basePrice, double currentPrice)
    {
        this.basePrice = basePrice;
        this.currentPrice = currentPrice;
    }
}
