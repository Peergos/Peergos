'use strict';
var Doppio = require('../doppiojvm');
var logging = Doppio.Debug.Logging;
var util = Doppio.VM.Util;
var classes_doppio_Debug = function () {
    function classes_doppio_Debug() {
    }
    classes_doppio_Debug['SetLogLevel(Lclasses/doppio/Debug$LogLevel;)V'] = function (thread, loglevel) {
        logging.log_level = loglevel['classes/doppio/Debug$LogLevel/level'];
    };
    classes_doppio_Debug['GetLogLevel()Lclasses/doppio/Debug$LogLevel;'] = function (thread) {
        var ll_cls = thread.getBsCl().getInitializedClass(thread, 'Lclasses/doppio/Debug$LogLevel;').getConstructor(thread);
        switch (logging.log_level) {
        case 10:
            return ll_cls['classes/doppio/Debug$LogLevel/VTRACE'];
        case 9:
            return ll_cls['classes/doppio/Debug$LogLevel/TRACE'];
        case 5:
            return ll_cls['classes/doppio/Debug$LogLevel/DEBUG'];
        default:
            return ll_cls['classes/doppio/Debug$LogLevel/ERROR'];
        }
    };
    return classes_doppio_Debug;
}();
var classes_doppio_JavaScript = function () {
    function classes_doppio_JavaScript() {
    }
    classes_doppio_JavaScript['eval(Ljava/lang/String;)Ljava/lang/String;'] = function (thread, to_eval) {
        try {
            var rv = eval(to_eval.toString());
            if (rv != null) {
                return util.initString(thread.getBsCl(), '' + rv);
            } else {
                return null;
            }
        } catch (e) {
            thread.throwNewException('Ljava/lang/Exception;', 'Error evaluating string: ' + e);
        }
    };
    return classes_doppio_JavaScript;
}();
registerNatives({
    'classes/doppio/Debug': classes_doppio_Debug,
    'classes/doppio/JavaScript': classes_doppio_JavaScript
});
//# sourceMappingURL=classes_doppio.js.map