package com.andruspro6446.servermod.territory;

// How a land claim's owner has configured one ClaimPermission category.
public enum PermissionMode
{
    ALLOWED,
    BLOCKED,
    // Not prevented - the action happens - but flagged so the owner can file a complaint with the city or
    // country for the offender to be reviewed and punished (a later phase; for now this behaves like ALLOWED).
    REPORTABLE
}
