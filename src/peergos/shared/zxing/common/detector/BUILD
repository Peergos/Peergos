java_library(
    name="MathUtils",
    srcs=["MathUtils.java"],
    visibility=["//visibility:public"],
)

java_library(
    name="MonochromeRectangleDetector",
    srcs=["MonochromeRectangleDetector.java"],
    # visibility=["//visibility:public"],
    deps=[
        "//src/peergos/shared/zxing/common:BitMatrix",
        "//src/peergos/shared/zxing:ResultPoint",
        "//src/peergos/shared/zxing:NotFoundException",
    ]
)

java_library(
    name="WhiteRectangleDetector",
    srcs=["WhiteRectangleDetector.java"],
    # visibility=["//visibility:public"],
    deps=[
        "//src/peergos/shared/zxing/common:BitMatrix",
        "//src/peergos/shared/zxing:ResultPoint",
        "//src/peergos/shared/zxing:NotFoundException",
        "//src/peergos/shared/zxing/common/detector:MathUtils",
    ]
)
