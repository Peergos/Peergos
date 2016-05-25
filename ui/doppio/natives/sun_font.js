'use strict';
var Doppio = require('../doppiojvm');
var Long = Doppio.VM.Long;
var sun_font_FreetypeFontScaler = function () {
    function sun_font_FreetypeFontScaler() {
    }
    sun_font_FreetypeFontScaler['initIDs(Ljava/lang/Class;)V'] = function (thread, arg0) {
    };
    sun_font_FreetypeFontScaler['initNativeScaler(Lsun/font/Font2D;IIZI)J'] = function (thread, javaThis, arg0, arg1, arg2, arg3, arg4) {
        thread.throwNewException('Ljava/lang/UnsatisfiedLinkError;', 'Native method not implemented.');
        return null;
    };
    sun_font_FreetypeFontScaler['getFontMetricsNative(Lsun/font/Font2D;JJ)Lsun/font/StrikeMetrics;'] = function (thread, javaThis, arg0, arg1, arg2) {
        thread.throwNewException('Ljava/lang/UnsatisfiedLinkError;', 'Native method not implemented.');
        return null;
    };
    sun_font_FreetypeFontScaler['getGlyphAdvanceNative(Lsun/font/Font2D;JJI)F'] = function (thread, javaThis, arg0, arg1, arg2, arg3) {
        thread.throwNewException('Ljava/lang/UnsatisfiedLinkError;', 'Native method not implemented.');
        return 0;
    };
    sun_font_FreetypeFontScaler['getGlyphMetricsNative(Lsun/font/Font2D;JJILjava/awt/geom/Point2D$Float;)V'] = function (thread, javaThis, arg0, arg1, arg2, arg3, arg4) {
        thread.throwNewException('Ljava/lang/UnsatisfiedLinkError;', 'Native method not implemented.');
    };
    sun_font_FreetypeFontScaler['getGlyphImageNative(Lsun/font/Font2D;JJI)J'] = function (thread, javaThis, arg0, arg1, arg2, arg3) {
        thread.throwNewException('Ljava/lang/UnsatisfiedLinkError;', 'Native method not implemented.');
        return null;
    };
    sun_font_FreetypeFontScaler['getGlyphOutlineBoundsNative(Lsun/font/Font2D;JJI)Ljava/awt/geom/Rectangle2D$Float;'] = function (thread, javaThis, arg0, arg1, arg2, arg3) {
        thread.throwNewException('Ljava/lang/UnsatisfiedLinkError;', 'Native method not implemented.');
        return null;
    };
    sun_font_FreetypeFontScaler['getGlyphOutlineNative(Lsun/font/Font2D;JJIFF)Ljava/awt/geom/GeneralPath;'] = function (thread, javaThis, arg0, arg1, arg2, arg3, arg4, arg5) {
        thread.throwNewException('Ljava/lang/UnsatisfiedLinkError;', 'Native method not implemented.');
        return null;
    };
    sun_font_FreetypeFontScaler['getGlyphVectorOutlineNative(Lsun/font/Font2D;JJ[IIFF)Ljava/awt/geom/GeneralPath;'] = function (thread, javaThis, arg0, arg1, arg2, arg3, arg4, arg5, arg6) {
        thread.throwNewException('Ljava/lang/UnsatisfiedLinkError;', 'Native method not implemented.');
        return null;
    };
    sun_font_FreetypeFontScaler['getGlyphPointNative(Lsun/font/Font2D;JJII)Ljava/awt/geom/Point2D$Float;'] = function (thread, javaThis, arg0, arg1, arg2, arg3, arg4) {
        thread.throwNewException('Ljava/lang/UnsatisfiedLinkError;', 'Native method not implemented.');
        return null;
    };
    sun_font_FreetypeFontScaler['getLayoutTableCacheNative(J)J'] = function (thread, javaThis, arg0) {
        thread.throwNewException('Ljava/lang/UnsatisfiedLinkError;', 'Native method not implemented.');
        return null;
    };
    sun_font_FreetypeFontScaler['disposeNativeScaler(Lsun/font/Font2D;J)V'] = function (thread, javaThis, arg0) {
        thread.throwNewException('Ljava/lang/UnsatisfiedLinkError;', 'Native method not implemented.');
    };
    sun_font_FreetypeFontScaler['getNumGlyphsNative(J)I'] = function (thread, javaThis, arg0) {
        thread.throwNewException('Ljava/lang/UnsatisfiedLinkError;', 'Native method not implemented.');
        return 0;
    };
    sun_font_FreetypeFontScaler['getMissingGlyphCodeNative(J)I'] = function (thread, javaThis, arg0) {
        thread.throwNewException('Ljava/lang/UnsatisfiedLinkError;', 'Native method not implemented.');
        return 0;
    };
    sun_font_FreetypeFontScaler['getUnitsPerEMNative(J)J'] = function (thread, javaThis, arg0) {
        thread.throwNewException('Ljava/lang/UnsatisfiedLinkError;', 'Native method not implemented.');
        return null;
    };
    sun_font_FreetypeFontScaler['createScalerContextNative(J[DIIFF)J'] = function (thread, javaThis, arg0, arg1, arg2, arg3, arg4, arg5, arg6) {
        thread.throwNewException('Ljava/lang/UnsatisfiedLinkError;', 'Native method not implemented.');
        return null;
    };
    return sun_font_FreetypeFontScaler;
}();
var sun_font_StrikeCache = function () {
    function sun_font_StrikeCache() {
    }
    sun_font_StrikeCache['getGlyphCacheDescription([J)V'] = function (thread, infoArray) {
        infoArray.array[0] = Long.fromInt(8);
        infoArray.array[1] = Long.fromInt(8);
    };
    sun_font_StrikeCache['freeIntPointer(I)V'] = function (thread, arg0) {
        thread.throwNewException('Ljava/lang/UnsatisfiedLinkError;', 'Native method not implemented.');
    };
    sun_font_StrikeCache['freeLongPointer(J)V'] = function (thread, arg0) {
        thread.throwNewException('Ljava/lang/UnsatisfiedLinkError;', 'Native method not implemented.');
    };
    sun_font_StrikeCache['freeIntMemory([IJ)V'] = function (thread, arg0, arg1) {
        thread.throwNewException('Ljava/lang/UnsatisfiedLinkError;', 'Native method not implemented.');
    };
    sun_font_StrikeCache['freeLongMemory([JJ)V'] = function (thread, arg0, arg1) {
        thread.throwNewException('Ljava/lang/UnsatisfiedLinkError;', 'Native method not implemented.');
    };
    return sun_font_StrikeCache;
}();
registerNatives({
    'sun/font/FreetypeFontScaler': sun_font_FreetypeFontScaler,
    'sun/font/StrikeCache': sun_font_StrikeCache
});
//# sourceMappingURL=sun_font.js.map