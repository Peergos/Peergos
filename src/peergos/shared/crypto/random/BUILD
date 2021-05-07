java_library(
    name="JSNaCl",
    srcs=["JSNaCl.java"],
    visibility=["//visibility:public"],
    deps = ["//external:gwt_jsinterop"],
)

java_library(
    name="SafeRandom",
    srcs=["SafeRandom.java"],
    visibility=["//visibility:public"],
    deps = ["//external:gwt_jsinterop", ":JSNaCl"],
)
