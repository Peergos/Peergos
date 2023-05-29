package peergos.server.storage;

import peergos.server.util.*;

import java.nio.file.*;
import java.util.*;
import java.util.stream.*;

/**
 * A Utility for installing IPFS and associated plugins.
 */
public class IpfsInstaller {

    public interface Plugin {

        void ensureInstalled(Path ipfsDir);

        void configure(IpfsWrapper ipfs);

        final class S3 implements Plugin {
            public static final String TYPE = "S3";
            public final String path, bucket, region, accessKey, secretKey, regionEndpoint;
            public final int workers;

            public S3(S3Config config, int workers) {
                this.path = config.path;
                this.bucket = config.bucket;
                this.region = config.region;
                this.accessKey = config.accessKey;
                this.secretKey = config.secretKey;
                this.regionEndpoint = config.regionEndpoint;
                this.workers = workers;
            }

            public String getFileName() {
                return "go-ds-s3.so";
            }

            public Object toJson() {
                Map<String, Object> res = new TreeMap<>();
                Map<String, Object> child = new TreeMap<>();
                child.put("path", path);
                child.put("bucket", bucket);
                child.put("accessKey", accessKey);
                child.put("secretKey", secretKey);
                child.put("workers", workers);
                child.put("region", region);
                child.put("regionEndpoint", regionEndpoint);
                child.put("type", "s3ds");
                res.put("child", child);
                res.put("mountpoint", "/blocks");
                res.put("prefix", "s3.datastore");
                res.put("type", "measure");
                return res;
            }

            public static S3 build(Args a) {
                S3Config config = S3Config.build(a, Optional.empty());
                int workers = Integer.parseInt(a.getArg("ipfs-s3-workers", "5"));
                return new S3(config, workers);
            }

            @Override
            public void configure(IpfsWrapper ipfs) {
                // Do the configuration dance..
                System.out.println("Configuring S3 datastore IPFS plugin");
                throw new IllegalStateException("Not implemented yet!");
                /*
                // update the config file
                List<Object> mount = Arrays.asList(
                        toJson(),
                        JSONParser.parse("{\n" +
                                "          \"child\": {\n" +
                                "            \"compression\": \"none\",\n" +
                                "            \"path\": \"datastore\",\n" +
                                "            \"type\": \"levelds\"\n" +
                                "          },\n" +
                                "          \"mountpoint\": \"/\",\n" +
                                "          \"prefix\": \"leveldb.datastore\",\n" +
                                "          \"type\": \"measure\"\n" +
                                "        }")
                );
                String mounts = JSONParser.toString(mount);
                ipfs.setConfig("Datastore.Spec.mounts", mounts);

                // replace the datastore spec file
                String newDataStoreSpec = "{\"mounts\":[{\"bucket\":\"" + bucket +
                        "\",\"mountpoint\":\"/blocks\",\"region\":\"" + region +
                        "\",\"rootDirectory\":\"\"},{\"mountpoint\":\"/\",\"path\":\"datastore\",\"type\":\"levelds\"}],\"type\":\"mount\"}";
                Path specPath = ipfs.ipfsDir.resolve("datastore_spec");
                try {
                    Files.write(specPath, newDataStoreSpec.getBytes());
                } catch (IOException e) {
                    throw new RuntimeException("Couldn't overwrite ipfs datastore spec file", e);
                }*/
            }

            @Override
            public void ensureInstalled(Path ipfsDir) {
            }
        }

        static List<Plugin> parseAll(Args args) {
            List<String> plugins = Arrays.asList(args.getArg("ipfs-plugins", "").split(","))
                    .stream()
                    .filter(s -> !s.isEmpty())
                    .collect(Collectors.toList());

            return plugins.stream()
                    .map(name -> parse(name, args))
                    .collect(Collectors.toList());
        }

        static Plugin parse(String pluginName, Args a) {
            switch (pluginName) {
                case "go-ds-s3": {
                    return S3.build(a);
                }
                default:
                    throw new IllegalStateException("Unknown plugin: " + pluginName);
            }
        }
    }
}
