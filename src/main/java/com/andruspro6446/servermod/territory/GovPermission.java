package com.andruspro6446.servermod.territory;

// Individually grantable permissions for city/country officials. The founder always implicitly has all of
// these; officials only have whatever's explicitly granted to them. Deliberately a flat checklist rather than
// named ranks, so a founder can hand out exactly as much (or little) control as they want.
public enum GovPermission
{
    MANAGE_LAWS,
    MANAGE_JAILS,
    MANAGE_PERMITS,
    MANAGE_RATES,
    MANAGE_OFFICIALS,
    MANAGE_LAND_RULES,
    // Licenses, foreign-tax surcharge, sanctions/alliances, subsidies, festivals, endorsements, audits, and
    // public squares - the government-vs-business commerce levers, kept separate from MANAGE_RATES so a
    // founder can hand out plain rate-setting without also granting these.
    MANAGE_COMMERCE
}
