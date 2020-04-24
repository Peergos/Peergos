package peergos.server.tests;

import org.junit.*;
import peergos.shared.storage.*;

import java.security.*;
import java.time.*;
import java.util.*;

public class S3V4SignatureTests {
    @Test
    public void validSignature() {
        String accessKey = "AKIAIOSFODNN7EXAMPLE";
        String secretKey = "wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY";
        byte[] payload = "Welcome to Amazon S3.".getBytes();
        String s3Key = "test%24file.text";
        String bucketName = "examplebucket";
        String region = "us-east-1";
        String host = bucketName + ".s3.amazonaws.com";
        String baseUrl = "https://" + host + "/";
        Map<String, String> extraHeaders = new TreeMap<>();
        extraHeaders.put("date", "Fri, 24 May 2013 00:00:00 GMT");
        extraHeaders.put("x-amz-storage-class", "REDUCED_REDUNDANCY");
        Instant timestamp = LocalDate.of(2013, Month.MAY, 24)
                .atStartOfDay()
                .toInstant(ZoneOffset.UTC);
        UploadPolicy.PresignedUrl url = UploadPolicy.preSignUrl(s3Key, payload.length, sha256(payload), false, timestamp,
                "PUT", host, extraHeaders, baseUrl, region, bucketName, accessKey, secretKey);
        Assert.assertTrue(("AWS4-HMAC-SHA256 Credential=AKIAIOSFODNN7EXAMPLE/20130524/us-east-1/s3/aws4_request," +
                "SignedHeaders=date;host;x-amz-content-sha256;x-amz-date;x-amz-storage-class," +
                "Signature=98ad721746da40c64f1a55b78f14c238d841ea1380cd77a1b5971af0ece108bd")
                .equals(url.fields.get("Authorization")));
    }

    private static byte[] sha256(byte[] input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            md.update(input);
            return md.digest();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("couldn't find hash algorithm");
        }
    }
}
