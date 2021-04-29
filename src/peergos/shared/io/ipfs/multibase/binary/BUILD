java_library(
    name = "Base32",
    srcs = ["Base32.java"],
    visibility = ["//visibility:public"],
    deps = [
        ":BaseNCodec",
        ":StringUtils",
    ],
)

java_library(
    name = "Base64",
    srcs = ["Base64.java"],
    visibility = ["//visibility:public"],
    deps = [
        ":BaseNCodec",
        ":StringUtils",
    ],
)

java_library(
    name = "BaseNCodec",
    srcs = ["BaseNCodec.java"],
    deps = [
        ":StringUtils",
        "//src/peergos/shared/io/ipfs/multibase:BinaryDecoder",
        "//src/peergos/shared/io/ipfs/multibase:BinaryEncoder",
        "//src/peergos/shared/io/ipfs/multibase:Encoder",
        "//src/peergos/shared/io/ipfs/multibase:EncoderException",
        "//src/peergos/shared/io/ipfs/multibase:Decoder",
        "//src/peergos/shared/io/ipfs/multibase:DecoderException",
    ],
)

java_library(
    name = "StringUtils",
    srcs = ["StringUtils.java"],
    deps = [
        "//src/peergos/shared/io/ipfs/multibase:CharEncoding",
        "//src/peergos/shared/io/ipfs/multibase:Charsets"
    ]
)