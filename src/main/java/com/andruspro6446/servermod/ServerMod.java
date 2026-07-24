package com.andruspro6446.servermod;

import com.andruspro6446.servermod.block.AutoSellerBlock;
import com.andruspro6446.servermod.block.AutoSellerBlockEntity;
import com.andruspro6446.servermod.block.BusinessSignBlock;
import com.andruspro6446.servermod.block.BusinessSignBlockEntity;
import com.andruspro6446.servermod.block.SellBarrelBlock;
import com.andruspro6446.servermod.block.SellBarrelBlockEntity;
import com.andruspro6446.servermod.block.TraderBlock;
import com.andruspro6446.servermod.business.BusinessData;
import com.andruspro6446.servermod.client.CustomerNpcRenderer;
import com.andruspro6446.servermod.client.EmployeeNpcRenderer;
import com.andruspro6446.servermod.client.MoneyOverlay;
import com.andruspro6446.servermod.command.ModCommands;
import com.andruspro6446.servermod.command.TerritoryCommands;
import com.andruspro6446.servermod.customer.CustomerNpc;
import com.andruspro6446.servermod.customer.CustomerNpcManager;
import com.andruspro6446.servermod.customer.EmployeeNpc;
import com.andruspro6446.servermod.market.MailboxData;
import com.andruspro6446.servermod.market.MarketData;
import com.andruspro6446.servermod.money.MoneyData;
import com.andruspro6446.servermod.network.NetworkHandler;
import com.andruspro6446.servermod.territory.ClaimShovelItem;
import com.andruspro6446.servermod.territory.ClaimType;
import com.andruspro6446.servermod.territory.JailListener;
import com.andruspro6446.servermod.territory.LawListener;
import com.andruspro6446.servermod.territory.TerritoryData;
import com.andruspro6446.servermod.web.WebServer;
import com.mojang.logging.LogUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.food.FoodProperties;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.material.MapColor;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.EntityRenderersEvent;
import net.minecraftforge.client.event.RegisterGuiOverlaysEvent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.BuildCreativeModeTabContentsEvent;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.EntityAttributeCreationEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.event.server.ServerStartingEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.config.ModConfig;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;
import org.slf4j.Logger;

// The value here should match an entry in the META-INF/mods.toml file
@Mod(ServerMod.MODID)
public class ServerMod
{
    // Define mod id in a common place for everything to reference
    public static final String MODID = "servermod";
    // Directly reference a slf4j logger
    private static final Logger LOGGER = LogUtils.getLogger();
    // Create a Deferred Register to hold Blocks which will all be registered under the "servermod" namespace
    public static final DeferredRegister<Block> BLOCKS = DeferredRegister.create(ForgeRegistries.BLOCKS, MODID);
    // Create a Deferred Register to hold Items which will all be registered under the "servermod" namespace
    public static final DeferredRegister<Item> ITEMS = DeferredRegister.create(ForgeRegistries.ITEMS, MODID);
    // Create a Deferred Register to hold CreativeModeTabs which will all be registered under the "servermod" namespace
    public static final DeferredRegister<CreativeModeTab> CREATIVE_MODE_TABS = DeferredRegister.create(Registries.CREATIVE_MODE_TAB, MODID);
    // Create a Deferred Register to hold BlockEntityTypes which will all be registered under the "servermod" namespace
    public static final DeferredRegister<BlockEntityType<?>> BLOCK_ENTITIES = DeferredRegister.create(ForgeRegistries.BLOCK_ENTITY_TYPES, MODID);
    // Create a Deferred Register to hold EntityTypes which will all be registered under the "servermod" namespace
    public static final DeferredRegister<EntityType<?>> ENTITY_TYPES = DeferredRegister.create(ForgeRegistries.ENTITY_TYPES, MODID);

    // Creates a new Block with the id "servermod:example_block", combining the namespace and path
    public static final RegistryObject<Block> EXAMPLE_BLOCK = BLOCKS.register("example_block", () -> new Block(BlockBehaviour.Properties.of().mapColor(MapColor.STONE)));
    // Creates a new BlockItem with the id "servermod:example_block", combining the namespace and path
    public static final RegistryObject<Item> EXAMPLE_BLOCK_ITEM = ITEMS.register("example_block", () -> new BlockItem(EXAMPLE_BLOCK.get(), new Item.Properties()));

    // Creates a new food item with the id "servermod:example_id", nutrition 1 and saturation 2
    public static final RegistryObject<Item> EXAMPLE_ITEM = ITEMS.register("example_item", () -> new Item(new Item.Properties().food(new FoodProperties.Builder()
            .alwaysEat().nutrition(1).saturationMod(2f).build())));

    // Creates the Trader Block: right-click to sell the held item at the current live market price
    // (see MarketData / the admin web panel)
    public static final RegistryObject<Block> TRADER_BLOCK = BLOCKS.register("trader_block", () -> new TraderBlock(BlockBehaviour.Properties.of().mapColor(MapColor.GOLD).strength(3.5f).requiresCorrectToolForDrops()));
    public static final RegistryObject<Item> TRADER_BLOCK_ITEM = ITEMS.register("trader_block", () -> new BlockItem(TRADER_BLOCK.get(), new Item.Properties()));

    // Creates the Auto-Seller: hopper items into it and it sells them automatically at the current market
    // price, paying whoever placed it
    public static final RegistryObject<Block> AUTO_SELLER_BLOCK = BLOCKS.register("auto_seller", () -> new AutoSellerBlock(BlockBehaviour.Properties.of().mapColor(MapColor.METAL).strength(3.5f).requiresCorrectToolForDrops()));
    public static final RegistryObject<Item> AUTO_SELLER_BLOCK_ITEM = ITEMS.register("auto_seller", () -> new BlockItem(AUTO_SELLER_BLOCK.get(), new Item.Properties()));
    public static final RegistryObject<BlockEntityType<AutoSellerBlockEntity>> AUTO_SELLER_BLOCK_ENTITY = BLOCK_ENTITIES.register("auto_seller",
            () -> BlockEntityType.Builder.of(AutoSellerBlockEntity::new, AUTO_SELLER_BLOCK.get()).build(null));

    // Creates the Sell Barrel: a real, physical 27-slot container a business places and stocks (by hand or
    // hopper) to make items sellable on its web storefront - see business.BusinessData / web.BusinessHandler.
    public static final RegistryObject<Block> SELL_BARREL_BLOCK = BLOCKS.register("sell_barrel", () -> new SellBarrelBlock(BlockBehaviour.Properties.of().mapColor(MapColor.WOOD).strength(2.5f)));
    public static final RegistryObject<Item> SELL_BARREL_BLOCK_ITEM = ITEMS.register("sell_barrel", () -> new BlockItem(SELL_BARREL_BLOCK.get(), new Item.Properties()));
    public static final RegistryObject<BlockEntityType<SellBarrelBlockEntity>> SELL_BARREL_BLOCK_ENTITY = BLOCK_ENTITIES.register("sell_barrel",
            () -> BlockEntityType.Builder.of(SellBarrelBlockEntity::new, SELL_BARREL_BLOCK.get()).build(null));

    // Creates the Business Sign: every business gets one on registration, showing customers its hours and
    // current open/closed status - see block.BusinessSignBlock / business.BusinessData.
    public static final RegistryObject<Block> BUSINESS_SIGN_BLOCK = BLOCKS.register("business_sign", () -> new BusinessSignBlock(BlockBehaviour.Properties.of().mapColor(MapColor.WOOD).strength(2.0f)));
    public static final RegistryObject<Item> BUSINESS_SIGN_BLOCK_ITEM = ITEMS.register("business_sign", () -> new BlockItem(BUSINESS_SIGN_BLOCK.get(), new Item.Properties()));
    public static final RegistryObject<BlockEntityType<BusinessSignBlockEntity>> BUSINESS_SIGN_BLOCK_ENTITY = BLOCK_ENTITIES.register("business_sign",
            () -> BlockEntityType.Builder.of(BusinessSignBlockEntity::new, BUSINESS_SIGN_BLOCK.get()).build(null));

    // Physical customer NPCs: spawn near a listed business, walk to and queue at an owner-designated queue
    // point, and wait to be served - see customer.CustomerNpc / customer.CustomerNpcManager.
    public static final RegistryObject<EntityType<CustomerNpc>> CUSTOMER_NPC = ENTITY_TYPES.register("customer_npc",
            () -> EntityType.Builder.of(CustomerNpc::new, MobCategory.MISC)
                    .sized(0.6F, 1.95F)
                    .clientTrackingRange(10)
                    .build("customer_npc"));

    // A purely cosmetic stand-in for a hired employee, standing at the counter - see customer.EmployeeNpc /
    // customer.CustomerNpcManager.tickEmployeeVisuals.
    public static final RegistryObject<EntityType<EmployeeNpc>> EMPLOYEE_NPC = ENTITY_TYPES.register("employee_npc",
            () -> EntityType.Builder.of(EmployeeNpc::new, MobCategory.MISC)
                    .sized(0.6F, 1.95F)
                    .clientTrackingRange(10)
                    .build("employee_npc"));

    // Claim shovels: right-click two corners to claim land/found a city/found a country - see
    // territory.TerritoryData / territory.ClaimShovelItem.
    public static final RegistryObject<Item> LAND_CLAIM_SHOVEL = ITEMS.register("land_claim_shovel",
            () -> new ClaimShovelItem(ClaimType.LAND, new Item.Properties().stacksTo(1)));
    public static final RegistryObject<Item> CITY_CLAIM_SHOVEL = ITEMS.register("city_claim_shovel",
            () -> new ClaimShovelItem(ClaimType.CITY, new Item.Properties().stacksTo(1)));
    public static final RegistryObject<Item> COUNTRY_CLAIM_SHOVEL = ITEMS.register("country_claim_shovel",
            () -> new ClaimShovelItem(ClaimType.COUNTRY, new Item.Properties().stacksTo(1)));

    // Creates a creative tab with the id "servermod:example_tab" for the example item, that is placed after the combat tab
    public static final RegistryObject<CreativeModeTab> EXAMPLE_TAB = CREATIVE_MODE_TABS.register("example_tab", () -> CreativeModeTab.builder()
            .withTabsBefore(CreativeModeTabs.COMBAT)
            .icon(() -> EXAMPLE_ITEM.get().getDefaultInstance())
            .displayItems((parameters, output) -> {
                output.accept(EXAMPLE_ITEM.get()); // Add the example item to the tab. For your own tabs, this method is preferred over the event
                output.accept(TRADER_BLOCK_ITEM.get());
                output.accept(AUTO_SELLER_BLOCK_ITEM.get());
                output.accept(SELL_BARREL_BLOCK_ITEM.get());
                output.accept(BUSINESS_SIGN_BLOCK_ITEM.get());
                output.accept(LAND_CLAIM_SHOVEL.get());
                output.accept(CITY_CLAIM_SHOVEL.get());
                output.accept(COUNTRY_CLAIM_SHOVEL.get());
            }).build());

    // Set while the server is running, so the periodic market-reversion and business-billing ticks have
    // something to act on.
    private static MinecraftServer serverInstance;
    private int reversionTickCounter = 0;
    private int businessBillingTickCounter = 0;
    private int customerSpawnTickCounter = 0;
    private int customerQueueTickCounter = 0;
    private int wandererSpawnTickCounter = 0;
    private int territoryBillingTickCounter = 0;
    private int jailTickCounter = 0;
    private int borderTickCounter = 0;
    private int clothingTickCounter = 0;
    private static final int BUSINESS_BILLING_INTERVAL_TICKS = 6000; // 5 minutes; billing itself is real-time-based
    private static final int JAIL_TICK_INTERVAL_TICKS = 20; // 1 second - confinement/release should feel responsive
    private static final int BORDER_TICK_INTERVAL_TICKS = 20; // 1 second - border prompts should feel responsive
    private static final int CLOTHING_TICK_INTERVAL_TICKS = 1200; // 1 minute - a periodic citation, not a per-tick check
    private static final int CUSTOMER_QUEUE_TICK_INTERVAL_TICKS = 20; // 1 second - queue order/timeouts should feel responsive

    public ServerMod(FMLJavaModLoadingContext context)
    {
        IEventBus modEventBus = context.getModEventBus();

        // Register the commonSetup method for modloading
        modEventBus.addListener(this::commonSetup);

        // Register the Deferred Register to the mod event bus so blocks get registered
        BLOCKS.register(modEventBus);
        // Register the Deferred Register to the mod event bus so items get registered
        ITEMS.register(modEventBus);
        // Register the Deferred Register to the mod event bus so tabs get registered
        CREATIVE_MODE_TABS.register(modEventBus);
        // Register the Deferred Register to the mod event bus so block entity types get registered
        BLOCK_ENTITIES.register(modEventBus);
        // Register the Deferred Register to the mod event bus so entity types get registered
        ENTITY_TYPES.register(modEventBus);

        modEventBus.addListener(this::registerAttributes);

        // Register ourselves for server and other game events we are interested in
        MinecraftForge.EVENT_BUS.register(this);

        // Register the item to a creative tab
        modEventBus.addListener(this::addCreative);

        // Register our mod's ForgeConfigSpec so that Forge can create and load the config file for us
        context.registerConfig(ModConfig.Type.COMMON, Config.SPEC);

        // Register the network channel used to sync money balances to clients
        NetworkHandler.register();
    }

    private void commonSetup(final FMLCommonSetupEvent event)
    {
        // Some common setup code
        LOGGER.info("HELLO FROM COMMON SETUP");

        if (Config.logDirtBlock)
            LOGGER.info("DIRT BLOCK >> {}", ForgeRegistries.BLOCKS.getKey(Blocks.DIRT));

        LOGGER.info(Config.magicNumberIntroduction + Config.magicNumber);

        Config.items.forEach((item) -> LOGGER.info("ITEM >> {}", item.toString()));
    }

    private void registerAttributes(final EntityAttributeCreationEvent event)
    {
        event.put(CUSTOMER_NPC.get(), CustomerNpc.createAttributes().build());
        event.put(EMPLOYEE_NPC.get(), EmployeeNpc.createAttributes().build());
    }

    // Add the example block item and the trader block to the building blocks tab
    private void addCreative(BuildCreativeModeTabContentsEvent event)
    {
        if (event.getTabKey() == CreativeModeTabs.BUILDING_BLOCKS)
        {
            event.accept(EXAMPLE_BLOCK_ITEM);
            event.accept(TRADER_BLOCK_ITEM);
            event.accept(AUTO_SELLER_BLOCK_ITEM);
            event.accept(SELL_BARREL_BLOCK_ITEM);
            event.accept(BUSINESS_SIGN_BLOCK_ITEM);
        }
    }

    @SubscribeEvent
    public void onServerStarting(ServerStartingEvent event)
    {
        LOGGER.info("HELLO from server starting");
    }

    // Seed the market with the config's starting prices (only for items not already tracked) and start the
    // web panel once the world/server is fully up.
    @SubscribeEvent
    public void onServerStarted(ServerStartedEvent event)
    {
        serverInstance = event.getServer();
        MarketData marketData = MarketData.get(serverInstance);
        Config.marketSeedPrices.forEach(marketData::seedIfAbsent);
        WebServer.start(serverInstance);
    }

    @SubscribeEvent
    public void onServerStopping(ServerStoppingEvent event)
    {
        WebServer.stop();
        serverInstance = null;
    }

    @SubscribeEvent
    public void onRegisterCommands(RegisterCommandsEvent event)
    {
        ModCommands.register(event.getDispatcher());
        TerritoryCommands.register(event.getDispatcher());
    }

    // Slowly drifts every item's live price back toward its base price, so the market settles over time
    // instead of only ever moving in whatever direction the last few trades pushed it.
    @SubscribeEvent
    public void onServerTick(TickEvent.ServerTickEvent event)
    {
        if (event.phase != TickEvent.Phase.END || serverInstance == null)
            return;

        reversionTickCounter++;
        if (reversionTickCounter >= Config.marketReversionIntervalTicks)
        {
            reversionTickCounter = 0;
            MarketData.get(serverInstance).tickReversion();
        }

        businessBillingTickCounter++;
        if (businessBillingTickCounter >= BUSINESS_BILLING_INTERVAL_TICKS)
        {
            businessBillingTickCounter = 0;
            BusinessData.get(serverInstance).tickBilling(serverInstance);
        }

        // Admin-configurable and off by default; the interval can change at runtime, so it's re-read from
        // BusinessData every check rather than cached once.
        BusinessData businessData = BusinessData.get(serverInstance);
        if (businessData.physicalNpcEnabled())
        {
            customerSpawnTickCounter++;
            int intervalTicks = Math.max(20, businessData.npcSpawnIntervalMinutes() * 60 * 20);
            if (customerSpawnTickCounter >= intervalTicks)
            {
                customerSpawnTickCounter = 0;
                CustomerNpcManager.tickSpawning(serverInstance);
            }
        }

        customerQueueTickCounter++;
        if (customerQueueTickCounter >= CUSTOMER_QUEUE_TICK_INTERVAL_TICKS)
        {
            customerQueueTickCounter = 0;
            CustomerNpcManager.tickQueues(serverInstance);
        }

        if (businessData.wanderersEnabled())
        {
            wandererSpawnTickCounter++;
            int wandererIntervalTicks = Math.max(20, businessData.wandererSpawnIntervalMinutes() * 60 * 20);
            if (wandererSpawnTickCounter >= wandererIntervalTicks)
            {
                wandererSpawnTickCounter = 0;
                CustomerNpcManager.tickWanderers(serverInstance);
            }
        }

        territoryBillingTickCounter++;
        if (territoryBillingTickCounter >= BUSINESS_BILLING_INTERVAL_TICKS)
        {
            territoryBillingTickCounter = 0;
            TerritoryData.get(serverInstance).tickBilling(serverInstance);
        }

        jailTickCounter++;
        if (jailTickCounter >= JAIL_TICK_INTERVAL_TICKS)
        {
            jailTickCounter = 0;
            JailListener.tick(serverInstance);
        }

        borderTickCounter++;
        if (borderTickCounter >= BORDER_TICK_INTERVAL_TICKS)
        {
            borderTickCounter = 0;
            LawListener.borderTick(serverInstance);
        }

        clothingTickCounter++;
        if (clothingTickCounter >= CLOTHING_TICK_INTERVAL_TICKS)
        {
            clothingTickCounter = 0;
            LawListener.clothingTick(serverInstance);
        }
    }

    // Send the player their current money balance and deliver any items bought from the web panel while
    // they were offline, as soon as they join.
    @SubscribeEvent
    public void onPlayerLoggedIn(PlayerEvent.PlayerLoggedInEvent event)
    {
        if (event.getEntity() instanceof ServerPlayer serverPlayer)
        {
            MinecraftServer server = serverPlayer.getServer();
            int money = MoneyData.get(server).getMoney(serverPlayer.getUUID());
            NetworkHandler.sendMoneyUpdate(serverPlayer, money);
            MailboxData.get(server).deliver(serverPlayer);
        }
    }

    // You can use EventBusSubscriber to automatically register all static methods in the class annotated with @SubscribeEvent
    @Mod.EventBusSubscriber(modid = MODID, bus = Mod.EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
    public static class ClientModEvents
    {
        @SubscribeEvent
        public static void onClientSetup(FMLClientSetupEvent event)
        {
            // Some client setup code
            LOGGER.info("HELLO FROM CLIENT SETUP");
            LOGGER.info("MINECRAFT NAME >> {}", Minecraft.getInstance().getUser().getName());
        }

        @SubscribeEvent
        public static void onRegisterGuiOverlays(RegisterGuiOverlaysEvent event)
        {
            event.registerAboveAll("money_overlay", new MoneyOverlay());
        }

        @SubscribeEvent
        public static void onRegisterRenderers(EntityRenderersEvent.RegisterRenderers event)
        {
            event.registerEntityRenderer(CUSTOMER_NPC.get(), CustomerNpcRenderer::new);
            event.registerEntityRenderer(EMPLOYEE_NPC.get(), EmployeeNpcRenderer::new);
        }
    }
}
