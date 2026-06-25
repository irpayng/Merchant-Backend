package com.tms.report.core.util;

import com.tms.report.core.storage.S3UrlGenerator;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Shared builders for the avatar / image objects the admin UI consumes.
 *
 * <p>
 * The legacy schema stored each user-uploaded image in three S3 variants —
 * {@code original}, {@code thumbnail}, and {@code default} — under matching
 * paths, e.g. {@code images/bvn/<variant>/<ulid>.jpeg}. The microservices only
 * persist the {@code original} key on entity columns
 * ({@code users.bvn_photo_url}, {@code users.selfie_url}, etc.). To match the
 * UI contract, we synthesize the variant keys on demand by swapping the path
 * segment, then presign each.
 *
 * <p>
 * UI consumers expect different envelope shapes per page:
 * <ul>
 * <li>{@code Users}: {@code avatar = { default, thumbnail }}</li>
 * <li>{@code BVN}: {@code image = { bvn_photo, thumbnail, default }}</li>
 * <li>{@code Address / CAC / NIN}: {@code image = { thumbnail, default,
 * original }}</li>
 * </ul>
 *
 * <p>
 * Use the dedicated helper for each shape so the service layer can stop
 * duplicating the swap-and-presign logic per module.
 */
public final class Avatars {

    private Avatars() {
    }

    /**
     * Build the {@code { default, thumbnail }} avatar map the Users / Roles /
     * profile pages render. Returns {@code null} for null/blank input. Absolute
     * URLs (already presigned or external) are mirrored to every variant.
     */
    public static Map<String, String> avatar(String storageKey) {
        if (storageKey == null || storageKey.isBlank())
            return null;
        if (isAbsolute(storageKey)) {
            return Map.of("default", storageKey, "thumbnail", storageKey);
        }
        try {
            return Map.of("default", S3UrlGenerator.temporaryUrl(deriveVariantKey(storageKey, "default")), "thumbnail",
                    S3UrlGenerator.temporaryUrl(deriveVariantKey(storageKey, "thumbnail")));
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Build the {@code { bvn_photo, thumbnail, default }} image map the BVN list
     * page renders. {@code bvn_photo} is the canonical full-size image; the UI also
     * reads {@code thumbnail} as a fallback in some flows.
     */
    public static Map<String, String> bvnImage(String storageKey) {
        if (storageKey == null || storageKey.isBlank())
            return null;
        if (isAbsolute(storageKey)) {
            return Map.of("bvn_photo", storageKey, "thumbnail", storageKey, "default", storageKey);
        }
        try {
            String original = S3UrlGenerator.temporaryUrl(storageKey);
            Map<String, String> out = new LinkedHashMap<>();
            out.put("bvn_photo", original);
            out.put("thumbnail", S3UrlGenerator.temporaryUrl(deriveVariantKey(storageKey, "thumbnail")));
            out.put("default", S3UrlGenerator.temporaryUrl(deriveVariantKey(storageKey, "default")));
            return out;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Build the {@code { thumbnail, default, original }} image map the verification
     * list pages render (Address, CAC, NIN). Returns {@code null} for null/blank
     * input.
     */
    public static Map<String, String> verificationImage(String storageKey) {
        if (storageKey == null || storageKey.isBlank())
            return null;
        if (isAbsolute(storageKey)) {
            return Map.of("thumbnail", storageKey, "default", storageKey, "original", storageKey);
        }
        try {
            Map<String, String> out = new LinkedHashMap<>();
            out.put("thumbnail", S3UrlGenerator.temporaryUrl(deriveVariantKey(storageKey, "thumbnail")));
            out.put("default", S3UrlGenerator.temporaryUrl(deriveVariantKey(storageKey, "default")));
            out.put("original", S3UrlGenerator.temporaryUrl(storageKey));
            return out;
        } catch (Exception e) {
            return null;
        }
    }

    private static boolean isAbsolute(String s) {
        return s.startsWith("http://") || s.startsWith("https://");
    }

    /**
     * Swap the {@code /original/} segment in an S3 key for {@code /<variant>/}.
     * Falls back to the input key if the path doesn't contain {@code /original/} —
     * better to return a working URL than to lose the avatar.
     */
    private static String deriveVariantKey(String key, String variant) {
        if (key.contains("/original/"))
            return key.replace("/original/", "/" + variant + "/");
        return key;
    }
}
