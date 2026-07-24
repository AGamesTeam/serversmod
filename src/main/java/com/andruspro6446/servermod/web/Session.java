package com.andruspro6446.servermod.web;

import java.util.UUID;

// An established, logged-in browser session, tracked server-side and referenced by a cookie.
public class Session
{
    public final Role role;
    public final UUID playerId;
    public final String playerName;
    public final long expiresAtMillis;

    public Session(Role role, UUID playerId, String playerName, long expiresAtMillis)
    {
        this.role = role;
        this.playerId = playerId;
        this.playerName = playerName;
        this.expiresAtMillis = expiresAtMillis;
    }

    public boolean isExpired()
    {
        return System.currentTimeMillis() > expiresAtMillis;
    }
}
