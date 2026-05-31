package com.ycy.fabric.config;

import com.terraformersmc.modmenu.api.ConfigScreenFactory;
import com.terraformersmc.modmenu.api.ModMenuApi;
import com.ycy.fabric.gui.YcyConfigScreen;
import net.minecraft.text.Text;

/**
 * Integrates with Mod Menu to show YCY Config button in the mods list
 */
public class ModMenuIntegration implements ModMenuApi {
    @Override
    public ConfigScreenFactory<?> getModConfigScreenFactory() {
        return parent -> new YcyConfigScreen();
    }
}
