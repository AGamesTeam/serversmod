package com.andruspro6446.servermod.territory;

// Mirrors business.Business.Status - kept as a separate enum (not shared) since businesses and territory are
// otherwise independent systems that happen to bill the same way.
public enum BillingStatus
{
    ACTIVE,
    GRACE,
    SUSPENDED
}
