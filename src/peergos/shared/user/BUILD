
java_library(
    name="user",
    srcs=glob(["*.java"]),
    visibility=["//visibility:public"],
    deps=[
    ]
)

java_library(
    name="Trio",
    srcs=[
        "ScryptGenerator.java",
        "RandomSecretType.java",
        "SecretGenerationAlgorithm.java",
    ],
    visibility=["//visibility:public"],
    deps=[
        "//src/peergos/shared/cbor:Cbor",
        "//src/peergos/shared/util:ArrayOps",
        "//src/peergos/shared/crypto/random:SafeRandom",
        "@gwt_jsinterop_artifact//:gwt_jsinterop_artifact",
    ]
)
