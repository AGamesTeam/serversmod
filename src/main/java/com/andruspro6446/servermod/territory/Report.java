package com.andruspro6446.servermod.territory;

import java.util.UUID;

// A land claim owner's complaint that another player violated a REPORTABLE rule on their claim, filed with a
// specific city or country (the reporter's choice, so long as it has jurisdiction - its territory overlaps
// the claim). Reviewed manually by an official (MANAGE_JAILS) of that government; there's no automatic
// detection or punishment for these, unlike the country-wide laws in a later phase.
public class Report
{
    public final UUID id;
    public final UUID reporterId;
    public final UUID offenderId;
    public final UUID landClaimId;
    public final ClaimType governmentType;
    public final UUID governmentId;
    public final ClaimPermission permission;
    public final String reason;
    public final long createdAtMillis;
    public ReportStatus status = ReportStatus.PENDING;

    public Report(UUID id, UUID reporterId, UUID offenderId, UUID landClaimId, ClaimType governmentType,
            UUID governmentId, ClaimPermission permission, String reason)
    {
        this.id = id;
        this.reporterId = reporterId;
        this.offenderId = offenderId;
        this.landClaimId = landClaimId;
        this.governmentType = governmentType;
        this.governmentId = governmentId;
        this.permission = permission;
        this.reason = reason;
        this.createdAtMillis = System.currentTimeMillis();
    }
}
