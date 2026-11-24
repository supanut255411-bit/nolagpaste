package dev.bigpaste;

import static net.minecraft.server.command.CommandManager.argument;
import static net.minecraft.server.command.CommandManager.literal;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Stream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.command.CommandSource;
import net.minecraft.command.argument.BlockPosArgumentType;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;

/**
 * /bigpaste start "<file>" [<x y z>] [air]   paste a .litematic from <gamedir>/schematics
 * /bigpaste status
 * /bigpaste cancel
 */
public final class BigPasteMod implements ModInitializer {
    public static final String ID = "bigpaste";
    public static final Logger LOG = LoggerFactory.getLogger(ID);

    /** The single active job. Nulled as soon as it finishes so it can never pin a ServerWorld. */
    private static PasteJob job;

    @Override
    public void onInitialize() {
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> register(dispatcher));
        ServerTickEvents.END_SERVER_TICK.register(BigPasteMod::tick);
        ServerLifecycleEvents.SERVER_STARTING.register(server -> PasteJob.cleanSpoolDir());
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
            if (job != null) {
                job.dispose();
                job = null;
            }
        });
    }

    private static void tick(MinecraftServer server) {
        PasteJob j = job;
        if (j == null) return;
        j.tick();
        if (j.isFinished()) {
            j.dispose();
            job = null;
        }
    }

    /**
     * Singleplayer owner or console only. To allow ops on a dedicated server, swap this for your
     * version's permission check (the API changed in 1.21.9+).
     */
    private static boolean allowed(ServerCommandSource src) {
        return src.getServer().isSingleplayer() || src.getPlayer() == null;
    }

    private static void register(CommandDispatcher<ServerCommandSource> d) {
        d.register(literal("bigpaste")
                .requires(BigPasteMod::allowed)
                .then(literal("start")
                        .then(argument("file", StringArgumentType.string())
                                .suggests(BigPasteMod::suggestFiles)
                                .executes(c -> start(c, null, false))
                                .then(argument("pos", BlockPosArgumentType.blockPos())
                                        .executes(c -> start(c, BlockPosArgumentType.getBlockPos(c, "pos"), false))
                                        .then(literal("air")
                                                .executes(c -> start(c, BlockPosArgumentType.getBlockPos(c, "pos"), true))))))
                .then(literal("status").executes(c -> {
                    String s = job == null ? "No paste running." : job.statusLine();
                    c.getSource().sendFeedback(() -> Text.literal(s), false);
                    return 1;
                }))
                .then(literal("cancel").executes(c -> {
                    if (job == null) {
                        c.getSource().sendError(Text.literal("No paste running."));
                        return 0;
                    }
                    job.cancel();
                    c.getSource().sendFeedback(() -> Text.literal("Cancelling..."), false);
                    return 1;
                })));
    }

    private static int start(CommandContext<ServerCommandSource> ctx, BlockPos pos, boolean replaceAir) {
        ServerCommandSource src = ctx.getSource();
        if (job != null) {
            src.sendError(Text.literal("A paste is already running. Use /bigpaste cancel first."));
            return 0;
        }
        Path file = resolveSchematic(StringArgumentType.getString(ctx, "file"));
        if (file == null) {
            src.sendError(Text.literal("File not found in " + schematicsDir() + " (.litematic)."));
            return 0;
        }
        ServerPlayerEntity player = src.getPlayer();
        BlockPos origin = pos != null ? pos : (player != null ? player.getBlockPos() : null);
        if (origin == null) {
            src.sendError(Text.literal("Give a position: /bigpaste start \"file\" <x y z>"));
            return 0;
        }
        job = new PasteJob(src.getServer(), src.getWorld(), file, origin, replaceAir,
                player != null ? player.getUuid() : null);
        job.startParsing();
        src.sendFeedback(() -> Text.literal("BigPaste: streaming " + file.getFileName()
                + " to " + origin.getX() + " " + origin.getY() + " " + origin.getZ()
                + " (lowest corner of the schematic)."), false);
        return 1;
    }

    static Path schematicsDir() {
        return FabricLoader.getInstance().getGameDir().resolve("schematics");
    }

    private static Path resolveSchematic(String name) {
        Path dir = schematicsDir().toAbsolutePath().normalize();
        if (!name.endsWith(".litematic")) name += ".litematic";
        Path p = dir.resolve(name).normalize();
        if (!p.startsWith(dir)) return null; // no path traversal
        return Files.isRegularFile(p) ? p : null;
    }

    private static CompletableFuture<Suggestions> suggestFiles(CommandContext<ServerCommandSource> c,
                                                               SuggestionsBuilder b) {
        try (Stream<Path> s = Files.list(schematicsDir())) {
            List<String> names = s.map(p -> p.getFileName().toString())
                    .filter(n -> n.endsWith(".litematic"))
                    .map(n -> "\"" + n + "\"")
                    .toList();
            return CommandSource.suggestMatching(names, b);
        } catch (IOException e) {
            return Suggestions.empty();
        }
    }
}
