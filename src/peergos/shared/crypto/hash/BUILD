java_library(
    name="BaseHash",
    srcs=["BaseHash.java"],
)

java_library(
    name="Blake2b",
    srcs=["Blake2b.java"],
)

# java_library(
#     name="Hash",
#     srcs=["Hash.java"],
# )

# java_library(
#     name="Hasher",
#     srcs=["Hasher.java"],
# )

java_library(
    name="Sha256",
    srcs=["Sha256.java"],
    deps=[
        ":BaseHash",
        "//src/peergos/shared/util:ArrayOps",
    ],
)
