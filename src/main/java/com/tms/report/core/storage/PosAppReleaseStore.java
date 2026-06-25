package com.tms.report.core.storage;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Response;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.S3Object;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Reads the POS app release manifest from the private
 * {@code irpay-pos-releases} S3 bucket and presigns the production APK for
 * admin download from the tms-ui admin portal.
 *
 * <p>
 * This is the admin-facing counterpart to the gateway's
 * {@code com.gateway.posqa.PosQaManifest}: the POS CI publishes the same
 * {@code manifest.json} (current pointer) and a versionCode-pinned
 * {@code app-release.apk} per channel under {@code pos-app/qa/<channel>/...}.
 * The gateway serves the device-facing channel; this serves admins the
 * {@code production} channel so they can pull the signed APK for Nexgo-store
 * submission / further distribution.
 *
 * <p>
 * The bucket is private. The device install path is intentionally closed on
 * production (the N80 blocks unknown-source installs), so the only way to
 * obtain the production APK is through this admin-gated, presigned URL.
 *
 * <p>
 * AWS credentials and region come from the same environment variables the rest
 * of {@link S3UrlGenerator} uses ({@code AWS_ACCESS_KEY_ID},
 * {@code AWS_SECRET_ACCESS_KEY}, {@code AWS_DEFAULT_REGION}). The releases
 * bucket and channel default to {@code irpay-pos-releases} / {@code production}
 * and are overridable via {@code POS_RELEASES_S3_BUCKET} /
 * {@code POS_RELEASES_CHANNEL}.
 */
@Component
@RequiredArgsConstructor
public class PosAppReleaseStore {

    private static final Logger log = LoggerFactory.getLogger(PosAppReleaseStore.class);
    private static final Duration URL_TTL = Duration.ofMinutes(10);

    private final ObjectMapper objectMapper;

    private volatile S3Client s3Client;
    private volatile S3Presigner presigner;

    private StaticCredentialsProvider credentials() {
        String accessKey = System.getenv("AWS_ACCESS_KEY_ID");
        String secretKey = System.getenv("AWS_SECRET_ACCESS_KEY");
        if (accessKey != null && !accessKey.isBlank() && secretKey != null && !secretKey.isBlank()) {
            return StaticCredentialsProvider.create(AwsBasicCredentials.create(accessKey, secretKey));
        }
        return null;
    }

    private Region region() {
        String region = System.getenv("AWS_DEFAULT_REGION");
        return Region.of(region != null && !region.isBlank() ? region : "eu-north-1");
    }

    private String bucket() {
        String bucket = System.getenv("POS_RELEASES_S3_BUCKET");
        return bucket != null && !bucket.isBlank() ? bucket : "irpay-pos-releases";
    }

    private String channel() {
        String channel = System.getenv("POS_RELEASES_CHANNEL");
        return channel != null && !channel.isBlank() ? channel : "production";
    }

    /**
     * S3 key of the channel's "current" manifest pointer that CI promotes on every
     * release.
     */
    private String manifestKey() {
        return channelPrefix() + "/manifest.json";
    }

    /**
     * {@code pos-app/qa/<channel>} — the channel's release prefix in the bucket.
     */
    private String channelPrefix() {
        return "pos-app/qa/" + channel();
    }

    public String channelName() {
        return channel();
    }

    public boolean isConfigured() {
        return credentials() != null;
    }

    private S3Client s3() {
        if (s3Client == null) {
            synchronized (this) {
                if (s3Client == null) {
                    s3Client = S3Client.builder().region(region()).credentialsProvider(credentials()).build();
                }
            }
        }
        return s3Client;
    }

    private S3Presigner presigner() {
        if (presigner == null) {
            synchronized (this) {
                if (presigner == null) {
                    presigner = S3Presigner.builder().region(region()).credentialsProvider(credentials()).build();
                }
            }
        }
        return presigner;
    }

    /**
     * Read the current production manifest (the channel's "current" pointer).
     * Returns {@code null} when S3 isn't configured, no build has been published
     * yet, or the manifest is incomplete.
     */
    public Manifest currentManifest() {
        if (!isConfigured()) {
            log.warn("POS app release: AWS credentials not configured; cannot read manifest");
            return null;
        }
        return readManifest(manifestKey());
    }

    /**
     * Read every published release for the channel, newest first. Each build has a
     * version-pinned {@code <channelPrefix>/<versionCode>/manifest.json} written by
     * CI alongside the "current" pointer; we list those and parse each. The current
     * pointer's versionCode marks the {@code latest} flag on the matching row.
     */
    public List<Manifest> listReleases() {
        if (!isConfigured()) {
            log.warn("POS app release: AWS credentials not configured; cannot list releases");
            return List.of();
        }
        Manifest current = readManifest(manifestKey());
        int latestVersionCode = current != null ? current.versionCode : -1;

        String prefix = channelPrefix() + "/";
        List<Manifest> releases = new ArrayList<>();
        try {
            String continuation = null;
            do {
                ListObjectsV2Request.Builder req = ListObjectsV2Request.builder().bucket(bucket()).prefix(prefix);
                if (continuation != null) {
                    req.continuationToken(continuation);
                }
                ListObjectsV2Response resp = s3().listObjectsV2(req.build());
                for (S3Object obj : resp.contents()) {
                    String key = obj.key();
                    // Only the version-pinned manifests, not the "current" pointer
                    // (<prefix>/manifest.json) and not the APKs.
                    if (!key.endsWith("/manifest.json") || key.equals(manifestKey())) {
                        continue;
                    }
                    Manifest m = readManifest(key);
                    if (m != null) {
                        m.latest = m.versionCode == latestVersionCode;
                        releases.add(m);
                    }
                }
                continuation = Boolean.TRUE.equals(resp.isTruncated()) ? resp.nextContinuationToken() : null;
            } while (continuation != null);
        } catch (Exception e) {
            log.warn("POS app release: failed to list releases under s3://{}/{}: {}", bucket(), prefix, e.getMessage());
            // Fall back to at least the current build so the page isn't empty.
            if (releases.isEmpty() && current != null) {
                current.latest = true;
                return List.of(current);
            }
        }
        releases.sort(Comparator.comparingInt((Manifest m) -> m.versionCode).reversed());
        return releases;
    }

    /**
     * Read the version-pinned manifest for a specific versionCode. Used by the
     * download endpoint so an admin can pull any historical build, not just the
     * current one.
     */
    public Manifest manifestForVersion(int versionCode) {
        if (!isConfigured() || versionCode <= 0) {
            return null;
        }
        return readManifest(channelPrefix() + "/" + versionCode + "/manifest.json");
    }

    /**
     * Parse a manifest JSON object at the given S3 key, or {@code null} if
     * absent/incomplete.
     */
    private Manifest readManifest(String key) {
        try {
            GetObjectRequest req = GetObjectRequest.builder().bucket(bucket()).key(key).build();
            try (ResponseInputStream<GetObjectResponse> in = s3().getObject(req)) {
                JsonNode root = objectMapper.readTree(in);
                if (root == null || !root.isObject()) {
                    log.warn("POS app release: manifest at s3://{}/{} is not a JSON object", bucket(), key);
                    return null;
                }
                Manifest m = new Manifest();
                m.versionCode = root.path("versionCode").asInt(0);
                m.versionName = textOrNull(root, "versionName");
                m.channel = textOrNull(root, "channel");
                m.sha256 = textOrNull(root, "sha256");
                m.notes = textOrNull(root, "notes");
                m.publishedAt = textOrNull(root, "publishedAt");
                m.apkS3Key = textOrNull(root, "apkS3Key");
                if (m.versionCode <= 0 || m.sha256 == null || m.apkS3Key == null) {
                    log.warn("POS app release: manifest incomplete at {} (versionCode={} sha256={} apkS3Key={})", key,
                            m.versionCode, m.sha256, m.apkS3Key);
                    return null;
                }
                return m;
            }
        } catch (NoSuchKeyException e) {
            log.info("POS app release: no manifest at s3://{}/{}", bucket(), key);
            return null;
        } catch (Exception e) {
            log.warn("POS app release: failed to read manifest s3://{}/{}: {}", bucket(), key, e.getMessage());
            return null;
        }
    }

    /**
     * Presign the APK for the given manifest. The S3 key never leaves the server;
     * the admin browser downloads straight from S3 with a short-lived URL.
     *
     * <p>
     * The {@code downloadFileName} is baked into the presigned URL as a
     * {@code response-content-disposition} override so the browser saves the file
     * under a versioned name (e.g. {@code app-release-1.40.1.apk}). This is
     * required because the URL is cross-origin (S3): a browser ignores the
     * {@code <a download>} attribute for cross-origin URLs, so the only reliable
     * way to control the saved filename is the {@code Content-Disposition} header
     * S3 returns.
     */
    public String presignApk(Manifest manifest, String downloadFileName) {
        GetObjectRequest get = GetObjectRequest.builder().bucket(bucket()).key(manifest.apkS3Key)
                .responseContentDisposition("attachment; filename=\"" + downloadFileName + "\"").build();
        GetObjectPresignRequest req = GetObjectPresignRequest.builder().signatureDuration(URL_TTL).getObjectRequest(get)
                .build();
        return presigner().presignGetObject(req).url().toString();
    }

    public long urlTtlSeconds() {
        return URL_TTL.getSeconds();
    }

    private static String textOrNull(JsonNode root, String key) {
        JsonNode v = root.get(key);
        return v == null || v.isNull() ? null : v.asString();
    }

    /**
     * Server-internal manifest view. {@code apkS3Key} is never serialized to the
     * client.
     */
    public static class Manifest {
        public int versionCode;
        public String versionName;
        public String channel;
        public String sha256;
        public String notes;
        public String publishedAt;
        public String apkS3Key;
        public boolean latest;
    }
}
