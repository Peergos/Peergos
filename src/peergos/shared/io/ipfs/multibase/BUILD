java_library(
    name="Base16",
    srcs=["Base16.java"],
    deps=["//src/peergos/shared/util:ArrayOps"]
)

java_library(
    name="Base36",
    srcs=["Base36.java"],
)

java_library(
    name="Base58",
    srcs=["Base58.java"],
    visibility=["//visibility:public"],
)

java_library(
    name="CharEncoding",
    srcs=["CharEncoding.java"],
    visibility=["//visibility:public"],
)

java_library(
    name="BinaryDecoder",
    srcs=["BinaryDecoder.java"],
    deps=[":DecoderException", ":Decoder"],
    visibility=["//visibility:public"],
)

java_library(
    name="BinaryEncoder",
    srcs=["BinaryEncoder.java"],
    deps=[":EncoderException", ":Encoder"],
    visibility=["//visibility:public"],
)

java_library(
    name="Charsets",
    srcs=["Charsets.java"],
    deps=[":CharEncoding"],
    visibility=["//visibility:public"],
)

java_library(
    name="Decoder",
    srcs=["Decoder.java"],
    deps=[":DecoderException"],
    visibility=["//visibility:public"],
)

java_library(
    name="DecoderException",
    srcs=["DecoderException.java"],
    visibility=["//visibility:public"],
)

java_library(
    name="Encoder",
    srcs=["Encoder.java"],
    deps=[":EncoderException"],
    visibility=["//visibility:public"],
)

java_library(
    name="EncoderException",
    srcs=["EncoderException.java"],
    visibility=["//visibility:public"],
)

java_library(
    name="Multibase",
    srcs=["Multibase.java"],
    visibility=["//visibility:public"],
    deps=[
        ":Base16",
        ":Base36",
        ":Base58",
        "//src/peergos/shared/io/ipfs/multibase/binary:Base32",
        "//src/peergos/shared/io/ipfs/multibase/binary:Base64",
    ],
)
