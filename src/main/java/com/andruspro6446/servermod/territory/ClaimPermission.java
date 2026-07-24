package com.andruspro6446.servermod.territory;

// The categories of interaction a land claim's owner can independently lock down. CONTAINER_TAKE is tracked
// (and shown/settable) but not yet hard-enforced - doing so properly needs a custom container menu that
// blocks outbound slot transfers while still allowing the container to be opened, which is a bigger, more
// isolated change than the rest of this phase; it's set up now for the "stealing" law in a later phase.
public enum ClaimPermission
{
    BUILD,
    CONTAINER_OPEN,
    CONTAINER_TAKE,
    INTERACT,
    PVP,
    MOB_DAMAGE
}
