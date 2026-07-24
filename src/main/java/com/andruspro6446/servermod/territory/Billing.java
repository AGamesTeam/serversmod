package com.andruspro6446.servermod.territory;

// Weekly billing state shared by LandClaim, City, and Country - held as a field rather than via inheritance,
// since a LandClaim isn't a Government but still needs the exact same rent bookkeeping.
public class Billing
{
    public BillingStatus status = BillingStatus.ACTIVE;
    public long nextBillingAtMillis;
    public long graceDeadlineMillis;

    public Billing(long nextBillingAtMillis)
    {
        this.nextBillingAtMillis = nextBillingAtMillis;
    }
}
