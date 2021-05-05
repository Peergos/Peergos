java_library(
    name="BitArray",
    srcs=["BitArray.java"],
    visibility=["//visibility:public"],
)

java_library(
    name="BitMatrix",
    srcs=["BitMatrix.java"],
    deps=[":BitArray"],
    visibility=["//visibility:public"],
)

java_library(
    name="BitSource",
    srcs=["BitSource.java"],
    visibility=["//visibility:public"],
)

java_library(
    name="CharacterSetECI",
    srcs=["CharacterSetECI.java"],
    deps=["//src/peergos/shared/zxing:FormatException"],
    visibility=["//visibility:public"],
)

java_library(
    name="DecoderResult",
    srcs=["DecoderResult.java"],
    visibility=["//visibility:public"],
)

# domanski: treat as a sigle compilation unit, circular dep between the files
java_library(
    name="GridSampler",
    srcs=["GridSampler.java", "DefaultGridSampler.java"],
    deps=["//src/peergos/shared/zxing:NotFoundException",
          ":BitMatrix", ":PerspectiveTransform"],
    visibility=["//visibility:public"],
)

java_library(
    name="DetectorResult",
    srcs=["DetectorResult.java"],
    deps=["//src/peergos/shared/zxing:ResultPoint", ":BitMatrix"],
    visibility=["//visibility:public"],
)

java_library(
    name="GlobalHistogramBinarizer",
    srcs=["GlobalHistogramBinarizer.java"],
    deps=["//src/peergos/shared/zxing:NotFoundException",
          "//src/peergos/shared/zxing:Binarizer",
          "//src/peergos/shared/zxing/common:BitArray",
          "//src/peergos/shared/zxing/common:BitMatrix",
          "//src/peergos/shared/zxing:LuminanceSource",
          ]
)

java_library(
    name="HybridBinarizer",
    srcs=["HybridBinarizer.java"],
    deps=[
        ":GlobalHistogramBinarizer",
        "//src/peergos/shared/zxing/common:BitMatrix",
        "//src/peergos/shared/zxing:Binarizer",
        "//src/peergos/shared/zxing:NotFoundException",
        "//src/peergos/shared/zxing:LuminanceSource",
    ]
)


java_library(
    name="PerspectiveTransform",
    srcs=["PerspectiveTransform.java"],
    visibility=["//visibility:public"],
)

java_library(
    name="StringUtils",
    srcs=["StringUtils.java"],
    deps=[
        "//src/peergos/shared/zxing:DecodeHintType",
        "//src/peergos/shared/zxing:ResultPointCallback",
    ],
    visibility=["//visibility:public"],
)
