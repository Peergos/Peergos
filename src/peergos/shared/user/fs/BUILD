java_library(
    name="Capability",
    srcs=["AbsoluteCapability.java", "WritableAbsoluteCapability.java", "RelativeCapability.java"],
    deps=[
        "//src/peergos/shared/cbor:Cbor",
        "@gwt_jsinterop_artifact//:gwt_jsinterop_artifact",
        "//src/peergos/shared/util:ArrayOps",
        "//src/peergos/shared/io/ipfs/multibase:Base58",
        "//src/peergos/shared/crypto:crypto",
        "//src/peergos/shared/crypto/hash:hash",
    ]
)

java_library(
    name="AsyncReader",
    srcs=["AsyncReader.java"],
    visibility=["//visibility:public"],
    deps=[
        ":Chunk",
        "//src/peergos/shared/cbor:Cbor",
        "//src/peergos/shared/util:Futures",
        # "//src/peergos/shared/io/ipfs/multibase:Base58",
        # "//src/peergos/shared/io/ipfs/multihash:Multihash",
        # "//src/peergos/shared/util:ArrayOps",
        "@gwt_jsinterop_artifact//:gwt_jsinterop_artifact",
    ]
)


java_library(
    name="Chunk",
    srcs=["Chunk.java"],
    visibility=["//visibility:public"],
    deps=[
        "//src/peergos/shared/crypto/symmetric:symmetric"
    ]
)


java_library(
    name="Fragment",
    srcs=["Fragment.java"],
    visibility=["//visibility:public"],
    deps=[
    ]
)


java_library(
    name="FragmentWithHash",
    srcs=["FragmentWithHash.java"],
    visibility=["//visibility:public"],
    deps=[
        ":Fragment",
        "//src/peergos/shared/io/ipfs/multihash:Multihash",
    ]
)

