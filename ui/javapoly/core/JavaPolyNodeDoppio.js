"use strict";

Object.defineProperty(exports, "__esModule", {
  value: true
});

var _createClass = function () { function defineProperties(target, props) { for (var i = 0; i < props.length; i++) { var descriptor = props[i]; descriptor.enumerable = descriptor.enumerable || false; descriptor.configurable = true; if ("value" in descriptor) descriptor.writable = true; Object.defineProperty(target, descriptor.key, descriptor); } } return function (Constructor, protoProps, staticProps) { if (protoProps) defineProperties(Constructor.prototype, protoProps); if (staticProps) defineProperties(Constructor, staticProps); return Constructor; }; }();

var _underscore = require('underscore');

var _ = _interopRequireWildcard(_underscore);

var _JavaPolyBase2 = require('./JavaPolyBase');

var _JavaPolyBase3 = _interopRequireDefault(_JavaPolyBase2);

var _ProxyWrapper = require('./ProxyWrapper');

var _ProxyWrapper2 = _interopRequireDefault(_ProxyWrapper);

var _NodeDoppioDispatcher = require('../dispatcher/NodeDoppioDispatcher.js');

var _NodeDoppioDispatcher2 = _interopRequireDefault(_NodeDoppioDispatcher);

var _WrapperUtil = require('./WrapperUtil.js');

var _WrapperUtil2 = _interopRequireDefault(_WrapperUtil);

var _CommonUtils = require('./CommonUtils.js');

var _CommonUtils2 = _interopRequireDefault(_CommonUtils);

var _NodeManager = require('./NodeManager');

var _NodeManager2 = _interopRequireDefault(_NodeManager);

function _interopRequireDefault(obj) { return obj && obj.__esModule ? obj : { default: obj }; }

function _interopRequireWildcard(obj) { if (obj && obj.__esModule) { return obj; } else { var newObj = {}; if (obj != null) { for (var key in obj) { if (Object.prototype.hasOwnProperty.call(obj, key)) newObj[key] = obj[key]; } } newObj.default = obj; return newObj; } }

function _classCallCheck(instance, Constructor) { if (!(instance instanceof Constructor)) { throw new TypeError("Cannot call a class as a function"); } }

function _possibleConstructorReturn(self, call) { if (!self) { throw new ReferenceError("this hasn't been initialised - super() hasn't been called"); } return call && (typeof call === "object" || typeof call === "function") ? call : self; }

function _inherits(subClass, superClass) { if (typeof superClass !== "function" && superClass !== null) { throw new TypeError("Super expression must either be null or a function, not " + typeof superClass); } subClass.prototype = Object.create(superClass && superClass.prototype, { constructor: { value: subClass, enumerable: false, writable: true, configurable: true } }); if (superClass) Object.setPrototypeOf ? Object.setPrototypeOf(subClass, superClass) : subClass.__proto__ = superClass; }

var DEFAULT_JAVAPOLY_NODE_DOPPIO_OPTIONS = {
  doppioBase: '',
  javapolyBase: '',

  /**
   * When page is loading look for all corresponding MIME-types and create objects for Java automatically
   * @type {Boolean}
   */
  initOnStart: true,

  /**
   * Directory name that stores all class-files, jars and/or java-files
   * @type {String}
   */
  storageDir: _NodeManager2.default.getTempDirectory(),

  /**
   * Enable Java Assertions
   *
   * @type {boolean}
   */
  assertionsEnabled: false
};

/**
 * Main JavaPoly class that do all underliying job for initialization
 * Simple usage:
 * 1. Create object: (new JavaPolyStandalone());
 * 2. Use JavaPoly API classes such as `J` and `Java`.
 *
 * (new JavaPoly());
 * JavaPoly.type(....).then(() => {  } );
 */

var JavaPoly = function (_JavaPolyBase) {
  _inherits(JavaPoly, _JavaPolyBase);

  function JavaPoly(_options) {
    var _ret;

    _classCallCheck(this, JavaPoly);

    var options = _.extend(DEFAULT_JAVAPOLY_NODE_DOPPIO_OPTIONS, _options);


    // Init objects for user to make possible start to work with JavaPoly instantly
    // only bind this api to global.window for the default javapoly instance (the 1th instance, created in main.js).

    var _this = _possibleConstructorReturn(this, Object.getPrototypeOf(JavaPoly).call(this, options));

    return _ret = _this.initApiObjects(_JavaPolyBase3.default.idCount === 1 ? global : undefined), _possibleConstructorReturn(_this, _ret);
  }

  _createClass(JavaPoly, [{
    key: 'beginLoading',
    value: function beginLoading(resolveDispatcherReady) {
      this.dispatcher = new _NodeDoppioDispatcher2.default(this);
      resolveDispatcherReady(this.dispatcher);

      _WrapperUtil2.default.dispatchOnJVM(this, 'META_START_JVM', 0, null);
    }
  }, {
    key: 'processScripts',
    value: function processScripts() {
      // NOP
    }
  }]);

  return JavaPoly;
}(_JavaPolyBase3.default);

exports.default = JavaPoly;
