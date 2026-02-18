package com.example.addon.commands;

import com.mojang.brigadier.arguments.IntegerArgumentType;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.example.addon.modules.GridTorch;
import meteordevelopment.meteorclient.commands.Command;
import meteordevelopment.meteorclient.systems.modules.Modules;
import net.minecraft.command.CommandSource;

/**
 * Alias command so users can use `.gt` as a short form of `.gridtorch`.
 * Behaviour mirrors `CommandGridTorch` (toggle/start/stop/status/preview/etc.).
 */
public class CommandGt extends Command {
    public CommandGt() {
        super("gt", "Alias for gridtorch (short form)");
    }

    @Override
    public void build(LiteralArgumentBuilder<CommandSource> builder) {
        // Toggle when no args
        builder.executes(ctx -> {
            GridTorch m = Modules.get().get(GridTorch.class);
            if (m == null) { info("GridTorch module not found"); return SINGLE_SUCCESS; }

            if (m.isActive()) m.toggle(); else m.toggle();
            info("GridTorch toggled: " + (m.isActive() ? "enabled" : "disabled"));
            return SINGLE_SUCCESS;
        });

        // Reuse the same subcommands as CommandGridTorch (start/stop/status/preview/etc.)
        // start
        builder.then(literal("start").executes(c -> {
            GridTorch m = Modules.get().get(GridTorch.class);
            if (m == null) { info("GridTorch module not found"); return SINGLE_SUCCESS; }

            if (m.isActive() && m.isPreviewOnly()) {
                m.stopPreview();
                m.toggle();
            } else if (!m.isActive()) {
                m.toggle();
            }

            if (m.isActive()) {
                int remaining = m.getRemainingPlacements();
                int planned = m.getPlannedTorchCount();
                if (remaining > 0) info("GridTorch started — remaining=" + remaining + " (total planned=" + planned + ")");
                else info("GridTorch started — total planned=" + planned);
            } else {
                info("GridTorch could not start — no valid torch positions (check settings)");
            }

            return SINGLE_SUCCESS;
        }));

        // stop
        builder.then(literal("stop").executes(c -> {
            GridTorch m = Modules.get().get(GridTorch.class);
            if (m == null) { info("GridTorch module not found"); return SINGLE_SUCCESS; }

            try { m.cancelBaritonePath(); } catch (Throwable ignored) {}

            if (m.isActive()) m.toggle();
            info("GridTorch stopped (Baritone cancelled)");
            return SINGLE_SUCCESS;
        }));

        // status
        builder.then(literal("status").executes(c -> {
            GridTorch m = Modules.get().get(GridTorch.class);
            if (m == null) { info("GridTorch module not found"); return SINGLE_SUCCESS; }

            info(String.format("GridTorch — active=%s previewing=%s xSpacing=%d zSpacing=%d maxDistX=%d maxDistZ=%d maxDip=%d minLight=%d water=%s breakGrass=%s breakTree=%s avoidPlayerBlocks=%s avoidTorchesNearby=%s avoidTorchesRadius=%d spiralTraversal=%s",
                m.isActive(), m.isPreviewing(), m.xSpacing.get(), m.zSpacing.get(), m.maxDistX.get(), m.maxDistZ.get(), m.maxDip.get(), m.minLightLevel.get(), m.stopOnWater.get(), m.breakGrass.get(), m.breakTree.get(), m.avoidPlayerBlocks.get(), m.avoidTorchesNearby.get(), m.avoidTorchesRadius.get(), m.spiralTraversal.get()));
            return SINGLE_SUCCESS;
        }));

        // Optional: set spacing then start
        builder.then(argument("xSpacing", IntegerArgumentType.integer(1)).then(argument("zSpacing", IntegerArgumentType.integer(1))
            .executes(ctx -> {
                int xs = IntegerArgumentType.getInteger(ctx, "xSpacing");
                int zs = IntegerArgumentType.getInteger(ctx, "zSpacing");

                GridTorch m = Modules.get().get(GridTorch.class);
                if (m == null) { info("GridTorch module not found"); return SINGLE_SUCCESS; }

                m.xSpacing.set(xs);
                m.zSpacing.set(zs);

                if (!m.isActive()) m.toggle();

                if (m.isActive()) info("GridTorch started — xSpacing=" + xs + " zSpacing=" + zs + " — will place " + m.getPlannedTorchCount() + " torches");
                else info("GridTorch could not start — check settings");
                return SINGLE_SUCCESS;
            })
            .then(argument("maxDistX", IntegerArgumentType.integer(1)).then(argument("maxDistZ", IntegerArgumentType.integer(1)).executes(ctx -> {
                int xs = IntegerArgumentType.getInteger(ctx, "xSpacing");
                int zs = IntegerArgumentType.getInteger(ctx, "zSpacing");
                int mdx = IntegerArgumentType.getInteger(ctx, "maxDistX");
                int mdz = IntegerArgumentType.getInteger(ctx, "maxDistZ");

                GridTorch m = Modules.get().get(GridTorch.class);
                if (m == null) { info("GridTorch module not found"); return SINGLE_SUCCESS; }

                m.xSpacing.set(xs);
                m.zSpacing.set(zs);
                m.maxDistX.set(mdx);
                m.maxDistZ.set(mdz);

                if (!m.isActive()) m.toggle();
                if (m.isActive()) info("GridTorch started — xSpacing=" + xs + " zSpacing=" + zs + " maxDistX=" + mdx + " maxDistZ=" + mdz + " — will place " + m.getPlannedTorchCount() + " torches");
                else info("GridTorch could not start — check settings");
                return SINGLE_SUCCESS;
            }))))) ;

        // preview
        builder.then(literal("preview").executes(ctx -> {
            GridTorch m = Modules.get().get(GridTorch.class);
            if (m == null) { info("GridTorch module not found"); return SINGLE_SUCCESS; }
            m.startPreview();
            if (m.isPreviewing()) info("GridTorch preview — valid placements=" + m.getPlannedTorchCount() + " (visual only)");
            else info("GridTorch preview not started (module already active?)");
            return SINGLE_SUCCESS;
        }).then(literal("stop").executes(ctx -> {
            GridTorch m = Modules.get().get(GridTorch.class);
            if (m == null) { info("GridTorch module not found"); return SINGLE_SUCCESS; }
            m.stopPreview();
            return SINGLE_SUCCESS;
        })));

        // pause / resume / help
        builder.then(literal("pause").executes(c -> {
            GridTorch m = Modules.get().get(GridTorch.class);
            if (m == null) { info("GridTorch module not found"); return SINGLE_SUCCESS; }
            if (!m.isActive()) { info("GridTorch not active — cannot pause"); return SINGLE_SUCCESS; }
            m.pause();
            return SINGLE_SUCCESS;
        })).then(literal("resume").executes(c -> {
            GridTorch m = Modules.get().get(GridTorch.class);
            if (m == null) { info("GridTorch module not found"); return SINGLE_SUCCESS; }
            if (!m.isActive()) { info("GridTorch not active — enable module or use .gridtorch start"); return SINGLE_SUCCESS; }
            if (!m.isPaused()) { info("GridTorch is not paused"); return SINGLE_SUCCESS; }
            m.resume();
            info("GridTorch resumed — remaining=" + m.getRemainingPlacements());
            return SINGLE_SUCCESS;
        })).then(literal("help").executes(ctx -> {
            info("gt — short alias for .gridtorch (use .gridtorch help for full usage)");
            return SINGLE_SUCCESS;
        }));
    }
}
