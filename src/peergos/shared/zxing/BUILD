java_library(
    name="BarcodeFormat",
    srcs=["BarcodeFormat.java"],
    visibility=["//visibility:public"],
)

java_library(
    name="Binarizer",
    srcs=["Binarizer.java"],
    visibility=["//visibility:public"],
    deps=["//src/peergos/shared/zxing/common:BitArray",
          "//src/peergos/shared/zxing/common:BitMatrix", ":LuminanceSource", ":NotFoundException"]
)

java_library(
    name="BinaryBitmap",
    srcs=["BinaryBitmap.java"],
    deps=[
        ":Binarizer",
        ":NotFoundException",
        ":LuminanceSource",
        "//src/peergos/shared/zxing/common:BitArray",
        "//src/peergos/shared/zxing/common:BitMatrix",
    ],
    visibility=["//visibility:public"],
)

java_library(
    name="ChecksumException",
    srcs=["ChecksumException.java"],
    visibility=["//visibility:public"],
    deps=[":ReaderException"]
)

java_library(
    name="DecodeHintType",
    srcs=["DecodeHintType.java"],
    visibility=["//visibility:public"],
    deps=[":ResultPoint", ":ResultPointCallback"]
)

java_library(
    name="Dimension",
    srcs=["Dimension.java"],
)

java_library(
    name="EncodeHintType",
    srcs=["EncodeHintType.java"],
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
    name="LuminanceSource",
    srcs=["LuminanceSource.java", "InvertedLuminanceSource.java"],
    visibility=["//visibility:public"],
)

java_library(
    name="NotFoundException",
    srcs=["NotFoundException.java"],
    visibility=["//visibility:public"],
    deps=[":ReaderException"]
)

java_library(
    name="PlanarYUVLuminanceSource",
    srcs=["PlanarYUVLuminanceSource.java"],
    deps=[":LuminanceSource"]
)

java_library(
    name="Reader",
    srcs=["Reader.java"],
    deps=[
        ":ChecksumException",
        ":FormatException",
        ":NotFoundException",
        ":BinaryBitmap",
        ":DecodeHintType",
        ":Result",
    ],
    visibility=["//visibility:public"],
)

java_library(
    name="ReaderException",
    srcs=["ReaderException.java"],
)

java_library(
    name="Result",
    srcs=["Result.java"],
    deps=[
        ":ResultPoint",
        ":ResultMetadataType",
        ":BarcodeFormat",
    ],
    visibility=["//visibility:public"],
)

java_library(
    name="ResultMetadataType",
    srcs=["ResultMetadataType.java"],
    visibility=["//visibility:public"],
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

java_library(
    name="RGBLuminanceSource",
    srcs=["RGBLuminanceSource.java"],
    deps=[":LuminanceSource"]
)


java_library(
    name="Writer",
    srcs=["Writer.java"],
    deps=[
        ":BarcodeFormat",
        ":WriterException",
        ":EncodeHintType",
        "//src/peergos/shared/zxing/common:BitMatrix",
    ],
    visibility=["//visibility:public"],
)

java_library(
    name="WriterException",
    srcs=["WriterException.java"],
    visibility=["//visibility:public"],
)
