# java_library(
#     name="crypto",
#     srcs=glob(["*.java"]),
#     visibility=["//visibility:public"],
#     deps=[
#         "//src/peergos/shared/cbor:Cbor",
#         "//src/peergos/shared/io/ipfs/multihash:Multihash",
#         "//src/peergos/shared/util:ArrayOps",
#         "//src/peergos/shared/crypto/asymmetric:asymmetric",
#         "//src/peergos/shared/crypto/symmetric:symmetric",
#         "//src/peergos/shared/crypto/random:SafeRandom",
#         "@gwt_jsinterop_artifact//:gwt_jsinterop_artifact",
#         "//src/peergos/shared/user/fs:Chunk",
#         "//src/peergos/shared/crypto/hash:hash",
#         "//src/peergos/shared/util:Futures",
#         "//src/peergos/shared/util:ProgressConsumer",
#         "//src/peergos/shared/util:Pair",
#         "//src/peergos/shared/user/fs:Fragment",
#         "//src/peergos/shared/user/fs:FragmentWithHash",
#     ],
# )

java_library(
    name="ProofOfWork",
    srcs=["ProofOfWork.java"],
    visibility=["//visibility:public"],
    deps=[
        "//src/peergos/shared/cbor:Cbor",
        "//src/peergos/shared/io/ipfs/multihash:Multihash",
        "@gwt_jsinterop_artifact//:gwt_jsinterop_artifact",
    ],
)
