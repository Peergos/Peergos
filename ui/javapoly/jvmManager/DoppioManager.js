"use strict";

Object.defineProperty(exports, "__esModule", {
  value: true
});

var _createClass = function () { function defineProperties(target, props) { for (var i = 0; i < props.length; i++) { var descriptor = props[i]; descriptor.enumerable = descriptor.enumerable || false; descriptor.configurable = true; if ("value" in descriptor) descriptor.writable = true; Object.defineProperty(target, descriptor.key, descriptor); } } return function (Constructor, protoProps, staticProps) { if (protoProps) defineProperties(Constructor.prototype, protoProps); if (staticProps) defineProperties(Constructor, staticProps); return Constructor; }; }();

var _CommonUtils = require('../core/CommonUtils.js');

var _CommonUtils2 = _interopRequireDefault(_CommonUtils);

var _WrapperUtil = require('../core/WrapperUtil.js');

var _WrapperUtil2 = _interopRequireDefault(_WrapperUtil);

function _interopRequireDefault(obj) { return obj && obj.__esModule ? obj : { default: obj }; }

function _classCallCheck(instance, Constructor) { if (!(instance instanceof Constructor)) { throw new TypeError("Cannot call a class as a function"); } }

var classfile = require('./../tools/classfile.js');

/**
 * The DoppioManager manages the JVM and filesystem.
 * It can be run in Browser or WebWorker.
 * It assumes that the .js for BrowserFS and Doppio is already loaded.
 */

var DoppioManager = function () {
  function DoppioManager(javapoly) {
    _classCallCheck(this, DoppioManager);

    this.javapoly = javapoly;

    this.fs = null;

    /**
     * It's a MountableFileSystem that contains XHR mounted file systems.
     * It's needed for loading JAR-files without loading and resaving it.
     */
    this.xhrdirs = new BrowserFS.FileSystem.MountableFileSystem();

    /**
     * Stores referense to the special extension for fs (for example it contains recursive mkdir)
     * @type {[type]}
     */
    this.fsext = null;

    /**
     * Array that contains classpath, include the root path of class files , jar file path.
     * @type {Array}
     */
    this.classpath = [this.getOptions().storageDir];

    this.mountHub = [];

    this.initBrowserFS();
  }

  _createClass(DoppioManager, [{
    key: 'getOptions',
    value: function getOptions() {
      return this.javapoly.options;
    }

    /**
     * Initialization of BrowserFS
     */

  }, {
    key: 'initBrowserFS',
    value: function initBrowserFS() {
      var _this = this;

      var mfs = new BrowserFS.FileSystem.MountableFileSystem();
      BrowserFS.initialize(mfs);
      mfs.mount('/tmp', new BrowserFS.FileSystem.InMemory());

      // FIXME local storage can't be used in WebWorker, check if it affect anything.
      if (!this.javapoly.isJavaPolyWorker) {
        mfs.mount('/home', new BrowserFS.FileSystem.LocalStorage());
      }

      var options = this.getOptions();

      this.bfsReady = new Promise(function (resolve) {
        _CommonUtils2.default.xhrRetrieve(options.doppioLibUrl + "/listings.json", "json").then(function (doppioListings) {
          _CommonUtils2.default.xhrRetrieve(options.javaPolyBaseUrl + "/listings.json", "json").then(function (javapolyListings) {
            mfs.mount('/sys', new BrowserFS.FileSystem.XmlHttpRequest(doppioListings, options.doppioLibUrl));
            mfs.mount('/javapoly', new BrowserFS.FileSystem.XmlHttpRequest(javapolyListings, options.javaPolyBaseUrl));
            mfs.mount('/xhrdirs', _this.xhrdirs);

            _this.fs = BrowserFS.BFSRequire('fs');
            _this.path = BrowserFS.BFSRequire('path');
            _this.fsext = require('./../tools/fsext')(_this.fs, _this.path);
            _this.fsext.rmkdirSync(options.storageDir);
            BrowserFS.install(_this);
            _this.installStreamHandlers();
            resolve();
          });
        });
      });
    }
  }, {
    key: '_mountJava',
    value: function _mountJava(src, fRegJarPath) {
      var _this2 = this;

      var Buffer = global.BrowserFS.BFSRequire('buffer').Buffer;
      var options = this.getOptions();

      return new Promise(function (resolve, reject) {
        if (_CommonUtils2.default.isZipFile(src)) {
          return _this2.mountFileViaXHR(src).then(fRegJarPath.bind(_this2, resolve, reject), reject);
        } else {
          // remote file, we need to download the file data of that url and parse the type.
          _CommonUtils2.default.xhrRetrieve(src, 'arraybuffer').then(function (fileData) {
            var fileDataBuf = new Buffer(fileData);

            // remote java class/jar file
            if (_CommonUtils2.default.isClassFile(fileDataBuf)) {
              //return WrapperUtil.dispatchOnJVM(this, 'FS_MOUNT_CLASS', 10, {src:script.src});
              return _this2.writeRemoteClassFileIntoFS(src, fileDataBuf).then(resolve, reject);
            } else if (_CommonUtils2.default.isZipFile(fileDataBuf)) {
              //return WrapperUtil.dispatchOnJVM(this, 'FS_MOUNT_JAR', 10, {src:script.src});
              return _this2.writeRemoteJarFileIntoFS(src, fileDataBuf).then(fRegJarPath.bind(_this2, resolve, reject), reject);
            }

            // remote java source code file
            var classInfo = _CommonUtils2.default.detectClassAndPackageNames(fileDataBuf.toString());
            if (classInfo && classInfo.class) {
              var className = classInfo.class;
              var packageName = classInfo.package;
              return _WrapperUtil2.default.dispatchOnJVM(_this2.javapoly, 'FILE_COMPILE', 10, [className, packageName ? packageName : '', options.storageDir, fileDataBuf.toString()], resolve, reject);
            }

            console.log('Unknown java file type', src);
            reject('Unknown java file type' + src);
          }, function () {
            console.log('URL Not Found', src);
            reject('Unknown java file type' + src);
          });
        }
      });
    }
  }, {
    key: 'mountJava',
    value: function mountJava(src) {
      var _this3 = this;

      this.bfsReady.then(function () {
        _this3.mountHub.push(_this3._mountJava(src, function (resolve, reject, jarStorePath) {
          _this3.classpath.push(jarStorePath);resolve("OK");
        }));
      });
    }
  }, {
    key: 'dynamicMountJava',
    value: function dynamicMountJava(src) {
      var _this4 = this;

      var Buffer = global.BrowserFS.BFSRequire('buffer').Buffer;
      var options = this.getOptions();
      return this._mountJava(src, function (resolve, reject, jarStorePath) {
        return _WrapperUtil2.default.dispatchOnJVM(_this4.javapoly, 'JAR_PATH_ADD', 10, ['file://' + jarStorePath], resolve, reject);
      });
    }
  }, {
    key: 'mountFileViaXHR',
    value: function mountFileViaXHR(src) {
      var _this5 = this;

      var options = this.getOptions();

      return new Promise(function (resolve, reject) {
        var fileName = _this5.path.basename(src);
        var dirName = _this5.path.join(src.replace(/[\///\:]/gi, ''));

        if (!_this5.fs.existsSync('/xhrdirs/' + dirName)) {
          var listingObject = {};listingObject[fileName] = null;
          var lastSlash = 0;
          for (var ti = 0; (ti = src.indexOf('/', lastSlash + 1)) > 0; lastSlash = ti) {}
          var mountPoint = new BrowserFS.FileSystem.XmlHttpRequest(listingObject, src.substr(0, lastSlash));

          _this5.xhrdirs.mount('/' + dirName, mountPoint);
        }

        resolve(_this5.path.join('/xhrdirs', dirName, fileName));
      });
    }
  }, {
    key: 'writeRemoteJarFileIntoFS',
    value: function writeRemoteJarFileIntoFS(src, jarFileData) {
      var _this6 = this;

      var Buffer = global.BrowserFS.BFSRequire('buffer').Buffer;
      var options = this.getOptions();
      return new Promise(function (resolve, reject) {
        var jarName = _this6.path.basename(src);
        var jarStorePath = _this6.path.join(options.storageDir, jarName);
        // store the .jar file to $storageDir
        _this6.fs.writeFile(jarStorePath, jarFileData, function (err) {
          if (err) {
            console.error(err.message);
            reject(err.message);
          } else {
            // add .jar file path to the URL of URLClassLoader
            //this.classpath.push(jarStorePath);

            //need to pass the path, will add that path to ClassLoader of Main.java
            resolve(jarStorePath);
          }
        });
      });
    }
  }, {
    key: 'writeRemoteClassFileIntoFS',
    value: function writeRemoteClassFileIntoFS(src, classFileData) {
      var _this7 = this;

      var Buffer = global.BrowserFS.BFSRequire('buffer').Buffer;
      var options = this.getOptions();
      return new Promise(function (resolve, reject) {
        var classFileInfo = classfile.analyze(classFileData);
        var className = _this7.path.basename(classFileInfo.this_class);
        var packageName = _this7.path.dirname(classFileInfo.this_class);

        _this7.fsext.rmkdirSync(_this7.path.join(options.storageDir, packageName));

        _this7.fs.writeFile(_this7.path.join(options.storageDir, classFileInfo.this_class + '.class'), classFileData, function (err) {
          if (err) {
            console.error(err.message);
            reject(err.message);
          } else {
            resolve("OK");
          }
        });
      });
    }
  }, {
    key: 'initJVM',
    value: function initJVM() {
      var _this8 = this;

      var options = this.getOptions();
      var responsiveness = this.javapoly.isJavaPolyWorker ? 100 : 10;
      this.classpath.unshift("/javapoly/classes/");
      this.bfsReady.then(function () {
        Promise.all(_this8.mountHub).then(function () {
          _this8.javapoly.jvm = new Doppio.VM.JVM({
            doppioHomePath: '/sys',
            classpath: _this8.classpath,
            javaHomePath: '/sys/vendor/java_home',
            extractionPath: '/tmp',
            nativeClasspath: ['/sys/natives', "/javapoly/natives"],
            assertionsEnabled: options.assertionsEnabled,
            responsiveness: responsiveness
          }, function (err, jvm) {
            if (err) {
              console.log('err loading JVM ' + _this8.javapoly.getId() + ' :', err);
            } else {
              jvm.runClass('com.javapoly.Main', [_this8.javapoly.getId()], function (exitCode) {
                // Control flow shouldn't reach here under normal circumstances,
                // because Main thread keeps polling for messages.
                console.log("JVM Exit code: ", exitCode);
              });
            }
          });
        });
      });
    }
  }, {
    key: 'installStreamHandlers',
    value: function installStreamHandlers() {
      var _this9 = this;

      this.process.stdout.on('data', function (data) {
        var ds = data.toString();
        if (ds != "\n") {
          console.log("JVM " + _this9.javapoly.getId() + " stdout>", ds);
        }
      });
      this.process.stderr.on('data', function (data) {
        var ds = data.toString();
        if (ds != "\n") {
          console.warn("JVM " + _this9.javapoly.getId() + " stderr>", ds);
        }
      });
    }
  }]);

  return DoppioManager;
}();

exports.default = DoppioManager;
