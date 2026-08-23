package ca.skynetcloud.sable_rtp.teleport;

import ca.skynetcloud.sable_rtp.Config;
import ca.skynetcloud.sable_rtp.Sable_rtp;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;
import net.minecraft.server.level.ServerPlayer;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;


@EventBusSubscriber(modid = Sable_rtp.MODID)
public class RtpWarmupManager {

    private static final double MOVEMENT_CANCEL_THRESHOLD_SQ = 0.25;

    private static final Map<UUID, PendingTeleport> PENDING = new HashMap<>();

    private record PendingTeleport(long readyAtMillis, Vec3 startPos, Runnable onComplete) {
    }

    public static void schedule(ServerPlayer player, Runnable onComplete) {
        int warmupSeconds = Config.getTeleportWarmupSeconds();
        if (warmupSeconds <= 0) {
            onComplete.run();
            return;
        }

        long readyAt = System.currentTimeMillis() + (warmupSeconds * 1000L);
        PENDING.put(player.getUUID(), new PendingTeleport(readyAt, player.position(), onComplete));

        player.displayClientMessage(
                Component.literal("Teleporting in " + warmupSeconds + "s... don't move!")
                        .withStyle(ChatFormatting.YELLOW),
                true
        );
    }

    public static boolean hasPending(UUID playerId) {
        return PENDING.containsKey(playerId);
    }

    @SubscribeEvent
    public static void onPlayerTick(PlayerTickEvent.Post event) {
        Player entity = event.getEntity();
        if (entity.level().isClientSide) return;
        if (!(entity instanceof ServerPlayer player)) return;

        PendingTeleport pending = PENDING.get(player.getUUID());
        if (pending == null) return;

        if (player.position().distanceToSqr(pending.startPos()) > MOVEMENT_CANCEL_THRESHOLD_SQ) {
            PENDING.remove(player.getUUID());
            player.displayClientMessage(
                    Component.literal("Teleport cancelled — you moved.").withStyle(ChatFormatting.RED),
                    true
            );
            return;
        }

        long now = System.currentTimeMillis();
        if (now >= pending.readyAtMillis()) {
            PENDING.remove(player.getUUID());
            pending.onComplete().run();
        } else {
            long secondsLeft = (pending.readyAtMillis() - now + 999) / 1000L;
            player.displayClientMessage(
                    Component.literal("Teleporting in " + secondsLeft + "...").withStyle(ChatFormatting.YELLOW),
                    true
            );
        }
    }

    @SubscribeEvent
    public static void onPlayerLoggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.getEntity() != null) {
            PENDING.remove(event.getEntity().getUUID());
        }
    }
}