package com.andruspro6446.servermod.web;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

// Generates and verifies the 20-character one-time login codes, and delivers them via in-game chat -
// the only channel available to reach a player from the web panel. Must be called from the main server
// thread (chat/player-list access isn't thread-safe).
public final class LoginManager
{
    private static final String CODE_ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";
    private static final int CODE_LENGTH = 20;
    private static final long CODE_DURATION_MILLIS = 5 * 60 * 1000;
    private static final int MAX_ATTEMPTS = 5;

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Map<String, PendingLogin> PENDING = new ConcurrentHashMap<>();

    private LoginManager()
    {
    }

    public static String generateCode()
    {
        StringBuilder sb = new StringBuilder(CODE_LENGTH);
        for (int i = 0; i < CODE_LENGTH; i++)
            sb.append(CODE_ALPHABET.charAt(RANDOM.nextInt(CODE_ALPHABET.length())));
        return sb.toString();
    }

    // Sends a login code to every currently online op. Returns the request id, or null if no ops are online.
    public static String requestAdminLogin(MinecraftServer server)
    {
        List<ServerPlayer> ops = server.getPlayerList().getPlayers().stream()
                .filter(p -> server.getPlayerList().isOp(p.getGameProfile()))
                .toList();
        if (ops.isEmpty())
            return null;

        String code = generateCode();
        String requestId = UUID.randomUUID().toString();
        PENDING.put(requestId, new PendingLogin(Role.ADMIN, code, null, null, System.currentTimeMillis() + CODE_DURATION_MILLIS));

        Component message = codeMessage("Web admin panel login code:", code);
        for (ServerPlayer op : ops)
            op.sendSystemMessage(message);

        return requestId;
    }

    // Sends a login code privately to the named player, if they're currently online.
    // Returns the request id, or null if that player isn't online.
    public static String requestUserLogin(MinecraftServer server, String username)
    {
        ServerPlayer player = server.getPlayerList().getPlayerByName(username);
        if (player == null)
            return null;

        String code = generateCode();
        String requestId = UUID.randomUUID().toString();
        PENDING.put(requestId, new PendingLogin(Role.USER, code, player.getUUID(), player.getGameProfile().getName(),
                System.currentTimeMillis() + CODE_DURATION_MILLIS));

        player.sendSystemMessage(codeMessage("Your web panel login code:", code));

        return requestId;
    }

    // Builds a chat message where the code itself is clickable - clicking it copies the code to the
    // clipboard, so it can be pasted straight into the website without having to select the text by hand.
    private static Component codeMessage(String label, String code)
    {
        Style codeStyle = Style.EMPTY
                .withColor(ChatFormatting.AQUA)
                .withBold(true)
                .withUnderlined(true)
                .withClickEvent(new ClickEvent(ClickEvent.Action.COPY_TO_CLIPBOARD, code))
                .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, Component.literal("Click to copy")));

        MutableComponent codeComponent = Component.literal(code).setStyle(codeStyle);

        return Component.literal("[ServerMod] " + label + " ").withStyle(ChatFormatting.GRAY)
                .append(codeComponent)
                .append(Component.literal(" (click to copy - expires in 5 minutes)").withStyle(ChatFormatting.GRAY));
    }

    // Verifies a submitted code. Returns the PendingLogin on success (consuming it - single use), or null if
    // the request id is unknown/expired/over the attempt limit, or the code doesn't match.
    public static PendingLogin verify(String requestId, String code)
    {
        if (requestId == null || code == null)
            return null;

        PendingLogin login = PENDING.get(requestId);
        if (login == null)
            return null;

        if (login.isExpired() || login.attempts >= MAX_ATTEMPTS)
        {
            PENDING.remove(requestId);
            return null;
        }

        if (!constantTimeEquals(login.code, code))
        {
            login.attempts++;
            return null;
        }

        PENDING.remove(requestId);
        return login;
    }

    private static boolean constantTimeEquals(String a, String b)
    {
        return MessageDigest.isEqual(a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
    }
}
