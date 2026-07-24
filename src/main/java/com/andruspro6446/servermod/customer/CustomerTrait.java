package com.andruspro6446.servermod.customer;

import java.util.Random;

// Every customer NPC is randomly assigned one of these at spawn, giving each visit a bit of personality:
// how much they're willing to spend, how quickly they get impatient, how much a high price bothers them, and
// how generous/harsh they are in their eventual review. VIP and Influencer are rare "special customer" rolls -
// an Influencer visit that goes well also raises the business's visibility (see BusinessData.runAdvert), as if
// they'd posted about the place afterward.
public enum CustomerTrait
{
    NONE(1.0, 1.0, 1.0, 0.0, false, "", 40),
    FRUGAL(0.5, 1.0, 1.3, 0.0, false, "", 12),
    BIG_SPENDER(2.5, 1.0, 0.7, 0.0, false, "", 12),
    IMPATIENT(1.0, 1.6, 1.0, -0.3, false, "", 10),
    PICKY(1.0, 1.0, 1.4, -0.2, false, "", 10),
    GENEROUS(1.0, 0.8, 0.8, 0.4, false, "", 10),
    VIP(6.0, 0.7, 0.6, 0.2, false, "⭐ ", 4),
    INFLUENCER(5.0, 0.9, 0.8, 0.3, true, "📹 ", 2);

    public final double spendMultiplier;
    public final double impatienceMultiplier;
    public final double priceSensitivityMultiplier;
    public final double reviewLeniencyBonus;
    public final boolean boostsVisibilityOnComplete;
    public final String displayPrefix;
    private final int weight;

    CustomerTrait(double spendMultiplier, double impatienceMultiplier, double priceSensitivityMultiplier,
                  double reviewLeniencyBonus, boolean boostsVisibilityOnComplete, String displayPrefix, int weight)
    {
        this.spendMultiplier = spendMultiplier;
        this.impatienceMultiplier = impatienceMultiplier;
        this.priceSensitivityMultiplier = priceSensitivityMultiplier;
        this.reviewLeniencyBonus = reviewLeniencyBonus;
        this.boostsVisibilityOnComplete = boostsVisibilityOnComplete;
        this.displayPrefix = displayPrefix;
        this.weight = weight;
    }

    private static final int TOTAL_WEIGHT;
    static
    {
        int total = 0;
        for (CustomerTrait trait : values())
            total += trait.weight;
        TOTAL_WEIGHT = total;
    }

    public static CustomerTrait pickRandom(Random random)
    {
        int roll = random.nextInt(TOTAL_WEIGHT);
        for (CustomerTrait trait : values())
        {
            roll -= trait.weight;
            if (roll < 0)
                return trait;
        }
        return NONE;
    }
}
