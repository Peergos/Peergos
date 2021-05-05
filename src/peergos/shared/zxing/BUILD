java_library(
    name="Binarizer",
    srcs=["Binarizer.java"],
    visibility=["//visibility:public"],
    deps=["//src/peergos/shared/zxing/common:BitArray",
          "//src/peergos/shared/zxing/common:BitMatrix", ":LuminanceSource", ":NotFoundException"]
)

java_library(
    name="DecodeHintType",
    srcs=["DecodeHintType.java"],
    visibility=["//visibility:public"],
    deps=[":ResultPoint", ":ResultPointCallback"]
)


java_library(
    name="FormatException",
    srcs=["FormatException.java"],
    visibility=["//visibility:public"],
    deps=[":ReaderException"]
)

java_library(
    name="NotFoundException",
    srcs=["NotFoundException.java"],
    visibility=["//visibility:public"],
    deps=[":ReaderException"]
)

java_library(
    name="LuminanceSource",
    srcs=["LuminanceSource.java", "InvertedLuminanceSource.java"],
    visibility=["//visibility:public"],
)


java_library(
    name="ReaderException",
    srcs=["ReaderException.java"],
)

java_library(
    name="ResultPoint",
    srcs=["ResultPoint.java"],
    visibility=["//visibility:public"],
    deps=["//src/peergos/shared/zxing/common/detector:MathUtils"]
)

java_library(
    name="ResultPointCallback",
    srcs=["ResultPointCallback.java"],
    visibility=["//visibility:public"],
    deps=[":ResultPoint"]
)

