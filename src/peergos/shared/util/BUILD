
# These form a circular dependancy, a pathological example, 
# treat as a single compilation unit
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
# java_library(
#     name = "FileUtils",
#     srcs = ["FileUtils.java"],
# )

java_library(
    name = "Futures",
    srcs = ["Futures.java"],
    deps = ["//external:gwt_jsinterop"],
)

java_library(
    name = "LongUtil",
    srcs = ["LongUtil.java"],
)

java_library(
    name = "LRUCache",
    srcs = ["LRUCache.java"],
)

java_library(
    name = "Pair",
    srcs = ["Pair.java"],
)

java_library(
    name = "ProgressConsumer",
    srcs = ["ProgressConsumer.java"],
    deps = ["//external:gwt_jsinterop"],
)

# External dep on `jsinterop` don't know how to include 
# Internal dep
# java_library(
#     name = "Serialize",
#     srcs = ["Serialize.java"],
#     deps = ["//external:gwt_jsinterop"],
# )

# Internal dep
# java_library(
#     name = "TimeLimitedClient",
#     srcs = ["TimeLimitedClient.java"],
# )

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