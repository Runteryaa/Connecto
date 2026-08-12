package com.runterya.connecto.skin;

import com.google.common.collect.HashMultimap;
import com.google.common.collect.Multimap;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.authlib.GameProfile;
import com.mojang.authlib.properties.Property;
import com.mojang.authlib.properties.PropertyMap;
import com.runterya.connecto.ConnectoMod;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Utility to fetch official Mojang skins for offline/bypassed players
 * and attach textures property to their GameProfile.
 */
public class SkinFetcher {

    private static final Map<String, Optional<Property>> SKIN_CACHE = new ConcurrentHashMap<>();
    private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(4))
            .build();

    private static volatile Property PREFETCHED_DEFAULT_SKIN = null;
    private static volatile String PREFETCHED_DEFAULT_USER = null;

    public static void preFetchDefaultSkin(String defaultSkinUser) {
        if (defaultSkinUser == null || defaultSkinUser.isBlank()) {
            PREFETCHED_DEFAULT_SKIN = null;
            PREFETCHED_DEFAULT_USER = null;
            return;
        }

        if (defaultSkinUser.equalsIgnoreCase(PREFETCHED_DEFAULT_USER) && PREFETCHED_DEFAULT_SKIN != null) {
            return;
        }

        Thread fetchThread = new Thread(() -> {
            ConnectoMod.LOGGER.info("[Connecto] Pre-fetching default skin for username '{}'...", defaultSkinUser);
            Property skin = fetchSkinFor(defaultSkinUser);
            if (skin != null) {
                PREFETCHED_DEFAULT_SKIN = skin;
                PREFETCHED_DEFAULT_USER = defaultSkinUser;
                ConnectoMod.LOGGER.info("[Connecto] ✓ Successfully pre-fetched and cached default skin of '{}'", defaultSkinUser);
            } else {
                ConnectoMod.LOGGER.warn("[Connecto] Could not pre-fetch default skin for username '{}'", defaultSkinUser);
            }
        }, "Connecto-SkinPreFetcher");
        fetchThread.setDaemon(true);
        fetchThread.start();
    }

    public static GameProfile processSkin(GameProfile profile, boolean fetchOwnSkin, String defaultSkinUser) {
        if (profile == null || profile.name() == null || profile.name().isBlank()) return profile;

        // If profile already has texture properties, do nothing
        if (profile.properties() != null && profile.properties().containsKey("textures")) {
            return profile;
        }

        Property skinProperty = null;

        // 1. Try to fetch player's own skin if enabled
        if (fetchOwnSkin) {
            skinProperty = fetchSkinFor(profile.name());
        }

        // 2. If profile still has no skin, apply pre-fetched default skin if available
        if (skinProperty == null) {
            if (PREFETCHED_DEFAULT_SKIN != null) {
                skinProperty = PREFETCHED_DEFAULT_SKIN;
            } else if (defaultSkinUser != null && !defaultSkinUser.isBlank()) {
                skinProperty = fetchSkinFor(defaultSkinUser);
            }
        }

        if (skinProperty != null) {
            Multimap<String, Property> newMap = HashMultimap.create();
            if (profile.properties() != null) {
                newMap.putAll(profile.properties());
            }
            newMap.put("textures", skinProperty);
            PropertyMap newPropertyMap = new PropertyMap(newMap);

            GameProfile updatedProfile = new GameProfile(profile.id(), profile.name(), newPropertyMap);
            ConnectoMod.LOGGER.info("[Connecto] ✓ Applied skin for player '{}'", profile.name());
            return updatedProfile;
        }

        return profile;
    }

    private static Property fetchSkinFor(String targetUsername) {
        if (targetUsername == null || targetUsername.isBlank()) return null;
        String cacheKey = targetUsername.toLowerCase();

        // 1. Check in-memory cache
        if (SKIN_CACHE.containsKey(cacheKey)) {
            Optional<Property> cachedProperty = SKIN_CACHE.get(cacheKey);
            return cachedProperty != null ? cachedProperty.orElse(null) : null;
        }

        try {
            // 2. Query Mojang API to get official UUID for targetUsername
            String uuidUrl = "https://api.mojang.com/users/profiles/minecraft/" + targetUsername;
            HttpRequest uuidRequest = HttpRequest.newBuilder()
                    .uri(URI.create(uuidUrl))
                    .timeout(Duration.ofSeconds(4))
                    .GET()
                    .build();

            HttpResponse<String> uuidResponse = HTTP_CLIENT.send(uuidRequest, HttpResponse.BodyHandlers.ofString());
            if (uuidResponse.statusCode() != 200 || uuidResponse.body() == null || uuidResponse.body().isBlank()) {
                ConnectoMod.LOGGER.debug("[Connecto] No Mojang account found for username '{}' - skipping skin fetch.", targetUsername);
                SKIN_CACHE.put(cacheKey, Optional.empty());
                return null;
            }

            JsonObject uuidJson = JsonParser.parseString(uuidResponse.body()).getAsJsonObject();
            if (!uuidJson.has("id")) {
                SKIN_CACHE.put(cacheKey, Optional.empty());
                return null;
            }
            String mojangUuid = uuidJson.get("id").getAsString();

            // 3. Query Session Server to get texture payload + signature
            String sessionUrl = "https://sessionserver.mojang.com/session/minecraft/profile/" + mojangUuid + "?unsigned=false";
            HttpRequest sessionRequest = HttpRequest.newBuilder()
                    .uri(URI.create(sessionUrl))
                    .timeout(Duration.ofSeconds(4))
                    .GET()
                    .build();

            HttpResponse<String> sessionResponse = HTTP_CLIENT.send(sessionRequest, HttpResponse.BodyHandlers.ofString());
            if (sessionResponse.statusCode() != 200 || sessionResponse.body() == null || sessionResponse.body().isBlank()) {
                SKIN_CACHE.put(cacheKey, Optional.empty());
                return null;
            }

            JsonObject sessionJson = JsonParser.parseString(sessionResponse.body()).getAsJsonObject();
            if (!sessionJson.has("properties")) {
                SKIN_CACHE.put(cacheKey, Optional.empty());
                return null;
            }

            JsonArray properties = sessionJson.getAsJsonArray("properties");
            for (JsonElement elem : properties) {
                JsonObject prop = elem.getAsJsonObject();
                if ("textures".equals(prop.get("name").getAsString())) {
                    String val = prop.get("value").getAsString();
                    String sig = prop.has("signature") ? prop.get("signature").getAsString() : "";

                    Property textureProperty = new Property("textures", val, sig);
                    SKIN_CACHE.put(cacheKey, Optional.of(textureProperty));
                    return textureProperty;
                }
            }

            SKIN_CACHE.put(cacheKey, Optional.empty());
        } catch (Exception e) {
            ConnectoMod.LOGGER.warn("[Connecto] Could not fetch skin for '{}': {}", targetUsername, e.toString());
        }
        return null;
    }
}
