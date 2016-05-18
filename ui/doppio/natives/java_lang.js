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
//# sourceMappingURL=data:application/json;base64,eyJ2ZXJzaW9uIjozLCJzb3VyY2VzIjpbIi4uLy4uLy4uLy4uL3NyYy9uYXRpdmVzL2phdmFfbGFuZy50cyJdLCJuYW1lcyI6W10sIm1hcHBpbmdzIjoiO0FBQUEsSUFBWSxNQUFBLEdBQU0sT0FBQSxDQUFNLGNBQU4sQ0FBbEI7QUFFQSxJQUFPLGtCQUFBLEdBQXFCLE1BQUEsQ0FBTyxFQUFQLENBQVUsU0FBVixDQUFvQixrQkFBaEQ7QUFDQSxJQUFPLE9BQUEsR0FBVSxNQUFBLENBQU8sS0FBUCxDQUFhLE9BQTlCO0FBQ0EsSUFBTyxJQUFBLEdBQU8sTUFBQSxDQUFPLEVBQVAsQ0FBVSxJQUF4QjtBQUNBLElBQU8sY0FBQSxHQUFpQixNQUFBLENBQU8sRUFBUCxDQUFVLFNBQVYsQ0FBb0IsY0FBNUM7QUFDQSxJQUFPLFlBQUEsR0FBZSxNQUFBLENBQU8sRUFBUCxDQUFVLEtBQVYsQ0FBZ0IsWUFBdEM7QUFDQSxJQUFPLE1BQUEsR0FBUyxNQUFBLENBQU8sRUFBUCxDQUFVLFNBQVYsQ0FBb0IsTUFBcEM7QUFHQSxJQUFPLElBQUEsR0FBTyxNQUFBLENBQU8sRUFBUCxDQUFVLElBQXhCO0FBQ0EsSUFBTyxNQUFBLEdBQVMsTUFBQSxDQUFPLEtBQVAsQ0FBYSxNQUE3QjtBQUVBLElBQU8sa0JBQUEsR0FBcUIsTUFBQSxDQUFPLEVBQVAsQ0FBVSxTQUFWLENBQW9CLGtCQUFoRDtBQUNBLElBQU8seUJBQUEsR0FBNEIsTUFBQSxDQUFPLEVBQVAsQ0FBVSxLQUFWLENBQWdCLHlCQUFuRDtBQU1BLElBQUksS0FBQSxHQUFRLE9BQUEsQ0FBUSxLQUFwQjtBQUVBLFNBQUEsUUFBQSxDQUFrQixNQUFsQixFQUFxQyxHQUFyQyxFQUFrRSxHQUFsRSxFQUE2RTtBQUFBLElBQzNFLElBQUksR0FBQSxJQUFPLElBQVgsRUFBaUI7QUFBQSxRQUNmLE1BQUEsQ0FBTyxpQkFBUCxDQUF5QixrQ0FBekIsRUFBNkQsRUFBN0QsRUFEZTtBQUFBLEtBQWpCLE1BRU87QUFBQSxRQUNMLElBQUksS0FBQSxHQUFRLEdBQUEsQ0FB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b250ZW50IjpbImltcG9ydCAqIGFzIERvcHBpbyBmcm9tICcuLi9kb3BwaW9qdm0nO1xuaW1wb3J0IEpWTVRocmVhZCA9IERvcHBpby5WTS5UaHJlYWRpbmcuSlZNVGhyZWFkO1xuaW1wb3J0IFJlZmVyZW5jZUNsYXNzRGF0YSA9IERvcHBpby5WTS5DbGFzc0ZpbGUuUmVmZXJlbmNlQ2xhc3NEYXRhO1xuaW1wb3J0IGxvZ2dpbmcgPSBEb3BwaW8uRGVidWcuTG9nZ2luZztcbmltcG9ydCB1dGlsID0gRG9wcGlvLlZNLlV0aWw7XG5pbXBvcnQgQXJyYXlDbGFzc0RhdGEgPSBEb3BwaW8uVk0uQ2xhc3NGaWxlLkFycmF5Q2xhc3NEYXRhO1xuaW1wb3J0IFRocmVhZFN0YXR1cyA9IERvcHBpby5WTS5FbnVtcy5UaHJlYWRTdGF0dXM7XG5pbXBvcnQgTWV0aG9kID0gRG9wcGlvLlZNLkNsYXNzRmlsZS5NZXRob2Q7XG5pbXBvcnQgRmllbGQgPSBEb3BwaW8uVk0uQ2xhc3NGaWxlLkZpZWxkO1xuaW1wb3J0IEFic3RyYWN0TWV0aG9kRmllbGQgPSBEb3BwaW8uVk0uQ2xhc3NGaWxlLkFic3RyYWN0TWV0aG9kRmllbGQ7XG5pbXBvcnQgTG9uZyA9IERvcHBpby5WTS5Mb25nO1xuaW1wb3J0IGFzc2VydCA9IERvcHBpby5EZWJ1Zy5Bc3NlcnQ7XG5pbXBvcnQgQ29uc3RhbnRQb29sID0gRG9wcGlvLlZNLkNsYXNzRmlsZS5Db25zdGFudFBvb2w7XG5pbXBvcnQgUHJpbWl0aXZlQ2xhc3NEYXRhID0gRG9wcGlvLlZNLkNsYXNzRmlsZS5QcmltaXRpdmVDbGFzc0RhdGE7XG5pbXBvcnQgTWV0aG9kSGFuZGxlUmVmZXJlbmNlS2luZCA9IERvcHBpby5WTS5FbnVtcy5NZXRob2RIYW5kbGVSZWZlcmVuY2VLaW5kO1xuaW1wb3J0IGF0dHJpYnV0ZXMgPSBEb3BwaW8uVk0uQ2xhc3NGaWxlLkF0dHJpYnV0ZXM7XG5pbXBvcnQgQ2xhc3NEYXRhID0gRG9wcGlvLlZNLkNsYXNzRmlsZS5DbGFzc0RhdGE7XG5pbXBvcnQgSlZNVHlwZXMgPSByZXF1aXJlKCcuLi8uLi9pbmNsdWRlcy9KVk1UeXBlcycpO1xuZGVjbGFyZSB2YXIgcmVnaXN0ZXJOYXRpdmVzOiAoZGVmczogYW55KSA9PiB2b2lkO1xuXG52YXIgZGVidWcgPSBsb2dnaW5nLmRlYnVnO1xuXG5mdW5jdGlvbiBhcnJheUdldCh0aHJlYWQ6IEpWTVRocmVhZCwgYXJyOiBKVk1UeXBlcy5KVk1BcnJheTxhbnk+LCBpZHg6IG51bWJlcik6IGFueSB7XG4gIGlmIChhcnIgPT0gbnVsbCkge1xuICAgIHRocmVhZC50aHJvd05ld0V4Y2VwdGlvbignTGphdmEvbGFuZy9OdWxsUG9pbnRlckV4Y2VwdGlvbjsnLCAnJyk7XG4gIH0gZWxzZSB7XG4gICAgdmFyIGFycmF5ID0gYXJyLmFycmF5O1xuICAgIGlmIChpZHggPCAwIHx8IGlkeCA+PSBhcnJheS5sZW5ndGgpIHtcbiAgICAgIHRocmVhZC50aHJvd05ld0V4Y2VwdGlvbignTGphdmEvbGFuZy9BcnJheUluZGV4T3V0T2ZCb3VuZHNFeGNlcHRpb247JywgJ1RyaWVkIHRvIGFjY2VzcyBhbiBpbGxlZ2FsIGluZGV4IGluIGFuIGFycmF5LicpO1xuICAgIH0gZWxzZSB7XG4gICAgICByZXR1cm4gYXJyYXlbaWR4XTtcbiAgICB9XG4gIH1cbn1cblxuZnVuY3Rpb24gaXNOb3ROdWxsKHRocmVhZDogSlZNVGhyZWFkLCBvYmo6IEpWTVR5cGVzLmphdmFfbGFuZ19PYmplY3QpOiBib29sZWFuIHtcbiAgaWYgKG9iaiA9PSBudWxsKSB7XG4gICAgdGhyZWFkLnRocm93TmV3RXhjZXB0aW9uKCdMamF2YS9sYW5nL051bGxQb2ludGVyRXhjZXB0aW9uOycsICcnKTtcbiAgICByZXR1cm4gZmFsc2U7XG4gIH0gZWxzZSB7XG4gICAgcmV0dXJuIHRydWU7XG4gIH1cbn1cblxuZnVuY3Rpb24gdmVyaWZ5QXJyYXkodGhyZWFkOiBKVk1UaHJlYWQsIG9iajogSlZNVHlwZXMuSlZNQXJyYXk8YW55Pik6IGJvb2xlYW4ge1xuICBpZiAoIShvYmouZ2V0Q2xhc3MoKSBpbnN0YW5jZW9mIEFycmF5Q2xhc3NEYXRhKSkge1xuICAgIHRocmVhZC50aHJvd05ld0V4Y2VwdGlvbignTGphdmEvbGFuZy9JbGxlZ2FsQXJndW1lbnRFeGNlcHRpb247JywgJ09iamVjdCBpcyBub3QgYW4gYXJyYXkuJyk7XG4gICAgcmV0dXJuIGZhbHNlO1xuICB9IGVsc2Uge1xuICAgIHJldHVybiB0cnVlO1xuICB9XG59XG5cbmNsYXNzIGphdmFfbGFuZ19DbGFzcyB7XG5cbiAgcHVibGljIHN0YXRpYyAnZm9yTmFtZTAoTGphdmEvbGFuZy9TdHJpbmc7WkxqYXZhL2xhbmcvQ2xhc3NMb2FkZXI7TGphdmEvbGFuZy9DbGFzczspTGphdmEvbGFuZy9DbGFzczsnKHRocmVhZDogSlZNVGhyZWFkLCBqdm1TdHI6IEpWTVR5cGVzLmphdmFfbGFuZ19TdHJpbmcsIGluaXRpYWxpemU6IG51bWJlciwgamNsbzogSlZNVHlwZXMuamF2YV9sYW5nX0NsYXNzTG9hZGVyLCBjYWxsZXI6IEpWTVR5cGVzLmphdmFfbGFuZ19DbGFzcyk6IHZvaWQge1xuICAgIHZhciBjbGFzc25hbWUgPSB1dGlsLmludF9jbGFzc25hbWUoanZtU3RyLnRvU3RyaW5nKCkpO1xuICAgIGlmICghdXRpbC52ZXJpZnlfaW50X2NsYXNzbmFtZShjbGFzc25hbWUpKSB7XG4gICAgICB0aHJlYWQudGhyb3dOZXdFeGNlcHRpb24oJ0xqYXZhL2xhbmcvQ2xhc3NOb3RGb3VuZEV4Y2VwdGlvbjsnLCBjbGFzc25hbWUpO1xuICAgIH0gZWxzZSB7XG4gICAgICB2YXIgbG9hZGVyID0gdXRpbC5nZXRMb2FkZXIodGhyZWFkLCBqY2xvKTtcbiAgICAgIHRocmVhZC5zZXRTdGF0dXMoVGhyZWFkU3RhdHVzLkFTWU5DX1dBSVRJTkcpO1xuICAgICAgaWYgKGluaXRpYWxpemUpIHtcbiAgICAgICAgbG9hZGVyLmluaXRpYWxpemVDbGFzcyh0aHJlYWQsIGNsYXNzbmFtZSwgKGNsczogUmVmZXJlbmNlQ2xhc3NEYXRhPEpWTVR5cGVzLmphdmFfbGFuZ19PYmplY3Q+KSA9PiB7XG4gICAgICAgICAgaWYgKGNscyAhPSBudWxsKSB7XG4gICAgICAgICAgICB0aHJlYWQuYXN5bmNSZXR1cm4oY2xzLmdldENsYXNzT2JqZWN0KHRocmVhZCkpO1xuICAgICAgICAgIH1cbiAgICAgICAgfSk7XG4gICAgICB9IGVsc2Uge1xuICAgICAgICBsb2FkZXIucmVzb2x2ZUNsYXNzKHRocmVhZCwgY2xhc3NuYW1lLCAoY2xzOiBSZWZlcmVuY2VDbGFzc0RhdGE8SlZNVHlwZXMuamF2YV9sYW5nX09iamVjdD4pID0+IHtcbiAgICAgICAgICBpZiAoY2xzICE9IG51bGwpIHtcbiAgICAgICAgICAgIHRocmVhZC5hc3luY1JldHVybihjbHMuZ2V0Q2xhc3NPYmplY3QodGhyZWFkKSk7XG4gICAgICAgICAgfVxuICAgICAgICB9KTtcbiAgICAgIH1cbiAgICB9XG4gIH1cblxuICBwdWJsaWMgc3RhdGljICdpc0luc3RhbmNlKExqYXZhL2xhbmcvT2JqZWN0OylaJyh0aHJlYWQ6IEpWTVRocmVhZCwgamF2YVRoaXM6IEpWTVR5cGVzLmphdmFfbGFuZ19DbGFzcywgb2JqOiBKVk1UeXBlcy5qYXZhX2xhbmdfT2JqZWN0KTogYm9vbGVhbiB7XG4gICAgaWYgKG9iaiAhPT0gbnVsbCkge1xuICAgICAgcmV0dXJuIG9iai5nZXRDbGFzcygpLmlzQ2FzdGFibGUoamF2YVRoaXMuJGNscyk7XG4gICAgfSBlbHNlIHtcbiAgICAgIHJldHVybiBmYWxzZTtcbiAgICB9XG4gIH1cblxuICBwdWJsaWMgc3RhdGljICdpc0Fzc2lnbmFibGVGcm9tKExqYXZhL2xhbmcvQ2xhc3M7KVonKHRocmVhZDogSlZNVGhyZWFkLCBqYXZhVGhpczogSlZNVHlwZXMuamF2YV9sYW5nX0NsYXNzLCBjbHM6IEpWTVR5cGVzLmphdmFfbGFuZ19DbGFzcyk6IGJvb2xlYW4ge1xuICAgIHJldHVybiBjbHMuJGNscy5pc0Nhc3RhYmxlKGphdmFUaGlzLiRjbHMpO1xuICB9XG5cbiAgcHVibGljIHN0YXRpYyAnaXNJbnRlcmZhY2UoKVonKHRocmVhZDogSlZNVGhyZWFkLCBqYXZhVGhpczogSlZNVHlwZXMuamF2YV9sYW5nX0NsYXNzKTogYm9vbGVhbiB7XG4gICAgaWYgKCEoamF2YVRoaXMuJGNscyBpbnN0YW5jZW9mIFJlZmVyZW5jZUNsYXNzRGF0YSkpIHtcbiAgICAgIHJldHVybiBmYWxzZTtcbiAgICB9XG4gICAgcmV0dXJuIGphdmFUaGlzLiRjbHMuYWNjZXNzRmxhZ3MuaXNJbnRlcmZhY2UoKTtcbiAgfVxuXG4gIHB1YmxpYyBzdGF0aWMgJ2lzQXJyYXkoKVonKHRocmVhZDogSlZNVGhyZWFkLCBqYXZhVGhpczogSlZNVHlwZXMuamF2YV9sYW5nX0NsYXNzKTogYm9vbGVhbiB7XG4gICAgcmV0dXJuIGphdmFUaGlzLiRjbHMgaW5zdGFuY2VvZiBBcnJheUNsYXNzRGF0YTtcbiAgfVxuXG4gIHB1YmxpYyBzdGF0aWMgJ2lzUHJpbWl0aXZlKClaJyh0aHJlYWQ6IEpWTVRocmVhZCwgamF2YVRoaXM6IEpWTVR5cGVzLmphdmFfbGFuZ19DbGFzcyk6IGJvb2xlYW4ge1xuICAgIHJldHVybiBqYXZhVGhpcy4kY2xzIGluc3RhbmNlb2YgUHJpbWl0aXZlQ2xhc3NEYXRhO1xuICB9XG5cbiAgcHVibGljIHN0YXRpYyAnZ2V0TmFtZTAoKUxqYXZhL2xhbmcvU3RyaW5nOycodGhyZWFkOiBKVk1UaHJlYWQsIGphdmFUaGlzOiBKVk1UeXBlcy5qYXZhX2xhbmdfQ2xhc3MpOiBKVk1UeXBlcy5qYXZhX2xhbmdfU3RyaW5nIHtcbiAgICByZXR1cm4gdXRpbC5pbml0U3RyaW5nKHRocmVhZC5nZXRCc0NsKCksIGphdmFUaGlzLiRjbHMuZ2V0RXh0ZXJuYWxOYW1lKCkpO1xuICB9XG5cbiAgcHVibGljIHN0YXRpYyAnZ2V0U3VwZXJjbGFzcygpTGphdmEvbGFuZy9DbGFzczsnKHRocmVhZDogSlZNVGhyZWFkLCBqYXZhVGhpczogSlZNVHlwZXMuamF2YV9sYW5nX0NsYXNzKTogSlZNVHlwZXMuamF2YV9sYW5nX0NsYXNzIHtcbiAgICBpZiAoamF2YVRoaXMuJGNscyBpbnN0YW5jZW9mIFByaW1pdGl2ZUNsYXNzRGF0YSkge1xuICAgICAgcmV0dXJuIG51bGw7XG4gICAgfVxuICAgIHZhciBjbHMgPSBqYXZhVGhpcy4kY2xzO1xuICAgIGlmIChjbHMuYWNjZXNzRmxhZ3MuaXNJbnRlcmZhY2UoKSB8fCAoY2xzLmdldFN1cGVyQ2xhc3MoKSA9PSBudWxsKSkge1xuICAgICAgcmV0dXJuIG51bGw7XG4gICAgfVxuICAgIHJldHVybiBjbHMuZ2V0U3VwZXJDbGFzcygpLmdldENsYXNzT2JqZWN0KHRocmVhZCk7XG4gIH1cblxuICBwdWJsaWMgc3RhdGljICdnZXRJbnRlcmZhY2VzMCgpW0xqYXZhL2xhbmcvQ2xhc3M7Jyh0aHJlYWQ6IEpWTVRocmVhZCwgamF2YVRoaXM6IEpWTVR5cGVzLmphdmFfbGFuZ19DbGFzcyk6IEpWTVR5cGVzLkpWTUFycmF5PEpWTVR5cGVzLmphdmFfbGFuZ19DbGFzcz4ge1xuICAgIHJldHVybiB1dGlsLm5ld0FycmF5RnJvbURhdGE8SlZNVHlwZXMuamF2YV9sYW5nX0NsYXNzPih0aHJlYWQsIHRocmVhZC5nZXRCc0NsKCksICdbTGphdmEvbGFuZy9DbGFzczsnLCBqYXZhVGhpcy4kY2xzLmdldEludGVyZmFjZXMoKS5tYXAoKGlmYWNlKSA9PiBpZmFjZS5nZXRDbGFzc09iamVjdCh0aHJlYWQpKSk7XG4gIH1cblxuICBwdWJsaWMgc3RhdGljICdnZXRDb21wb25lbnRUeXBlKClMamF2YS9sYW5nL0NsYXNzOycodGhyZWFkOiBKVk1UaHJlYWQsIGphdmFUaGlzOiBKVk1UeXBlcy5qYXZhX2xhbmdfQ2xhc3MpOiBKVk1UeXBlcy5qYXZhX2xhbmdfQ2xhc3Mge1xuICAgIGlmICghKGphdmFUaGlzLiRjbHMgaW5zdGFuY2VvZiBBcnJheUNsYXNzRGF0YSkpIHtcbiAgICAgIHJldHVybiBudWxsO1xuICAgIH1cbiAgICAvLyBBcyB0aGlzIGFycmF5IHR5cGUgaXMgbG9hZGVkLCB0aGUgY29tcG9uZW50IHR5cGUgaXMgZ3VhcmFudGVlZFxuICAgIC8vIHRvIGJlIGxvYWRlZCBhcyB3ZWxsLiBObyBuZWVkIGZvciBhc3luY2hyb25pY2l0eS5cbiAgICByZXR1cm4gKDxBcnJheUNsYXNzRGF0YTxhbnk+PiBqYXZhVGhpcy4kY2xzKS5nZXRDb21wb25lbnRDbGFzcygpLmdldENsYXNzT2JqZWN0KHRocmVhZCk7XG4gIH1cblxuICBwdWJsaWMgc3RhdGljICdnZXRNb2RpZmllcnMoKUknKHRocmVhZDogSlZNVGhyZWFkLCBqYXZhVGhpczogSlZNVHlwZXMuamF2YV9sYW5nX0NsYXNzKTogbnVtYmVyIHtcbiAgICByZXR1cm4gamF2YVRoaXMuJGNscy5hY2Nlc3NGbGFncy5nZXRSYXdCeXRlKCk7XG4gIH1cblxuICBwdWJsaWMgc3RhdGljICdnZXRTaWduZXJzKClbTGphdmEvbGFuZy9PYmplY3Q7Jyh0aHJlYWQ6IEpWTVRocmVhZCwgamF2YVRoaXM6IEpWTVR5cGVzLmphdmFfbGFuZ19DbGFzcyk6IEpWTVR5cGVzLkpWTUFycmF5PEpWTVR5cGVzLmphdmFfbGFuZ19PYmplY3Q+IHtcbiAgICB0aHJlYWQudGhyb3dOZXdFeGNlcHRpb24oJ0xqYXZhL2xhbmcvVW5zYXRpc2ZpZWRMaW5rRXJyb3I7JywgJ05hdGl2ZSBtZXRob2Qgbm90IGltcGxlbWVudGVkLicpO1xuICAgIC8vIFNhdGlzZnkgVHlwZVNjcmlwdCByZXR1cm4gdHlwZS5cbiAgICByZXR1cm4gbnVsbDtcbiAgfVxuXG4gIHB1YmxpYyBzdGF0aWMgJ3NldFNpZ25lcnMoW0xqYXZhL2xhbmcvT2JqZWN0OylWJyh0aHJlYWQ6IEpWTVRocmVhZCwgamF2YVRoaXM6IEpWTVR5cGVzLmphdmFfbGFuZ19DbGFzcywgYXJnMDogSlZNVHlwZXMuSlZNQXJyYXk8SlZNVHlwZXMuamF2YV9sYW5nX09iamVjdD4pOiB2b2lkIHtcbiAgICB0aHJlYWQudGhyb3dOZXdFeGNlcHRpb24oJ0xqYXZhL2xhbmcvVW5zYXRpc2ZpZWRMaW5rRXJyb3I7JywgJ05hdGl2ZSBtZXRob2Qgbm90IGltcGxlbWVudGVkLicpO1xuICB9XG5cbiAgcHVibGljIHN0YXRpYyAnZ2V0RW5jbG9zaW5nTWV0aG9kMCgpW0xqYXZhL2xhbmcvT2JqZWN0OycodGhyZWFkOiBKVk1UaHJlYWQsIGphdmFUaGlzOiBKVk1UeXBlcy5qYXZhX2xhbmdfQ2xhc3MpOiBKVk1UeXBlcy5KVk1BcnJheTxKVk1UeXBlcy5qYXZhX2xhbmdfT2JqZWN0PiB7XG4gICAgdmFyIGVuY0Rlc2M6IEpWTVR5cGVzLmphdmFfbGFuZ19TdHJpbmcgPSBudWxsLFxuICAgICAgZW5jX25hbWU6IEpWTVR5cGVzLmphdmFfbGFuZ19TdHJpbmcgPSBudWxsLFxuICAgICAgYnNDbCA9IHRocmVhZC5nZXRCc0NsKCk7XG5cbiAgICBpZiAoamF2YVRoaXMuJGNscyBpbnN0YW5jZW9mIFJlZmVyZW5jZUNsYXNzRGF0YSkge1xuICAgICAgdmFyIGNscyA9IDxSZWZlcmVuY2VDbGFzc0RhdGE8SlZNVHlwZXMuamF2YV9sYW5nX09iamVjdD4+IGphdmFUaGlzLiRjbHMsXG4gICAgICAgIGVtOiBhdHRyaWJ1dGVzLkVuY2xvc2luZ01ldGhvZCA9IDxhdHRyaWJ1dGVzLkVuY2xvc2luZ01ldGhvZD4gY2xzLmdldEF0dHJpYnV0ZSgnRW5jbG9zaW5nTWV0aG9kJyk7XG4gICAgICBpZiAoZW0gPT0gbnVsbCkge1xuICAgICAgICByZXR1cm4gbnVsbDtcbiAgICAgIH1cblxuICAgICAgLy8gYXJyYXkgdy8gMyBlbGVtZW50czpcbiAgICAgIC8vIC0gdGhlIGltbWVkaWF0ZWx5IGVuY2xvc2luZyBjbGFzcyAoamF2YS9sYW5nL0NsYXNzKVxuICAgICAgLy8gLSB0aGUgaW1tZWRpYXRlbHkgZW5jbG9zaW5nIG1ldGhvZCBvciBjb25zdHJ1Y3RvcidzIG5hbWUgKGNhbiBiZSBudWxsKS4gKFN0cmluZylcbiAgICAgIC8vIC0gdGhlIGltbWVkaWF0ZWx5IGVuY2xvc2luZyBtZXRob2Qgb3IgY29uc3RydWN0b3IncyBkZXNjcmlwdG9yIChudWxsIGlmZiBuYW1lIGlzKS4gKFN0cmluZylcbiAgICAgIHZhciBydiA9IHV0aWwubmV3QXJyYXk8SlZNVHlwZXMuamF2YV9sYW5nX09iamVjdD4odGhyZWFkLCBic0NsLCAnW0xqYXZhL2xhbmcvT2JqZWN0OycsIDMpLFxuICAgICAgICBlbmNDbGFzc1JlZiA9IGVtLmVuY0NsYXNzO1xuICAgICAgaWYgKGVtLmVuY01ldGhvZCAhPSBudWxsKSB7XG4gICAgICAgIHJ2LmFycmF5WzFdID0gdXRpbC5pbml0U3RyaW5nKGJzQ2wsIGVtLmVuY01ldGhvZC5uYW1lKTtcbiAgICAgICAgcnYuYXJyYXlbMl0gPSB1dGlsLmluaXRTdHJpbmcoYnNDbCwgZW0uZW5jTWV0aG9kLmRlc2NyaXB0b3IpO1xuICAgICAgfVxuXG4gICAgICBpZiAoZW5jQ2xhc3NSZWYuaXNSZXNvbHZlZCgpKSB7XG4gICAgICAgIHJ2LmFycmF5WzBdID0gZW5jQ2xhc3NSZWYuY2xzLmdldENsYXNzT2JqZWN0KHRocmVhZCk7XG4gICAgICAgIHJldHVybiBydjtcbiAgICAgIH0gZWxzZSB7XG4gICAgICAgIHRocmVhZC5zZXRTdGF0dXMoVGhyZWFkU3RhdHVzLkFTWU5DX1dBSVRJTkcpO1xuICAgICAgICBlbmNDbGFzc1JlZi5yZXNvbHZlKHRocmVhZCwgY2xzLmdldExvYWRlcigpLCBjbHMsIChzdGF0dXM6IGJvb2xlYW4pID0+IHtcbiAgICAgICAgICBpZiAoc3RhdHVzKSB7XG4gICAgICAgICAgICBydi5hcnJheVswXSA9IGVuY0NsYXNzUmVmLmNscy5nZXRDbGFzc09iamVjdCh0aHJlYWQpO1xuICAgICAgICAgICAgdGhyZWFkLmFzeW5jUmV0dXJuKHJ2KTtcbiAgICAgICAgICB9XG4gICAgICAgIH0pO1xuICAgICAgfVxuICAgIH1cbiAgICByZXR1cm4gbnVsbDtcbiAgfVxuXG4gIHB1YmxpYyBzdGF0aWMgJ2dldERlY2xhcmluZ0NsYXNzMCgpTGphdmEvbGFuZy9DbGFzczsnKHRocmVhZDogSlZNVGhyZWFkLCBqYXZhVGhpczogSlZNVHlwZXMuamF2YV9sYW5nX0NsYXNzKTogSlZNVHlwZXMuamF2YV9sYW5nX0NsYXNzIHtcbiAgICB2YXIgZGVjbGFyaW5nTmFtZTogQ29uc3RhbnRQb29sLkNsYXNzUmVmZXJlbmNlLCBlbnRyeTogYXR0cmlidXRlcy5JSW5uZXJDbGFzc0luZm8sXG4gICAgICBuYW1lOiBzdHJpbmcsIGk6IG51bWJlciwgbGVuOiBudW1iZXI7XG4gICAgaWYgKGphdmFUaGlzLiRjbHMgaW5zdGFuY2VvZiBSZWZlcmVuY2VDbGFzc0RhdGEpIHtcbiAgICAgIHZhciBjbHMgPSA8UmVmZXJlbmNlQ2xhc3NEYXRhPEpWTVR5cGVzLmphdmFfbGFuZ19PYmplY3Q+PiBqYXZhVGhpcy4kY2xzLFxuICAgICAgICBpY2xzID0gPGF0dHJpYnV0ZXMuSW5uZXJDbGFzc2VzPiBjbHMuZ2V0QXR0cmlidXRlKCdJbm5lckNsYXNzZXMnKTtcbiAgICAgIGlmIChpY2xzID09IG51bGwpIHtcbiAgICAgICAgcmV0dXJuIG51bGw7XG4gICAgICB9XG4gICAgICB2YXIgbXlDbGFzcyA9IGNscy5nZXRJbnRlcm5hbE5hbWUoKSxcbiAgICAgICAgaW5uZXJDbGFzc0luZm8gPSBpY2xzLmNsYXNzZXM7XG4gICAgICBmb3IgKGkgPSAwLCBsZW4gPSBpbm5lckNsYXNzSW5mby5sZW5ndGg7IGkgPCBsZW47IGkrKykge1xuICAgICAgICBlbnRyeSA9IGlubmVyQ2xhc3NJbmZvW2ldO1xuICAgICAgICBpZiAoZW50cnkub3V0ZXJJbmZvSW5kZXggPD0gMCkge1xuICAgICAgICAgIGNvbnRpbnVlO1xuICAgICAgICB9XG4gICAgICAgIG5hbWUgPSAoPENvbnN0YW50UG9vbC5DbGFzc1JlZmVyZW5jZT4gY2xzLmNvbnN0YW50UG9vbC5nZXQoZW50cnkuaW5uZXJJbmZvSW5kZXgpKS5uYW1lO1xuICAgICAgICBpZiAobmFtZSAhPT0gbXlDbGFzcykge1xuICAgICAgICAgIGNvbnRpbnVlO1xuICAgICAgICB9XG4gICAgICAgIC8vIFhYWChqZXopOiB0aGlzIGFzc3VtZXMgdGhhdCB0aGUgZmlyc3QgZW5jbG9zaW5nIGVudHJ5IGlzIGFsc29cbiAgICAgICAgLy8gdGhlIGltbWVkaWF0ZSBlbmNsb3NpbmcgcGFyZW50LCBhbmQgSSdtIG5vdCAxMDAlIHN1cmUgdGhpcyBpc1xuICAgICAgICAvLyBndWFyYW50ZWVkIGJ5IHRoZSBzcGVjXG4gICAgICAgIGRlY2xhcmluZ05hbWUgPSAoPENvbnN0YW50UG9vbC5DbGFzc1JlZmVyZW5jZT4gY2xzLmNvbnN0YW50UG9vbC5nZXQoZW50cnkub3V0ZXJJbmZvSW5kZXgpKTtcbiAgICAgICAgaWYgKGRlY2xhcmluZ05hbWUuaXNSZXNvbHZlZCgpKSB7XG4gICAgICAgICAgcmV0dXJuIGRlY2xhcmluZ05hbWUuY2xzLmdldENsYXNzT2JqZWN0KHRocmVhZCk7XG4gICAgICAgIH0gZWxzZSB7XG4gICAgICAgICAgdGhyZWFkLnNldFN0YXR1cyhUaHJlYWRTdGF0dXMuQVNZTkNfV0FJVElORyk7XG4gICAgICAgICAgZGVjbGFyaW5nTmFtZS5yZXNvbHZlKHRocmVhZCwgY2xzLmdldExvYWRlcigpLCBjbHMsIChzdGF0dXM6IGJvb2xlYW4pID0+IHtcbiAgICAgICAgICAgIGlmIChzdGF0dXMpIHtcbiAgICAgICAgICAgICAgdGhyZWFkLmFzeW5jUmV0dXJuKGRlY2xhcmluZ05hbWUuY2xzLmdldENsYXNzT2JqZWN0KHRocmVhZCkpO1xuICAgICAgICAgICAgfVxuICAgICAgICAgIH0pO1xuICAgICAgICB9XG4gICAgICB9XG4gICAgfVxuICAgIHJldHVybiBudWxsO1xuICB9XG5cbiAgcHVibGljIHN0YXRpYyAnZ2V0UHJvdGVjdGlvbkRvbWFpbjAoKUxqYXZhL3NlY3VyaXR5L1Byb3RlY3Rpb25Eb21haW47Jyh0aHJlYWQ6IEpWTVRocmVhZCwgamF2YVRoaXM6IEpWTVR5cGVzLmphdmFfbGFuZ19DbGFzcyk6IEpWTVR5cGVzLmphdmFfc2VjdXJpdHlfUHJvdGVjdGlvbkRvbWFpbiB7XG4gICAgcmV0dXJuIGphdmFUaGlzLiRjbHMuZ2V0UHJvdGVjdGlvbkRvbWFpbigpO1xuICB9XG5cbiAgcHVibGljIHN0YXRpYyAnZ2V0UHJpbWl0aXZlQ2xhc3MoTGphdmEvbGFuZy9TdHJpbmc7KUxqYXZhL2xhbmcvQ2xhc3M7Jyh0aHJlYWQ6IEpWTVRocmVhZCwganZtU3RyOiBKVk1UeXBlcy5qYXZhX2xhbmdfU3RyaW5nKTogSlZNVHlwZXMuamF2YV9sYW5nX0NsYXNzIHtcbiAgICB2YXIgdHlwZV9kZXNjID0gdXRpbC50eXBlc3RyMmRlc2NyaXB0b3IoanZtU3RyLnRvU3RyaW5nKCkpLFxuICAgICAgcHJpbV9jbHMgPSB0aHJlYWQuZ2V0QnNDbCgpLmdldEluaXRpYWxpemVkQ2xhc3ModGhyZWFkLCB0eXBlX2Rlc2MpO1xuICAgIHJldHVybiBwcmltX2Nscy5nZXRDbGFzc09iamVjdCh0aHJlYWQpO1xuICB9XG5cbiAgcHVibGljIHN0YXRpYyAnZ2V0R2VuZXJpY1NpZ25hdHVyZTAoKUxqYXZhL2xhbmcvU3RyaW5nOycodGhyZWFkOiBKVk1UaHJlYWQsIGphdmFUaGlzOiBKVk1UeXBlcy5qYXZhX2xhbmdfQ2xhc3MpOiBKVk1UeXBlcy5qYXZhX2xhbmdfU3RyaW5nIHtcbiAgICB2YXIgY2xzID0gamF2YVRoaXMuJGNscztcbiAgICAvLyBUT0RPOiBXaGF0IGlmIGl0IGlzIGEgcHJpbWl0aXZlIHR5cGU/IFdoYXQgZG8gSSByZXR1cm4/XG4gICAgaWYgKCF1dGlsLmlzX3ByaW1pdGl2ZV90eXBlKGNscy5nZXRJbnRlcm5hbE5hbWUoKSkpIHtcbiAgICAgIHZhciBzaWdBdHRyID0gPGF0dHJpYnV0ZXMuU2lnbmF0dXJlPiAoPFJlZmVyZW5jZUNsYXNzRGF0YTxKVk1UeXBlcy5qYXZhX2xhbmdfT2JqZWN0Pj4gY2xzKS5nZXRBdHRyaWJ1dGUoJ1NpZ25hdHVyZScpO1xuICAgICAgaWYgKHNpZ0F0dHIgIT0gbnVsbCAmJiBzaWdBdHRyLnNpZyAhPSBudWxsKSB7XG4gICAgICAgIHJldHVybiB1dGlsLmluaXRTdHJpbmcodGhyZWFkLmdldEJzQ2woKSwgc2lnQXR0ci5zaWcpO1xuICAgICAgfVxuICAgIH1cbiAgICByZXR1cm4gbnVsbDtcbiAgfVxuXG4gIC8qKlxuICAgKiBSZXR1cm5zIFJ1bnRpbWVWaXNpYmxlQW5ub3RhdGlvbnMgZGVmaW5lZCBvbiB0aGUgY2xhc3MuXG4gICAqL1xuICBwdWJsaWMgc3RhdGljICdnZXRSYXdBbm5vdGF0aW9ucygpW0InKHRocmVhZDogSlZNVGhyZWFkLCBqYXZhVGhpczogSlZNVHlwZXMuamF2YV9sYW5nX0NsYXNzKTogSlZNVHlwZXMuSlZNQXJyYXk8bnVtYmVyPiB7XG4gICAgdmFyIGNscyA9IDxSZWZlcmVuY2VDbGFzc0RhdGE8SlZNVHlwZXMuamF2YV9sYW5nX0NsYXNzPj4gamF2YVRoaXMuJGNscyxcbiAgICAgIGFubm90YXRpb25zVmlzaWJsZSA9IDxhdHRyaWJ1dGVzLlJ1bnRpbWVWaXNpYmxlQW5ub3RhdGlvbnM+IGNscy5nZXRBdHRyaWJ1dGUoJ1J1bnRpbWVWaXNpYmxlQW5ub3RhdGlvbnMnKSxcbiAgICAgIG1ldGhvZHM6IE1ldGhvZFtdLCBpOiBudW1iZXIsIG06IE1ldGhvZDtcblxuICAgIGlmIChhbm5vdGF0aW9uc1Zpc2libGUgIT09IG51bGwpIHtcbiAgICAgIC8vIFRPRE86IFVzZSBhIHR5cGVkIGFycmF5P1xuICAgICAgdmFyIGJ5dGVzID0gYW5ub3RhdGlvbnNWaXNpYmxlLnJhd0J5dGVzLCBkYXRhOiBudW1iZXJbXSA9IG5ldyBBcnJheShieXRlcy5sZW5ndGgpO1xuICAgICAgZm9yICh2YXIgaSA9IDA7IGkgPCBieXRlcy5sZW5ndGg7IGkrKykge1xuICAgICAgICBkYXRhW2ldID0gYnl0ZXMucmVhZEludDgoaSk7XG4gICAgICB9XG4gICAgICByZXR1cm4gdXRpbC5uZXdBcnJheUZyb21EYXRhPG51bWJlcj4odGhyZWFkLCB0aHJlYWQuZ2V0QnNDbCgpLCAnW0InLCBkYXRhKTtcbiAgICB9XG4gICAgcmV0dXJuIG51bGw7XG4gIH1cblxuICBwdWJsaWMgc3RhdGljICdnZXRDb25zdGFudFBvb2woKUxzdW4vcmVmbGVjdC9Db25zdGFudFBvb2w7Jyh0aHJlYWQ6IEpWTVRocmVhZCwgamF2YVRoaXM6IEpWTVR5cGVzLmphdmFfbGFuZ19DbGFzcyk6IEpWTVR5cGVzLnN1bl9yZWZsZWN0X0NvbnN0YW50UG9vbCB7XG4gICAgdmFyIGNscyA9IDxSZWZlcmVuY2VDbGFzc0RhdGE8SlZNVHlwZXMuamF2YV9sYW5nX09iamVjdD4+IGphdmFUaGlzLiRjbHMsXG4gICAgICBjcE9iaiA9IHV0aWwubmV3T2JqZWN0PEpWTVR5cGVzLnN1bl9yZWZsZWN0X0NvbnN0YW50UG9vbD4odGhyZWFkLCB0aHJlYWQuZ2V0QnNDbCgpLCAnTHN1bi9yZWZsZWN0L0NvbnN0YW50UG9vbDsnKTtcbiAgICAvLyBAdG9kbyBNYWtlIHRoaXMgYSBwcm9wZXIgSmF2YU9iamVjdC4gSSBkb24ndCB0aGluayB0aGUgSkNMIHVzZXMgaXQgYXMgc3VjaCxcbiAgICAvLyBidXQgcmlnaHQgbm93IHRoaXMgZnVuY3Rpb24gZmFpbHMgYW55IGF1dG9tYXRlZCBzYW5pdHkgY2hlY2tzIG9uIHJldHVybiB2YWx1ZXMuXG4gICAgY3BPYmpbJ3N1bi9yZWZsZWN0L0NvbnN0YW50UG9vbC9jb25zdGFudFBvb2xPb3AnXSA9IDxhbnk+IGNscy5jb25zdGFudFBvb2w7XG4gICAgcmV0dXJuIGNwT2JqO1xuICB9XG5cbiAgcHVibGljIHN0YXRpYyAnZ2V0RGVjbGFyZWRGaWVsZHMwKFopW0xqYXZhL2xhbmcvcmVmbGVjdC9GaWVsZDsnKHRocmVhZDogSlZNVGhyZWFkLCBqYXZhVGhpczogSlZNVHlwZXMuamF2YV9sYW5nX0NsYXNzLCBwdWJsaWNPbmx5OiBudW1iZXIpOiB2b2lkIHtcbiAgICB2YXIgZmllbGRzID0gamF2YVRoaXMuJGNscy5nZXRGaWVsZHMoKTtcbiAgICBpZiAocHVibGljT25seSkge1xuICAgICAgZmllbGRzID0gZmllbGRzLmZpbHRlcigoZikgPT4gZi5hY2Nlc3NGbGFncy5pc1B1YmxpYygpKTtcbiAgICB9XG4gICAgdmFyIHJ2ID0gdXRpbC5uZXdBcnJheTxKVk1UeXBlcy5qYXZhX2xhbmdfcmVmbGVjdF9GaWVsZD4odGhyZWFkLCB0aHJlYWQuZ2V0QnNDbCgpLCAnW0xqYXZhL2xhbmcvcmVmbGVjdC9GaWVsZDsnLCBmaWVsZHMubGVuZ3RoKSxcbiAgICAgIGk6IG51bWJlciA9IDA7XG4gICAgdGhyZWFkLnNldFN0YXR1cyhUaHJlYWRTdGF0dXMuQVNZTkNfV0FJVElORyk7XG4gICAgdXRpbC5hc3luY0ZvckVhY2g8RmllbGQ+KGZpZWxkcyxcbiAgICAgIChmLCBuZXh0SXRlbSkgPT4ge1xuICAgICAgICBmLnJlZmxlY3Rvcih0aHJlYWQsIChmaWVsZE9iajogSlZNVHlwZXMuamF2YV9sYW5nX3JlZmxlY3RfRmllbGQpID0+IHtcbiAgICAgICAgICBpZiAoZmllbGRPYmogIT09IG51bGwpIHtcbiAgICAgICAgICAgIHJ2LmFycmF5W2krK10gPSBmaWVsZE9iajtcbiAgICAgICAgICAgIG5leHRJdGVtKCk7XG4gICAgICAgICAgfVxuICAgICAgICB9KTtcbiAgICAgIH0sICgpID0+IHtcbiAgICAgICAgdGhyZWFkLmFzeW5jUmV0dXJuKHJ2KTtcbiAgICAgIH0pO1xuICB9XG5cbiAgcHVibGljIHN0YXRpYyAnZ2V0RGVjbGFyZWRNZXRob2RzMChaKVtMamF2YS9sYW5nL3JlZmxlY3QvTWV0aG9kOycodGhyZWFkOiBKVk1UaHJlYWQsIGphdmFUaGlzOiBKVk1UeXBlcy5qYXZhX2xhbmdfQ2xhc3MsIHB1YmxpY09ubHk6IG51bWJlcik6IHZvaWQge1xuICAgIHZhciBtZXRob2RzOiBNZXRob2RbXSA9IGphdmFUaGlzLiRjbHMuZ2V0TWV0aG9kcygpLmZpbHRlcigobTogTWV0aG9kKSA9PiB7XG4gICAgICByZXR1cm4gbS5uYW1lWzBdICE9PSAnPCcgJiYgKG0uYWNjZXNzRmxhZ3MuaXNQdWJsaWMoKSB8fCAhcHVibGljT25seSk7XG4gICAgfSksIHJ2ID0gdXRpbC5uZXdBcnJheTxKVk1UeXBlcy5qYXZhX2xhbmdfcmVmbGVjdF9NZXRob2Q+KHRocmVhZCwgdGhyZWFkLmdldEJzQ2woKSwgJ1tMamF2YS9sYW5nL3JlZmxlY3QvTWV0aG9kOycsIG1ldGhvZHMubGVuZ3RoKSxcbiAgICAgIGkgPSAwO1xuICAgIHRocmVhZC5zZXRTdGF0dXMoVGhyZWFkU3RhdHVzLkFTWU5DX1dBSVRJTkcpO1xuICAgIHV0aWwuYXN5bmNGb3JFYWNoPE1ldGhvZD4obWV0aG9kcyxcbiAgICAgIChtLCBuZXh0SXRlbSkgPT4ge1xuICAgICAgICBtLnJlZmxlY3Rvcih0aHJlYWQsIChtZXRob2RPYmopID0+IHtcbiAgICAgICAgICBpZiAobWV0aG9kT2JqICE9PSBudWxsKSB7XG4gICAgICAgICAgICBydi5hcnJheVtpKytdID0gPEpWTVR5cGVzLmphdmFfbGFuZ19yZWZsZWN0X01ldGhvZD4gbWV0aG9kT2JqO1xuICAgICAgICAgICAgbmV4dEl0ZW0oKVxuICAgICAgICAgIH1cbiAgICAgICAgfSk7XG4gICAgICB9LCAoKSA9PiB7XG4gICAgICAgIHRocmVhZC5hc3luY1JldHVybihydik7XG4gICAgICB9KTtcbiAgfVxuXG4gIHB1YmxpYyBzdGF0aWMgJ2dldERlY2xhcmVkQ29uc3RydWN0b3JzMChaKVtMamF2YS9sYW5nL3JlZmxlY3QvQ29uc3RydWN0b3I7Jyh0aHJlYWQ6IEpWTVRocmVhZCwgamF2YVRoaXM6IEpWTVR5cGVzLmphdmFfbGFuZ19DbGFzcywgcHVibGljT25seTogbnVtYmVyKTogdm9pZCB7XG4gICAgdmFyIG1ldGhvZHM6IE1ldGhvZFtdID0gamF2YVRoaXMuJGNscy5nZXRNZXRob2RzKCkuZmlsdGVyKChtOiBNZXRob2QpID0+IHtcbiAgICAgIHJldHVybiBtLm5hbWUgPT09ICc8aW5pdD4nICYmICghcHVibGljT25seSB8fCBtLmFjY2Vzc0ZsYWdzLmlzUHVibGljKCkpO1xuICAgIH0pLCBydiA9IHV0aWwubmV3QXJyYXk8SlZNVHlwZXMuamF2YV9sYW5nX3JlZmxlY3RfQ29uc3RydWN0b3I+KHRocmVhZCwgdGhyZWFkLmdldEJzQ2woKSwgJ1tMamF2YS9sYW5nL3JlZmxlY3QvQ29uc3RydWN0b3I7JywgbWV0aG9kcy5sZW5ndGgpLFxuICAgICAgaSA9IDA7XG4gICAgdGhyZWFkLnNldFN0YXR1cyhUaHJlYWRTdGF0dXMuQVNZTkNfV0FJVElORyk7XG4gICAgdXRpbC5hc3luY0ZvckVhY2gobWV0aG9kcyxcbiAgICAgIChtOiBNZXRob2QsIG5leHRJdGVtOiAoZXJyPzogYW55KSA9PiB2b2lkKSA9PiB7XG4gICAgICAgIG0ucmVmbGVjdG9yKHRocmVhZCwgKG1ldGhvZE9iaikgPT4ge1xuICAgICAgICAgIGlmIChtZXRob2RPYmogIT09IG51bGwpIHtcbiAgICAgICAgICAgIHJ2LmFycmF5W2krK10gPSA8SlZNVHlwZXMuamF2YV9sYW5nX3JlZmxlY3RfQ29uc3RydWN0b3I+IG1ldGhvZE9iajtcbiAgICAgICAgICAgIG5leHRJdGVtKClcbiAgICAgICAgICB9XG4gICAgICAgIH0pO1xuICAgICAgfSwgKCkgPT4ge1xuICAgICAgICB0aHJlYWQuYXN5bmNSZXR1cm4ocnYpO1xuICAgICAgfSk7XG4gIH1cblxuICBwdWJsaWMgc3RhdGljICdnZXREZWNsYXJlZENsYXNzZXMwKClbTGphdmEvbGFuZy9DbGFzczsnKHRocmVhZDogSlZNVGhyZWFkLCBqYXZhVGhpczogSlZNVHlwZXMuamF2YV9sYW5nX0NsYXNzKTogSlZNVHlwZXMuSlZNQXJyYXk8SlZNVHlwZXMuamF2YV9sYW5nX0NsYXNzPiB7XG4gICAgdmFyIHJldCA9IHV0aWwubmV3QXJyYXk8SlZNVHlwZXMuamF2YV9sYW5nX0NsYXNzPih0aHJlYWQsIHRocmVhZC5nZXRCc0NsKCksICdbTGphdmEvbGFuZy9DbGFzczsnLCAwKSxcbiAgICAgIGNscyA9IGphdmFUaGlzLiRjbHM7XG4gICAgaWYgKGNscyBpbnN0YW5jZW9mIFJlZmVyZW5jZUNsYXNzRGF0YSkge1xuICAgICAgdmFyIG15Q2xhc3MgPSBjbHMuZ2V0SW50ZXJuYWxOYW1lKCksXG4gICAgICAgIGljbHNlcyA9IDxhdHRyaWJ1dGVzLklubmVyQ2xhc3Nlc1tdPiBjbHMuZ2V0QXR0cmlidXRlcygnSW5uZXJDbGFzc2VzJyksXG4gICAgICAgIGZsYXROYW1lczogQ29uc3RhbnRQb29sLkNsYXNzUmVmZXJlbmNlW10gPSBbXTtcbiAgICAgIGlmIChpY2xzZXMubGVuZ3RoID09PSAwKSB7XG4gICAgICAgIHJldHVybiByZXQ7XG4gICAgICB9XG4gICAgICBmb3IgKHZhciBpID0gMDsgaSA8IGljbHNlcy5sZW5ndGg7IGkrKykge1xuICAgICAgICBmbGF0TmFtZXMgPSBmbGF0TmFtZXMuY29uY2F0KGljbHNlc1tpXS5jbGFzc2VzLmZpbHRlcigoYzogYXR0cmlidXRlcy5JSW5uZXJDbGFzc0luZm8pID0+XG4gICAgICAgICAgLy8gc2VsZWN0IGlubmVyIGNsYXNzZXMgd2hlcmUgdGhlIGVuY2xvc2luZyBjbGFzcyBpcyBteV9jbGFzc1xuICAgICAgICAgIGMub3V0ZXJJbmZvSW5kZXggPiAwICYmICg8Q29uc3RhbnRQb29sLkNsYXNzUmVmZXJlbmNlPiBjbHMuY29uc3RhbnRQb29sLmdldChjLm91dGVySW5mb0luZGV4KSkubmFtZSA9PT0gbXlDbGFzcylcbiAgICAgICAgICAubWFwKChjOiBhdHRyaWJ1dGVzLklJbm5lckNsYXNzSW5mbykgPT4gKDxDb25zdGFudFBvb2wuQ2xhc3NSZWZlcmVuY2U+IGNscy5jb25zdGFudFBvb2wuZ2V0KGMuaW5uZXJJbmZvSW5kZXgpKSkpO1xuICAgICAgfVxuICAgICAgdGhyZWFkLnNldFN0YXR1cyhUaHJlYWRTdGF0dXMuQVNZTkNfV0FJVElORyk7XG4gICAgICB1dGlsLmFzeW5jRm9yRWFjaChmbGF0TmFtZXMsXG4gICAgICAgIChjbHNSZWY6IENvbnN0YW50UG9vbC5DbGFzc1JlZmVyZW5jZSwgbmV4dEl0ZW06ICgpID0+IHZvaWQpID0+IHtcbiAgICAgICAgICBpZiAoY2xzUmVmLmlzUmVzb2x2ZWQoKSkge1xuICAgICAgICAgICAgcmV0LmFycmF5LnB1c2goY2xzUmVmLmNscy5nZXRDbGFzc09iamVjdCh0aHJlYWQpKTtcbiAgICAgICAgICAgIG5leHRJdGVtKCk7XG4gICAgICAgICAgfSBlbHNlIHtcbiAgICAgICAgICAgIGNsc1JlZi5yZXNvbHZlKHRocmVhZCwgY2xzLmdldExvYWRlcigpLCA8UmVmZXJlbmNlQ2xhc3NEYXRhPEpWTVR5cGVzLmphdmFfbGFuZ19PYmplY3Q+PiBqYXZhVGhpcy5nZXRDbGFzcygpLCAoc3RhdHVzKSA9PiB7XG4gICAgICAgICAgICAgIGlmIChzdGF0dXMpIHtcbiAgICAgICAgICAgICAgICByZXQuYXJyYXkucHVzaChjbHNSZWYuY2xzLmdldENsYXNzT2JqZWN0KHRocmVhZCkpO1xuICAgICAgICAgICAgICAgIG5leHRJdGVtKCk7XG4gICAgICAgICAgICAgIH1cbiAgICAgICAgICAgIH0pO1xuICAgICAgICAgIH1cbiAgICAgICAgfSwgKCkgPT4gdGhyZWFkLmFzeW5jUmV0dXJuKHJldCkpO1xuICAgIH0gZWxzZSB7XG4gICAgICByZXR1cm4gcmV0O1xuICAgIH1cbiAgfVxuXG4gIHB1YmxpYyBzdGF0aWMgJ2Rlc2lyZWRBc3NlcnRpb25TdGF0dXMwKExqYXZhL2xhbmcvQ2xhc3M7KVonKHRocmVhZDogSlZNVGhyZWFkLCBhcmcwOiBKVk1UeXBlcy5qYXZhX2xhbmdfQ2xhc3MpOiBib29sZWFuIHtcbiAgICBpZiAoYXJnMC4kY2xzLmdldExvYWRlcigpLmdldExvYWRlck9iamVjdCgpID09PSBudWxsKSB7XG4gICAgICByZXR1cm4gdGhyZWFkLmdldEpWTSgpLmFyZVN5c3RlbUFzc2VydGlvbnNFbmFibGVkKCk7XG4gICAgfVxuICAgIHJldHVybiBmYWxzZTtcbiAgfVxuXG59XG5cbmNsYXNzIGphdmFfbGFuZ19DbGFzc0xvYWRlciROYXRpdmVMaWJyYXJ5IHtcblxuICBwdWJsaWMgc3RhdGljICdsb2FkKExqYXZhL2xhbmcvU3RyaW5nO1opVicodGhyZWFkOiBKVk1UaHJlYWQsIGphdmFUaGlzOiBKVk1UeXBlcy5qYXZhX2xhbmdfQ2xhc3NMb2FkZXIkTmF0aXZlTGlicmFyeSwgbmFtZTogSlZNVHlwZXMuamF2YV9sYW5nX1N0cmluZywgaXNCdWlsdEluOiBudW1iZXIpOiB2b2lkIHtcbiAgICB0aHJlYWQudGhyb3dOZXdFeGNlcHRpb24oJ0xqYXZhL2xhbmcvVW5zYXRpc2ZpZWRMaW5rRXJyb3I7JywgJ05hdGl2ZSBtZXRob2Qgbm90IGltcGxlbWVudGVkLicpO1xuICB9XG5cbiAgcHVibGljIHN0YXRpYyAnZmluZChMamF2YS9sYW5nL1N0cmluZzspSicodGhyZWFkOiBKVk1UaHJlYWQsIGphdmFUaGlzOiBKVk1UeXBlcy5qYXZhX2xhbmdfQ2xhc3NMb2FkZXIkTmF0aXZlTGlicmFyeSwgYXJnMDogSlZNVHlwZXMuamF2YV9sYW5nX1N0cmluZyk6IExvbmcge1xuICAgIHRocmVhZC50aHJvd05ld0V4Y2VwdGlvbignTGphdmEvbGFuZy9VbnNhdGlzZmllZExpbmtFcnJvcjsnLCAnTmF0aXZlIG1ldGhvZCBub3QgaW1wbGVtZW50ZWQuJyk7XG4gICAgLy8gU2F0aXNmeSBUeXBlU2NyaXB0IHJldHVybiB0eXBlLlxuICAgIHJldHVybiBudWxsO1xuICB9XG5cbiAgcHVibGljIHN0YXRpYyAndW5sb2FkKExqYXZhL2xhbmcvU3RyaW5nO1opVicodGhyZWFkOiBKVk1UaHJlYWQsIGphdmFUaGlzOiBKVk1UeXBlcy5qYXZhX2xhbmdfQ2xhc3NMb2FkZXIkTmF0aXZlTGlicmFyeSwgbmFtZTogSlZNVHlwZXMuamF2YV9sYW5nX1N0cmluZyk6IHZvaWQge1xuICAgIHRocmVhZC50aHJvd05ld0V4Y2VwdGlvbignTGphdmEvbGFuZy9VbnNhdGlzZmllZExpbmtFcnJvcjsnLCAnTmF0aXZlIG1ldGhvZCBub3QgaW1wbGVtZW50ZWQuJyk7XG4gIH1cblxufVxuXG4vLyBGdW4gTm90ZTogVGhlIGJvb3RzdHJhcCBjbGFzc2xvYWRlciBvYmplY3QgaXMgcmVwcmVzZW50ZWQgYnkgbnVsbC5cbmNsYXNzIGphdmFfbGFuZ19DbGFzc0xvYWRlciB7XG5cbiAgcHVibGljIHN0YXRpYyAnZGVmaW5lQ2xhc3MwKExqYXZhL2xhbmcvU3RyaW5nO1tCSUlMamF2YS9zZWN1cml0eS9Qcm90ZWN0aW9uRG9tYWluOylMamF2YS9sYW5nL0NsYXNzOycodGhyZWFkOiBKVk1UaHJlYWQsIGphdmFUaGlzOiBKVk1UeXBlcy5qYXZhX2xhbmdfQ2xhc3NMb2FkZXIsIGFyZzA6IEpWTVR5cGVzLmphdmFfbGFuZ19TdHJpbmcsIGFyZzE6IEpWTVR5cGVzLkpWTUFycmF5PG51bWJlcj4sIGFyZzI6IG51bWJlciwgYXJnMzogbnVtYmVyLCBhcmc0OiBKVk1UeXBlcy5qYXZhX3NlY3VyaXR5X1Byb3RlY3Rpb25Eb21haW4pOiBKVk1UeXBlcy5qYXZhX2xhbmdfQ2xhc3Mge1xuICAgIHRocmVhZC50aHJvd05ld0V4Y2VwdGlvbignTGphdmEvbGFuZy9VbnNhdGlzZmllZExpbmtFcnJvcjsnLCAnTmF0aXZlIG1ldGhvZCBub3QgaW1wbGVtZW50ZWQuJyk7XG4gICAgLy8gU2F0aXNmeSBUeXBlU2NyaXB0IHJldHVybiB0eXBlLlxuICAgIHJldHVybiBudWxsO1xuICB9XG5cbiAgcHVibGljIHN0YXRpYyAnZGVmaW5lQ2xhc3MxKExqYXZhL2xhbmcvU3RyaW5nO1tCSUlMamF2YS9zZWN1cml0eS9Qcm90ZWN0aW9uRG9tYWluO0xqYXZhL2xhbmcvU3RyaW5nOylMamF2YS9sYW5nL0NsYXNzOycodGhyZWFkOiBKVk1UaHJlYWQsIGphdmFUaGlzOiBKVk1UeXBlcy5qYXZhX2xhbmdfQ2xhc3NMb2FkZXIsIG5hbWU6IEpWTVR5cGVzLmphdmFfbGFuZ19TdHJpbmcsIGJ5dGVzOiBKVk1UeXBlcy5KVk1BcnJheTxudW1iZXI+LCBvZmZzZXQ6IG51bWJlciwgbGVuOiBudW1iZXIsIHBkOiBKVk1UeXBlcy5qYXZhX3NlY3VyaXR5X1Byb3RlY3Rpb25Eb21haW4sIHNvdXJjZTogSlZNVHlwZXMuamF2YV9sYW5nX1N0cmluZyk6IEpWTVR5cGVzLmphdmFfbGFuZ19DbGFzcyB7XG4gICAgdmFyIGxvYWRlciA9IHV0aWwuZ2V0TG9hZGVyKHRocmVhZCwgamF2YVRoaXMpLFxuICAgICAgdHlwZSA9IHV0aWwuaW50X2NsYXNzbmFtZShuYW1lLnRvU3RyaW5nKCkpLFxuICAgICAgY2xzID0gbG9hZGVyLmRlZmluZUNsYXNzKHRocmVhZCwgdHlwZSwgdXRpbC5ieXRlQXJyYXkyQnVmZmVyKGJ5dGVzLmFycmF5LCBvZmZzZXQsIGxlbiksIHBkKTtcbiAgICBpZiAoY2xzID09IG51bGwpIHtcbiAgICAgIHJldHVybiBudWxsO1xuICAgIH1cbiAgICAvLyBFbnN1cmUgdGhhdCB0aGlzIGNsYXNzIGlzIHJlc29sdmVkLlxuICAgIHRocmVhZC5zZXRTdGF0dXMoVGhyZWFkU3RhdHVzLkFTWU5DX1dBSVRJTkcpO1xuICAgIGNscy5yZXNvbHZlKHRocmVhZCwgKHN0YXR1cykgPT4ge1xuICAgICAgLy8gTlVMTCBzdGF0dXMgbWVhbnMgcmVzb2x1dGlvbiBmYWlsZWQuXG4gICAgICBpZiAoc3RhdHVzICE9PSBudWxsKSB7XG4gICAgICAgIHRocmVhZC5hc3luY1JldHVybihjbHMuZ2V0Q2xhc3NPYmplY3QodGhyZWFkKSk7XG4gICAgICB9XG4gICAgfSwgdHJ1ZSk7XG4gIH1cblxuICBwdWJsaWMgc3RhdGljICdkZWZpbmVDbGFzczIoTGphdmEvbGFuZy9TdHJpbmc7TGphdmEvbmlvL0J5dGVCdWZmZXI7SUlMamF2YS9zZWN1cml0eS9Qcm90ZWN0aW9uRG9tYWluO0xqYXZhL2xhbmcvU3RyaW5nOylMamF2YS9sYW5nL0NsYXNzOycodGhyZWFkOiBKVk1UaHJlYWQsIGphdmFUaGlzOiBKVk1UeXBlcy5qYXZhX2xhbmdfQ2xhc3NMb2FkZXIsIG5hbWU6IEpWTVR5cGVzLmphdmFfbGFuZ19TdHJpbmcsIGI6IEpWTVR5cGVzLmphdmFfbmlvX0J5dGVCdWZmZXIsIG9mZjogbnVtYmVyLCBsZW46IG51bWJlciwgcGQ6IEpWTVR5cGVzLmphdmFfc2VjdXJpdHlfUHJvdGVjdGlvbkRvbWFpbiwgc291cmNlOiBKVk1UeXBlcy5qYXZhX2xhbmdfU3RyaW5nKTogSlZNVHlwZXMuamF2YV9sYW5nX0NsYXNzIHtcbiAgICB0aHJlYWQudGhyb3dOZXdFeGNlcHRpb24oJ0xqYXZhL2xhbmcvVW5zYXRpc2ZpZWRMaW5rRXJyb3I7JywgJ05hdGl2ZSBtZXRob2Qgbm90IGltcGxlbWVudGVkLicpO1xuICAgIC8vIFNhdGlzZnkgVHlwZVNjcmlwdCByZXR1cm4gdHlwZS5cbiAgICByZXR1cm4gbnVsbDtcbiAgfVxuXG4gIHB1YmxpYyBzdGF0aWMgJ3Jlc29sdmVDbGFzczAoTGphdmEvbGFuZy9DbGFzczspVicodGhyZWFkOiBKVk1UaHJlYWQsIGphdmFUaGlzOiBKVk1UeXBlcy5qYXZhX2xhbmdfQ2xhc3NMb2FkZXIsIGNsczogSlZNVHlwZXMuamF2YV9sYW5nX0NsYXNzKTogdm9pZCB7XG4gICAgdmFyIGxvYWRlciA9IHV0aWwuZ2V0TG9hZGVyKHRocmVhZCwgamF2YVRoaXMpO1xuICAgIGlmIChjbHMuJGNscy5pc1Jlc29sdmVkKCkpIHtcbiAgICAgIHJldHVybjtcbiAgICB9XG4gICAgLy8gRW5zdXJlIHRoYXQgdGhpcyBjbGFzcyBpcyByZXNvbHZlZC5cbiAgICB0aHJlYWQuc2V0U3RhdHVzKFRocmVhZFN0YXR1cy5BU1lOQ19XQUlUSU5HKTtcbiAgICBjbHMuJGNscy5yZXNvbHZlKHRocmVhZCwgKGNkYXRhOiBDbGFzc0RhdGEpID0+IHtcbiAgICAgIGlmIChjZGF0YSAhPT0gbnVsbCkge1xuICAgICAgICB0aHJlYWQuYXN5bmNSZXR1cm4oKTtcbiAgICAgIH1cbiAgICAgIC8vIEVsc2U6IEFuIGV4Y2VwdGlvbiBvY2N1cnJlZC5cbiAgICB9LCB0cnVlKTtcbiAgfVxuXG4gIHB1YmxpYyBzdGF0aWMgJ2ZpbmRCb290c3RyYXBDbGFzcyhMamF2YS9sYW5nL1N0cmluZzspTGphdmEvbGFuZy9DbGFzczsnKHRocmVhZDogSlZNVGhyZWFkLCBqYXZhVGhpczogSlZNVHlwZXMuamF2YV9sYW5nX0NsYXNzTG9hZGVyLCBuYW1lOiBKVk1UeXBlcy5qYXZhX2xhbmdfU3RyaW5nKTogdm9pZCB7XG4gICAgdmFyIHR5cGUgPSB1dGlsLmludF9jbGFzc25hbWUobmFtZS50b1N0cmluZygpKTtcbiAgICAvLyBUaGlzIHJldHVybnMgbnVsbCBpbiBPcGVuSkRLNywgYnV0IGFjdHVhbGx5IGNhbiB0aHJvdyBhbiBleGNlcHRpb25cbiAgICAvLyBpbiBPcGVuSkRLNi5cbiAgICAvLyBUT0RPOiBGaXggY3VycmVudGx5IGluY29ycmVjdCBiZWhhdmlvciBmb3Igb3VyIEpESy4gU2hvdWxkIHJldHVybiBudWxsLCBub3QgdGhyb3cgYW4gZXhjZXB0aW9uIG9uIGZhaWx1cmUuXG4gICAgdGhyZWFkLnNldFN0YXR1cyhUaHJlYWRTdGF0dXMuQVNZTkNfV0FJVElORyk7XG4gICAgdGhyZWFkLmdldEJzQ2woKS5yZXNvbHZlQ2xhc3ModGhyZWFkLCB0eXBlLCAoY2xzKSA9PiB7XG4gICAgICBpZiAoY2xzICE9IG51bGwpIHtcbiAgICAgICAgdGhyZWFkLmFzeW5jUmV0dXJuKGNscy5nZXRDbGFzc09iamVjdCh0aHJlYWQpKTtcbiAgICAgIH1cbiAgICB9LCB0cnVlKTtcbiAgfVxuXG4gIHB1YmxpYyBzdGF0aWMgJ2ZpbmRMb2FkZWRDbGFzczAoTGphdmEvbGFuZy9TdHJpbmc7KUxqYXZhL2xhbmcvQ2xhc3M7Jyh0aHJlYWQ6IEpWTVRocmVhZCwgamF2YVRoaXM6IEpWTVR5cGVzLmphdmFfbGFuZ19DbGFzc0xvYWRlciwgbmFtZTogSlZNVHlwZXMuamF2YV9sYW5nX1N0cmluZyk6IEpWTVR5cGVzLmphdmFfbGFuZ19DbGFzcyB7XG4gICAgdmFyIGxvYWRlciA9IHV0aWwuZ2V0TG9hZGVyKHRocmVhZCwgamF2YVRoaXMpLFxuICAgICAgdHlwZSA9IHV0aWwuaW50X2NsYXNzbmFtZShuYW1lLnRvU3RyaW5nKCkpLFxuICAgICAgLy8gUmV0dXJuIEphdmFDbGFzc09iamVjdCBpZiBsb2FkZWQsIG9yIG51bGwgb3RoZXJ3aXNlLlxuICAgICAgY2xzID0gbG9hZGVyLmdldFJlc29sdmVkQ2xhc3ModHlwZSk7XG4gICAgaWYgKGNscyAhPSBudWxsKSB7XG4gICAgICByZXR1cm4gY2xzLmdldENsYXNzT2JqZWN0KHRocmVhZCk7XG4gICAgfSBlbHNlIHtcbiAgICAgIHJldHVybiBudWxsO1xuICAgIH1cbiAgfVxuXG4gIHB1YmxpYyBzdGF0aWMgJ3JldHJpZXZlRGlyZWN0aXZlcygpTGphdmEvbGFuZy9Bc3NlcnRpb25TdGF0dXNEaXJlY3RpdmVzOycodGhyZWFkOiBKVk1UaHJlYWQpOiB2b2lkIHtcbiAgICBsZXQganZtID0gdGhyZWFkLmdldEpWTSgpLCBic0NsID0gdGhyZWFkLmdldEJzQ2woKTtcbiAgICB0aHJlYWQuaW1wb3J0KCdMamF2YS9sYW5nL0Fzc2VydGlvblN0YXR1c0RpcmVjdGl2ZXM7JywgKGFzZDogdHlwZW9mIEpWTVR5cGVzLmphdmFfbGFuZ19Bc3NlcnRpb25TdGF0dXNEaXJlY3RpdmVzKSA9PiB7XG4gICAgICBsZXQgZGlyZWN0aXZlcyA9IG5ldyBhc2QoKTtcbiAgICAgIGxldCBlbmFibGVkQXNzZXJ0aW9ucyA9IGp2bS5nZXRFbmFibGVkQXNzZXJ0aW9ucygpO1xuICAgICAgLy8gVGhlIGNsYXNzZXMgZm9yIHdoaWNoIGFzc2VydGlvbnMgYXJlIHRvIGJlIGVuYWJsZWQgb3IgZGlzYWJsZWQuXG4gICAgICBsZXQgY2xhc3Nlczogc3RyaW5nW10gPSBbXSxcbiAgICAgICAgLy8gQSBwYXJhbGxlbCBhcnJheSB0byBjbGFzc2VzLCBpbmRpY2F0aW5nIHdoZXRoZXIgZWFjaCBjbGFzc1xuICAgICAgICAvLyBpcyB0byBoYXZlIGFzc2VydGlvbnMgZW5hYmxlZCBvciBkaXNhYmxlZC5cbiAgICAgICAgY2xhc3NFbmFibGVkOiBudW1iZXJbXSA9IFtdLFxuICAgICAgICAvLyBUaGUgcGFja2FnZS10cmVlcyBmb3Igd2hpY2ggYXNzZXJ0aW9ucyBhcmUgdG8gYmUgZW5hYmxlZCBvciBkaXNhYmxlZC5cbiAgICAgICAgcGFja2FnZXM6IHN0cmluZ1tdID0gW10sXG4gICAgICAgIC8vIEEgcGFyYWxsZWwgYXJyYXkgdG8gcGFja2FnZXMsIGluZGljYXRpbmcgd2hldGhlciBlYWNoXG4gICAgICAgIC8vIHBhY2thZ2UtdHJlZSBpcyB0byBoYXZlIGFzc2VydGlvbnMgZW5hYmxlZCBvciBkaXNhYmxlZC5cbiAgICAgICAgcGFja2FnZUVuYWJsZWQ6IG51bWJlcltdID0gW10sXG4gICAgICAgIGRlZmx0OiBib29sZWFuID0gZmFsc2UsXG4gICAgICAgIHByb2Nlc3NBc3NlcnRpb25zID0gKGVuYWJsZWQ6IG51bWJlcikgPT4ge1xuICAgICAgICAgIHJldHVybiAobmFtZTogc3RyaW5nKTogdm9pZCA9PiB7XG4gICAgICAgICAgICBsZXQgZG90SW5kZXggPSBuYW1lLmluZGV4T2YoJy4uLicpO1xuICAgICAgICAgICAgaWYgKGRvdEluZGV4ID09PSAtMSkge1xuICAgICAgICAgICAgICBjbGFzc2VzLnB1c2gobmFtZSk7XG4gICAgICAgICAgICAgIGNsYXNzRW5hYmxlZC5wdXNoKGVuYWJsZWQpO1xuICAgICAgICAgICAgfSBlbHNlIHtcbiAgICAgICAgICAgICAgcGFja2FnZXMucHVzaChuYW1lLnNsaWNlKDAsIGRvdEluZGV4KSk7XG4gICAgICAgICAgICAgIHBhY2thZ2VFbmFibGVkLnB1c2goZW5hYmxlZCk7XG4gICAgICAgICAgICB9XG4gICAgICAgICAgfTtcbiAgICAgICAgfTtcblxuICAgICAganZtLmdldERpc2FibGVkQXNzZXJ0aW9ucygpLmZvckVhY2gocHJvY2Vzc0Fzc2VydGlvbnMoMCkpO1xuXG4gICAgICBpZiAodHlwZW9mKGVuYWJsZWRBc3NlcnRpb25zKSA9PT0gJ2Jvb2xlYW4nKSB7XG4gICAgICAgIGRlZmx0ID0gPGJvb2xlYW4+IGVuYWJsZWRBc3NlcnRpb25zO1xuICAgICAgfSBlbHNlIGlmIChBcnJheS5pc0FycmF5KGVuYWJsZWRBc3NlcnRpb25zKSkge1xuICAgICAgICBlbmFibGVkQXNzZXJ0aW9ucy5mb3JFYWNoKHByb2Nlc3NBc3NlcnRpb25zKDEpKTtcbiAgICAgIH0gZWxzZSB7XG4gICAgICAgIHJldHVybiB0aHJlYWQudGhyb3dOZXdFeGNlcHRpb24oJ0xqYXZhL2xhbmcvSW50ZXJuYWxFcnJvcjsnLCBgRXhwZWN0ZWQgZW5hYmxlQXNzZXJ0aW9ucyBvcHRpb24gdG8gYmUgYSBib29sZWFuIG9yIGFuIGFycmF5IG9mIHN0cmluZ3MuYCk7XG4gICAgICB9XG5cbiAgICAgIGRpcmVjdGl2ZXNbJ2phdmEvbGFuZy9Bc3NlcnRpb25TdGF0dXNEaXJlY3RpdmVzL2NsYXNzZXMnXSA9IHV0aWwubmV3QXJyYXlGcm9tRGF0YTxKVk1UeXBlcy5qYXZhX2xhbmdfU3RyaW5nPih0aHJlYWQsIGJzQ2wsICdbTGphdmEvbGFuZy9TdHJpbmc7JywgY2xhc3Nlcy5tYXAoKGNscykgPT4gdXRpbC5pbml0U3RyaW5nKGJzQ2wsIGNscykpKTtcbiAgICAgIGRpcmVjdGl2ZXNbJ2phdmEvbGFuZy9Bc3NlcnRpb25TdGF0dXNEaXJlY3RpdmVzL2NsYXNzRW5hYmxlZCddID0gdXRpbC5uZXdBcnJheUZyb21EYXRhPG51bWJlcj4odGhyZWFkLCBic0NsLCAnW1onLCBjbGFzc0VuYWJsZWQpO1xuICAgICAgZGlyZWN0aXZlc1snamF2YS9sYW5nL0Fzc2VydGlvblN0YXR1c0RpcmVjdGl2ZXMvcGFja2FnZXMnXSA9IHV0aWwubmV3QXJyYXlGcm9tRGF0YTxKVk1UeXBlcy5qYXZhX2xhbmdfU3RyaW5nPih0aHJlYWQsIGJzQ2wsICdbTGphdmEvbGFuZy9TdHJpbmc7JywgcGFja2FnZXMubWFwKChwa2cpID0+IHV0aWwuaW5pdFN0cmluZyhic0NsLCBwa2cpKSk7XG4gICAgICBkaXJlY3RpdmVzWydqYXZhL2xhbmcvQXNzZXJ0aW9uU3RhdHVzRGlyZWN0aXZlcy9wYWNrYWdlRW5hYmxlZCddID0gdXRpbC5uZXdBcnJheUZyb21EYXRhPG51bWJlcj4odGhyZWFkLCBic0NsLCAnW1onLCBwYWNrYWdlRW5hYmxlZCk7XG4gICAgICBkaXJlY3RpdmVzWydqYXZhL2xhbmcvQXNzZXJ0aW9uU3RhdHVzRGlyZWN0aXZlcy9kZWZsdCddID0gKDxib29sZWFuPiBlbmFibGVkQXNzZXJ0aW9ucykgPyAxIDogMDtcblxuICAgICAgdGhyZWFkLmFzeW5jUmV0dXJuKGRpcmVjdGl2ZXMpO1xuICAgIH0pO1xuICB9XG5cbn1cblxuY2xhc3MgamF2YV9sYW5nX0NvbXBpbGVyIHtcblxuICBwdWJsaWMgc3RhdGljICdpbml0aWFsaXplKClWJyh0aHJlYWQ6IEpWTVRocmVhZCk6IHZvaWQge1xuICAgIC8vIE5PUC5cbiAgfVxuXG4gIHB1YmxpYyBzdGF0aWMgJ3JlZ2lzdGVyTmF0aXZlcygpVicodGhyZWFkOiBKVk1UaHJlYWQpOiB2b2lkIHtcbiAgICAvLyBOT1AuXG4gIH1cblxuICBwdWJsaWMgc3RhdGljICdjb21waWxlQ2xhc3MoTGphdmEvbGFuZy9DbGFzczspWicodGhyZWFkOiBKVk1UaHJlYWQsIGFyZzA6IEpWTVR5cGVzLmphdmFfbGFuZ19DbGFzcyk6IG51bWJlciB7XG4gICAgLy8gUmV0dXJuIGZhbHNlOiBObyBjb21waWxlciBhdmFpbGFibGUuXG4gICAgcmV0dXJuIDA7XG4gIH1cblxuICBwdWJsaWMgc3RhdGljICdjb21waWxlQ2xhc3NlcyhMamF2YS9sYW5nL1N0cmluZzspWicodGhyZWFkOiBKVk1UaHJlYWQsIGFyZzA6IEpWTVR5cGVzLmphdmFfbGFuZ19TdHJpbmcpOiBudW1iZXIge1xuICAgIC8vIFJldHVybiBmYWxzZTogTm8gY29tcGlsZXIgYXZhaWxhYmxlLlxuICAgIHJldHVybiAwO1xuICB9XG5cbiAgcHVibGljIHN0YXRpYyAnY29tbWFuZChMamF2YS9sYW5nL09iamVjdDspTGphdmEvbGFuZy9PYmplY3Q7Jyh0aHJlYWQ6IEpWTVRocmVhZCwgYXJnMDogSlZNVHlwZXMuamF2YV9sYW5nX09iamVjdCk6IEpWTVR5cGVzLmphdmFfbGFuZ19PYmplY3Qge1xuICAgIC8vIFJldHVybiBudWxsOyBubyBjb21waWxlciBhdmFpbGFibGUuXG4gICAgcmV0dXJuIG51bGw7XG4gIH1cblxuICAvLyBOT1AnZC5cbiAgcHVibGljIHN0YXRpYyAnZW5hYmxlKClWJyh0aHJlYWQ6IEpWTVRocmVhZCk6IHZvaWQge31cbiAgcHVibGljIHN0YXRpYyAnZGlzYWJsZSgpVicodGhyZWFkOiBKVk1UaHJlYWQpOiB2b2lkIHt9XG5cbn1cblxuLy8gVXNlZCBmb3IgY29udmVydGluZyBiZXR3ZWVuIG51bWVyaWNhbCByZXByZXNlbnRhdGlvbnMuXG52YXIgY29udmVyc2lvbkJ1ZmZlciA9IG5ldyBCdWZmZXIoOCk7XG5cbmNsYXNzIGphdmFfbGFuZ19Eb3VibGUge1xuXG4gIHB1YmxpYyBzdGF0aWMgJ2RvdWJsZVRvUmF3TG9uZ0JpdHMoRClKJyh0aHJlYWQ6IEpWTVRocmVhZCwgbnVtOiBudW1iZXIpOiBMb25nIHtcbiAgICBjb252ZXJzaW9uQnVmZmVyLndyaXRlRG91YmxlTEUobnVtLCAwKTtcbiAgICByZXR1cm4gTG9uZy5mcm9tQml0cyhjb252ZXJzaW9uQnVmZmVyLnJlYWRVSW50MzJMRSgwKSwgY29udmVyc2lvbkJ1ZmZlci5yZWFkVUludDMyTEUoNCkpO1xuICB9XG5cbiAgcHVibGljIHN0YXRpYyAnbG9uZ0JpdHNUb0RvdWJsZShKKUQnKHRocmVhZDogSlZNVGhyZWFkLCBudW06IExvbmcpOiBudW1iZXIge1xuICAgIGNvbnZlcnNpb25CdWZmZXIud3JpdGVJbnQzMkxFKG51bS5nZXRMb3dCaXRzKCksIDApO1xuICAgIGNvbnZlcnNpb25CdWZmZXIud3JpdGVJbnQzMkxFKG51bS5nZXRIaWdoQml0cygpLCA0KTtcbiAgICByZXR1cm4gY29udmVyc2lvbkJ1ZmZlci5yZWFkRG91YmxlTEUoMCk7XG4gIH1cblxufVxuXG5jbGFzcyBqYXZhX2xhbmdfRmxvYXQge1xuXG4gIHB1YmxpYyBzdGF0aWMgJ2Zsb2F0VG9SYXdJbnRCaXRzKEYpSScodGhyZWFkOiBKVk1UaHJlYWQsIG51bTogbnVtYmVyKTogbnVtYmVyIHtcbiAgICBjb252ZXJzaW9uQnVmZmVyLndyaXRlRmxvYXRMRShudW0sIDApO1xuICAgIHJldHVybiBjb252ZXJzaW9uQnVmZmVyLnJlYWRJbnQzMkxFKDApO1xuICB9XG5cbiAgcHVibGljIHN0YXRpYyAnaW50Qml0c1RvRmxvYXQoSSlGJyh0aHJlYWQ6IEpWTVRocmVhZCwgbnVtOiBudW1iZXIpOiBudW1iZXIge1xuICAgIGNvbnZlcnNpb25CdWZmZXIud3JpdGVJbnQzMkxFKG51bSwgMCk7XG4gICAgcmV0dXJuIGNvbnZlcnNpb25CdWZmZXIucmVhZEZsb2F0TEUoMCk7XG4gIH1cblxufVxuXG5jbGFzcyBqYXZhX2xhbmdfT2JqZWN0IHtcblxuICBwdWJsaWMgc3RhdGljICdnZXRDbGFzcygpTGphdmEvbGFuZy9DbGFzczsnKHRocmVhZDogSlZNVGhyZWFkLCBqYXZhVGhpczogSlZNVHlwZXMuamF2YV9sYW5nX09iamVjdCk6IEpWTVR5cGVzLmphdmFfbGFuZ19DbGFzcyB7XG4gICAgcmV0dXJuIGphdmFUaGlzLmdldENsYXNzKCkuZ2V0Q2xhc3NPYmplY3QodGhyZWFkKTtcbiAgfVxuXG4gIHB1YmxpYyBzdGF0aWMgJ2hhc2hDb2RlKClJJyh0aHJlYWQ6IEpWTVRocmVhZCwgamF2YVRoaXM6IEpWTVR5cGVzLmphdmFfbGFuZ19PYmplY3QpOiBudW1iZXIge1xuICAgIHJldHVybiBqYXZhVGhpcy5yZWY7XG4gIH1cblxuICBwdWJsaWMgc3RhdGljICdjbG9uZSgpTGphdmEvbGFuZy9PYmplY3Q7Jyh0aHJlYWQ6IEpWTVRocmVhZCwgamF2YVRoaXM6IEpWTVR5cGVzLmphdmFfbGFuZ19PYmplY3QpOiBKVk1UeXBlcy5qYXZhX2xhbmdfT2JqZWN0IHtcbiAgICB2YXIgY2xzID0gamF2YVRoaXMuZ2V0Q2xhc3MoKTtcbiAgICBpZiAoY2xzLmdldEludGVybmFsTmFtZSgpWzBdID09PSAnWycpIHtcbiAgICAgIC8vIEFycmF5IGNsb25lLiBJdCdzIGFsd2F5cyBhIHNoYWxsb3cgY2xvbmUuXG4gICAgICByZXR1cm4gKDxKVk1UeXBlcy5KVk1BcnJheTxhbnk+PiBqYXZhVGhpcykuc2xpY2UoMCk7XG4gICAgfSBlbHNlIHtcbiAgICAgIHZhciBjbG9uZWRPYmogPSB1dGlsLm5ld09iamVjdEZyb21DbGFzczxKVk1UeXBlcy5qYXZhX2xhbmdfT2JqZWN0Pih0aHJlYWQsIDxSZWZlcmVuY2VDbGFzc0RhdGE8SlZNVHlwZXMuamF2YV9sYW5nX09iamVjdD4+IGphdmFUaGlzLmdldENsYXNzKCkpO1xuICAgICAgT2JqZWN0LmtleXMoamF2YVRoaXMpLmZvckVhY2goKGZpZWxkTmFtZTogc3RyaW5nKSA9PiB7XG4gICAgICAgICg8YW55PiBjbG9uZWRPYmopW2ZpZWxkTmFtZV0gPSAoPGFueT4gamF2YVRoaXMpW2ZpZWxkTmFtZV07XG4gICAgICB9KTtcbiAgICAgIHJldHVybiBjbG9uZWRPYmo7XG4gICAgfVxuICB9XG5cbiAgcHVibGljIHN0YXRpYyAnbm90aWZ5KClWJyh0aHJlYWQ6IEpWTVRocmVhZCwgamF2YVRoaXM6IEpWTVR5cGVzLmphdmFfbGFuZ19PYmplY3QpOiB2b2lkIHtcbiAgICBkZWJ1ZyhcIlRFKG5vdGlmeSk6IG9uIGxvY2sgKlwiICsgamF2YVRoaXMucmVmKTtcbiAgICBqYXZhVGhpcy5nZXRNb25pdG9yKCkubm90aWZ5KHRocmVhZCk7XG4gIH1cblxuICBwdWJsaWMgc3RhdGljICdub3RpZnlBbGwoKVYnKHRocmVhZDogSlZNVGhyZWFkLCBqYXZhVGhpczogSlZNVHlwZXMuamF2YV9sYW5nX09iamVjdCk6IHZvaWQge1xuICAgIGRlYnVnKFwiVEUobm90aWZ5QWxsKTogb24gbG9jayAqXCIgKyBqYXZhVGhpcy5yZWYpO1xuICAgIGphdmFUaGlzLmdldE1vbml0b3IoKS5ub3RpZnlBbGwodGhyZWFkKTtcbiAgfVxuXG4gIHB1YmxpYyBzdGF0aWMgJ3dhaXQoSilWJyh0aHJlYWQ6IEpWTVRocmVhZCwgamF2YVRoaXM6IEpWTVR5cGVzLmphdmFfbGFuZ19PYmplY3QsIHRpbWVvdXQ6IExvbmcpOiB2b2lkIHtcbiAgICBkZWJ1ZyhcIlRFKHdhaXQpOiBvbiBsb2NrICpcIiArIGphdmFUaGlzLnJlZik7XG4gICAgamF2YVRoaXMuZ2V0TW9uaXRvcigpLndhaXQodGhyZWFkLCAoZnJvbVRpbWVyOiBib29sZWFuKSA9PiB7XG4gICAgICB0aHJlYWQuYXN5bmNSZXR1cm4oKTtcbiAgICB9LCB0aW1lb3V0LnRvTnVtYmVyKCkpO1xuICB9XG5cbn1cblxuY2xhc3MgamF2YV9sYW5nX1BhY2thZ2Uge1xuXG4gIHB1YmxpYyBzdGF0aWMgJ2dldFN5c3RlbVBhY2thZ2UwKExqYXZhL2xhbmcvU3RyaW5nOylMamF2YS9sYW5nL1N0cmluZzsnKHRocmVhZDogSlZNVGhyZWFkLCBwa2dOYW1lT2JqOiBKVk1UeXBlcy5qYXZhX2xhbmdfU3RyaW5nKTogSlZNVHlwZXMuamF2YV9sYW5nX1N0cmluZyB7XG4gICAgdmFyIHBrZ05hbWUgPSBwa2dOYW1lT2JqLnRvU3RyaW5nKCk7XG4gICAgLy8gU2xpY2Ugb2ZmIGVuZGluZyAvXG4gICAgcGtnTmFtZSA9IHBrZ05hbWUuc2xpY2UoMCwgcGtnTmFtZS5sZW5ndGggLSAxKTtcbiAgICBsZXQgcGtncyA9IHRocmVhZC5nZXRCc0NsKCkuZ2V0UGFja2FnZXMoKTtcbiAgICBmb3IgKGxldCBpID0gMDsgaSA8IHBrZ3MubGVuZ3RoOyBpKyspIHtcbiAgICAgIGlmIChwa2dzW2ldWzBdID09PSBwa2dOYW1lKSB7XG4gICAgICAgIC8vIFhYWDogSWdub3JlIHNlY29uZGFyeSBsb2FkIGxvY2F0aW9ucy5cbiAgICAgICAgcmV0dXJuIHV0aWwuaW5pdFN0cmluZyh0aHJlYWQuZ2V0QnNDbCgpLCBwa2dzW2ldWzFdWzBdKTtcbiAgICAgIH1cbiAgICB9XG4gICAgLy8gQ291bGQgbm90IGZpbmQgcGFja2FnZS5cbiAgICByZXR1cm4gbnVsbDtcbiAgfVxuXG4gIHB1YmxpYyBzdGF0aWMgJ2dldFN5c3RlbVBhY2thZ2VzMCgpW0xqYXZhL2xhbmcvU3RyaW5nOycodGhyZWFkOiBKVk1UaHJlYWQpOiBKVk1UeXBlcy5KVk1BcnJheTxKVk1UeXBlcy5qYXZhX2xhbmdfU3RyaW5nPiB7XG4gICAgdmFyIHBrZ05hbWVzID0gdGhyZWFkLmdldEJzQ2woKS5nZXRQYWNrYWdlcygpO1xuICAgIC8vIE5vdGU6IFdlIGFkZCAvIHRvIGVuZCBvZiBwYWNrYWdlIG5hbWUsIHNpbmNlIGl0IGFwcGVhcnMgdGhhdCBpcyB3aGF0IE9wZW5KREsgZXhwZWN0cy5cbiAgICByZXR1cm4gdXRpbC5uZXdBcnJheUZyb21EYXRhPEpWTVR5cGVzLmphdmFfbGFuZ19TdHJpbmc+KHRocmVhZCwgdGhyZWFkLmdldEJzQ2woKSwgJ1tMamF2YS9sYW5nL1N0cmluZzsnLCBwa2dOYW1lcy5tYXAoKHBrZ05hbWUpID0+IHV0aWwuaW5pdFN0cmluZyh0aHJlYWQuZ2V0QnNDbCgpLCBwa2dOYW1lWzBdICsgXCIvXCIpKSk7XG4gIH1cbn1cblxuY2xhc3MgamF2YV9sYW5nX1Byb2Nlc3NFbnZpcm9ubWVudCB7XG5cbiAgcHVibGljIHN0YXRpYyAnZW52aXJvbigpW1tCJyh0aHJlYWQ6IEpWTVRocmVhZCk6IEpWTVR5cGVzLkpWTUFycmF5PEpWTVR5cGVzLkpWTUFycmF5PG51bWJlcj4+IHtcbiAgICB2YXIgZW52QXJyID0gdXRpbC5uZXdBcnJheTxKVk1UeXBlcy5KVk1BcnJheTxudW1iZXI+Pih0aHJlYWQsIHRocmVhZC5nZXRCc0NsKCksICdbW0InLCAwKSxcbiAgICAgIGVudiA9IHByb2Nlc3MuZW52LCBrZXk6IHN0cmluZywgdjogc3RyaW5nLCBiQXJyOiBKVk1UeXBlcy5KVk1BcnJheTxudW1iZXI+O1xuICAgIC8vIGNvbnZlcnQgdG8gYW4gYXJyYXkgb2Ygc3RyaW5ncyBvZiB0aGUgZm9ybSBba2V5LCB2YWx1ZSwga2V5LCB2YWx1ZSAuLi5dXG4gICAgZm9yIChrZXkgaW4gZW52KSB7XG4gICAgICB2ID0gZW52W2tleV07XG4gICAgICBiQXJyID0gdXRpbC5uZXdBcnJheTxudW1iZXI+KHRocmVhZCwgdGhyZWFkLmdldEJzQ2woKSwgJ1tCJywgMCk7XG4gICAgICBiQXJyLmFycmF5ID0gdXRpbC5ieXRlc3RyMkFycmF5KGtleSk7XG4gICAgICBlbnZBcnIuYXJyYXkucHVzaChiQXJyKTtcbiAgICAgIGJBcnIgPSB1dGlsLm5ld0FycmF5PG51bWJlcj4odGhyZWFkLCB0aHJlYWQuZ2V0QnNDbCgpLCAnW0InLCAwKTtcbiAgICAgIGJBcnIuYXJyYXkgPSB1dGlsLmJ5dGVzdHIyQXJyYXkodik7XG4gICAgICBlbnZBcnIuYXJyYXkucHVzaChiQXJyKTtcbiAgICB9XG4gICAgcmV0dXJuIGVudkFycjtcbiAgfVxuXG59XG5cbmNsYXNzIGphdmFfbGFuZ19yZWZsZWN0X0FycmF5IHtcblxuICBwdWJsaWMgc3RhdGljICdnZXRMZW5ndGgoTGphdmEvbGFuZy9PYmplY3Q7KUknKHRocmVhZDogSlZNVGhyZWFkLCBhcnI6IEpWTVR5cGVzLkpWTUFycmF5PEpWTVR5cGVzLmphdmFfbGFuZ19PYmplY3Q+KTogbnVtYmVyIHtcbiAgICBpZiAodmVyaWZ5QXJyYXkodGhyZWFkLCBhcnIpKSB7XG4gICAgICBpZiAoaXNOb3ROdWxsKHRocmVhZCwgYXJyKSkge1xuICAgICAgICByZXR1cm4gYXJyLmFycmF5Lmxlbmd0aDtcbiAgICAgIH1cbiAgICB9XG4gIH1cblxuICBwdWJsaWMgc3RhdGljICdnZXQoTGphdmEvbGFuZy9PYmplY3Q7SSlMamF2YS9sYW5nL09iamVjdDsnKHRocmVhZDogSlZNVGhyZWFkLCBhcnI6IEpWTVR5cGVzLkpWTUFycmF5PGFueT4sIGlkeDogbnVtYmVyKTogYW55IHtcbiAgICB2YXIgdmFsID0gYXJyYXlHZXQodGhyZWFkLCBhcnIsIGlkeCk7XG4gICAgaWYgKHZhbCAhPSBudWxsKSB7XG4gICAgICB2YXIgY29tcG9uZW50ID0gYXJyLmdldENsYXNzKCkuZ2V0Q29tcG9uZW50Q2xhc3MoKTtcbiAgICAgIGlmICh1dGlsLmlzX3ByaW1pdGl2ZV90eXBlKGNvbXBvbmVudC5nZXRJbnRlcm5hbE5hbWUoKSkpIHtcbiAgICAgICAgLy8gQm94IHByaW1pdGl2ZSB2YWx1ZXMuXG4gICAgICAgIHJldHVybiAoPFByaW1pdGl2ZUNsYXNzRGF0YT4gY29tcG9uZW50KS5jcmVhdGVXcmFwcGVyT2JqZWN0KHRocmVhZCwgdmFsKTtcbiAgICAgIH1cbiAgICB9XG4gICAgcmV0dXJuIHZhbDtcbiAgfVxuXG4gIHB1YmxpYyBzdGF0aWMgJ2dldEJvb2xlYW4oTGphdmEvbGFuZy9PYmplY3Q7SSlaJzogKHRocmVhZDogSlZNVGhyZWFkLCBhcmcwOiBKVk1UeXBlcy5KVk1BcnJheTxudW1iZXI+LCBhcmcxOiBudW1iZXIpID0+IG51bWJlciA9IGFycmF5R2V0O1xuICBwdWJsaWMgc3RhdGljICdnZXRCeXRlKExqYXZhL2xhbmcvT2JqZWN0O0kpQic6ICh0aHJlYWQ6IEpWTVRocmVhZCwgYXJnMDogSlZNVHlwZXMuSlZNQXJyYXk8bnVtYmVyPiwgYXJnMTogbnVtYmVyKSA9PiBudW1iZXIgPSBhcnJheUdldDtcbiAgcHVibGljIHN0YXRpYyAnZ2V0Q2hhcihMamF2YS9sYW5nL09iamVjdDtJKUMnOiAodGhyZWFkOiBKVk1UaHJlYWQsIGFyZzA6IEpWTVR5cGVzLkpWTUFycmF5PG51bWJlcj4sIGFyZzE6IG51bWJlcikgPT4gbnVtYmVyID0gYXJyYXlHZXQ7XG4gIHB1YmxpYyBzdGF0aWMgJ2dldFNob3J0KExqYXZhL2xhbmcvT2JqZWN0O0kpUyc6ICh0aHJlYWQ6IEpWTVRocmVhZCwgYXJnMDogSlZNVHlwZXMuSlZNQXJyYXk8bnVtYmVyPiwgYXJnMTogbnVtYmVyKSA9PiBudW1iZXIgPSBhcnJheUdldDtcbiAgcHVibGljIHN0YXRpYyAnZ2V0SW50KExqYXZhL2xhbmcvT2JqZWN0O0kpSSc6ICh0aHJlYWQ6IEpWTVRocmVhZCwgYXJnMDogSlZNVHlwZXMuSlZNQXJyYXk8bnVtYmVyPiwgYXJnMTogbnVtYmVyKSA9PiBudW1iZXIgPSBhcnJheUdldDtcbiAgcHVibGljIHN0YXRpYyAnZ2V0TG9uZyhMamF2YS9sYW5nL09iamVjdDtJKUonOiAodGhyZWFkOiBKVk1UaHJlYWQsIGFyZzA6IEpWTVR5cGVzLkpWTUFycmF5PExvbmc+LCBhcmcxOiBudW1iZXIpID0+IExvbmcgPSBhcnJheUdldDtcbiAgcHVibGljIHN0YXRpYyAnZ2V0RmxvYXQoTGphdmEvbGFuZy9PYmplY3Q7SSlGJzogKHRocmVhZDogSlZNVGhyZWFkLCBhcmcwOiBKVk1UeXBlcy5KVk1BcnJheTxudW1iZXI+LCBhcmcxOiBudW1iZXIpID0+IG51bWJlciA9IGFycmF5R2V0O1xuICBwdWJsaWMgc3RhdGljICdnZXREb3VibGUoTGphdmEvbGFuZy9PYmplY3Q7SSlEJzogKHRocmVhZDogSlZNVGhyZWFkLCBhcmcwOiBKVk1UeXBlcy5KVk1BcnJheTxudW1iZXI+LCBhcmcxOiBudW1iZXIpID0+IG51bWJlciA9IGFycmF5R2V0O1xuXG4gIHB1YmxpYyBzdGF0aWMgJ3NldChMamF2YS9sYW5nL09iamVjdDtJTGphdmEvbGFuZy9PYmplY3Q7KVYnKHRocmVhZDogSlZNVGhyZWFkLCBhcnI6IEpWTVR5cGVzLkpWTUFycmF5PGFueT4sIGlkeDogbnVtYmVyLCB2YWw6IEpWTVR5cGVzLmphdmFfbGFuZ19PYmplY3QpOiB2b2lkIHtcbiAgICBpZiAodmVyaWZ5QXJyYXkodGhyZWFkLCBhcnIpICYmIGlzTm90TnVsbCh0aHJlYWQsIGFycikpIHtcbiAgICAgIGlmIChpZHggPCAwIHx8IGlkeCA+PSBhcnIuYXJyYXkubGVuZ3RoKSB7XG4gICAgICAgIHRocmVhZC50aHJvd05ld0V4Y2VwdGlvbignTGphdmEvbGFuZy9BcnJheUluZGV4T3V0T2ZCb3VuZHNFeGNlcHRpb247JywgJ1RyaWVkIHRvIHdyaXRlIHRvIGFuIGlsbGVnYWwgaW5kZXggaW4gYW4gYXJyYXkuJyk7XG4gICAgICB9IGVsc2Uge1xuICAgICAgICB2YXIgY2NscyA9IGFyci5nZXRDbGFzcygpLmdldENvbXBvbmVudENsYXNzKCk7XG4gICAgICAgIGlmIChjY2xzIGluc3RhbmNlb2YgUHJpbWl0aXZlQ2xhc3NEYXRhKSB7XG4gICAgICAgICAgaWYgKHZhbC5nZXRDbGFzcygpLmlzU3ViY2xhc3ModGhyZWFkLmdldEJzQ2woKS5nZXRJbml0aWFsaXplZENsYXNzKHRocmVhZCwgKDxQcmltaXRpdmVDbGFzc0RhdGE+IGNjbHMpLmJveENsYXNzTmFtZSgpKSkpIHtcbiAgICAgICAgICAgIHZhciBjY25hbWUgPSBjY2xzLmdldEludGVybmFsTmFtZSgpO1xuICAgICAgICAgICAgKDxKVk1UeXBlcy5KVk1GdW5jdGlvbj4gKDxhbnk+IHZhbClbYCR7dXRpbC5pbnRlcm5hbDJleHRlcm5hbFtjY25hbWVdfVZhbHVlKCkke2NjbmFtZX1gXSkodGhyZWFkLCBudWxsLCAoZT86IEpWTVR5cGVzLmphdmFfbGFuZ19UaHJvd2FibGUsIHJ2PzogYW55KSA9PiB7XG4gICAgICAgICAgICAgIGlmIChlKSB7XG4gICAgICAgICAgICAgICAgdGhyZWFkLnRocm93RXhjZXB0aW9uKGUpO1xuICAgICAgICAgICAgICB9IGVsc2Uge1xuICAgICAgICAgICAgICAgIGFyci5hcnJheVtpZHhdID0gcnY7XG4gICAgICAgICAgICAgICAgdGhyZWFkLmFzeW5jUmV0dXJuKCk7XG4gICAgICAgICAgICAgIH1cbiAgICAgICAgICAgIH0pO1xuICAgICAgICAgIH0gZWxzZSB7XG4gICAgICAgICAgICB0aHJlYWQudGhyb3dOZXdFeGNlcHRpb24oJ0xqYXZhL2xhbmcvSWxsZWdhbEFyZ3VtZW50RXhjZXB0aW9uOycsICdhcmd1bWVudCB0eXBlIG1pc21hdGNoJyk7XG4gICAgICAgICAgfVxuICAgICAgICB9IGVsc2UgaWYgKHZhbC5nZXRDbGFzcygpLmlzU3ViY2xhc3MoY2NscykpIHtcbiAgICAgICAgICBhcnIuYXJyYXlbaWR4XSA9IHZhbDtcbiAgICAgICAgfSBlbHNlIHtcbiAgICAgICAgICB0aHJlYWQudGhyb3dOZXdFeGNlcHRpb24oJ0xqYXZhL2xhbmcvSWxsZWdhbEFyZ3VtZW50RXhjZXB0aW9uOycsICdhcmd1bWVudCB0eXBlIG1pc21hdGNoJyk7XG4gICAgICAgIH1cbiAgICAgIH1cbiAgICB9XG4gIH1cblxuICBwdWJsaWMgc3RhdGljICdzZXRCb29sZWFuKExqYXZhL2xhbmcvT2JqZWN0O0laKVYnKHRocmVhZDogSlZNVGhyZWFkLCBhcmcwOiBKVk1UeXBlcy5KVk1BcnJheTxudW1iZXI+LCBhcmcxOiBudW1iZXIsIGFyZzI6IG51bWJlcik6IHZvaWQge1xuICAgIHRocmVhZC50aHJvd05ld0V4Y2VwdGlvbignTGphdmEvbGFuZy9VbnNhdGlzZmllZExpbmtFcnJvcjsnLCAnTmF0aXZlIG1ldGhvZCBub3QgaW1wbGVtZW50ZWQuJyk7XG4gIH1cblxuICBwdWJsaWMgc3RhdGljICdzZXRCeXRlKExqYXZhL2xhbmcvT2JqZWN0O0lCKVYnKHRocmVhZDogSlZNVGhyZWFkLCBhcmcwOiBKVk1UeXBlcy5KVk1BcnJheTxudW1iZXI+LCBhcmcxOiBudW1iZXIsIGFyZzI6IG51bWJlcik6IHZvaWQge1xuICAgIHRocmVhZC50aHJvd05ld0V4Y2VwdGlvbignTGphdmEvbGFuZy9VbnNhdGlzZmllZExpbmtFcnJvcjsnLCAnTmF0aXZlIG1ldGhvZCBub3QgaW1wbGVtZW50ZWQuJyk7XG4gIH1cblxuICBwdWJsaWMgc3RhdGljICdzZXRDaGFyKExqYXZhL2xhbmcvT2JqZWN0O0lDKVYnKHRocmVhZDogSlZNVGhyZWFkLCBhcmcwOiBKVk1UeXBlcy5KVk1BcnJheTxudW1iZXI+LCBhcmcxOiBudW1iZXIsIGFyZzI6IG51bWJlcik6IHZvaWQge1xuICAgIHRocmVhZC50aHJvd05ld0V4Y2VwdGlvbignTGphdmEvbGFuZy9VbnNhdGlzZmllZExpbmtFcnJvcjsnLCAnTmF0aXZlIG1ldGhvZCBub3QgaW1wbGVtZW50ZWQuJyk7XG4gIH1cblxuICBwdWJsaWMgc3RhdGljICdzZXRTaG9ydChMamF2YS9sYW5nL09iamVjdDtJUylWJyh0aHJlYWQ6IEpWTVRocmVhZCwgYXJnMDogSlZNVHlwZXMuSlZNQXJyYXk8bnVtYmVyPiwgYXJnMTogbnVtYmVyLCBhcmcyOiBudW1iZXIpOiB2b2lkIHtcbiAgICB0aHJlYWQudGhyb3dOZXdFeGNlcHRpb24oJ0xqYXZhL2xhbmcvVW5zYXRpc2ZpZWRMaW5rRXJyb3I7JywgJ05hdGl2ZSBtZXRob2Qgbm90IGltcGxlbWVudGVkLicpO1xuICB9XG5cbiAgcHVibGljIHN0YXRpYyAnc2V0SW50KExqYXZhL2xhbmcvT2JqZWN0O0lJKVYnKHRocmVhZDogSlZNVGhyZWFkLCBhcmcwOiBKVk1UeXBlcy5KVk1BcnJheTxudW1iZXI+LCBhcmcxOiBudW1iZXIsIGFyZzI6IG51bWJlcik6IHZvaWQge1xuICAgIHRocmVhZC50aHJvd05ld0V4Y2VwdGlvbignTGphdmEvbGFuZy9VbnNhdGlzZmllZExpbmtFcnJvcjsnLCAnTmF0aXZlIG1ldGhvZCBub3QgaW1wbGVtZW50ZWQuJyk7XG4gIH1cblxuICBwdWJsaWMgc3RhdGljICdzZXRMb25nKExqYXZhL2xhbmcvT2JqZWN0O0lKKVYnKHRocmVhZDogSlZNVGhyZWFkLCBhcmcwOiBKVk1UeXBlcy5KVk1BcnJheTxMb25nPiwgYXJnMTogbnVtYmVyLCBhcmcyOiBMb25nKTogdm9pZCB7XG4gICAgdGhyZWFkLnRocm93TmV3RXhjZXB0aW9uKCdMamF2YS9sYW5nL1Vuc2F0aXNmaWVkTGlua0Vycm9yOycsICdOYXRpdmUgbWV0aG9kIG5vdCBpbXBsZW1lbnRlZC4nKTtcbiAgfVxuXG4gIHB1YmxpYyBzdGF0aWMgJ3NldEZsb2F0KExqYXZhL2xhbmcvT2JqZWN0O0lGKVYnKHRocmVhZDogSlZNVGhyZWFkLCBhcmcwOiBKVk1UeXBlcy5KVk1BcnJheTxudW1iZXI+LCBhcmcxOiBudW1iZXIsIGFyZzI6IG51bWJlcik6IHZvaWQge1xuICAgIHRocmVhZC50aHJvd05ld0V4Y2VwdGlvbignTGphdmEvbGFuZy9VbnNhdGlzZmllZExpbmtFcnJvcjsnLCAnTmF0aXZlIG1ldGhvZCBub3QgaW1wbGVtZW50ZWQuJyk7XG4gIH1cblxuICBwdWJsaWMgc3RhdGljICdzZXREb3VibGUoTGphdmEvbGFuZy9PYmplY3Q7SUQpVicodGhyZWFkOiBKVk1UaHJlYWQsIGFyZzA6IEpWTVR5cGVzLkpWTUFycmF5PG51bWJlcj4sIGFyZzE6IG51bWJlciwgYXJnMjogbnVtYmVyKTogdm9pZCB7XG4gICAgdGhyZWFkLnRocm93TmV3RXhjZXB0aW9uKCdMamF2YS9sYW5nL1Vuc2F0aXNmaWVkTGlua0Vycm9yOycsICdOYXRpdmUgbWV0aG9kIG5vdCBpbXBsZW1lbnRlZC4nKTtcbiAgfVxuXG4gIHB1YmxpYyBzdGF0aWMgJ25ld0FycmF5KExqYXZhL2xhbmcvQ2xhc3M7SSlMamF2YS9sYW5nL09iamVjdDsnKHRocmVhZDogSlZNVGhyZWFkLCBjbHM6IEpWTVR5cGVzLmphdmFfbGFuZ19DbGFzcywgbGVuOiBudW1iZXIpOiBKVk1UeXBlcy5KVk1BcnJheTxhbnk+IHtcbiAgICByZXR1cm4gdXRpbC5uZXdBcnJheTxhbnk+KHRocmVhZCwgY2xzLiRjbHMuZ2V0TG9hZGVyKCksIGBbJHtjbHMuJGNscy5nZXRJbnRlcm5hbE5hbWUoKX1gLCBsZW4pO1xuICB9XG5cbiAgcHVibGljIHN0YXRpYyAnbXVsdGlOZXdBcnJheShMamF2YS9sYW5nL0NsYXNzO1tJKUxqYXZhL2xhbmcvT2JqZWN0OycodGhyZWFkOiBKVk1UaHJlYWQsIGpjbzogSlZNVHlwZXMuamF2YV9sYW5nX0NsYXNzLCBsZW5zOiBKVk1UeXBlcy5KVk1BcnJheTxudW1iZXI+KTogSlZNVHlwZXMuSlZNQXJyYXk8YW55PiB7XG4gICAgdmFyIHR5cGVTdHIgPSAobmV3IEFycmF5KGxlbnMuYXJyYXkubGVuZ3RoICsgMSkpLmpvaW4oJ1snKSArIGpjby4kY2xzLmdldEludGVybmFsTmFtZSgpO1xuICAgIGlmIChqY28uJGNscy5pc0luaXRpYWxpemVkKHRocmVhZCkpIHtcbiAgICAgIHJldHVybiB1dGlsLm11bHRpTmV3QXJyYXk8YW55Pih0aHJlYWQsIGpjby4kY2xzLmdldExvYWRlcigpLCB0eXBlU3RyLCBsZW5zLmFycmF5KTtcbiAgICB9IGVsc2Uge1xuICAgICAgdGhyZWFkLnNldFN0YXR1cyhUaHJlYWRTdGF0dXMuQVNZTkNfV0FJVElORyk7XG4gICAgICBqY28uJGNscy5pbml0aWFsaXplKHRocmVhZCwgKGNscykgPT4ge1xuICAgICAgICB0aHJlYWQuYXN5bmNSZXR1cm4odXRpbC5tdWx0aU5ld0FycmF5PGFueT4odGhyZWFkLCBqY28uJGNscy5nZXRMb2FkZXIoKSwgdHlwZVN0ciwgbGVucy5hcnJheSkpO1xuICAgICAgfSk7XG4gICAgfVxuICB9XG5cbn1cblxuY2xhc3MgamF2YV9sYW5nX3JlZmxlY3RfUHJveHkge1xuXG4gIHB1YmxpYyBzdGF0aWMgJ2RlZmluZUNsYXNzMChMamF2YS9sYW5nL0NsYXNzTG9hZGVyO0xqYXZhL2xhbmcvU3RyaW5nO1tCSUkpTGphdmEvbGFuZy9DbGFzczsnKHRocmVhZDogSlZNVGhyZWFkLCBjbDogSlZNVHlwZXMuamF2YV9sYW5nX0NsYXNzTG9hZGVyLCBuYW1lOiBKVk1UeXBlcy5qYXZhX2xhbmdfU3RyaW5nLCBieXRlczogSlZNVHlwZXMuSlZNQXJyYXk8bnVtYmVyPiwgb2Zmc2V0OiBudW1iZXIsIGxlbjogbnVtYmVyKTogSlZNVHlwZXMuamF2YV9sYW5nX0NsYXNzIHtcbiAgICB2YXIgbG9hZGVyID0gdXRpbC5nZXRMb2FkZXIodGhyZWFkLCBjbCksXG4gICAgICBjbHMgPSBsb2FkZXIuZGVmaW5lQ2xhc3ModGhyZWFkLCB1dGlsLmludF9jbGFzc25hbWUobmFtZS50b1N0cmluZygpKSwgdXRpbC5ieXRlQXJyYXkyQnVmZmVyKGJ5dGVzLmFycmF5LCBvZmZzZXQsIGxlbiksIG51bGwpO1xuICAgIGlmIChjbHMgIT0gbnVsbCkge1xuICAgICAgcmV0dXJuIGNscy5nZXRDbGFzc09iamVjdCh0aHJlYWQpO1xuICAgIH1cbiAgfVxuXG59XG5cbmNsYXNzIGphdmFfbGFuZ19SdW50aW1lIHtcblxuICBwdWJsaWMgc3RhdGljICdhdmFpbGFibGVQcm9jZXNzb3JzKClJJyh0aHJlYWQ6IEpWTVRocmVhZCwgamF2YVRoaXM6IEpWTVR5cGVzLmphdmFfbGFuZ19SdW50aW1lKTogbnVtYmVyIHtcbiAgICByZXR1cm4gMTtcbiAgfVxuXG4gIHB1YmxpYyBzdGF0aWMgJ2ZyZWVNZW1vcnkoKUonKHRocmVhZDogSlZNVGhyZWFkLCBqYXZhVGhpczogSlZNVHlwZXMuamF2YV9sYW5nX1J1bnRpbWUpOiBMb25nIHtcbiAgICByZXR1cm4gTG9uZy5NQVhfVkFMVUU7XG4gIH1cblxuICBwdWJsaWMgc3RhdGljICd0b3RhbE1lbW9yeSgpSicodGhyZWFkOiBKVk1UaHJlYWQsIGphdmFUaGlzOiBKVk1UeXBlcy5qYXZhX2xhbmdfUnVudGltZSk6IExvbmcge1xuICAgIHJldHVybiBMb25nLk1BWF9WQUxVRTtcbiAgfVxuXG4gIC8qKlxuICAgKiBSZXR1cm5zIHRoZSBtYXhpbXVtIGFtb3VudCBvZiBtZW1vcnkgdGhhdCB0aGUgSmF2YSBWaXJ0dWFsIE1hY2hpbmUgd2lsbFxuICAgKiBhdHRlbXB0IHRvIHVzZSwgaW4gYnl0ZXMsIGFzIGEgTG9uZy4gSWYgdGhlcmUgaXMgbm8gaW5oZXJlbnQgbGltaXQgdGhlbiB0aGVcbiAgICogdmFsdWUgTG9uZy5NQVhfVkFMVUUgd2lsbCBiZSByZXR1cm5lZC5cbiAgICpcbiAgICogQ3VycmVudGx5IHJldHVybnMgTG9uZy5NQVhfVkFMVUUgYmVjYXVzZSB1bmxpa2Ugb3RoZXIgSlZNcyBEb3BwaW8gaGFzIG5vXG4gICAqIGhhcmQgbGltaXQgb24gdGhlIGhlYXAgc2l6ZS5cbiAgICovXG4gIHB1YmxpYyBzdGF0aWMgJ21heE1lbW9yeSgpSicodGhyZWFkOiBKVk1UaHJlYWQsIGphdmFUaGlzOiBKVk1UeXBlcy5qYXZhX2xhbmdfUnVudGltZSk6IExvbmcge1xuICAgIHJldHVybiBMb25nLk1BWF9WQUxVRTtcbiAgfVxuXG4gIC8qKlxuICAgKiBObyB1bml2ZXJzYWwgd2F5IG9mIGZvcmNpbmcgYnJvd3NlciB0byBHQywgc28gd2UgeWllbGQgaW4gaG9wZXNcbiAgICogdGhhdCB0aGUgYnJvd3NlciB3aWxsIHVzZSBpdCBhcyBhbiBvcHBvcnR1bml0eSB0byBHQy5cbiAgICovXG4gIHB1YmxpYyBzdGF0aWMgJ2djKClWJyh0aHJlYWQ6IEpWTVRocmVhZCwgamF2YVRoaXM6IEpWTVR5cGVzLmphdmFfbGFuZ19SdW50aW1lKTogdm9pZCB7XG4gICAgdGhyZWFkLnNldFN0YXR1cyhUaHJlYWRTdGF0dXMuQVNZTkNfV0FJVElORyk7XG4gICAgc2V0SW1tZWRpYXRlKCgpID0+IHtcbiAgICAgIHRocmVhZC5hc3luY1JldHVybigpO1xuICAgIH0pO1xuICB9XG5cbiAgcHVibGljIHN0YXRpYyAncnVuRmluYWxpemF0aW9uMCgpVicodGhyZWFkOiBKVk1UaHJlYWQpOiB2b2lkIHtcbiAgICB0aHJlYWQudGhyb3dOZXdFeGNlcHRpb24oJ0xqYXZhL2xhbmcvVW5zYXRpc2ZpZWRMaW5rRXJyb3I7JywgJ05hdGl2ZSBtZXRob2Qgbm90IGltcGxlbWVudGVkLicpO1xuICB9XG5cbiAgcHVibGljIHN0YXRpYyAndHJhY2VJbnN0cnVjdGlvbnMoWilWJyh0aHJlYWQ6IEpWTVRocmVhZCwgamF2YVRoaXM6IEpWTVR5cGVzLmphdmFfbGFuZ19SdW50aW1lLCBhcmcwOiBudW1iZXIpOiB2b2lkIHtcbiAgICB0aHJlYWQudGhyb3dOZXdFeGNlcHRpb24oJ0xqYXZhL2xhbmcvVW5zYXRpc2ZpZWRMaW5rRXJyb3I7JywgJ05hdGl2ZSBtZXRob2Qgbm90IGltcGxlbWVudGVkLicpO1xuICB9XG5cbiAgcHVibGljIHN0YXRpYyAndHJhY2VNZXRob2RDYWxscyhaKVYnKHRocmVhZDogSlZNVGhyZWFkLCBqYXZhVGhpczogSlZNVHlwZXMuamF2YV9sYW5nX1J1bnRpbWUsIGFyZzA6IG51bWJlcik6IHZvaWQge1xuICAgIHRocmVhZC50aHJvd05ld0V4Y2VwdGlvbignTGphdmEvbGFuZy9VbnNhdGlzZmllZExpbmtFcnJvcjsnLCAnTmF0aXZlIG1ldGhvZCBub3QgaW1wbGVtZW50ZWQuJyk7XG4gIH1cblxufVxuXG5jbGFzcyBqYXZhX2xhbmdfU2VjdXJpdHlNYW5hZ2VyIHtcblxuICBwdWJsaWMgc3RhdGljICdnZXRDbGFzc0NvbnRleHQoKVtMamF2YS9sYW5nL0NsYXNzOycodGhyZWFkOiBKVk1UaHJlYWQsIGphdmFUaGlzOiBKVk1UeXBlcy5qYXZhX2xhbmdfU2VjdXJpdHlNYW5hZ2VyKTogSlZNVHlwZXMuSlZNQXJyYXk8SlZNVHlwZXMuamF2YV9sYW5nX0NsYXNzPiB7XG4gICAgLy8gcmV0dXJuIGFuIGFycmF5IG9mIGNsYXNzZXMgZm9yIGVhY2ggbWV0aG9kIG9uIHRoZSBzdGFja1xuICAgIC8vIHN0YXJ0aW5nIHdpdGggdGhlIGN1cnJlbnQgbWV0aG9kIGFuZCBnb2luZyB1cCB0aGUgY2FsbCBjaGFpblxuICAgIHJldHVybiB1dGlsLm5ld0FycmF5RnJvbURhdGE8SlZNVHlwZXMuamF2YV9sYW5nX0NsYXNzPih0aHJlYWQsIHRocmVhZC5nZXRCc0NsKCksICdbTGphdmEvbGFuZy9DbGFzczsnLCB0aHJlYWQuZ2V0U3RhY2tUcmFjZSgpLm1hcCgoaXRlbSkgPT4gaXRlbS5tZXRob2QuY2xzLmdldENsYXNzT2JqZWN0KHRocmVhZCkpKTs7XG4gIH1cblxuICBwdWJsaWMgc3RhdGljICdjdXJyZW50Q2xhc3NMb2FkZXIwKClMamF2YS9sYW5nL0NsYXNzTG9hZGVyOycodGhyZWFkOiBKVk1UaHJlYWQsIGphdmFUaGlzOiBKVk1UeXBlcy5qYXZhX2xhbmdfU2VjdXJpdHlNYW5hZ2VyKTogSlZNVHlwZXMuamF2YV9sYW5nX0NsYXNzTG9hZGVyIHtcbiAgICB0aHJlYWQudGhyb3dOZXdFeGNlcHRpb24oJ0xqYXZhL2xhbmcvVW5zYXRpc2ZpZWRMaW5rRXJyb3I7JywgJ05hdGl2ZSBtZXRob2Qgbm90IGltcGxlbWVudGVkLicpO1xuICAgIC8vIFNhdGlzZnkgVHlwZVNjcmlwdCByZXR1cm4gdHlwZS5cbiAgICByZXR1cm4gbnVsbDtcbiAgfVxuXG4gIHB1YmxpYyBzdGF0aWMgJ2NsYXNzRGVwdGgoTGphdmEvbGFuZy9TdHJpbmc7KUknKHRocmVhZDogSlZNVGhyZWFkLCBqYXZhVGhpczogSlZNVHlwZXMuamF2YV9sYW5nX1NlY3VyaXR5TWFuYWdlciwgYXJnMDogSlZNVHlwZXMuamF2YV9sYW5nX1NlY3VyaXR5TWFuYWdlcik6IG51bWJlciB7XG4gICAgdGhyZWFkLnRocm93TmV3RXhjZXB0aW9uKCdMamF2YS9sYW5nL1Vuc2F0aXNmaWVkTGlua0Vycm9yOycsICdOYXRpdmUgbWV0aG9kIG5vdCBpbXBsZW1lbnRlZC4nKTtcbiAgICAvLyBTYXRpc2Z5IFR5cGVTY3JpcHQgcmV0dXJuIHR5cGUuXG4gICAgcmV0dXJuIDA7XG4gIH1cblxuICBwdWJsaWMgc3RhdGljICdjbGFzc0xvYWRlckRlcHRoMCgpSScodGhyZWFkOiBKVk1UaHJlYWQsIGphdmFUaGlzOiBKVk1UeXBlcy5qYXZhX2xhbmdfU2VjdXJpdHlNYW5hZ2VyKTogbnVtYmVyIHtcbiAgICB0aHJlYWQudGhyb3dOZXdFeGNlcHRpb24oJ0xqYXZhL2xhbmcvVW5zYXRpc2ZpZWRMaW5rRXJyb3I7JywgJ05hdGl2ZSBtZXRob2Qgbm90IGltcGxlbWVudGVkLicpO1xuICAgIC8vIFNhdGlzZnkgVHlwZVNjcmlwdCByZXR1cm4gdHlwZS5cbiAgICByZXR1cm4gMDtcbiAgfVxuXG4gIHB1YmxpYyBzdGF0aWMgJ2N1cnJlbnRMb2FkZWRDbGFzczAoKUxqYXZhL2xhbmcvQ2xhc3M7Jyh0aHJlYWQ6IEpWTVRocmVhZCwgamF2YVRoaXM6IEpWTVR5cGVzLmphdmFfbGFuZ19TZWN1cml0eU1hbmFnZXIpOiBKVk1UeXBlcy5qYXZhX2xhbmdfQ2xhc3Mge1xuICAgIHRocmVhZC50aHJvd05ld0V4Y2VwdGlvbignTGphdmEvbGFuZy9VbnNhdGlzZmllZExpbmtFcnJvcjsnLCAnTmF0aXZlIG1ldGhvZCBub3QgaW1wbGVtZW50ZWQuJyk7XG4gICAgLy8gU2F0aXNmeSBUeXBlU2NyaXB0IHJldHVybiB0eXBlLlxuICAgIHJldHVybiBudWxsO1xuICB9XG5cbn1cblxuY2xhc3MgamF2YV9sYW5nX1NodXRkb3duIHtcblxuICBwdWJsaWMgc3RhdGljICdoYWx0MChJKVYnKHRocmVhZDogSlZNVGhyZWFkLCBzdGF0dXM6IG51bWJlcik6IHZvaWQge1xuICAgIHRocmVhZC5nZXRKVk0oKS5oYWx0KHN0YXR1cyk7XG4gIH1cblxuICBwdWJsaWMgc3RhdGljICdydW5BbGxGaW5hbGl6ZXJzKClWJyh0aHJlYWQ6IEpWTVRocmVhZCk6IHZvaWQge1xuICAgIHRocmVhZC50aHJvd05ld0V4Y2VwdGlvbignTGphdmEvbGFuZy9VbnNhdGlzZmllZExpbmtFcnJvcjsnLCAnTmF0aXZlIG1ldGhvZCBub3QgaW1wbGVtZW50ZWQuJyk7XG4gIH1cblxufVxuXG5jbGFzcyBqYXZhX2xhbmdfU3RyaWN0TWF0aCB7XG5cbiAgcHVibGljIHN0YXRpYyAnc2luKEQpRCcodGhyZWFkOiBKVk1UaHJlYWQsIGRfdmFsOiBudW1iZXIpOiBudW1iZXIge1xuICAgIHJldHVybiBNYXRoLnNpbihkX3ZhbCk7XG4gIH1cblxuICBwdWJsaWMgc3RhdGljICdjb3MoRClEJyh0aHJlYWQ6IEpWTVRocmVhZCwgZF92YWw6IG51bWJlcik6IG51bWJlciB7XG4gICAgcmV0dXJuIE1hdGguY29zKGRfdmFsKTtcbiAgfVxuXG4gIHB1YmxpYyBzdGF0aWMgJ3RhbihEKUQnKHRocmVhZDogSlZNVGhyZWFkLCBkX3ZhbDogbnVtYmVyKTogbnVtYmVyIHtcbiAgICByZXR1cm4gTWF0aC50YW4oZF92YWwpO1xuICB9XG5cbiAgcHVibGljIHN0YXRpYyAnYXNpbihEKUQnKHRocmVhZDogSlZNVGhyZWFkLCBkX3ZhbDogbnVtYmVyKTogbnVtYmVyIHtcbiAgICByZXR1cm4gTWF0aC5hc2luKGRfdmFsKTtcbiAgfVxuXG4gIHB1YmxpYyBzdGF0aWMgJ2Fjb3MoRClEJyh0aHJlYWQ6IEpWTVRocmVhZCwgZF92YWw6IG51bWJlcik6IG51bWJlciB7XG4gICAgcmV0dXJuIE1hdGguYWNvcyhkX3ZhbCk7XG4gIH1cblxuICBwdWJsaWMgc3RhdGljICdhdGFuKEQpRCcodGhyZWFkOiBKVk1UaHJlYWQsIGRfdmFsOiBudW1iZXIpOiBudW1iZXIge1xuICAgIHJldHVybiBNYXRoLmF0YW4oZF92YWwpO1xuICB9XG5cbiAgcHVibGljIHN0YXRpYyAnZXhwKEQpRCcodGhyZWFkOiBKVk1UaHJlYWQsIGRfdmFsOiBudW1iZXIpOiBudW1iZXIge1xuICAgIHJldHVybiBNYXRoLmV4cChkX3ZhbCk7XG4gIH1cblxuICBwdWJsaWMgc3RhdGljICdsb2coRClEJyh0aHJlYWQ6IEpWTVRocmVhZCwgZF92YWw6IG51bWJlcik6IG51bWJlciB7XG4gICAgcmV0dXJuIE1hdGgubG9nKGRfdmFsKTtcbiAgfVxuXG4gIHB1YmxpYyBzdGF0aWMgJ2xvZzEwKEQpRCcodGhyZWFkOiBKVk1UaHJlYWQsIGRfdmFsOiBudW1iZXIpOiBudW1iZXIge1xuICAgIHJldHVybiBNYXRoLmxvZyhkX3ZhbCkgLyBNYXRoLkxOMTA7XG4gIH1cblxuICBwdWJsaWMgc3RhdGljICdzcXJ0KEQpRCcodGhyZWFkOiBKVk1UaHJlYWQsIGRfdmFsOiBudW1iZXIpOiBudW1iZXIge1xuICAgIHJldHVybiBNYXRoLnNxcnQoZF92YWwpO1xuICB9XG5cbiAgcHVibGljIHN0YXRpYyAnY2JydChEKUQnKHRocmVhZDogSlZNVGhyZWFkLCBkX3ZhbDogbnVtYmVyKTogbnVtYmVyIHtcbiAgICB2YXIgaXNfbmVnID0gZF92YWwgPCAwO1xuICAgIGlmIChpc19uZWcpIHtcbiAgICAgIHJldHVybiAtTWF0aC5wb3coLWRfdmFsLCAxIC8gMyk7XG4gICAgfSBlbHNlIHtcbiAgICAgIHJldHVybiBNYXRoLnBvdyhkX3ZhbCwgMSAvIDMpO1xuICAgIH1cbiAgfVxuXG4gIHB1YmxpYyBzdGF0aWMgJ0lFRUVyZW1haW5kZXIoREQpRCcodGhyZWFkOiBKVk1UaHJlYWQsIHg6IG51bWJlciwgeTogbnVtYmVyKTogbnVtYmVyIHtcbiAgICAvLyBQdXJnZSBvZmYgZXhjZXB0aW9uIHZhbHVlcy5cbiAgICBpZiAoeCA9PSBOdW1iZXIuTkVHQVRJVkVfSU5GSU5JVFkgfHwgISh4IDwgTnVtYmVyLlBPU0lUSVZFX0lORklOSVRZKVxuICAgICAgICB8fCB5ID09IDAgfHwgeSAhPSB5KVxuICAgICAgcmV0dXJuIE51bWJlci5OYU47XG5cbiAgICB2YXIgVFdPXzEwMjMgPSA4Ljk4ODQ2NTY3NDMxMTU4ZTMwNzsgLy8gTG9uZyBiaXRzIDB4N2ZlMDAwMDAwMDAwMDAwMEwuXG5cbiAgICB2YXIgbmVnYXRpdmUgPSB4IDwgMDtcbiAgICB4ID0gTWF0aC5hYnMoeCk7XG4gICAgeSA9IE1hdGguYWJzKHkpO1xuICAgIGlmICh4ID09IHkgfHwgeCA9PSAwKVxuICAgICAgcmV0dXJuIDAgKiB4OyAvLyBHZXQgY29ycmVjdCBzaWduLlxuXG4gICAgLy8gQWNoaWV2ZSB4IDwgMnksIHRoZW4gdGFrZSBmaXJzdCBzaG90IGF0IHJlbWFpbmRlci5cbiAgICBpZiAoeSA8IFRXT18xMDIzKVxuICAgICAgeCAlPSB5ICsgeTtcblxuICAgIC8vIE5vdyBhZGp1c3QgeCB0byBnZXQgY29ycmVjdCBwcmVjaXNpb24uXG4gICAgaWYgKHkgPCA0IC8gVFdPXzEwMjMpIHtcbiAgICAgIGlmICh4ICsgeCA+IHkpIHtcbiAgICAgICAgeCAtPSB5O1xuICAgICAgICBpZiAoeCArIHggPj0geSlcbiAgICAgICAgICB4IC09IHk7XG4gICAgICB9XG4gICAgfSBlbHNlIHtcbiAgICAgIHkgKj0gMC41O1xuICAgICAgaWYgKHggPiB5KSB7XG4gICAgICAgIHggLT0geTtcbiAgICAgICAgaWYgKHggPj0geSlcbiAgICAgICAgICB4IC09IHk7XG4gICAgICB9XG4gICAgfVxuICAgIHJldHVybiBuZWdhdGl2ZSA/IC14IDogeDtcbiAgfVxuXG4gIHB1YmxpYyBzdGF0aWMgJ2F0YW4yKEREKUQnKHRocmVhZDogSlZNVGhyZWFkLCB5OiBudW1iZXIsIHg6IG51bWJlcik6IG51bWJlciB7XG4gICAgcmV0dXJuIE1hdGguYXRhbjIoeSwgeCk7XG4gIH1cblxuICBwdWJsaWMgc3RhdGljICdwb3coREQpRCcodGhyZWFkOiBKVk1UaHJlYWQsIGJhc2U6IG51bWJlciwgZXhwOiBudW1iZXIpOiBudW1iZXIge1xuICAgIHJldHVybiBNYXRoLnBvdyhiYXNlLCBleHApO1xuICB9XG5cbiAgcHVibGljIHN0YXRpYyAnc2luaChEKUQnKHRocmVhZDogSlZNVGhyZWFkLCBkX3ZhbDogbnVtYmVyKTogbnVtYmVyIHtcbiAgICByZXR1cm4gKDxhbnk+IE1hdGgpLnNpbmgoZF92YWwpO1xuICB9XG5cbiAgcHVibGljIHN0YXRpYyAnY29zaChEKUQnKHRocmVhZDogSlZNVGhyZWFkLCBkX3ZhbDogbnVtYmVyKTogbnVtYmVyIHtcbiAgICB2YXIgZXhwID0gTWF0aC5leHAoZF92YWwpO1xuICAgIHJldHVybiAoZXhwICsgMSAvIGV4cCkgLyAyO1xuICB9XG5cbiAgcHVibGljIHN0YXRpYyAndGFuaChEKUQnKHRocmVhZDogSlZNVGhyZWFkLCBkX3ZhbDogbnVtYmVyKTogbnVtYmVyIHtcbiAgICB0aHJlYWQudGhyb3dOZXdFeGNlcHRpb24oJ0xqYXZhL2xhbmcvVW5zYXRpc2ZpZWRMaW5rRXJyb3I7JywgJ05hdGl2ZSBtZXRob2Qgbm90IGltcGxlbWVudGVkLicpO1xuICAgIC8vIFNhdGlzZnkgVHlwZVNjcmlwdCByZXR1cm4gdHlwZS5cbiAgICByZXR1cm4gMDtcbiAgfVxuXG4gIHB1YmxpYyBzdGF0aWMgJ2h5cG90KEREKUQnKHRocmVhZDogSlZNVGhyZWFkLCBhcmcwOiBudW1iZXIsIGFyZzE6IG51bWJlcik6IG51bWJlciB7XG4gICAgcmV0dXJuIE1hdGguc3FydChNYXRoLnBvdyhhcmcwLCAyKSArIE1hdGgucG93KGFyZzEsIDIpKTtcbiAgfVxuXG4gIHB1YmxpYyBzdGF0aWMgJ2V4cG0xKEQpRCcodGhyZWFkOiBKVk1UaHJlYWQsIGRfdmFsOiBudW1iZXIpOiBudW1iZXIge1xuICAgIHJldHVybiAoPGFueT4gTWF0aCkuZXhwbTEoZF92YWwpO1xuICB9XG5cbiAgcHVibGljIHN0YXRpYyAnbG9nMXAoRClEJyh0aHJlYWQ6IEpWTVRocmVhZCwgZF92YWw6IG51bWJlcik6IG51bWJlciB7XG4gICAgdGhyZWFkLnRocm93TmV3RXhjZXB0aW9uKCdMamF2YS9sYW5nL1Vuc2F0aXNmaWVkTGlua0Vycm9yOycsICdOYXRpdmUgbWV0aG9kIG5vdCBpbXBsZW1lbnRlZC4nKTtcbiAgICAvLyBTYXRpc2Z5IFR5cGVTY3JpcHQgcmV0dXJuIHR5cGUuXG4gICAgcmV0dXJuIDA7XG4gIH1cblxufVxuXG5jbGFzcyBqYXZhX2xhbmdfU3RyaW5nIHtcblxuICBwdWJsaWMgc3RhdGljICdpbnRlcm4oKUxqYXZhL2xhbmcvU3RyaW5nOycodGhyZWFkOiBKVk1UaHJlYWQsIGphdmFUaGlzOiBKVk1UeXBlcy5qYXZhX2xhbmdfU3RyaW5nKTogSlZNVHlwZXMuamF2YV9sYW5nX1N0cmluZyB7XG4gICAgcmV0dXJuIHRocmVhZC5nZXRKVk0oKS5pbnRlcm5TdHJpbmcoamF2YVRoaXMudG9TdHJpbmcoKSwgamF2YVRoaXMpO1xuICB9XG5cbn1cblxuY2xhc3MgamF2YV9sYW5nX1N5c3RlbSB7XG5cbiAgcHVibGljIHN0YXRpYyAnc2V0SW4wKExqYXZhL2lvL0lucHV0U3RyZWFtOylWJyh0aHJlYWQ6IEpWTVRocmVhZCwgc3RyZWFtOiBKVk1UeXBlcy5qYXZhX2lvX0lucHV0U3RyZWFtKTogdm9pZCB7XG4gICAgdmFyIHN5cyA9IHV0aWwuZ2V0U3RhdGljRmllbGRzPHR5cGVvZiBKVk1UeXBlcy5qYXZhX2xhbmdfU3lzdGVtPih0aHJlYWQsIHRocmVhZC5nZXRCc0NsKCksICdMamF2YS9sYW5nL1N5c3RlbTsnKTtcbiAgICBzeXNbJ2phdmEvbGFuZy9TeXN0ZW0vaW4nXSA9IHN0cmVhbTtcbiAgfVxuXG4gIHB1YmxpYyBzdGF0aWMgJ3NldE91dDAoTGphdmEvaW8vUHJpbnRTdHJlYW07KVYnKHRocmVhZDogSlZNVGhyZWFkLCBzdHJlYW06IEpWTVR5cGVzLmphdmFfaW9fUHJpbnRTdHJlYW0pOiB2b2lkIHtcbiAgICB2YXIgc3lzID0gdXRpbC5nZXRTdGF0aWNGaWVsZHM8dHlwZW9mIEpWTVR5cGVzLmphdmFfbGFuZ19TeXN0ZW0+KHRocmVhZCwgdGhyZWFkLmdldEJzQ2woKSwgJ0xqYXZhL2xhbmcvU3lzdGVtOycpO1xuICAgIHN5c1snamF2YS9sYW5nL1N5c3RlbS9vdXQnXSA9IHN0cmVhbTtcbiAgfVxuXG4gIHB1YmxpYyBzdGF0aWMgJ3NldEVycjAoTGphdmEvaW8vUHJpbnRTdHJlYW07KVYnKHRocmVhZDogSlZNVGhyZWFkLCBzdHJlYW06IEpWTVR5cGVzLmphdmFfaW9fUHJpbnRTdHJlYW0pOiB2b2lkIHtcbiAgICB2YXIgc3lzID0gdXRpbC5nZXRTdGF0aWNGaWVsZHM8dHlwZW9mIEpWTVR5cGVzLmphdmFfbGFuZ19TeXN0ZW0+KHRocmVhZCwgdGhyZWFkLmdldEJzQ2woKSwgJ0xqYXZhL2xhbmcvU3lzdGVtOycpO1xuICAgIHN5c1snamF2YS9sYW5nL1N5c3RlbS9lcnInXSA9IHN0cmVhbTtcbiAgfVxuXG4gIHB1YmxpYyBzdGF0aWMgJ2N1cnJlbnRUaW1lTWlsbGlzKClKJyh0aHJlYWQ6IEpWTVRocmVhZCk6IExvbmcge1xuICAgIHJldHVybiBMb25nLmZyb21OdW1iZXIoKG5ldyBEYXRlKS5nZXRUaW1lKCkpO1xuICB9XG5cbiAgLyoqXG4gICAqIEB0b2RvIFVzZSBwZXJmb3JtYW5jZS5ub3coKSBpZiBhdmFpbGFibGUuXG4gICAqL1xuICBwdWJsaWMgc3RhdGljICduYW5vVGltZSgpSicodGhyZWFkOiBKVk1UaHJlYWQpOiBMb25nIHtcbiAgICByZXR1cm4gTG9uZy5mcm9tTnVtYmVyKChuZXcgRGF0ZSkuZ2V0VGltZSgpKS5tdWx0aXBseShMb25nLmZyb21OdW1iZXIoMTAwMDAwMCkpO1xuICB9XG5cbiAgcHVibGljIHN0YXRpYyAnYXJyYXljb3B5KExqYXZhL2xhbmcvT2JqZWN0O0lMamF2YS9sYW5nL09iamVjdDtJSSlWJyh0aHJlYWQ6IEpWTVRocmVhZCwgc3JjOiBKVk1UeXBlcy5KVk1BcnJheTxhbnk+LCBzcmNQb3M6IG51bWJlciwgZGVzdDogSlZNVHlwZXMuSlZNQXJyYXk8YW55PiwgZGVzdFBvczogbnVtYmVyLCBsZW5ndGg6IG51bWJlcik6IHZvaWQge1xuICAgIC8vIE5lZWRzIHRvIGJlIGNoZWNrZWQgKmV2ZW4gaWYgbGVuZ3RoIGlzIDAqLlxuICAgIGlmICgoc3JjID09IG51bGwpIHx8IChkZXN0ID09IG51bGwpKSB7XG4gICAgICB0aHJlYWQudGhyb3dOZXdFeGNlcHRpb24oJ0xqYXZhL2xhbmcvTnVsbFBvaW50ZXJFeGNlcHRpb247JywgJ0Nhbm5vdCBjb3B5IHRvL2Zyb20gYSBudWxsIGFycmF5LicpO1xuICAgIH1cbiAgICAvLyBDYW4ndCBkbyB0aGlzIG9uIG5vbi1hcnJheSB0eXBlcy4gTmVlZCB0byBjaGVjayBiZWZvcmUgSSBjaGVjayBib3VuZHMgYmVsb3csIG9yIGVsc2UgSSdsbCBnZXQgYW4gZXhjZXB0aW9uLlxuICAgIGVsc2UgaWYgKCEoc3JjLmdldENsYXNzKCkgaW5zdGFuY2VvZiBBcnJheUNsYXNzRGF0YSkgfHwgIShkZXN0LmdldENsYXNzKCkgaW5zdGFuY2VvZiBBcnJheUNsYXNzRGF0YSkpIHtcbiAgICAgIHRocmVhZC50aHJvd05ld0V4Y2VwdGlvbignTGphdmEvbGFuZy9BcnJheVN0b3JlRXhjZXB0aW9uOycsICdzcmMgYW5kIGRlc3QgYXJndW1lbnRzIG11c3QgYmUgb2YgYXJyYXkgdHlwZS4nKTtcbiAgICB9XG4gICAgLy8gQWxzbyBuZWVkcyB0byBiZSBjaGVja2VkICpldmVuIGlmIGxlbmd0aCBpcyAwKi5cbiAgICBlbHNlIGlmIChzcmNQb3MgPCAwIHx8IChzcmNQb3MgKyBsZW5ndGgpID4gc3JjLmFycmF5Lmxlbmd0aCB8fCBkZXN0UG9zIDwgMCB8fCAoZGVzdFBvcyArIGxlbmd0aCkgPiBkZXN0LmFycmF5Lmxlbmd0aCB8fCBsZW5ndGggPCAwKSB7XG4gICAgICAvLyBTeXN0ZW0uYXJyYXljb3B5IHJlcXVpcmVzIEluZGV4T3V0T2ZCb3VuZHNFeGNlcHRpb24sIGJ1dCBKYXZhIHRocm93cyBhbiBhcnJheSB2YXJpYW50IG9mIHRoZSBleGNlcHRpb24gaW4gcHJhY3RpY2UuXG4gICAgICB0aHJlYWQudGhyb3dOZXdFeGNlcHRpb24oJ0xqYXZhL2xhbmcvQXJyYXlJbmRleE91dE9mQm91bmRzRXhjZXB0aW9uOycsICdUcmllZCB0byB3cml0ZSB0byBhbiBpbGxlZ2FsIGluZGV4IGluIGFuIGFycmF5LicpO1xuICAgIH0gZWxzZSB7XG4gICAgICB2YXIgc3JjQ2xhc3MgPSBzcmMuZ2V0Q2xhc3MoKSwgZGVzdENsYXNzID0gZGVzdC5nZXRDbGFzcygpO1xuICAgICAgLy8gU3BlY2lhbCBjYXNlOyBuZWVkIHRvIGNvcHkgdGhlIHNlY3Rpb24gb2Ygc3JjIHRoYXQgaXMgYmVpbmcgY29waWVkIGludG8gYSB0ZW1wb3JhcnkgYXJyYXkgYmVmb3JlIGFjdHVhbGx5IGRvaW5nIHRoZSBjb3B5LlxuICAgICAgaWYgKHNyYyA9PT0gZGVzdCkge1xuICAgICAgICBzcmMgPSBkZXN0LnNsaWNlKHNyY1Bvcywgc3JjUG9zICsgbGVuZ3RoKVxuICAgICAgICBzcmNQb3MgPSAwO1xuICAgICAgfVxuICAgICAgaWYgKHNyY0NsYXNzLmlzQ2FzdGFibGUoZGVzdENsYXNzKSkge1xuICAgICAgICAvLyBGYXN0IHBhdGhcbiAgICAgICAgdXRpbC5hcnJheWNvcHlOb0NoZWNrKHNyYywgc3JjUG9zLCBkZXN0LCBkZXN0UG9zLCBsZW5ndGgpO1xuICAgICAgfSBlbHNlIHtcbiAgICAgICAgLy8gU2xvdyBwYXRoXG4gICAgICAgIC8vIEFic29sdXRlbHkgY2Fubm90IGRvIHRoaXMgd2hlbiB0d28gZGlmZmVyZW50IHByaW1pdGl2ZSB0eXBlcywgb3IgYSBwcmltaXRpdmUgdHlwZSBhbmQgYSByZWZlcmVuY2UgdHlwZS5cbiAgICAgICAgdmFyIHNyY0NvbXBDbHMgPSBzcmMuZ2V0Q2xhc3MoKS5nZXRDb21wb25lbnRDbGFzcygpLFxuICAgICAgICAgIGRlc3RDb21wQ2xzID0gZGVzdC5nZXRDbGFzcygpLmdldENvbXBvbmVudENsYXNzKCk7XG4gICAgICAgIGlmICgoc3JjQ29tcENscyBpbnN0YW5jZW9mIFByaW1pdGl2ZUNsYXNzRGF0YSkgfHwgKGRlc3RDb21wQ2xzIGluc3RhbmNlb2YgUHJpbWl0aXZlQ2xhc3NEYXRhKSkge1xuICAgICAgICAgIHRocmVhZC50aHJvd05ld0V4Y2VwdGlvbignTGphdmEvbGFuZy9BcnJheVN0b3JlRXhjZXB0aW9uOycsICdJZiBjYWxsaW5nIGFycmF5Y29weSB3aXRoIGEgcHJpbWl0aXZlIGFycmF5LCBib3RoIHNyYyBhbmQgZGVzdCBtdXN0IGJlIG9mIHRoZSBzYW1lIHByaW1pdGl2ZSB0eXBlLicpO1xuICAgICAgICB9IGVsc2Uge1xuICAgICAgICAgIC8vIE11c3QgYmUgdHdvIHJlZmVyZW5jZSB0eXBlcy5cbiAgICAgICAgICB1dGlsLmFycmF5Y29weUNoZWNrKHRocmVhZCwgc3JjLCBzcmNQb3MsIGRlc3QsIGRlc3RQb3MsIGxlbmd0aCk7XG4gICAgICAgIH1cbiAgICAgIH1cbiAgICB9XG4gIH1cblxuICBwdWJsaWMgc3RhdGljICdpZGVudGl0eUhhc2hDb2RlKExqYXZhL2xhbmcvT2JqZWN0OylJJyh0aHJlYWQ6IEpWTVRocmVhZCwgeDogSlZNVHlwZXMuamF2YV9sYW5nX09iamVjdCk6IG51bWJlciB7XG4gICAgaWYgKHggIT0gbnVsbCAmJiB4LnJlZiAhPSBudWxsKSB7XG4gICAgICByZXR1cm4geC5yZWY7XG4gICAgfVxuICAgIHJldHVybiAwO1xuICB9XG5cbiAgcHVibGljIHN0YXRpYyAnaW5pdFByb3BlcnRpZXMoTGphdmEvdXRpbC9Qcm9wZXJ0aWVzOylMamF2YS91dGlsL1Byb3BlcnRpZXM7Jyh0aHJlYWQ6IEpWTVRocmVhZCwgcHJvcHM6IEpWTVR5cGVzLmphdmFfdXRpbF9Qcm9wZXJ0aWVzKTogdm9pZCB7XG4gICAgdmFyIGp2bSA9IHRocmVhZC5nZXRKVk0oKSxcbiAgICAgIHByb3BlcnRpZXMgPSBqdm0uZ2V0U3lzdGVtUHJvcGVydHlOYW1lcygpO1xuICAgIHRocmVhZC5zZXRTdGF0dXMoVGhyZWFkU3RhdHVzLkFTWU5DX1dBSVRJTkcpO1xuICAgIHV0aWwuYXN5bmNGb3JFYWNoKHByb3BlcnRpZXMsIChwcm9wZXJ0eU5hbWU6IHN0cmluZywgbmV4dEl0ZW06IChlcnI/OiBKVk1UeXBlcy5qYXZhX2xhbmdfVGhyb3dhYmxlKSA9PiB2b2lkKSA9PiB7XG4gICAgICB2YXIgcHJvcGVydHlWYWwgPSBqdm0uZ2V0U3lzdGVtUHJvcGVydHkocHJvcGVydHlOYW1lKTtcbiAgICAgIHByb3BzW1wic2V0UHJvcGVydHkoTGphdmEvbGFuZy9TdHJpbmc7TGphdmEvbGFuZy9TdHJpbmc7KUxqYXZhL2xhbmcvT2JqZWN0O1wiXSh0aHJlYWQsIFtqdm0uaW50ZXJuU3RyaW5nKHByb3BlcnR5TmFtZSksIGp2bS5pbnRlcm5TdHJpbmcocHJvcGVydHlWYWwpXSwgbmV4dEl0ZW0pO1xuICAgIH0sIChlcnI/OiBKVk1UeXBlcy5qYXZhX2xhbmdfVGhyb3dhYmxlKSA9PiB7XG4gICAgICBpZiAoZXJyKSB7XG4gICAgICAgIHRocmVhZC50aHJvd0V4Y2VwdGlvbihlcnIpO1xuICAgICAgfSBlbHNlIHtcbiAgICAgICAgdGhyZWFkLmFzeW5jUmV0dXJuKHByb3BzKTtcbiAgICAgIH1cbiAgICB9KTtcbiAgfVxuXG4gIHB1YmxpYyBzdGF0aWMgJ21hcExpYnJhcnlOYW1lKExqYXZhL2xhbmcvU3RyaW5nOylMamF2YS9sYW5nL1N0cmluZzsnKHRocmVhZDogSlZNVGhyZWFkLCBhcmcwOiBKVk1UeXBlcy5qYXZhX2xhbmdfU3RyaW5nKTogSlZNVHlwZXMuamF2YV9sYW5nX1N0cmluZyB7XG4gICAgdGhyZWFkLnRocm93TmV3RXhjZXB0aW9uKCdMamF2YS9sYW5nL1Vuc2F0aXNmaWVkTGlua0Vycm9yOycsICdOYXRpdmUgbWV0aG9kIG5vdCBpbXBsZW1lbnRlZC4nKTtcbiAgICAvLyBTYXRpc2Z5IFR5cGVTY3JpcHQgcmV0dXJuIHR5cGUuXG4gICAgcmV0dXJuIG51bGw7XG4gIH1cblxufVxuXG5jbGFzcyBqYXZhX2xhbmdfVGhyZWFkIHtcblxuICBwdWJsaWMgc3RhdGljICdjdXJyZW50VGhyZWFkKClMamF2YS9sYW5nL1RocmVhZDsnKHRocmVhZDogSlZNVGhyZWFkKTogSlZNVHlwZXMuamF2YV9sYW5nX1RocmVhZCB7XG4gICAgcmV0dXJuIHRocmVhZC5nZXRKVk1PYmplY3QoKTtcbiAgfVxuXG4gIHB1YmxpYyBzdGF0aWMgJ3lpZWxkKClWJyh0aHJlYWQ6IEpWTVRocmVhZCk6IHZvaWQge1xuICAgIC8vIEZvcmNlIHRoZSB0aHJlYWQgc2NoZWR1bGVyIHRvIHBpY2sgYW5vdGhlciB0aHJlYWQgYnkgd2FpdGluZyBmb3IgYSBzaG9ydFxuICAgIC8vIGFtb3VudCBvZiB0aW1lLlxuICAgIHRocmVhZC5zZXRTdGF0dXMoVGhyZWFkU3RhdHVzLkFTWU5DX1dBSVRJTkcpO1xuICAgIHNldEltbWVkaWF0ZSgoKSA9PiB7XG4gICAgICB0aHJlYWQuc2V0U3RhdHVzKFRocmVhZFN0YXR1cy5SVU5OQUJMRSk7XG4gICAgICB0aHJlYWQuYXN5bmNSZXR1cm4oKTtcbiAgICB9KTtcbiAgfVxuXG4gIHB1YmxpYyBzdGF0aWMgJ3NsZWVwKEopVicodGhyZWFkOiBKVk1UaHJlYWQsIG1pbGxpczogTG9uZyk6IHZvaWQge1xuICAgIHZhciBiZWZvcmVNZXRob2QgPSB0aHJlYWQuY3VycmVudE1ldGhvZCgpO1xuICAgIHRocmVhZC5zZXRTdGF0dXMoVGhyZWFkU3RhdHVzLkFTWU5DX1dBSVRJTkcpO1xuICAgIHNldFRpbWVvdXQoKCkgPT4ge1xuICAgICAgLy8gQ2hlY2sgaWYgdGhlIHRocmVhZCB3YXMgaW50ZXJydXB0ZWQgZHVyaW5nIG91ciBzbGVlcC4gSW50ZXJydXB0aW5nXG4gICAgICAvLyBzbGVlcCBjYXVzZXMgYW4gZXhjZXB0aW9uLCBzbyB3ZSBuZWVkIHRvIGlnbm9yZSB0aGUgc2V0VGltZW91dFxuICAgICAgLy8gY2FsbGJhY2sgaW4gdGhpcyBjYXNlLlxuICAgICAgaWYgKGJlZm9yZU1ldGhvZCA9PT0gdGhyZWFkLmN1cnJlbnRNZXRob2QoKSkge1xuICAgICAgICB0aHJlYWQuc2V0U3RhdHVzKFRocmVhZFN0YXR1cy5SVU5OQUJMRSk7XG4gICAgICAgIHRocmVhZC5hc3luY1JldHVybigpO1xuICAgICAgfVxuICAgIH0sIG1pbGxpcy50b051bWJlcigpKTtcbiAgfVxuXG4gIHB1YmxpYyBzdGF0aWMgJ3N0YXJ0MCgpVicodGhyZWFkOiBKVk1UaHJlYWQsIGphdmFUaGlzOiBKVk1UeXBlcy5qYXZhX2xhbmdfVGhyZWFkKTogdm9pZCB7XG4gICAgamF2YVRoaXNbJ3J1bigpViddKGphdmFUaGlzLiR0aHJlYWQsIG51bGwpO1xuICB9XG5cbiAgcHVibGljIHN0YXRpYyAnc2V0TmF0aXZlTmFtZShMamF2YS9sYW5nL1N0cmluZzspVicodGhyZWFkOiBKVk1UaHJlYWQsIGphdmFUaGlzOiBKVk1UeXBlcy5qYXZhX2xhbmdfVGhyZWFkLCBuYW1lOiBKVk1UeXBlcy5qYXZhX2xhbmdfU3RyaW5nKTogdm9pZCB7XG4gICAgLy8gTk9QLiBObyBuZWVkIHRvIGRvIGFueXRoaW5nLlxuICB9XG5cbiAgcHVibGljIHN0YXRpYyAnaXNJbnRlcnJ1cHRlZChaKVonKHRocmVhZDogSlZNVGhyZWFkLCBqYXZhVGhpczogSlZNVHlwZXMuamF2YV9sYW5nX1RocmVhZCwgY2xlYXJGbGFnOiBudW1iZXIpOiBib29sZWFuIHtcbiAgICB2YXIgaXNJbnRlcnJ1cHRlZCA9IGphdmFUaGlzLiR0aHJlYWQuaXNJbnRlcnJ1cHRlZCgpO1xuICAgIGlmIChjbGVhckZsYWcpIHtcbiAgICAgIGphdmFUaGlzLiR0aHJlYWQuc2V0SW50ZXJydXB0ZWQoZmFsc2UpO1xuICAgIH1cbiAgICByZXR1cm4gaXNJbnRlcnJ1cHRlZDtcbiAgfVxuXG4gIHB1YmxpYyBzdGF0aWMgJ2lzQWxpdmUoKVonKHRocmVhZDogSlZNVGhyZWFkLCBqYXZhVGhpczogSlZNVHlwZXMuamF2YV9sYW5nX1RocmVhZCk6IGJvb2xlYW4ge1xuICAgIHZhciBzdGF0ZSA9IGphdmFUaGlzLiR0aHJlYWQuZ2V0U3RhdHVzKCk7XG4gICAgcmV0dXJuIHN0YXRlICE9PSBUaHJlYWRTdGF0dXMuVEVSTUlOQVRFRCAmJiBzdGF0ZSAhPT0gVGhyZWFkU3RhdHVzLk5FVztcbiAgfVxuXG4gIHB1YmxpYyBzdGF0aWMgJ2NvdW50U3RhY2tGcmFtZXMoKUknKHRocmVhZDogSlZNVGhyZWFkLCBqYXZhVGhpczogSlZNVHlwZXMuamF2YV9sYW5nX1RocmVhZCk6IG51bWJlciB7XG4gICAgcmV0dXJuIGphdmFUaGlzLiR0aHJlYWQuZ2V0U3RhY2tUcmFjZSgpLmxlbmd0aDtcbiAgfVxuXG4gIHB1YmxpYyBzdGF0aWMgJ2hvbGRzTG9jayhMamF2YS9sYW5nL09iamVjdDspWicodGhyZWFkOiBKVk1UaHJlYWQsIG9iajogSlZNVHlwZXMuamF2YV9sYW5nX09iamVjdCk6IGJvb2xlYW4ge1xuICAgIHZhciBtb24gPSBvYmouZ2V0TW9uaXRvcigpO1xuICAgIHJldHVybiBtb24uZ2V0T3duZXIoKSA9PT0gdGhyZWFkO1xuICB9XG5cbiAgcHVibGljIHN0YXRpYyAnZHVtcFRocmVhZHMoW0xqYXZhL2xhbmcvVGhyZWFkOylbW0xqYXZhL2xhbmcvU3RhY2tUcmFjZUVsZW1lbnQ7Jyh0aHJlYWQ6IEpWTVRocmVhZCwgYXJnMDogSlZNVHlwZXMuSlZNQXJyYXk8SlZNVHlwZXMuamF2YV9sYW5nX1RocmVhZD4pOiBKVk1UeXBlcy5KVk1BcnJheTxKVk1UeXBlcy5KVk1BcnJheTxKVk1UeXBlcy5qYXZhX2xhbmdfU3RhY2tUcmFjZUVsZW1lbnQ+PiB7XG4gICAgdGhyZWFkLnRocm93TmV3RXhjZXB0aW9uKCdMamF2YS9sYW5nL1Vuc2F0aXNmaWVkTGlua0Vycm9yOycsICdOYXRpdmUgbWV0aG9kIG5vdCBpbXBsZW1lbnRlZC4nKTtcbiAgICAvLyBTYXRpc2Z5IFR5cGVTY3JpcHQgcmV0dXJuIHR5cGUuXG4gICAgcmV0dXJuIG51bGw7XG4gIH1cblxuICBwdWJsaWMgc3RhdGljICdnZXRUaHJlYWRzKClbTGphdmEvbGFuZy9UaHJlYWQ7Jyh0aHJlYWQ6IEpWTVRocmVhZCk6IEpWTVR5cGVzLkpWTUFycmF5PEpWTVR5cGVzLmphdmFfbGFuZ19UaHJlYWQ+IHtcbiAgICByZXR1cm4gdXRpbC5uZXdBcnJheUZyb21EYXRhPEpWTVR5cGVzLmphdmFfbGFuZ19UaHJlYWQ+KHRocmVhZCwgdGhyZWFkLmdldEJzQ2woKSwgJ1tMamF2YS9sYW5nL1RocmVhZDsnLCB0aHJlYWQuZ2V0VGhyZWFkUG9vbCgpLmdldFRocmVhZHMoKS5tYXAoKHRocmVhZDogSlZNVGhyZWFkKSA9PiB0aHJlYWQuZ2V0SlZNT2JqZWN0KCkpKTtcbiAgfVxuXG4gIHB1YmxpYyBzdGF0aWMgJ3NldFByaW9yaXR5MChJKVYnKHRocmVhZDogSlZNVGhyZWFkLCBqYXZhVGhpczogSlZNVHlwZXMuamF2YV9sYW5nX1RocmVhZCwgYXJnMDogbnVtYmVyKTogdm9pZCB7XG4gICAgdGhyZWFkLnNpZ25hbFByaW9yaXR5Q2hhbmdlKCk7XG4gIH1cblxuICBwdWJsaWMgc3RhdGljICdzdG9wMChMamF2YS9sYW5nL09iamVjdDspVicodGhyZWFkOiBKVk1UaHJlYWQsIGphdmFUaGlzOiBKVk1UeXBlcy5qYXZhX2xhbmdfVGhyZWFkLCBhcmcwOiBKVk1UeXBlcy5qYXZhX2xhbmdfT2JqZWN0KTogdm9pZCB7XG4gICAgdGhyZWFkLnRocm93TmV3RXhjZXB0aW9uKCdMamF2YS9sYW5nL1Vuc2F0aXNmaWVkTGlua0Vycm9yOycsICdOYXRpdmUgbWV0aG9kIG5vdCBpbXBsZW1lbnRlZC4nKTtcbiAgfVxuXG4gIHB1YmxpYyBzdGF0aWMgJ3N1c3BlbmQwKClWJyh0aHJlYWQ6IEpWTVRocmVhZCwgamF2YVRoaXM6IEpWTVR5cGVzLmphdmFfbGFuZ19UaHJlYWQpOiB2b2lkIHtcbiAgICB0aHJlYWQudGhyb3dOZXdFeGNlcHRpb24oJ0xqYXZhL2xhbmcvVW5zYXRpc2ZpZWRMaW5rRXJyb3I7JywgJ05hdGl2ZSBtZXRob2Qgbm90IGltcGxlbWVudGVkLicpO1xuICB9XG5cbiAgcHVibGljIHN0YXRpYyAncmVzdW1lMCgpVicodGhyZWFkOiBKVk1UaHJlYWQsIGphdmFUaGlzOiBKVk1UeXBlcy5qYXZhX2xhbmdfVGhyZWFkKTogdm9pZCB7XG4gICAgdGhyZWFkLnRocm93TmV3RXhjZXB0aW9uKCdMamF2YS9sYW5nL1Vuc2F0aXNmaWVkTGlua0Vycm9yOycsICdOYXRpdmUgbWV0aG9kIG5vdCBpbXBsZW1lbnRlZC4nKTtcbiAgfVxuXG4gIC8qKlxuICAgKiBJbnRlcnJ1cHRzIHRoaXMgdGhyZWFkLlxuICAgKlxuICAgKiBVbmxlc3MgdGhlIGN1cnJlbnQgdGhyZWFkIGlzIGludGVycnVwdGluZyBpdHNlbGYsIHdoaWNoIGlzIGFsd2F5c1xuICAgKiBwZXJtaXR0ZWQsIHRoZSBjaGVja0FjY2VzcyBtZXRob2Qgb2YgdGhpcyB0aHJlYWQgaXMgaW52b2tlZCwgd2hpY2ggbWF5XG4gICAqIGNhdXNlIGEgU2VjdXJpdHlFeGNlcHRpb24gdG8gYmUgdGhyb3duLlxuICAgKlxuICAgKiAtIElmIHRoaXMgdGhyZWFkIGlzIGJsb2NrZWQgaW4gYW4gaW52b2NhdGlvbiBvZiB0aGUgT2JqZWN0LndhaXQoKSxcbiAgICogICB3YWl0KGxvbmcpLCBvciBPYmplY3Qud2FpdChsb25nLGludCkgbWV0aG9kcyBvZiB0aGUgT2JqZWN0IGNsYXNzLCBvciBvZlxuICAgKiAgIHRoZSBqb2luKCksIGpvaW4obG9uZyksIGpvaW4obG9uZyxpbnQpLCBzbGVlcChsb25nKSwgb3Igc2xlZXAobG9uZyxpbnQpLFxuICAgKiAgIG1ldGhvZHMgb2YgdGhpcyBjbGFzcywgdGhlbiBpdHMgaW50ZXJydXB0IHN0YXR1cyB3aWxsIGJlIGNsZWFyZWQgYW5kIGl0XG4gICAqICAgd2lsbCByZWNlaXZlIGFuIEludGVycnVwdGVkRXhjZXB0aW9uLlxuICAgKlxuICAgKiAtIElmIHRoaXMgdGhyZWFkIGlzIGJsb2NrZWQgaW4gYW4gSS9PIG9wZXJhdGlvbiB1cG9uIGFuXG4gICAqICAgamF2YS5uaW8uY2hhbm5lbHMuSW50ZXJydXB0aWJsZUNoYW5uZWwgdGhlbiB0aGUgY2hhbm5lbCB3aWxsIGJlIGNsb3NlZCxcbiAgICogICB0aGUgdGhyZWFkJ3MgaW50ZXJydXB0IHN0YXR1cyB3aWxsIGJlIHNldCwgYW5kIHRoZSB0aHJlYWQgd2lsbCByZWNlaXZlIGFcbiAgICogICBqYXZhLm5pby5jaGFubmVscy5DbG9zZWRCeUludGVycnVwdEV4Y2VwdGlvbi5cbiAgICpcbiAgICogLSBJZiB0aGlzIHRocmVhZCBpcyBibG9ja2VkIGluIGEgamF2YS5uaW8uY2hhbm5lbHMuU2VsZWN0b3IgdGhlbiB0aGVcbiAgICogICB0aHJlYWQncyBpbnRlcnJ1cHQgc3RhdHVzIHdpbGwgYmUgc2V0IGFuZCBpdCB3aWxsIHJldHVybiBpbW1lZGlhdGVseSBmcm9tXG4gICAqICAgdGhlIHNlbGVjdGlvbiBvcGVyYXRpb24sIHBvc3NpYmx5IHdpdGggYSBub24temVybyB2YWx1ZSwganVzdCBhcyBpZiB0aGVcbiAgICogICBzZWxlY3RvcidzIGphdmEubmlvLmNoYW5uZWxzLlNlbGVjdG9yLndha2V1cCgpIG1ldGhvZCB3ZXJlIGludm9rZWQuXG4gICAqXG4gICAqIC0gSWYgbm9uZSBvZiB0aGUgcHJldmlvdXMgY29uZGl0aW9ucyBob2xkIHRoZW4gdGhpcyB0aHJlYWQncyBpbnRlcnJ1cHRcbiAgICogICBzdGF0dXMgd2lsbCBiZSBzZXQuXG4gICAqXG4gICAqIEludGVycnVwdGluZyBhIHRocmVhZCB0aGF0IGlzIG5vdCBhbGl2ZSBuZWVkIG5vdCBoYXZlIGFueSBlZmZlY3QuXG4gICAqL1xuICBwdWJsaWMgc3RhdGljICdpbnRlcnJ1cHQwKClWJyh0aHJlYWQ6IEpWTVRocmVhZCwgamF2YVRoaXM6IEpWTVR5cGVzLmphdmFfbGFuZ19UaHJlYWQpOiB2b2lkIHtcbiAgICBmdW5jdGlvbiB0aHJvd0ludGVycnVwdGVkRXhjZXB0aW9uKCkge1xuICAgICAgamF2YVRoaXMuJHRocmVhZC50aHJvd05ld0V4Y2VwdGlvbignTGphdmEvbGFuZy9JbnRlcnJ1cHRlZEV4Y2VwdGlvbjsnLCAnaW50ZXJydXB0MCBjYWxsZWQnKTtcbiAgICB9XG5cbiAgICB2YXIgbmF0aXZlVGhyZWFkT2JqID0gamF2YVRoaXMuJHRocmVhZDtcbiAgICAvLyBTZWUgaWYgd2UgaGF2ZSBhY2Nlc3MgdG8gbW9kaWZ5IHRoaXMgdGhyZWFkLlxuICAgIGphdmFUaGlzWydjaGVja0FjY2VzcygpViddKHRocmVhZCwgbnVsbCwgKGU/OiBKVk1UeXBlcy5qYXZhX2xhbmdfVGhyb3dhYmxlKSA9PiB7XG4gICAgICBpZiAoZSkge1xuICAgICAgICAvLyBTZWN1cml0eUV4Y2VwdGlvbi4gUmV0aHJvdyBpdC5cbiAgICAgICAgdGhyZWFkLnRocm93RXhjZXB0aW9uKGUpO1xuICAgICAgfSBlbHNlIHtcbiAgICAgICAgLy8gQ2hlY2sgaWYgdGhyZWFkIGlzIGFsaXZlLlxuICAgICAgICB2YXIgc3RhdHVzID0gbmF0aXZlVGhyZWFkT2JqLmdldFN0YXR1cygpO1xuICAgICAgICBzd2l0Y2ggKHN0YXR1cykge1xuICAgICAgICAgIGNhc2UgVGhyZWFkU3RhdHVzLk5FVzpcbiAgICAgICAgICBjYXNlIFRocmVhZFN0YXR1cy5URVJNSU5BVEVEOlxuICAgICAgICAgICAgLy8gVGhyZWFkIGlzIG5vdCBhbGl2ZS4gTk9QLlxuICAgICAgICAgICAgcmV0dXJuIHRocmVhZC5hc3luY1JldHVybigpO1xuICAgICAgICAgIGNhc2UgVGhyZWFkU3RhdHVzLkJMT0NLRUQ6XG4gICAgICAgICAgY2FzZSBUaHJlYWRTdGF0dXMuV0FJVElORzpcbiAgICAgICAgICBjYXNlIFRocmVhZFN0YXR1cy5USU1FRF9XQUlUSU5HOlxuICAgICAgICAgICAgLy8gVGhyZWFkIGlzIHdhaXRpbmcgb3IgYmxvY2tlZCBvbiBhIG1vbml0b3IuIENsZWFyIGludGVycnVwdGVkXG4gICAgICAgICAgICAvLyBzdGF0dXMsIGFuZCB0aHJvdyBhbiBpbnRlcnJ1cHRlZCBleGNlcHRpb24uXG4gICAgICAgICAgICBuYXRpdmVUaHJlYWRPYmouc2V0SW50ZXJydXB0ZWQoZmFsc2UpO1xuICAgICAgICAgICAgLy8gRXhpdCB0aGUgbW9uaXRvci5cbiAgICAgICAgICAgIHZhciBtb25pdG9yID0gbmF0aXZlVGhyZWFkT2JqLmdldE1vbml0b3JCbG9jaygpO1xuICAgICAgICAgICAgaWYgKHN0YXR1cyA9PT0gVGhyZWFkU3RhdHVzLkJMT0NLRUQpIHtcbiAgICAgICAgICAgICAgbW9uaXRvci51bmJsb2NrKG5hdGl2ZVRocmVhZE9iaiwgdHJ1ZSk7XG4gICAgICAgICAgICAgIHRocm93SW50ZXJydXB0ZWRFeGNlcHRpb24oKTtcbiAgICAgICAgICAgIH0gZWxzZSB7XG4gICAgICAgICAgICAgIG1vbml0b3IudW53YWl0KG5hdGl2ZVRocmVhZE9iaiwgZmFsc2UsIHRydWUsIHRocm93SW50ZXJydXB0ZWRFeGNlcHRpb24pO1xuICAgICAgICAgICAgfVxuICAgICAgICAgICAgcmV0dXJuIHRocmVhZC5hc3luY1JldHVybigpO1xuICAgICAgICAgIGNhc2UgVGhyZWFkU3RhdHVzLlBBUktFRDpcbiAgICAgICAgICAgIC8vIFBhcmtlZCB0aHJlYWRzIGJlY29tZSB1bnBhcmtlZCB3aGVuIGludGVycnVwdGVkLlxuICAgICAgICAgICAgdGhyZWFkLmdldEpWTSgpLmdldFBhcmtlcigpLmNvbXBsZXRlbHlVbnBhcmsobmF0aXZlVGhyZWFkT2JqKTtcbiAgICAgICAgICAgIC8vIEZBTEwtVEhST1VHSFxuICAgICAgICAgIGRlZmF1bHQ6XG4gICAgICAgICAgICB2YXIgdGhyZWFkQ2xzID0gPFJlZmVyZW5jZUNsYXNzRGF0YTxKVk1UeXBlcy5qYXZhX2xhbmdfVGhyZWFkPj4gdGhyZWFkLmdldEJzQ2woKS5nZXRJbml0aWFsaXplZENsYXNzKHRocmVhZCwgJ0xqYXZhL2xhbmcvVGhyZWFkOycpLFxuICAgICAgICAgICAgICAvLyBJZiB3ZSBhcmUgaW4gdGhlIGZvbGxvd2luZyBtZXRob2RzLCB3ZSB0aHJvdyBhbiBJbnRlcnJ1cHRlZEV4Y2VwdGlvbjpcbiAgICAgICAgICAgICAgaW50ZXJydXB0TWV0aG9kczogTWV0aG9kW10gPSBbXG4gICAgICAgICAgICAgICAgdGhyZWFkQ2xzLm1ldGhvZExvb2t1cCgnam9pbigpVicpLCAgIC8vICogVGhyZWFkLmpvaW4oKVxuICAgICAgICAgICAgICAgIHRocmVhZENscy5tZXRob2RMb29rdXAoJ2pvaW4oSilWJyksICAvLyAqIFRocmVhZC5qb2luKGxvbmcpXG4gICAgICAgICAgICAgICAgdGhyZWFkQ2xzLm1ldGhvZExvb2t1cCgnam9pbihKSSlWJyksIC8vICogVGhyZWFkLmpvaW4obG9uZywgaW50KVxuICAgICAgICAgICAgICAgIHRocmVhZENscy5tZXRob2RMb29rdXAoJ3NsZWVwKEopVicpLCAvLyAqIFRocmVhZC5zbGVlcChsb25nKVxuICAgICAgICAgICAgICAgIHRocmVhZENscy5tZXRob2RMb29rdXAoJ3NsZWVwKEpJKVYnKSAvLyAqIFRocmVhZC5zbGVlcChsb25nLCBpbnQpXG4gICAgICAgICAgICAgIF0sXG4gICAgICAgICAgICAgIHN0YWNrVHJhY2UgPSBuYXRpdmVUaHJlYWRPYmouZ2V0U3RhY2tUcmFjZSgpLFxuICAgICAgICAgICAgICBjdXJyZW50TWV0aG9kID0gc3RhY2tUcmFjZVtzdGFja1RyYWNlLmxlbmd0aCAtIDFdLm1ldGhvZDtcbiAgICAgICAgICAgIGlmIChpbnRlcnJ1cHRNZXRob2RzLmluZGV4T2YoY3VycmVudE1ldGhvZCkgIT09IC0xKSB7XG4gICAgICAgICAgICAgIC8vIENsZWFyIGludGVycnVwdCBzdGF0ZSBiZWZvcmUgdGhyb3dpbmcgdGhlIGV4Y2VwdGlvbi5cbiAgICAgICAgICAgICAgbmF0aXZlVGhyZWFkT2JqLnNldEludGVycnVwdGVkKGZhbHNlKTtcbiAgICAgICAgICAgICAgbmF0aXZlVGhyZWFkT2JqLnRocm93TmV3RXhjZXB0aW9uKCdMamF2YS9sYW5nL0ludGVycnVwdGVkRXhjZXB0aW9uOycsICdpbnRlcnJ1cHQwIGNhbGxlZCcpO1xuICAgICAgICAgICAgfSBlbHNlIHtcbiAgICAgICAgICAgICAgLy8gU2V0IHRoZSBpbnRlcnJ1cHRlZCBzdGF0dXMuXG4gICAgICAgICAgICAgIG5hdGl2ZVRocmVhZE9iai5zZXRJbnRlcnJ1cHRlZCh0cnVlKTtcbiAgICAgICAgICAgIH1cbiAgICAgICAgICAgIHJldHVybiB0aHJlYWQuYXN5bmNSZXR1cm4oKTtcbiAgICAgICAgfVxuICAgICAgfVxuICAgIH0pO1xuICB9XG5cbn1cblxuY2xhc3MgamF2YV9sYW5nX1Rocm93YWJsZSB7XG5cbiAgLyoqXG4gICAqIE5PVEU6IEludGVnZXIgaXMgb25seSB0aGVyZSB0byBkaXN0aW5ndWlzaCB0aGlzIGZ1bmN0aW9uIGZyb20gbm9uLW5hdGl2ZSBmaWxsSW5TdGFja1RyYWNlKClWLlxuICAgKi9cbiAgcHVibGljIHN0YXRpYyAnZmlsbEluU3RhY2tUcmFjZShJKUxqYXZhL2xhbmcvVGhyb3dhYmxlOycodGhyZWFkOiBKVk1UaHJlYWQsIGphdmFUaGlzOiBKVk1UeXBlcy5qYXZhX2xhbmdfVGhyb3dhYmxlLCBkdW1teTogbnVtYmVyKTogSlZNVHlwZXMuamF2YV9sYW5nX1Rocm93YWJsZSB7XG4gICAgdmFyIHN0YWNrVHJhY2VFbGVtZW50Q2xzID0gPFJlZmVyZW5jZUNsYXNzRGF0YTxKVk1UeXBlcy5qYXZhX2xhbmdfU3RhY2tUcmFjZUVsZW1lbnQ+PiB0aHJlYWQuZ2V0QnNDbCgpLmdldEluaXRpYWxpemVkQ2xhc3ModGhyZWFkLCAnTGphdmEvbGFuZy9TdGFja1RyYWNlRWxlbWVudDsnKSxcbiAgICAgIHN0YWNrdHJhY2UgPSB1dGlsLm5ld0FycmF5PEpWTVR5cGVzLmphdmFfbGFuZ19TdGFja1RyYWNlRWxlbWVudD4odGhyZWFkLCB0aHJlYWQuZ2V0QnNDbCgpLCAnW0xqYXZhL2xhbmcvU3RhY2tUcmFjZUVsZW1lbnQ7JywgMCksXG4gICAgICBjc3RhY2sgPSB0aHJlYWQuZ2V0U3RhY2tUcmFjZSgpLFxuICAgICAgaTogbnVtYmVyLCBqOiBudW1iZXIsIGJzQ2wgPSB0aHJlYWQuZ2V0QnNDbCgpO1xuICAgIC8qKlxuICAgICAqIE9LLCBzbyB3ZSBuZWVkIHRvIHRvc3MgdGhlIGZvbGxvd2luZyBzdGFjayBmcmFtZXM6XG4gICAgICogLSBUaGUgc3RhY2sgZnJhbWUgZm9yIHRoaXMgbWV0aG9kLlxuICAgICAqIC0gSWYgd2UncmUgc3RpbGwgY29uc3RydWN0aW5nIHRoZSB0aHJvd2FibGUgb2JqZWN0LCB3ZSBuZWVkIHRvIHRvc3MgYW55XG4gICAgICogICBzdGFjayBmcmFtZXMgaW52b2x2ZWQgaW4gY29uc3RydWN0aW5nIHRoZSB0aHJvd2FibGUuIEJ1dCBpZiB3ZSdyZSBub3QsXG4gICAgICogICB0aGVuIHRoZXJlJ3Mgbm8gb3RoZXIgZnJhbWVzIHdlIHNob3VsZCBjdXQuXG4gICAgICovXG4gICAgY3N0YWNrLnBvcCgpOyAvLyBUaGUgc3RhY2sgZnJhbWUgZm9yIHRoaXMgbWV0aG9kLlxuICAgIC8vIEJ5dGVjb2RlIG1ldGhvZHMgaW52b2x2ZWQgaW4gY29uc3RydWN0aW5nIHRoZSB0aHJvd2FibGUuIFdlIGFzc3VtZSB0aGF0XG4gICAgLy8gdGhlcmUgYXJlIG5vIG5hdGl2ZSBtZXRob2RzIGludm9sdmVkIGluIHRoZSBtaXggb3RoZXIgdGhhbiB0aGlzIG9uZS5cbiAgICB3aGlsZSAoY3N0YWNrLmxlbmd0aCA+IDAgJiZcbiAgICAgICFjc3RhY2tbY3N0YWNrLmxlbmd0aCAtIDFdLm1ldGhvZC5hY2Nlc3NGbGFncy5pc05hdGl2ZSgpICYmXG4gICAgICBjc3RhY2tbY3N0YWNrLmxlbmd0aCAtIDFdLmxvY2Fsc1swXSA9PT0gamF2YVRoaXMpIHtcbiAgICAgIGNzdGFjay5wb3AoKTtcbiAgICB9XG5cbiAgICAvLyBDb25zdHJ1Y3QgdGhlIHN0YWNrIHN1Y2ggdGhhdCB0aGUgbWV0aG9kIG9uIHRvcCBvZiB0aGUgc3RhY2sgaXMgYXQgaW5kZXhcbiAgICAvLyAwLlxuICAgIGZvciAoaSA9IGNzdGFjay5sZW5ndGggLSAxOyBpID49IDA7IGktLSkge1xuICAgICAgdmFyIHNmID0gY3N0YWNrW2ldLFxuICAgICAgICBjbHMgPSBzZi5tZXRob2QuY2xzLFxuICAgICAgICBsbiA9IC0xLFxuICAgICAgICBzb3VyY2VGaWxlOiBzdHJpbmc7XG4gICAgICAvLyBKYXZhIDg6IElnbm9yZSAnSGlkZGVuJyBtZXRob2RzLiBUaGVzZSBhcmUgaW52b2x2ZWQgaW4gY29uc3RydWN0aW5nXG4gICAgICAvLyBMYW1iZGFzLCBhbmQgc2hvdWxkbid0IGJlIHVzZS12aXNpYmxlLlxuICAgICAgaWYgKHNmLm1ldGhvZC5pc0hpZGRlbigpKSB7XG4gICAgICAgIGNvbnRpbnVlO1xuICAgICAgfVxuXG4gICAgICBpZiAoc2YubWV0aG9kLmFjY2Vzc0ZsYWdzLmlzTmF0aXZlKCkpIHtcbiAgICAgICAgc291cmNlRmlsZSA9ICdOYXRpdmUgTWV0aG9kJztcbiAgICAgIH0gZWxzZSB7XG4gICAgICAgIHZhciBzcmNBdHRyID0gPGF0dHJpYnV0ZXMuU291cmNlRmlsZT4gY2xzLmdldEF0dHJpYnV0ZSgnU291cmNlRmlsZScpLFxuICAgICAgICAgIGNvZGUgPSBzZi5tZXRob2QuZ2V0Q29kZUF0dHJpYnV0ZSgpLFxuICAgICAgICAgIHRhYmxlID0gPGF0dHJpYnV0ZXMuTGluZU51bWJlclRhYmxlPiBjb2RlLmdldEF0dHJpYnV0ZSgnTGluZU51bWJlclRhYmxlJyk7XG4gICAgICAgIHNvdXJjZUZpbGUgPSAoc3JjQXR0ciAhPSBudWxsKSA/IHNyY0F0dHIuZmlsZW5hbWUgOiAndW5rbm93bic7XG5cbiAgICAgICAgaWYgKHRhYmxlICE9IG51bGwpIHtcbiAgICAgICAgICBsbiA9IHRhYmxlLmdldExpbmVOdW1iZXIoc2YucGMpO1xuICAgICAgICB9IGVsc2Uge1xuICAgICAgICAgIGxuID0gLTE7XG4gICAgICAgIH1cbiAgICAgIH1cblxuICAgICAgdmFyIG5ld0VsZW1lbnQgPSB1dGlsLm5ld09iamVjdEZyb21DbGFzczxKVk1UeXBlcy5qYXZhX2xhbmdfU3RhY2tUcmFjZUVsZW1lbnQ+KHRocmVhZCwgc3RhY2tUcmFjZUVsZW1lbnRDbHMpO1xuICAgICAgbmV3RWxlbWVudFsnamF2YS9sYW5nL1N0YWNrVHJhY2VFbGVtZW50L2RlY2xhcmluZ0NsYXNzJ10gPSB1dGlsLmluaXRTdHJpbmcoYnNDbCwgdXRpbC5leHRfY2xhc3NuYW1lKGNscy5nZXRJbnRlcm5hbE5hbWUoKSkpO1xuICAgICAgbmV3RWxlbWVudFsnamF2YS9sYW5nL1N0YWNrVHJhY2VFbGVtZW50L21ldGhvZE5hbWUnXSA9IHV0aWwuaW5pdFN0cmluZyhic0NsLCBzZi5tZXRob2QubmFtZSAhPSBudWxsID8gc2YubWV0aG9kLm5hbWUgOiAndW5rbm93bicpO1xuICAgICAgbmV3RWxlbWVudFsnamF2YS9sYW5nL1N0YWNrVHJhY2VFbGVtZW50L2ZpbGVOYW1lJ10gPSB1dGlsLmluaXRTdHJpbmcoYnNDbCwgc291cmNlRmlsZSk7XG4gICAgICBuZXdFbGVtZW50WydqYXZhL2xhbmcvU3RhY2tUcmFjZUVsZW1lbnQvbGluZU51bWJlciddID0gbG47XG4gICAgICBzdGFja3RyYWNlLmFycmF5LnB1c2gobmV3RWxlbWVudCk7XG4gICAgfVxuICAgIGphdmFUaGlzWydqYXZhL2xhbmcvVGhyb3dhYmxlL2JhY2t0cmFjZSddID0gc3RhY2t0cmFjZTtcbiAgICByZXR1cm4gamF2YVRoaXM7XG4gIH1cblxuICBwdWJsaWMgc3RhdGljICdnZXRTdGFja1RyYWNlRGVwdGgoKUknKHRocmVhZDogSlZNVGhyZWFkLCBqYXZhVGhpczogSlZNVHlwZXMuamF2YV9sYW5nX1Rocm93YWJsZSk6IG51bWJlciB7XG4gICAgLy8gJ2JhY2t0cmFjZScgaXMgdHlwZWQgYXMgYW4gT2JqZWN0IHNvIEpWTXMgaGF2ZSBmbGV4aWJpbGl0eSBpbiB3aGF0IHRvIHN0b3JlIHRoZXJlLlxuICAgIC8vIFdlIHNpbXBseSBzdG9yZSB0aGUgc3RhY2sgdHJhY2UgZWxlbWVudCBhcnJheS5cbiAgICByZXR1cm4gKDxKVk1UeXBlcy5KVk1BcnJheTxKVk1UeXBlcy5qYXZhX2xhbmdfU3RhY2tUcmFjZUVsZW1lbnQ+PiBqYXZhVGhpc1snamF2YS9sYW5nL1Rocm93YWJsZS9iYWNrdHJhY2UnXSkuYXJyYXkubGVuZ3RoO1xuICB9XG5cbiAgcHVibGljIHN0YXRpYyAnZ2V0U3RhY2tUcmFjZUVsZW1lbnQoSSlMamF2YS9sYW5nL1N0YWNrVHJhY2VFbGVtZW50OycodGhyZWFkOiBKVk1UaHJlYWQsIGphdmFUaGlzOiBKVk1UeXBlcy5qYXZhX2xhbmdfVGhyb3dhYmxlLCBkZXB0aDogbnVtYmVyKTogSlZNVHlwZXMuamF2YV9sYW5nX1N0YWNrVHJhY2VFbGVtZW50IHtcbiAgICByZXR1cm4gKDxKVk1UeXBlcy5KVk1BcnJheTxKVk1UeXBlcy5qYXZhX2xhbmdfU3RhY2tUcmFjZUVsZW1lbnQ+PiBqYXZhVGhpc1snamF2YS9sYW5nL1Rocm93YWJsZS9iYWNrdHJhY2UnXSkuYXJyYXlbZGVwdGhdO1xuICB9XG5cbn1cblxuY2xhc3MgamF2YV9sYW5nX1VOSVhQcm9jZXNzIHtcblxuICBwdWJsaWMgc3RhdGljICd3YWl0Rm9yUHJvY2Vzc0V4aXQoSSlJJyh0aHJlYWQ6IEpWTVRocmVhZCwgamF2YVRoaXM6IEpWTVR5cGVzLmphdmFfbGFuZ19VTklYUHJvY2VzcywgYXJnMDogbnVtYmVyKTogbnVtYmVyIHtcbiAgICB0aHJlYWQudGhyb3dOZXdFeGNlcHRpb24oJ0xqYXZhL2xhbmcvVW5zYXRpc2ZpZWRMaW5rRXJyb3I7JywgJ05hdGl2ZSBtZXRob2Qgbm90IGltcGxlbWVudGVkLicpO1xuICAgIC8vIFNhdGlzZnkgVHlwZVNjcmlwdCByZXR1cm4gdHlwZS5cbiAgICByZXR1cm4gMDtcbiAgfVxuXG4gIHB1YmxpYyBzdGF0aWMgJ2ZvcmtBbmRFeGVjKElbQltCW0JJW0JJW0JbSVopSScodGhyZWFkOiBKVk1UaHJlYWQsIGphdmFUaGlzOiBKVk1UeXBlcy5qYXZhX2xhbmdfVU5JWFByb2Nlc3MpOiB2b2lkIHtcbiAgICB0aHJlYWQudGhyb3dOZXdFeGNlcHRpb24oJ0xqYXZhL2xhbmcvRXJyb3I7JywgXCJEb3BwaW8gZG9lc24ndCBzdXBwb3J0IGZvcmtpbmcgcHJvY2Vzc2VzLlwiKTtcbiAgfVxuXG4gIHB1YmxpYyBzdGF0aWMgJ2Rlc3Ryb3lQcm9jZXNzKElaKVYnKHRocmVhZDogSlZNVGhyZWFkLCBhcmcwOiBudW1iZXIpOiB2b2lkIHtcbiAgICB0aHJlYWQudGhyb3dOZXdFeGNlcHRpb24oJ0xqYXZhL2xhbmcvVW5zYXRpc2ZpZWRMaW5rRXJyb3I7JywgJ05hdGl2ZSBtZXRob2Qgbm90IGltcGxlbWVudGVkLicpO1xuICB9XG5cbiAgcHVibGljIHN0YXRpYyAnaW5pdCgpVicodGhyZWFkOiBKVk1UaHJlYWQpOiB2b2lkIHtcbiAgICB0aHJlYWQudGhyb3dOZXdFeGNlcHRpb24oJ0xqYXZhL2xhbmcvVW5zYXRpc2ZpZWRMaW5rRXJyb3I7JywgJ05hdGl2ZSBtZXRob2Qgbm90IGltcGxlbWVudGVkLicpO1xuICB9XG5cbn1cblxuLyoqXG4gKiBNaXNjLiBNZW1iZXJOYW1lLXNwZWNpZmljIGNvbnN0YW50cywgZW51bSdkIHNvIHRoZXkgZ2V0IGlubGluZWQuXG4gKi9cbmVudW0gTWVtYmVyTmFtZUNvbnN0YW50cyB7XG4gIC8qIEJpdCBtYXNrcyBmb3IgRkxBR1MgZm9yIHBhcnRpY3VsYXIgdHlwZXMgKi9cbiAgSVNfTUVUSE9EICAgICAgICAgICA9IDB4MDAwMTAwMDAsIC8vIG1ldGhvZCAobm90IGNvbnN0cnVjdG9yKVxuICBJU19DT05TVFJVQ1RPUiAgICAgID0gMHgwMDAyMDAwMCwgLy8gY29uc3RydWN0b3JcbiAgSVNfRklFTEQgICAgICAgICAgICA9IDB4MDAwNDAwMDAsIC8vIGZpZWxkXG4gIElTX1RZUEUgICAgICAgICAgICAgPSAweDAwMDgwMDAwLCAvLyBuZXN0ZWQgdHlwZVxuICBDQUxMRVJfU0VOU0lUSVZFICAgID0gMHgwMDEwMDAwMCwgLy8gQENhbGxlclNlbnNpdGl2ZSBhbm5vdGF0aW9uIGRldGVjdGVkXG4gIC8qIFBhc3NlZCBpbiBpbiBtYXRjaEZsYWdzIGFyZ3VtZW50IG9mIE1ITi5nZXRNZW1iZXJzICovXG4gIFNFQVJDSF9TVVBFUkNMQVNTRVMgPSAweDAwMTAwMDAwLFxuICBTRUFSQ0hfSU5URVJGQUNFUyAgID0gMHgwMDIwMDAwMCxcbiAgLyogTnVtYmVyIG9mIGJpdHMgdG8gc2hpZnQgb3ZlciB0aGUgcmVmZXJlbmNlIGtpbmQgaW50byB0aGUgTU4ncyBmbGFncy4gKi9cbiAgUkVGRVJFTkNFX0tJTkRfU0hJRlQgPSAyNCxcbiAgLyogTWFzayB0byBleHRyYWN0IG1lbWJlciB0eXBlLiAqL1xuICBBTExfS0lORFMgPSAoSVNfTUVUSE9EIHwgSVNfQ09OU1RSVUNUT1IgfCBJU19GSUVMRCB8IElTX1RZUEUpXG59XG5cbi8qKlxuICogR2l2ZW4gYSBNZW1iZXJOYW1lIG9iamVjdCBhbmQgYSByZWZsZWN0aXZlIGZpZWxkL21ldGhvZC9jb25zdHJ1Y3RvcixcbiAqIGluaXRpYWxpemVzIHRoZSBtZW1iZXIgbmFtZTpcbiAqIC0gbmFtZTogTmFtZSBvZiB0aGUgZmllbGQvbWV0aG9kLlxuICogLSBjbGF6ejogUmVmZXJlbmNlZCBjbGFzcyB0aGF0IGNvbnRhaW5zIHRoZSBtZXRob2QuXG4gKiAtIGZsYWdzOiBFbmNvZGVzIHRoZSByZWZlcmVuY2UgdHlwZSBvZiB0aGUgbWVtYmVyIGFuZCB0aGUgbWVtYmVyJ3MgYWNjZXNzIGZsYWdzLlxuICogLSB0eXBlOiBTdHJpbmcgZW5jb2Rpbmcgb2YgdGhlIHR5cGUgKG1ldGhvZCBkZXNjcmlwdG9yLCBvciBjbGFzcyBuYW1lIG9mIGZpZWxkIHR5cGUgaW4gZGVzY3JpcHRvciBmb3JtKVxuICogLSB2bXRhcmdldDogQ29udGFpbnMgdGhlIFZNLXNwZWNpZmljIHBvaW50ZXIgdG8gdGhlIG1lbWJlciAoaW4gb3VyIGNhc2UsIGEgTWV0aG9kIG9yIEZpZWxkIG9iamVjdClcbiAqIChzZXQgY2xhenosIHVwZGF0ZXMgZmxhZ3MsIHNldHMgdm10YXJnZXQpLlxuICovXG5mdW5jdGlvbiBpbml0aWFsaXplTWVtYmVyTmFtZSh0aHJlYWQ6IEpWTVRocmVhZCwgbW46IEpWTVR5cGVzLmphdmFfbGFuZ19pbnZva2VfTWVtYmVyTmFtZSwgcmVmOiBBYnN0cmFjdE1ldGhvZEZpZWxkKSB7XG4gIHZhciBmbGFncyA9IG1uWydqYXZhL2xhbmcvaW52b2tlL01lbWJlck5hbWUvZmxhZ3MnXSxcbiAgICB0eXBlID0gbW5bJ2phdmEvbGFuZy9pbnZva2UvTWVtYmVyTmFtZS90eXBlJ10sXG4gICAgbmFtZSA9IG1uWydqYXZhL2xhbmcvaW52b2tlL01lbWJlck5hbWUvbmFtZSddLFxuICAgIHJlZktpbmQ6IG51bWJlcixcbiAgICBleGlzdGluZ1JlZktpbmQgPSBmbGFncyA+Pj4gTWVtYmVyTmFtZUNvbnN0YW50cy5SRUZFUkVOQ0VfS0lORF9TSElGVDtcblxuICAvLyBEZXRlcm1pbmUgdGhlIHJlZmVyZW5jZSB0eXBlLlxuICBpZiAocmVmIGluc3RhbmNlb2YgTWV0aG9kKSB7XG4gICAgIGZsYWdzID0gTWVtYmVyTmFtZUNvbnN0YW50cy5JU19NRVRIT0Q7XG4gICAgIGlmIChyZWYuY2xzLmFjY2Vzc0ZsYWdzLmlzSW50ZXJmYWNlKCkpIHtcbiAgICAgICByZWZLaW5kID0gTWV0aG9kSGFuZGxlUmVmZXJlbmNlS2luZC5JTlZPS0VJTlRFUkZBQ0U7XG4gICAgIH0gZWxzZSBpZiAocmVmLmFjY2Vzc0ZsYWdzLmlzU3RhdGljKCkpIHtcbiAgICAgICByZWZLaW5kID0gTWV0aG9kSGFuZGxlUmVmZXJlbmNlS2luZC5JTlZPS0VTVEFUSUM7XG4gICAgIH0gZWxzZSBpZiAocmVmLm5hbWVbMF0gPT09ICc8Jykge1xuICAgICAgIGZsYWdzID0gTWVtYmVyTmFtZUNvbnN0YW50cy5JU19DT05TVFJVQ1RPUjtcbiAgICAgICByZWZLaW5kID0gTWV0aG9kSGFuZGxlUmVmZXJlbmNlS2luZC5JTlZPS0VTUEVDSUFMO1xuICAgICB9IGVsc2Uge1xuICAgICAgIHJlZktpbmQgPSBNZXRob2RIYW5kbGVSZWZlcmVuY2VLaW5kLklOVk9LRVZJUlRVQUw7XG4gICAgIH1cbiAgICAgbW4udm10YXJnZXQgPSByZWYuZ2V0Vk1UYXJnZXRCcmlkZ2VNZXRob2QodGhyZWFkLCBleGlzdGluZ1JlZktpbmQgPyBleGlzdGluZ1JlZktpbmQgOiByZWZLaW5kKTtcbiAgICAgaWYgKHJlZktpbmQgPT09IE1ldGhvZEhhbmRsZVJlZmVyZW5jZUtpbmQuSU5WT0tFSU5URVJGQUNFIHx8XG4gICAgICAgcmVmS2luZCA9PT0gTWV0aG9kSGFuZGxlUmVmZXJlbmNlS2luZC5JTlZPS0VWSVJUVUFMKSB7XG4gICAgICAgbW4udm1pbmRleCA9IHJlZi5jbHMuZ2V0Vk1JbmRleEZvck1ldGhvZChyZWYpO1xuICAgICB9XG4gICAgIGZsYWdzIHw9IChyZWZLaW5kIDw8IE1lbWJlck5hbWVDb25zdGFudHMuUkVGRVJFTkNFX0tJTkRfU0hJRlQpIHwgbWV0aG9kRmxhZ3MocmVmKTtcbiAgfSBlbHNlIHtcbiAgICBmbGFncyA9IE1lbWJlck5hbWVDb25zdGFudHMuSVNfRklFTEQ7XG4gICAgLy8gQXNzdW1lIGEgR0VULlxuICAgIGlmIChyZWYuYWNjZXNzRmxhZ3MuaXNTdGF0aWMoKSkge1xuICAgICAgcmVmS2luZCA9IE1ldGhvZEhhbmRsZVJlZmVyZW5jZUtpbmQuR0VUU1RBVElDO1xuICAgIH0gZWxzZSB7XG4gICAgICByZWZLaW5kID0gTWV0aG9kSGFuZGxlUmVmZXJlbmNlS2luZC5HRVRGSUVMRDtcbiAgICB9XG4gICAgbW4udm1pbmRleCA9IHJlZi5jbHMuZ2V0Vk1JbmRleEZvckZpZWxkKDxGaWVsZD4gcmVmKTtcbiAgICBmbGFncyB8PSAocmVmS2luZCA8PCBNZW1iZXJOYW1lQ29uc3RhbnRzLlJFRkVSRU5DRV9LSU5EX1NISUZUKSB8IHJlZi5hY2Nlc3NGbGFncy5nZXRSYXdCeXRlKCk7XG4gIH1cbiAgLy8gSW5pdGlhbGl6ZSB0eXBlIGlmIHdlIG5lZWQgdG8uXG4gIGlmICh0eXBlID09PSBudWxsKSB7XG4gICAgdHlwZSA9IHRocmVhZC5nZXRKVk0oKS5pbnRlcm5TdHJpbmcocmVmLnJhd0Rlc2NyaXB0b3IpO1xuICB9XG4gIC8vIEluaXRpYWxpemUgbmFtZSBpZiB3ZSBuZWVkIHRvLlxuICBpZiAobmFtZSA9PT0gbnVsbCkge1xuICAgIG5hbWUgPSB0aHJlYWQuZ2V0SlZNKCkuaW50ZXJuU3RyaW5nKHJlZi5uYW1lKTtcbiAgfVxuICBtblsnamF2YS9sYW5nL2ludm9rZS9NZW1iZXJOYW1lL2NsYXp6J10gPSByZWYuY2xzLmdldENsYXNzT2JqZWN0KHRocmVhZCk7XG4gIG1uWydqYXZhL2xhbmcvaW52b2tlL01lbWJlck5hbWUvZmxhZ3MnXSA9IGZsYWdzO1xuICBtblsnamF2YS9sYW5nL2ludm9rZS9NZW1iZXJOYW1lL3R5cGUnXSA9IHR5cGU7XG4gIG1uWydqYXZhL2xhbmcvaW52b2tlL01lbWJlck5hbWUvbmFtZSddID0gbmFtZTtcbn1cblxuLyoqXG4gKiBSZXR1cm5zIHRoZSBNZW1iZXJOYW1lIGZsYWdzIGZvciB0aGUgZ2l2ZW4gbWV0aG9kLlxuICovXG5mdW5jdGlvbiBtZXRob2RGbGFncyhtZXRob2Q6IE1ldGhvZCk6IG51bWJlciB7XG4gIHZhciBmbGFncyA9IG1ldGhvZC5hY2Nlc3NGbGFncy5nZXRSYXdCeXRlKCk7XG4gIGlmIChtZXRob2QuaXNDYWxsZXJTZW5zaXRpdmUoKSkge1xuICAgIGZsYWdzIHw9IE1lbWJlck5hbWVDb25zdGFudHMuQ0FMTEVSX1NFTlNJVElWRTtcbiAgfVxuICByZXR1cm4gZmxhZ3M7XG59XG5cbmNsYXNzIGphdmFfbGFuZ19pbnZva2VfTWV0aG9kSGFuZGxlTmF0aXZlcyB7XG4gIC8qKlxuICAgKiBJJ20gZ29pbmcgYnkgSkFNVk0ncyBpbXBsZW1lbnRhdGlvbiBvZiB0aGlzIG1ldGhvZCwgd2hpY2ggaXMgdmVyeSBlYXN5XG4gICAqIHRvIHVuZGVyc3RhbmQ6XG4gICAqIGh0dHA6Ly9zb3VyY2Vmb3JnZS5uZXQvcC9qYW12bS9jb2RlL2NpL21hc3Rlci90cmVlL3NyYy9jbGFzc2xpYi9vcGVuamRrL21oLmMjbDM4OFxuICAgKlxuICAgKiBUaGUgc2Vjb25kIGFyZ3VtZW50IGlzIGEgUmVmbGVjdGlvbiBvYmplY3QgZm9yIHRoZSBzcGVjaWZpZWQgbWVtYmVyLFxuICAgKiB3aGljaCBpcyBlaXRoZXIgYSBGaWVsZCwgTWV0aG9kLCBvciBDb25zdHJ1Y3Rvci5cbiAgICpcbiAgICogV2UgbmVlZCB0bzpcbiAgICogKiBTZXQgXCJjbGF6elwiIGZpZWxkIHRvIGl0ZW0ncyBkZWNsYXJpbmcgY2xhc3MgaW4gdGhlIHJlZmxlY3Rpb24gb2JqZWN0LlxuICAgKiAqIFNldCBcImZsYWdzXCIgZmllbGQgdG8gaXRlbXMncyBmbGFncywgT1InZCB3aXRoIGl0cyB0eXBlIChtZXRob2QvZmllbGQvXG4gICAqICAgY29uc3RydWN0b3IpLCBhbmQgT1InZCB3aXRoIGl0cyByZWZlcmVuY2Uga2luZCBzaGlmdGVkIHVwIGJ5IDI0LlxuICAgKiAqIFNldCBcInZtdGFyZ2V0XCIgaWYgcmVsZXZhbnQuXG4gICAqICogU2V0IFwidm1pbmRleFwiIGlmIHJlbGV2YW50LlxuICAgKlxuICAgKiBUaGlzIG1ldGhvZCBcInJlc29sdmVzXCIgdGhlIE1lbWJlck5hbWUgdW5hbWJpZ3VvdXNseSB1c2luZyB0aGUgcHJvdmlkZWRcbiAgICogcmVmbGVjdGlvbiBvYmplY3QuXG4gICAqXG4gICAqL1xuICBwdWJsaWMgc3RhdGljICdpbml0KExqYXZhL2xhbmcvaW52b2tlL01lbWJlck5hbWU7TGphdmEvbGFuZy9PYmplY3Q7KVYnKHRocmVhZDogSlZNVGhyZWFkLCBzZWxmOiBKVk1UeXBlcy5qYXZhX2xhbmdfaW52b2tlX01lbWJlck5hbWUsIHJlZjogSlZNVHlwZXMuamF2YV9sYW5nX09iamVjdCk6IHZvaWQge1xuICAgIHZhciBjbGF6ejogSlZNVHlwZXMuamF2YV9sYW5nX0NsYXNzLFxuICAgICAgY2xhenpEYXRhOiBSZWZlcmVuY2VDbGFzc0RhdGE8SlZNVHlwZXMuamF2YV9sYW5nX0NsYXNzPixcbiAgICAgIGZsYWdzOiBudW1iZXIsIG06IE1ldGhvZCwgZjogRmllbGQ7XG4gICAgc3dpdGNoIChyZWYuZ2V0Q2xhc3MoKS5nZXRJbnRlcm5hbE5hbWUoKSkge1xuICAgICAgY2FzZSBcIkxqYXZhL2xhbmcvcmVmbGVjdC9NZXRob2Q7XCI6XG4gICAgICAgIHZhciBtZXRob2RPYmogPSA8SlZNVHlwZXMuamF2YV9sYW5nX3JlZmxlY3RfTWV0aG9kPiByZWYsIHJlZktpbmQ6ICBudW1iZXI7XG4gICAgICAgIGNsYXp6ID0gbWV0aG9kT2JqWydqYXZhL2xhbmcvcmVmbGVjdC9NZXRob2QvY2xhenonXTtcbiAgICAgICAgY2xhenpEYXRhID0gKDxSZWZlcmVuY2VDbGFzc0RhdGE8SlZNVHlwZXMuamF2YV9sYW5nX0NsYXNzPj4gY2xhenouJGNscyk7XG4gICAgICAgIG0gPSBjbGF6ekRhdGEuZ2V0TWV0aG9kRnJvbVNsb3QobWV0aG9kT2JqWydqYXZhL2xhbmcvcmVmbGVjdC9NZXRob2Qvc2xvdCddKTtcbiAgICAgICAgZmxhZ3MgPSBtZXRob2RGbGFncyhtKSB8IE1lbWJlck5hbWVDb25zdGFudHMuSVNfTUVUSE9EO1xuICAgICAgICBpZiAobS5hY2Nlc3NGbGFncy5pc1N0YXRpYygpKSB7XG4gICAgICAgICAgcmVmS2luZCA9IE1ldGhvZEhhbmRsZVJlZmVyZW5jZUtpbmQuSU5WT0tFU1RBVElDO1xuICAgICAgICB9IGVsc2UgaWYgKGNsYXp6RGF0YS5hY2Nlc3NGbGFncy5pc0ludGVyZmFjZSgpKSB7XG4gICAgICAgICAgcmVmS2luZCA9IE1ldGhvZEhhbmRsZVJlZmVyZW5jZUtpbmQuSU5WT0tFSU5URVJGQUNFO1xuICAgICAgICB9IGVsc2Uge1xuICAgICAgICAgIHJlZktpbmQgPSBNZXRob2RIYW5kbGVSZWZlcmVuY2VLaW5kLklOVk9LRVZJUlRVQUw7XG4gICAgICAgIH1cbiAgICAgICAgZmxhZ3MgfD0gcmVmS2luZCA8PCBNZW1iZXJOYW1lQ29uc3RhbnRzLlJFRkVSRU5DRV9LSU5EX1NISUZUO1xuXG4gICAgICAgIHNlbGZbJ2phdmEvbGFuZy9pbnZva2UvTWVtYmVyTmFtZS9jbGF6eiddID0gY2xheno7XG4gICAgICAgIHNlbGZbJ2phdmEvbGFuZy9pbnZva2UvTWVtYmVyTmFtZS9mbGFncyddID0gZmxhZ3M7XG4gICAgICAgIHNlbGYudm10YXJnZXQgPSBtLmdldFZNVGFyZ2V0QnJpZGdlTWV0aG9kKHRocmVhZCwgcmVmS2luZCk7XG4gICAgICAgIC8vIE9ubHkgc2V0IHZtaW5kZXggZm9yIHZpcnR1YWwgZGlzcGF0Y2guXG4gICAgICAgIGlmIChyZWZLaW5kID09PSBNZXRob2RIYW5kbGVSZWZlcmVuY2VLaW5kLklOVk9LRVZJUlRVQUwgfHwgcmVmS2luZCA9PT0gTWV0aG9kSGFuZGxlUmVmZXJlbmNlS2luZC5JTlZPS0VJTlRFUkZBQ0UpIHtcbiAgICAgICAgICBzZWxmLnZtaW5kZXggPSBjbGF6ekRhdGEuZ2V0Vk1JbmRleEZvck1ldGhvZChtKTtcbiAgICAgICAgfVxuICAgICAgICBicmVhaztcbiAgICAgIGNhc2UgXCJMamF2YS9sYW5nL3JlZmxlY3QvQ29uc3RydWN0b3I7XCI6XG4gICAgICAgIHZhciBjb25zT2JqID0gPEpWTVR5cGVzLmphdmFfbGFuZ19yZWZsZWN0X0NvbnN0cnVjdG9yPiByZWY7XG4gICAgICAgIGNsYXp6ID0gY29uc09ialsnamF2YS9sYW5nL3JlZmxlY3QvQ29uc3RydWN0b3IvY2xhenonXTtcbiAgICAgICAgY2xhenpEYXRhID0gKDxSZWZlcmVuY2VDbGFzc0RhdGE8SlZNVHlwZXMuamF2YV9sYW5nX0NsYXNzPj4gY2xhenouJGNscyk7XG4gICAgICAgIG0gPSBjbGF6ekRhdGEuZ2V0TWV0aG9kRnJvbVNsb3QoY29uc09ialsnamF2YS9sYW5nL3JlZmxlY3QvQ29uc3RydWN0b3Ivc2xvdCddKTtcbiAgICAgICAgZmxhZ3MgPSBtZXRob2RGbGFncyhtKSB8IE1lbWJlck5hbWVDb25zdGFudHMuSVNfQ09OU1RSVUNUT1IgfCAoTWV0aG9kSGFuZGxlUmVmZXJlbmNlS2luZC5JTlZPS0VTUEVDSUFMIDw8IE1lbWJlck5hbWVDb25zdGFudHMuUkVGRVJFTkNFX0tJTkRfU0hJRlQpO1xuICAgICAgICBzZWxmWydqYXZhL2xhbmcvaW52b2tlL01lbWJlck5hbWUvY2xhenonXSA9IGNsYXp6O1xuICAgICAgICBzZWxmWydqYXZhL2xhbmcvaW52b2tlL01lbWJlck5hbWUvZmxhZ3MnXSA9IGZsYWdzO1xuICAgICAgICBzZWxmLnZtdGFyZ2V0ID0gbS5nZXRWTVRhcmdldEJyaWRnZU1ldGhvZCh0aHJlYWQsIHJlZktpbmQpO1xuICAgICAgICAvLyB2bWluZGV4IG5vdCByZWxldmFudDsgbm9udmlydHVhbCBkaXNwYXRjaC5cbiAgICAgICAgYnJlYWs7XG4gICAgICBjYXNlIFwiTGphdmEvbGFuZy9yZWZsZWN0L0ZpZWxkO1wiOlxuICAgICAgICB2YXIgZmllbGRPYmogPSA8SlZNVHlwZXMuamF2YV9sYW5nX3JlZmxlY3RfRmllbGQ+IHJlZjtcbiAgICAgICAgY2xhenogPSBmaWVsZE9ialsnamF2YS9sYW5nL3JlZmxlY3QvRmllbGQvY2xhenonXTtcbiAgICAgICAgY2xhenpEYXRhID0gKDxSZWZlcmVuY2VDbGFzc0RhdGE8SlZNVHlwZXMuamF2YV9sYW5nX0NsYXNzPj4gY2xhenouJGNscyk7XG4gICAgICAgIGYgPSBjbGF6ekRhdGEuZ2V0RmllbGRGcm9tU2xvdChmaWVsZE9ialsnamF2YS9sYW5nL3JlZmxlY3QvRmllbGQvc2xvdCddKTtcbiAgICAgICAgZmxhZ3MgPSBmLmFjY2Vzc0ZsYWdzLmdldFJhd0J5dGUoKSB8IE1lbWJlck5hbWVDb25zdGFudHMuSVNfRklFTEQ7XG4gICAgICAgIGZsYWdzIHw9IChmLmFjY2Vzc0ZsYWdzLmlzU3RhdGljKCkgPyBNZXRob2RIYW5kbGVSZWZlcmVuY2VLaW5kLkdFVFNUQVRJQyA6IE1ldGhvZEhhbmRsZVJlZmVyZW5jZUtpbmQuR0VURklFTEQpIDw8IE1lbWJlck5hbWVDb25zdGFudHMuUkVGRVJFTkNFX0tJTkRfU0hJRlQ7XG5cbiAgICAgICAgc2VsZlsnamF2YS9sYW5nL2ludm9rZS9NZW1iZXJOYW1lL2NsYXp6J10gPSBjbGF6ejtcbiAgICAgICAgc2VsZlsnamF2YS9sYW5nL2ludm9rZS9NZW1iZXJOYW1lL2ZsYWdzJ10gPSBmbGFncztcbiAgICAgICAgc2VsZi52bWluZGV4ID0gY2xhenpEYXRhLmdldFZNSW5kZXhGb3JGaWVsZChmKTtcbiAgICAgICAgLy8gdm10YXJnZXQgbm90IHJlbGV2YW50LlxuICAgICAgICBicmVhaztcbiAgICAgIGRlZmF1bHQ6XG4gICAgICAgIHRocmVhZC50aHJvd05ld0V4Y2VwdGlvbihcIkxqYXZhL2xhbmcvSW50ZXJuYWxFcnJvcjtcIiwgXCJpbml0OiBJbnZhbGlkIHRhcmdldC5cIik7XG4gICAgICAgIGJyZWFrO1xuICAgIH1cbiAgfVxuXG4gIHB1YmxpYyBzdGF0aWMgJ2dldENvbnN0YW50KEkpSScodGhyZWFkOiBKVk1UaHJlYWQsIGFyZzA6IG51bWJlcik6IG51bWJlciB7XG4gICAgLy8gSSBoYXZlIG5vIGlkZWEgd2hhdCB0aGUgc2VtYW50aWNzIGFyZSwgYnV0IHJldHVybmluZyAwIGRpc2FibGVzIHNvbWUgaW50ZXJuYWwgTUgtcmVsYXRlZCBjb3VudGluZy5cbiAgICByZXR1cm4gMDtcbiAgfVxuXG4gIC8qKlxuICAgKiBJJ20gZ29pbmcgYnkgSkFNVk0ncyBpbXBsZW1lbnRhdGlvbiBvZiByZXNvbHZlOlxuICAgKiBodHRwOi8vc291cmNlZm9yZ2UubmV0L3AvamFtdm0vY29kZS9jaS9tYXN0ZXIvdHJlZS9zcmMvY2xhc3NsaWIvb3Blbmpkay9taC5jI2wxMjY2XG4gICAqIEB0b2RvIEl0IGRvZXNuJ3QgZG8gYW55dGhpbmcgd2l0aCB0aGUgbG9va3VwQ2xhc3MuLi4gaXMgdGhhdCBmb3IgcGVybWlzc2lvbiBjaGVja3M/XG4gICAqXG4gICAqIElucHV0OiBBIE1lbWJlck5hbWUgb2JqZWN0IHRoYXQgYWxyZWFkeSBoYXMgYSBuYW1lLCByZWZlcmVuY2Uga2luZCwgYW5kIGNsYXNzIHNldC5cbiAgICogVXNlcyB0aGF0IGluZm8gdG8gcmVzb2x2ZSBhIGNvbmNyZXRlIG1ldGhvZCwgYW5kIHRoZW4gdXBkYXRlcyB0aGUgTWVtYmVyTmFtZSdzIGZsYWdzLFxuICAgKiBzZXRzIFwidm10YXJnZXRcIiwgYW5kIHNldHMgXCJ2bWluZGV4XCIuXG4gICAqL1xuICBwdWJsaWMgc3RhdGljICdyZXNvbHZlKExqYXZhL2xhbmcvaW52b2tlL01lbWJlck5hbWU7TGphdmEvbGFuZy9DbGFzczspTGphdmEvbGFuZy9pbnZva2UvTWVtYmVyTmFtZTsnKHRocmVhZDogSlZNVGhyZWFkLCBtZW1iZXJOYW1lOiBKVk1UeXBlcy5qYXZhX2xhbmdfaW52b2tlX01lbWJlck5hbWUsIGxvb2t1cENsYXNzOiBKVk1UeXBlcy5qYXZhX2xhbmdfQ2xhc3MpOiBKVk1UeXBlcy5qYXZhX2xhbmdfaW52b2tlX01lbWJlck5hbWUge1xuICAgIHZhciB0eXBlID0gbWVtYmVyTmFtZVsnamF2YS9sYW5nL2ludm9rZS9NZW1iZXJOYW1lL3R5cGUnXSxcbiAgICAgIG5hbWUgPSBtZW1iZXJOYW1lWydqYXZhL2xhbmcvaW52b2tlL01lbWJlck5hbWUvbmFtZSddLnRvU3RyaW5nKCksXG4gICAgICBjbGF6eiA9IDxSZWZlcmVuY2VDbGFzc0RhdGE8SlZNVHlwZXMuamF2YV9sYW5nX09iamVjdD4+IG1lbWJlck5hbWVbJ2phdmEvbGFuZy9pbnZva2UvTWVtYmVyTmFtZS9jbGF6eiddLiRjbHMsXG4gICAgICBmbGFncyA9IG1lbWJlck5hbWVbJ2phdmEvbGFuZy9pbnZva2UvTWVtYmVyTmFtZS9mbGFncyddLFxuICAgICAgcmVmS2luZCA9IGZsYWdzID4+PiBNZW1iZXJOYW1lQ29uc3RhbnRzLlJFRkVSRU5DRV9LSU5EX1NISUZUO1xuXG4gICAgaWYgKGNsYXp6ID09IG51bGwgfHwgbmFtZSA9PSBudWxsIHx8IHR5cGUgPT0gbnVsbCkge1xuICAgICAgdGhyZWFkLnRocm93TmV3RXhjZXB0aW9uKFwiTGphdmEvbGFuZy9JbGxlZ2FsQXJndW1lbnRFeGNlcHRpb247XCIsIFwiSW52YWxpZCBNZW1iZXJOYW1lLlwiKTtcbiAgICAgIHJldHVybjtcbiAgICB9XG5cbiAgICBhc3NlcnQoKGZsYWdzICYgTWVtYmVyTmFtZUNvbnN0YW50cy5DQUxMRVJfU0VOU0lUSVZFKSA9PT0gMCwgXCJOb3QgeWV0IHN1cHBvcnRlZDogQ2FsbGVyIHNlbnNpdGl2ZSBtZXRob2RzLlwiKTtcbiAgICBzd2l0Y2ggKGZsYWdzICYgTWVtYmVyTmFtZUNvbnN0YW50cy5BTExfS0lORFMpIHtcbiAgICAgIGNhc2UgTWVtYmVyTmFtZUNvbnN0YW50cy5JU19DT05TVFJVQ1RPUjpcbiAgICAgIGNhc2UgTWVtYmVyTmFtZUNvbnN0YW50cy5JU19NRVRIT0Q6XG4gICAgICAgIC8vIE5lZWQgdG8gcGVyZm9ybSBtZXRob2QgbG9va3VwLlxuICAgICAgICB2YXIgbWV0aG9kVGFyZ2V0ID0gY2xhenouc2lnbmF0dXJlUG9seW1vcnBoaWNBd2FyZU1ldGhvZExvb2t1cChuYW1lICsgKDxKVk1UeXBlcy5qYXZhX2xhbmdfaW52b2tlX01ldGhvZFR5cGU+IHR5cGUpLnRvU3RyaW5nKCkpO1xuICAgICAgICBpZiAobWV0aG9kVGFyZ2V0ICE9PSBudWxsKSB7XG4gICAgICAgICAgZmxhZ3MgfD0gbWV0aG9kRmxhZ3MobWV0aG9kVGFyZ2V0KTtcbiAgICAgICAgICBtZW1iZXJOYW1lWydqYXZhL2xhbmcvaW52b2tlL01lbWJlck5hbWUvZmxhZ3MnXSA9IGZsYWdzO1xuICAgICAgICAgIG1lbWJlck5hbWUudm10YXJnZXQgPSBtZXRob2RUYXJnZXQuZ2V0Vk1UYXJnZXRCcmlkZ2VNZXRob2QodGhyZWFkLCBmbGFncyA+Pj4gTWVtYmVyTmFtZUNvbnN0YW50cy5SRUZFUkVOQ0VfS0lORF9TSElGVCk7XG4gICAgICAgICAgLy8gdm1pbmRleCBpcyBvbmx5IHJlbGV2YW50IGZvciB2aXJ0dWFsIGRpc3BhdGNoLlxuICAgICAgICAgIGlmIChyZWZLaW5kID09PSBNZXRob2RIYW5kbGVSZWZlcmVuY2VLaW5kLklOVk9LRUlOVEVSRkFDRSB8fCByZWZLaW5kID09PSBNZXRob2RIYW5kbGVSZWZlcmVuY2VLaW5kLklOVk9LRVZJUlRVQUwpIHtcbiAgICAgICAgICAgIG1lbWJlck5hbWUudm1pbmRleCA9IGNsYXp6LmdldFZNSW5kZXhGb3JNZXRob2QobWV0aG9kVGFyZ2V0KTtcbiAgICAgICAgICB9XG4gICAgICAgICAgcmV0dXJuIG1lbWJlck5hbWU7XG4gICAgICAgIH0gZWxzZSB7XG4gICAgICAgICAgdGhyZWFkLnRocm93TmV3RXhjZXB0aW9uKCdMamF2YS9sYW5nL05vU3VjaE1ldGhvZEVycm9yOycsIGBJbnZhbGlkIG1ldGhvZCAke25hbWUgKyAoPEpWTVR5cGVzLmphdmFfbGFuZ19pbnZva2VfTWV0aG9kVHlwZT4gdHlwZSkudG9TdHJpbmcoKX0gaW4gY2xhc3MgJHtjbGF6ei5nZXRFeHRlcm5hbE5hbWUoKX0uYCk7XG4gICAgICAgIH1cbiAgICAgICAgYnJlYWs7XG4gICAgICBjYXNlIE1lbWJlck5hbWVDb25zdGFudHMuSVNfRklFTEQ6XG4gICAgICAgIHZhciBmaWVsZFRhcmdldCA9IGNsYXp6LmZpZWxkTG9va3VwKG5hbWUpO1xuICAgICAgICBpZiAoZmllbGRUYXJnZXQgIT09IG51bGwpIHtcbiAgICAgICAgICBmbGFncyB8PSBmaWVsZFRhcmdldC5hY2Nlc3NGbGFncy5nZXRSYXdCeXRlKCk7XG4gICAgICAgICAgbWVtYmVyTmFtZVsnamF2YS9sYW5nL2ludm9rZS9NZW1iZXJOYW1lL2ZsYWdzJ10gPSBmbGFncztcbiAgICAgICAgICBtZW1iZXJOYW1lLnZtaW5kZXggPSBjbGF6ei5nZXRWTUluZGV4Rm9yRmllbGQoZmllbGRUYXJnZXQpO1xuICAgICAgICAgIHJldHVybiBtZW1iZXJOYW1lO1xuICAgICAgICB9IGVsc2Uge1xuICAgICAgICAgIHRocmVhZC50aHJvd05ld0V4Y2VwdGlvbignTGphdmEvbGFuZy9Ob1N1Y2hGaWVsZEVycm9yOycsIGBJbnZhbGlkIG1ldGhvZCAke25hbWV9IGluIGNsYXNzICR7Y2xhenouZ2V0RXh0ZXJuYWxOYW1lKCl9LmApO1xuICAgICAgICB9XG4gICAgICAgIGJyZWFrO1xuICAgICAgZGVmYXVsdDpcbiAgICAgICAgdGhyZWFkLnRocm93TmV3RXhjZXB0aW9uKCdMamF2YS9sYW5nL0xpbmthZ2VFcnJvcjsnLCAncmVzb2x2ZSBtZW1iZXIgbmFtZScpO1xuICAgICAgICBicmVhaztcbiAgICB9XG4gIH1cblxuICAvKipcbiAgICogRm9sbG93cyB0aGUgc2FtZSBsb2dpYyBhcyBzdW4ubWlzYy5VbnNhZmUncyBvYmplY3RGaWVsZE9mZnNldC5cbiAgICovXG4gIHB1YmxpYyBzdGF0aWMgJ29iamVjdEZpZWxkT2Zmc2V0KExqYXZhL2xhbmcvaW52b2tlL01lbWJlck5hbWU7KUonKHRocmVhZDogSlZNVGhyZWFkLCBtZW1iZXJOYW1lOiBKVk1UeXBlcy5qYXZhX2xhbmdfaW52b2tlX01lbWJlck5hbWUpOiBMb25nIHtcbiAgICBpZiAobWVtYmVyTmFtZVsndm1pbmRleCddID09PSAtMSkge1xuICAgICAgdGhyZWFkLnRocm93TmV3RXhjZXB0aW9uKFwiTGphdmEvbGFuZy9JbGxlZ2FsU3RhdGVFeGNlcHRpb247XCIsIFwiQXR0ZW1wdGVkIHRvIHJldHJpZXZlIHRoZSBvYmplY3Qgb2Zmc2V0IGZvciBhbiB1bnJlc29sdmVkIG9yIG5vbi1vYmplY3QgTWVtYmVyTmFtZS5cIik7XG4gICAgfSBlbHNlIHtcbiAgICAgIHJldHVybiBMb25nLmZyb21OdW1iZXIobWVtYmVyTmFtZS52bWluZGV4KTtcbiAgICB9XG4gIH1cblxuICAvKipcbiAgICogRm9sbG93cyB0aGUgc2FtZSBsb2dpYyBhcyBzdW4ubWlzYy5VbnNhZmUncyBzdGF0aWNGaWVsZE9mZnNldC5cbiAgICovXG4gIHB1YmxpYyBzdGF0aWMgJ3N0YXRpY0ZpZWxkT2Zmc2V0KExqYXZhL2xhbmcvaW52b2tlL01lbWJlck5hbWU7KUonKHRocmVhZDogSlZNVGhyZWFkLCBtZW1iZXJOYW1lOiBKVk1UeXBlcy5qYXZhX2xhbmdfaW52b2tlX01lbWJlck5hbWUpOiBMb25nIHtcbiAgICBpZiAobWVtYmVyTmFtZVsndm1pbmRleCddID09PSAtMSkge1xuICAgICAgdGhyZWFkLnRocm93TmV3RXhjZXB0aW9uKFwiTGphdmEvbGFuZy9JbGxlZ2FsU3RhdGVFeGNlcHRpb247XCIsIFwiQXR0ZW1wdGVkIHRvIHJldHJpZXZlIHRoZSBvYmplY3Qgb2Zmc2V0IGZvciBhbiB1bnJlc29sdmVkIG9yIG5vbi1vYmplY3QgTWVtYmVyTmFtZS5cIik7XG4gICAgfSBlbHNlIHtcbiAgICAgIHJldHVybiBMb25nLmZyb21OdW1iZXIobWVtYmVyTmFtZS52bWluZGV4KTtcbiAgICB9XG4gIH1cblxuICAvKipcbiAgICogRm9sbG93cyB0aGUgc2FtZSBsb2dpYyBhcyBzdW4ubWlzYy5VbnNhZmUncyBzdGF0aWNGaWVsZEJhc2UuXG4gICAqL1xuICBwdWJsaWMgc3RhdGljICdzdGF0aWNGaWVsZEJhc2UoTGphdmEvbGFuZy9pbnZva2UvTWVtYmVyTmFtZTspTGphdmEvbGFuZy9PYmplY3Q7Jyh0aHJlYWQ6IEpWTVRocmVhZCwgbWVtYmVyTmFtZTogSlZNVHlwZXMuamF2YV9sYW5nX2ludm9rZV9NZW1iZXJOYW1lKTogSlZNVHlwZXMuamF2YV9sYW5nX09iamVjdCB7XG4gICAgLy8gUmV0dXJuIGEgc3BlY2lhbCBKVk0gb2JqZWN0LlxuICAgIC8vIFRPRE86IEFjdHVhbGx5IGNyZWF0ZSBhIHNwZWNpYWwgRG9wcGlvSlZNIGNsYXNzIGZvciB0aGlzLlxuICAgIHZhciBydiA9IG5ldyAoKDxSZWZlcmVuY2VDbGFzc0RhdGE8SlZNVHlwZXMuamF2YV9sYW5nX09iamVjdD4+IHRocmVhZC5nZXRCc0NsKCkuZ2V0SW5pdGlhbGl6ZWRDbGFzcyh0aHJlYWQsICdMamF2YS9sYW5nL09iamVjdDsnKSkuZ2V0Q29uc3RydWN0b3IodGhyZWFkKSkodGhyZWFkKTtcbiAgICAoPGFueT4gcnYpLiRzdGF0aWNGaWVsZEJhc2UgPSBtZW1iZXJOYW1lWydqYXZhL2xhbmcvaW52b2tlL01lbWJlck5hbWUvY2xhenonXS4kY2xzO1xuICAgIHJldHVybiBydjtcbiAgfVxuXG4gIC8qKlxuICAgKiBHZXQgdGhlIG1lbWJlcnMgb2YgdGhlIGdpdmVuIGNsYXNzIHRoYXQgbWF0Y2ggdGhlIHNwZWNpZmllZCBmbGFncywgc2tpcHBpbmdcbiAgICogdGhlIHNwZWNpZmllZCBudW1iZXIgb2YgbWVtYmVycy4gRm9yIGVhY2ggbm9uLXNraXBwZWQgbWF0Y2hpbmcgbWVtYmVyLFxuICAgKiBmaWxsIGluIHRoZSBmaWVsZHMgb2YgYSBNZW1iZXJOYW1lIG9iamVjdHMgaW4gdGhlIHJlc3VsdHMgYXJyYXkuXG4gICAqIElmIHRoZXJlIGFyZSBtb3JlIG1hdGNoZXMgdGhhbiBjYW4gZml0IGluIHRoZSBhcnJheSwgZG8gKm5vdCogb3ZlcnJ1blxuICAgKiB0aGUgYXJyYXkuIFJldHVybiB0aGUgdG90YWwgbnVtYmVyIG9mIG1hdGNoaW5nIG5vbi1za2lwcGVkIG1lbWJlcnMuXG4gICAqIFRPRE86IEFjY2VzcyBjaGVja3M/XG4gICAqL1xuICBwdWJsaWMgc3RhdGljICdnZXRNZW1iZXJzKExqYXZhL2xhbmcvQ2xhc3M7TGphdmEvbGFuZy9TdHJpbmc7TGphdmEvbGFuZy9TdHJpbmc7SUxqYXZhL2xhbmcvQ2xhc3M7SVtMamF2YS9sYW5nL2ludm9rZS9NZW1iZXJOYW1lOylJJyhcbiAgICB0aHJlYWQ6IEpWTVRocmVhZCwgZGVmYzogSlZNVHlwZXMuamF2YV9sYW5nX0NsYXNzLFxuICAgIG1hdGNoTmFtZTogSlZNVHlwZXMuamF2YV9sYW5nX1N0cmluZywgbWF0Y2hTaWc6IEpWTVR5cGVzLmphdmFfbGFuZ19TdHJpbmcsXG4gICAgbWF0Y2hGbGFnczogbnVtYmVyLCBjYWxsZXI6IEpWTVR5cGVzLmphdmFfbGFuZ19DbGFzcywgc2tpcDogbnVtYmVyLFxuICAgIHJlc3VsdHM6IEpWTVR5cGVzLkpWTUFycmF5PEpWTVR5cGVzLmphdmFfbGFuZ19pbnZva2VfTWVtYmVyTmFtZT5cbiAgKTogbnVtYmVyIHtcbiAgICAvLyBHZW5lcmFsIHNlYXJjaCBmbGFncy5cbiAgICB2YXIgc2VhcmNoU3VwZXJjbGFzc2VzID0gMCAhPT0gKG1hdGNoRmxhZ3MgJiBNZW1iZXJOYW1lQ29uc3RhbnRzLlNFQVJDSF9TVVBFUkNMQVNTRVMpLFxuICAgICAgc2VhcmNoSW50ZXJmYWNlcyA9IDAgIT09IChtYXRjaEZsYWdzICYgTWVtYmVyTmFtZUNvbnN0YW50cy5TRUFSQ0hfSU5URVJGQUNFUyksXG4gICAgICBtYXRjaGVkID0gMCwgdGFyZ2V0Q2xhc3MgPSBkZWZjLiRjbHMsIG1ldGhvZHM6IE1ldGhvZFtdLFxuICAgICAgZmllbGRzOiBGaWVsZFtdLCBtYXRjaEFycmF5ID0gcmVzdWx0cy5hcnJheSxcbiAgICAgIG5hbWU6IHN0cmluZyA9IG1hdGNoTmFtZSAhPT0gbnVsbCA/IG1hdGNoTmFtZS50b1N0cmluZygpIDogbnVsbCxcbiAgICAgIHNpZzogc3RyaW5nID0gbWF0Y2hTaWcgIT09IG51bGwgPyBtYXRjaFNpZy50b1N0cmluZygpIDogbnVsbDtcblxuICAgIC8qKlxuICAgICAqIEhlbHBlciBmdW5jdGlvbjogQWRkcyBtYXRjaGVkIGl0ZW1zIHRvIHRoZSBhcnJheSBvbmNlIHdlJ3ZlIHNraXBwZWRcbiAgICAgKiBlbm91Z2guXG4gICAgICovXG4gICAgZnVuY3Rpb24gYWRkTWF0Y2goaXRlbTogQWJzdHJhY3RNZXRob2RGaWVsZCkge1xuICAgICAgaWYgKHNraXAgPj0gMCkge1xuICAgICAgICBpZiAobWF0Y2hlZCA8IG1hdGNoQXJyYXkubGVuZ3RoKSB7XG4gICAgICAgICAgaW5pdGlhbGl6ZU1lbWJlck5hbWUodGhyZWFkLCBtYXRjaEFycmF5W21hdGNoZWRdLCBpdGVtKTtcbiAgICAgICAgfVxuICAgICAgICBtYXRjaGVkKys7XG4gICAgICB9IGVsc2Uge1xuICAgICAgICBza2lwLS07XG4gICAgICB9XG4gICAgfVxuXG4gICAgLy8gVE9ETzogU3VwcG9ydCB0aGVzZSBmbGFncy5cbiAgICBhc3NlcnQoIXNlYXJjaFN1cGVyY2xhc3NlcyAmJiAhc2VhcmNoSW50ZXJmYWNlcywgXCJVbnN1cHBvcnRlZDogTm9uLWxvY2FsIGdldE1lbWJlcnMgY2FsbHMuXCIpO1xuXG4gICAgLy8gQ29uc3RydWN0b3JzXG4gICAgaWYgKDAgIT09IChtYXRjaEZsYWdzICYgTWVtYmVyTmFtZUNvbnN0YW50cy5JU19DT05TVFJVQ1RPUikgJiYgKG5hbWUgPT09IG51bGwgfHwgbmFtZSA9PT0gXCI8aW5pdD5cIikpIHtcbiAgICAgIG1ldGhvZHMgPSB0YXJnZXRDbGFzcy5nZXRNZXRob2RzKCk7XG4gICAgICBtZXRob2RzLmZvckVhY2goKG06IE1ldGhvZCkgPT4ge1xuICAgICAgICBpZiAobS5uYW1lID09PSBcIjxpbml0PlwiICYmIChzaWcgPT09IG51bGwgfHwgc2lnID09PSBtLnJhd0Rlc2NyaXB0b3IpKSB7XG4gICAgICAgICAgYWRkTWF0Y2gobSk7XG4gICAgICAgIH1cbiAgICAgIH0pO1xuICAgIH1cblxuICAgIC8vIE1ldGhvZHNcbiAgICBpZiAoMCAhPT0gKG1hdGNoRmxhZ3MgJiBNZW1iZXJOYW1lQ29uc3RhbnRzLklTX01FVEhPRCkpIHtcbiAgICAgIG1ldGhvZHMgPSB0YXJnZXRDbGFzcy5nZXRNZXRob2RzKCk7XG4gICAgICBtZXRob2RzLmZvckVhY2goKG06IE1ldGhvZCkgPT4ge1xuICAgICAgICBpZiAobS5uYW1lICE9PSBcIjxpbml0PlwiICYmIChuYW1lID09PSBudWxsIHx8IG5hbWUgPT09IG0ubmFtZSkgJiYgKHNpZyA9PT0gbnVsbCB8fCBzaWcgPT09IG0ucmF3RGVzY3JpcHRvcikpIHtcbiAgICAgICAgICBhZGRNYXRjaChtKTtcbiAgICAgICAgfVxuICAgICAgfSk7XG4gICAgfVxuXG4gICAgLy8gRmllbGRzXG4gICAgaWYgKDAgIT09IChtYXRjaEZsYWdzICYgTWVtYmVyTmFtZUNvbnN0YW50cy5JU19GSUVMRCkgJiYgc2lnID09PSBudWxsKSB7XG4gICAgICBmaWVsZHMgPSB0YXJnZXRDbGFzcy5nZXRGaWVsZHMoKTtcbiAgICAgIGZpZWxkcy5mb3JFYWNoKChmOiBGaWVsZCkgPT4ge1xuICAgICAgICBpZiAobmFtZSA9PT0gbnVsbCB8fCBuYW1lID09PSBmLm5hbWUpIHtcbiAgICAgICAgICBhZGRNYXRjaChmKTtcbiAgICAgICAgfVxuICAgICAgfSk7XG4gICAgfVxuXG4gICAgLy8gVE9ETzogSW5uZXIgdHlwZXMgKElTX1RZUEUpLlxuICAgIGFzc2VydCgwID09IChtYXRjaEZsYWdzICYgTWVtYmVyTmFtZUNvbnN0YW50cy5JU19UWVBFKSwgXCJVbnN1cHBvcnRlZDogR2V0dGluZyBpbm5lciB0eXBlIE1lbWJlck5hbWVzLlwiKTtcbiAgICByZXR1cm4gbWF0Y2hlZDtcbiAgfVxuXG4gIC8qKlxuICAgKiBEZWJ1ZyBuYXRpdmUgaW4gdGhlIEpESzogR2V0cyBhIG5hbWVkIGNvbnN0YW50IGZyb20gTWV0aG9kSGFuZGxlTmF0aXZlcy5Db25zdGFudHMuXG4gICAqL1xuICBwdWJsaWMgc3RhdGljICdnZXROYW1lZENvbihJW0xqYXZhL2xhbmcvT2JqZWN0OylJJyh0aHJlYWQ6IEpWTVRocmVhZCwgZmllbGROdW06IG51bWJlciwgYXJnczogSlZNVHlwZXMuSlZNQXJyYXk8SlZNVHlwZXMuamF2YV9sYW5nX09iamVjdD4pOiB2b2lkIHtcbiAgICB0aHJlYWQuc2V0U3RhdHVzKFRocmVhZFN0YXR1cy5BU1lOQ19XQUlUSU5HKTtcbiAgICB0aHJlYWQuZ2V0QnNDbCgpLmluaXRpYWxpemVDbGFzcyh0aHJlYWQsIFwiTGphdmEvbGFuZy9pbnZva2UvTWV0aG9kSGFuZGxlTmF0aXZlcyRDb25zdGFudHM7XCIsIChjb25zdGFudHNDbHM6IFJlZmVyZW5jZUNsYXNzRGF0YTxKVk1UeXBlcy5qYXZhX2xhbmdfaW52b2tlX01ldGhvZEhhbmRsZU5hdGl2ZXMkQ29uc3RhbnRzPikgPT4ge1xuICAgICAgaWYgKGNvbnN0YW50c0NscyA9PT0gbnVsbCkge1xuICAgICAgICByZXR1cm47XG4gICAgICB9XG4gICAgICB2YXIgY29uc3RhbnRzID0gY29uc3RhbnRzQ2xzLmdldEZpZWxkcygpLmZpbHRlcigoZmllbGQ6IEZpZWxkKSA9PiBmaWVsZC5hY2Nlc3NGbGFncy5pc1N0YXRpYygpICYmIGZpZWxkLmFjY2Vzc0ZsYWdzLmlzRmluYWwoKSk7XG4gICAgICBpZiAoZmllbGROdW0gPCBjb25zdGFudHMubGVuZ3RoKSB7XG4gICAgICAgIHZhciBmaWVsZCA9IGNvbnN0YW50c1tmaWVsZE51bV07XG4gICAgICAgIGFyZ3MuYXJyYXlbMF0gPSB1dGlsLmluaXRTdHJpbmcodGhyZWFkLmdldEJzQ2woKSwgZmllbGQubmFtZSk7XG4gICAgICAgIHRocmVhZC5hc3luY1JldHVybigoPGFueT4gY29uc3RhbnRzQ2xzLmdldENvbnN0cnVjdG9yKHRocmVhZCkpW2ZpZWxkLmZ1bGxOYW1lXSk7XG4gICAgICB9IGVsc2Uge1xuICAgICAgICB0aHJlYWQuYXN5bmNSZXR1cm4oLTEpO1xuICAgICAgfVxuICAgIH0pO1xuICB9XG5cbiAgcHVibGljIHN0YXRpYyAnZ2V0TWVtYmVyVk1JbmZvKExqYXZhL2xhbmcvaW52b2tlL01lbWJlck5hbWU7KUxqYXZhL2xhbmcvT2JqZWN0OycodGhyZWFkOiBKVk1UaHJlYWQsIG1uYW1lOiBKVk1UeXBlcy5qYXZhX2xhbmdfaW52b2tlX01lbWJlck5hbWUpOiBKVk1UeXBlcy5qYXZhX2xhbmdfT2JqZWN0IHtcbiAgICB2YXIgcnYgPSB1dGlsLm5ld0FycmF5KHRocmVhZCwgdGhyZWFkLmdldEJzQ2woKSwgJ1tMamF2YS9sYW5nL09iamVjdDsnLCAyKSxcbiAgICAgIGZsYWdzID0gbW5hbWVbJ2phdmEvbGFuZy9pbnZva2UvTWVtYmVyTmFtZS9mbGFncyddLFxuICAgICAgcmVmS2luZCA9IGZsYWdzID4+PiBNZW1iZXJOYW1lQ29uc3RhbnRzLlJFRkVSRU5DRV9LSU5EX1NISUZULFxuICAgICAgbG9uZ0NscyA9ICg8UHJpbWl0aXZlQ2xhc3NEYXRhPiB0aHJlYWQuZ2V0QnNDbCgpLmdldEluaXRpYWxpemVkQ2xhc3ModGhyZWFkLCAnSicpKTtcblxuICAgIC8vIFZNSW5kZXggb2YgdGhlIHRhcmdldC5cbiAgICBydi5hcnJheVswXSA9IGxvbmdDbHMuY3JlYXRlV3JhcHBlck9iamVjdCh0aHJlYWQsIExvbmcuZnJvbU51bWJlcihtbmFtZS52bWluZGV4KSk7XG4gICAgLy8gQ2xhc3MgaWYgZmllbGQsIG1lbWJlcm5hbWUgaWYgbWV0aG9kXG4gICAgcnYuYXJyYXlbMV0gPSAoKChmbGFncyAmIE1lbWJlck5hbWVDb25zdGFudHMuQUxMX0tJTkRTKSAmIE1lbWJlck5hbWVDb25zdGFudHMuSVNfRklFTEQpID4gMCkgPyBtbmFtZVsnamF2YS9sYW5nL2ludm9rZS9NZW1iZXJOYW1lL2NsYXp6J10gOiBtbmFtZTtcbiAgICByZXR1cm4gcnY7XG4gIH1cblxuICBwdWJsaWMgc3RhdGljICdzZXRDYWxsU2l0ZVRhcmdldE5vcm1hbChMamF2YS9sYW5nL2ludm9rZS9DYWxsU2l0ZTtMamF2YS9sYW5nL2ludm9rZS9NZXRob2RIYW5kbGU7KVYnKHRocmVhZDogSlZNVGhyZWFkLCBjYWxsU2l0ZTogSlZNVHlwZXMuamF2YV9sYW5nX2ludm9rZV9DYWxsU2l0ZSwgbWV0aG9kSGFuZGxlOiBKVk1UeXBlcy5qYXZhX2xhbmdfaW52b2tlX01ldGhvZEhhbmRsZSk6IHZvaWQge1xuICAgIGNhbGxTaXRlWydqYXZhL2xhbmcvaW52b2tlL0NhbGxTaXRlL3RhcmdldCddID0gbWV0aG9kSGFuZGxlO1xuICB9XG59XG5cbmNsYXNzIGphdmFfbGFuZ19pbnZva2VfTWV0aG9kSGFuZGxlIHtcbiAgLyoqXG4gICAqIEludm9rZXMgdGhlIG1ldGhvZCBoYW5kbGUsIGFsbG93aW5nIGFueSBjYWxsZXIgdHlwZSBkZXNjcmlwdG9yLCBidXQgcmVxdWlyaW5nIGFuIGV4YWN0IHR5cGUgbWF0Y2guXG4gICAqXG4gICAqIElmIHRoaXMgbmF0aXZlIG1ldGhvZCBpcyBpbnZva2VkIGRpcmVjdGx5IHZpYSBqYXZhLmxhbmcucmVmbGVjdC5NZXRob2QuaW52b2tlLFxuICAgKiB2aWEgSk5JLCBvciBpbmRpcmVjdGx5IHZpYSBqYXZhLmxhbmcuaW52b2tlLk1ldGhvZEhhbmRsZXMuTG9va3VwLnVucmVmbGVjdCxcbiAgICogaXQgd2lsbCB0aHJvdyBhbiBVbnN1cHBvcnRlZE9wZXJhdGlvbkV4Y2VwdGlvbi5cbiAgICpcbiAgICogQHRocm93cyBXcm9uZ01ldGhvZFR5cGVFeGNlcHRpb24gaWYgdGhlIHRhcmdldCdzIHR5cGUgaXMgbm90IGlkZW50aWNhbCB3aXRoIHRoZSBjYWxsZXIncyBzeW1ib2xpYyB0eXBlIGRlc2NyaXB0b3JcbiAgICogQHRocm93cyBUaHJvd2FibGUgYW55dGhpbmcgdGhyb3duIGJ5IHRoZSB1bmRlcmx5aW5nIG1ldGhvZCBwcm9wYWdhdGVzIHVuY2hhbmdlZCB0aHJvdWdoIHRoZSBtZXRob2QgaGFuZGxlIGNhbGxcbiAgICovXG4gIHB1YmxpYyBzdGF0aWMgJ2ludm9rZUV4YWN0KFtMamF2YS9sYW5nL09iamVjdDspTGphdmEvbGFuZy9PYmplY3Q7Jyh0aHJlYWQ6IEpWTVRocmVhZCwgbWg6IEpWTVR5cGVzLmphdmFfbGFuZ19pbnZva2VfTWV0aG9kSGFuZGxlLCBhcmdzOiBKVk1UeXBlcy5KVk1BcnJheTxKVk1UeXBlcy5qYXZhX2xhbmdfT2JqZWN0Pik6IHZvaWQge1xuICAgIC8vIExpa2Ugb3RoZXIgSlZNcywgd2UgYmFrZSB0aGUgc2VtYW50aWNzIG9mIGludm9rZS9pbnZva2VFeGFjdCBkaXJlY3RseVxuICAgIC8vIGludG8gdGhlIGJ5dGVjb2RlLiBUaHVzLCB0aGlzIHZlcnNpb24gb2YgdGhlIG1ldGhvZCB3aWxsICpvbmx5KiBiZVxuICAgIC8vIGludm9rZWQgdmlhIHJlZmxlY3Rpb24sIGNhdXNpbmcgdGhpcyBleGNlcHRpb24uXG4gICAgdGhyZWFkLnRocm93TmV3RXhjZXB0aW9uKFwiTGphdmEvbGFuZy9VbnN1cHBvcnRlZE9wZXJhdGlvbkV4Y2VwdGlvbjtcIiwgXCJNZXRob2RIYW5kbGUuaW52b2tlRXhhY3QgY2Fubm90IGJlIGludm9rZWQgcmVmbGVjdGl2ZWx5XCIpO1xuICB9XG5cbiAgcHVibGljIHN0YXRpYyAnaW52b2tlKFtMamF2YS9sYW5nL09iamVjdDspTGphdmEvbGFuZy9PYmplY3Q7Jyh0aHJlYWQ6IEpWTVRocmVhZCwgbWg6IEpWTVR5cGVzLmphdmFfbGFuZ19pbnZva2VfTWV0aG9kSGFuZGxlLCBhcmdzOiBKVk1UeXBlcy5KVk1BcnJheTxKVk1UeXBlcy5qYXZhX2xhbmdfT2JqZWN0Pik6IHZvaWQge1xuICAgIC8vIExpa2Ugb3RoZXIgSlZNcywgd2UgYmFrZSB0aGUgc2VtYW50aWNzIG9mIGludm9rZS9pbnZva2VFeGFjdCBkaXJlY3RseVxuICAgIC8vIGludG8gdGhlIGJ5dGVjb2RlLiBUaHVzLCB0aGlzIHZlcnNpb24gb2YgdGhlIG1ldGhvZCB3aWxsICpvbmx5KiBiZVxuICAgIC8vIGludm9rZWQgdmlhIHJlZmxlY3Rpb24sIGNhdXNpbmcgdGhpcyBleGNlcHRpb24uXG4gICAgdGhyZWFkLnRocm93TmV3RXhjZXB0aW9uKFwiTGphdmEvbGFuZy9VbnN1cHBvcnRlZE9wZXJhdGlvbkV4Y2VwdGlvbjtcIiwgXCJNZXRob2RIYW5kbGUuaW52b2tlIGNhbm5vdCBiZSBpbnZva2VkIHJlZmxlY3RpdmVseVwiKTtcbiAgfVxuXG4gIC8qKlxuICAgKiBVbmxpa2UgaW52b2tlIGFuZCBpbnZva2VFeGFjdCwgaW52b2tlQmFzaWMgKmNhbiogYmUgaW52b2tlZCByZWZsZWN0aXZlbHksXG4gICAqIGFuZCB0aHVzIGl0IGhhcyBhbiBpbXBsZW1lbnRhdGlvbiBoZXJlLiBOb3RlIHRoYXQgaW52b2tlQmFzaWMgaXMgcHJpdmF0ZSxcbiAgICogYW5kIHRodXMgY2FuIG9ubHkgYmUgaW52b2tlZCBieSB0cnVzdGVkIE9wZW5KREsgY29kZS5cbiAgICpcbiAgICogV2hlbiBpbnZva2VkIHJlZmxlY3RpdmVseSwgYXJndW1lbnRzIHRvIGludm9rZUJhc2ljIHdpbGwgYmUgYm94ZWQuXG4gICAqXG4gICAqIFRoZSByZXR1cm4gdmFsdWUgaXMgKm5ldmVyKiBib3hlZC4gWWVzLCB0aGlzIGlzIHdlaXJkLiBJdCdzIG9ubHkgY2FsbGVkIGJ5XG4gICAqIHRydXN0ZWQgY29kZSwgdGhvdWdoLlxuICAgKi9cbiAgcHVibGljIHN0YXRpYyAnaW52b2tlQmFzaWMoW0xqYXZhL2xhbmcvT2JqZWN0OylMamF2YS9sYW5nL09iamVjdDsnKHRocmVhZDogSlZNVGhyZWFkLCBtaDogSlZNVHlwZXMuamF2YV9sYW5nX2ludm9rZV9NZXRob2RIYW5kbGUsIGFyZ3NCb3hlZDogSlZNVHlwZXMuSlZNQXJyYXk8SlZNVHlwZXMuamF2YV9sYW5nX09iamVjdD4pOiB2b2lkIHtcbiAgICB2YXIgbG1iZGFGb3JtID0gbWhbJ2phdmEvbGFuZy9pbnZva2UvTWV0aG9kSGFuZGxlL2Zvcm0nXSxcbiAgICAgIG1uID0gbG1iZGFGb3JtWydqYXZhL2xhbmcvaW52b2tlL0xhbWJkYUZvcm0vdm1lbnRyeSddLFxuICAgICAgZGVzY3JpcHRvcjogc3RyaW5nLCBwYXJhbVR5cGVzOiBzdHJpbmdbXTtcblxuICAgIGFzc2VydChtaC5nZXRDbGFzcygpLmlzQ2FzdGFibGUodGhyZWFkLmdldEJzQ2woKS5nZXRJbml0aWFsaXplZENsYXNzKHRocmVhZCwgJ0xqYXZhL2xhbmcvaW52b2tlL01ldGhvZEhhbmRsZTsnKSksIFwiRmlyc3QgYXJndW1lbnQgdG8gaW52b2tlQmFzaWMgbXVzdCBiZSBhIG1ldGhvZCBoYW5kbGUuXCIpO1xuICAgIGFzc2VydChtbi52bXRhcmdldCAhPT0gbnVsbCAmJiBtbi52bXRhcmdldCAhPT0gdW5kZWZpbmVkLCBcInZtdGFyZ2V0IG11c3QgYmUgZGVmaW5lZFwiKTtcblxuICAgIGFzc2VydChtblsnamF2YS9sYW5nL2ludm9rZS9NZW1iZXJOYW1lL3R5cGUnXS5nZXRDbGFzcygpLmdldEludGVybmFsTmFtZSgpID09PSAnTGphdmEvbGFuZy9pbnZva2UvTWV0aG9kVHlwZTsnLCBcIkV4cGVjdGVkIGEgTWV0aG9kVHlwZSBvYmplY3QuXCIpO1xuICAgIGRlc2NyaXB0b3IgPSAoPEpWTVR5cGVzLmphdmFfbGFuZ19pbnZva2VfTWV0aG9kVHlwZT4gbW5bJ2phdmEvbGFuZy9pbnZva2UvTWVtYmVyTmFtZS90eXBlJ10pLnRvU3RyaW5nKCk7XG4gICAgcGFyYW1UeXBlcyA9IHV0aWwuZ2V0VHlwZXMoZGVzY3JpcHRvcik7XG4gICAgLy8gUmVtb3ZlIHJldHVybiB2YWx1ZS5cbiAgICBwYXJhbVR5cGVzLnBvcCgpO1xuICAgIC8vIFJlbW92ZSBtZXRob2QgaGFuZGxlOyBpdCdzIG5vdCBib3hlZC5cbiAgICBwYXJhbVR5cGVzLnNoaWZ0KCk7XG4gICAgdGhyZWFkLnNldFN0YXR1cyhUaHJlYWRTdGF0dXMuQVNZTkNfV0FJVElORyk7XG4gICAgLy8gTmVlZCB0byBpbmNsdWRlIG1ldGhvZGhhbmRsZSBpbiB0aGUgYXJndW1lbnRzIHRvIHZtdGFyZ2V0LCB3aGljaCBoYW5kbGVzXG4gICAgLy8gaW52b2tpbmcgaXQgYXBwcm9wcmlhdGVseS5cbiAgICBtbi52bXRhcmdldCh0aHJlYWQsIGRlc2NyaXB0b3IsIFttaF0uY29uY2F0KHV0aWwudW5ib3hBcmd1bWVudHModGhyZWFkLCBwYXJhbVR5cGVzLCBhcmdzQm94ZWQuYXJyYXkpKSwgKGU6IEpWTVR5cGVzLmphdmFfbGFuZ19UaHJvd2FibGUsIHJ2OiBhbnkpID0+IHtcbiAgICAgIGlmIChlKSB7XG4gICAgICAgIHRocmVhZC50aHJvd0V4Y2VwdGlvbihlKTtcbiAgICAgIH0gZWxzZSB7XG4gICAgICAgIHRocmVhZC5hc3luY1JldHVybihydik7XG4gICAgICB9XG4gICAgfSk7XG4gIH1cbn1cblxucmVnaXN0ZXJOYXRpdmVzKHtcbiAgJ2phdmEvbGFuZy9DbGFzcyc6IGphdmFfbGFuZ19DbGFzcyxcbiAgJ2phdmEvbGFuZy9DbGFzc0xvYWRlciROYXRpdmVMaWJyYXJ5JzogamF2YV9sYW5nX0NsYXNzTG9hZGVyJE5hdGl2ZUxpYnJhcnksXG4gICdqYXZhL2xhbmcvQ2xhc3NMb2FkZXInOiBqYXZhX2xhbmdfQ2xhc3NMb2FkZXIsXG4gICdqYXZhL2xhbmcvQ29tcGlsZXInOiBqYXZhX2xhbmdfQ29tcGlsZXIsXG4gICdqYXZhL2xhbmcvRG91YmxlJzogamF2YV9sYW5nX0RvdWJsZSxcbiAgJ2phdmEvbGFuZy9GbG9hdCc6IGphdmFfbGFuZ19GbG9hdCxcbiAgJ2phdmEvbGFuZy9PYmplY3QnOiBqYXZhX2xhbmdfT2JqZWN0LFxuICAnamF2YS9sYW5nL1BhY2thZ2UnOiBqYXZhX2xhbmdfUGFja2FnZSxcbiAgJ2phdmEvbGFuZy9Qcm9jZXNzRW52aXJvbm1lbnQnOiBqYXZhX2xhbmdfUHJvY2Vzc0Vudmlyb25tZW50LFxuICAnamF2YS9sYW5nL3JlZmxlY3QvQXJyYXknOiBqYXZhX2xhbmdfcmVmbGVjdF9BcnJheSxcbiAgJ2phdmEvbGFuZy9yZWZsZWN0L1Byb3h5JzogamF2YV9sYW5nX3JlZmxlY3RfUHJveHksXG4gICdqYXZhL2xhbmcvUnVudGltZSc6IGphdmFfbGFuZ19SdW50aW1lLFxuICAnamF2YS9sYW5nL1NlY3VyaXR5TWFuYWdlcic6IGphdmFfbGFuZ19TZWN1cml0eU1hbmFnZXIsXG4gICdqYXZhL2xhbmcvU2h1dGRvd24nOiBqYXZhX2xhbmdfU2h1dGRvd24sXG4gICdqYXZhL2xhbmcvU3RyaWN0TWF0aCc6IGphdmFfbGFuZ19TdHJpY3RNYXRoLFxuICAnamF2YS9sYW5nL1N0cmluZyc6IGphdmFfbGFuZ19TdHJpbmcsXG4gICdqYXZhL2xhbmcvU3lzdGVtJzogamF2YV9sYW5nX1N5c3RlbSxcbiAgJ2phdmEvbGFuZy9UaHJlYWQnOiBqYXZhX2xhbmdfVGhyZWFkLFxuICAnamF2YS9sYW5nL1Rocm93YWJsZSc6IGphdmFfbGFuZ19UaHJvd2FibGUsXG4gICdqYXZhL2xhbmcvVU5JWFByb2Nlc3MnOiBqYXZhX2xhbmdfVU5JWFByb2Nlc3MsXG4gICdqYXZhL2xhbmcvaW52b2tlL01ldGhvZEhhbmRsZU5hdGl2ZXMnOiBqYXZhX2xhbmdfaW52b2tlX01ldGhvZEhhbmRsZU5hdGl2ZXMsXG4gICdqYXZhL2xhbmcvaW52b2tlL01ldGhvZEhhbmRsZSc6IGphdmFfbGFuZ19pbnZva2VfTWV0aG9kSGFuZGxlXG59KTtcbiJdfQ==