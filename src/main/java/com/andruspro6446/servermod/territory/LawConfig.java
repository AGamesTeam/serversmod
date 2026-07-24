package com.andruspro6446.servermod.territory;

import java.util.UUID;

// A country's pre-configured punishment for one CountryLaw - applied automatically and immediately the
// moment a violation is detected, unlike a land claim Report's manually-decided punishment. Any combination
// of fine/jail/quota can be stacked, same as report resolution.
public class LawConfig
{
    public boolean enabled;
    public int fineCents;
    public UUID jailCellId;
    public int jailMinutes;
    public int quotaCents;
}
