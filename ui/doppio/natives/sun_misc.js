'use strict';
var Doppio = require('../doppiojvm');
var ReferenceClassData = Doppio.VM.ClassFile.ReferenceClassData;
var ArrayClassData = Doppio.VM.ClassFile.ArrayClassData;
var util = Doppio.VM.Util;
var Long = Doppio.VM.Long;
var ThreadStatus = Doppio.VM.Enums.ThreadStatus;
var assert = Doppio.Debug.Assert;
function getFieldInfo(thread, unsafe, obj, offset) {
    var fieldName, objBase, objCls = obj.getClass(), cls, compName, unsafeCons = unsafe.getClass().getConstructor(thread), stride = 1;
    if (objCls.getInternalName() === 'Ljava/lang/Object;') {
        cls = obj.$staticFieldBase;
        objBase = cls.getConstructor(thread);
        fieldName = cls.getStaticFieldFromVMIndex(offset.toInt()).fullName;
    } else if (objCls instanceof ArrayClassData) {
        compName = util.internal2external[objCls.getInternalName()[1]];
        if (!compName) {
            compName = 'OBJECT';
        }
        compName = compName.toUpperCase();
        stride = unsafeCons['sun/misc/Unsafe/ARRAY_' + compName + '_INDEX_SCALE'];
        if (!stride) {
            stride = 1;
        }
        objBase = obj.array;
        assert(offset.toInt() % stride === 0, 'Invalid offset for stride ' + stride + ': ' + offset.toInt());
        fieldName = '' + offset.toInt() / stride;
    } else {
        cls = obj.getClass();
        objBase = obj;
        fieldName = cls.getObjectFieldFromVMIndex(offset.toInt()).fullName;
    }
    return [
        objBase,
        fieldName
    ];
}
function unsafeCompareAndSwap(thread, unsafe, obj, offset, expected, x) {
    var fi = getFieldInfo(thread, unsafe, obj, offset), actual = fi[0][fi[1]];
    if (actual === expected) {
        fi[0][fi[1]] = x;
        return true;
    } else {
        return false;
    }
}
function getFromVMIndex(thread, unsafe, obj, offset) {
    var fi = getFieldInfo(thread, unsafe, obj, offset);
    return fi[0][fi[1]];
}
function setFromVMIndex(thread, unsafe, obj, offset, val) {
    var fi = getFieldInfo(thread, unsafe, obj, offset);
    fi[0][fi[1]] = val;
}
var sun_misc_GC = function () {
    function sun_misc_GC() {
    }
    sun_misc_GC['maxObjectInspectionAge()J'] = function (thread) {
        thread.throwNewException('Ljava/lang/UnsatisfiedLinkError;', 'Native method not implemented.');
        return null;
    };
    return sun_misc_GC;
}();
var sun_misc_MessageUtils = function () {
    function sun_misc_MessageUtils() {
    }
    sun_misc_MessageUtils['toStderr(Ljava/lang/String;)V'] = function (thread, str) {
        thread.throwNewException('Ljava/lang/UnsatisfiedLinkError;', 'Native method not implemented.');
    };
    sun_misc_MessageUtils['toStdout(Ljava/lang/String;)V'] = function (thread, str) {
        thread.throwNewException('Ljava/lang/UnsatisfiedLinkError;', 'Native method not implemented.');
    };
    return sun_misc_MessageUtils;
}();
var sun_misc_NativeSignalHandler = function () {
    function sun_misc_NativeSignalHandler() {
    }
    sun_misc_NativeSignalHandler['handle0(IJ)V'] = function (thread, arg0, arg1) {
        thread.throwNewException('Ljava/lang/UnsatisfiedLinkError;', 'Native method not implemented.');
    };
    return sun_misc_NativeSignalHandler;
}();
var sun_misc_Perf = function () {
    function sun_misc_Perf() {
    }
    sun_misc_Perf['attach(Ljava/lang/String;II)Ljava/nio/ByteBuffer;'] = function (thread, javaThis, arg0, arg1, arg2) {
        thread.throwNewException('Ljava/lang/UnsatisfiedLinkError;', 'Native method not implemented.');
        return null;
    };
    sun_misc_Perf['detach(Ljava/nio/ByteBuffer;)V'] = function (thread, javaThis, arg0) {
        thread.throwNewException('Ljava/lang/UnsatisfiedLinkError;', 'Native method not implemented.');
    };
    sun_misc_Perf['createLong(Ljava/lang/String;IIJ)Ljava/nio/ByteBuffer;'] = function (thread, javaThis, name, variability, units, value) {
        thread.import('Ljava/nio/DirectByteBuffer;', function (buffCons) {
            var buff = new buffCons(thread), heap = thread.getJVM().getHeap(), addr = heap.malloc(8);
            buff['<init>(JI)V'](thread, [
                Long.fromNumber(addr),
                null,
                8
            ], function (e) {
                if (e) {
                    thread.throwException(e);
                } else {
                    heap.store_word(addr, value.getLowBits());
                    heap.store_word(addr + 4, value.getHighBits());
                    thread.asyncReturn(buff);
                }
            });
        });
    };
    sun_misc_Perf['createByteArray(Ljava/lang/String;II[BI)Ljava/nio/ByteBuffer;'] = function (thread, javaThis, arg0, arg1, arg2, arg3, arg4) {
        thread.throwNewException('Ljava/lang/UnsatisfiedLinkError;', 'Native method not implemented.');
        return null;
    };
    sun_misc_Perf['highResCounter()J'] = function (thread, javaThis) {
        thread.throwNewException('Ljava/lang/UnsatisfiedLinkError;', 'Native method not implemented.');
        return null;
    };
    sun_misc_Perf['highResFrequency()J'] = function (thread, javaThis) {
        thread.throwNewException('Ljava/lang/UnsatisfiedLinkError;', 'Native method not implemented.');
        return null;
    };
    sun_misc_Perf['registerNatives()V'] = function (thread) {
        thread.throwNewException('Ljava/lang/UnsatisfiedLinkError;', 'Native method not implemented.');
    };
    return sun_misc_Perf;
}();
var sun_misc_Signal = function () {
    function sun_misc_Signal() {
    }
    sun_misc_Signal['findSignal(Ljava/lang/String;)I'] = function (thread, arg0) {
        return -1;
    };
    sun_misc_Signal['handle0(IJ)J'] = function (thread, arg0, arg1) {
        thread.throwNewException('Ljava/lang/UnsatisfiedLinkError;', 'Native method not implemented.');
        return null;
    };
    sun_misc_Signal['raise0(I)V'] = function (thread, arg0) {
        thread.throwNewException('Ljava/lang/UnsatisfiedLinkError;', 'Native method not implemented.');
    };
    return sun_misc_Signal;
}();
var sun_misc_Unsafe = function () {
    function sun_misc_Unsafe() {
    }
    sun_misc_Unsafe['getByte(J)B'] = function (thread, javaThis, address) {
        var heap = thread.getJVM().getHeap();
        return heap.get_signed_byte(address.toNumber());
    };
    sun_misc_Unsafe['putByte(JB)V'] = function (thread, javaThis, address, val) {
        var heap = thread.getJVM().getHeap();
        heap.set_signed_byte(address.toNumber(), val);
    };
    sun_misc_Unsafe['getShort(J)S'] = function (thread, javaThis, arg0) {
        thread.throwNewException('Ljava/lang/UnsatisfiedLinkError;', 'Native method not implemented.');
        return 0;
    };
    sun_misc_Unsafe['putShort(JS)V'] = function (thread, javaThis, arg0, arg1) {
        thread.throwNewException('Ljava/lang/UnsatisfiedLinkError;', 'Native method not implemented.');
    };
    sun_misc_Unsafe['getChar(J)C'] = function (thread, javaThis, arg0) {
        thread.throwNewException('Ljava/lang/UnsatisfiedLinkError;', 'Native method not implemented.');
        return 0;
    };
    sun_misc_Unsafe['putChar(JC)V'] = function (thread, javaThis, arg0, arg1) {
        thread.throwNewException('Ljava/lang/UnsatisfiedLinkError;', 'Native method not implemented.');
    };
    sun_misc_Unsafe['getInt(J)I'] = function (thread, javaThis, arg0) {
        thread.throwNewException('Ljava/lang/UnsatisfiedLinkError;', 'Native method not implemented.');
        return 0;
    };
    sun_misc_Unsafe['putInt(JI)V'] = function (thread, javaThis, arg0, arg1) {
        thread.throwNewException('Ljava/lang/UnsatisfiedLinkError;', 'Native method not implemented.');
    };
    sun_misc_Unsafe['getLong(J)J'] = function (thread, javaThis, address) {
        var heap = thread.getJVM().getHeap(), addr = address.toNumber();
        return new Long(heap.get_word(addr), heap.get_word(addr + 4));
    };
    sun_misc_Unsafe['putLong(JJ)V'] = function (thread, javaThis, address, value) {
        var heap = thread.getJVM().getHeap(), addr = address.toNumber();
        heap.store_word(addr, value.getLowBits());
        heap.store_word(addr + 4, value.getHighBits());
    };
    sun_misc_Unsafe['getFloat(J)F'] = function (thread, javaThis, arg0) {
        thread.throwNewException('Ljava/lang/UnsatisfiedLinkError;', 'Native method not implemented.');
        return 0;
    };
    sun_misc_Unsafe['putFloat(JF)V'] = function (thread, javaThis, arg0, arg1) {
        thread.throwNewException('Ljava/lang/UnsatisfiedLinkError;', 'Native method not implemented.');
    };
    sun_misc_Unsafe['getDouble(J)D'] = function (thread, javaThis, arg0) {
        thread.throwNewException('Ljava/lang/UnsatisfiedLinkError;', 'Native method not implemented.');
        return 0;
    };
    sun_misc_Unsafe['putDouble(JD)V'] = function (thread, javaThis, arg0, arg1) {
        thread.throwNewException('Ljava/lang/UnsatisfiedLinkError;', 'Native method not implemented.');
    };
    sun_misc_Unsafe['getAddress(J)J'] = function (thread, javaThis, arg0) {
        thread.throwNewException('Ljava/lang/UnsatisfiedLinkError;', 'Native method not implemented.');
        return null;
    };
    sun_misc_Unsafe['putAddress(JJ)V'] = function (thread, javaThis, arg0, arg1) {
        thread.throwNewException('Ljava/lang/UnsatisfiedLinkError;', 'Native method not implemented.');
    };
    sun_misc_Unsafe['allocateMemory(J)J'] = function (thread, javaThis, size) {
        var heap = thread.getJVM().getHeap();
        return Long.fromNumber(heap.malloc(size.toNumber()));
    };
    sun_misc_Unsafe['reallocateMemory(JJ)J'] = function (thread, javaThis, arg0, arg1) {
        thread.throwNewException('Ljava/lang/UnsatisfiedLinkError;', 'Native method not implemented.');
        return null;
    };
    sun_misc_Unsafe['setMemory(Ljava/lang/Object;JJB)V'] = function (thread, javaThis, obj, address, bytes, value) {
        if (obj === null) {
            var i, addr = address.toNumber(), bytesNum = bytes.toNumber(), heap = thread.getJVM().getHeap();
            for (i = 0; i < bytesNum; i++) {
                heap.set_signed_byte(addr + i, value);
            }
        } else {
            thread.throwNewException('Ljava/lang/UnsatisfiedLinkError;', 'Native method not implemented.');
        }
    };
    sun_misc_Unsafe['copyMemory(Ljava/lang/Object;JLjava/lang/Object;JJ)V'] = function (thread, javaThis, srcBase, srcOffset, destBase, destOffset, bytes) {
        var heap = thread.getJVM().getHeap(), srcAddr = srcOffset.toNumber(), destAddr = destOffset.toNumber(), length = bytes.toNumber();
        if (srcBase === null && destBase === null) {
            heap.memcpy(srcAddr, destAddr, length);
        } else if (srcBase === null && destBase !== null) {
            if (util.is_array_type(destBase.getClass().getInternalName()) && util.is_primitive_type(destBase.getClass().getComponentClass().getInternalName())) {
                var destArray = destBase;
                switch (destArray.getClass().getComponentClass().getInternalName()) {
                case 'B':
                    for (var i = 0; i < length; i++) {
                        destArray.array[destAddr + i] = heap.get_signed_byte(srcAddr + i);
                    }
                    break;
                default:
                    thread.throwNewException('Ljava/lang/UnsatisfiedLinkError;', 'Native method not implemented. destArray type: ' + destArray.getClass().getComponentClass().getInternalName());
                    break;
                }
            } else {
                thread.throwNewException('Ljava/lang/UnsatisfiedLinkError;', 'Native method not implemented.');
            }
        } else if (srcBase !== null && destBase === null) {
            if (util.is_array_type(srcBase.getClass().getInternalName()) && util.is_primitive_type(srcBase.getClass().getComponentClass().getInternalName())) {
                var srcArray = srcBase;
                switch (srcArray.getClass().getComponentClass().getInternalName()) {
                case 'B':
                case 'C':
                    for (var i = 0; i < length; i++) {
                        heap.set_signed_byte(destAddr + i, srcArray.array[srcAddr + i]);
                    }
                    break;
                default:
                    thread.throwNewException('Ljava/lang/UnsatisfiedLinkError;', 'Native method not implemented. srcArray:' + srcArray.getClass().getComponentClass().getInternalName());
                    break;
                }
            } else {
                thread.throwNewException('Ljava/lang/UnsatisfiedLinkError;', 'Native method not implemented.');
            }
        } else {
            thread.throwNewException('Ljava/lang/UnsatisfiedLinkError;', 'Native method not implemented. Both src and dest are arrays?');
        }
    };
    sun_misc_Unsafe['freeMemory(J)V'] = function (thread, javaThis, address) {
        var heap = thread.getJVM().getHeap();
        heap.free(address.toNumber());
    };
    sun_misc_Unsafe['staticFieldOffset(Ljava/lang/reflect/Field;)J'] = function (thread, javaThis, field) {
        var cls = field['java/lang/reflect/Field/clazz'].$cls;
        return Long.fromNumber(cls.getVMIndexForField(cls.getFieldFromSlot(field['java/lang/reflect/Field/slot'])));
    };
    sun_misc_Unsafe['objectFieldOffset(Ljava/lang/reflect/Field;)J'] = function (thread, javaThis, field) {
        var cls = field['java/lang/reflect/Field/clazz'].$cls;
        return Long.fromNumber(cls.getVMIndexForField(cls.getFieldFromSlot(field['java/lang/reflect/Field/slot'])));
    };
    sun_misc_Unsafe['staticFieldBase(Ljava/lang/reflect/Field;)Ljava/lang/Object;'] = function (thread, javaThis, field) {
        var rv = new (thread.getBsCl().getInitializedClass(thread, 'Ljava/lang/Object;').getConstructor(thread))(thread);
        rv.$staticFieldBase = field['java/lang/reflect/Field/clazz'].$cls;
        return rv;
    };
    sun_misc_Unsafe['ensureClassInitialized(Ljava/lang/Class;)V'] = function (thread, javaThis, cls) {
        if (cls.$cls.isInitialized(thread)) {
            return;
        }
        thread.setStatus(ThreadStatus.ASYNC_WAITING);
        cls.$cls.initialize(thread, function (cdata) {
            if (cdata != null) {
                thread.asyncReturn();
            }
        }, true);
    };
    sun_misc_Unsafe['arrayBaseOffset(Ljava/lang/Class;)I'] = function (thread, javaThis, arg0) {
        return 0;
    };
    sun_misc_Unsafe['arrayIndexScale(Ljava/lang/Class;)I'] = function (thread, javaThis, arg0) {
        var cls = arg0.$cls;
        if (cls instanceof ArrayClassData) {
            switch (cls.getComponentClass().getInternalName()[0]) {
            case 'L':
            case '[':
            case 'F':
            case 'I':
                return 4;
            case 'B':
            case 'Z':
                return 1;
            case 'C':
            case 'S':
                return 2;
            case 'D':
            case 'J':
                return 8;
            default:
                return -1;
            }
        } else {
            return -1;
        }
    };
    sun_misc_Unsafe['addressSize()I'] = function (thread, javaThis) {
        return 4;
    };
    sun_misc_Unsafe['pageSize()I'] = function (thread, javaThis) {
        return 4096;
    };
    sun_misc_Unsafe['defineClass(Ljava/lang/String;[BIILjava/lang/ClassLoader;Ljava/security/ProtectionDomain;)Ljava/lang/Class;'] = function (thread, javaThis, name, bytes, offset, len, loaderObj, pd) {
        var loader = util.getLoader(thread, loaderObj), cdata = loader.defineClass(thread, util.int_classname(name.toString()), util.byteArray2Buffer(bytes.array, offset, len), pd);
        if (cdata !== null) {
            thread.setStatus(ThreadStatus.ASYNC_WAITING);
            cdata.resolve(thread, function (cdata) {
                if (cdata !== null) {
                    thread.asyncReturn(cdata.getClassObject(thread));
                }
            });
        }
    };
    sun_misc_Unsafe['allocateInstance(Ljava/lang/Class;)Ljava/lang/Object;'] = function (thread, javaThis, jco) {
        var cls = jco.$cls;
        if (cls.isInitialized(thread)) {
            return new (cls.getConstructor(thread))(thread);
        } else {
            thread.setStatus(ThreadStatus.ASYNC_WAITING);
            cls.initialize(thread, function () {
                thread.asyncReturn(new (cls.getConstructor(thread))(thread));
            });
        }
    };
    sun_misc_Unsafe['monitorEnter(Ljava/lang/Object;)V'] = function (thread, javaThis, arg0) {
        thread.throwNewException('Ljava/lang/UnsatisfiedLinkError;', 'Native method not implemented.');
    };
    sun_misc_Unsafe['monitorExit(Ljava/lang/Object;)V'] = function (thread, javaThis, arg0) {
        thread.throwNewException('Ljava/lang/UnsatisfiedLinkError;', 'Native method not implemented.');
    };
    sun_misc_Unsafe['tryMonitorEnter(Ljava/lang/Object;)Z'] = function (thread, javaThis, arg0) {
        thread.throwNewException('Ljava/lang/UnsatisfiedLinkError;', 'Native method not implemented.');
        return 0;
    };
    sun_misc_Unsafe['throwException(Ljava/lang/Throwable;)V'] = function (thread, javaThis, exception) {
        thread.throwException(exception);
    };
    sun_misc_Unsafe['unpark(Ljava/lang/Object;)V'] = function (thread, javaThis, theThread) {
        thread.getJVM().getParker().unpark(theThread.$thread);
    };
    sun_misc_Unsafe['park(ZJ)V'] = function (thread, javaThis, absolute, time) {
        var timeout = Infinity, parker = thread.getJVM().getParker();
        if (absolute) {
            timeout = time.toNumber() - new Date().getTime();
            if (timeout < 0) {
                timeout = 0;
            }
        } else {
            if (time.toNumber() > 0) {
                timeout = time.toNumber() / 1000000;
            }
        }
        var timer;
        if (timeout !== Infinity) {
            timer = setTimeout(function () {
                parker.completelyUnpark(thread);
            }, timeout);
        }
        parker.park(thread, function () {
            clearTimeout(timer);
            thread.asyncReturn();
        });
    };
    sun_misc_Unsafe['getLoadAverage([DI)I'] = function (thread, javaThis, arg0, arg1) {
        thread.throwNewException('Ljava/lang/UnsatisfiedLinkError;', 'Native method not implemented.');
        return 0;
    };
    sun_misc_Unsafe['shouldBeInitialized(Ljava/lang/Class;)Z'] = function (thread, javaThis, cls) {
        return !cls.$cls.isInitialized(thread) ? 1 : 0;
    };
    sun_misc_Unsafe['defineAnonymousClass(Ljava/lang/Class;[B[Ljava/lang/Object;)Ljava/lang/Class;'] = function (thread, javaThis, hostClass, data, cpPatches) {
        return new ReferenceClassData(new Buffer(data.array), null, hostClass.$cls.getLoader(), cpPatches).getClassObject(thread);
    };
    sun_misc_Unsafe['loadFence()V'] = function (thread, javaThis) {
    };
    sun_misc_Unsafe['storeFence()V'] = function (thread, javaThis) {
    };
    sun_misc_Unsafe['fullFence()V'] = function (thread, javaThis) {
    };
    sun_misc_Unsafe['getInt(Ljava/lang/Object;J)I'] = getFromVMIndex;
    sun_misc_Unsafe['putInt(Ljava/lang/Object;JI)V'] = setFromVMIndex;
    sun_misc_Unsafe['getObject(Ljava/lang/Object;J)Ljava/lang/Object;'] = getFromVMIndex;
    sun_misc_Unsafe['putObject(Ljava/lang/Object;JLjava/lang/Object;)V'] = setFromVMIndex;
    sun_misc_Unsafe['getBoolean(Ljava/lang/Object;J)Z'] = getFromVMIndex;
    sun_misc_Unsafe['putBoolean(Ljava/lang/Object;JZ)V'] = setFromVMIndex;
    sun_misc_Unsafe['getByte(Ljava/lang/Object;J)B'] = getFromVMIndex;
    sun_misc_Unsafe['putByte(Ljava/lang/Object;JB)V'] = setFromVMIndex;
    sun_misc_Unsafe['getShort(Ljava/lang/Object;J)S'] = getFromVMIndex;
    sun_misc_Unsafe['putShort(Ljava/lang/Object;JS)V'] = setFromVMIndex;
    sun_misc_Unsafe['getChar(Ljava/lang/Object;J)C'] = getFromVMIndex;
    sun_misc_Unsafe['putChar(Ljava/lang/Object;JC)V'] = setFromVMIndex;
    sun_misc_Unsafe['getLong(Ljava/lang/Object;J)J'] = getFromVMIndex;
    sun_misc_Unsafe['putLong(Ljava/lang/Object;JJ)V'] = setFromVMIndex;
    sun_misc_Unsafe['getFloat(Ljava/lang/Object;J)F'] = getFromVMIndex;
    sun_misc_Unsafe['putFloat(Ljava/lang/Object;JF)V'] = setFromVMIndex;
    sun_misc_Unsafe['getDouble(Ljava/lang/Object;J)D'] = getFromVMIndex;
    sun_misc_Unsafe['putDouble(Ljava/lang/Object;JD)V'] = setFromVMIndex;
    sun_misc_Unsafe['compareAndSwapObject(Ljava/lang/Object;JLjava/lang/Object;Ljava/lang/Object;)Z'] = unsafeCompareAndSwap;
    sun_misc_Unsafe['compareAndSwapInt(Ljava/lang/Object;JII)Z'] = unsafeCompareAndSwap;
    sun_misc_Unsafe['compareAndSwapLong(Ljava/lang/Object;JJJ)Z'] = unsafeCompareAndSwap;
    sun_misc_Unsafe['getObjectVolatile(Ljava/lang/Object;J)Ljava/lang/Object;'] = getFromVMIndex;
    sun_misc_Unsafe['putObjectVolatile(Ljava/lang/Object;JLjava/lang/Object;)V'] = setFromVMIndex;
    sun_misc_Unsafe['getIntVolatile(Ljava/lang/Object;J)I'] = getFromVMIndex;
    sun_misc_Unsafe['putIntVolatile(Ljava/lang/Object;JI)V'] = setFromVMIndex;
    sun_misc_Unsafe['getBooleanVolatile(Ljava/lang/Object;J)Z'] = getFromVMIndex;
    sun_misc_Unsafe['putBooleanVolatile(Ljava/lang/Object;JZ)V'] = setFromVMIndex;
    sun_misc_Unsafe['getByteVolatile(Ljava/lang/Object;J)B'] = getFromVMIndex;
    sun_misc_Unsafe['putByteVolatile(Ljava/lang/Object;JB)V'] = setFromVMIndex;
    sun_misc_Unsafe['getShortVolatile(Ljava/lang/Object;J)S'] = getFromVMIndex;
    sun_misc_Unsafe['putShortVolatile(Ljava/lang/Object;JS)V'] = setFromVMIndex;
    sun_misc_Unsafe['getCharVolatile(Ljava/lang/Object;J)C'] = getFromVMIndex;
    sun_misc_Unsafe['putCharVolatile(Ljava/lang/Object;JC)V'] = setFromVMIndex;
    sun_misc_Unsafe['getLongVolatile(Ljava/lang/Object;J)J'] = getFromVMIndex;
    sun_misc_Unsafe['putLongVolatile(Ljava/lang/Object;JJ)V'] = setFromVMIndex;
    sun_misc_Unsafe['getFloatVolatile(Ljava/lang/Object;J)F'] = getFromVMIndex;
    sun_misc_Unsafe['putFloatVolatile(Ljava/lang/Object;JF)V'] = setFromVMIndex;
    sun_misc_Unsafe['getDoubleVolatile(Ljava/lang/Object;J)D'] = getFromVMIndex;
    sun_misc_Unsafe['putDoubleVolatile(Ljava/lang/Object;JD)V'] = setFromVMIndex;
    sun_misc_Unsafe['putOrderedObject(Ljava/lang/Object;JLjava/lang/Object;)V'] = setFromVMIndex;
    sun_misc_Unsafe['putOrderedInt(Ljava/lang/Object;JI)V'] = setFromVMIndex;
    sun_misc_Unsafe['putOrderedLong(Ljava/lang/Object;JJ)V'] = setFromVMIndex;
    return sun_misc_Unsafe;
}();
var sun_misc_Version = function () {
    function sun_misc_Version() {
    }
    sun_misc_Version['getJvmSpecialVersion()Ljava/lang/String;'] = function (thread) {
        thread.throwNewException('Ljava/lang/UnsatisfiedLinkError;', 'Native method not implemented.');
        return null;
    };
    sun_misc_Version['getJdkSpecialVersion()Ljava/lang/String;'] = function (thread) {
        thread.throwNewException('Ljava/lang/UnsatisfiedLinkError;', 'Native method not implemented.');
        return null;
    };
    sun_misc_Version['getJvmVersionInfo()Z'] = function (thread) {
        thread.throwNewException('Ljava/lang/UnsatisfiedLinkError;', 'Native method not implemented.');
        return 0;
    };
    sun_misc_Version['getJdkVersionInfo()V'] = function (thread) {
        thread.throwNewException('Ljava/lang/UnsatisfiedLinkError;', 'Native method not implemented.');
    };
    return sun_misc_Version;
}();
var sun_misc_VM = function () {
    function sun_misc_VM() {
    }
    sun_misc_VM['initialize()V'] = function (thread) {
        return;
    };
    sun_misc_VM['latestUserDefinedLoader()Ljava/lang/ClassLoader;'] = function (thread) {
        var stackTrace = thread.getStackTrace(), i, bsCl = thread.getBsCl(), loader;
        for (i = stackTrace.length - 1; i >= 0; i--) {
            loader = stackTrace[i].method.cls.getLoader();
            if (loader !== bsCl) {
                return loader.getLoaderObject();
            }
        }
        return null;
    };
    return sun_misc_VM;
}();
var sun_misc_VMSupport = function () {
    function sun_misc_VMSupport() {
    }
    sun_misc_VMSupport['initAgentProperties(Ljava/util/Properties;)Ljava/util/Properties;'] = function (thread, arg0) {
        thread.throwNewException('Ljava/lang/UnsatisfiedLinkError;', 'Native method not implemented.');
        return null;
    };
    return sun_misc_VMSupport;
}();
var sun_misc_URLClassPath = function () {
    function sun_misc_URLClassPath() {
    }
    sun_misc_URLClassPath['getLookupCacheURLs(Ljava/lang/ClassLoader;)[Ljava/net/URL;'] = function (thread, loader) {
        return null;
    };
    sun_misc_URLClassPath['getLookupCacheForClassLoader(Ljava/lang/ClassLoader;Ljava/lang/String;)[I'] = function (thread, loader, name) {
        return null;
    };
    sun_misc_URLClassPath['knownToNotExist0(Ljava/lang/ClassLoader;Ljava/lang/String;)Z'] = function (thread, loader, name) {
        return false;
    };
    return sun_misc_URLClassPath;
}();
registerNatives({
    'sun/misc/GC': sun_misc_GC,
    'sun/misc/MessageUtils': sun_misc_MessageUtils,
    'sun/misc/NativeSignalHandler': sun_misc_NativeSignalHandler,
    'sun/misc/Perf': sun_misc_Perf,
    'sun/misc/Signal': sun_misc_Signal,
    'sun/misc/Unsafe': sun_misc_Unsafe,
    'sun/misc/Version': sun_misc_Version,
    'sun/misc/VM': sun_misc_VM,
    'sun/misc/VMSupport': sun_misc_VMSupport,
    'sun/misc/URLClassPath': sun_misc_URLClassPath
});
//# sourceMappingURL=sun_misc.js.map