package com.andruspro6446.servermod.business;

import net.minecraft.resources.ResourceLocation;

// A pluggable kind of business - see com.andruspro6446.servermod.api.BusinessTypeRegistry for how addons
// register new ones. The built-in Shop type (see BusinessTypes.SHOP) covers everything the base mod already
// does (storefront, market, manufacturing); an addon can register additional types (e.g. Shipping) that add
// their own weekly costs and persistent state without the base mod knowing anything about them.
public interface BusinessType
{
    ResourceLocation id();

    String displayName();

    // Folded into BusinessData.amountDueCents alongside the base weekly fee/barrels/sign/employee wage - lets
    // a type charge for its own upkeep (e.g. port/ship maintenance) through the same billing cycle.
    default int extraWeeklyFeeCents(Business business) { return 0; }

    // Called once, right after BusinessData.register creates the business - a chance to seed extraData.
    default void onRegistered(Business business) {}

    // Called right before a business is removed (dissolved for missed payment, or admin-deleted) - a chance
    // to clean up or refund type-specific state. The business is still fully intact at this point.
    default void onDissolved(Business business) {}

    // Optional read-only HTML shown on this business's /user/business web dashboard (see
    // web.BusinessHandler.otherBusinessesHtml) - e.g. the Shipping addon surfaces its ports/ships/contracts
    // here. Returns "" (nothing shown) by default; the core mod never interprets what's returned, just
    // splices it into the page as-is, so escape any business-controlled text yourself (see web.Html.escape -
    // public, safe for an addon to call directly).
    default String dashboardHtml(Business business) { return ""; }
}
