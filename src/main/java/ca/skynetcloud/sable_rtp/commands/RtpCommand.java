package ca.skynetcloud.sable_rtp.commands;

import ca.skynetcloud.sable_rtp.Config;
import ca.skynetcloud.sable_rtp.Sable_rtp;
import ca.skynetcloud.sable_rtp.teleport.RtpWarmupManager;
import ca.skynetcloud.sable_rtp.teleport.SubLevelTeleporter;
import ca.skynetcloud.sable_rtp.utils.SubLevelUtils;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.sublevel.SubLevel;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static ca.skynetcloud.sable_rtp.utils.SubLevelUtils.*;

@EventBusSubscriber(modid = Sable_rtp.MODID)
public class RtpCommand {

    private static final int COOLDOWN_BYPASS_PERMISSION_LEVEL = 2;
    private static final Map<UUID, Long> lastTeleportTime = new HashMap<>();

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(
                Commands.literal("sablertp")
                        .requires(src -> src.hasPermission(0))
                        .executes(RtpCommand::handleRtpCommandExecution)
        );
    }

    private static int handleRtpCommandExecution(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        CommandSourceStack source = ctx.getSource();
        ServerPlayer player = source.getPlayerOrException();
        ServerLevel level = source.getLevel();

        if (RtpWarmupManager.hasPending(player.getUUID())) {
            source.sendFailure(Component.literal("You already have a teleport pending!"));
            return 0;
        }

        if (!source.hasPermission(COOLDOWN_BYPASS_PERMISSION_LEVEL)) {
            long remaining = remainingCooldownSeconds(player);
            if (remaining > 0) {
                source.sendFailure(Component.literal(
                        "You must wait " + formatDuration(remaining) + " before using /sablertp again."
                ).withStyle(ChatFormatting.RED));
                return 0;
            }
        }

        SubLevel subLevel = SubLevelUtils.resolve(player);
        if (subLevel == null) {
            source.sendFailure(Component.literal("You need to be sitting on (or standing on) your contraption to /sablertp it."));
            return 0;
        }

        if (!(subLevel instanceof ServerSubLevel serverSubLevel)) {
            source.sendFailure(Component.literal("That contraption isn't server-side, can't teleport it."));
            return 0;
        }

        if (isInWater(serverSubLevel)) {
            source.sendFailure(Component.literal("You cannot RTP a water ship! Only airships and ground vehicles can be teleported."));
            return 0;
        }

        boolean isSableAirship = isAirborne(serverSubLevel);

        source.sendSuccess(() -> Component.literal("Searching for a safe location...").withStyle(ChatFormatting.GRAY), false);

        int[] attempts = new int[1];
        BlockPos dest = locateSafeTeleportPos(level, serverSubLevel, isSableAirship, attempts);

        if (dest == null) {
            source.sendFailure(Component.literal("Couldn't find a safe destination, try again."));
            return 0;
        }

        preloadChunks(level, dest, 3);

        int attemptsUsed = attempts[0];
        RtpWarmupManager.schedule(player, () ->
                finishTeleport(player, serverSubLevel, level, dest, attemptsUsed));

        return 1;
    }

    private static void finishTeleport(ServerPlayer player, ServerSubLevel serverSubLevel,
                                       ServerLevel level, BlockPos dest, int attemptsUsed) {
        // Simply call teleport - it handles everything internally
        boolean ok = SubLevelTeleporter.teleport(serverSubLevel, level, dest);

        if (!ok) {
            player.displayClientMessage(Component.literal("Teleport failed.").withStyle(ChatFormatting.RED), false);
            return;
        }

        lastTeleportTime.put(player.getUUID(), System.currentTimeMillis());

        String locationMsg = String.format("[x %d, y %d, z %d]", dest.getX(), dest.getY(), dest.getZ());
        player.displayClientMessage(Component.literal(
                "Whoosh! Found a good location after " + attemptsUsed + " attempt(s) @ " + locationMsg
        ).withStyle(ChatFormatting.GREEN), false);
    }

    private static long remainingCooldownSeconds(ServerPlayer player) {
        Long lastUse = lastTeleportTime.get(player.getUUID());
        if (lastUse == null) return 0L;

        long elapsedSeconds = (System.currentTimeMillis() - lastUse) / 1000L;
        long remaining = Config.getTeleportCooldownSeconds() - elapsedSeconds;
        return Math.max(0L, remaining);
    }

    private static String formatDuration(long totalSeconds) {
        long minutes = totalSeconds / 60;
        long seconds = totalSeconds % 60;
        return minutes > 0 ? (minutes + "m " + seconds + "s") : (seconds + "s");
    }

    private static void preloadChunks(ServerLevel level, BlockPos center, int radius) {
        int centerChunkX = center.getX() >> 4;
        int centerChunkZ = center.getZ() >> 4;

        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                level.getChunk(centerChunkX + dx, centerChunkZ + dz);
            }
        }
    }

    @SubscribeEvent
    public static void onPlayerLoggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.getEntity() != null) {
            lastTeleportTime.remove(event.getEntity().getUUID());
        }
    }
}