package com.andruspro6446.servermod.web;

import net.minecraft.server.MinecraftServer;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;

// HTTP requests are handled on their own worker threads, but Minecraft's world/player state is only safe to
// touch from the main server thread. This hops any game-state-touching logic over to the main thread and
// blocks the calling HTTP thread until it's done (or times out) - safe because the HTTP thread pool is
// independent of the server's main thread, so there's no risk of deadlocking it.
public final class MainThreadExecutor
{
    private MainThreadExecutor()
    {
    }

    public static <T> T run(MinecraftServer server, Supplier<T> action)
    {
        try
        {
            return server.submit(action::get).get(5, TimeUnit.SECONDS);
        }
        catch (ExecutionException e)
        {
            if (e.getCause() instanceof WebActionException webActionException)
                throw webActionException;
            throw new WebActionException("Server error: " + e.getCause().getMessage());
        }
        catch (InterruptedException e)
        {
            Thread.currentThread().interrupt();
            throw new WebActionException("Interrupted while processing request.");
        }
        catch (TimeoutException e)
        {
            throw new WebActionException("The server took too long to respond.");
        }
    }
}
