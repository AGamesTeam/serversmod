package com.andruspro6446.servermod.territory;

import com.andruspro6446.servermod.business.BarrelPos;
import com.andruspro6446.servermod.business.Business;
import com.andruspro6446.servermod.business.BusinessData;
import com.andruspro6446.servermod.business.SalesTax;
import com.andruspro6446.servermod.money.MoneyData;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

// Every land claim, city, and country, plus the admin-configured shovel tiers, the flat per-block rate every
// country owes the server admin, and the (independently configured per tier) missed-payment policies. All
// access must happen on the main server thread.
public class TerritoryData extends SavedData
{
    private static final String DATA_NAME = "servermod_territory";
    public static final long BILLING_INTERVAL_MILLIS = 7L * 24 * 60 * 60 * 1000; // 7 real days, i.e. "weekly"

    private final Map<UUID, LandClaim> landClaims = new LinkedHashMap<>();
    private final Map<UUID, City> cities = new LinkedHashMap<>();
    private final Map<UUID, Country> countries = new LinkedHashMap<>();
    private final Map<ClaimType, List<ShovelTier>> tiers = new LinkedHashMap<>(Map.of(
            ClaimType.LAND, new ArrayList<>(), ClaimType.CITY, new ArrayList<>(), ClaimType.COUNTRY, new ArrayList<>()));
    private final Map<UUID, Report> reports = new LinkedHashMap<>();
    // Keyed by player, since a player can only be serving one sentence at a time.
    private final Map<UUID, Sentence> sentences = new LinkedHashMap<>();

    private int adminCountryRatePerBlockCents = 1;

    // Global costs for the government-vs-business commerce actions (see business.SalesTax and the
    // government-dashboard "Commerce" section) - flat and admin-configurable rather than per-government, so
    // every government pays the same to run a festival/endorse/audit rather than needing its own tunable.
    private int festivalCostCents = 50_00;
    private int endorsementCostCents = 100_00;
    private int auditCostCents = 25_00;
    private double auditFinePercent = 15.0;
    private int publicSquareCostCents = 200_00;
    private int publicSquareMaxRadius = 48;

    private MissedPaymentPolicy landPolicy = MissedPaymentPolicy.SUSPEND;
    private int landGraceDays = 3;
    private MissedPaymentPolicy cityPolicy = MissedPaymentPolicy.SUSPEND;
    private int cityGraceDays = 3;
    private MissedPaymentPolicy countryPolicy = MissedPaymentPolicy.SUSPEND;
    private int countryGraceDays = 3;

    public static TerritoryData create()
    {
        return new TerritoryData();
    }

    public static TerritoryData load(CompoundTag tag)
    {
        TerritoryData data = new TerritoryData();
        if (tag.contains("adminCountryRatePerBlockCents"))
            data.adminCountryRatePerBlockCents = tag.getInt("adminCountryRatePerBlockCents");
        if (tag.contains("festivalCostCents"))
            data.festivalCostCents = tag.getInt("festivalCostCents");
        if (tag.contains("endorsementCostCents"))
            data.endorsementCostCents = tag.getInt("endorsementCostCents");
        if (tag.contains("auditCostCents"))
            data.auditCostCents = tag.getInt("auditCostCents");
        if (tag.contains("auditFinePercent"))
            data.auditFinePercent = tag.getDouble("auditFinePercent");
        if (tag.contains("publicSquareCostCents"))
            data.publicSquareCostCents = tag.getInt("publicSquareCostCents");
        if (tag.contains("publicSquareMaxRadius"))
            data.publicSquareMaxRadius = tag.getInt("publicSquareMaxRadius");

        data.landPolicy = readPolicy(tag, "landPolicy");
        data.landGraceDays = tag.contains("landGraceDays") ? tag.getInt("landGraceDays") : 3;
        data.cityPolicy = readPolicy(tag, "cityPolicy");
        data.cityGraceDays = tag.contains("cityGraceDays") ? tag.getInt("cityGraceDays") : 3;
        data.countryPolicy = readPolicy(tag, "countryPolicy");
        data.countryGraceDays = tag.contains("countryGraceDays") ? tag.getInt("countryGraceDays") : 3;

        ListTag landTag = tag.getList("landClaims", Tag.TAG_COMPOUND);
        for (int i = 0; i < landTag.size(); i++)
        {
            CompoundTag t = landTag.getCompound(i);
            UUID id = UUID.fromString(t.getString("id"));
            UUID owner = UUID.fromString(t.getString("owner"));
            ClaimRegion region = readRegion(t.getCompound("region"));
            if (region == null)
                continue;
            Map<ClaimPermission, PermissionMode> rules = t.contains("rules") ? readRules(t.getCompound("rules")) : LandClaim.defaultRules();
            LandClaim claim = new LandClaim(id, owner, region, readBilling(t), rules);
            readTrusted(t.getList("trusted", Tag.TAG_COMPOUND), claim.trusted);
            data.landClaims.put(id, claim);
        }

        ListTag citiesTag = tag.getList("cities", Tag.TAG_COMPOUND);
        for (int i = 0; i < citiesTag.size(); i++)
        {
            CompoundTag t = citiesTag.getCompound(i);
            ClaimRegion region = readRegion(t.getCompound("region"));
            if (region == null)
                continue;
            UUID id = UUID.fromString(t.getString("id"));
            City city = new City(id, t.getString("name"), UUID.fromString(t.getString("founder")), region, t.getInt("rate"), readBilling(t));
            city.balanceCents = t.getInt("balance");
            readOfficials(t.getList("officials", Tag.TAG_COMPOUND), city.officials);
            readPermissionSet(t.getList("officialAccess", Tag.TAG_STRING), city.officialAccess);
            readGovernmentExtras(t, city);
            data.cities.put(id, city);
        }

        ListTag countriesTag = tag.getList("countries", Tag.TAG_COMPOUND);
        for (int i = 0; i < countriesTag.size(); i++)
        {
            CompoundTag t = countriesTag.getCompound(i);
            ClaimRegion region = readRegion(t.getCompound("region"));
            if (region == null)
                continue;
            UUID id = UUID.fromString(t.getString("id"));
            Country country = new Country(id, t.getString("name"), UUID.fromString(t.getString("founder")), region, t.getInt("rate"), readBilling(t));
            country.balanceCents = t.getInt("balance");
            readOfficials(t.getList("officials", Tag.TAG_COMPOUND), country.officials);
            readGovernmentExtras(t, country);
            readCountryExtras(t, country);
            data.countries.put(id, country);
        }

        ListTag reportsTag = tag.getList("reports", Tag.TAG_COMPOUND);
        for (int i = 0; i < reportsTag.size(); i++)
        {
            CompoundTag t = reportsTag.getCompound(i);
            ClaimPermission permission;
            ClaimType governmentType;
            try
            {
                permission = ClaimPermission.valueOf(t.getString("permission"));
                governmentType = ClaimType.valueOf(t.getString("governmentType"));
            }
            catch (IllegalArgumentException e)
            {
                continue;
            }
            Report report = new Report(UUID.fromString(t.getString("id")), UUID.fromString(t.getString("reporter")),
                    UUID.fromString(t.getString("offender")), UUID.fromString(t.getString("landClaimId")),
                    governmentType, UUID.fromString(t.getString("governmentId")), permission, t.getString("reason"));
            try
            {
                report.status = ReportStatus.valueOf(t.getString("status"));
            }
            catch (IllegalArgumentException e)
            {
                report.status = ReportStatus.PENDING;
            }
            data.reports.put(report.id, report);
        }

        ListTag sentencesTag = tag.getList("sentences", Tag.TAG_COMPOUND);
        for (int i = 0; i < sentencesTag.size(); i++)
        {
            CompoundTag t = sentencesTag.getCompound(i);
            UUID playerId = UUID.fromString(t.getString("player"));
            UUID cellId = t.contains("cell") ? UUID.fromString(t.getString("cell")) : null;
            Sentence sentence = new Sentence(playerId, cellId, t.getLong("releaseAtMillis"), t.getInt("quotaCents"));
            sentence.miningProgressCents = t.getInt("quotaProgress");
            sentence.applied = t.getBoolean("applied");
            if (t.contains("returnDim"))
            {
                ResourceLocation dimId = ResourceLocation.tryParse(t.getString("returnDim"));
                if (dimId != null)
                    sentence.returnDimension = ResourceKey.create(Registries.DIMENSION, dimId);
            }
            sentence.returnX = t.getDouble("returnX");
            sentence.returnY = t.getDouble("returnY");
            sentence.returnZ = t.getDouble("returnZ");
            ListTag itemsTag = t.getList("items", Tag.TAG_COMPOUND);
            for (int j = 0; j < itemsTag.size(); j++)
                sentence.confiscatedItems.add(ItemStack.of(itemsTag.getCompound(j)));
            data.sentences.put(playerId, sentence);
        }

        CompoundTag tiersTag = tag.getCompound("tiers");
        for (ClaimType type : ClaimType.values())
        {
            ListTag tierList = tiersTag.getList(type.name(), Tag.TAG_COMPOUND);
            for (int i = 0; i < tierList.size(); i++)
            {
                CompoundTag t = tierList.getCompound(i);
                data.tiers.get(type).add(new ShovelTier(UUID.fromString(t.getString("id")), t.getString("name"),
                        t.getInt("price"), t.getInt("maxCharges"), t.getLong("maxArea")));
            }
        }

        return data;
    }

    private static MissedPaymentPolicy readPolicy(CompoundTag tag, String key)
    {
        try
        {
            return tag.contains(key) ? MissedPaymentPolicy.valueOf(tag.getString(key)) : MissedPaymentPolicy.SUSPEND;
        }
        catch (IllegalArgumentException e)
        {
            return MissedPaymentPolicy.SUSPEND;
        }
    }

    private static CompoundTag writeRegion(ClaimRegion region)
    {
        CompoundTag tag = new CompoundTag();
        tag.putString("dim", region.dimension.location().toString());
        ListTag rectsTag = new ListTag();
        for (Rect r : region.pieces)
        {
            CompoundTag rectTag = new CompoundTag();
            rectTag.putInt("minX", r.minX());
            rectTag.putInt("minZ", r.minZ());
            rectTag.putInt("maxX", r.maxX());
            rectTag.putInt("maxZ", r.maxZ());
            rectsTag.add(rectTag);
        }
        tag.put("rects", rectsTag);
        return tag;
    }

    private static ClaimRegion readRegion(CompoundTag tag)
    {
        ResourceLocation dimId = ResourceLocation.tryParse(tag.getString("dim"));
        if (dimId == null)
            return null;
        ResourceKey<Level> dimension = ResourceKey.create(Registries.DIMENSION, dimId);

        List<Rect> pieces = new ArrayList<>();
        ListTag rectsTag = tag.getList("rects", Tag.TAG_COMPOUND);
        for (int i = 0; i < rectsTag.size(); i++)
        {
            CompoundTag r = rectsTag.getCompound(i);
            pieces.add(new Rect(r.getInt("minX"), r.getInt("minZ"), r.getInt("maxX"), r.getInt("maxZ")));
        }
        return pieces.isEmpty() ? null : new ClaimRegion(dimension, pieces);
    }

    private static CompoundTag writeBilling(Billing billing)
    {
        CompoundTag tag = new CompoundTag();
        tag.putString("status", billing.status.name());
        tag.putLong("nextBillingAtMillis", billing.nextBillingAtMillis);
        tag.putLong("graceDeadlineMillis", billing.graceDeadlineMillis);
        return tag;
    }

    private static Billing readBilling(CompoundTag ownerTag)
    {
        CompoundTag tag = ownerTag.getCompound("billing");
        Billing billing = new Billing(tag.getLong("nextBillingAtMillis"));
        billing.graceDeadlineMillis = tag.getLong("graceDeadlineMillis");
        try
        {
            billing.status = tag.contains("status") ? BillingStatus.valueOf(tag.getString("status")) : BillingStatus.ACTIVE;
        }
        catch (IllegalArgumentException e)
        {
            billing.status = BillingStatus.ACTIVE;
        }
        return billing;
    }

    private static ListTag writeOfficials(Map<UUID, Set<GovPermission>> officials)
    {
        ListTag listTag = new ListTag();
        officials.forEach((playerId, perms) -> {
            CompoundTag t = new CompoundTag();
            t.putString("player", playerId.toString());
            ListTag permsTag = new ListTag();
            for (GovPermission perm : perms)
                permsTag.add(StringTag.valueOf(perm.name()));
            t.put("perms", permsTag);
            listTag.add(t);
        });
        return listTag;
    }

    private static void readOfficials(ListTag listTag, Map<UUID, Set<GovPermission>> target)
    {
        for (int i = 0; i < listTag.size(); i++)
        {
            CompoundTag t = listTag.getCompound(i);
            UUID playerId = UUID.fromString(t.getString("player"));
            Set<GovPermission> perms = new LinkedHashSet<>();
            ListTag permsTag = t.getList("perms", Tag.TAG_STRING);
            for (int j = 0; j < permsTag.size(); j++)
            {
                try
                {
                    perms.add(GovPermission.valueOf(permsTag.getString(j)));
                }
                catch (IllegalArgumentException ignored)
                {
                }
            }
            target.put(playerId, perms);
        }
    }

    private static CompoundTag writeRules(Map<ClaimPermission, PermissionMode> rules)
    {
        CompoundTag tag = new CompoundTag();
        rules.forEach((permission, mode) -> tag.putString(permission.name(), mode.name()));
        return tag;
    }

    private static Map<ClaimPermission, PermissionMode> readRules(CompoundTag tag)
    {
        Map<ClaimPermission, PermissionMode> rules = LandClaim.defaultRules();
        for (String key : tag.getAllKeys())
        {
            try
            {
                rules.put(ClaimPermission.valueOf(key), PermissionMode.valueOf(tag.getString(key)));
            }
            catch (IllegalArgumentException ignored)
            {
            }
        }
        return rules;
    }

    private static ListTag writeTrusted(Map<UUID, Set<ClaimPermission>> trusted)
    {
        ListTag listTag = new ListTag();
        trusted.forEach((playerId, perms) -> {
            CompoundTag t = new CompoundTag();
            t.putString("player", playerId.toString());
            ListTag permsTag = new ListTag();
            for (ClaimPermission perm : perms)
                permsTag.add(StringTag.valueOf(perm.name()));
            t.put("perms", permsTag);
            listTag.add(t);
        });
        return listTag;
    }

    private static void readTrusted(ListTag listTag, Map<UUID, Set<ClaimPermission>> target)
    {
        for (int i = 0; i < listTag.size(); i++)
        {
            CompoundTag t = listTag.getCompound(i);
            UUID playerId = UUID.fromString(t.getString("player"));
            Set<ClaimPermission> perms = EnumSet.noneOf(ClaimPermission.class);
            ListTag permsTag = t.getList("perms", Tag.TAG_STRING);
            for (int j = 0; j < permsTag.size(); j++)
            {
                try
                {
                    perms.add(ClaimPermission.valueOf(permsTag.getString(j)));
                }
                catch (IllegalArgumentException ignored)
                {
                }
            }
            target.put(playerId, perms);
        }
    }

    private static ListTag writePermissionSet(Set<ClaimPermission> permissions)
    {
        ListTag listTag = new ListTag();
        for (ClaimPermission permission : permissions)
            listTag.add(StringTag.valueOf(permission.name()));
        return listTag;
    }

    private static void readPermissionSet(ListTag listTag, Set<ClaimPermission> target)
    {
        for (int i = 0; i < listTag.size(); i++)
        {
            try
            {
                target.add(ClaimPermission.valueOf(listTag.getString(i)));
            }
            catch (IllegalArgumentException ignored)
            {
            }
        }
    }

    private static CompoundTag writeJailCell(JailCell cell)
    {
        CompoundTag tag = new CompoundTag();
        tag.putString("id", cell.id.toString());
        tag.putString("name", cell.name);
        tag.putString("dim", cell.dimension.location().toString());
        tag.putInt("x", cell.pos.getX());
        tag.putInt("y", cell.pos.getY());
        tag.putInt("z", cell.pos.getZ());
        tag.putInt("radius", cell.radius);
        tag.putInt("maxCapacity", cell.maxCapacity);
        return tag;
    }

    private static JailCell readJailCell(CompoundTag tag)
    {
        ResourceLocation dimId = ResourceLocation.tryParse(tag.getString("dim"));
        if (dimId == null)
            return null;
        ResourceKey<Level> dimension = ResourceKey.create(Registries.DIMENSION, dimId);
        BlockPos pos = new BlockPos(tag.getInt("x"), tag.getInt("y"), tag.getInt("z"));
        return new JailCell(UUID.fromString(tag.getString("id")), tag.getString("name"), dimension, pos, tag.getInt("radius"), tag.getInt("maxCapacity"));
    }

    private static void writeGovernmentExtras(CompoundTag tag, Government government)
    {
        tag.putInt("victimSharePercent", government.victimSharePercent);
        tag.putInt("salesTaxRatePercent", government.salesTaxRatePercent);
        tag.putInt("foreignTaxSurchargePercent", government.foreignTaxSurchargePercent);
        tag.putInt("licenseFeeCents", government.licenseFeeCents);
        ListTag cellsTag = new ListTag();
        for (JailCell cell : government.jailCells)
            cellsTag.add(writeJailCell(cell));
        tag.put("jailCells", cellsTag);

        ListTag sanctionedTag = new ListTag();
        for (UUID id : government.sanctionedGovernmentIds)
            sanctionedTag.add(StringTag.valueOf(id.toString()));
        tag.put("sanctionedGovernmentIds", sanctionedTag);

        ListTag alliedTag = new ListTag();
        for (UUID id : government.alliedGovernmentIds)
            alliedTag.add(StringTag.valueOf(id.toString()));
        tag.put("alliedGovernmentIds", alliedTag);

        ListTag squaresTag = new ListTag();
        for (PublicSquare square : government.publicSquares)
            squaresTag.add(writePublicSquare(square));
        tag.put("publicSquares", squaresTag);
    }

    private static void readGovernmentExtras(CompoundTag tag, Government government)
    {
        if (tag.contains("victimSharePercent"))
            government.victimSharePercent = tag.getInt("victimSharePercent");
        if (tag.contains("salesTaxRatePercent"))
            government.salesTaxRatePercent = tag.getInt("salesTaxRatePercent");
        if (tag.contains("foreignTaxSurchargePercent"))
            government.foreignTaxSurchargePercent = tag.getInt("foreignTaxSurchargePercent");
        if (tag.contains("licenseFeeCents"))
            government.licenseFeeCents = tag.getInt("licenseFeeCents");
        ListTag cellsTag = tag.getList("jailCells", Tag.TAG_COMPOUND);
        for (int i = 0; i < cellsTag.size(); i++)
        {
            JailCell cell = readJailCell(cellsTag.getCompound(i));
            if (cell != null)
                government.jailCells.add(cell);
        }

        ListTag sanctionedTag = tag.getList("sanctionedGovernmentIds", Tag.TAG_STRING);
        for (int i = 0; i < sanctionedTag.size(); i++)
            government.sanctionedGovernmentIds.add(UUID.fromString(sanctionedTag.getString(i)));

        ListTag alliedTag = tag.getList("alliedGovernmentIds", Tag.TAG_STRING);
        for (int i = 0; i < alliedTag.size(); i++)
            government.alliedGovernmentIds.add(UUID.fromString(alliedTag.getString(i)));

        ListTag squaresTag = tag.getList("publicSquares", Tag.TAG_COMPOUND);
        for (int i = 0; i < squaresTag.size(); i++)
        {
            PublicSquare square = readPublicSquare(squaresTag.getCompound(i));
            if (square != null)
                government.publicSquares.add(square);
        }
    }

    private static CompoundTag writePublicSquare(PublicSquare square)
    {
        CompoundTag tag = new CompoundTag();
        tag.putString("dim", square.dimension.location().toString());
        tag.putInt("x", square.pos.getX());
        tag.putInt("y", square.pos.getY());
        tag.putInt("z", square.pos.getZ());
        tag.putInt("radius", square.radius);
        return tag;
    }

    private static PublicSquare readPublicSquare(CompoundTag tag)
    {
        ResourceLocation dimId = ResourceLocation.tryParse(tag.getString("dim"));
        if (dimId == null)
            return null;
        ResourceKey<Level> dimension = ResourceKey.create(Registries.DIMENSION, dimId);
        BlockPos pos = new BlockPos(tag.getInt("x"), tag.getInt("y"), tag.getInt("z"));
        return new PublicSquare(dimension, pos, tag.getInt("radius"));
    }

    private static CompoundTag writeLawConfig(LawConfig config)
    {
        CompoundTag tag = new CompoundTag();
        tag.putBoolean("enabled", config.enabled);
        tag.putInt("fineCents", config.fineCents);
        if (config.jailCellId != null)
            tag.putString("jailCellId", config.jailCellId.toString());
        tag.putInt("jailMinutes", config.jailMinutes);
        tag.putInt("quotaCents", config.quotaCents);
        return tag;
    }

    private static void readLawConfig(CompoundTag tag, LawConfig config)
    {
        config.enabled = tag.getBoolean("enabled");
        config.fineCents = tag.getInt("fineCents");
        if (tag.contains("jailCellId"))
            config.jailCellId = UUID.fromString(tag.getString("jailCellId"));
        config.jailMinutes = tag.getInt("jailMinutes");
        config.quotaCents = tag.getInt("quotaCents");
    }

    private static void writeCountryExtras(CompoundTag tag, Country country)
    {
        tag.putInt("lawMaxY", country.lawMaxY);
        tag.putInt("lawVersion", country.lawVersion);

        ListTag zonesTag = new ListTag();
        for (Rect zone : country.lawYOverrideZones)
        {
            CompoundTag z = new CompoundTag();
            z.putInt("minX", zone.minX());
            z.putInt("minZ", zone.minZ());
            z.putInt("maxX", zone.maxX());
            z.putInt("maxZ", zone.maxZ());
            zonesTag.add(z);
        }
        tag.put("lawYOverrideZones", zonesTag);

        CompoundTag lawsTag = new CompoundTag();
        country.laws.forEach((law, config) -> lawsTag.put(law.name(), writeLawConfig(config)));
        tag.put("laws", lawsTag);

        ListTag permitsTag = new ListTag();
        country.permits.forEach((playerId, laws) -> {
            CompoundTag p = new CompoundTag();
            p.putString("player", playerId.toString());
            ListTag lawsListTag = new ListTag();
            for (CountryLaw law : laws)
                lawsListTag.add(StringTag.valueOf(law.name()));
            p.put("laws", lawsListTag);
            permitsTag.add(p);
        });
        tag.put("permits", permitsTag);

        CompoundTag agreedTag = new CompoundTag();
        country.agreedLawVersions.forEach((playerId, version) -> agreedTag.putInt(playerId.toString(), version));
        tag.put("agreedLawVersions", agreedTag);
    }

    private static void readCountryExtras(CompoundTag tag, Country country)
    {
        if (tag.contains("lawMaxY"))
            country.lawMaxY = tag.getInt("lawMaxY");
        if (tag.contains("lawVersion"))
            country.lawVersion = tag.getInt("lawVersion");

        ListTag zonesTag = tag.getList("lawYOverrideZones", Tag.TAG_COMPOUND);
        for (int i = 0; i < zonesTag.size(); i++)
        {
            CompoundTag z = zonesTag.getCompound(i);
            country.lawYOverrideZones.add(new Rect(z.getInt("minX"), z.getInt("minZ"), z.getInt("maxX"), z.getInt("maxZ")));
        }

        CompoundTag lawsTag = tag.getCompound("laws");
        for (CountryLaw law : CountryLaw.values())
        {
            if (lawsTag.contains(law.name()))
                readLawConfig(lawsTag.getCompound(law.name()), country.laws.get(law));
        }

        ListTag permitsTag = tag.getList("permits", Tag.TAG_COMPOUND);
        for (int i = 0; i < permitsTag.size(); i++)
        {
            CompoundTag p = permitsTag.getCompound(i);
            UUID playerId = UUID.fromString(p.getString("player"));
            Set<CountryLaw> laws = EnumSet.noneOf(CountryLaw.class);
            ListTag lawsListTag = p.getList("laws", Tag.TAG_STRING);
            for (int j = 0; j < lawsListTag.size(); j++)
            {
                try
                {
                    laws.add(CountryLaw.valueOf(lawsListTag.getString(j)));
                }
                catch (IllegalArgumentException ignored)
                {
                }
            }
            country.permits.put(playerId, laws);
        }

        CompoundTag agreedTag = tag.getCompound("agreedLawVersions");
        for (String key : agreedTag.getAllKeys())
        {
            try
            {
                country.agreedLawVersions.put(UUID.fromString(key), agreedTag.getInt(key));
            }
            catch (IllegalArgumentException ignored)
            {
            }
        }
    }

    @Override
    public CompoundTag save(CompoundTag tag)
    {
        tag.putInt("adminCountryRatePerBlockCents", adminCountryRatePerBlockCents);
        tag.putInt("festivalCostCents", festivalCostCents);
        tag.putInt("endorsementCostCents", endorsementCostCents);
        tag.putInt("auditCostCents", auditCostCents);
        tag.putDouble("auditFinePercent", auditFinePercent);
        tag.putInt("publicSquareCostCents", publicSquareCostCents);
        tag.putInt("publicSquareMaxRadius", publicSquareMaxRadius);
        tag.putString("landPolicy", landPolicy.name());
        tag.putInt("landGraceDays", landGraceDays);
        tag.putString("cityPolicy", cityPolicy.name());
        tag.putInt("cityGraceDays", cityGraceDays);
        tag.putString("countryPolicy", countryPolicy.name());
        tag.putInt("countryGraceDays", countryGraceDays);

        ListTag landTag = new ListTag();
        landClaims.values().forEach(claim -> {
            CompoundTag t = new CompoundTag();
            t.putString("id", claim.id.toString());
            t.putString("owner", claim.ownerId.toString());
            t.put("region", writeRegion(claim.region));
            t.put("billing", writeBilling(claim.billing));
            t.put("rules", writeRules(claim.rules));
            t.put("trusted", writeTrusted(claim.trusted));
            landTag.add(t);
        });
        tag.put("landClaims", landTag);

        ListTag citiesTag = new ListTag();
        cities.values().forEach(city -> {
            CompoundTag t = new CompoundTag();
            t.putString("id", city.id.toString());
            t.putString("name", city.name);
            t.putString("founder", city.founderId.toString());
            t.putInt("balance", city.balanceCents);
            t.putInt("rate", city.landClaimRatePerBlockCents);
            t.put("region", writeRegion(city.region));
            t.put("billing", writeBilling(city.billing));
            t.put("officials", writeOfficials(city.officials));
            t.put("officialAccess", writePermissionSet(city.officialAccess));
            writeGovernmentExtras(t, city);
            citiesTag.add(t);
        });
        tag.put("cities", citiesTag);

        ListTag countriesTag = new ListTag();
        countries.values().forEach(country -> {
            CompoundTag t = new CompoundTag();
            t.putString("id", country.id.toString());
            t.putString("name", country.name);
            t.putString("founder", country.founderId.toString());
            t.putInt("balance", country.balanceCents);
            t.putInt("rate", country.cityRatePerBlockCents);
            t.put("region", writeRegion(country.region));
            t.put("billing", writeBilling(country.billing));
            t.put("officials", writeOfficials(country.officials));
            writeGovernmentExtras(t, country);
            writeCountryExtras(t, country);
            countriesTag.add(t);
        });
        tag.put("countries", countriesTag);

        ListTag reportsTag = new ListTag();
        reports.values().forEach(report -> {
            CompoundTag t = new CompoundTag();
            t.putString("id", report.id.toString());
            t.putString("reporter", report.reporterId.toString());
            t.putString("offender", report.offenderId.toString());
            t.putString("landClaimId", report.landClaimId.toString());
            t.putString("governmentType", report.governmentType.name());
            t.putString("governmentId", report.governmentId.toString());
            t.putString("permission", report.permission.name());
            t.putString("reason", report.reason);
            t.putString("status", report.status.name());
            reportsTag.add(t);
        });
        tag.put("reports", reportsTag);

        ListTag sentencesTag = new ListTag();
        sentences.values().forEach(sentence -> {
            CompoundTag t = new CompoundTag();
            t.putString("player", sentence.playerId.toString());
            if (sentence.jailCellId != null)
                t.putString("cell", sentence.jailCellId.toString());
            t.putLong("releaseAtMillis", sentence.releaseAtMillis);
            t.putInt("quotaCents", sentence.miningQuotaCents);
            t.putInt("quotaProgress", sentence.miningProgressCents);
            t.putBoolean("applied", sentence.applied);
            if (sentence.returnDimension != null)
                t.putString("returnDim", sentence.returnDimension.location().toString());
            t.putDouble("returnX", sentence.returnX);
            t.putDouble("returnY", sentence.returnY);
            t.putDouble("returnZ", sentence.returnZ);
            ListTag itemsTag = new ListTag();
            for (ItemStack stack : sentence.confiscatedItems)
                itemsTag.add(stack.save(new CompoundTag()));
            t.put("items", itemsTag);
            sentencesTag.add(t);
        });
        tag.put("sentences", sentencesTag);

        CompoundTag tiersTag = new CompoundTag();
        for (ClaimType type : ClaimType.values())
        {
            ListTag tierList = new ListTag();
            for (ShovelTier tier : tiers.get(type))
            {
                CompoundTag t = new CompoundTag();
                t.putString("id", tier.id.toString());
                t.putString("name", tier.name);
                t.putInt("price", tier.priceCents);
                t.putInt("maxCharges", tier.maxCharges);
                t.putLong("maxArea", tier.maxAreaBlocks);
                tierList.add(t);
            }
            tiersTag.put(type.name(), tierList);
        }
        tag.put("tiers", tiersTag);

        return tag;
    }

    public static TerritoryData get(MinecraftServer server)
    {
        return server.overworld().getDataStorage().computeIfAbsent(TerritoryData::load, TerritoryData::create, DATA_NAME);
    }

    // ---------- admin settings ----------

    public int adminCountryRatePerBlockCents()
    {
        return adminCountryRatePerBlockCents;
    }

    public void setAdminCountryRatePerBlockCents(int cents)
    {
        adminCountryRatePerBlockCents = Math.max(0, cents);
        setDirty();
    }

    public int festivalCostCents() { return festivalCostCents; }
    public int endorsementCostCents() { return endorsementCostCents; }
    public int auditCostCents() { return auditCostCents; }
    public double auditFinePercent() { return auditFinePercent; }
    public int publicSquareCostCents() { return publicSquareCostCents; }
    public int publicSquareMaxRadius() { return publicSquareMaxRadius; }

    public void setFestivalCostCents(int cents) { festivalCostCents = Math.max(0, cents); setDirty(); }
    public void setEndorsementCostCents(int cents) { endorsementCostCents = Math.max(0, cents); setDirty(); }
    public void setAuditCostCents(int cents) { auditCostCents = Math.max(0, cents); setDirty(); }
    public void setAuditFinePercent(double percent) { auditFinePercent = Math.max(0, percent); setDirty(); }
    public void setPublicSquareCostCents(int cents) { publicSquareCostCents = Math.max(0, cents); setDirty(); }
    public void setPublicSquareMaxRadius(int radius) { publicSquareMaxRadius = Math.max(1, radius); setDirty(); }

    public MissedPaymentPolicy policyFor(ClaimType type)
    {
        return switch (type)
        {
            case LAND -> landPolicy;
            case CITY -> cityPolicy;
            case COUNTRY -> countryPolicy;
        };
    }

    public int graceDaysFor(ClaimType type)
    {
        return switch (type)
        {
            case LAND -> landGraceDays;
            case CITY -> cityGraceDays;
            case COUNTRY -> countryGraceDays;
        };
    }

    public void setPolicy(ClaimType type, MissedPaymentPolicy policy)
    {
        switch (type)
        {
            case LAND -> landPolicy = policy;
            case CITY -> cityPolicy = policy;
            case COUNTRY -> countryPolicy = policy;
        }
        setDirty();
    }

    public void setGraceDays(ClaimType type, int days)
    {
        int clamped = Math.max(0, days);
        switch (type)
        {
            case LAND -> landGraceDays = clamped;
            case CITY -> cityGraceDays = clamped;
            case COUNTRY -> countryGraceDays = clamped;
        }
        setDirty();
    }

    // ---------- shovel tiers ----------

    public List<ShovelTier> getTiers(ClaimType type)
    {
        return new ArrayList<>(tiers.get(type));
    }

    public ShovelTier getTier(ClaimType type, UUID tierId)
    {
        return tiers.get(type).stream().filter(t -> t.id.equals(tierId)).findFirst().orElse(null);
    }

    public ShovelTier findTierByName(ClaimType type, String name)
    {
        return tiers.get(type).stream().filter(t -> t.name.equalsIgnoreCase(name)).findFirst().orElse(null);
    }

    public ShovelTier addTier(ClaimType type, String name, int priceCents, int maxCharges, long maxAreaBlocks)
    {
        ShovelTier tier = new ShovelTier(UUID.randomUUID(), name, priceCents, maxCharges, maxAreaBlocks);
        tiers.get(type).add(tier);
        setDirty();
        return tier;
    }

    public boolean removeTier(ClaimType type, UUID tierId)
    {
        boolean removed = tiers.get(type).removeIf(t -> t.id.equals(tierId));
        if (removed)
            setDirty();
        return removed;
    }

    // ---------- claims: lookup ----------

    public LandClaim getLandClaim(UUID id)
    {
        return landClaims.get(id);
    }

    public List<LandClaim> getAllLandClaims()
    {
        return new ArrayList<>(landClaims.values());
    }

    public List<LandClaim> getLandClaimsOwnedBy(UUID ownerId)
    {
        List<LandClaim> result = new ArrayList<>();
        for (LandClaim claim : landClaims.values())
            if (claim.ownerId.equals(ownerId))
                result.add(claim);
        return result;
    }

    // The land claim (if any) the given position falls inside.
    public LandClaim findLandClaimAt(ResourceKey<Level> dim, int x, int z)
    {
        for (LandClaim claim : landClaims.values())
            if (claim.region.contains(dim, x, z))
                return claim;
        return null;
    }

    public Country findCountryAt(ResourceKey<Level> dim, int x, int z)
    {
        for (Country country : countries.values())
            if (country.region.contains(dim, x, z))
                return country;
        return null;
    }

    // The city (if any) the given position falls inside. Cities and countries are independent overlapping
    // claimed regions (a city isn't necessarily inside any particular country, or any at all - see
    // cityCountry), so this is deliberately separate from findCountryAt rather than derived from it.
    public City findCityAt(ResourceKey<Level> dim, int x, int z)
    {
        for (City city : cities.values())
            if (city.region.contains(dim, x, z))
                return city;
        return null;
    }

    public City getCity(UUID id)
    {
        return cities.get(id);
    }

    public City findCityByName(String name)
    {
        return cities.values().stream().filter(c -> c.name.equalsIgnoreCase(name)).findFirst().orElse(null);
    }

    public List<City> getAllCities()
    {
        return new ArrayList<>(cities.values());
    }

    public Country getCountry(UUID id)
    {
        return countries.get(id);
    }

    public Country findCountryByName(String name)
    {
        return countries.values().stream().filter(c -> c.name.equalsIgnoreCase(name)).findFirst().orElse(null);
    }

    public List<Country> getAllCountries()
    {
        return new ArrayList<>(countries.values());
    }

    // Every city whose territory currently overlaps a land claim's region - computed fresh, not cached, so a
    // city growing/shrinking/dissolving is reflected at the next billing check automatically.
    public List<City> citiesOverlapping(ClaimRegion region)
    {
        List<City> result = new ArrayList<>();
        for (City city : cities.values())
            if (regionsOverlap(city.region, region))
                result.add(city);
        return result;
    }

    public Country countryOverlapping(ClaimRegion region)
    {
        for (Country country : countries.values())
            if (regionsOverlap(country.region, region))
                return country;
        return null;
    }

    private static boolean regionsOverlap(ClaimRegion a, ClaimRegion b)
    {
        if (!a.dimension.equals(b.dimension))
            return false;
        for (Rect ra : a.pieces)
            for (Rect rb : b.pieces)
                if (ra.intersects(rb))
                    return true;
        return false;
    }

    // ---------- rent (shared by the claim-time preview and the actual billing tick) ----------

    // Weekly rent a land claim owes, broken down per overlapping city, prorated to the blocks actually inside
    // each one - not the claim's full size, so a claim spanning two cities pays each fairly.
    public Map<City, Integer> landRentBreakdown(ClaimRegion claimRegion)
    {
        Map<City, Integer> result = new LinkedHashMap<>();
        for (City city : citiesOverlapping(claimRegion))
        {
            long overlapBlocks = ClaimRegion.overlapBlockCount(claimRegion, city.region);
            result.put(city, (int) (overlapBlocks * city.landClaimRatePerBlockCents));
        }
        return result;
    }

    // Weekly rent a city owes its country, or null if it isn't inside one.
    public Country cityCountry(ClaimRegion cityRegion)
    {
        return countryOverlapping(cityRegion);
    }

    public int cityRentToCountryCents(ClaimRegion cityRegion, Country country)
    {
        if (country == null)
            return 0;
        long overlapBlocks = ClaimRegion.overlapBlockCount(cityRegion, country.region);
        return (int) (overlapBlocks * country.cityRatePerBlockCents);
    }

    public int countryRentToAdminCents(Country country)
    {
        return (int) (country.region.blockCount() * adminCountryRatePerBlockCents);
    }

    // ---------- claims: molding ----------

    private static List<Rect> subtractAll(Rect selection, List<Rect> blockers)
    {
        List<Rect> pieces = new ArrayList<>(List.of(selection));
        for (Rect blocker : blockers)
        {
            List<Rect> next = new ArrayList<>();
            for (Rect piece : pieces)
                next.addAll(piece.minus(blocker));
            pieces = next;
        }
        return pieces;
    }

    private static boolean intersectsAny(List<Rect> a, List<Rect> b)
    {
        for (Rect ra : a)
            for (Rect rb : b)
                if (ra.intersects(rb))
                    return true;
        return false;
    }

    // The selection with every OTHER player's land claim cut out. Your own overlapping claims aren't
    // subtracted here - they get merged into instead, at finalizeLandClaim().
    public List<Rect> moldLandSelection(ResourceKey<Level> dim, UUID ownerId, Rect selection)
    {
        List<Rect> blockers = new ArrayList<>();
        for (LandClaim claim : landClaims.values())
            if (!claim.ownerId.equals(ownerId) && claim.region.dimension.equals(dim))
                blockers.addAll(claim.region.pieces);
        return subtractAll(selection, blockers);
    }

    public List<Rect> moldCitySelection(ResourceKey<Level> dim, UUID founderId, Rect selection)
    {
        List<Rect> blockers = new ArrayList<>();
        for (City city : cities.values())
            if (!city.founderId.equals(founderId) && city.region.dimension.equals(dim))
                blockers.addAll(city.region.pieces);
        return subtractAll(selection, blockers);
    }

    public List<Rect> moldCountrySelection(ResourceKey<Level> dim, UUID founderId, Rect selection)
    {
        List<Rect> blockers = new ArrayList<>();
        for (Country country : countries.values())
            if (!country.founderId.equals(founderId) && country.region.dimension.equals(dim))
                blockers.addAll(country.region.pieces);
        return subtractAll(selection, blockers);
    }

    // ---------- claims: finalize ----------

    // Creates a new land claim from the (already molded) pieces, or - if they touch a claim the same player
    // already owns in that dimension - merges into it instead.
    public LandClaim finalizeLandClaim(UUID ownerId, ResourceKey<Level> dim, List<Rect> moldedPieces)
    {
        for (LandClaim claim : landClaims.values())
        {
            if (claim.ownerId.equals(ownerId) && claim.region.dimension.equals(dim) && intersectsAny(claim.region.pieces, moldedPieces))
            {
                claim.region.addAll(moldedPieces);
                setDirty();
                return claim;
            }
        }
        LandClaim claim = new LandClaim(UUID.randomUUID(), ownerId, new ClaimRegion(dim, moldedPieces),
                new Billing(System.currentTimeMillis() + BILLING_INTERVAL_MILLIS));
        landClaims.put(claim.id, claim);
        setDirty();
        return claim;
    }

    public City finalizeCity(UUID founderId, String name, ResourceKey<Level> dim, List<Rect> moldedPieces)
    {
        for (City city : cities.values())
        {
            if (city.founderId.equals(founderId) && city.region.dimension.equals(dim) && intersectsAny(city.region.pieces, moldedPieces))
            {
                city.region.addAll(moldedPieces);
                setDirty();
                return city;
            }
        }
        City city = new City(UUID.randomUUID(), name, founderId, new ClaimRegion(dim, moldedPieces), 0,
                new Billing(System.currentTimeMillis() + BILLING_INTERVAL_MILLIS));
        cities.put(city.id, city);
        setDirty();
        return city;
    }

    public Country finalizeCountry(UUID founderId, String name, ResourceKey<Level> dim, List<Rect> moldedPieces)
    {
        for (Country country : countries.values())
        {
            if (country.founderId.equals(founderId) && country.region.dimension.equals(dim) && intersectsAny(country.region.pieces, moldedPieces))
            {
                country.region.addAll(moldedPieces);
                setDirty();
                return country;
            }
        }
        Country country = new Country(UUID.randomUUID(), name, founderId, new ClaimRegion(dim, moldedPieces), 0,
                new Billing(System.currentTimeMillis() + BILLING_INTERVAL_MILLIS));
        countries.put(country.id, country);
        setDirty();
        return country;
    }

    // ---------- governments: rates & officials ----------

    public void setCityRate(City city, int centsPerBlock)
    {
        city.landClaimRatePerBlockCents = Math.max(0, centsPerBlock);
        setDirty();
    }

    public void setCountryRate(Country country, int centsPerBlock)
    {
        country.cityRatePerBlockCents = Math.max(0, centsPerBlock);
        setDirty();
    }

    public void setSalesTaxRate(Government government, int percent)
    {
        government.salesTaxRatePercent = Math.max(0, Math.min(100, percent));
        setDirty();
    }

    // Credits a government's treasury directly - used for sales tax (see business.SalesTax), mirroring how
    // rent/fines already land straight in balanceCents elsewhere in this class.
    public void addIncome(Government government, int cents)
    {
        if (cents <= 0)
            return;
        government.balanceCents += cents;
        setDirty();
    }

    // ---------- commerce: licenses, tariffs, diplomacy, civic spending ----------

    public void setForeignTaxSurcharge(Government government, int percent)
    {
        government.foreignTaxSurchargePercent = Math.max(0, Math.min(100, percent));
        setDirty();
    }

    public void setLicenseFee(Government government, int cents)
    {
        government.licenseFeeCents = Math.max(0, cents);
        setDirty();
    }

    public void setSanctioned(Government government, Government target, boolean sanctioned)
    {
        if (sanctioned)
            government.sanctionedGovernmentIds.add(target.id);
        else
            government.sanctionedGovernmentIds.remove(target.id);
        setDirty();
    }

    public void setAllied(Government government, Government target, boolean allied)
    {
        if (allied)
            government.alliedGovernmentIds.add(target.id);
        else
            government.alliedGovernmentIds.remove(target.id);
        setDirty();
    }

    // Looks a government up by id regardless of whether it's a City or a Country - the two id namespaces
    // never collide in practice (both random UUIDs), but there was previously no single lookup spanning both.
    public Government getGovernmentById(UUID id)
    {
        Government city = cities.get(id);
        if (city != null)
            return city;
        return countries.get(id);
    }

    // Every government whose territory a player personally holds a LandClaim in - the "citizenship" this mod
    // uses for resident-vs-foreign tax rates, sanctions, and the diplomatic discount, derived entirely from
    // existing claim data rather than a separate membership roster (there is no such roster anywhere else).
    public Set<Government> residentGovernments(UUID ownerId)
    {
        Set<Government> result = new LinkedHashSet<>();
        List<LandClaim> claims = getLandClaimsOwnedBy(ownerId);
        if (claims.isEmpty())
            return result;
        for (City city : cities.values())
            for (LandClaim claim : claims)
                if (regionsOverlap(city.region, claim.region))
                {
                    result.add(city);
                    break;
                }
        for (Country country : countries.values())
            for (LandClaim claim : claims)
                if (regionsOverlap(country.region, claim.region))
                {
                    result.add(country);
                    break;
                }
        return result;
    }

    // A subsidy: an official spends straight out of the government's own treasury into a business's
    // balance, capped by what the treasury actually holds. See web.TerritoryHandler's Commerce section.
    public boolean grantSubsidy(MinecraftServer server, Government government, Business business, int cents)
    {
        if (cents <= 0 || government.balanceCents < cents)
            return false;
        government.balanceCents -= cents;
        setDirty();
        BusinessData.get(server).adjustBalance(business, cents);
        return true;
    }

    // A permanent reputation badge (see Business.endorsements) plus a one-off, extra-long visibility boost -
    // paid for out of the treasury, same risk-free-if-you-can-afford-it shape as a subsidy.
    public boolean endorseBusiness(MinecraftServer server, Government government, Business business)
    {
        if (government.balanceCents < endorsementCostCents)
            return false;
        government.balanceCents -= endorsementCostCents;
        business.endorsements.add(government.name);
        setDirty();
        BusinessData data = BusinessData.get(server);
        data.runAdvert(business, data.advertDurationHours() * 4);
        return true;
    }

    // A city/country-wide "festival": pays festivalCostCents out of the treasury once, then runs the normal
    // paid-advertising boost (see business.BusinessData.runAdvert) for every business currently taxed by this
    // government (i.e. with a Sell Barrel somewhere inside its territory - see business.SalesTax).
    public boolean runFestival(MinecraftServer server, Government government, int hours)
    {
        if (government.balanceCents < festivalCostCents)
            return false;
        government.balanceCents -= festivalCostCents;
        setDirty();

        BusinessData data = BusinessData.get(server);
        for (Business business : data.getAll())
            if (SalesTax.applicableGovernments(server, business).contains(government))
                data.runAdvert(business, hours);
        return true;
    }

    // An audit: pays auditCostCents up front (a real risk even if it finds nothing), then checks whether the
    // named business has no barrel actually inside this government's territory but at least one suspiciously
    // close to its border (see distanceToRegion) - read as dodging tax by placing just outside the line. If
    // caught, fines the business a percentage of its last-7-days revenue straight into this treasury.
    public String auditBusiness(MinecraftServer server, Government government, Business business)
    {
        if (government.balanceCents < auditCostCents)
            return "Not enough in the treasury to fund an audit (" + auditCostCents + " needed).";
        government.balanceCents -= auditCostCents;
        setDirty();

        boolean anyInside = false;
        boolean anySuspicious = false;
        for (BarrelPos barrel : business.barrels)
        {
            if (government.region.contains(barrel.dimension(), barrel.pos().getX(), barrel.pos().getZ()))
            {
                anyInside = true;
                continue;
            }
            if (distanceToRegion(government.region, barrel.dimension(), barrel.pos().getX(), barrel.pos().getZ()) <= 32)
                anySuspicious = true;
        }

        if (anyInside || !anySuspicious)
            return "Audit found nothing suspicious - " + auditCostCents + " spent.";

        long now = System.currentTimeMillis();
        long sevenDays = 7L * 24 * 60 * 60 * 1000;
        long recentRevenue = business.orders.stream().filter(o -> o.timestampMillis >= now - sevenDays).mapToLong(o -> o.totalCents).sum();
        int fine = (int) Math.round(recentRevenue * auditFinePercent / 100.0);
        if (fine > 0)
        {
            BusinessData.get(server).adjustBalance(business, -fine);
            government.balanceCents += fine;
            setDirty();
        }
        return "Audit caught " + business.name + " dodging tax at the border - fined " + fine + " cents to the treasury.";
    }

    // Chebyshev distance from a point to the nearest edge of a region's bounding rectangles - "how close is
    // this to being inside" rather than an exact polygon distance, which is plenty for an audit heuristic.
    private static int distanceToRegion(ClaimRegion region, ResourceKey<Level> dim, int x, int z)
    {
        if (!dim.equals(region.dimension) || region.pieces.isEmpty())
            return Integer.MAX_VALUE;
        int best = Integer.MAX_VALUE;
        for (Rect r : region.pieces)
        {
            int dx = Math.max(0, Math.max(r.minX() - x, x - r.maxX()));
            int dz = Math.max(0, Math.max(r.minZ() - z, z - r.maxZ()));
            best = Math.min(best, Math.max(dx, dz));
        }
        return best;
    }

    // Designates an existing, still player-owned business as affiliated with a government - see
    // Business.stateAffiliationGovernmentId. A percentage of every sale then flows to that government's
    // treasury on top of whatever it would otherwise tax the business (see business.SalesTax), regardless of
    // whether the business even operates inside its territory. Pass percent 0 (or a different government) to
    // change/clear the affiliation - only one government can be affiliated with a business at a time.
    public void setStateAffiliation(MinecraftServer server, Business business, Government government, int percent)
    {
        business.stateAffiliationGovernmentId = percent > 0 ? government.id : null;
        business.stateProfitSharePercent = Math.max(0, Math.min(100, percent));
        BusinessData.get(server).setDirty();
    }

    public void addPublicSquare(Government government, PublicSquare square)
    {
        government.publicSquares.add(square);
        setDirty();
    }

    public boolean removePublicSquare(Government government, int index)
    {
        if (index < 0 || index >= government.publicSquares.size())
            return false;
        government.publicSquares.remove(index);
        setDirty();
        return true;
    }

    // ---------- country laws & permits ----------

    // Any change here bumps lawVersion, which is how currently-present players get flagged for an immediate
    // re-prompt and returning players get told "rules changed since you last agreed".

    public void setLawEnabled(Country country, CountryLaw law, boolean enabled)
    {
        country.laws.get(law).enabled = enabled;
        country.lawVersion++;
        setDirty();
    }

    public void configureLaw(Country country, CountryLaw law, int fineCents, UUID jailCellId, int jailMinutes, int quotaCents)
    {
        LawConfig config = country.laws.get(law);
        config.fineCents = Math.max(0, fineCents);
        config.jailCellId = jailCellId;
        config.jailMinutes = Math.max(0, jailMinutes);
        config.quotaCents = Math.max(0, quotaCents);
        country.lawVersion++;
        setDirty();
    }

    public void grantPermit(Country country, UUID playerId, CountryLaw law)
    {
        country.permits.computeIfAbsent(playerId, key -> EnumSet.noneOf(CountryLaw.class)).add(law);
        country.lawVersion++;
        setDirty();
    }

    public boolean revokePermit(Country country, UUID playerId, CountryLaw law)
    {
        Set<CountryLaw> granted = country.permits.get(playerId);
        if (granted == null)
            return false;
        boolean removed = granted.remove(law);
        if (granted.isEmpty())
            country.permits.remove(playerId);
        if (removed)
        {
            country.lawVersion++;
            setDirty();
        }
        return removed;
    }

    public void setLawMaxY(Country country, int maxY)
    {
        country.lawMaxY = maxY;
        country.lawVersion++;
        setDirty();
    }

    public void addLawYOverrideZone(Country country, Rect zone)
    {
        country.lawYOverrideZones.add(zone);
        country.lawVersion++;
        setDirty();
    }

    public boolean removeLawYOverrideZone(Country country, int index)
    {
        if (index < 0 || index >= country.lawYOverrideZones.size())
            return false;
        country.lawYOverrideZones.remove(index);
        country.lawVersion++;
        setDirty();
        return true;
    }

    // Records that a player has agreed to a country's current rules - stops the border tick from flagging
    // them for a re-prompt until lawVersion next changes.
    public void agreeToLaws(Country country, UUID playerId)
    {
        country.agreedLawVersions.put(playerId, country.lawVersion);
        setDirty();
    }

    public void grantOfficial(Government government, UUID playerId, GovPermission permission)
    {
        government.officials.computeIfAbsent(playerId, key -> new LinkedHashSet<>()).add(permission);
        setDirty();
    }

    public boolean revokeOfficial(Government government, UUID playerId, GovPermission permission)
    {
        Set<GovPermission> granted = government.officials.get(playerId);
        if (granted == null)
            return false;
        boolean removed = granted.remove(permission);
        if (granted.isEmpty())
            government.officials.remove(playerId);
        if (removed)
            setDirty();
        return removed;
    }

    public boolean removeOfficial(Government government, UUID playerId)
    {
        boolean removed = government.officials.remove(playerId) != null;
        if (removed)
            setDirty();
        return removed;
    }

    // ---------- claim permissions ----------

    // Whether a player may perform an action on a land claim: the owner and anyone individually trusted with
    // this permission are always allowed; failing that, a city official (MANAGE_LAND_RULES) whose city has
    // opted into automatic access for this permission is allowed; failing that, the claim's own public rule
    // decides. REPORTABLE currently resolves the same as ALLOWED - see ClaimPermission/PermissionMode.
    public PermissionMode resolvePermission(LandClaim claim, UUID playerId, ClaimPermission permission)
    {
        if (claim.ownerId.equals(playerId))
            return PermissionMode.ALLOWED;

        Set<ClaimPermission> trustedPerms = claim.trusted.get(playerId);
        if (trustedPerms != null && trustedPerms.contains(permission))
            return PermissionMode.ALLOWED;

        for (City city : citiesOverlapping(claim.region))
        {
            if (city.officialAccess.contains(permission) && city.hasPermission(playerId, GovPermission.MANAGE_LAND_RULES))
                return PermissionMode.ALLOWED;
        }

        return claim.rules.getOrDefault(permission, PermissionMode.ALLOWED);
    }

    public boolean isAllowed(LandClaim claim, UUID playerId, ClaimPermission permission)
    {
        return resolvePermission(claim, playerId, permission) != PermissionMode.BLOCKED;
    }

    public void setRule(LandClaim claim, ClaimPermission permission, PermissionMode mode)
    {
        claim.rules.put(permission, mode);
        setDirty();
    }

    public void trustPlayer(LandClaim claim, UUID playerId, ClaimPermission permission)
    {
        claim.trusted.computeIfAbsent(playerId, key -> EnumSet.noneOf(ClaimPermission.class)).add(permission);
        setDirty();
    }

    public boolean untrustPlayer(LandClaim claim, UUID playerId)
    {
        boolean removed = claim.trusted.remove(playerId) != null;
        if (removed)
            setDirty();
        return removed;
    }

    public void setCityOfficialAccess(City city, ClaimPermission permission, boolean access)
    {
        if (access)
            city.officialAccess.add(permission);
        else
            city.officialAccess.remove(permission);
        setDirty();
    }

    // ---------- reports & jails ----------

    public Report submitReport(LandClaim claim, UUID reporterId, UUID offenderId, ClaimType governmentType,
            Government government, ClaimPermission permission, String reason)
    {
        Report report = new Report(UUID.randomUUID(), reporterId, offenderId, claim.id, governmentType, government.id, permission, reason);
        reports.put(report.id, report);
        setDirty();
        return report;
    }

    public Report getReport(UUID id)
    {
        return reports.get(id);
    }

    public List<Report> getPendingReportsFor(Government government)
    {
        List<Report> result = new ArrayList<>();
        for (Report report : reports.values())
            if (report.status == ReportStatus.PENDING && report.governmentId.equals(government.id))
                result.add(report);
        return result;
    }

    public LandClaim landClaimOf(Report report)
    {
        return landClaims.get(report.landClaimId);
    }

    public void denyReport(Report report)
    {
        report.status = ReportStatus.DENIED;
        setDirty();
    }

    // Applies a fine (split between the reporter and the government per victimSharePercent) and/or a jail
    // sentence to a report's offender, then marks it resolved. Doesn't teleport/confiscate anything itself -
    // that stays out of this class, since it's meant to hold data, not touch the world/players directly.
    public Sentence resolveReport(MinecraftServer server, Government government, Report report, int fineCents, JailCell cell, long jailMillis, int quotaCents)
    {
        report.status = ReportStatus.RESOLVED;

        if (fineCents > 0)
        {
            MoneyData moneyData = MoneyData.get(server);
            int actualFine = Math.min(fineCents, moneyData.getMoney(report.offenderId));
            if (actualFine > 0)
            {
                moneyData.addMoney(server, report.offenderId, -actualFine);
                int reporterShare = actualFine * government.victimSharePercent / 100;
                moneyData.addMoney(server, report.reporterId, reporterShare);
                government.balanceCents += actualFine - reporterShare;
            }
        }

        Sentence sentence = null;
        if (cell != null || quotaCents > 0)
        {
            long releaseAt = jailMillis > 0 ? System.currentTimeMillis() + jailMillis : 0;
            sentence = new Sentence(report.offenderId, cell != null ? cell.id : null, releaseAt, quotaCents);
            sentences.put(report.offenderId, sentence);
        }

        setDirty();
        return sentence;
    }

    // Auto-applies a country law's pre-configured punishment the instant a violation is detected - no report,
    // no reporter, no manual review. The full fine goes to the country treasury (there's no victim to split
    // it with here, unlike resolveReport).
    public Sentence applyLawPunishment(MinecraftServer server, Country country, CountryLaw law, UUID offenderId)
    {
        LawConfig config = country.laws.get(law);
        if (config == null || !config.enabled)
            return null;

        if (config.fineCents > 0)
        {
            MoneyData moneyData = MoneyData.get(server);
            int actualFine = Math.min(config.fineCents, moneyData.getMoney(offenderId));
            if (actualFine > 0)
            {
                moneyData.addMoney(server, offenderId, -actualFine);
                country.balanceCents += actualFine;
            }
        }

        Sentence sentence = null;
        if (config.jailCellId != null || config.quotaCents > 0)
        {
            long releaseAt = config.jailMinutes > 0 ? System.currentTimeMillis() + config.jailMinutes * 60_000L : 0;
            sentence = new Sentence(offenderId, config.jailCellId, releaseAt, config.quotaCents);
            sentences.put(offenderId, sentence);
        }

        setDirty();
        return sentence;
    }

    public JailCell addJailCell(Government government, String name, ResourceKey<Level> dim, BlockPos pos, int radius, int maxCapacity)
    {
        JailCell cell = new JailCell(UUID.randomUUID(), name, dim, pos, radius, maxCapacity);
        government.jailCells.add(cell);
        setDirty();
        return cell;
    }

    public boolean removeJailCell(Government government, UUID cellId)
    {
        boolean removed = government.jailCells.removeIf(c -> c.id.equals(cellId));
        if (removed)
            setDirty();
        return removed;
    }

    public void setVictimShare(Government government, int percent)
    {
        government.victimSharePercent = Math.max(0, Math.min(100, percent));
        setDirty();
    }

    public long jailCellOccupancy(JailCell cell)
    {
        return sentences.values().stream().filter(s -> cell.id.equals(s.jailCellId)).count();
    }

    public Sentence getSentence(UUID playerId)
    {
        return sentences.get(playerId);
    }

    public void removeSentence(UUID playerId)
    {
        if (sentences.remove(playerId) != null)
            setDirty();
    }

    public List<Sentence> getAllSentences()
    {
        return new ArrayList<>(sentences.values());
    }

    // For JailListener to call after mutating a Sentence in place (e.g. mining quota progress) from outside
    // this class.
    public void markSentenceDirty()
    {
        setDirty();
    }

    // ---------- billing ----------

    // Charges every due land claim/city/country its weekly rent, cascading the money upward (land -> the
    // cities it overlaps -> the country the city is in -> the server admin), applying each tier's own
    // missed-payment policy when the payer can't afford it. Called periodically from the server tick.
    public void tickBilling(MinecraftServer server)
    {
        long now = System.currentTimeMillis();
        MoneyData moneyData = MoneyData.get(server);
        List<UUID> landToDissolve = new ArrayList<>();
        List<UUID> citiesToDissolve = new ArrayList<>();
        List<UUID> countriesToDissolve = new ArrayList<>();

        for (LandClaim claim : landClaims.values())
        {
            if (!isDue(claim.billing, now))
                continue;

            Map<City, Integer> breakdown = landRentBreakdown(claim.region);
            int due = breakdown.values().stream().mapToInt(Integer::intValue).sum();
            if (due == 0)
                continue;

            if (moneyData.getMoney(claim.ownerId) >= due)
            {
                moneyData.addMoney(server, claim.ownerId, -due);
                breakdown.forEach((city, cents) -> { city.balanceCents += cents; });
                advance(claim.billing, now);
                setDirty();
            }
            else
            {
                applyPolicy(ClaimType.LAND, claim.billing, now, () -> landToDissolve.add(claim.id));
            }
        }

        for (City city : cities.values())
        {
            if (!isDue(city.billing, now))
                continue;

            Country country = cityCountry(city.region);
            int due = cityRentToCountryCents(city.region, country);
            if (due == 0)
                continue;

            if (city.balanceCents >= due)
            {
                city.balanceCents -= due;
                country.balanceCents += due;
                advance(city.billing, now);
                setDirty();
            }
            else
            {
                applyPolicy(ClaimType.CITY, city.billing, now, () -> citiesToDissolve.add(city.id));
            }
        }

        for (Country country : countries.values())
        {
            if (!isDue(country.billing, now))
                continue;

            int due = countryRentToAdminCents(country);
            if (due == 0)
                continue;

            if (country.balanceCents >= due)
            {
                country.balanceCents -= due;
                advance(country.billing, now);
                setDirty();
            }
            else
            {
                applyPolicy(ClaimType.COUNTRY, country.billing, now, () -> countriesToDissolve.add(country.id));
            }
        }

        for (UUID id : landToDissolve)
            landClaims.remove(id);
        for (UUID id : citiesToDissolve)
        {
            City city = cities.remove(id);
            if (city != null && city.balanceCents > 0)
                moneyData.addMoney(server, city.founderId, city.balanceCents);
        }
        for (UUID id : countriesToDissolve)
        {
            Country country = countries.remove(id);
            if (country != null && country.balanceCents > 0)
                moneyData.addMoney(server, country.founderId, country.balanceCents);
        }
        if (!landToDissolve.isEmpty() || !citiesToDissolve.isEmpty() || !countriesToDissolve.isEmpty())
            setDirty();
    }

    private static boolean isDue(Billing billing, long now)
    {
        return billing.status != BillingStatus.SUSPENDED && billing.nextBillingAtMillis <= now;
    }

    private static void advance(Billing billing, long now)
    {
        billing.nextBillingAtMillis = now + BILLING_INTERVAL_MILLIS;
        billing.status = BillingStatus.ACTIVE;
        billing.graceDeadlineMillis = 0;
    }

    private void applyPolicy(ClaimType type, Billing billing, long now, Runnable dissolve)
    {
        switch (policyFor(type))
        {
            case DISSOLVE -> dissolve.run();
            case SUSPEND -> billing.status = BillingStatus.SUSPENDED;
            case GRACE_THEN_SUSPEND -> {
                if (billing.status != BillingStatus.GRACE)
                {
                    billing.status = BillingStatus.GRACE;
                    billing.graceDeadlineMillis = now + graceDaysFor(type) * 24L * 60 * 60 * 1000;
                }
                else if (now >= billing.graceDeadlineMillis)
                {
                    billing.status = BillingStatus.SUSPENDED;
                }
            }
        }
        setDirty();
    }

    // ---------- reactivation ----------

    public boolean reactivateLandClaim(MinecraftServer server, LandClaim claim)
    {
        Map<City, Integer> breakdown = landRentBreakdown(claim.region);
        int due = breakdown.values().stream().mapToInt(Integer::intValue).sum();
        MoneyData moneyData = MoneyData.get(server);
        if (moneyData.getMoney(claim.ownerId) < due)
            return false;

        moneyData.addMoney(server, claim.ownerId, -due);
        breakdown.forEach((city, cents) -> { city.balanceCents += cents; });
        advance(claim.billing, System.currentTimeMillis());
        setDirty();
        return true;
    }

    public boolean reactivateCity(City city)
    {
        Country country = cityCountry(city.region);
        int due = cityRentToCountryCents(city.region, country);
        if (city.balanceCents < due)
            return false;

        city.balanceCents -= due;
        if (country != null)
            country.balanceCents += due;
        advance(city.billing, System.currentTimeMillis());
        setDirty();
        return true;
    }

    public boolean reactivateCountry(Country country)
    {
        int due = countryRentToAdminCents(country);
        if (country.balanceCents < due)
            return false;

        country.balanceCents -= due;
        advance(country.billing, System.currentTimeMillis());
        setDirty();
        return true;
    }
}
