package com.andruspro6446.servermod.territory;

// The concrete, actually-enforceable law catalog. Each is auto-detected and auto-punished (unlike land claim
// reports, which are manually reviewed) using that country's own pre-configured LawConfig for it.
public enum CountryLaw
{
    // Starting a fire (flint & steel, fire spread) anywhere in the country's territory.
    NO_FIRE,
    // Breaking/placing blocks on UNCLAIMED land within the country. Claimed land is already covered by the
    // claim's own BUILD permission (see ClaimPermission) - this only governs the commons/wilderness.
    NO_DESTRUCTION,
    // Periodically checked, not event-driven: being online within the country without a chestplate and
    // leggings equipped.
    CLOTHING_REQUIRED
}
