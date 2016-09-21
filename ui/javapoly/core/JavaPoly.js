"use strict";

Object.defineProperty(exports, "__esModule", {
  value: true
});

var _createClass = function () { function defineProperties(target, props) { for (var i = 0; i < props.length; i++) { var descriptor = props[i]; descriptor.enumerable = descriptor.enumerable || false; descriptor.configurable = true; if ("value" in descriptor) descriptor.writable = true; Object.defineProperty(target, descriptor.key, descriptor); } } return function (Constructor, protoProps, staticProps) { if (protoProps) defineProperties(Constructor.prototype, protoProps); if (staticProps) defineProperties(Constructor, staticProps); return Constructor; }; }();

var _underscore = require('underscore');

var _ = _interopRequireWildcard(_underscore);

var _JavaPolyBase2 = require('./JavaPolyBase');

var _JavaPolyBase3 = _interopRequireDefault(_JavaPolyBase2);

var _BrowserDispatcher = require('../dispatcher/BrowserDispatcher.js');

var _BrowserDispatcher2 = _interopRequireDefault(_BrowserDispatcher);

var _WorkerCallBackDispatcher = require('../dispatcher/WorkerCallBackDispatcher.js');

var _WorkerCallBackDispatcher2 = _interopRequireDefault(_WorkerCallBackDispatcher);

var _WrapperUtil = require('./WrapperUtil.js');

var _WrapperUtil2 = _interopRequireDefault(_WrapperUtil);

var _CommonUtils = require('./CommonUtils.js');

var _CommonUtils2 = _interopRequireDefault(_CommonUtils);

function _interopRequireDefault(obj) { return obj && obj.__esModule ? obj : { default: obj }; }

function _interopRequireWildcard(obj) { if (obj && obj.__esModule) { return obj; } else { var newObj = {}; if (obj != null) { for (var key in obj) { if (Object.prototype.hasOwnProperty.call(obj, key)) newObj[key] = obj[key]; } } newObj.default = obj; return newObj; } }

function _classCallCheck(instance, Constructor) { if (!(instance instanceof Constructor)) { throw new TypeError("Cannot call a class as a function"); } }

function _possibleConstructorReturn(self, call) { if (!self) { throw new ReferenceError("this hasn't been initialised - super() hasn't been called"); } return call && (typeof call === "object" || typeof call === "function") ? call : self; }

function _inherits(subClass, superClass) { if (typeof superClass !== "function" && superClass !== null) { throw new TypeError("Super expression must either be null or a function, not " + typeof superClass); } subClass.prototype = Object.create(superClass && superClass.prototype, { constructor: { value: subClass, enumerable: false, writable: true, configurable: true } }); if (superClass) Object.setPrototypeOf ? Object.setPrototypeOf(subClass, superClass) : subClass.__proto__ = superClass; }

var DEFAULT_JAVAPOLY_OPTIONS = {
  /**
   * When page is loading look for all corresponding MIME-types and create objects for Java automatically
   * @type {Boolean}
   */
  initOnStart: true,
  /**
   * Directory name that stores all class-files, jars and/or java-files
   * @type {String}
   */
  storageDir: '/tmp/data',
  /**
   * URL where we download the doppio library.
   * @type {String}
   * 1.'doppio/', download from user owner domain(${your.domain}/doppio), eg. localhost for locally test
   * 2. or a public url, eg. https://www.javapoly.com/doppio/
   */
  doppioLibUrl: '/doppio/',

  /**
   * URL where we download the BrowserFS library
   * @type {String}
   */
  browserfsLibUrl: '/browserfs/',

  /**
   * Optional: javaPolyBaseUrl
   * When defined, this is used as the base URL for loading JavaPoly data such as system classes and native functions.
   * If empty, JavaPoly will try to automatically figure it out during initialization.
   */
  javaPolyBaseUrl: null,

  /**
   * Javapoly worker path. null or a path, eg. build/javapoly_worker.js
   *
   * @type {String}
   * when defined not null, we will try to use the webworkers path to run the core javapoly and jvm.
   * if web worker is not supported by browser, we will just load javapoly and jvm in browser main Thread.
   */
  worker: null, // 'build/javapoly_worker.js'

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
 * 1. Create object: (new JavaPoly());
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

    var options = _.extend(DEFAULT_JAVAPOLY_OPTIONS, _options);
    if (!options.javaPolyBaseUrl) {
      options.javaPolyBaseUrl = JavaPoly.getScriptBase();
    }

    // Init objects for user to make possible start to work with JavaPoly instantly
    // only bind this api to global.window for the default javapoly instance (the 1th instance, created in main.js).

    var _this = _possibleConstructorReturn(this, Object.getPrototypeOf(JavaPoly).call(this, options));

    return _ret = _this.initApiObjects(_JavaPolyBase3.default.idCount === 1 ? global.window : undefined), _possibleConstructorReturn(_this, _ret);
  }

  _createClass(JavaPoly, [{
    key: 'beginLoading',
    value: function beginLoading(resolveDispatcherReady) {
      var _this2 = this;

      // User worker only if worker option is enabled and browser supports WebWorkers
      if (this.options.worker && global.Worker) {
        this.loadJavaPolyCoreInWebWorker(resolveDispatcherReady);
      } else {
        this.loadJavaPolyCoreInBrowser(resolveDispatcherReady);
      }

      // case when async loading javapoly lib and the DOM contented already loaded
      if (global.document.readyState !== 'loading') {
        this.processScripts();
        _WrapperUtil2.default.dispatchOnJVM(this, 'META_START_JVM', 0, null);
      } else {
        global.document.addEventListener('DOMContentLoaded', function (e) {
          _this2.processScripts();
          _WrapperUtil2.default.dispatchOnJVM(_this2, 'META_START_JVM', 0, null);
        }, false);
      }
    }
  }, {
    key: 'processScripts',
    value: function processScripts() {
      var _this3 = this;

      _.each(global.document.scripts, function (script) {
        _this3.processScript(script);
      });
    }
  }, {
    key: 'processScript',
    value: function processScript(script) {
      if (script.type.toLowerCase() !== 'text/java' && script.type.toLowerCase() !== 'application/java') return;

      if (script.analyzed) return;

      script.analyzed = true;

      //embedded source code
      if (script.text) {
        var classInfo = _CommonUtils2.default.detectClassAndPackageNames(script.text);
        this.createProxyForClass(global.window, classInfo.class, classInfo.package);
        return this.compileJavaSource(script.text);
      }

      if (!script.src) {
        console.warning('please specify the text or src of text/java');
        return;
      }

      return _WrapperUtil2.default.dispatchOnJVM(this, 'FS_MOUNT_JAVA', 10, { src: script.src });
    }
  }, {
    key: 'loadJavaPolyCoreInBrowser',
    value: function loadJavaPolyCoreInBrowser(resolveDispatcherReady) {
      this.dispatcher = new _BrowserDispatcher2.default(this);
      resolveDispatcherReady(this.dispatcher);
    }
  }, {
    key: 'loadJavaPolyCoreInWebWorker',
    value: function loadJavaPolyCoreInWebWorker(resolveDispatcherReady) {
      this.dispatcher = new _WorkerCallBackDispatcher2.default(this.options, new global.Worker(this.options.worker));

      resolveDispatcherReady(this.dispatcher);
    }

    /* This should be called outside of Promise, or any such async call */

  }], [{
    key: 'getScriptBase',
    value: function getScriptBase() {
      var scriptSrc = JavaPoly.getScriptSrc();
      return scriptSrc.slice(0, scriptSrc.lastIndexOf("/") + 1);
    }
  }, {
    key: 'getScriptSrc',
    value: function getScriptSrc() {
      if (document.currentScript) {
        return document.currentScript.src;
      } else {
        var scripts = document.getElementsByTagName('script'),
            script = scripts[scripts.length - 1];

        if (script.getAttribute.length !== undefined) {
          return script.src;
        }

        return script.getAttribute('src', -1);
      }
    }
  }]);

  return JavaPoly;
}(_JavaPolyBase3.default);

global.window.JavaPoly = JavaPoly;

exports.default = JavaPoly;
