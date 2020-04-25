package peergos.server.tests;

import org.junit.*;
import peergos.server.storage.*;
import peergos.shared.util.*;

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
        Map<String, String> extraHeaders = new TreeMap<>();
        extraHeaders.put("date", "Fri, 24 May 2013 00:00:00 GMT");
        extraHeaders.put("x-amz-storage-class", "REDUCED_REDUNDANCY");
        Instant timestamp = LocalDate.of(2013, Month.MAY, 24)
                .atStartOfDay()
                .toInstant(ZoneOffset.UTC);
        String contentSha256 = ArrayOps.bytesToHex(sha256(payload));

        UploadPolicy policy = new UploadPolicy("PUT", host, s3Key, payload.length, contentSha256, false, extraHeaders,
                bucketName, accessKey, region, timestamp);
        String toSign = policy.stringToSign();
        Assert.assertTrue(toSign.equals("AWS4-HMAC-SHA256\n" +
                "20130524T000000Z\n" +
                "20130524/us-east-1/s3/aws4_request\n" +
                "9e0e90d9c76de8fa5b200d8c849cd5b8dc7a3be3951ddb7f6a76b4158342019d"));

        String signature = UploadPolicy.computeSignature(policy, secretKey);
        Assert.assertTrue(signature.equals("98ad721746da40c64f1a55b78f14c238d841ea1380cd77a1b5971af0ece108bd"));

        UploadPolicy.PresignedUrl url = UploadPolicy.preSignUrl(s3Key, payload.length, contentSha256, false, timestamp,
                "PUT", host, extraHeaders, region, bucketName, accessKey, secretKey);
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
