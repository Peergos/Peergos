"use strict";

Object.defineProperty(exports, "__esModule", {
  value: true
});

var _createClass = function () { function defineProperties(target, props) { for (var i = 0; i < props.length; i++) { var descriptor = props[i]; descriptor.enumerable = descriptor.enumerable || false; descriptor.configurable = true; if ("value" in descriptor) descriptor.writable = true; Object.defineProperty(target, descriptor.key, descriptor); } } return function (Constructor, protoProps, staticProps) { if (protoProps) defineProperties(Constructor.prototype, protoProps); if (staticProps) defineProperties(Constructor, staticProps); return Constructor; }; }();

function _classCallCheck(instance, Constructor) { if (!(instance instanceof Constructor)) { throw new TypeError("Cannot call a class as a function"); } }

var WrapperUtil = function () {
  function WrapperUtil() {
    _classCallCheck(this, WrapperUtil);
  }

  _createClass(WrapperUtil, null, [{
    key: "dispatchOnJVM",
    value: function dispatchOnJVM(javapoly, messageType, priority, data, resolve, reject) {
      javapoly.dispatcherReady.then(function (dispatcher) {
        return dispatcher.postMessage(messageType, priority, data, function (response) {
          if (response.success) {
            if (resolve) resolve(response.returnValue);
          } else {

            /* This function is added here, because it is not possible to serialise functions across web-worker sandbox */
            response.cause.printStackTrace = function () {
              for (var se in response.cause.stack) {
                console.warn(response.cause.stack[se]);
              }
            };
            if (reject) reject(response.cause);
          }
        });
      });
    }
  }]);

  return WrapperUtil;
}();

exports.default = WrapperUtil;
