
java_library(
    name="encoder",
    srcs=glob(["*.java"]),
    deps=[
        "//src/peergos/shared/zxing:EncodeHintType",
        "//src/peergos/shared/zxing:WriterException",
        "//src/peergos/shared/zxing/common:BitArray",
        "//src/peergos/shared/zxing/common:CharacterSetECI",
        "//src/peergos/shared/zxing/common/reedsolomon:GenericGF",
        "//src/peergos/shared/zxing/common/reedsolomon:ReedSolomonEncoder",
        "//src/peergos/shared/zxing/qrcode/decoder:decoder",
    ],
    visibility=["//visibility:public"],
)
