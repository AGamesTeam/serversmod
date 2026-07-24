package com.andruspro6446.servermod.territory;

// What happens when a land claim/city/country can't afford its weekly rent. Configured independently for
// each of the three tiers (land->city, city->country, country->admin) - mirrors business.MissedPaymentPolicy.
public enum MissedPaymentPolicy
{
    SUSPEND,
    DISSOLVE,
    GRACE_THEN_SUSPEND
}
