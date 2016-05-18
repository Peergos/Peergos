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

/**
 * The NodeDoppioManager manages the Doppio JVM on Node
 */

var NodeDoppioManager = function () {
  function NodeDoppioManager(javapoly) {
    _classCallCheck(this, NodeDoppioManager);

    this.javapoly = javapoly;

    /**
     * Array that contains classpath, include the root path of class files , jar file path.
     * @type {Array}
     */
    var options = this.getOptions();
    this.classpath = [options.javapolyBase + "/classes", options.storageDir];
  }

  _createClass(NodeDoppioManager, [{
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
      var fs = require('fs');
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
    key: 'initJVM',
    value: function initJVM() {
      var _this2 = this;

      var options = this.getOptions();
      var responsiveness = this.javapoly.isJavaPolyWorker ? 100 : 10;
      var javaHomePath = options.doppioBase + '/vendor/java_home';

      this.javapoly.jvm = new Doppio.VM.JVM({
        classpath: this.classpath,
        doppioHomePath: options.doppioBase,
        nativeClasspath: [options.doppioBase + '/src/natives', options.javapolyBase + "/natives"],
        assertionsEnabled: options.assertionsEnabled,
        responsiveness: responsiveness
      }, function (err, jvm) {
        if (err) {
          console.error('err loading JVM ' + _this2.javapoly.getId() + ' :', err);
        } else {
          jvm.runClass('com.javapoly.Main', [_this2.javapoly.getId()], function (exitCode) {
            // Control flow shouldn't reach here under normal circumstances,
            // because Main thread keeps polling for messages.
            console.log("JVM Exit code: ", exitCode);
          });
        }
      });
    }
  }]);

  return NodeDoppioManager;
}();

exports.default = NodeDoppioManager;
