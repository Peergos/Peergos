"use strict";

Object.defineProperty(exports, "__esModule", {
  value: true
});

var _createClass = function () { function defineProperties(target, props) { for (var i = 0; i < props.length; i++) { var descriptor = props[i]; descriptor.enumerable = descriptor.enumerable || false; descriptor.configurable = true; if ("value" in descriptor) descriptor.writable = true; Object.defineProperty(target, descriptor.key, descriptor); } } return function (Constructor, protoProps, staticProps) { if (protoProps) defineProperties(Constructor.prototype, protoProps); if (staticProps) defineProperties(Constructor, staticProps); return Constructor; }; }();

var _Wrapper2 = require("./Wrapper");

var _Wrapper3 = _interopRequireDefault(_Wrapper2);

var _WrapperUtil = require("./WrapperUtil");

var _WrapperUtil2 = _interopRequireDefault(_WrapperUtil);

function _interopRequireDefault(obj) { return obj && obj.__esModule ? obj : { default: obj }; }

function _toConsumableArray(arr) { if (Array.isArray(arr)) { for (var i = 0, arr2 = Array(arr.length); i < arr.length; i++) { arr2[i] = arr[i]; } return arr2; } else { return Array.from(arr); } }

function _classCallCheck(instance, Constructor) { if (!(instance instanceof Constructor)) { throw new TypeError("Cannot call a class as a function"); } }

function _possibleConstructorReturn(self, call) { if (!self) { throw new ReferenceError("this hasn't been initialised - super() hasn't been called"); } return call && (typeof call === "object" || typeof call === "function") ? call : self; }

function _inherits(subClass, superClass) { if (typeof superClass !== "function" && superClass !== null) { throw new TypeError("Super expression must either be null or a function, not " + typeof superClass); } subClass.prototype = Object.create(superClass && superClass.prototype, { constructor: { value: subClass, enumerable: false, writable: true, configurable: true } }); if (superClass) Object.setPrototypeOf ? Object.setPrototypeOf(subClass, superClass) : subClass.__proto__ = superClass; }

var JavaClassWrapper = function (_Wrapper) {
  _inherits(JavaClassWrapper, _Wrapper);

  _createClass(JavaClassWrapper, null, [{
    key: "runProxyMethod",
    value: function runProxyMethod(javapoly, methodObject, argumentsList) {
      return new Promise(function (resolve, reject) {
        JavaClassWrapper.getClassWrapperByName(javapoly, methodObject._parent._identifier).then(function (classWrapper) {
          classWrapper[methodObject._name].apply(classWrapper, _toConsumableArray(argumentsList)).then(function (returnValue) {
            return resolve(returnValue);
          });
        });
      });
    }
  }, {
    key: "getClassWrapperByName",
    value: function getClassWrapperByName(javapoly, clsName) {
      return new Promise(function (resolve, reject) {
        if (JavaClassWrapper.cache === undefined) JavaClassWrapper.cache = {};
        if (JavaClassWrapper.cache[javapoly.getId()] === undefined) JavaClassWrapper.cache[javapoly.getId()] = {};
        var cache = JavaClassWrapper.cache[javapoly.getId()];
        if (cache[clsName] !== undefined) {
          resolve(cache[clsName]);
        } else {
          var data = [clsName];
          _WrapperUtil2.default.dispatchOnJVM(javapoly, 'CLASS_LOADING', 0, data, function (result) {
            var javaClassWrapper = new JavaClassWrapper(javapoly, result[0], result[1], result[2], clsName);
            cache[clsName] = javaClassWrapper;
            resolve(javaClassWrapper);
          }, reject);
        }
      });
    }
  }]);

  function JavaClassWrapper(javapoly, methods, nonFinalFields, finalFields, clsName) {
    var _ret;

    _classCallCheck(this, JavaClassWrapper);

    var _this = _possibleConstructorReturn(this, Object.getPrototypeOf(JavaClassWrapper).call(this, javapoly.dispatcher));

    _this.clsName = clsName;
    _this.javapoly = javapoly;

    var wrapper = _this;
    function objConstructorFunction() {
      return wrapper.runConstructorWithJavaDispatching(Array.prototype.slice.call(arguments));
    }

    // Note: There is some JS magic here. This JS constructor function returns an object which is different than the one
    // being constructed (this). The returned object is a function extended with this. The idea is that `new` operator
    // can be called on the returned object to mimic Java's `new` operator.
    var retFunction = Object.assign(objConstructorFunction, _this);

    _this.init(retFunction, methods, nonFinalFields, finalFields);

    return _ret = retFunction, _possibleConstructorReturn(_this, _ret);
  }

  _createClass(JavaClassWrapper, [{
    key: "runConstructorWithJavaDispatching",
    value: function runConstructorWithJavaDispatching(argumentsList) {
      var _this2 = this;

      return new Promise(function (resolve, reject) {
        var data = [_this2.clsName, argumentsList];
        _WrapperUtil2.default.dispatchOnJVM(_this2.javapoly, 'CLASS_CONSTRUCTOR_INVOCATION', 0, data, resolve, reject);
      });
    }
  }, {
    key: "runMethodWithJavaDispatching",
    value: function runMethodWithJavaDispatching(methodName, argumentsList) {
      var _this3 = this;

      return new Promise(function (resolve, reject) {
        var data = [_this3.clsName, methodName, argumentsList];
        _WrapperUtil2.default.dispatchOnJVM(_this3.javapoly, 'CLASS_METHOD_INVOCATION', 0, data, resolve, reject);
      });
    }

    // This is to prevent recursion, because reflectJSValue itself needs to be dispatched on the JVM.

  }, {
    key: "isReflectMethod",
    value: function isReflectMethod(methodName) {
      return methodName === "reflectJSValue" && this.clsName == "com.javapoly.Main";
    }
  }, {
    key: "getFieldWithJavaDispatching",
    value: function getFieldWithJavaDispatching(name) {
      var _this4 = this;

      return new Promise(function (resolve, reject) {
        var data = [_this4.clsName, name];
        _WrapperUtil2.default.dispatchOnJVM(_this4.javapoly, 'CLASS_FIELD_READ', 0, data, resolve, reject);
      });
    }
  }, {
    key: "setFieldWithJavaDispatching",
    value: function setFieldWithJavaDispatching(name, value) {
      var _this5 = this;

      return new Promise(function (resolve, reject) {
        var data = [_this5.clsName, name, value];
        _WrapperUtil2.default.dispatchOnJVM(_this5.javapoly, 'CLASS_FIELD_WRITE', 0, data, resolve, reject);
      });
    }
  }]);

  return JavaClassWrapper;
}(_Wrapper3.default);

exports.default = JavaClassWrapper;
