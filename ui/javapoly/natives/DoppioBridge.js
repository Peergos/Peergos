(function e(t,n,r){function s(o,u){if(!n[o]){if(!t[o]){var a=typeof require=="function"&&require;if(!u&&a)return a(o,!0);if(i)return i(o,!0);var f=new Error("Cannot find module '"+o+"'");throw f.code="MODULE_NOT_FOUND",f}var l=n[o]={exports:{}};t[o][0].call(l.exports,function(e){var n=t[o][1][e];return s(n?n:e)},l,l.exports,e,t,n,r)}return n[o].exports}var i=typeof require=="function"&&require;for(var o=0;o<r.length;o++)s(r[o]);return s})({1:[function(require,module,exports){
"use strict";

var _typeof = typeof Symbol === "function" && typeof Symbol.iterator === "symbol" ? function (obj) { return typeof obj; } : function (obj) { return obj && typeof Symbol === "function" && obj.constructor === Symbol ? "symbol" : typeof obj; };

var javapoly0;

function toPrimitiveTypeName(name) {
  // TODO: Consider using a map
  switch (name) {
    case 'Ljava/lang/Integer;':
      return "I";
    case 'Ljava/lang/Double;':
      return "D";
    case 'Ljava/lang/Byte;':
      return "B";
    case 'Ljava/lang/Character;':
      return "C";
    case 'Ljava/lang/Float;':
      return "F";
    case 'Ljava/lang/Long;':
      return "J";
    case 'Ljava/lang/Short;':
      return "S";
    case 'Ljava/lang/Boolean;':
      return "Z";
    case 'Ljava/lang/Void;':
      return "V";
    default:
      return name;
  }
};

function wrapObject(thread, obj) {
  var objectType = typeof obj === "undefined" ? "undefined" : _typeof(obj);
  if (Array.isArray(obj)) {
    return wrapArray(thread, obj);
  } else if (objectType === 'string') {
    return wrapString(thread, obj);
  } else if (objectType === 'number') {
    return wrapNumber(thread, obj);
  } else if (objectType === 'boolean') {
    // We need to cast to number because Doppio JVM represents booleans as numbers
    var intValue = obj ? 1 : 0;
    return util.boxPrimitiveValue(thread, 'Z', intValue);
  } else {
    var possibleJavaObj = javapoly0.unwrapJavaObject(obj);
    return possibleJavaObj == null ? obj : possibleJavaObj;
  }
}

function wrapString(thread, obj) {
  return javapoly0.jvm.internString(obj);
}

function wrapArray(thread, obj) {
  var wrappedArr = [];
  for (var i = 0; i < obj.length; i++) {
    wrappedArr.push(wrapObject(thread, obj[i]));
  }
  return util.newArrayFromData(thread, thread.getBsCl(), '[Ljava/lang/Object;', wrappedArr);
}

function wrapNumber(thread, obj) {
  return util.boxPrimitiveValue(thread, 'D', obj);
}

function getPublicFields(obj) {
  // Mimicking a set, based on http://stackoverflow.com/a/18890005
  var nonFinalNamesSet = Object.create(null);
  var finalNamesSet = Object.create(null);

  var fields = obj.constructor.cls.fields;
  // console.log("cls", obj.constructor.cls, " fields", fields);
  for (var i in fields) {
    var field = fields[i];
    if (field.accessFlags.isPublic()) {
      if (field.accessFlags.isFinal()) {
        finalNamesSet[field.name] = true;
      } else {
        nonFinalNamesSet[field.name] = true;
      }
    }
  }
  return { nonFinal: Object.keys(nonFinalNamesSet), "final": Object.keys(finalNamesSet) };
}

function getPublicInstanceMethodsFromClass(cls, set) {
  var methods = cls.getMethods();
  for (var i in methods) {
    var method = methods[i];
    set[method.name] = true;
  }
  if (cls.superClass) {
    getPublicInstanceMethodsFromClass(cls.superClass, set);
  }
}

function getPublicInstanceMethods(obj) {
  // Mimicking a set, based on http://stackoverflow.com/a/18890005
  var methodNamesSet = Object.create(null);
  getPublicInstanceMethodsFromClass(obj.constructor.cls, methodNamesSet);
  return Object.keys(methodNamesSet);
}

function isValidNumber(gLong) {
  var max = 2097151;
  var absgLong = gLong.isNegative() ? gLong.negate() : gLong;
  return absgLong.getHighBits() < max || absgLong.getHighBits() === max && absgLong.getLowBits() < 0;
}

/* Converts a Java object to a JS friendly object. Primitive numbers, primitive booleans, strings and arrays are
 * converted to their JS counter-parts. Others are wrapped with JavaObjectWrapper */
function javaObjToJS(thread, obj) {
  if (obj === null) return null;
  if (obj['getClass']) {
    var cls = obj.getClass();
    if (cls.className === 'Ljava/lang/String;') {
      return obj.toString();
    } else if (cls.className === 'Ljava/lang/Boolean;') {
      return obj['java/lang/Boolean/value'] == 1;
    } else if (cls.className === 'Ljava/lang/Long;') {
      var gLong = obj.unbox();
      if (isValidNumber(gLong)) {
        return gLong.toNumber();
      } else {
        throw new RangeError('Unfortunately, JavaScript does not yet support 64 bit integers');
      }
    } else if (cls.className.charAt(0) === '[') {
      var nativeArray = [];
      for (var i = 0; i < obj.array.length; i++) {
        nativeArray.push(javaObjToJS(thread, obj.array[i]));
      }
      return nativeArray;
    } else {
      if (obj.unbox) {
        return obj.unbox();
      } else {
        var fields = getPublicFields(obj);
        return javapoly0.wrapJavaObject(obj, getPublicInstanceMethods(obj), fields.nonFinal, fields.final);
      }
    }
  }
}

function flatThrowableToJS(ft) {
  var cause = ft["com/javapoly/FlatThrowable/causedBy"];
  var name = ft["com/javapoly/FlatThrowable/name"];
  var message = ft["com/javapoly/FlatThrowable/message"];
  return {
    name: name === null ? null : name.toString(),
    message: message === null ? null : message.toString(),
    stack: ft["com/javapoly/FlatThrowable/stack"].array.map(function (e) {
      return e.toString();
    }),
    causedBy: cause === null ? null : flatThrowableToJS(cause)
  };
}

/* This function is used to wrap and return an object and its type to Java land.
 * It returns an array of Object[], where the first element is a string describing the JS type,
 * and the second is the obj.
 */
function getRawType(thread, obj) {
  return util.newArrayFromData(thread, thread.getBsCl(), '[Ljava/lang/Object;', [util.initString(thread.getBsCl(), typeof obj === "undefined" ? "undefined" : _typeof(obj)), obj]);
}

registerNatives({
  'com/javapoly/DoppioBridge': {

    'evalRaw(Ljava/lang/String;)[Ljava/lang/Object;': function evalRawLjavaLangStringLjavaLangObject(thread, toEval) {
      var expr = toEval.toString();
      var res = eval(expr);
      return util.newArrayFromData(thread, thread.getBsCl(), '[Ljava/lang/Object;', [util.initString(thread.getBsCl(), typeof res === "undefined" ? "undefined" : _typeof(res)), res]);
    },

    'dispatchMessage(Ljava/lang/String;)V': function dispatchMessageLjavaLangStringV(thread, obj, msgId) {
      var callback = javapoly0.dispatcher.getMessageCallback(msgId);
      thread.setStatus(6); // ASYNC_WAITING
      callback(thread, function () {
        thread.asyncReturn();
      });
    },

    'returnResult(Ljava/lang/String;Ljava/lang/Object;)V': function returnResultLjavaLangStringLjavaLangObjectV(thread, obj, msgId, returnValue) {
      try {
        javapoly0.dispatcher.callbackMessage(msgId, { success: true, returnValue: javaObjToJS(thread, returnValue) });
      } catch (e) {
        javapoly0.dispatcher.callbackMessage(msgId, { success: false, cause: e });
      }
    },

    'returnErrorFlat(Ljava/lang/String;Lcom/javapoly/FlatThrowable;)V': function returnErrorFlatLjavaLangStringLcomJavapolyFlatThrowableV(thread, obj, msgId, flatThrowable) {
      javapoly0.dispatcher.callbackMessage(msgId, { success: false, cause: flatThrowableToJS(flatThrowable) });
    },

    'getMessageId()Ljava/lang/String;': function getMessageIdLjavaLangString(thread, obj) {
      var id = javapoly0.dispatcher.getMessageId();
      if (id) {
        return wrapObject(thread, id);
      } else {
        thread.setStatus(6); // ASYNC_WAITING
        javapoly0.dispatcher.setJavaPolyCallback(function () {
          javapoly0.dispatcher.setJavaPolyCallback(null);
          thread.asyncReturn(wrapObject(thread, javapoly0.dispatcher.getMessageId()));
        });
      }
    },

    'isJSNativeObj(Ljava/lang/Object;)Z': function isJSNativeObjLjavaLangObjectZ(thread, e) {
      // TODO: find a better way to check for a JS native object
      return (typeof e === "undefined" ? "undefined" : _typeof(e)) === "object" && e !== null && !e.hasOwnProperty("$monitor");
    },

    'getMessageType(Ljava/lang/String;)Ljava/lang/String;': function getMessageTypeLjavaLangStringLjavaLangString(thread, obj, msgId) {
      var unwrappedData = javapoly0.dispatcher.getMessageType(msgId);
      if (typeof unwrappedData !== 'undefined') {
        return wrapObject(thread, unwrappedData);
      } else {
        return null;
      }
    },

    'getData(Ljava/lang/String;)[Ljava/lang/Object;': function getDataLjavaLangStringLjavaLangObject(thread, obj, msgId) {
      var unwrappedData = javapoly0.dispatcher.getMessageData(msgId);
      if (typeof unwrappedData !== 'undefined') {
        return wrapObject(thread, unwrappedData);
      } else {
        return null;
      }
    },

    'setJavaPolyInstanceId(Ljava/lang/String;)V': function setJavaPolyInstanceIdLjavaLangStringV(thread, obj, javapolyId) {
      javapoly0 = JavaPoly.getInstance(javapolyId);
    },

    'getRawType(Ljava/lang/Object;)[Ljava/lang/Object;': function getRawTypeLjavaLangObjectLjavaLangObject(thread, obj) {
      return getRawType(thread, obj);
    }
  },

  'com/javapoly/reflect/DoppioJSObject': {
    'getProperty(Ljava/lang/Object;Ljava/lang/String;)[Ljava/lang/Object;': function getPropertyLjavaLangObjectLjavaLangStringLjavaLangObject(thread, obj, name) {
      var nameStr = name.toString();
      return getRawType(thread, obj[nameStr]);
    },

    'invoke(Ljava/lang/Object;[Ljava/lang/Object;)[Ljava/lang/Object;': function invokeLjavaLangObjectLjavaLangObjectLjavaLangObject(thread, toInvoke, args) {
      var ubArgs = args.array.map(function (e) {
        if ((typeof e === "undefined" ? "undefined" : _typeof(e)) === "object" && typeof e['getClass'] === "function") {
          var intName = e.getClass().getInternalName();
          if (util.is_primitive_type(toPrimitiveTypeName(intName))) {
            return e.unbox();
          } else if (intName === 'Ljava/lang/String;') {
            return e.toString();
          } else {
            return e;
          }
        } else {
          return e;
        }
      });
      var res = toInvoke.apply(null, ubArgs);
      return getRawType(thread, res);
    }
  },

  'com/javapoly/reflect/DoppioJSPrimitive': {
    'asDouble(Ljava/lang/Object;)D': function asDoubleLjavaLangObjectD(thread, arg0) {
      return arg0;
    },

    'asInteger(Ljava/lang/Object;)I': function asIntegerLjavaLangObjectI(thread, arg0) {
      return arg0;
    },

    'asLong(Ljava/lang/Object;)J': function asLongLjavaLangObjectJ(thread, arg0) {
      return Doppio.VM.Long.fromNumber(arg0);
    },

    'asString(Ljava/lang/Object;)Ljava/lang/String;': function asStringLjavaLangObjectLjavaLangString(thread, arg0) {
      return util.initString(thread.getBsCl(), arg0);
    }
  }
});

},{}]},{},[1]);
