"use strict";

Object.defineProperty(exports, "__esModule", {
  value: true
});

var _createClass = function () { function defineProperties(target, props) { for (var i = 0; i < props.length; i++) { var descriptor = props[i]; descriptor.enumerable = descriptor.enumerable || false; descriptor.configurable = true; if ("value" in descriptor) descriptor.writable = true; Object.defineProperty(target, descriptor.key, descriptor); } } return function (Constructor, protoProps, staticProps) { if (protoProps) defineProperties(Constructor.prototype, protoProps); if (staticProps) defineProperties(Constructor, staticProps); return Constructor; }; }();

var _JavaClassWrapper = require('./JavaClassWrapper');

var _JavaClassWrapper2 = _interopRequireDefault(_JavaClassWrapper);

function _interopRequireDefault(obj) { return obj && obj.__esModule ? obj : { default: obj }; }

function _classCallCheck(instance, Constructor) { if (!(instance instanceof Constructor)) { throw new TypeError("Cannot call a class as a function"); } }

var ProxyWrapper = function () {
  function ProxyWrapper() {
    _classCallCheck(this, ProxyWrapper);
  }

  _createClass(ProxyWrapper, null, [{
    key: 'createEntity',
    value: function createEntity(javapoly, name, parent) {
      var _this = this;

      // We don't now in advance is it a function or just an Object
      // But objects cannot be called, so it is a function
      var object = function object() {};
      object._parent = parent;
      object._name = name;
      if (parent !== null) {
        object._identifier = (parent._identifier === null ? '' : parent._identifier + '.') + name;
      } else {
        object._identifier = name;
      }
      object._call = function (thisArg, argumentsList) {
        return new Promise(function (resolve, reject) {
          _JavaClassWrapper2.default.runProxyMethod(javapoly, object, argumentsList).then(function (rv) {
            return resolve(rv);
          });
        });
      };

      var proxy = new Proxy(object, {
        get: function get(target, property) {
          if (!target.hasOwnProperty(property)) {
            target[property] = _this.createEntity(javapoly, property, target);
          }
          return target[property];
        },
        apply: function apply(target, thisArg, argumentsList) {
          return target._call(thisArg, argumentsList);
        }
      });

      return proxy;
    }
  }, {
    key: 'createRootEntity',
    value: function createRootEntity(javapoly, name) {
      return this.createEntity(javapoly, name, null);
    }
  }]);

  return ProxyWrapper;
}();

exports.default = ProxyWrapper;
