package com.andruspro6446.servermod.web;

import com.andruspro6446.servermod.Config;
import com.mojang.logging.LogUtils;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import net.minecraft.server.MinecraftServer;
import org.slf4j.Logger;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

// Lifecycle for the embedded web admin/user panel. Runs on plain HTTP (no TLS) - by default it only binds to
// localhost; see Config.webServerBindAddress before exposing it beyond this machine.
public final class WebServer
{
    private static final Logger LOGGER = LogUtils.getLogger();

    private static HttpServer httpServer;
    private static ExecutorService executor;
    // Contexts an addon asked to host on this same server before it was actually running yet - applied the
    // moment start() creates the real HttpServer. Kept even after that so a server restart re-embeds anyone
    // who registered earlier without needing to register again.
    private static final Map<String, HttpHandler> externalContexts = new LinkedHashMap<>();

    private WebServer()
    {
    }

    public static void start(MinecraftServer server)
    {
        if (!Config.webServerEnabled)
        {
            LOGGER.info("ServerMod web panel is disabled in config.");
            return;
        }

        try
        {
            InetSocketAddress address = new InetSocketAddress(Config.webServerBindAddress, Config.webServerPort);
            httpServer = HttpServer.create(address, 0);
            httpServer.createContext("/", new WebHandler(server));
            for (Map.Entry<String, HttpHandler> entry : externalContexts.entrySet())
                httpServer.createContext(entry.getKey(), entry.getValue());
            executor = Executors.newFixedThreadPool(4);
            httpServer.setExecutor(executor);
            httpServer.start();
            LOGGER.info("ServerMod web panel listening on http://{}:{}", Config.webServerBindAddress, Config.webServerPort);
        }
        catch (IOException e)
        {
            LOGGER.error("Failed to start ServerMod web panel", e);
        }
    }

    // Lets another mod host its own pages on this same server/port instead of running a separate one - e.g.
    // the FerryLine mod embeds its booking site under "/ferry" here when ServerMod is present, rather than
    // starting its own standalone server. Safe to call before or after start() - if the server's already
    // running, the context is added immediately; otherwise it's applied the moment it does start.
    public static void registerExternalContext(String path, HttpHandler handler)
    {
        externalContexts.put(path, handler);
        if (httpServer != null)
            httpServer.createContext(path, handler);
    }

    public static void stop()
    {
        if (httpServer != null)
        {
            httpServer.stop(0);
            httpServer = null;
        }
        if (executor != null)
        {
            executor.shutdownNow();
            executor = null;
        }
    }
}
