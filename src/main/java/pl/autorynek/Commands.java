package pl.autorynek;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.LongArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.text.Text;
import pl.autorynek.data.AutoRynekConfig;
import pl.autorynek.data.WatchedItem;

import static net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.argument;
import static net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.literal;

/** /autorynek start | analyze | stop | reset | resetstats | remove <nr> | margin <nr> <kwota> | query <nr> <tekst> */
public final class Commands {
    private Commands() {}

    public static void register() {
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> dispatcher.register(
                literal("autorynek")
                        .then(literal("start").executes(c -> {
                            AutoRynekClient.BOT.start(false);
                            return fb(c, "Start (analiza + skupowanie)");
                        }))
                        .then(literal("analyze").executes(c -> {
                            AutoRynekClient.BOT.start(true);
                            return fb(c, "Start (tylko analiza)");
                        }))
                        .then(literal("stop").executes(c -> {
                            AutoRynekClient.BOT.stop("komenda");
                            return fb(c, "Zatrzymano");
                        }))
                        .then(literal("reset").executes(c -> {
                            AutoRynekClient.BOT.resetMarket();
                            return fb(c, "Reset rynku");
                        }))
                        .then(literal("resetstats").executes(c -> {
                            cfg().stats.resetAll();
                            cfg().save();
                            return fb(c, "Wyzerowano statystyki");
                        }))
                        .then(literal("remove")
                                .then(argument("nr", IntegerArgumentType.integer(1)).executes(c -> {
                                    int n = IntegerArgumentType.getInteger(c, "nr");
                                    if (n > cfg().watchlist.size()) return fb(c, "Nie ma przedmiotu o numerze " + n);
                                    WatchedItem w = cfg().watchlist.remove(n - 1);
                                    cfg().save();
                                    return fb(c, "Usunieto: " + w.label);
                                })))
                        .then(literal("margin")
                                .then(argument("nr", IntegerArgumentType.integer(1))
                                        .then(argument("kwota", LongArgumentType.longArg(0)).executes(c -> {
                                            int n = IntegerArgumentType.getInteger(c, "nr");
                                            if (n > cfg().watchlist.size()) return fb(c, "Nie ma przedmiotu o numerze " + n);
                                            WatchedItem w = cfg().watchlist.get(n - 1);
                                            w.margin = LongArgumentType.getLong(c, "kwota");
                                            w.recalc(cfg().settings.taxMultiplier);
                                            cfg().save();
                                            return fb(c, w.label + ": marza " + w.margin + (w.analyzed && w.buyPrice > 0 ? ", cena skupu " + w.buyPrice : " (cena skupu po analizie rynku)"));
                                        }))))
                        .then(literal("query")
                                .then(argument("nr", IntegerArgumentType.integer(1))
                                        .then(argument("tekst", StringArgumentType.greedyString()).executes(c -> {
                                            int n = IntegerArgumentType.getInteger(c, "nr");
                                            if (n > cfg().watchlist.size()) return fb(c, "Nie ma przedmiotu o numerze " + n);
                                            WatchedItem w = cfg().watchlist.get(n - 1);
                                            w.searchQuery = StringArgumentType.getString(c, "tekst");
                                            cfg().save();
                                            return fb(c, w.label + ": /ah search " + w.searchQuery);
                                        }))))
        ));
    }

    private static AutoRynekConfig cfg() {
        return AutoRynekClient.CONFIG;
    }

    private static int fb(CommandContext<FabricClientCommandSource> c, String msg) {
        c.getSource().sendFeedback(Text.literal("[AutoRynek] " + msg));
        return 1;
    }
}
