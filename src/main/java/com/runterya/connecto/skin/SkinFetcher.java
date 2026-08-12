package com.runterya.connecto.skin;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.authlib.GameProfile;
import com.mojang.authlib.properties.Property;
import com.runterya.connecto.ConnectoMod;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Utility to fetch official Mojang skins for offline/bypassed players
 * and attach textures property to their GameProfile.
 */
public class SkinFetcher {

    private static final Map<String, Property> SKIN_CACHE = new ConcurrentHashMap<>();
    private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(4))
            .build();

    public static void applySkinIfMissing(GameProfile profile) {
        if (profile == null || profile.name() == null || profile.name().isBlank()) return;

        // If profile already has texture properties, do nothing
        if (profile.properties() != null && profile.properties().containsKey("textures")) {
            return;
        }

        String username = profile.name();
        String cacheKey = username.toLowerCase();

        // 1. Check in-memory cache
        if (SKIN_CACHE.containsKey(cacheKey)) {
            Property cachedProperty = SKIN_CACHE.get(cacheKey);
            if (cachedProperty != null) {
                profile.properties().put("textures", cachedProperty);
                ConnectoMod.LOGGER.info("[Connecto] Applied cached skin for offline player '{}'", username);
            }
            return;
        }

        try {
            // 2. Query Mojang API to get official UUID for username
            String uuidUrl = "https://api.mojang.com/users/profiles/minecraft/" + username;
            HttpRequest uuidRequest = HttpRequest.newBuilder()
                    .uri(URI.create(uuidUrl))
                    .timeout(Duration.ofSeconds(4))
                    .GET()
                    .build();

            HttpResponse<String> uuidResponse = HTTP_CLIENT.send(uuidRequest, HttpResponse.BodyHandlers.ofString());
            if (uuidResponse.statusCode() != 200 || uuidResponse.body() == null || uuidResponse.body().isBlank()) {
                ConnectoMod.LOGGER.debug("[Connecto] No Mojang account found for username '{}' - skipping skin fetch.", username);
                SKIN_CACHE.put(cacheKey, null); // Cache negative result to avoid repeated failed lookups
                return;
            }

            JsonObject uuidJson = JsonParser.parseString(uuidResponse.body()).getAsJsonObject();
            if (!uuidJson.has("id")) {
                SKIN_CACHE.put(cacheKey, null);
                return;
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
                SKIN_CACHE.put(cacheKey, null);
                return;
            }

            JsonObject sessionJson = JsonParser.parseString(sessionResponse.body()).getAsJsonObject();
            if (!sessionJson.has("properties")) {
                SKIN_CACHE.put(cacheKey, null);
                return;
            }

            JsonArray properties = sessionJson.getAsJsonArray("properties");
            for (JsonElement elem : properties) {
                JsonObject prop = elem.getAsJsonObject();
                if ("textures".equals(prop.get("name").getAsString())) {
                    String val = prop.get("value").getAsString();
                    String sig = prop.has("signature") ? prop.get("signature").getAsString() : "";

                    Property textureProperty = new Property("textures", val, sig);
                    SKIN_CACHE.put(cacheKey, textureProperty);
                    profile.properties().put("textures", textureProperty);
                    ConnectoMod.LOGGER.info("[Connecto] ✓ Successfully fetched and applied official Mojang skin for offline player '{}'", username);
                    return;
                }
            }
            
            SKIN_CACHE.put(cacheKey, null);
        } catch (Exception e) {
            ConnectoMod.LOGGER.warn("[Connecto] Could not fetch skin for '{}': {}", username, e.getMessage());
        }
    }
}
