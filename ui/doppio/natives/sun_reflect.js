'use strict';
var Doppio = require('../doppiojvm');
var util = Doppio.VM.Util;
var ThreadStatus = Doppio.VM.Enums.ThreadStatus;
var assert = Doppio.Debug.Assert;
var sun_reflect_ConstantPool = function () {
    function sun_reflect_ConstantPool() {
    }
    sun_reflect_ConstantPool['getSize0(Ljava/lang/Object;)I'] = function (thread, javaThis, cp) {
        thread.throwNewException('Ljava/lang/UnsatisfiedLinkError;', 'Native method not implemented.');
        return 0;
    };
    sun_reflect_ConstantPool['getClassAt0(Ljava/lang/Object;I)Ljava/lang/Class;'] = function (thread, javaThis, cp, arg1) {
        thread.throwNewException('Ljava/lang/UnsatisfiedLinkError;', 'Native method not implemented.');
        return null;
    };
    sun_reflect_ConstantPool['getClassAtIfLoaded0(Ljava/lang/Object;I)Ljava/lang/Class;'] = function (thread, javaThis, cp, arg1) {
        thread.throwNewException('Ljava/lang/UnsatisfiedLinkError;', 'Native method not implemented.');
        return null;
    };
    sun_reflect_ConstantPool['getMethodAt0(Ljava/lang/Object;I)Ljava/lang/reflect/Member;'] = function (thread, javaThis, cp, arg1) {
        thread.throwNewException('Ljava/lang/UnsatisfiedLinkError;', 'Native method not implemented.');
        return null;
    };
    sun_reflect_ConstantPool['getMethodAtIfLoaded0(Ljava/lang/Object;I)Ljava/lang/reflect/Member;'] = function (thread, javaThis, cp, arg1) {
        thread.throwNewException('Ljava/lang/UnsatisfiedLinkError;', 'Native method not implemented.');
        return null;
    };
    sun_reflect_ConstantPool['getFieldAt0(Ljava/lang/Object;I)Ljava/lang/reflect/Field;'] = function (thread, javaThis, cp, arg1) {
        thread.throwNewException('Ljava/lang/UnsatisfiedLinkError;', 'Native method not implemented.');
        return null;
    };
    sun_reflect_ConstantPool['getFieldAtIfLoaded0(Ljava/lang/Object;I)Ljava/lang/reflect/Field;'] = function (thread, javaThis, cp, arg1) {
        thread.throwNewException('Ljava/lang/UnsatisfiedLinkError;', 'Native method not implemented.');
        return null;
    };
    sun_reflect_ConstantPool['getMemberRefInfoAt0(Ljava/lang/Object;I)[Ljava/lang/String;'] = function (thread, javaThis, cp, arg1) {
        thread.throwNewException('Ljava/lang/UnsatisfiedLinkError;', 'Native method not implemented.');
        return null;
    };
    sun_reflect_ConstantPool['getIntAt0(Ljava/lang/Object;I)I'] = function (thread, javaThis, cp, idx) {
        return cp.get(idx).value;
    };
    sun_reflect_ConstantPool['getLongAt0(Ljava/lang/Object;I)J'] = function (thread, javaThis, cp, idx) {
        return cp.get(idx).value;
    };
    sun_reflect_ConstantPool['getFloatAt0(Ljava/lang/Object;I)F'] = function (thread, javaThis, cp, arg1) {
        thread.throwNewException('Ljava/lang/UnsatisfiedLinkError;', 'Native method not implemented.');
        return 0;
    };
    sun_reflect_ConstantPool['getDoubleAt0(Ljava/lang/Object;I)D'] = function (thread, javaThis, cp, arg1) {
        thread.throwNewException('Ljava/lang/UnsatisfiedLinkError;', 'Native method not implemented.');
        return 0;
    };
    sun_reflect_ConstantPool['getStringAt0(Ljava/lang/Object;I)Ljava/lang/String;'] = function (thread, javaThis, cp, arg1) {
        thread.throwNewException('Ljava/lang/UnsatisfiedLinkError;', 'Native method not implemented.');
        return null;
    };
    sun_reflect_ConstantPool['getUTF8At0(Ljava/lang/Object;I)Ljava/lang/String;'] = function (thread, javaThis, cp, idx) {
        return util.initString(thread.getBsCl(), cp.get(idx).value);
    };
    return sun_reflect_ConstantPool;
}();
var sun_reflect_NativeConstructorAccessorImpl = function () {
    function sun_reflect_NativeConstructorAccessorImpl() {
    }
    sun_reflect_NativeConstructorAccessorImpl['newInstance0(Ljava/lang/reflect/Constructor;[Ljava/lang/Object;)Ljava/lang/Object;'] = function (thread, m, params) {
        var cls = m['java/lang/reflect/Constructor/clazz'], slot = m['java/lang/reflect/Constructor/slot'];
        thread.setStatus(ThreadStatus.ASYNC_WAITING);
        cls.$cls.initialize(thread, function (cls) {
            if (cls !== null) {
                var method = cls.getMethodFromSlot(slot), obj = new (cls.getConstructor(thread))(thread), cb = function (e) {
                        if (e) {
                            thread.getBsCl().initializeClass(thread, 'Ljava/lang/reflect/InvocationTargetException;', function (cdata) {
                                if (cdata !== null) {
                                    var wrappedE = new (cdata.getConstructor(thread))(thread);
                                    wrappedE['<init>(Ljava/lang/Throwable;)V'](thread, [e], function (e) {
                                        thread.throwException(e ? e : wrappedE);
                                    });
                                }
                            });
                        } else {
                            thread.asyncReturn(obj);
                        }
                    };
                var paramTypes = m['java/lang/reflect/Constructor/parameterTypes'].array.map(function (pType) {
                    return pType.$cls.getInternalName();
                });
                assert(slot >= 0, 'Found a constructor without a slot?!');
                obj[method.signature](thread, params ? util.unboxArguments(thread, paramTypes, params.array) : null, cb);
            }
        }, true);
    };
    return sun_reflect_NativeConstructorAccessorImpl;
}();
var sun_reflect_NativeMethodAccessorImpl = function () {
    function sun_reflect_NativeMethodAccessorImpl() {
    }
    sun_reflect_NativeMethodAccessorImpl['invoke0(Ljava/lang/reflect/Method;Ljava/lang/Object;[Ljava/lang/Object;)Ljava/lang/Object;'] = function (thread, mObj, obj, params) {
        var cls = mObj['java/lang/reflect/Method/clazz'].$cls, slot = mObj['java/lang/reflect/Method/slot'], retType = mObj['java/lang/reflect/Method/returnType'], m = cls.getMethodFromSlot(slot), args = [], cb = function (e, rv) {
                if (e) {
                    thread.getBsCl().initializeClass(thread, 'Ljava/lang/reflect/InvocationTargetException;', function (cdata) {
                        if (cdata !== null) {
                            var wrappedE = new (cdata.getConstructor(thread))(thread);
                            wrappedE['<init>(Ljava/lang/Throwable;)V'](thread, [e], function (e) {
                                thread.throwException(e ? e : wrappedE);
                            });
                        }
                    });
                } else {
                    if (util.is_primitive_type(m.returnType)) {
                        if (m.returnType === 'V') {
                            thread.asyncReturn(null);
                        } else {
                            thread.asyncReturn(retType.$cls.createWrapperObject(thread, rv));
                        }
                    } else {
                        thread.asyncReturn(rv);
                    }
                }
            };
        if (params !== null) {
            args = util.unboxArguments(thread, m.parameterTypes, params.array);
        }
        thread.setStatus(ThreadStatus.ASYNC_WAITING);
        if (m.accessFlags.isStatic()) {
            cls.getConstructor(thread)[m.fullSignature](thread, args, cb);
        } else {
            obj[m.signature](thread, args, cb);
        }
    };
    return sun_reflect_NativeMethodAccessorImpl;
}();
function getCallerClass(thread, framesToSkip) {
    var caller = thread.getStackTrace(), idx = caller.length - 1 - framesToSkip, frame = caller[idx];
    while (frame.method.fullSignature.indexOf('java/lang/reflect/Method/invoke') === 0) {
        if (idx === 0) {
            return null;
        }
        frame = caller[--idx];
    }
    return frame.method.cls.getClassObject(thread);
}
var sun_reflect_Reflection = function () {
    function sun_reflect_Reflection() {
    }
    sun_reflect_Reflection['getCallerClass()Ljava/lang/Class;'] = function (thread) {
        return getCallerClass(thread, 2);
    };
    sun_reflect_Reflection['getClassAccessFlags(Ljava/lang/Class;)I'] = function (thread, classObj) {
        return classObj.$cls.accessFlags.getRawByte();
    };
    sun_reflect_Reflection['getCallerClass(I)Ljava/lang/Class;'] = getCallerClass;
    return sun_reflect_Reflection;
}();
registerNatives({
    'sun/reflect/ConstantPool': sun_reflect_ConstantPool,
    'sun/reflect/NativeConstructorAccessorImpl': sun_reflect_NativeConstructorAccessorImpl,
    'sun/reflect/NativeMethodAccessorImpl': sun_reflect_NativeMethodAccessorImpl,
    'sun/reflect/Reflection': sun_reflect_Reflection
});
//# sourceMappingURL=sun_reflect.js.map