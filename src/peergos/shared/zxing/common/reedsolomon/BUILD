# domanski: treat as a sigle compilation unit, circular dep between the files
java_library(
    name="GenericGF",
    srcs=["GenericGF.java", "GenericGFPoly.java"],
    visibility=["//visibility:public"],
)

java_library(
    name="ReedSolomonDecoder",
    srcs=["ReedSolomonDecoder.java"],
    deps=[":GenericGF", ":ReedSolomonException"],
    visibility=["//visibility:public"],
)

java_library(
    name="ReedSolomonEncoder",
    srcs=["ReedSolomonEncoder.java"],
    deps=[":GenericGF"],
    visibility=["//visibility:public"],
)

java_library(
    name="ReedSolomonException",
    srcs=["ReedSolomonException.java"],
    visibility=["//visibility:public"],
)
