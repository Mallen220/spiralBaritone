package com.example.addon.commands;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.example.addon.modules.GridTorch;
import meteordevelopment.meteorclient.commands.Command;
import meteordevelopment.meteorclient.systems.modules.Modules;
import net.minecraft.command.CommandSource;

/**
 * /gridtorch [xSpacing] [zSpacing]
 * - No args: toggle module
 * - start: enable module
 * - stop: disable module
 * - status: print progress
 * - optional positional x/z spacing to set before starting
 */
public class CommandGridTorch extends Command {
    public CommandGridTorch() {
        super("gridtorch", "Start/stop the GridTorch module and show status");
    }

    @Override
    public void build(LiteralArgumentBuilder<CommandSource> builder) {
        // Toggle when no args
        builder.executes(ctx -> {
            GridTorch m = Modules.get().get(GridTorch.class);
            if (m == null) {
                info("GridTorch module not found");
                return SINGLE_SUCCESS;
            }

            if (m.isActive()) m.toggle(); else m.toggle();
            info("GridTorch toggled: " + (m.isActive() ? "enabled" : "disabled"));
            return SINGLE_SUCCESS;
        });

        // start
        builder.then(literal("start").executes(c -> {
            GridTorch m = Modules.get().get(GridTorch.class);
            if (m == null) { info("GridTorch module not found"); return SINGLE_SUCCESS; }

            // If preview-only mode is active (module enabled only for preview), convert to full active mode.
            if (m.isActive() && m.isPreviewOnly()) {
                m.stopPreview(); // disables preview-only active instance
                m.toggle();      // enable full active behavior
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
        }))
        // help: concise usage summary
        .then(literal("help").executes(ctx -> {
            info("gridtorch — usage summary:\n" +
                "  (no args)                Toggle module\n" +
                "  start                    Enable GridTorch (convert preview if active)\n" +
                "  stop                     Disable GridTorch and cancel Baritone\n" +
                "  pause / resume           Pause or resume an active run (preserves queue)\n" +
                "  preview / preview stop   Visual-only preview of placements\n" +
                "  <x> <z> [maxX maxZ]      Set spacing (and optional bounds) then start\n" +
                "  status                   Show module status and counts\n" +
                "  maxdip <v>               Set max height delta between placements\n" +
                "  minlight <0-15>          Skip placements with block light >= value\n" +
                "  water|breakgrass|breaktree|avoidblocks|shouldSpiral  Toggle/set options\n" +
                "  resetSettings            Restore defaults\n" +
                "Examples: .gridtorch 7 7 31 31   .gridtorch preview   .gridtorch shouldSpiral toggle");
            return SINGLE_SUCCESS;
        }));

        // stop
        builder.then(literal("stop").executes(c -> {
            GridTorch m = Modules.get().get(GridTorch.class);
            if (m == null) { info("GridTorch module not found"); return SINGLE_SUCCESS; }

            // Cancel any Baritone pathing immediately (works even if module isn't active)
            try { m.cancelBaritonePath(); } catch (Throwable ignored) {}

            if (m.isActive()) m.toggle();
            info("GridTorch stopped (Baritone cancelled)");
            return SINGLE_SUCCESS;
        }))
        // pause
        .then(literal("pause").executes(c -> {
            GridTorch m = Modules.get().get(GridTorch.class);
            if (m == null) { info("GridTorch module not found"); return SINGLE_SUCCESS; }

            if (!m.isActive()) { info("GridTorch not active — cannot pause"); return SINGLE_SUCCESS; }
            m.pause();
            return SINGLE_SUCCESS;
        }))
        // resume
        .then(literal("resume").executes(c -> {
            GridTorch m = Modules.get().get(GridTorch.class);
            if (m == null) { info("GridTorch module not found"); return SINGLE_SUCCESS; }

            if (!m.isActive()) { info("GridTorch not active — enable module or use .gridtorch start"); return SINGLE_SUCCESS; }
            if (!m.isPaused()) { info("GridTorch is not paused"); return SINGLE_SUCCESS; }
            m.resume();
            info("GridTorch resumed — remaining=" + m.getRemainingPlacements());
            return SINGLE_SUCCESS;
        }));

        // status
        builder.then(literal("status").executes(c -> {
            GridTorch m = Modules.get().get(GridTorch.class);
            if (m == null) { info("GridTorch module not found"); return SINGLE_SUCCESS; }

            info(String.format("GridTorch — active=%s paused=%s previewing=%s xSpacing=%d zSpacing=%d maxDistX=%d maxDistZ=%d maxDip=%d minLight=%d water=%s breakGrass=%s breakTree=%s avoidPlayerBlocks=%s avoidTorchesNearby=%s avoidTorchesRadius=%d etaSafetyMultiplier=%.2f etaAdditiveTicks=%d etaMaxGraceTicks=%d navGraceTicks=%d maxNavRetries=%d spiralTraversal=%s",
                m.isActive(), m.isPaused(), m.isPreviewing(), m.xSpacing.get(), m.zSpacing.get(), m.maxDistX.get(), m.maxDistZ.get(), m.maxDip.get(), m.minLightLevel.get(), m.stopOnWater.get(), m.breakGrass.get(), m.breakTree.get(), m.avoidPlayerBlocks.get(), m.avoidTorchesNearby.get(), m.avoidTorchesRadius.get(), m.etaSafetyMultiplier.get(), m.etaAdditiveTicks.get(), m.etaMaxGraceTicks.get(), m.navGraceTicks.get(), m.maxNavRetries.get(), m.spiralTraversal.get()));
            return SINGLE_SUCCESS;
        }))
        // resetSettings: restore all config to defaults
        .then(literal("resetSettings").executes(c -> {
            GridTorch m = Modules.get().get(GridTorch.class);
            if (m == null) { info("GridTorch module not found"); return SINGLE_SUCCESS; }

            m.resetSettings();
            info("GridTorch settings reset to defaults");
            return SINGLE_SUCCESS;
        }));

        // Optional: set spacing then start
        // Usage: /gridtorch <xSpacing> <zSpacing> [maxDistX maxDistZ]
        builder.then(argument("xSpacing", IntegerArgumentType.integer(1)).then(argument("zSpacing", IntegerArgumentType.integer(1))
            // two-arg form (spacing only)
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
            // optional extended form: spacing + bounds
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

        // preview: visual-only preview of computed torch positions (does NOT place torches)
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

        // maxDip setter: /gridtorch maxdip <value>
        builder.then(literal("maxdip").then(argument("value", IntegerArgumentType.integer(0)).executes(ctx -> {
            int v = IntegerArgumentType.getInteger(ctx, "value");
            GridTorch m = Modules.get().get(GridTorch.class);
            if (m == null) { info("GridTorch module not found"); return SINGLE_SUCCESS; }
            m.maxDip.set(v);
            info("GridTorch: maxDip set to " + v);
            return SINGLE_SUCCESS;
        })));

        // minLightLevel setter: /gridtorch minlight <0-15>
        builder.then(literal("minlight").then(argument("value", IntegerArgumentType.integer(0, 15)).executes(ctx -> {
            int v = IntegerArgumentType.getInteger(ctx, "value");
            GridTorch m = Modules.get().get(GridTorch.class);
            if (m == null) { info("GridTorch module not found"); return SINGLE_SUCCESS; }
            m.minLightLevel.set(v);
            info("GridTorch: minLightLevel set to " + v);
            return SINGLE_SUCCESS;
        })));

        // water toggle/set: /gridtorch water <true|false>  OR /gridtorch water toggle
        builder.then(literal("water").executes(ctx -> {
            GridTorch m = Modules.get().get(GridTorch.class);
            if (m == null) { info("GridTorch module not found"); return SINGLE_SUCCESS; }
            info("water-boundary = " + m.stopOnWater.get());
            return SINGLE_SUCCESS;
        }).then(argument("value", StringArgumentType.word()).executes(ctx -> {
            boolean v = Boolean.parseBoolean(StringArgumentType.getString(ctx, "value"));
            GridTorch m = Modules.get().get(GridTorch.class);
            if (m == null) { info("GridTorch module not found"); return SINGLE_SUCCESS; }
            m.stopOnWater.set(v);
            info("GridTorch: water-boundary set to " + v);
            return SINGLE_SUCCESS;
        })).then(literal("toggle").executes(ctx -> {
            GridTorch m = Modules.get().get(GridTorch.class);
            if (m == null) { info("GridTorch module not found"); return SINGLE_SUCCESS; }
            m.stopOnWater.set(!m.stopOnWater.get());
            info("GridTorch: water-boundary toggled to " + m.stopOnWater.get());
            return SINGLE_SUCCESS;
        })));

        // breakGrass toggle/set: /gridtorch breakgrass <true|false> OR /gridtorch breakgrass toggle
        builder.then(literal("breakgrass").executes(ctx -> {
            GridTorch m = Modules.get().get(GridTorch.class);
            if (m == null) { info("GridTorch module not found"); return SINGLE_SUCCESS; }
            info("breakGrass = " + m.breakGrass.get());
            return SINGLE_SUCCESS;
        }).then(argument("value", StringArgumentType.word()).executes(ctx -> {
            boolean v = Boolean.parseBoolean(StringArgumentType.getString(ctx, "value"));
            GridTorch m = Modules.get().get(GridTorch.class);
            if (m == null) { info("GridTorch module not found"); return SINGLE_SUCCESS; }
            m.breakGrass.set(v);
            info("GridTorch: breakGrass set to " + v);
            return SINGLE_SUCCESS;
        })).then(literal("toggle").executes(ctx -> {
            GridTorch m = Modules.get().get(GridTorch.class);
            if (m == null) { info("GridTorch module not found"); return SINGLE_SUCCESS; }
            m.breakGrass.set(!m.breakGrass.get());
            info("GridTorch: breakGrass toggled to " + m.breakGrass.get());
            return SINGLE_SUCCESS;
        })));

        // breakTree toggle/set: /gridtorch breaktree <true|false> OR /gridtorch breaktree toggle
        builder.then(literal("breaktree").executes(ctx -> {
            GridTorch m = Modules.get().get(GridTorch.class);
            if (m == null) { info("GridTorch module not found"); return SINGLE_SUCCESS; }
            info("breakTree = " + m.breakTree.get());
            return SINGLE_SUCCESS;
        }).then(argument("value", StringArgumentType.word()).executes(ctx -> {
            boolean v = Boolean.parseBoolean(StringArgumentType.getString(ctx, "value"));
            GridTorch m = Modules.get().get(GridTorch.class);
            if (m == null) { info("GridTorch module not found"); return SINGLE_SUCCESS; }
            m.breakTree.set(v);
            info("GridTorch: breakTree set to " + v);
            return SINGLE_SUCCESS;
        })).then(literal("toggle").executes(ctx -> {
            GridTorch m = Modules.get().get(GridTorch.class);
            if (m == null) { info("GridTorch module not found"); return SINGLE_SUCCESS; }
            m.breakTree.set(!m.breakTree.get());
            info("GridTorch: breakTree toggled to " + m.breakTree.get());
            return SINGLE_SUCCESS;
        })));

        // avoid-player-blocks toggle/set: /gridtorch avoidblocks <true|false> OR /gridtorch avoidblocks toggle
        builder.then(literal("avoidblocks").executes(ctx -> {
            GridTorch m = Modules.get().get(GridTorch.class);
            if (m == null) { info("GridTorch module not found"); return SINGLE_SUCCESS; }
            info("avoidPlayerBlocks = " + m.avoidPlayerBlocks.get());
            return SINGLE_SUCCESS;
        }).then(argument("value", StringArgumentType.word()).executes(ctx -> {
            boolean v = Boolean.parseBoolean(StringArgumentType.getString(ctx, "value"));
            GridTorch m = Modules.get().get(GridTorch.class);
            if (m == null) { info("GridTorch module not found"); return SINGLE_SUCCESS; }
            m.avoidPlayerBlocks.set(v);
            info("GridTorch: avoidPlayerBlocks set to " + v);
            return SINGLE_SUCCESS;
        })).then(literal("toggle").executes(ctx -> {
            GridTorch m = Modules.get().get(GridTorch.class);
            if (m == null) { info("GridTorch module not found"); return SINGLE_SUCCESS; }
            m.avoidPlayerBlocks.set(!m.avoidPlayerBlocks.get());
            info("GridTorch: avoidPlayerBlocks toggled to " + m.avoidPlayerBlocks.get());
            return SINGLE_SUCCESS;
        })));

        // shouldSpiral toggle/set: /gridtorch shouldSpiral <true|false> OR /gridtorch shouldSpiral toggle
        builder.then(literal("shouldSpiral").executes(ctx -> {
            GridTorch m = Modules.get().get(GridTorch.class);
            if (m == null) { info("GridTorch module not found"); return SINGLE_SUCCESS; }
            info("shouldSpiral = " + m.spiralTraversal.get());
            return SINGLE_SUCCESS;
        }).then(argument("value", StringArgumentType.word()).executes(ctx -> {
            boolean v = Boolean.parseBoolean(StringArgumentType.getString(ctx, "value"));
            GridTorch m = Modules.get().get(GridTorch.class);
            if (m == null) { info("GridTorch module not found"); return SINGLE_SUCCESS; }
            m.spiralTraversal.set(v);
            if (m.isActive()) m.applyTraversalModeNow();
            info("GridTorch: shouldSpiral set to " + v);
            return SINGLE_SUCCESS;
        })).then(literal("toggle").executes(ctx -> {
            GridTorch m = Modules.get().get(GridTorch.class);
            if (m == null) { info("GridTorch module not found"); return SINGLE_SUCCESS; }
            m.spiralTraversal.set(!m.spiralTraversal.get());
            if (m.isActive()) m.applyTraversalModeNow();
            info("GridTorch: shouldSpiral toggled to " + m.spiralTraversal.get());
            return SINGLE_SUCCESS;
        })));
    }
}
