package peergos.server.tests;

import org.junit.*;
import peergos.server.*;
import peergos.server.storage.*;
import peergos.shared.*;
import peergos.shared.crypto.hash.*;
import peergos.shared.storage.*;
import peergos.shared.storage.auth.*;
import peergos.shared.util.*;

import java.time.*;
import java.util.*;

public class S3V4SignatureTests {
    private static final Crypto crypto = Main.initCrypto();
    private static final Hasher h = crypto.hasher;

    @Test
    public void validPutSignature() {
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
        String timestamp = S3AdminRequests.asAwsDate(LocalDate.of(2013, Month.MAY, 24)
                .atStartOfDay()
                .toInstant(ZoneOffset.UTC)
                .atZone(ZoneId.of("UTC")));
        String contentSha256 = ArrayOps.bytesToHex(Hash.sha256(payload));

        S3Request policy = new S3Request("PUT", host, s3Key, contentSha256, Optional.empty(), false, true,
                Collections.emptyMap(), extraHeaders, accessKey, region, timestamp);
        String toSign = policy.stringToSign();
        Assert.assertTrue(toSign.equals("AWS4-HMAC-SHA256\n" +
                "20130524T000000Z\n" +
                "20130524/us-east-1/s3/aws4_request\n" +
                "9e0e90d9c76de8fa5b200d8c849cd5b8dc7a3be3951ddb7f6a76b4158342019d"));

        String signature = S3Request.computeSignature(policy, secretKey, h).join();
        Assert.assertTrue(signature.equals("98ad721746da40c64f1a55b78f14c238d841ea1380cd77a1b5971af0ece108bd"));
    }

    @Test
    public void validGetSignature() {
        String accessKey = "AKIAIOSFODNN7EXAMPLE";
        String secretKey = "wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY";
        String s3Key = "test.txt";
        String bucketName = "examplebucket";
        String region = "us-east-1";
        String host = bucketName + ".s3.amazonaws.com";
        String timestamp = S3AdminRequests.asAwsDate(LocalDate.of(2013, Month.MAY, 24)
                .atStartOfDay()
                .toInstant(ZoneOffset.UTC)
                .atZone(ZoneId.of("UTC")));
        String contentSha256 = "UNSIGNED-PAYLOAD";

        S3Request policy = new S3Request("GET", host, s3Key, contentSha256, Optional.of(86400), false, false,
                Collections.emptyMap(), Collections.emptyMap(), accessKey, region, timestamp);
        String toSign = policy.stringToSign();
        Assert.assertTrue(toSign.equals("AWS4-HMAC-SHA256\n" +
                "20130524T000000Z\n" +
                "20130524/us-east-1/s3/aws4_request\n" +
                "3bfa292879f6447bbcda7001decf97f4a54dc650c8942174ae0a9121cf58ad04"));

        String signature = S3Request.computeSignature(policy, secretKey, h).join();
        Assert.assertTrue(signature.equals("aeeed9bbccd4d02ee5c0109b86d86835f995330da4c265957d157751f604d404"));
    }

    @Test
    public void linodeSignature() {
        String accessKey = "AKIAIOSFODNN7EXAMPLE";
        String secretKey = "wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY";
        byte[] payload = "Hi Linode!".getBytes();
        String s3Key = "AFKREIGBKDTWGUVD3UTHMNCKKLMIL6YE5MI4XSEYCZOWP7WTOUJ6XNMASU";
        String bucketName = "peergos-test";
        String region = "us-east-1";
        String host = bucketName + "." +region + ".linodeobjects.com";
        Map<String, String> extraHeaders = new TreeMap<>();
        extraHeaders.put("amz-sdk-invocation-id", "e84d52c4-953e-e1d2-d442-f05ca7067524");
        extraHeaders.put("amz-sdk-retry", "0/0/500");
        extraHeaders.put("Content-Length", "" + payload.length);
        extraHeaders.put("Content-Type", "application/octet-stream");
        extraHeaders.put("User-Agent", "aws-sdk-java/1.11.705 Linux/5.3.0-46-generic OpenJDK_64-Bit_Server_VM/11.0.7+10-post-Ubuntu-2ubuntu218.04 java/11.0.7 vendor/Ubuntu");
        String timestamp = S3AdminRequests.asAwsDate(LocalDate.of(2020, Month.APRIL, 25)
                .atStartOfDay()
                .withHour(20)
                .withMinute(41)
                .withSecond(56)
                .withNano(0)
                .toInstant(ZoneOffset.UTC)
                .atZone(ZoneId.of("UTC")));
        String contentSha256 = "UNSIGNED-PAYLOAD";

        S3Request policy = new S3Request("PUT", host, s3Key, contentSha256, Optional.empty(), false, true,
                Collections.emptyMap(), extraHeaders, accessKey, region, timestamp);

        String canonicalRequest = policy.toCanonicalRequest();
        Assert.assertTrue(canonicalRequest.equals("PUT\n" +
                "/AFKREIGBKDTWGUVD3UTHMNCKKLMIL6YE5MI4XSEYCZOWP7WTOUJ6XNMASU\n" +
                "\n" +
                "amz-sdk-invocation-id:e84d52c4-953e-e1d2-d442-f05ca7067524\n" +
                "amz-sdk-retry:0/0/500\n" +
                "content-length:10\n" +
                "content-type:application/octet-stream\n" +
                "host:peergos-test.us-east-1.linodeobjects.com\n" +
                "user-agent:aws-sdk-java/1.11.705 Linux/5.3.0-46-generic OpenJDK_64-Bit_Server_VM/11.0.7+10-post-Ubuntu-2ubuntu218.04 java/11.0.7 vendor/Ubuntu\n" +
                "x-amz-content-sha256:UNSIGNED-PAYLOAD\n" +
                "x-amz-date:20200425T204156Z\n" +
                "\n" +
                "amz-sdk-invocation-id;amz-sdk-retry;content-length;content-type;host;user-agent;x-amz-content-sha256;x-amz-date\n" +
                "UNSIGNED-PAYLOAD"));

        String toSign = policy.stringToSign();
        Assert.assertTrue(toSign.equals("AWS4-HMAC-SHA256\n" +
                "20200425T204156Z\n" +
                "20200425/us-east-1/s3/aws4_request\n" +
                "8dc8ddc0eef8bc2f62ef0ae12a89df788b73780404358bfaba915d58096b9cec"));

        String signature = S3Request.computeSignature(policy, secretKey, h).join();
        Assert.assertTrue(signature.equals("5cc3daea623ac6d43b482209892cc6eb95e46b068e232eabd85343caf79bb17e"));

        PresignedUrl url = S3Request.preSignPut(s3Key, payload.length, contentSha256, false, timestamp,
                host, extraHeaders, region, accessKey, secretKey, true, h).join();
        Assert.assertTrue(("AWS4-HMAC-SHA256 Credential=AKIAIOSFODNN7EXAMPLE/20200425/us-east-1/s3/aws4_request," +
                "SignedHeaders=amz-sdk-invocation-id;amz-sdk-retry;content-length;content-type;host;user-agent;x-amz-content-sha256;x-amz-date," +
                "Signature=5cc3daea623ac6d43b482209892cc6eb95e46b068e232eabd85343caf79bb17e")
                .equals(url.fields.get("Authorization")));
    }
}
