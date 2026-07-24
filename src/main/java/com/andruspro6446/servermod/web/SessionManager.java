package com.andruspro6446.servermod.web;

import java.security.SecureRandom;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

// In-memory session store for logged-in browser sessions. Sessions don't survive a server restart - users
// just log in again, which is fine since a fresh login code is always one chat message away.
public final class SessionManager
{
    private static final long SESSION_DURATION_MILLIS = 24L * 60 * 60 * 1000;
    private static final Map<String, Session> SESSIONS = new ConcurrentHashMap<>();
    private static final SecureRandom RANDOM = new SecureRandom();

    private SessionManager()
    {
    }

    public static String createSession(Role role, UUID playerId, String playerName)
    {
        String token = randomToken();
        SESSIONS.put(token, new Session(role, playerId, playerName, System.currentTimeMillis() + SESSION_DURATION_MILLIS));
        return token;
    }

    public static Session get(String token)
    {
        if (token == null)
            return null;

        Session session = SESSIONS.get(token);
        if (session == null)
            return null;

        if (session.isExpired())
        {
            SESSIONS.remove(token);
            return null;
        }
        return session;
    }

    public static void invalidate(String token)
    {
        if (token != null)
            SESSIONS.remove(token);
    }

    private static String randomToken()
    {
        byte[] bytes = new byte[24];
        RANDOM.nextBytes(bytes);
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes)
            sb.append(String.format("%02x", b));
        return sb.toString();
    }
}
