package com.andruspro6446.servermod.web;

import com.andruspro6446.servermod.Config;
import com.mojang.logging.LogUtils;
import com.sun.net.httpserver.HttpServer;
import net.minecraft.server.MinecraftServer;
import org.slf4j.Logger;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

// Lifecycle for the embedded web admin/user panel. Runs on plain HTTP (no TLS) - by default it only binds to
// localhost; see Config.webServerBindAddress before exposing it beyond this machine.
public final class WebServer
{
    private static final Logger LOGGER = LogUtils.getLogger();

    private static HttpServer httpServer;
    private static ExecutorService executor;

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
