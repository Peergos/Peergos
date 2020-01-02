package peergos.server.storage;

import peergos.server.util.*;

public class S3Config {
    public final String path, bucket, region, accessKey, secretKey, regionEndpoint;

    public S3Config(String path, String bucket, String region, String accessKey, String secretKey, String regionEndpoint) {
        this.path = path;
        this.bucket = bucket;
        this.region = region;
        this.accessKey = accessKey;
        this.secretKey = secretKey;
        this.regionEndpoint = regionEndpoint;
    }
    public static boolean useS3(Args a) {
        return a.hasArg("s3.bucket");
    }

    public static S3Config build(Args a) {
        String path = a.getArg("s3.path", "blocks");
        String bucket = a.getArg("s3.bucket");
        String region = a.getArg("s3.region");
        String accessKey = a.getArg("s3.accessKey", "");
        String secretKey = a.getArg("s3.secretKey", "");
        String regionEndpoint = a.getArg("s3.region.endpoint", bucket + ".amazonaws.com");
        return new S3Config(path, bucket, region, accessKey, secretKey, regionEndpoint);
    }
}
