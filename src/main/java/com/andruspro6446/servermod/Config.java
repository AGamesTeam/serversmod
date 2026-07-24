package com.andruspro6446.servermod;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraftforge.common.ForgeConfigSpec;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.config.ModConfigEvent;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

// An example config class. This is not required, but it's a good idea to have one to keep your config organized.
// Demonstrates how to use Forge's config APIs
@Mod.EventBusSubscriber(modid = ServerMod.MODID, bus = Mod.EventBusSubscriber.Bus.MOD)
public class Config
{
    private static final ForgeConfigSpec.Builder BUILDER = new ForgeConfigSpec.Builder();

    private static final ForgeConfigSpec.BooleanValue LOG_DIRT_BLOCK = BUILDER
            .comment("Whether to log the dirt block on common setup")
            .define("logDirtBlock", true);

    private static final ForgeConfigSpec.IntValue MAGIC_NUMBER = BUILDER
            .comment("A magic number")
            .defineInRange("magicNumber", 42, 0, Integer.MAX_VALUE);

    public static final ForgeConfigSpec.ConfigValue<String> MAGIC_NUMBER_INTRODUCTION = BUILDER
            .comment("What you want the introduction message to be for the magic number")
            .define("magicNumberIntroduction", "The magic number is... ");

    // a list of strings that are treated as resource locations for items
    private static final ForgeConfigSpec.ConfigValue<List<? extends String>> ITEM_STRINGS = BUILDER
            .comment("A list of items to log on common setup.")
            .defineListAllowEmpty("items", List.of("minecraft:iron_ingot"), Config::validateItemName);

    // a list of "item=price" entries used to seed the trader market the first time it is created.
    // Once the world exists, base prices are managed at runtime from the admin web panel instead.
    private static final ForgeConfigSpec.ConfigValue<List<? extends String>> MARKET_SEED_STRINGS = BUILDER
            .comment("Items tradeable at the Trader Block / web panel and their starting base price.",
                    "Only used to seed a brand new world; afterwards prices are managed from the admin web panel.",
                    "Format: \"<item resource location>=<price>\", e.g. \"minecraft:diamond=100\"")
            .defineList("marketSeedPrices", List.of(
                    "minecraft:diamond=100",
                    "minecraft:emerald=50",
                    "minecraft:gold_ingot=25",
                    "minecraft:iron_ingot=10",
                    "minecraft:coal=2"
            ), Config::validateSellPriceEntry);

    private static final ForgeConfigSpec.BooleanValue WEB_SERVER_ENABLED = BUILDER
            .comment("Whether to start the web admin/user panel when the server starts.")
            .define("webServerEnabled", true);

    private static final ForgeConfigSpec.IntValue WEB_SERVER_PORT = BUILDER
            .comment("The TCP port the web panel listens on.")
            .defineInRange("webServerPort", 8080, 1, 65535);

    private static final ForgeConfigSpec.ConfigValue<String> WEB_SERVER_BIND_ADDRESS = BUILDER
            .comment("The address the web panel binds to.",
                    "\"127.0.0.1\" (default) only allows connections from this machine.",
                    "Use \"0.0.0.0\" to allow connections from other devices on your network/internet.",
                    "WARNING: the panel is plain HTTP (not HTTPS). Only expose it beyond localhost on a trusted network,",
                    "or behind a TLS-terminating reverse proxy.")
            .define("webServerBindAddress", "127.0.0.1");

    private static final ForgeConfigSpec.DoubleValue MARKET_IMPACT_RATE = BUILDER
            .comment("How much a single buy/sell transaction moves an item's current price, per unit traded.")
            .defineInRange("marketImpactRate", 0.02, 0.0, 1.0);

    private static final ForgeConfigSpec.DoubleValue MARKET_REVERSION_RATE = BUILDER
            .comment("How far current prices drift back toward the base price on each reversion tick (0-1, fraction of the gap closed).")
            .defineInRange("marketReversionRate", 0.05, 0.0, 1.0);

    private static final ForgeConfigSpec.IntValue MARKET_REVERSION_INTERVAL_TICKS = BUILDER
            .comment("How often (in server ticks, 20 per second) prices drift back toward their base price.")
            .defineInRange("marketReversionIntervalTicks", 6000, 20, Integer.MAX_VALUE);

    private static final ForgeConfigSpec.DoubleValue MARKET_MIN_MULTIPLIER = BUILDER
            .comment("The current price can never fall below this fraction of the base price.")
            .defineInRange("marketMinMultiplier", 0.1, 0.01, 1.0);

    private static final ForgeConfigSpec.DoubleValue MARKET_MAX_MULTIPLIER = BUILDER
            .comment("The current price can never rise above this multiple of the base price.")
            .defineInRange("marketMaxMultiplier", 10.0, 1.0, 1000.0);

    private static final ForgeConfigSpec.IntValue CUSTOMER_SERVICE_TIMEOUT_TICKS = BUILDER
            .comment("How long (in ticks, 20 per second) a customer NPC waits at the front of the queue before giving up and leaving a bad review.")
            .defineInRange("customerServiceTimeoutTicks", 6000, 20, Integer.MAX_VALUE); // 5 minutes

    private static final ForgeConfigSpec.IntValue CUSTOMER_QUEUE_LINE_CAPACITY = BUILDER
            .comment("How many customer NPCs can be waiting at once, per queue point.")
            .defineInRange("customerQueueLineCapacity", 6, 1, 64);

    private static final ForgeConfigSpec.IntValue REVIEW_RECENCY_WINDOW_HOURS = BUILDER
            .comment("Only reviews left within this many hours count toward a business's spawn-rate/basket-size review score.")
            .defineInRange("reviewRecencyWindowHours", 72, 1, 24 * 365);

    private static final ForgeConfigSpec.DoubleValue CUSTOMER_MIN_RATE_MULTIPLIER = BUILDER
            .comment("The spawn-chance/basket-size multiplier a business's recent reviews can drag it down to (at 0.0 stars).")
            .defineInRange("customerMinRateMultiplier", 0.4, 0.05, 1.0);

    private static final ForgeConfigSpec.DoubleValue CUSTOMER_MAX_RATE_MULTIPLIER = BUILDER
            .comment("The spawn-chance/basket-size multiplier a business's recent reviews can boost it up to (at 5.0 stars).")
            .defineInRange("customerMaxRateMultiplier", 1.6, 1.0, 5.0);

    static final ForgeConfigSpec SPEC = BUILDER.build();

    public static boolean logDirtBlock;
    public static int magicNumber;
    public static String magicNumberIntroduction;
    public static Set<Item> items;
    public static Map<ResourceLocation, Integer> marketSeedPrices;
    public static boolean webServerEnabled;
    public static int webServerPort;
    public static String webServerBindAddress;
    public static double marketImpactRate;
    public static double marketReversionRate;
    public static int marketReversionIntervalTicks;
    public static double marketMinMultiplier;
    public static double marketMaxMultiplier;
    public static int customerServiceTimeoutTicks;
    public static int customerQueueLineCapacity;
    public static int reviewRecencyWindowHours;
    public static double customerMinRateMultiplier;
    public static double customerMaxRateMultiplier;

    private static boolean validateItemName(final Object obj)
    {
        return obj instanceof final String itemName && ForgeRegistries.ITEMS.containsKey(new ResourceLocation(itemName));
    }

    private static boolean validateSellPriceEntry(final Object obj)
    {
        if (!(obj instanceof final String entry))
            return false;

        String[] parts = entry.split("=", 2);
        if (parts.length != 2)
            return false;

        try
        {
            Integer.parseInt(parts[1].trim());
        }
        catch (NumberFormatException e)
        {
            return false;
        }

        return ForgeRegistries.ITEMS.containsKey(new ResourceLocation(parts[0].trim()));
    }

    @SubscribeEvent
    static void onLoad(final ModConfigEvent event)
    {
        logDirtBlock = LOG_DIRT_BLOCK.get();
        magicNumber = MAGIC_NUMBER.get();
        magicNumberIntroduction = MAGIC_NUMBER_INTRODUCTION.get();

        // convert the list of strings into a set of items
        items = ITEM_STRINGS.get().stream()
                .map(itemName -> ForgeRegistries.ITEMS.getValue(new ResourceLocation(itemName)))
                .collect(Collectors.toSet());

        // convert the list of "item=price" strings into a lookup map
        Map<ResourceLocation, Integer> parsedSeedPrices = new HashMap<>();
        for (String entry : MARKET_SEED_STRINGS.get())
        {
            String[] parts = entry.split("=", 2);
            ResourceLocation id = new ResourceLocation(parts[0].trim());
            int price = Integer.parseInt(parts[1].trim());
            if (ForgeRegistries.ITEMS.containsKey(id))
                parsedSeedPrices.put(id, price);
        }
        marketSeedPrices = parsedSeedPrices;

        webServerEnabled = WEB_SERVER_ENABLED.get();
        webServerPort = WEB_SERVER_PORT.get();
        webServerBindAddress = WEB_SERVER_BIND_ADDRESS.get();
        marketImpactRate = MARKET_IMPACT_RATE.get();
        marketReversionRate = MARKET_REVERSION_RATE.get();
        marketReversionIntervalTicks = MARKET_REVERSION_INTERVAL_TICKS.get();
        marketMinMultiplier = MARKET_MIN_MULTIPLIER.get();
        marketMaxMultiplier = MARKET_MAX_MULTIPLIER.get();
        customerServiceTimeoutTicks = CUSTOMER_SERVICE_TIMEOUT_TICKS.get();
        customerQueueLineCapacity = CUSTOMER_QUEUE_LINE_CAPACITY.get();
        reviewRecencyWindowHours = REVIEW_RECENCY_WINDOW_HOURS.get();
        customerMinRateMultiplier = CUSTOMER_MIN_RATE_MULTIPLIER.get();
        customerMaxRateMultiplier = CUSTOMER_MAX_RATE_MULTIPLIER.get();
    }
}
