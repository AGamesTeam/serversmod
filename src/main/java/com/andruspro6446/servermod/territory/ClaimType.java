package com.andruspro6446.servermod.territory;

// The three tiers of the territory hierarchy. Each has its own independent set of claims, shovel tiers, and
// molding/merging rules - a land claim overlapping a city doesn't mold around it, only other land claims do.
public enum ClaimType
{
    LAND,
    CITY,
    COUNTRY
}
