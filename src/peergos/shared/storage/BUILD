
java_library(
    name="BlockStoreProperties",
    srcs=["BlockStoreProperties.java"],
    deps=[
        "//src/peergos/shared/cbor:Cbor",
    ],
    # visibility=["//visibility:public"],
)

java_library(
    name="Storage",
    srcs=[
        "DelegatingStorage.java",
        "ContentAddressedStorage.java",
        "CachingStorage.java",
    ],
    deps=[
        ":TransactionId",
        ":PresignedUrl",
        ":WriteAuthRequest",
        ":BlockStoreProperties",
        "//src/peergos/shared/user/fs:AsyncReader",
        "//src/peergos/shared/io/ipfs/multihash:Multihash",
        "//src/peergos/shared/crypto/hash:hash",
        "//src/peergos/shared/cbor:Cbor",
        "//src/peergos/shared/crypto/asymmetric",
        "//src/peergos/shared/io/ipfs/cid:Cid",
        "//src/peergos/shared/util:ArrayOps",
        "//src/peergos/shared/util:Futures",
        "//src/peergos/shared/io/ipfs/api:JSONParser",
        "//src/peergos/shared/util:LRUCache",
        "//src/peergos/shared:MaybeMultihash",
        "//src/peergos/shared/util:ProgressConsumer",
        "//src/peergos/shared/util:Pair",
        "//src/peergos/shared/user:HttpPoster",
        "//src/peergos/shared/user/fs:FragmentWithHash",
    ]
)

java_library(
    name="TransactionId",
    srcs=["TransactionId.java"],
)

java_library(
    name="PresignedUrl",
    srcs=["PresignedUrl.java"],
    deps=[
        "//src/peergos/shared/cbor:Cbor",
    ]
)

java_library(
    name="WriteAuthRequest",
    srcs=["WriteAuthRequest.java"],
    deps=[
        "//src/peergos/shared/cbor:Cbor",
    ]
)
