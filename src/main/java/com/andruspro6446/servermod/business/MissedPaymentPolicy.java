package com.andruspro6446.servermod.business;

// What happens to a business that can't afford its monthly fee when it comes due. Admin-configurable via
// the /business command or the admin web page.
public enum MissedPaymentPolicy
{
    SUSPEND,
    DISSOLVE,
    GRACE_THEN_SUSPEND
}
