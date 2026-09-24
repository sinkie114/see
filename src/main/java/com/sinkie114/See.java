package com.sinkie114;

import net.fabricmc.api.ModInitializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class See implements ModInitializer {
    public static final String MOD_ID = "see";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    @Override
    public void onInitialize() {
        LOGGER.info("实体调试查看器已加载");
    }
}
