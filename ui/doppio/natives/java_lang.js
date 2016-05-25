'use strict';
var Doppio = require('../doppiojvm');
var ReferenceClassData = Doppio.VM.ClassFile.ReferenceClassData;
var logging = Doppio.Debug.Logging;
var util = Doppio.VM.Util;
var ArrayClassData = Doppio.VM.ClassFile.ArrayClassData;
var ThreadStatus = Doppio.VM.Enums.ThreadStatus;
var Method = Doppio.VM.ClassFile.Method;
var Long = Doppio.VM.Long;
var assert = Doppio.Debug.Assert;
var PrimitiveClassData = Doppio.VM.ClassFile.PrimitiveClassData;
var MethodHandleReferenceKind = Doppio.VM.Enums.MethodHandleReferenceKind;
var debug = logging.debug;
function arrayGet(thread, arr, idx) {
    if (arr == null) {
        thread.throwNewException('Ljava/lang/NullPointerException;', '');
    } else {
        var array = arr.array;
        if (idx < 0 || idx >= array.length) {
            thread.throwNewException('Ljava/lang/ArrayIndexOutOfBoundsException;', 'Tried to access an illegal index in an array.');
        } else {
            return array[idx];
        }
    }
}
function isNotNull(thread, obj) {
    if (obj == null) {
        thread.throwNewException('Ljava/lang/NullPointerException;', '');
        return false;
    } else {
        return true;
    }
}
function verifyArray(thread, obj) {
    if (!(obj.getClass() instanceof ArrayClassData)) {
        thread.throwNewException('Ljava/lang/IllegalArgumentException;', 'Object is not an array.');
        return false;
    } else {
        return true;
    }
}
var java_lang_Class = function () {
    function java_lang_Class() {
    }
    java_lang_Class['forName0(Ljava/lang/String;ZLjava/lang/ClassLoader;Ljava/lang/Class;)Ljava/lang/Class;'] = function (thread, jvmStr, initialize, jclo, caller) {
        var classname = util.int_classname(jvmStr.toString());
        if (!util.verify_int_classname(classname)) {
            thread.throwNewException('Ljava/lang/ClassNotFoundException;', classname);
        } else {
            var loader = util.getLoader(thread, jclo);
            thread.setStatus(ThreadStatus.ASYNC_WAITING);
            if (initialize) {
                loader.initializeClass(thread, classname, function (cls) {
                    if (cls != null) {
                        thread.asyncReturn(cls.getClassObject(thread));
                    }
                });
            } else {
                loader.resolveClass(thread, classname, function (cls) {
                    if (cls != null) {
                        thread.asyncReturn(cls.getClassObject(thread));
                    }
                });
            }
        }
    };
    java_lang_Class['isInstance(Ljava/lang/Object;)Z'] = function (thread, javaThis, obj) {
        if (obj !== null) {
            return obj.getClass().isCastable(javaThis.$cls);
        } else {
            return false;
        }
    };
    java_lang_Class['isAssignableFrom(Ljava/lang/Class;)Z'] = function (thread, javaThis, cls) {
        return cls.$cls.isCastable(javaThis.$cls);
    };
    java_lang_Class['isInterface()Z'] = function (thread, javaThis) {
        if (!(javaThis.$cls instanceof ReferenceClassData)) {
            return false;
        }
        return javaThis.$cls.accessFlags.isInterface();
    };
    java_lang_Class['isArray()Z'] = function (thread, javaThis) {
        return javaThis.$cls instanceof ArrayClassData;
    };
    java_lang_Class['isPrimitive()Z'] = function (thread, javaThis) {
        return javaThis.$cls instanceof PrimitiveClassData;
    };
    java_lang_Class['getName0()Ljava/lang/String;'] = function (thread, javaThis) {
        return util.initString(thread.getBsCl(), javaThis.$cls.getExternalName());
    };
    java_lang_Class['getSuperclass()Ljava/lang/Class;'] = function (thread, javaThis) {
        if (javaThis.$cls instanceof PrimitiveClassData) {
            return null;
        }
        var cls = javaThis.$cls;
        if (cls.accessFlags.isInterface() || cls.getSuperClass() == null) {
            return null;
        }
        return cls.getSuperClass().getClassObject(thread);
    };
    java_lang_Class['getInterfaces0()[Ljava/lang/Class;'] = function (thread, javaThis) {
        return util.newArrayFromData(thread, thread.getBsCl(), '[Ljava/lang/Class;', javaThis.$cls.getInterfaces().map(function (iface) {
            return iface.getClassObject(thread);
        }));
    };
    java_lang_Class['getComponentType()Ljava/lang/Class;'] = function (thread, javaThis) {
        if (!(javaThis.$cls instanceof ArrayClassData)) {
            return null;
        }
        return javaThis.$cls.getComponentClass().getClassObject(thread);
    };
    java_lang_Class['getModifiers()I'] = function (thread, javaThis) {
        return javaThis.$cls.accessFlags.getRawByte();
    };
    java_lang_Class['getSigners()[Ljava/lang/Object;'] = function (thread, javaThis) {
        thread.throwNewException('Ljava/lang/UnsatisfiedLinkError;', 'Native method not implemented.');
        return null;
    };
    java_lang_Class['setSigners([Ljava/lang/Object;)V'] = function (thread, javaThis, arg0) {
        thread.throwNewException('Ljava/lang/UnsatisfiedLinkError;', 'Native method not implemented.');
    };
    java_lang_Class['getEnclosingMethod0()[Ljava/lang/Object;'] = function (thread, javaThis) {
        var encDesc = null, enc_name = null, bsCl = thread.getBsCl();
        if (javaThis.$cls instanceof ReferenceClassData) {
            var cls = javaThis.$cls, em = cls.getAttribute('EnclosingMethod');
            if (em == null) {
                return null;
            }
            var rv = util.newArray(thread, bsCl, '[Ljava/lang/Object;', 3), encClassRef = em.encClass;
            if (em.encMethod != null) {
                rv.array[1] = util.initString(bsCl, em.encMethod.name);
                rv.array[2] = util.initString(bsCl, em.encMethod.descriptor);
            }
            if (encClassRef.isResolved()) {
                rv.array[0] = encClassRef.cls.getClassObject(thread);
                return rv;
            } else {
                thread.setStatus(ThreadStatus.ASYNC_WAITING);
                encClassRef.resolve(thread, cls.getLoader(), cls, function (status) {
                    if (status) {
                        rv.array[0] = encClassRef.cls.getClassObject(thread);
                        thread.asyncReturn(rv);
                    }
                });
            }
        }
        return null;
    };
    java_lang_Class['getDeclaringClass0()Ljava/lang/Class;'] = function (thread, javaThis) {
        var declaringName, entry, name, i, len;
        if (javaThis.$cls instanceof ReferenceClassData) {
            var cls = javaThis.$cls, icls = cls.getAttribute('InnerClasses');
            if (icls == null) {
                return null;
            }
            var myClass = cls.getInternalName(), innerClassInfo = icls.classes;
            for (i = 0, len = innerClassInfo.length; i < len; i++) {
                entry = innerClassInfo[i];
                if (entry.outerInfoIndex <= 0) {
                    continue;
                }
                name = cls.constantPool.get(entry.innerInfoIndex).name;
                if (name !== myClass) {
                    continue;
                }
                declaringName = cls.constantPool.get(entry.outerInfoIndex);
                if (declaringName.isResolved()) {
                    return declaringName.cls.getClassObject(thread);
                } else {
                    thread.setStatus(ThreadStatus.ASYNC_WAITING);
                    declaringName.resolve(thread, cls.getLoader(), cls, function (status) {
                        if (status) {
                            thread.asyncReturn(declaringName.cls.getClassObject(thread));
                        }
                    });
                }
            }
        }
        return null;
    };
    java_lang_Class['getProtectionDomain0()Ljava/security/ProtectionDomain;'] = function (thread, javaThis) {
        return javaThis.$cls.getProtectionDomain();
    };
    java_lang_Class['getPrimitiveClass(Ljava/lang/String;)Ljava/lang/Class;'] = function (thread, jvmStr) {
        var type_desc = util.typestr2descriptor(jvmStr.toString()), prim_cls = thread.getBsCl().getInitializedClass(thread, type_desc);
        return prim_cls.getClassObject(thread);
    };
    java_lang_Class['getGenericSignature0()Ljava/lang/String;'] = function (thread, javaThis) {
        var cls = javaThis.$cls;
        if (!util.is_primitive_type(cls.getInternalName())) {
            var sigAttr = cls.getAttribute('Signature');
            if (sigAttr != null && sigAttr.sig != null) {
                return util.initString(thread.getBsCl(), sigAttr.sig);
            }
        }
        return null;
    };
    java_lang_Class['getRawAnnotations()[B'] = function (thread, javaThis) {
        var cls = javaThis.$cls, annotationsVisible = cls.getAttribute('RuntimeVisibleAnnotations'), methods, i, m;
        if (annotationsVisible !== null) {
            var bytes = annotationsVisible.rawBytes, data = new Array(bytes.length);
            for (var i = 0; i < bytes.length; i++) {
                data[i] = bytes.readInt8(i);
            }
            return util.newArrayFromData(thread, thread.getBsCl(), '[B', data);
        }
        return null;
    };
    java_lang_Class['getConstantPool()Lsun/reflect/ConstantPool;'] = function (thread, javaThis) {
        var cls = javaThis.$cls, cpObj = util.newObject(thread, thread.getBsCl(), 'Lsun/reflect/ConstantPool;');
        cpObj['sun/reflect/ConstantPool/constantPoolOop'] = cls.constantPool;
        return cpObj;
    };
    java_lang_Class['getDeclaredFields0(Z)[Ljava/lang/reflect/Field;'] = function (thread, javaThis, publicOnly) {
        var fields = javaThis.$cls.getFields();
        if (publicOnly) {
            fields = fields.filter(function (f) {
                return f.accessFlags.isPublic();
            });
        }
        var rv = util.newArray(thread, thread.getBsCl(), '[Ljava/lang/reflect/Field;', fields.length), i = 0;
        thread.setStatus(ThreadStatus.ASYNC_WAITING);
        util.asyncForEach(fields, function (f, nextItem) {
            f.reflector(thread, function (fieldObj) {
                if (fieldObj !== null) {
                    rv.array[i++] = fieldObj;
                    nextItem();
                }
            });
        }, function () {
            thread.asyncReturn(rv);
        });
    };
    java_lang_Class['getDeclaredMethods0(Z)[Ljava/lang/reflect/Method;'] = function (thread, javaThis, publicOnly) {
        var methods = javaThis.$cls.getMethods().filter(function (m) {
                return m.name[0] !== '<' && (m.accessFlags.isPublic() || !publicOnly);
            }), rv = util.newArray(thread, thread.getBsCl(), '[Ljava/lang/reflect/Method;', methods.length), i = 0;
        thread.setStatus(ThreadStatus.ASYNC_WAITING);
        util.asyncForEach(methods, function (m, nextItem) {
            m.reflector(thread, function (methodObj) {
                if (methodObj !== null) {
                    rv.array[i++] = methodObj;
                    nextItem();
                }
            });
        }, function () {
            thread.asyncReturn(rv);
        });
    };
    java_lang_Class['getDeclaredConstructors0(Z)[Ljava/lang/reflect/Constructor;'] = function (thread, javaThis, publicOnly) {
        var methods = javaThis.$cls.getMethods().filter(function (m) {
                return m.name === '<init>' && (!publicOnly || m.accessFlags.isPublic());
            }), rv = util.newArray(thread, thread.getBsCl(), '[Ljava/lang/reflect/Constructor;', methods.length), i = 0;
        thread.setStatus(ThreadStatus.ASYNC_WAITING);
        util.asyncForEach(methods, function (m, nextItem) {
            m.reflector(thread, function (methodObj) {
                if (methodObj !== null) {
                    rv.array[i++] = methodObj;
                    nextItem();
                }
            });
        }, function () {
            thread.asyncReturn(rv);
        });
    };
    java_lang_Class['getDeclaredClasses0()[Ljava/lang/Class;'] = function (thread, javaThis) {
        var ret = util.newArray(thread, thread.getBsCl(), '[Ljava/lang/Class;', 0), cls = javaThis.$cls;
        if (cls instanceof ReferenceClassData) {
            var myClass = cls.getInternalName(), iclses = cls.getAttributes('InnerClasses'), flatNames = [];
            if (iclses.length === 0) {
                return ret;
            }
            for (var i = 0; i < iclses.length; i++) {
                flatNames = flatNames.concat(iclses[i].classes.filter(function (c) {
                    return c.outerInfoIndex > 0 && cls.constantPool.get(c.outerInfoIndex).name === myClass;
                }).map(function (c) {
                    return cls.constantPool.get(c.innerInfoIndex);
                }));
            }
            thread.setStatus(ThreadStatus.ASYNC_WAITING);
            util.asyncForEach(flatNames, function (clsRef, nextItem) {
                if (clsRef.isResolved()) {
                    ret.array.push(clsRef.cls.getClassObject(thread));
                    nextItem();
                } else {
                    clsRef.resolve(thread, cls.getLoader(), javaThis.getClass(), function (status) {
                        if (status) {
                            ret.array.push(clsRef.cls.getClassObject(thread));
                            nextItem();
                        }
                    });
                }
            }, function () {
                return thread.asyncReturn(ret);
            });
        } else {
            return ret;
        }
    };
    java_lang_Class['desiredAssertionStatus0(Ljava/lang/Class;)Z'] = function (thread, arg0) {
        if (arg0.$cls.getLoader().getLoaderObject() === null) {
            return thread.getJVM().areSystemAssertionsEnabled();
        }
        return false;
    };
    return java_lang_Class;
}();
var java_lang_ClassLoader$NativeLibrary = function () {
    function java_lang_ClassLoader$NativeLibrary() {
    }
    java_lang_ClassLoader$NativeLibrary['load(Ljava/lang/String;Z)V'] = function (thread, javaThis, name, isBuiltIn) {
        thread.throwNewException('Ljava/lang/UnsatisfiedLinkError;', 'Native method not implemented.');
    };
    java_lang_ClassLoader$NativeLibrary['find(Ljava/lang/String;)J'] = function (thread, javaThis, arg0) {
        thread.throwNewException('Ljava/lang/UnsatisfiedLinkError;', 'Native method not implemented.');
        return null;
    };
    java_lang_ClassLoader$NativeLibrary['unload(Ljava/lang/String;Z)V'] = function (thread, javaThis, name) {
        thread.throwNewException('Ljava/lang/UnsatisfiedLinkError;', 'Native method not implemented.');
    };
    return java_lang_ClassLoader$NativeLibrary;
}();
var java_lang_ClassLoader = function () {
    function java_lang_ClassLoader() {
    }
    java_lang_ClassLoader['defineClass0(Ljava/lang/String;[BIILjava/security/ProtectionDomain;)Ljava/lang/Class;'] = function (thread, javaThis, arg0, arg1, arg2, arg3, arg4) {
        thread.throwNewException('Ljava/lang/UnsatisfiedLinkError;', 'Native method not implemented.');
        return null;
    };
    java_lang_ClassLoader['defineClass1(Ljava/lang/String;[BIILjava/security/ProtectionDomain;Ljava/lang/String;)Ljava/lang/Class;'] = function (thread, javaThis, name, bytes, offset, len, pd, source) {
        var loader = util.getLoader(thread, javaThis), type = util.int_classname(name.toString()), cls = loader.defineClass(thread, type, util.byteArray2Buffer(bytes.array, offset, len), pd);
        if (cls == null) {
            return null;
        }
        thread.setStatus(ThreadStatus.ASYNC_WAITING);
        cls.resolve(thread, function (status) {
            if (status !== null) {
                thread.asyncReturn(cls.getClassObject(thread));
            }
        }, true);
    };
    java_lang_ClassLoader['defineClass2(Ljava/lang/String;Ljava/nio/ByteBuffer;IILjava/security/ProtectionDomain;Ljava/lang/String;)Ljava/lang/Class;'] = function (thread, javaThis, name, b, off, len, pd, source) {
        thread.throwNewException('Ljava/lang/UnsatisfiedLinkError;', 'Native method not implemented.');
        return null;
    };
    java_lang_ClassLoader['resolveClass0(Ljava/lang/Class;)V'] = function (thread, javaThis, cls) {
        var loader = util.getLoader(thread, javaThis);
        if (cls.$cls.isResolved()) {
            return;
        }
        thread.setStatus(ThreadStatus.ASYNC_WAITING);
        cls.$cls.resolve(thread, function (cdata) {
            if (cdata !== null) {
                thread.asyncReturn();
            }
        }, true);
    };
    java_lang_ClassLoader['findBootstrapClass(Ljava/lang/String;)Ljava/lang/Class;'] = function (thread, javaThis, name) {
        var type = util.int_classname(name.toString());
        thread.setStatus(ThreadStatus.ASYNC_WAITING);
        thread.getBsCl().resolveClass(thread, type, function (cls) {
            if (cls != null) {
                thread.asyncReturn(cls.getClassObject(thread));
            }
        }, true);
    };
    java_lang_ClassLoader['findLoadedClass0(Ljava/lang/String;)Ljava/lang/Class;'] = function (thread, javaThis, name) {
        var loader = util.getLoader(thread, javaThis), type = util.int_classname(name.toString()), cls = loader.getResolvedClass(type);
        if (cls != null) {
            return cls.getClassObject(thread);
        } else {
            return null;
        }
    };
    java_lang_ClassLoader['retrieveDirectives()Ljava/lang/AssertionStatusDirectives;'] = function (thread) {
        var jvm = thread.getJVM(), bsCl = thread.getBsCl();
        thread.import('Ljava/lang/AssertionStatusDirectives;', function (asd) {
            var directives = new asd();
            var enabledAssertions = jvm.getEnabledAssertions();
            var classes = [], classEnabled = [], packages = [], packageEnabled = [], deflt = false, processAssertions = function (enabled) {
                    return function (name) {
                        var dotIndex = name.indexOf('...');
                        if (dotIndex === -1) {
                            classes.push(name);
                            classEnabled.push(enabled);
                        } else {
                            packages.push(name.slice(0, dotIndex));
                            packageEnabled.push(enabled);
                        }
                    };
                };
            jvm.getDisabledAssertions().forEach(processAssertions(0));
            if (typeof enabledAssertions === 'boolean') {
                deflt = enabledAssertions;
            } else if (Array.isArray(enabledAssertions)) {
                enabledAssertions.forEach(processAssertions(1));
            } else {
                return thread.throwNewException('Ljava/lang/InternalError;', 'Expected enableAssertions option to be a boolean or an array of strings.');
            }
            directives['java/lang/AssertionStatusDirectives/classes'] = util.newArrayFromData(thread, bsCl, '[Ljava/lang/String;', classes.map(function (cls) {
                return util.initString(bsCl, cls);
            }));
            directives['java/lang/AssertionStatusDirectives/classEnabled'] = util.newArrayFromData(thread, bsCl, '[Z', classEnabled);
            directives['java/lang/AssertionStatusDirectives/packages'] = util.newArrayFromData(thread, bsCl, '[Ljava/lang/String;', packages.map(function (pkg) {
                return util.initString(bsCl, pkg);
            }));
            directives['java/lang/AssertionStatusDirectives/packageEnabled'] = util.newArrayFromData(thread, bsCl, '[Z', packageEnabled);
            directives['java/lang/AssertionStatusDirectives/deflt'] = enabledAssertions ? 1 : 0;
            thread.asyncReturn(directives);
        });
    };
    return java_lang_ClassLoader;
}();
var java_lang_Compiler = function () {
    function java_lang_Compiler() {
    }
    java_lang_Compiler['initialize()V'] = function (thread) {
    };
    java_lang_Compiler['registerNatives()V'] = function (thread) {
    };
    java_lang_Compiler['compileClass(Ljava/lang/Class;)Z'] = function (thread, arg0) {
        return 0;
    };
    java_lang_Compiler['compileClasses(Ljava/lang/String;)Z'] = function (thread, arg0) {
        return 0;
    };
    java_lang_Compiler['command(Ljava/lang/Object;)Ljava/lang/Object;'] = function (thread, arg0) {
        return null;
    };
    java_lang_Compiler['enable()V'] = function (thread) {
    };
    java_lang_Compiler['disable()V'] = function (thread) {
    };
    return java_lang_Compiler;
}();
var conversionBuffer = new Buffer(8);
var java_lang_Double = function () {
    function java_lang_Double() {
    }
    java_lang_Double['doubleToRawLongBits(D)J'] = function (thread, num) {
        conversionBuffer.writeDoubleLE(num, 0);
        return Long.fromBits(conversionBuffer.readUInt32LE(0), conversionBuffer.readUInt32LE(4));
    };
    java_lang_Double['longBitsToDouble(J)D'] = function (thread, num) {
        conversionBuffer.writeInt32LE(num.getLowBits(), 0);
        conversionBuffer.writeInt32LE(num.getHighBits(), 4);
        return conversionBuffer.readDoubleLE(0);
    };
    return java_lang_Double;
}();
var java_lang_Float = function () {
    function java_lang_Float() {
    }
    java_lang_Float['floatToRawIntBits(F)I'] = function (thread, num) {
        conversionBuffer.writeFloatLE(num, 0);
        return conversionBuffer.readInt32LE(0);
    };
    java_lang_Float['intBitsToFloat(I)F'] = function (thread, num) {
        conversionBuffer.writeInt32LE(num, 0);
        return conversionBuffer.readFloatLE(0);
    };
    return java_lang_Float;
}();
var java_lang_Object = function () {
    function java_lang_Object() {
    }
    java_lang_Object['getClass()Ljava/lang/Class;'] = function (thread, javaThis) {
        return javaThis.getClass().getClassObject(thread);
    };
    java_lang_Object['hashCode()I'] = function (thread, javaThis) {
        return javaThis.ref;
    };
    java_lang_Object['clone()Ljava/lang/Object;'] = function (thread, javaThis) {
        var cls = javaThis.getClass();
        if (cls.getInternalName()[0] === '[') {
            return javaThis.slice(0);
        } else {
            var clonedObj = util.newObjectFromClass(thread, javaThis.getClass());
            Object.keys(javaThis).forEach(function (fieldName) {
                clonedObj[fieldName] = javaThis[fieldName];
            });
            return clonedObj;
        }
    };
    java_lang_Object['notify()V'] = function (thread, javaThis) {
        ;
        javaThis.getMonitor().notify(thread);
    };
    java_lang_Object['notifyAll()V'] = function (thread, javaThis) {
        ;
        javaThis.getMonitor().notifyAll(thread);
    };
    java_lang_Object['wait(J)V'] = function (thread, javaThis, timeout) {
        ;
        javaThis.getMonitor().wait(thread, function (fromTimer) {
            thread.asyncReturn();
        }, timeout.toNumber());
    };
    return java_lang_Object;
}();
var java_lang_Package = function () {
    function java_lang_Package() {
    }
    java_lang_Package['getSystemPackage0(Ljava/lang/String;)Ljava/lang/String;'] = function (thread, pkgNameObj) {
        var pkgName = pkgNameObj.toString();
        pkgName = pkgName.slice(0, pkgName.length - 1);
        var pkgs = thread.getBsCl().getPackages();
        for (var i = 0; i < pkgs.length; i++) {
            if (pkgs[i][0] === pkgName) {
                return util.initString(thread.getBsCl(), pkgs[i][1][0]);
            }
        }
        return null;
    };
    java_lang_Package['getSystemPackages0()[Ljava/lang/String;'] = function (thread) {
        var pkgNames = thread.getBsCl().getPackages();
        return util.newArrayFromData(thread, thread.getBsCl(), '[Ljava/lang/String;', pkgNames.map(function (pkgName) {
            return util.initString(thread.getBsCl(), pkgName[0] + '/');
        }));
    };
    return java_lang_Package;
}();
var java_lang_ProcessEnvironment = function () {
    function java_lang_ProcessEnvironment() {
    }
    java_lang_ProcessEnvironment['environ()[[B'] = function (thread) {
        var envArr = util.newArray(thread, thread.getBsCl(), '[[B', 0), env = process.env, key, v, bArr;
        for (key in env) {
            v = env[key];
            bArr = util.newArray(thread, thread.getBsCl(), '[B', 0);
            bArr.array = util.bytestr2Array(key);
            envArr.array.push(bArr);
            bArr = util.newArray(thread, thread.getBsCl(), '[B', 0);
            bArr.array = util.bytestr2Array(v);
            envArr.array.push(bArr);
        }
        return envArr;
    };
    return java_lang_ProcessEnvironment;
}();
var java_lang_reflect_Array = function () {
    function java_lang_reflect_Array() {
    }
    java_lang_reflect_Array['getLength(Ljava/lang/Object;)I'] = function (thread, arr) {
        if (verifyArray(thread, arr)) {
            if (isNotNull(thread, arr)) {
                return arr.array.length;
            }
        }
    };
    java_lang_reflect_Array['get(Ljava/lang/Object;I)Ljava/lang/Object;'] = function (thread, arr, idx) {
        var val = arrayGet(thread, arr, idx);
        if (val != null) {
            var component = arr.getClass().getComponentClass();
            if (util.is_primitive_type(component.getInternalName())) {
                return component.createWrapperObject(thread, val);
            }
        }
        return val;
    };
    java_lang_reflect_Array['set(Ljava/lang/Object;ILjava/lang/Object;)V'] = function (thread, arr, idx, val) {
        if (verifyArray(thread, arr) && isNotNull(thread, arr)) {
            if (idx < 0 || idx >= arr.array.length) {
                thread.throwNewException('Ljava/lang/ArrayIndexOutOfBoundsException;', 'Tried to write to an illegal index in an array.');
            } else {
                var ccls = arr.getClass().getComponentClass();
                if (ccls instanceof PrimitiveClassData) {
                    if (val.getClass().isSubclass(thread.getBsCl().getInitializedClass(thread, ccls.boxClassName()))) {
                        var ccname = ccls.getInternalName();
                        val[util.internal2external[ccname] + 'Value()' + ccname](thread, null, function (e, rv) {
                            if (e) {
                                thread.throwException(e);
                            } else {
                                arr.array[idx] = rv;
                                thread.asyncReturn();
                            }
                        });
                    } else {
                        thread.throwNewException('Ljava/lang/IllegalArgumentException;', 'argument type mismatch');
                    }
                } else if (val.getClass().isSubclass(ccls)) {
                    arr.array[idx] = val;
                } else {
                    thread.throwNewException('Ljava/lang/IllegalArgumentException;', 'argument type mismatch');
                }
            }
        }
    };
    java_lang_reflect_Array['setBoolean(Ljava/lang/Object;IZ)V'] = function (thread, arg0, arg1, arg2) {
        thread.throwNewException('Ljava/lang/UnsatisfiedLinkError;', 'Native method not implemented.');
    };
    java_lang_reflect_Array['setByte(Ljava/lang/Object;IB)V'] = function (thread, arg0, arg1, arg2) {
        thread.throwNewException('Ljava/lang/UnsatisfiedLinkError;', 'Native method not implemented.');
    };
    java_lang_reflect_Array['setChar(Ljava/lang/Object;IC)V'] = function (thread, arg0, arg1, arg2) {
        thread.throwNewException('Ljava/lang/UnsatisfiedLinkError;', 'Native method not implemented.');
    };
    java_lang_reflect_Array['setShort(Ljava/lang/Object;IS)V'] = function (thread, arg0, arg1, arg2) {
        thread.throwNewException('Ljava/lang/UnsatisfiedLinkError;', 'Native method not implemented.');
    };
    java_lang_reflect_Array['setInt(Ljava/lang/Object;II)V'] = function (thread, arg0, arg1, arg2) {
        thread.throwNewException('Ljava/lang/UnsatisfiedLinkError;', 'Native method not implemented.');
    };
    java_lang_reflect_Array['setLong(Ljava/lang/Object;IJ)V'] = function (thread, arg0, arg1, arg2) {
        thread.throwNewException('Ljava/lang/UnsatisfiedLinkError;', 'Native method not implemented.');
    };
    java_lang_reflect_Array['setFloat(Ljava/lang/Object;IF)V'] = function (thread, arg0, arg1, arg2) {
        thread.throwNewException('Ljava/lang/UnsatisfiedLinkError;', 'Native method not implemented.');
    };
    java_lang_reflect_Array['setDouble(Ljava/lang/Object;ID)V'] = function (thread, arg0, arg1, arg2) {
        thread.throwNewException('Ljava/lang/UnsatisfiedLinkError;', 'Native method not implemented.');
    };
    java_lang_reflect_Array['newArray(Ljava/lang/Class;I)Ljava/lang/Object;'] = function (thread, cls, len) {
        return util.newArray(thread, cls.$cls.getLoader(), '[' + cls.$cls.getInternalName(), len);
    };
    java_lang_reflect_Array['multiNewArray(Ljava/lang/Class;[I)Ljava/lang/Object;'] = function (thread, jco, lens) {
        var typeStr = new Array(lens.array.length + 1).join('[') + jco.$cls.getInternalName();
        if (jco.$cls.isInitialized(thread)) {
            return util.multiNewArray(thread, jco.$cls.getLoader(), typeStr, lens.array);
        } else {
            thread.setStatus(ThreadStatus.ASYNC_WAITING);
            jco.$cls.initialize(thread, function (cls) {
                thread.asyncReturn(util.multiNewArray(thread, jco.$cls.getLoader(), typeStr, lens.array));
            });
        }
    };
    java_lang_reflect_Array['getBoolean(Ljava/lang/Object;I)Z'] = arrayGet;
    java_lang_reflect_Array['getByte(Ljava/lang/Object;I)B'] = arrayGet;
    java_lang_reflect_Array['getChar(Ljava/lang/Object;I)C'] = arrayGet;
    java_lang_reflect_Array['getShort(Ljava/lang/Object;I)S'] = arrayGet;
    java_lang_reflect_Array['getInt(Ljava/lang/Object;I)I'] = arrayGet;
    java_lang_reflect_Array['getLong(Ljava/lang/Object;I)J'] = arrayGet;
    java_lang_reflect_Array['getFloat(Ljava/lang/Object;I)F'] = arrayGet;
    java_lang_reflect_Array['getDouble(Ljava/lang/Object;I)D'] = arrayGet;
    return java_lang_reflect_Array;
}();
var java_lang_reflect_Proxy = function () {
    function java_lang_reflect_Proxy() {
    }
    java_lang_reflect_Proxy['defineClass0(Ljava/lang/ClassLoader;Ljava/lang/String;[BII)Ljava/lang/Class;'] = function (thread, cl, name, bytes, offset, len) {
        var loader = util.getLoader(thread, cl), cls = loader.defineClass(thread, util.int_classname(name.toString()), util.byteArray2Buffer(bytes.array, offset, len), null);
        if (cls != null) {
            return cls.getClassObject(thread);
        }
    };
    return java_lang_reflect_Proxy;
}();
var java_lang_Runtime = function () {
    function java_lang_Runtime() {
    }
    java_lang_Runtime['availableProcessors()I'] = function (thread, javaThis) {
        return 1;
    };
    java_lang_Runtime['freeMemory()J'] = function (thread, javaThis) {
        return Long.MAX_VALUE;
    };
    java_lang_Runtime['totalMemory()J'] = function (thread, javaThis) {
        return Long.MAX_VALUE;
    };
    java_lang_Runtime['maxMemory()J'] = function (thread, javaThis) {
        return Long.MAX_VALUE;
    };
    java_lang_Runtime['gc()V'] = function (thread, javaThis) {
        thread.setStatus(ThreadStatus.ASYNC_WAITING);
        setImmediate(function () {
            thread.asyncReturn();
        });
    };
    java_lang_Runtime['runFinalization0()V'] = function (thread) {
        thread.throwNewException('Ljava/lang/UnsatisfiedLinkError;', 'Native method not implemented.');
    };
    java_lang_Runtime['traceInstructions(Z)V'] = function (thread, javaThis, arg0) {
        thread.throwNewException('Ljava/lang/UnsatisfiedLinkError;', 'Native method not implemented.');
    };
    java_lang_Runtime['traceMethodCalls(Z)V'] = function (thread, javaThis, arg0) {
        thread.throwNewException('Ljava/lang/UnsatisfiedLinkError;', 'Native method not implemented.');
    };
    return java_lang_Runtime;
}();
var java_lang_SecurityManager = function () {
    function java_lang_SecurityManager() {
    }
    java_lang_SecurityManager['getClassContext()[Ljava/lang/Class;'] = function (thread, javaThis) {
        return util.newArrayFromData(thread, thread.getBsCl(), '[Ljava/lang/Class;', thread.getStackTrace().map(function (item) {
            return item.method.cls.getClassObject(thread);
        }));
        ;
    };
    java_lang_SecurityManager['currentClassLoader0()Ljava/lang/ClassLoader;'] = function (thread, javaThis) {
        thread.throwNewException('Ljava/lang/UnsatisfiedLinkError;', 'Native method not implemented.');
        return null;
    };
    java_lang_SecurityManager['classDepth(Ljava/lang/String;)I'] = function (thread, javaThis, arg0) {
        thread.throwNewException('Ljava/lang/UnsatisfiedLinkError;', 'Native method not implemented.');
        return 0;
    };
    java_lang_SecurityManager['classLoaderDepth0()I'] = function (thread, javaThis) {
        thread.throwNewException('Ljava/lang/UnsatisfiedLinkError;', 'Native method not implemented.');
        return 0;
    };
    java_lang_SecurityManager['currentLoadedClass0()Ljava/lang/Class;'] = function (thread, javaThis) {
        thread.throwNewException('Ljava/lang/UnsatisfiedLinkError;', 'Native method not implemented.');
        return null;
    };
    return java_lang_SecurityManager;
}();
var java_lang_Shutdown = function () {
    function java_lang_Shutdown() {
    }
    java_lang_Shutdown['halt0(I)V'] = function (thread, status) {
        thread.getJVM().halt(status);
    };
    java_lang_Shutdown['runAllFinalizers()V'] = function (thread) {
        thread.throwNewException('Ljava/lang/UnsatisfiedLinkError;', 'Native method not implemented.');
    };
    return java_lang_Shutdown;
}();
var java_lang_StrictMath = function () {
    function java_lang_StrictMath() {
    }
    java_lang_StrictMath['sin(D)D'] = function (thread, d_val) {
        return Math.sin(d_val);
    };
    java_lang_StrictMath['cos(D)D'] = function (thread, d_val) {
        return Math.cos(d_val);
    };
    java_lang_StrictMath['tan(D)D'] = function (thread, d_val) {
        return Math.tan(d_val);
    };
    java_lang_StrictMath['asin(D)D'] = function (thread, d_val) {
        return Math.asin(d_val);
    };
    java_lang_StrictMath['acos(D)D'] = function (thread, d_val) {
        return Math.acos(d_val);
    };
    java_lang_StrictMath['atan(D)D'] = function (thread, d_val) {
        return Math.atan(d_val);
    };
    java_lang_StrictMath['exp(D)D'] = function (thread, d_val) {
        return Math.exp(d_val);
    };
    java_lang_StrictMath['log(D)D'] = function (thread, d_val) {
        return Math.log(d_val);
    };
    java_lang_StrictMath['log10(D)D'] = function (thread, d_val) {
        return Math.log(d_val) / Math.LN10;
    };
    java_lang_StrictMath['sqrt(D)D'] = function (thread, d_val) {
        return Math.sqrt(d_val);
    };
    java_lang_StrictMath['cbrt(D)D'] = function (thread, d_val) {
        var is_neg = d_val < 0;
        if (is_neg) {
            return -Math.pow(-d_val, 1 / 3);
        } else {
            return Math.pow(d_val, 1 / 3);
        }
    };
    java_lang_StrictMath['IEEEremainder(DD)D'] = function (thread, x, y) {
        if (x == Number.NEGATIVE_INFINITY || !(x < Number.POSITIVE_INFINITY) || y == 0 || y != y)
            return Number.NaN;
        var TWO_1023 = 8.98846567431158e+307;
        var negative = x < 0;
        x = Math.abs(x);
        y = Math.abs(y);
        if (x == y || x == 0)
            return 0 * x;
        if (y < TWO_1023)
            x %= y + y;
        if (y < 4 / TWO_1023) {
            if (x + x > y) {
                x -= y;
                if (x + x >= y)
                    x -= y;
            }
        } else {
            y *= 0.5;
            if (x > y) {
                x -= y;
                if (x >= y)
                    x -= y;
            }
        }
        return negative ? -x : x;
    };
    java_lang_StrictMath['atan2(DD)D'] = function (thread, y, x) {
        return Math.atan2(y, x);
    };
    java_lang_StrictMath['pow(DD)D'] = function (thread, base, exp) {
        return Math.pow(base, exp);
    };
    java_lang_StrictMath['sinh(D)D'] = function (thread, d_val) {
        return Math.sinh(d_val);
    };
    java_lang_StrictMath['cosh(D)D'] = function (thread, d_val) {
        var exp = Math.exp(d_val);
        return (exp + 1 / exp) / 2;
    };
    java_lang_StrictMath['tanh(D)D'] = function (thread, d_val) {
        thread.throwNewException('Ljava/lang/UnsatisfiedLinkError;', 'Native method not implemented.');
        return 0;
    };
    java_lang_StrictMath['hypot(DD)D'] = function (thread, arg0, arg1) {
        return Math.sqrt(Math.pow(arg0, 2) + Math.pow(arg1, 2));
    };
    java_lang_StrictMath['expm1(D)D'] = function (thread, d_val) {
        return Math.expm1(d_val);
    };
    java_lang_StrictMath['log1p(D)D'] = function (thread, d_val) {
        thread.throwNewException('Ljava/lang/UnsatisfiedLinkError;', 'Native method not implemented.');
        return 0;
    };
    return java_lang_StrictMath;
}();
var java_lang_String = function () {
    function java_lang_String() {
    }
    java_lang_String['intern()Ljava/lang/String;'] = function (thread, javaThis) {
        return thread.getJVM().internString(javaThis.toString(), javaThis);
    };
    return java_lang_String;
}();
var java_lang_System = function () {
    function java_lang_System() {
    }
    java_lang_System['setIn0(Ljava/io/InputStream;)V'] = function (thread, stream) {
        var sys = util.getStaticFields(thread, thread.getBsCl(), 'Ljava/lang/System;');
        sys['java/lang/System/in'] = stream;
    };
    java_lang_System['setOut0(Ljava/io/PrintStream;)V'] = function (thread, stream) {
        var sys = util.getStaticFields(thread, thread.getBsCl(), 'Ljava/lang/System;');
        sys['java/lang/System/out'] = stream;
    };
    java_lang_System['setErr0(Ljava/io/PrintStream;)V'] = function (thread, stream) {
        var sys = util.getStaticFields(thread, thread.getBsCl(), 'Ljava/lang/System;');
        sys['java/lang/System/err'] = stream;
    };
    java_lang_System['currentTimeMillis()J'] = function (thread) {
        return Long.fromNumber(new Date().getTime());
    };
    java_lang_System['nanoTime()J'] = function (thread) {
        return Long.fromNumber(new Date().getTime()).multiply(Long.fromNumber(1000000));
    };
    java_lang_System['arraycopy(Ljava/lang/Object;ILjava/lang/Object;II)V'] = function (thread, src, srcPos, dest, destPos, length) {
        if (src == null || dest == null) {
            thread.throwNewException('Ljava/lang/NullPointerException;', 'Cannot copy to/from a null array.');
        } else if (!(src.getClass() instanceof ArrayClassData) || !(dest.getClass() instanceof ArrayClassData)) {
            thread.throwNewException('Ljava/lang/ArrayStoreException;', 'src and dest arguments must be of array type.');
        } else if (srcPos < 0 || srcPos + length > src.array.length || destPos < 0 || destPos + length > dest.array.length || length < 0) {
            thread.throwNewException('Ljava/lang/ArrayIndexOutOfBoundsException;', 'Tried to write to an illegal index in an array.');
        } else {
            var srcClass = src.getClass(), destClass = dest.getClass();
            if (src === dest) {
                src = dest.slice(srcPos, srcPos + length);
                srcPos = 0;
            }
            if (srcClass.isCastable(destClass)) {
                util.arraycopyNoCheck(src, srcPos, dest, destPos, length);
            } else {
                var srcCompCls = src.getClass().getComponentClass(), destCompCls = dest.getClass().getComponentClass();
                if (srcCompCls instanceof PrimitiveClassData || destCompCls instanceof PrimitiveClassData) {
                    thread.throwNewException('Ljava/lang/ArrayStoreException;', 'If calling arraycopy with a primitive array, both src and dest must be of the same primitive type.');
                } else {
                    util.arraycopyCheck(thread, src, srcPos, dest, destPos, length);
                }
            }
        }
    };
    java_lang_System['identityHashCode(Ljava/lang/Object;)I'] = function (thread, x) {
        if (x != null && x.ref != null) {
            return x.ref;
        }
        return 0;
    };
    java_lang_System['initProperties(Ljava/util/Properties;)Ljava/util/Properties;'] = function (thread, props) {
        var jvm = thread.getJVM(), properties = jvm.getSystemPropertyNames();
        thread.setStatus(ThreadStatus.ASYNC_WAITING);
        util.asyncForEach(properties, function (propertyName, nextItem) {
            var propertyVal = jvm.getSystemProperty(propertyName);
            props['setProperty(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/Object;'](thread, [
                jvm.internString(propertyName),
                jvm.internString(propertyVal)
            ], nextItem);
        }, function (err) {
            if (err) {
                thread.throwException(err);
            } else {
                thread.asyncReturn(props);
            }
        });
    };
    java_lang_System['mapLibraryName(Ljava/lang/String;)Ljava/lang/String;'] = function (thread, arg0) {
        thread.throwNewException('Ljava/lang/UnsatisfiedLinkError;', 'Native method not implemented.');
        return null;
    };
    return java_lang_System;
}();
var java_lang_Thread = function () {
    function java_lang_Thread() {
    }
    java_lang_Thread['currentThread()Ljava/lang/Thread;'] = function (thread) {
        return thread.getJVMObject();
    };
    java_lang_Thread['yield()V'] = function (thread) {
        thread.setStatus(ThreadStatus.ASYNC_WAITING);
        setImmediate(function () {
            thread.setStatus(ThreadStatus.RUNNABLE);
            thread.asyncReturn();
        });
    };
    java_lang_Thread['sleep(J)V'] = function (thread, millis) {
        var beforeMethod = thread.currentMethod();
        thread.setStatus(ThreadStatus.ASYNC_WAITING);
        setTimeout(function () {
            if (beforeMethod === thread.currentMethod()) {
                thread.setStatus(ThreadStatus.RUNNABLE);
                thread.asyncReturn();
            }
        }, millis.toNumber());
    };
    java_lang_Thread['start0()V'] = function (thread, javaThis) {
        javaThis['run()V'](javaThis.$thread, null);
    };
    java_lang_Thread['setNativeName(Ljava/lang/String;)V'] = function (thread, javaThis, name) {
    };
    java_lang_Thread['isInterrupted(Z)Z'] = function (thread, javaThis, clearFlag) {
        var isInterrupted = javaThis.$thread.isInterrupted();
        if (clearFlag) {
            javaThis.$thread.setInterrupted(false);
        }
        return isInterrupted;
    };
    java_lang_Thread['isAlive()Z'] = function (thread, javaThis) {
        var state = javaThis.$thread.getStatus();
        return state !== ThreadStatus.TERMINATED && state !== ThreadStatus.NEW;
    };
    java_lang_Thread['countStackFrames()I'] = function (thread, javaThis) {
        return javaThis.$thread.getStackTrace().length;
    };
    java_lang_Thread['holdsLock(Ljava/lang/Object;)Z'] = function (thread, obj) {
        var mon = obj.getMonitor();
        return mon.getOwner() === thread;
    };
    java_lang_Thread['dumpThreads([Ljava/lang/Thread;)[[Ljava/lang/StackTraceElement;'] = function (thread, arg0) {
        thread.throwNewException('Ljava/lang/UnsatisfiedLinkError;', 'Native method not implemented.');
        return null;
    };
    java_lang_Thread['getThreads()[Ljava/lang/Thread;'] = function (thread) {
        return util.newArrayFromData(thread, thread.getBsCl(), '[Ljava/lang/Thread;', thread.getThreadPool().getThreads().map(function (thread) {
            return thread.getJVMObject();
        }));
    };
    java_lang_Thread['setPriority0(I)V'] = function (thread, javaThis, arg0) {
        thread.signalPriorityChange();
    };
    java_lang_Thread['stop0(Ljava/lang/Object;)V'] = function (thread, javaThis, arg0) {
        thread.throwNewException('Ljava/lang/UnsatisfiedLinkError;', 'Native method not implemented.');
    };
    java_lang_Thread['suspend0()V'] = function (thread, javaThis) {
        thread.throwNewException('Ljava/lang/UnsatisfiedLinkError;', 'Native method not implemented.');
    };
    java_lang_Thread['resume0()V'] = function (thread, javaThis) {
        thread.throwNewException('Ljava/lang/UnsatisfiedLinkError;', 'Native method not implemented.');
    };
    java_lang_Thread['interrupt0()V'] = function (thread, javaThis) {
        function throwInterruptedException() {
            javaThis.$thread.throwNewException('Ljava/lang/InterruptedException;', 'interrupt0 called');
        }
        var nativeThreadObj = javaThis.$thread;
        javaThis['checkAccess()V'](thread, null, function (e) {
            if (e) {
                thread.throwException(e);
            } else {
                var status = nativeThreadObj.getStatus();
                switch (status) {
                case ThreadStatus.NEW:
                case ThreadStatus.TERMINATED:
                    return thread.asyncReturn();
                case ThreadStatus.BLOCKED:
                case ThreadStatus.WAITING:
                case ThreadStatus.TIMED_WAITING:
                    nativeThreadObj.setInterrupted(false);
                    var monitor = nativeThreadObj.getMonitorBlock();
                    if (status === ThreadStatus.BLOCKED) {
                        monitor.unblock(nativeThreadObj, true);
                        throwInterruptedException();
                    } else {
                        monitor.unwait(nativeThreadObj, false, true, throwInterruptedException);
                    }
                    return thread.asyncReturn();
                case ThreadStatus.PARKED:
                    thread.getJVM().getParker().completelyUnpark(nativeThreadObj);
                default:
                    var threadCls = thread.getBsCl().getInitializedClass(thread, 'Ljava/lang/Thread;'), interruptMethods = [
                            threadCls.methodLookup('join()V'),
                            threadCls.methodLookup('join(J)V'),
                            threadCls.methodLookup('join(JI)V'),
                            threadCls.methodLookup('sleep(J)V'),
                            threadCls.methodLookup('sleep(JI)V')
                        ], stackTrace = nativeThreadObj.getStackTrace(), currentMethod = stackTrace[stackTrace.length - 1].method;
                    if (interruptMethods.indexOf(currentMethod) !== -1) {
                        nativeThreadObj.setInterrupted(false);
                        nativeThreadObj.throwNewException('Ljava/lang/InterruptedException;', 'interrupt0 called');
                    } else {
                        nativeThreadObj.setInterrupted(true);
                    }
                    return thread.asyncReturn();
                }
            }
        });
    };
    return java_lang_Thread;
}();
var java_lang_Throwable = function () {
    function java_lang_Throwable() {
    }
    java_lang_Throwable['fillInStackTrace(I)Ljava/lang/Throwable;'] = function (thread, javaThis, dummy) {
        var stackTraceElementCls = thread.getBsCl().getInitializedClass(thread, 'Ljava/lang/StackTraceElement;'), stacktrace = util.newArray(thread, thread.getBsCl(), '[Ljava/lang/StackTraceElement;', 0), cstack = thread.getStackTrace(), i, j, bsCl = thread.getBsCl();
        cstack.pop();
        while (cstack.length > 0 && !cstack[cstack.length - 1].method.accessFlags.isNative() && cstack[cstack.length - 1].locals[0] === javaThis) {
            cstack.pop();
        }
        for (i = cstack.length - 1; i >= 0; i--) {
            var sf = cstack[i], cls = sf.method.cls, ln = -1, sourceFile;
            if (sf.method.isHidden()) {
                continue;
            }
            if (sf.method.accessFlags.isNative()) {
                sourceFile = 'Native Method';
            } else {
                var srcAttr = cls.getAttribute('SourceFile'), code = sf.method.getCodeAttribute(), table = code.getAttribute('LineNumberTable');
                sourceFile = srcAttr != null ? srcAttr.filename : 'unknown';
                if (table != null) {
                    ln = table.getLineNumber(sf.pc);
                } else {
                    ln = -1;
                }
            }
            var newElement = util.newObjectFromClass(thread, stackTraceElementCls);
            newElement['java/lang/StackTraceElement/declaringClass'] = util.initString(bsCl, util.ext_classname(cls.getInternalName()));
            newElement['java/lang/StackTraceElement/methodName'] = util.initString(bsCl, sf.method.name != null ? sf.method.name : 'unknown');
            newElement['java/lang/StackTraceElement/fileName'] = util.initString(bsCl, sourceFile);
            newElement['java/lang/StackTraceElement/lineNumber'] = ln;
            stacktrace.array.push(newElement);
        }
        javaThis['java/lang/Throwable/backtrace'] = stacktrace;
        return javaThis;
    };
    java_lang_Throwable['getStackTraceDepth()I'] = function (thread, javaThis) {
        return javaThis['java/lang/Throwable/backtrace'].array.length;
    };
    java_lang_Throwable['getStackTraceElement(I)Ljava/lang/StackTraceElement;'] = function (thread, javaThis, depth) {
        return javaThis['java/lang/Throwable/backtrace'].array[depth];
    };
    return java_lang_Throwable;
}();
var java_lang_UNIXProcess = function () {
    function java_lang_UNIXProcess() {
    }
    java_lang_UNIXProcess['waitForProcessExit(I)I'] = function (thread, javaThis, arg0) {
        thread.throwNewException('Ljava/lang/UnsatisfiedLinkError;', 'Native method not implemented.');
        return 0;
    };
    java_lang_UNIXProcess['forkAndExec(I[B[B[BI[BI[B[IZ)I'] = function (thread, javaThis) {
        thread.throwNewException('Ljava/lang/Error;', 'Doppio doesn\'t support forking processes.');
    };
    java_lang_UNIXProcess['destroyProcess(IZ)V'] = function (thread, arg0) {
        thread.throwNewException('Ljava/lang/UnsatisfiedLinkError;', 'Native method not implemented.');
    };
    java_lang_UNIXProcess['init()V'] = function (thread) {
        thread.throwNewException('Ljava/lang/UnsatisfiedLinkError;', 'Native method not implemented.');
    };
    return java_lang_UNIXProcess;
}();
var MemberNameConstants;
(function (MemberNameConstants) {
    MemberNameConstants[MemberNameConstants['IS_METHOD'] = 65536] = 'IS_METHOD';
    MemberNameConstants[MemberNameConstants['IS_CONSTRUCTOR'] = 131072] = 'IS_CONSTRUCTOR';
    MemberNameConstants[MemberNameConstants['IS_FIELD'] = 262144] = 'IS_FIELD';
    MemberNameConstants[MemberNameConstants['IS_TYPE'] = 524288] = 'IS_TYPE';
    MemberNameConstants[MemberNameConstants['CALLER_SENSITIVE'] = 1048576] = 'CALLER_SENSITIVE';
    MemberNameConstants[MemberNameConstants['SEARCH_SUPERCLASSES'] = 1048576] = 'SEARCH_SUPERCLASSES';
    MemberNameConstants[MemberNameConstants['SEARCH_INTERFACES'] = 2097152] = 'SEARCH_INTERFACES';
    MemberNameConstants[MemberNameConstants['REFERENCE_KIND_SHIFT'] = 24] = 'REFERENCE_KIND_SHIFT';
    MemberNameConstants[MemberNameConstants['ALL_KINDS'] = 983040] = 'ALL_KINDS';
}(MemberNameConstants || (MemberNameConstants = {})));
function initializeMemberName(thread, mn, ref) {
    var flags = mn['java/lang/invoke/MemberName/flags'], type = mn['java/lang/invoke/MemberName/type'], name = mn['java/lang/invoke/MemberName/name'], refKind, existingRefKind = flags >>> MemberNameConstants.REFERENCE_KIND_SHIFT;
    if (ref instanceof Method) {
        flags = MemberNameConstants.IS_METHOD;
        if (ref.cls.accessFlags.isInterface()) {
            refKind = MethodHandleReferenceKind.INVOKEINTERFACE;
        } else if (ref.accessFlags.isStatic()) {
            refKind = MethodHandleReferenceKind.INVOKESTATIC;
        } else if (ref.name[0] === '<') {
            flags = MemberNameConstants.IS_CONSTRUCTOR;
            refKind = MethodHandleReferenceKind.INVOKESPECIAL;
        } else {
            refKind = MethodHandleReferenceKind.INVOKEVIRTUAL;
        }
        mn.vmtarget = ref.getVMTargetBridgeMethod(thread, existingRefKind ? existingRefKind : refKind);
        if (refKind === MethodHandleReferenceKind.INVOKEINTERFACE || refKind === MethodHandleReferenceKind.INVOKEVIRTUAL) {
            mn.vmindex = ref.cls.getVMIndexForMethod(ref);
        }
        flags |= refKind << MemberNameConstants.REFERENCE_KIND_SHIFT | methodFlags(ref);
    } else {
        flags = MemberNameConstants.IS_FIELD;
        if (ref.accessFlags.isStatic()) {
            refKind = MethodHandleReferenceKind.GETSTATIC;
        } else {
            refKind = MethodHandleReferenceKind.GETFIELD;
        }
        mn.vmindex = ref.cls.getVMIndexForField(ref);
        flags |= refKind << MemberNameConstants.REFERENCE_KIND_SHIFT | ref.accessFlags.getRawByte();
    }
    if (type === null) {
        type = thread.getJVM().internString(ref.rawDescriptor);
    }
    if (name === null) {
        name = thread.getJVM().internString(ref.name);
    }
    mn['java/lang/invoke/MemberName/clazz'] = ref.cls.getClassObject(thread);
    mn['java/lang/invoke/MemberName/flags'] = flags;
    mn['java/lang/invoke/MemberName/type'] = type;
    mn['java/lang/invoke/MemberName/name'] = name;
}
function methodFlags(method) {
    var flags = method.accessFlags.getRawByte();
    if (method.isCallerSensitive()) {
        flags |= MemberNameConstants.CALLER_SENSITIVE;
    }
    return flags;
}
var java_lang_invoke_MethodHandleNatives = function () {
    function java_lang_invoke_MethodHandleNatives() {
    }
    java_lang_invoke_MethodHandleNatives['init(Ljava/lang/invoke/MemberName;Ljava/lang/Object;)V'] = function (thread, self, ref) {
        var clazz, clazzData, flags, m, f;
        switch (ref.getClass().getInternalName()) {
        case 'Ljava/lang/reflect/Method;':
            var methodObj = ref, refKind;
            clazz = methodObj['java/lang/reflect/Method/clazz'];
            clazzData = clazz.$cls;
            m = clazzData.getMethodFromSlot(methodObj['java/lang/reflect/Method/slot']);
            flags = methodFlags(m) | MemberNameConstants.IS_METHOD;
            if (m.accessFlags.isStatic()) {
                refKind = MethodHandleReferenceKind.INVOKESTATIC;
            } else if (clazzData.accessFlags.isInterface()) {
                refKind = MethodHandleReferenceKind.INVOKEINTERFACE;
            } else {
                refKind = MethodHandleReferenceKind.INVOKEVIRTUAL;
            }
            flags |= refKind << MemberNameConstants.REFERENCE_KIND_SHIFT;
            self['java/lang/invoke/MemberName/clazz'] = clazz;
            self['java/lang/invoke/MemberName/flags'] = flags;
            self.vmtarget = m.getVMTargetBridgeMethod(thread, refKind);
            if (refKind === MethodHandleReferenceKind.INVOKEVIRTUAL || refKind === MethodHandleReferenceKind.INVOKEINTERFACE) {
                self.vmindex = clazzData.getVMIndexForMethod(m);
            }
            break;
        case 'Ljava/lang/reflect/Constructor;':
            var consObj = ref;
            clazz = consObj['java/lang/reflect/Constructor/clazz'];
            clazzData = clazz.$cls;
            m = clazzData.getMethodFromSlot(consObj['java/lang/reflect/Constructor/slot']);
            flags = methodFlags(m) | MemberNameConstants.IS_CONSTRUCTOR | MethodHandleReferenceKind.INVOKESPECIAL << MemberNameConstants.REFERENCE_KIND_SHIFT;
            self['java/lang/invoke/MemberName/clazz'] = clazz;
            self['java/lang/invoke/MemberName/flags'] = flags;
            self.vmtarget = m.getVMTargetBridgeMethod(thread, refKind);
            break;
        case 'Ljava/lang/reflect/Field;':
            var fieldObj = ref;
            clazz = fieldObj['java/lang/reflect/Field/clazz'];
            clazzData = clazz.$cls;
            f = clazzData.getFieldFromSlot(fieldObj['java/lang/reflect/Field/slot']);
            flags = f.accessFlags.getRawByte() | MemberNameConstants.IS_FIELD;
            flags |= (f.accessFlags.isStatic() ? MethodHandleReferenceKind.GETSTATIC : MethodHandleReferenceKind.GETFIELD) << MemberNameConstants.REFERENCE_KIND_SHIFT;
            self['java/lang/invoke/MemberName/clazz'] = clazz;
            self['java/lang/invoke/MemberName/flags'] = flags;
            self.vmindex = clazzData.getVMIndexForField(f);
            break;
        default:
            thread.throwNewException('Ljava/lang/InternalError;', 'init: Invalid target.');
            break;
        }
    };
    java_lang_invoke_MethodHandleNatives['getConstant(I)I'] = function (thread, arg0) {
        return 0;
    };
    java_lang_invoke_MethodHandleNatives['resolve(Ljava/lang/invoke/MemberName;Ljava/lang/Class;)Ljava/lang/invoke/MemberName;'] = function (thread, memberName, lookupClass) {
        var type = memberName['java/lang/invoke/MemberName/type'], name = memberName['java/lang/invoke/MemberName/name'].toString(), clazz = memberName['java/lang/invoke/MemberName/clazz'].$cls, flags = memberName['java/lang/invoke/MemberName/flags'], refKind = flags >>> MemberNameConstants.REFERENCE_KIND_SHIFT;
        if (clazz == null || name == null || type == null) {
            thread.throwNewException('Ljava/lang/IllegalArgumentException;', 'Invalid MemberName.');
            return;
        }
        assert((flags & MemberNameConstants.CALLER_SENSITIVE) === 0, 'Not yet supported: Caller sensitive methods.');
        switch (flags & MemberNameConstants.ALL_KINDS) {
        case MemberNameConstants.IS_CONSTRUCTOR:
        case MemberNameConstants.IS_METHOD:
            var methodTarget = clazz.signaturePolymorphicAwareMethodLookup(name + type.toString());
            if (methodTarget !== null) {
                flags |= methodFlags(methodTarget);
                memberName['java/lang/invoke/MemberName/flags'] = flags;
                memberName.vmtarget = methodTarget.getVMTargetBridgeMethod(thread, flags >>> MemberNameConstants.REFERENCE_KIND_SHIFT);
                if (refKind === MethodHandleReferenceKind.INVOKEINTERFACE || refKind === MethodHandleReferenceKind.INVOKEVIRTUAL) {
                    memberName.vmindex = clazz.getVMIndexForMethod(methodTarget);
                }
                return memberName;
            } else {
                thread.throwNewException('Ljava/lang/NoSuchMethodError;', 'Invalid method ' + (name + type.toString()) + ' in class ' + clazz.getExternalName() + '.');
            }
            break;
        case MemberNameConstants.IS_FIELD:
            var fieldTarget = clazz.fieldLookup(name);
            if (fieldTarget !== null) {
                flags |= fieldTarget.accessFlags.getRawByte();
                memberName['java/lang/invoke/MemberName/flags'] = flags;
                memberName.vmindex = clazz.getVMIndexForField(fieldTarget);
                return memberName;
            } else {
                thread.throwNewException('Ljava/lang/NoSuchFieldError;', 'Invalid method ' + name + ' in class ' + clazz.getExternalName() + '.');
            }
            break;
        default:
            thread.throwNewException('Ljava/lang/LinkageError;', 'resolve member name');
            break;
        }
    };
    java_lang_invoke_MethodHandleNatives['objectFieldOffset(Ljava/lang/invoke/MemberName;)J'] = function (thread, memberName) {
        if (memberName['vmindex'] === -1) {
            thread.throwNewException('Ljava/lang/IllegalStateException;', 'Attempted to retrieve the object offset for an unresolved or non-object MemberName.');
        } else {
            return Long.fromNumber(memberName.vmindex);
        }
    };
    java_lang_invoke_MethodHandleNatives['staticFieldOffset(Ljava/lang/invoke/MemberName;)J'] = function (thread, memberName) {
        if (memberName['vmindex'] === -1) {
            thread.throwNewException('Ljava/lang/IllegalStateException;', 'Attempted to retrieve the object offset for an unresolved or non-object MemberName.');
        } else {
            return Long.fromNumber(memberName.vmindex);
        }
    };
    java_lang_invoke_MethodHandleNatives['staticFieldBase(Ljava/lang/invoke/MemberName;)Ljava/lang/Object;'] = function (thread, memberName) {
        var rv = new (thread.getBsCl().getInitializedClass(thread, 'Ljava/lang/Object;').getConstructor(thread))(thread);
        rv.$staticFieldBase = memberName['java/lang/invoke/MemberName/clazz'].$cls;
        return rv;
    };
    java_lang_invoke_MethodHandleNatives['getMembers(Ljava/lang/Class;Ljava/lang/String;Ljava/lang/String;ILjava/lang/Class;I[Ljava/lang/invoke/MemberName;)I'] = function (thread, defc, matchName, matchSig, matchFlags, caller, skip, results) {
        var searchSuperclasses = 0 !== (matchFlags & MemberNameConstants.SEARCH_SUPERCLASSES), searchInterfaces = 0 !== (matchFlags & MemberNameConstants.SEARCH_INTERFACES), matched = 0, targetClass = defc.$cls, methods, fields, matchArray = results.array, name = matchName !== null ? matchName.toString() : null, sig = matchSig !== null ? matchSig.toString() : null;
        function addMatch(item) {
            if (skip >= 0) {
                if (matched < matchArray.length) {
                    initializeMemberName(thread, matchArray[matched], item);
                }
                matched++;
            } else {
                skip--;
            }
        }
        assert(!searchSuperclasses && !searchInterfaces, 'Unsupported: Non-local getMembers calls.');
        if (0 !== (matchFlags & MemberNameConstants.IS_CONSTRUCTOR) && (name === null || name === '<init>')) {
            methods = targetClass.getMethods();
            methods.forEach(function (m) {
                if (m.name === '<init>' && (sig === null || sig === m.rawDescriptor)) {
                    addMatch(m);
                }
            });
        }
        if (0 !== (matchFlags & MemberNameConstants.IS_METHOD)) {
            methods = targetClass.getMethods();
            methods.forEach(function (m) {
                if (m.name !== '<init>' && (name === null || name === m.name) && (sig === null || sig === m.rawDescriptor)) {
                    addMatch(m);
                }
            });
        }
        if (0 !== (matchFlags & MemberNameConstants.IS_FIELD) && sig === null) {
            fields = targetClass.getFields();
            fields.forEach(function (f) {
                if (name === null || name === f.name) {
                    addMatch(f);
                }
            });
        }
        assert(0 == (matchFlags & MemberNameConstants.IS_TYPE), 'Unsupported: Getting inner type MemberNames.');
        return matched;
    };
    java_lang_invoke_MethodHandleNatives['getNamedCon(I[Ljava/lang/Object;)I'] = function (thread, fieldNum, args) {
        thread.setStatus(ThreadStatus.ASYNC_WAITING);
        thread.getBsCl().initializeClass(thread, 'Ljava/lang/invoke/MethodHandleNatives$Constants;', function (constantsCls) {
            if (constantsCls === null) {
                return;
            }
            var constants = constantsCls.getFields().filter(function (field) {
                return field.accessFlags.isStatic() && field.accessFlags.isFinal();
            });
            if (fieldNum < constants.length) {
                var field = constants[fieldNum];
                args.array[0] = util.initString(thread.getBsCl(), field.name);
                thread.asyncReturn(constantsCls.getConstructor(thread)[field.fullName]);
            } else {
                thread.asyncReturn(-1);
            }
        });
    };
    java_lang_invoke_MethodHandleNatives['getMemberVMInfo(Ljava/lang/invoke/MemberName;)Ljava/lang/Object;'] = function (thread, mname) {
        var rv = util.newArray(thread, thread.getBsCl(), '[Ljava/lang/Object;', 2), flags = mname['java/lang/invoke/MemberName/flags'], refKind = flags >>> MemberNameConstants.REFERENCE_KIND_SHIFT, longCls = thread.getBsCl().getInitializedClass(thread, 'J');
        rv.array[0] = longCls.createWrapperObject(thread, Long.fromNumber(mname.vmindex));
        rv.array[1] = (flags & MemberNameConstants.ALL_KINDS & MemberNameConstants.IS_FIELD) > 0 ? mname['java/lang/invoke/MemberName/clazz'] : mname;
        return rv;
    };
    java_lang_invoke_MethodHandleNatives['setCallSiteTargetNormal(Ljava/lang/invoke/CallSite;Ljava/lang/invoke/MethodHandle;)V'] = function (thread, callSite, methodHandle) {
        callSite['java/lang/invoke/CallSite/target'] = methodHandle;
    };
    return java_lang_invoke_MethodHandleNatives;
}();
var java_lang_invoke_MethodHandle = function () {
    function java_lang_invoke_MethodHandle() {
    }
    java_lang_invoke_MethodHandle['invokeExact([Ljava/lang/Object;)Ljava/lang/Object;'] = function (thread, mh, args) {
        thread.throwNewException('Ljava/lang/UnsupportedOperationException;', 'MethodHandle.invokeExact cannot be invoked reflectively');
    };
    java_lang_invoke_MethodHandle['invoke([Ljava/lang/Object;)Ljava/lang/Object;'] = function (thread, mh, args) {
        thread.throwNewException('Ljava/lang/UnsupportedOperationException;', 'MethodHandle.invoke cannot be invoked reflectively');
    };
    java_lang_invoke_MethodHandle['invokeBasic([Ljava/lang/Object;)Ljava/lang/Object;'] = function (thread, mh, argsBoxed) {
        var lmbdaForm = mh['java/lang/invoke/MethodHandle/form'], mn = lmbdaForm['java/lang/invoke/LambdaForm/vmentry'], descriptor, paramTypes;
        assert(mh.getClass().isCastable(thread.getBsCl().getInitializedClass(thread, 'Ljava/lang/invoke/MethodHandle;')), 'First argument to invokeBasic must be a method handle.');
        assert(mn.vmtarget !== null && mn.vmtarget !== undefined, 'vmtarget must be defined');
        assert(mn['java/lang/invoke/MemberName/type'].getClass().getInternalName() === 'Ljava/lang/invoke/MethodType;', 'Expected a MethodType object.');
        descriptor = mn['java/lang/invoke/MemberName/type'].toString();
        paramTypes = util.getTypes(descriptor);
        paramTypes.pop();
        paramTypes.shift();
        thread.setStatus(ThreadStatus.ASYNC_WAITING);
        mn.vmtarget(thread, descriptor, [mh].concat(util.unboxArguments(thread, paramTypes, argsBoxed.array)), function (e, rv) {
            if (e) {
                thread.throwException(e);
            } else {
                thread.asyncReturn(rv);
            }
        });
    };
    return java_lang_invoke_MethodHandle;
}();
registerNatives({
    'java/lang/Class': java_lang_Class,
    'java/lang/ClassLoader$NativeLibrary': java_lang_ClassLoader$NativeLibrary,
    'java/lang/ClassLoader': java_lang_ClassLoader,
    'java/lang/Compiler': java_lang_Compiler,
    'java/lang/Double': java_lang_Double,
    'java/lang/Float': java_lang_Float,
    'java/lang/Object': java_lang_Object,
    'java/lang/Package': java_lang_Package,
    'java/lang/ProcessEnvironment': java_lang_ProcessEnvironment,
    'java/lang/reflect/Array': java_lang_reflect_Array,
    'java/lang/reflect/Proxy': java_lang_reflect_Proxy,
    'java/lang/Runtime': java_lang_Runtime,
    'java/lang/SecurityManager': java_lang_SecurityManager,
    'java/lang/Shutdown': java_lang_Shutdown,
    'java/lang/StrictMath': java_lang_StrictMath,
    'java/lang/String': java_lang_String,
    'java/lang/System': java_lang_System,
    'java/lang/Thread': java_lang_Thread,
    'java/lang/Throwable': java_lang_Throwable,
    'java/lang/UNIXProcess': java_lang_UNIXProcess,
    'java/lang/invoke/MethodHandleNatives': java_lang_invoke_MethodHandleNatives,
    'java/lang/invoke/MethodHandle': java_lang_invoke_MethodHandle
});
//# sourceMappingURL=java_lang.js.map