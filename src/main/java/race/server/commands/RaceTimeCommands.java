package race.server.commands;

import com.mojang.brigadier.arguments.LongArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.world.World;

import static net.minecraft.server.command.CommandManager.literal;
import static net.minecraft.server.command.CommandManager.argument;

/**
 * Команды для управления виртуальным временем персональных миров.
 * Работают с SlotTimeService вместо world.setTimeOfDay для fabric_race миров.
 */
public final class RaceTimeCommands {

    public static void register() {
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {
            dispatcher.register(
                literal("race")
                    .requires(src -> src.hasPermissionLevel(2))
                    .then(literal("time")
                        .then(literal("get").executes(RaceTimeCommands::get))
                        .then(literal("set")
                            .then(argument("value", StringArgumentType.string())
                                .executes(ctx -> setPreset(ctx, StringArgumentType.getString(ctx, "value"))))
                            .then(argument("ticks", LongArgumentType.longArg(0))
                                .executes(ctx -> setTicks(ctx, LongArgumentType.getLong(ctx, "ticks"))))
                        )
                        .then(literal("add")
                            .then(argument("ticks", LongArgumentType.longArg())
                                .executes(ctx -> addTicks(ctx, LongArgumentType.getLong(ctx, "ticks"))))
                        )
                        .then(literal("speed")
                            .then(argument("tpt", LongArgumentType.longArg(0)) // ticks-per-server-tick
                                .executes(ctx -> setSpeed(ctx, LongArgumentType.getLong(ctx, "tpt"))))
                        )
                        .then(literal("sync")
                            .executes(RaceTimeCommands::syncNow)
                        )
                        .then(literal("dawn")
                            .then(argument("ticks", LongArgumentType.longArg(1, 1200))
                                .executes(ctx -> smoothDawn(ctx, LongArgumentType.getLong(ctx, "ticks"))))
                            .executes(ctx -> smoothDawn(ctx, 60L)) // по умолчанию 60 тиков
                        )
                        .then(literal("test")
                            .executes(RaceTimeCommands::testTimeSystem)
                        )
                    )
            );
        });
    }

    private static boolean isRaceWorld(ServerWorld w) {
        return "fabric_race".equals(w.getRegistryKey().getValue().getNamespace());
    }

    private static int get(CommandContext<ServerCommandSource> ctx) {
        ServerWorld w = ctx.getSource().getWorld();
        if (isRaceWorld(w)) {
            long t = race.server.SlotTimeService.getTime(w.getRegistryKey());
            ctx.getSource().sendFeedback(() -> Text.literal("[Race] slot-time: " + t), false);
        } else {
            ctx.getSource().sendFeedback(() -> Text.literal("[Race] vanilla-time: " + w.getTimeOfDay()), false);
        }
        return 1;
    }

    private static int setPreset(CommandContext<ServerCommandSource> ctx, String val) {
        ServerWorld w = ctx.getSource().getWorld();
        long target;
        switch (val.toLowerCase()) {
            case "day" -> target = 1000L;
            case "noon" -> target = 6000L;
            case "night" -> target = 13000L;
            case "midnight" -> target = 18000L;
            default -> {
                ctx.getSource().sendError(Text.literal("[Race] Unknown preset: " + val));
                return 0;
            }
        }
        return setTicks(ctx, target);
    }

    private static int setTicks(CommandContext<ServerCommandSource> ctx, long ticks) {
        ServerWorld w = ctx.getSource().getWorld();
        if (isRaceWorld(w)) {
            long day = (race.server.SlotTimeService.getTime(w.getRegistryKey()) / 24000L) * 24000L;
            long next = day + (ticks % 24000L + 24000L) % 24000L;
            race.server.SlotTimeService.setTimeAndSnap(w, next);
            ctx.getSource().sendFeedback(() -> Text.literal("[Race] slot-time set: " + next), true);
        } else {
            w.setTimeOfDay(ticks);
            ctx.getSource().sendFeedback(() -> Text.literal("[Race] vanilla-time set: " + ticks), true);
        }
        return 1;
    }

    private static int addTicks(CommandContext<ServerCommandSource> ctx, long dt) {
        ServerWorld w = ctx.getSource().getWorld();
        if (isRaceWorld(w)) {
            long cur = race.server.SlotTimeService.getTime(w.getRegistryKey());
            long next = cur + dt;
            race.server.SlotTimeService.setTimeAndSnap(w, next);
            ctx.getSource().sendFeedback(() -> Text.literal("[Race] slot-time add: " + dt + " -> " + next), true);
        } else {
            long next = w.getTimeOfDay() + dt;
            w.setTimeOfDay(next);
            ctx.getSource().sendFeedback(() -> Text.literal("[Race] vanilla-time add: " + dt + " -> " + next), true);
        }
        return 1;
    }

    private static int setSpeed(CommandContext<ServerCommandSource> ctx, long tpt) {
        ServerWorld w = ctx.getSource().getWorld();
        if (tpt < 0) tpt = 0;
        if (isRaceWorld(w)) {
            race.server.SlotTimeService.setSpeed(w.getRegistryKey(), tpt);
            // Снапнем текущее время, чтобы клиенты мгновенно обновили базу интерполяции
            race.server.SlotTimeService.setTimeAndSnap(w, race.server.SlotTimeService.getTime(w.getRegistryKey()));
            long finalTpt = tpt;
            ctx.getSource().sendFeedback(() -> Text.literal("[Race] slot-speed: " + finalTpt + " ticks/tick"), true);
        } else {
            ctx.getSource().sendError(Text.literal("[Race] speed доступен только в fabric_race мирах"));
        }
        return 1;
    }

    private static int syncNow(CommandContext<ServerCommandSource> ctx) {
        ServerWorld w = ctx.getSource().getWorld();
        if (isRaceWorld(w)) {
            race.server.SlotTimeService.setTimeAndSnap(w, race.server.SlotTimeService.getTime(w.getRegistryKey()));
            ctx.getSource().sendFeedback(() -> Text.literal("[Race] slot-time synced"), true);
        } else {
            ctx.getSource().sendError(Text.literal("[Race] sync доступен только в fabric_race мирах"));
        }
        return 1;
    }

    private static int smoothDawn(CommandContext<ServerCommandSource> ctx, long ticks) {
        ServerWorld w = ctx.getSource().getWorld();
        if (isRaceWorld(w)) {
            race.server.SlotTimeService.skipToMorningSmooth(w, (int) ticks);
            ctx.getSource().sendFeedback(() -> Text.literal("[Race] Плавный рассвет за " + ticks + " тиков"), true);
        } else {
            ctx.getSource().sendError(Text.literal("[Race] dawn доступен только в fabric_race мирах"));
        }
        return 1;
    }

    private static int testTimeSystem(CommandContext<ServerCommandSource> ctx) {
        ServerWorld w = ctx.getSource().getWorld();
        if (isRaceWorld(w)) {
            long slotTime = race.server.SlotTimeService.getTime(w.getRegistryKey());
            long tod = slotTime % 24000L;
            boolean isDay = w.isDay();
            int darkness = w.getAmbientDarkness();
            float skyAngleRadians = w.getSkyAngleRadians(1.0f);
            
            ctx.getSource().sendFeedback(() -> Text.literal("[Race] Тест времени:")
                .append("\n  SLOT_TIME: " + slotTime)
                .append("\n  TimeOfDay: " + tod)
                .append("\n  isDay(): " + isDay)
                .append("\n  AmbientDarkness: " + darkness)
                .append("\n  SkyAngleRadians: " + String.format("%.3f", skyAngleRadians))
                .append("\n  DO_MOB_SPAWNING: " + w.getGameRules().get(net.minecraft.world.GameRules.DO_MOB_SPAWNING).get())
                .append("\n  DO_DAYLIGHT_CYCLE: " + w.getGameRules().get(net.minecraft.world.GameRules.DO_DAYLIGHT_CYCLE).get()), false);
        } else {
            ctx.getSource().sendFeedback(() -> Text.literal("[Race] Тест времени (ваниль):")
                .append("\n  TimeOfDay: " + w.getTimeOfDay())
                .append("\n  isDay(): " + w.isDay())
                .append("\n  AmbientDarkness: " + w.getAmbientDarkness()), false);
        }
        return 1;
    }

    private RaceTimeCommands() {}
}
