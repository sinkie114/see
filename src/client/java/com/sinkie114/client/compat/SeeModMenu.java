package com.sinkie114.client.compat;

import com.sinkie114.client.SeeKeysScreen;
import com.terraformersmc.modmenu.api.ConfigScreenFactory;
import com.terraformersmc.modmenu.api.ModMenuApi;

/** Only loaded when Mod Menu is installed; the mod itself never requires it. */
public final class SeeModMenu implements ModMenuApi {
    @Override public ConfigScreenFactory<?> getModConfigScreenFactory() {
        return SeeKeysScreen::new;
    }
}
