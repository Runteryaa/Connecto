package com.runterya.connecto;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;

/**
 * Main mod initializer for Connecto.
 * Loads configuration on server startup.
 */
public class ConnectoMod implements ModInitializer {

    public static final String MOD_ID = "connecto";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    @Override
    public void onInitialize() {
        Path configDir = FabricLoader.getInstance().getConfigDir();
        ConnectoConfig.load(configDir);

        LOGGER.info("[Connecto] Initialized. Auth bypass is {}.",
                ConnectoConfig.getInstance().enabled ? "ENABLED" : "DISABLED");
    }
}
