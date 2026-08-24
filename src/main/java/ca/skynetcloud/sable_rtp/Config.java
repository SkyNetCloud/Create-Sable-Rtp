package ca.skynetcloud.sable_rtp;

import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.event.config.ModConfigEvent;
import net.neoforged.neoforge.common.ModConfigSpec;

@EventBusSubscriber(modid = Sable_rtp.MODID)
public class Config {
    private static final ModConfigSpec.Builder BUILDER = new ModConfigSpec.Builder();

    private static final ModConfigSpec.IntValue TELEPORT_SEARCH_RADIUS = BUILDER.comment("Maximum radius (in blocks) to search for a valid teleport destination.").defineInRange("teleportSearchRadius", 5000, 1, 30000000);
    private static final ModConfigSpec.IntValue MAX_LOCATION_LOOKUP_ATTEMPTS = BUILDER.comment("Maximum number of attempts to find a safe teleport location before giving up.").defineInRange("maxLocationLookupAttempts", 30, 1, 1000);
    private static final ModConfigSpec.IntValue GROUND_CLEARANCE = BUILDER.comment("Minimum vertical clearance (in blocks) required above ground for a ground-vehicle teleport.").defineInRange("groundClearance", 2, 0, 32);
    private static final ModConfigSpec.IntValue AIRSHIP_MIN_HEIGHT = BUILDER.comment("Minimum Y height an airship may be teleported to.").defineInRange("airshipMinHeight", 40, -2032, 2031);
    private static final ModConfigSpec.IntValue AIRSHIP_MAX_HEIGHT = BUILDER.comment("Maximum Y height an airship may be teleported to.").defineInRange("airshipMaxHeight", 150, -2032, 2031);
    private static final ModConfigSpec.IntValue TELEPORT_COOLDOWN_SECONDS = BUILDER.comment("Cooldown, in seconds, between uses of /sablertp per player.").defineInRange("teleportCooldownSeconds", 600, 0, Integer.MAX_VALUE);
    private static final ModConfigSpec.IntValue TELEPORT_WARMUP_SECONDS = BUILDER.comment("Delay, in seconds, after a safe destination is found before the player is actually teleported.", "The teleport is cancelled (with no cooldown penalty) if the player moves during this delay.", "Set to 0 to teleport instantly.").defineInRange("teleportWarmupSeconds", 3, 0, 3600);

    static final ModConfigSpec SPEC = BUILDER.build();

    public static int getTeleportSearchRadius() {
        return TELEPORT_SEARCH_RADIUS.get();
    }

    public static int getMaxLocationLookupAttempts() {
        return MAX_LOCATION_LOOKUP_ATTEMPTS.get();
    }

    public static int getGroundClearance() {
        return GROUND_CLEARANCE.get();
    }

    public static int getAirshipMinHeight() {
        return AIRSHIP_MIN_HEIGHT.get();
    }

    public static int getAirshipMaxHeight() {
        return AIRSHIP_MAX_HEIGHT.get();
    }

    public static int getTeleportCooldownSeconds() {
        return TELEPORT_COOLDOWN_SECONDS.get();
    }

    public static int getTeleportWarmupSeconds() {
        return TELEPORT_WARMUP_SECONDS.get();
    }

    @SubscribeEvent
    static void onLoad(final ModConfigEvent event) {

    }
}