package com.tms.report.core.storage;

import java.io.InputStream;
import java.time.Duration;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;

/**
 * Generates S3 presigned URLs matching PHP's Storage::temporaryUrl(). Also
 * supports uploading files to S3.
 */
public class S3UrlGenerator {

    private static S3Presigner presigner;
    private static S3Client s3Client;

    private static StaticCredentialsProvider getCredentials() {
        String accessKey = System.getenv("AWS_ACCESS_KEY_ID");
        String secretKey = System.getenv("AWS_SECRET_ACCESS_KEY");
        if (accessKey != null && secretKey != null) {
            return StaticCredentialsProvider.create(AwsBasicCredentials.create(accessKey, secretKey));
        }
        return null;
    }

    private static Region getRegion() {
        String region = System.getenv("AWS_DEFAULT_REGION");
        return Region.of(region != null ? region : "eu-north-1");
    }

    private static String getBucket() {
        String bucket = System.getenv("AWS_BUCKET");
        return bucket != null ? bucket : "ircms-public-images";
    }

    private static S3Presigner getPresigner() {
        if (presigner == null) {
            StaticCredentialsProvider creds = getCredentials();
            if (creds != null) {
                presigner = S3Presigner.builder().region(getRegion()).credentialsProvider(creds).build();
            }
        }
        return presigner;
    }

    private static S3Client getS3Client() {
        if (s3Client == null) {
            StaticCredentialsProvider creds = getCredentials();
            if (creds != null) {
                s3Client = S3Client.builder().region(getRegion()).credentialsProvider(creds).build();
            }
        }
        return s3Client;
    }

    /**
     * Generate a presigned URL for the given S3 object path. Expires in 60 minutes
     * (matching PHP's now()->addMinutes(60)).
     */
    public static String temporaryUrl(String path) {
        S3Presigner signer = getPresigner();
        if (signer == null) {
            return "https://" + getBucket() + ".s3." + getRegion().id() + ".amazonaws.com/" + path;
        }

        GetObjectRequest getObjectRequest = GetObjectRequest.builder().bucket(getBucket()).key(path).build();

        GetObjectPresignRequest presignRequest = GetObjectPresignRequest.builder()
                .signatureDuration(Duration.ofMinutes(60)).getObjectRequest(getObjectRequest).build();

        return signer.presignGetObject(presignRequest).url().toString();
    }

    /**
     * Upload a file to S3. Returns the S3 key on success, null if S3 is not
     * configured.
     */
    public static String upload(String key, InputStream inputStream, long contentLength, String contentType) {
        S3Client client = getS3Client();
        if (client == null) {
            return null;
        }

        PutObjectRequest putRequest = PutObjectRequest.builder().bucket(getBucket()).key(key).contentType(contentType)
                .build();

        client.putObject(putRequest, RequestBody.fromInputStream(inputStream, contentLength));
        return key;
    }
}
