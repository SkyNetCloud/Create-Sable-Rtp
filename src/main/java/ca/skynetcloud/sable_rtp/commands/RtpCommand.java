package ca.skynetcloud.sable_rtp.commands;

import ca.skynetcloud.sable_rtp.Config;
import ca.skynetcloud.sable_rtp.teleport.RtpWarmupManager;
import ca.skynetcloud.sable_rtp.teleport.SubLevelTeleporter;
import ca.skynetcloud.sable_rtp.utils.SubLevelUtils;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.context.CommandContext;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.sublevel.SubLevel;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
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

import static ca.skynetcloud.sable_rtp.Sable_rtp.MODID;
import static ca.skynetcloud.sable_rtp.utils.SubLevelUtils.*;
import static net.minecraft.commands.Commands.literal;


@EventBusSubscriber(modid = MODID)
public class RtpCommand {

    private static final int COOLDOWN_BYPASS_LEVEL = 2;
    private static final Map<UUID, Long> lastDestinationTime = new HashMap<>();


    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(literal("sablertp").requires(src -> src.hasPermission(0)).executes(
                RtpCommand::rtpExecution
        ));
    }

    private static int rtpExecution(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        ServerPlayer serverPlayer = source.getPlayer();
        ServerLevel serverLevel = source.getLevel();

        assert serverPlayer != null;
        if (RtpWarmupManager.hasPending(serverPlayer.getUUID())) {
            source.sendFailure(Component.translatable("message.pending.text"));
        }

        if (!source.hasPermission(COOLDOWN_BYPASS_LEVEL)) {
            long remaining = remainingTimeInSeconds(serverPlayer);
            if (remaining > 0) {
                source.sendFailure(Component.translatable( "message.wait.text",fixDurationFormat(remaining) ).withStyle(ChatFormatting.RED));
                return 0;
            }
        }
        SubLevel subLevel = SubLevelUtils.resolve(serverPlayer);

        if (!(subLevel instanceof ServerSubLevel serverSubLevel)) {
            source.sendFailure(Component.translatable("message.notserversided.text"));
            return 0;
        }

        if (isInWater(serverSubLevel)) {
            source.sendFailure(Component.translatable("message.noallowedwatership.text"));
            return 0;
        }

        boolean isAirship = isAirborne(serverSubLevel);

        source.sendSuccess(() -> Component.translatable("message.findingdestination.text").withStyle(ChatFormatting.GRAY), false);

        int[] attempts = new int[1];
        BlockPos destination = locateSafeTeleportPos(serverLevel, serverSubLevel, isAirship, attempts);

        if (destination == null) {
            source.sendFailure(Component.translatable("message.notsafe.text"));
            return 0;
        }

        preloadChucksSystem(serverLevel,destination);

        int attemptsUsed = attempts[0];
        RtpWarmupManager.schedule(serverPlayer, () -> completingTeleport(serverPlayer, serverSubLevel, serverLevel, destination, attemptsUsed));


        return 1;
    }

    private static void completingTeleport(ServerPlayer serverPlayer, ServerSubLevel serverSubLevel, ServerLevel serverLevel, BlockPos destination, int attemptsUsed) {
      boolean okay = SubLevelTeleporter.teleport(serverSubLevel, serverLevel, destination);

      if (!okay) {
          serverPlayer.displayClientMessage(Component.translatable("message.teleportfailed.text").withStyle(ChatFormatting.RED), false);
          return;
      }
       lastDestinationTime.put(serverPlayer.getUUID(), System.currentTimeMillis());

      String destinationMsg = String.format("[x %d, y %d, z %d]", destination.getX(), destination.getY(), destination.getZ());
      serverPlayer.displayClientMessage(Component.translatable("message.destinationFound.text", attemptsUsed, destinationMsg).withStyle(ChatFormatting.GOLD), false);

    }

    private static Long remainingTimeInSeconds(ServerPlayer serverPlayer) {
        Long lastUsed = lastDestinationTime.get(serverPlayer.getUUID());
        if (lastUsed == null) return 0L;

        long elapsedTimeInSeconds = System.currentTimeMillis() - lastUsed;
        long remainingTime = Config.getTeleportCooldownSeconds() - elapsedTimeInSeconds;

        return Math.max(0L, remainingTime);
    }

    private static String fixDurationFormat(long totalSeconds) {
        long mins = totalSeconds / 60;
        long seconds = totalSeconds % 60;
        return mins > 0 ? (mins + "m " + seconds + "s") : (seconds + "s");
    }

    private static void preloadChucksSystem(ServerLevel level, BlockPos pos) {
        int chunkX = pos.getX() >> 4;
        int chunkZ = pos.getZ() >> 4;
        int radiusOverall = 3;

        for (int cx = -radiusOverall; cx <= radiusOverall; cx++) {
            for (int cz = -radiusOverall; cz <= radiusOverall; cz++) {
                level.getChunk(chunkX + cx, cz + chunkZ);
            }
        }
    }

    @SubscribeEvent
    public static void onPlayerLeave(PlayerEvent.PlayerLoggedOutEvent event) {
        lastDestinationTime.remove(event.getEntity().getUUID());
    }

}