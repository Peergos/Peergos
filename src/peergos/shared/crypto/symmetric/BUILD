java_library(
    name="Salsa20Poly1305",
    srcs=["Salsa20Poly1305.java"],
    deps = [
        "//src/peergos/shared/crypto/random:JSNaCl",
    ],
)

java_library(
    name="SymmetricKeyTweetNaClKey",
    srcs=["SymmetricKey.java", "TweetNaClKey.java"],
    deps=[
        ":Salsa20Poly1305",
        "//src/peergos/shared/crypto/random:SafeRandom",
        "//src/peergos/shared/cbor:Cbor",
        "@gwt_jsinterop_artifact//:gwt_jsinterop_artifact",
    ]
)
