package pl.autorynek;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import org.lwjgl.glfw.GLFW;
import pl.autorynek.bot.MarketBot;
import pl.autorynek.data.AutoRynekConfig;
import pl.autorynek.ui.Hud;
import pl.autorynek.ui.ScreenHooks;

public class AutoRynekClient implements ClientModInitializer {
    public static AutoRynekConfig CONFIG;
    public static MarketBot BOT;
    public static KeyBinding ADD_KEY;
    public static KeyBinding DUMP_KEY;

    @Override
    public void onInitializeClient() {
        CONFIG = AutoRynekConfig.load();
        BOT = new MarketBot(CONFIG);

        ADD_KEY = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.autorynek.add", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_L, "key.categories.autorynek"));
        DUMP_KEY = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.autorynek.dump", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_J, "key.categories.autorynek"));

        ClientTickEvents.END_CLIENT_TICK.register(client -> BOT.tick(client));
        ClientReceiveMessageEvents.GAME.register((message, overlay) -> BOT.onGameMessage(message.getString()));
        HudRenderCallback.EVENT.register(Hud::render);

        ScreenHooks.register();
        Commands.register();
    }
}
