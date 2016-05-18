"use strict";

Object.defineProperty(exports, "__esModule", {
  value: true
});

var _typeof = typeof Symbol === "function" && typeof Symbol.iterator === "symbol" ? function (obj) { return typeof obj; } : function (obj) { return obj && typeof Symbol === "function" && obj.constructor === Symbol ? "symbol" : typeof obj; };

var _createClass = function () { function defineProperties(target, props) { for (var i = 0; i < props.length; i++) { var descriptor = props[i]; descriptor.enumerable = descriptor.enumerable || false; descriptor.configurable = true; if ("value" in descriptor) descriptor.writable = true; Object.defineProperty(target, descriptor.key, descriptor); } } return function (Constructor, protoProps, staticProps) { if (protoProps) defineProperties(Constructor.prototype, protoProps); if (staticProps) defineProperties(Constructor, staticProps); return Constructor; }; }();

var _CommonDispatcher2 = require('./CommonDispatcher.js');

var _CommonDispatcher3 = _interopRequireDefault(_CommonDispatcher2);

var _DoppioManager = require('../jvmManager/DoppioManager.js');

var _DoppioManager2 = _interopRequireDefault(_DoppioManager);

var _WrapperUtil = require('../core/WrapperUtil');

var _WrapperUtil2 = _interopRequireDefault(_WrapperUtil);

function _interopRequireDefault(obj) { return obj && obj.__esModule ? obj : { default: obj }; }

function _classCallCheck(instance, Constructor) { if (!(instance instanceof Constructor)) { throw new TypeError("Cannot call a class as a function"); } }

function _possibleConstructorReturn(self, call) { if (!self) { throw new ReferenceError("this hasn't been initialised - super() hasn't been called"); } return call && (typeof call === "object" || typeof call === "function") ? call : self; }

function _inherits(subClass, superClass) { if (typeof superClass !== "function" && superClass !== null) { throw new TypeError("Super expression must either be null or a function, not " + typeof superClass); } subClass.prototype = Object.create(superClass && superClass.prototype, { constructor: { value: subClass, enumerable: false, writable: true, configurable: true } }); if (superClass) Object.setPrototypeOf ? Object.setPrototypeOf(subClass, superClass) : subClass.__proto__ = superClass; }

/* Used for the case when javaploy is running in Browser */

var BrowserDispatcher = function (_CommonDispatcher) {
  _inherits(BrowserDispatcher, _CommonDispatcher);

  function BrowserDispatcher(javapoly) {
    _classCallCheck(this, BrowserDispatcher);

    var _this = _possibleConstructorReturn(this, Object.getPrototypeOf(BrowserDispatcher).call(this, javapoly));

    _this.javapoly = javapoly;
    return _this;
  }

  _createClass(BrowserDispatcher, [{
    key: 'initDoppioManager',
    value: function initDoppioManager(javapoly) {
      var _this2 = this;

      return this.loadExternalJs(javapoly.options.browserfsLibUrl + 'browserfs.min.js').then(function () {
        return _this2.loadExternalJs(javapoly.options.doppioLibUrl + 'doppio.js').then(function () {
          return new _DoppioManager2.default(javapoly);
        });
      });
    }
  }, {
    key: 'postMessage',
    value: function postMessage(messageType, priority, data, callback) {
      var id = this.javaPolyIdCount++;

      this.handleIncomingMessage(id, priority, messageType, data, callback);
    }

    /**
     * load js library file.
     * @param fileSrc
     * 		the uri src of the file
     * @return Promise
     * 		we could use Promise to wait for js loaded finished.
     */

  }, {
    key: 'loadExternalJs',
    value: function loadExternalJs(fileSrc) {
      return new Promise(function (resolve, reject) {
        var jsElm = global.document.createElement("script");
        jsElm.type = "text/javascript";

        if (jsElm.readyState) {
          jsElm.onreadystatechange = function () {
            if (jsElm.readyState == "loaded" || jsElm.readyState == "complete") {
              jsElm.onreadysteatechange = null;
              resolve();
            }
          };
        } else {
          jsElm.onload = function () {
            resolve();
            // FIXME reject when timeout
          };
        }

        jsElm.src = fileSrc;
        global.document.getElementsByTagName("head")[0].appendChild(jsElm);
      });
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

  return BrowserDispatcher;
}(_CommonDispatcher3.default);

exports.default = BrowserDispatcher;
