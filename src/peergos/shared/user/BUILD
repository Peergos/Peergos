


java_library(
    name="Secret",
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

java_library(
    name="HttpPoster",
    srcs=["HttpPoster.java"],
    visibility=["//visibility:public"],
    deps=[
    ]
)