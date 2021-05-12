java_library(
    name = "ArrayOps",
    srcs = ["ArrayOps.java", "ByteArrayWrapper.java"],
    visibility = ["//visibility:public"],
)

java_library(
    name = "CappedProgressConsumer",
    srcs = ["CappedProgressConsumer.java"],
    deps = [":ProgressConsumer"]
)

java_library(
    name = "Constants",
    srcs = ["Constants.java"]
)

java_library(
    name = "EfficientHashMap",
    srcs = ["EfficientHashMap.java"],
)

java_library(
    name = "Either",
    srcs = ["Either.java"],
)

java_library(
    name = "Exceptions",
    srcs = ["Exceptions.java"],
)

# Internal deps
java_library(
    name = "FileUtils",
    srcs = ["FileUtils.java"],
    deps = [
        "//src/peergos/shared/cbor:Cbor",
        "//src/peergos/shared/user/fs:AsyncReader",
        # UserContext
        # Serialize
    ]
)

java_library(
    name = "Futures",
    srcs = ["Futures.java"],
    visibility = ["//visibility:public"],
    deps = ["//external:gwt_jsinterop"],
)

java_library(
    name = "LongUtil",
    srcs = ["LongUtil.java"],
)

java_library(
    name = "LRUCache",
    srcs = ["LRUCache.java"],
    visibility = ["//visibility:public"],
)

java_library(
    name = "Pair",
    srcs = ["Pair.java"],
    visibility = ["//visibility:public"],
)

java_library(
    name = "ProgressConsumer",
    srcs = ["ProgressConsumer.java"],
    visibility = ["//visibility:public"],
    deps = ["//external:gwt_jsinterop"],
)

# External dep on `jsinterop` don't know how to include 
# Internal dep
java_library(
    name = "Serialize",
    srcs = ["Serialize.java"],
    deps = [
        "//src/peergos/shared/cbor:Cbor",
        "//src/peergos/shared/user/fs:AsyncReader",
        # FileWrapper
        # Crypto
        # NetworkAccess
        "//external:gwt_jsinterop"
    ],
)

java_library(
    name = "TimeLimitedClient",
    srcs = ["TimeLimitedClient.java"],
    deps = [
        "//src/peergos/shared/cbor:Cbor",
        "//src/peergos/shared/crypto/asymmetric:asymmetric",
    ]
)

java_library(
    name = "TriFunction",
    srcs = ["TriFunction.java"]
)

java_library(
    name = "Triple",
    srcs = ["Triple.java"]
)

java_library(
    name = "Version",
    srcs = ["Version.java"],
)