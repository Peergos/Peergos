"use strict";

Object.defineProperty(exports, "__esModule", {
  value: true
});

var _createClass = function () { function defineProperties(target, props) { for (var i = 0; i < props.length; i++) { var descriptor = props[i]; descriptor.enumerable = descriptor.enumerable || false; descriptor.configurable = true; if ("value" in descriptor) descriptor.writable = true; Object.defineProperty(target, descriptor.key, descriptor); } } return function (Constructor, protoProps, staticProps) { if (protoProps) defineProperties(Constructor.prototype, protoProps); if (staticProps) defineProperties(Constructor, staticProps); return Constructor; }; }();

var _underscore = require('underscore');

var _ = _interopRequireWildcard(_underscore);

var _JavaClassWrapper = require('./JavaClassWrapper');

var _JavaClassWrapper2 = _interopRequireDefault(_JavaClassWrapper);

var _JavaObjectWrapper = require('./JavaObjectWrapper');

var _JavaObjectWrapper2 = _interopRequireDefault(_JavaObjectWrapper);

var _ProxyWrapper = require('./ProxyWrapper');

var _ProxyWrapper2 = _interopRequireDefault(_ProxyWrapper);

var _WrapperUtil = require('./WrapperUtil.js');

var _WrapperUtil2 = _interopRequireDefault(_WrapperUtil);

var _CommonUtils = require('./CommonUtils.js');

var _CommonUtils2 = _interopRequireDefault(_CommonUtils);

function _interopRequireDefault(obj) { return obj && obj.__esModule ? obj : { default: obj }; }

function _interopRequireWildcard(obj) { if (obj && obj.__esModule) { return obj; } else { var newObj = {}; if (obj != null) { for (var key in obj) { if (Object.prototype.hasOwnProperty.call(obj, key)) newObj[key] = obj[key]; } } newObj.default = obj; return newObj; } }

function _classCallCheck(instance, Constructor) { if (!(instance instanceof Constructor)) { throw new TypeError("Cannot call a class as a function"); } }

/**
 * Base JavaPoly class that contains common functionality.
 */

var JavaPolyBase = function () {
  function JavaPolyBase(_options) {
    _classCallCheck(this, JavaPolyBase);

    /**
     * Object with options of JavaPoly
     * @type {Object}
     */
    this.options = _options;

    /**
     * The dispatcher for handle jvm command message
     * @Type {Object}
     */
    this.dispatcher = null;

    var dispatcherDeferred = new _CommonUtils2.default.deferred();
    this.dispatcherReady = dispatcherDeferred.promise;
    this.initJavaPoly(dispatcherDeferred.resolve, dispatcherDeferred.reject);

    var id = ++JavaPolyBase.idCount + '';
    JavaPolyBase.instances[id] = this;
    this.getId = function () {
      return id;
    };
  }

  _createClass(JavaPolyBase, [{
    key: 'initJavaPoly',
    value: function initJavaPoly(resolve, reject) {
      if (this.options.initOnStart === true) {
        return this.beginLoading(resolve);
      } else {
        return reject('not initialised');
      }
    }
  }, {
    key: 'compileJavaSource',
    value: function compileJavaSource(scriptText, resolve, reject) {
      var classInfo = _CommonUtils2.default.detectClassAndPackageNames(scriptText);

      var className = classInfo.class;
      var packageName = classInfo.package;

      _WrapperUtil2.default.dispatchOnJVM(this, "FILE_COMPILE", 10, [className, packageName ? packageName : "", this.options.storageDir, scriptText], resolve, reject);
    }

    /**
     * init the api objects of JavaPoly.
     * @param globalObject
     *   if specified, we want access this java poly instance by a global object, such as global.window.
     */

  }, {
    key: 'initApiObjects',
    value: function initApiObjects(globalObject) {
      var _this = this;

      var api = {};
      api.id = this.getId();
      api.options = this.options;

      // Initialize proxies for the most common/built-in packages.
      // This will make built-in jvm packages available, since the built-ins won't have their source code in the script tags (and thus wouldn't be analyzed at parse time).
      // Most importantly, it will setup warnings when Proxy is not defined in legacy browsers (warn upon access of one of these super common packages)
      if (globalObject) {
        this.createProxyForClass(globalObject, null, 'com');
        this.createProxyForClass(globalObject, null, 'org');
        this.createProxyForClass(globalObject, null, 'net');
        this.createProxyForClass(globalObject, null, 'sun');
        this.createProxyForClass(globalObject, null, 'java');
        this.createProxyForClass(globalObject, null, 'javax');
      }

      if (typeof Proxy !== 'undefined') {
        api.J = _ProxyWrapper2.default.createRootEntity(this, null);
        if (globalObject) globalObject.J = api.J;
      } else {
        this.defineProxyWarning(api, 'J', 'accessor');
        if (globalObject) this.defineProxyWarning(globalObject, 'J', 'accessor');
      }

      this.processScripts();
      /*
        TODO: use the reflect command
        reflect: (jsObj) => {
          return javaType("com.javapoly.Main").then((Main) => {
            return Main.reflectJSValue(jsObj);
          });
        }
      };*/

      api.addClass = function (data) {
        return _this.addClass(data);
      };

      if (globalObject) {
        globalObject.addClass = api.addClass;
      }

      return api;
    }
  }, {
    key: 'type',
    value: function type(clsName) {
      return _JavaClassWrapper2.default.getClassWrapperByName(this, clsName);
    }

    // data could be text string of java source or the url of remote java class/jar/source

  }, {
    key: 'addClass',
    value: function addClass(data) {
      var _this2 = this;

      return new Promise(function (resolve, reject) {
        // try to parse it as java souce string
        var classInfo = _CommonUtils2.default.detectClassAndPackageNames(data);
        // parse success, embedded java source code
        if (classInfo && classInfo.class) {
          return _this2.compileJavaSource(data, resolve, reject);
        }

        // try add remote java/class/jar file
        return _WrapperUtil2.default.dispatchOnJVM(_this2, 'FS_DYNAMIC_MOUNT_JAVA', 10, { src: data }, resolve, reject);
      });
    }
  }, {
    key: 'createProxyForClass',
    value: function createProxyForClass(obj, classname, packagename) {
      var name = null;
      var type = null;
      if (packagename != null) {
        name = packagename.split('.')[0];
        type = 'package';
      } else {
        name = classname;
        type = 'class';
      }

      if (typeof Proxy !== 'undefined') {
        obj[name] = _ProxyWrapper2.default.createRootEntity(this, name);
      } else {
        this.defineProxyWarning(obj, name, type);
      }
    }
  }, {
    key: 'defineProxyWarning',
    value: function defineProxyWarning(obj, name, type) {
      var self = this;
      Object.defineProperty(obj, name, { configurable: true, get: function get() {
          if (!self.proxyWarnings) self.proxyWarnings = {};if (!self.proxyWarnings[name]) console.error('Your browser does not support Proxy objects, so the `' + name + '` ' + type + ' must be accessed using JavaPoly.type(\'' + (type === 'class' ? 'YourClass' : 'com.yourpackage.YourClass') + '\') instead of using the class\' fully qualified name directly from javascript.  Note that `JavaPoly.type` will return a promise for a class instead of a direct class reference.  For more info: https://javapoly.com/details.html#Java_Classes_using_JavaPoly.type()');self.proxyWarnings[name] = true;
        } });
    }
  }, {
    key: 'wrapJavaObject',
    value: function wrapJavaObject(obj, methods, nonFinalFields, finalFields) {
      return new _JavaObjectWrapper2.default(this, obj, methods, nonFinalFields, finalFields);
    }
  }, {
    key: 'unwrapJavaObject',
    value: function unwrapJavaObject(obj) {
      // TODO: is a better check possible using prototypes
      if (obj && obj._javaObj) {
        return obj._javaObj;
      } else {
        return null;
      }
    }
  }], [{
    key: 'getInstance',
    value: function getInstance(javapolyId) {
      return JavaPolyBase.instances[javapolyId];
    }
  }, {
    key: 'initialJavaPoly',
    value: function initialJavaPoly(JavaPolyProto) {
      return JavaPolyBase.idCount === 0 ? new JavaPolyProto() : JavaPolyBase.instances['1'];
    }
  }, {
    key: 'new',
    value: function _new(name) {
      for (var _len = arguments.length, args = Array(_len > 1 ? _len - 1 : 0), _key = 1; _key < _len; _key++) {
        args[_key - 1] = arguments[_key];
      }

      return JavaPolyBase.initialJavaPoly(this).type(name).then(function (classWrapper) {
        return new (Function.prototype.bind.apply(classWrapper, [null].concat(args)))();
      });
    }
  }, {
    key: 'addClass',
    value: function addClass(data) {
      return JavaPolyBase.initialJavaPoly(this).addClass(data);
    }
  }, {
    key: 'type',
    value: function type(clsName) {
      return JavaPolyBase.initialJavaPoly(this).type(clsName);
    }
  }]);

  return JavaPolyBase;
}();

exports.default = JavaPolyBase;


JavaPolyBase.idCount = 0;
JavaPolyBase.instances = {};
