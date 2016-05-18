"use strict";

Object.defineProperty(exports, "__esModule", {
  value: true
});

var _createClass = function () { function defineProperties(target, props) { for (var i = 0; i < props.length; i++) { var descriptor = props[i]; descriptor.enumerable = descriptor.enumerable || false; descriptor.configurable = true; if ("value" in descriptor) descriptor.writable = true; Object.defineProperty(target, descriptor.key, descriptor); } } return function (Constructor, protoProps, staticProps) { if (protoProps) defineProperties(Constructor.prototype, protoProps); if (staticProps) defineProperties(Constructor, staticProps); return Constructor; }; }();

var _JavaClassWrapper = require("./JavaClassWrapper");

var _JavaClassWrapper2 = _interopRequireDefault(_JavaClassWrapper);

var _WrapperUtil = require("./WrapperUtil");

var _WrapperUtil2 = _interopRequireDefault(_WrapperUtil);

function _interopRequireDefault(obj) { return obj && obj.__esModule ? obj : { default: obj }; }

function _classCallCheck(instance, Constructor) { if (!(instance instanceof Constructor)) { throw new TypeError("Cannot call a class as a function"); } }

var Wrapper = function () {
  function Wrapper(dispatcher) {
    _classCallCheck(this, Wrapper);

    this.dispatcher = dispatcher;
  }

  _createClass(Wrapper, [{
    key: "init",
    value: function init(obj, methods, nonFinalFields, finalFields) {
      var wrapper = this;

      // Add method handlers
      var _iteratorNormalCompletion = true;
      var _didIteratorError = false;
      var _iteratorError = undefined;

      try {
        var _loop = function _loop() {
          var name = _step.value;

          obj[name] = function () {
            return wrapper.runMethodWithJSReflection(name, Array.prototype.slice.call(arguments));
          };
        };

        for (var _iterator = methods[Symbol.iterator](), _step; !(_iteratorNormalCompletion = (_step = _iterator.next()).done); _iteratorNormalCompletion = true) {
          _loop();
        }
      } catch (err) {
        _didIteratorError = true;
        _iteratorError = err;
      } finally {
        try {
          if (!_iteratorNormalCompletion && _iterator.return) {
            _iterator.return();
          }
        } finally {
          if (_didIteratorError) {
            throw _iteratorError;
          }
        }
      }

      function methodExists(name) {
        return methods.findIndex(function (n) {
          return n == name;
        }) >= 0;
      }

      // Add getters and setters for non-final fields
      var _iteratorNormalCompletion2 = true;
      var _didIteratorError2 = false;
      var _iteratorError2 = undefined;

      try {
        var _loop2 = function _loop2() {
          var name = _step2.value;

          if (!methodExists(name)) {
            Object.defineProperty(obj, name, {
              get: function get() {
                return wrapper.getFieldWithJavaDispatching(name);
              },
              set: function set(newValue) {
                wrapper.setFieldWithJavaDispatching(name, newValue);
              }
            });
          }
        };

        for (var _iterator2 = nonFinalFields[Symbol.iterator](), _step2; !(_iteratorNormalCompletion2 = (_step2 = _iterator2.next()).done); _iteratorNormalCompletion2 = true) {
          _loop2();
        }

        // Add getters for final fields
      } catch (err) {
        _didIteratorError2 = true;
        _iteratorError2 = err;
      } finally {
        try {
          if (!_iteratorNormalCompletion2 && _iterator2.return) {
            _iterator2.return();
          }
        } finally {
          if (_didIteratorError2) {
            throw _iteratorError2;
          }
        }
      }

      var _iteratorNormalCompletion3 = true;
      var _didIteratorError3 = false;
      var _iteratorError3 = undefined;

      try {
        var _loop3 = function _loop3() {
          var name = _step3.value;

          if (!methodExists(name)) {
            Object.defineProperty(obj, name, {
              get: function get() {
                return wrapper.getFieldWithJavaDispatching(name);
              }
            });
          }
        };

        for (var _iterator3 = finalFields[Symbol.iterator](), _step3; !(_iteratorNormalCompletion3 = (_step3 = _iterator3.next()).done); _iteratorNormalCompletion3 = true) {
          _loop3();
        }
      } catch (err) {
        _didIteratorError3 = true;
        _iteratorError3 = err;
      } finally {
        try {
          if (!_iteratorNormalCompletion3 && _iterator3.return) {
            _iterator3.return();
          }
        } finally {
          if (_didIteratorError3) {
            throw _iteratorError3;
          }
        }
      }
    }
  }, {
    key: "runMethodWithJSReflection",
    value: function runMethodWithJSReflection(methodName, args) {
      var wrapper = this;
      var okToReflect = !wrapper.isReflectMethod(methodName);

      var reflectedArgs = args.map(function (e) {
        return wrapper.dispatcher.reflect(e);
      });

      var resultPromise = wrapper.runMethodWithJavaDispatching(methodName, reflectedArgs);

      if (okToReflect) {
        return resultPromise.then(function (result) {
          return wrapper.dispatcher.unreflect(result);
        });
      } else {
        return resultPromise;
      }
    }
  }]);

  return Wrapper;
}();

exports.default = Wrapper;
