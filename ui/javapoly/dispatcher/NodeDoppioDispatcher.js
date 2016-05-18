"use strict";

Object.defineProperty(exports, "__esModule", {
  value: true
});

var _typeof = typeof Symbol === "function" && typeof Symbol.iterator === "symbol" ? function (obj) { return typeof obj; } : function (obj) { return obj && typeof Symbol === "function" && obj.constructor === Symbol ? "symbol" : typeof obj; };

var _createClass = function () { function defineProperties(target, props) { for (var i = 0; i < props.length; i++) { var descriptor = props[i]; descriptor.enumerable = descriptor.enumerable || false; descriptor.configurable = true; if ("value" in descriptor) descriptor.writable = true; Object.defineProperty(target, descriptor.key, descriptor); } } return function (Constructor, protoProps, staticProps) { if (protoProps) defineProperties(Constructor.prototype, protoProps); if (staticProps) defineProperties(Constructor, staticProps); return Constructor; }; }();

var _CommonDispatcher2 = require('./CommonDispatcher.js');

var _CommonDispatcher3 = _interopRequireDefault(_CommonDispatcher2);

var _NodeDoppioManager = require('../jvmManager/NodeDoppioManager.js');

var _NodeDoppioManager2 = _interopRequireDefault(_NodeDoppioManager);

function _interopRequireDefault(obj) { return obj && obj.__esModule ? obj : { default: obj }; }

function _classCallCheck(instance, Constructor) { if (!(instance instanceof Constructor)) { throw new TypeError("Cannot call a class as a function"); } }

function _possibleConstructorReturn(self, call) { if (!self) { throw new ReferenceError("this hasn't been initialised - super() hasn't been called"); } return call && (typeof call === "object" || typeof call === "function") ? call : self; }

function _inherits(subClass, superClass) { if (typeof superClass !== "function" && superClass !== null) { throw new TypeError("Super expression must either be null or a function, not " + typeof superClass); } subClass.prototype = Object.create(superClass && superClass.prototype, { constructor: { value: subClass, enumerable: false, writable: true, configurable: true } }); if (superClass) Object.setPrototypeOf ? Object.setPrototypeOf(subClass, superClass) : subClass.__proto__ = superClass; }

/* Used for the case when javaploy is running in node with doppio */

var NodeDoppioDispatcher = function (_CommonDispatcher) {
  _inherits(NodeDoppioDispatcher, _CommonDispatcher);

  function NodeDoppioDispatcher(javapoly) {
    _classCallCheck(this, NodeDoppioDispatcher);

    return _possibleConstructorReturn(this, Object.getPrototypeOf(NodeDoppioDispatcher).call(this, javapoly));
  }

  _createClass(NodeDoppioDispatcher, [{
    key: 'initDoppioManager',
    value: function initDoppioManager(javapoly) {
      return Promise.resolve(new _NodeDoppioManager2.default(javapoly));
    }
  }, {
    key: 'postMessage',
    value: function postMessage(messageType, priority, data, callback) {
      var id = this.javaPolyIdCount++;

      this.handleIncomingMessage(id, priority, messageType, data, callback);
    }
  }, {
    key: 'reflect',
    value: function reflect(jsObj) {
      return jsObj;
    }
  }, {
    key: 'unreflect',
    value: function unreflect(result) {
      if (!!result && (typeof result === 'undefined' ? 'undefined' : _typeof(result)) === "object" && !!result._javaObj) {
        var className = result._javaObj.getClass().className;
        if (className === "Lcom/javapoly/reflect/DoppioJSObject;" || className === "Lcom/javapoly/reflect/DoppioJSPrimitive;") {
          return result._javaObj["com/javapoly/reflect/DoppioJSValue/rawValue"];
        }
      }
      return result;
    }
  }]);

  return NodeDoppioDispatcher;
}(_CommonDispatcher3.default);

exports.default = NodeDoppioDispatcher;
