package com.andruspro6446.servermod.web;

import java.util.UUID;

// A login code that has been sent out (to all ops, or to a specific user) and is waiting to be typed into
// the website. Single-use and expires a few minutes after being issued.
public class PendingLogin
{
    public final Role role;
    public final String code;
    public final UUID playerId;
    public final String playerName;
    public final long expiresAtMillis;
    public int attempts = 0;

    public PendingLogin(Role role, String code, UUID playerId, String playerName, long expiresAtMillis)
    {
        this.role = role;
        this.code = code;
        this.playerId = playerId;
        this.playerName = playerName;
        this.expiresAtMillis = expiresAtMillis;
    }

    public boolean isExpired()
    {
        return System.currentTimeMillis() > expiresAtMillis;
    }
}
