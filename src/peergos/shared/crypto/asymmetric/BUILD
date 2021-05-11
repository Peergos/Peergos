
java_library(
    name="asymmetric",
    srcs=glob(["*.java"]) + glob(["curve25519/*.java"]),
    visibility=["//visibility:public"],
    deps=[
        "//src/peergos/shared/cbor:Cbor",
        "//src/peergos/shared/util:ArrayOps",
        "//src/peergos/shared/crypto/random:SafeRandom",
        "@gwt_jsinterop_artifact//:gwt_jsinterop_artifact",
        "//src/peergos/shared/crypto/random:JSNaCl",
    ],
)
