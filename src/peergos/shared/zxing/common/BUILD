java_library(
    name="BitArray",
    srcs=["BitArray.java"],
)

java_library(
    name="BitMatrix",
    srcs=["BitMatrix.java"],
    deps=[":BitArray"]
)

java_library(
    name="BitSource",
    srcs=["BitSource.java"],
)

java_library(
    name="CharacterSetECI",
    srcs=["CharacterSetECI.java"],
    deps=["//src/peergos/shared/zxing:FormatException"]
)

java_library(
    name="DecoderResult",
    srcs=["DecoderResult.java"],
)

# domanski: treat as a sigle compilation unit, circular dep between the files
java_library(
    name="GridSampler",
    srcs=["GridSampler.java", "DefaultGridSampler.java"],
    deps=["//src/peergos/shared/zxing:NotFoundException", ":BitMatrix", ":PerspectiveTransform"]
)

java_library(
    name="DetectorResult",
    srcs=["DetectorResult.java"],
    deps=["//src/peergos/shared/zxing:ResultPoint", ":BitMatrix"]
)


java_library(
    name="PerspectiveTransform",
    srcs=["PerspectiveTransform.java"],
)
