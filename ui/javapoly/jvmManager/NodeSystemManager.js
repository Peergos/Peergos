"use strict";

Object.defineProperty(exports, "__esModule", {
  value: true
});

var _createClass = function () { function defineProperties(target, props) { for (var i = 0; i < props.length; i++) { var descriptor = props[i]; descriptor.enumerable = descriptor.enumerable || false; descriptor.configurable = true; if ("value" in descriptor) descriptor.writable = true; Object.defineProperty(target, descriptor.key, descriptor); } } return function (Constructor, protoProps, staticProps) { if (protoProps) defineProperties(Constructor.prototype, protoProps); if (staticProps) defineProperties(Constructor, staticProps); return Constructor; }; }();

var _CommonUtils = require('../core/CommonUtils.js');

var _CommonUtils2 = _interopRequireDefault(_CommonUtils);

var _WrapperUtil = require('../core/WrapperUtil.js');

var _WrapperUtil2 = _interopRequireDefault(_WrapperUtil);

var _NodeManager = require('../core/NodeManager');

var _NodeManager2 = _interopRequireDefault(_NodeManager);

function _interopRequireDefault(obj) { return obj && obj.__esModule ? obj : { default: obj }; }

function _classCallCheck(instance, Constructor) { if (!(instance instanceof Constructor)) { throw new TypeError("Cannot call a class as a function"); } }

/**
 * The NodeSystemManager manages the Doppio JVM on Node
 */

var NodeSystemManager = function () {
  function NodeSystemManager(javapoly, secret, httpPortDeffered, jsServer) {
    _classCallCheck(this, NodeSystemManager);

    this.javapoly = javapoly;
    this.httpPortDeffered = httpPortDeffered;
    this.secret = secret;
    this.jsServer = jsServer;

    /**
     * Array that contains classpath, include the root path of class files , jar file path.
     * @type {Array}
     */
    var options = this.getOptions();
    this.classpath = [options.javapolyBase + "/classes", options.storageDir];
    this.javaBin = this.getJavaExec();
  }

  _createClass(NodeSystemManager, [{
    key: 'getOptions',
    value: function getOptions() {
      return this.javapoly.options;
    }
  }, {
    key: 'dynamicMountJava',
    value: function dynamicMountJava(src) {
      var _this = this;

      var fs = require("fs");
      var options = this.getOptions();
      return new Promise(function (resolve, reject) {
        // remote file, we need to download the file data of that url and parse the type.
        fs.readFile(src, function (err, fileDataBuf) {
          if (err) {
            reject(err);
          } else {
            // remote java class/jar file
            if (_CommonUtils2.default.isClassFile(fileDataBuf)) {
              _this.writeRemoteClassFileIntoFS(src, fileDataBuf).then(resolve, reject);
            } else if (_CommonUtils2.default.isZipFile(fileDataBuf)) {
              _WrapperUtil2.default.dispatchOnJVM(_this.javapoly, 'JAR_PATH_ADD', 10, ['file://' + src], resolve, reject);
            } else {

              // remote java source code file
              var classInfo = _CommonUtils2.default.detectClassAndPackageNames(fileDataBuf.toString());
              if (classInfo && classInfo.class) {
                var className = classInfo.class;
                var packageName = classInfo.package;
                return _WrapperUtil2.default.dispatchOnJVM(_this.javapoly, "FILE_COMPILE", 10, [className, packageName ? packageName : "", options.storageDir, fileDataBuf.toString()], resolve, reject);
              }

              console.log('Unknown java file type', src);
              reject('Unknown java file type' + src);
            }
          }
        });
      });
    }
  }, {
    key: 'writeRemoteClassFileIntoFS',
    value: function writeRemoteClassFileIntoFS(src, classFileData) {
      var path = require('path');
      var fs = require("fs");
      var options = this.getOptions();
      var classfile = require('./../tools/classfile.js');
      var fsext = require('./../tools/fsext')(fs, path);

      return new Promise(function (resolve, reject) {
        var classFileInfo = classfile.analyze(classFileData);
        var className = path.basename(classFileInfo.this_class);
        var packageName = path.dirname(classFileInfo.this_class);

        fsext.rmkdirSync(path.join(options.storageDir, packageName));

        fs.writeFile(path.join(options.storageDir, classFileInfo.this_class + '.class'), classFileData, function (err) {
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
    key: 'getJavaExec',
    value: function getJavaExec() {
      var path = require('path');
      var fs = require("fs");

      var homeRootDirectory = process.env[process.platform === 'win32' ? 'USERPROFILE' : 'HOME'];
      var binDirName = '.jvm';
      var javaExec = process.platform === 'win32' ? "java.exe" : "java";
      var javaFullPath = path.join(homeRootDirectory, binDirName, 'jre', 'bin', javaExec);

      try {
        var stat = fs.statSync(javaFullPath);
        if (stat.isFile()) {
          return javaFullPath;
        }
      } catch (e) {
        if (e.code === 'ENOENT') {
          // Java wasn't installed locally
        } else {
            // Hm unknown error
            console.error(e);
          }
      }
      return "java";
    }
  }, {
    key: 'initJVM',
    value: function initJVM() {
      var _this2 = this;

      var javaBin = this.javaBin;
      this.jsServer.then(function (serverPort) {
        var childProcess = require('child_process');
        var spawn = childProcess.spawn;

        var path = require('path');
        var currentDirectory = __dirname;
        var packageRoot = path.resolve(currentDirectory, "..");

        var classPath = packageRoot + '/jars/java_websocket.jar:' + packageRoot + '/jars/javax.json-1.0.4.jar:' + packageRoot + '/classes:' + _NodeManager2.default.getTempDirectory();

        var args = ['-cp', classPath, 'com.javapoly.Main', _this2.javapoly.getId(), "system", _this2.secret, serverPort];
        // const child = spawn('java', args, {detached: true, stdio: ['ignore', 'ignore', 'ignore']});
        var child = spawn(javaBin, args, { detached: true, stdio: 'inherit' });
        child.unref();
      });
    }
  }]);

  return NodeSystemManager;
}();

exports.default = NodeSystemManager;
