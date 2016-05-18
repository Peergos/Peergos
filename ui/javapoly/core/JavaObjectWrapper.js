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

function _classCallCheck(instance, Constructor) { if (!(instance instanceof Constructor)) { throw new TypeError("Cannot call a class as a function"); } }

function _possibleConstructorReturn(self, call) { if (!self) { throw new ReferenceError("this hasn't been initialised - super() hasn't been called"); } return call && (typeof call === "object" || typeof call === "function") ? call : self; }

function _inherits(subClass, superClass) { if (typeof superClass !== "function" && superClass !== null) { throw new TypeError("Super expression must either be null or a function, not " + typeof superClass); } subClass.prototype = Object.create(superClass && superClass.prototype, { constructor: { value: subClass, enumerable: false, writable: true, configurable: true } }); if (superClass) Object.setPrototypeOf ? Object.setPrototypeOf(subClass, superClass) : subClass.__proto__ = superClass; }

var JavaObjectWrapper = function (_Wrapper) {
  _inherits(JavaObjectWrapper, _Wrapper);

  function JavaObjectWrapper(javapoly, javaObj, methods, nonFinalFields, finalFields) {
    _classCallCheck(this, JavaObjectWrapper);

    var _this = _possibleConstructorReturn(this, Object.getPrototypeOf(JavaObjectWrapper).call(this, javapoly.dispatcher));

    _this.javapoly = javapoly;
    _this._javaObj = javaObj;
    _this.init(_this, methods, nonFinalFields, finalFields);
    return _this;
  }

  _createClass(JavaObjectWrapper, [{
    key: "getFieldWithJavaDispatching",
    value: function getFieldWithJavaDispatching(name) {
      var _this2 = this;

      return new Promise(function (resolve, reject) {
        var data = [_this2._javaObj, name];
        _WrapperUtil2.default.dispatchOnJVM(_this2.javapoly, 'OBJ_FIELD_READ', 0, data, resolve, reject);
      });
    }
  }, {
    key: "setFieldWithJavaDispatching",
    value: function setFieldWithJavaDispatching(name, value) {
      var _this3 = this;

      return new Promise(function (resolve, reject) {
        var data = [_this3._javaObj, name, value];
        _WrapperUtil2.default.dispatchOnJVM(_this3.javapoly, 'OBJ_FIELD_WRITE', 0, data, resolve, reject);
      });
    }
  }, {
    key: "isReflectMethod",
    value: function isReflectMethod(methodName) {
      return false;
    }
  }, {
    key: "runMethodWithJavaDispatching",
    value: function runMethodWithJavaDispatching(methodName, argumentsList) {
      var _this4 = this;

      return new Promise(function (resolve, reject) {
        var data = [_this4._javaObj, methodName, argumentsList];
        _WrapperUtil2.default.dispatchOnJVM(_this4.javapoly, 'OBJ_METHOD_INVOCATION', 0, data, resolve, reject);
      });
    }
  }]);

  return JavaObjectWrapper;
}(_Wrapper3.default);

exports.default = JavaObjectWrapper;
