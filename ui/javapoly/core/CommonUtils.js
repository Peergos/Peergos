"use strict";

Object.defineProperty(exports, "__esModule", {
  value: true
});

var _createClass = function () { function defineProperties(target, props) { for (var i = 0; i < props.length; i++) { var descriptor = props[i]; descriptor.enumerable = descriptor.enumerable || false; descriptor.configurable = true; if ("value" in descriptor) descriptor.writable = true; Object.defineProperty(target, descriptor.key, descriptor); } } return function (Constructor, protoProps, staticProps) { if (protoProps) defineProperties(Constructor.prototype, protoProps); if (staticProps) defineProperties(Constructor, staticProps); return Constructor; }; }();

var _jsjavaparser = require('jsjavaparser');

var _jsjavaparser2 = _interopRequireDefault(_jsjavaparser);

function _interopRequireDefault(obj) { return obj && obj.__esModule ? obj : { default: obj }; }

function _classCallCheck(instance, Constructor) { if (!(instance instanceof Constructor)) { throw new TypeError("Cannot call a class as a function"); } }

var CLASS_MAGIC_NUMBER = 'cafebabe';
var ZIP_MAGIC_NUMBER = '504b0304';

var CommonUtils = function () {
  function CommonUtils() {
    _classCallCheck(this, CommonUtils);
  }

  _createClass(CommonUtils, null, [{
    key: 'xhrRetrieve',
    value: function xhrRetrieve(url, responseType) {
      return new Promise(function (resolve, reject) {
        var xmlr = new XMLHttpRequest();
        xmlr.open('GET', url, true);
        xmlr.responseType = responseType;
        xmlr.onreadystatechange = function () {
          if (xmlr.readyState === 4) {
            if (xmlr.status === 200) {
              resolve(xmlr.response);
            } else {
              reject();
            }
          }
        };
        xmlr.send(null);
      });
    }
  }, {
    key: 'hexFromBuffer',
    value: function hexFromBuffer(buffer, from, count) {
      var str = [];
      var bufferGetter = global.BrowserFS ? function (index) {
        return buffer.get(index);
      } : function (index) {
        return buffer[index];
      };
      for (var i = 0; i < count; i++) {
        var ss = bufferGetter(from + i).toString(16);
        if (ss.length < 2) ss = '0' + ss;
        str.push(ss);
      }
      return str.join('');
    }

    /**
     * Detects if passed 'data' is zip file
     * @param {String|Buffer} data URL string or data buffer
     * @return {Boolean}
     */

  }, {
    key: 'isZipFile',
    value: function isZipFile(data) {
      if (typeof data === 'string') {
        return data.endsWith('.jar') || data.endsWith('.zip');
      } else {
        return ZIP_MAGIC_NUMBER === CommonUtils.hexFromBuffer(data, 0, 4);
      }
    }

    /**
       * Detects if passed 'data' is class file
       * @param {String|Buffer} data URL string or data buffer
       * @return {Boolean}
       */

  }, {
    key: 'isClassFile',
    value: function isClassFile(data) {
      if (typeof data === 'string') {
        return data.endsWith('.class');
      } else {
        return CLASS_MAGIC_NUMBER === CommonUtils.hexFromBuffer(data, 0, 4);
      }
    }

    /**
     * This functions parse Java source file and detects its name and package
     * @param  {String} source Java source
     * @return {Object}        Object with fields: package and class
     */

  }, {
    key: 'detectClassAndPackageNames',
    value: function detectClassAndPackageNames(source) {
      var className = null,
          packageName = null;

      var parsedSource = void 0;
      try {
        parsedSource = _jsjavaparser2.default.parse(source);
      } catch (e) {
        return null;
      }

      if (parsedSource.node === 'CompilationUnit') {
        for (var i = 0; i < parsedSource.types.length; i++) {
          if (CommonUtils.isPublic(parsedSource.types[i])) {
            className = parsedSource.types[i].name.identifier;
            break;
          }
        }
        if (parsedSource.package) {
          packageName = CommonUtils.getPackageName(parsedSource.package.name);
        }
      }

      return {
        package: packageName,
        class: className
      };
    }
  }, {
    key: 'isPublic',
    value: function isPublic(node) {
      if (node.modifiers) {
        for (var i = 0; i < node.modifiers.length; i++) {
          if (node.modifiers[i].keyword === 'public') {
            return true;
          }
        }
      }
      return false;
    }
  }, {
    key: 'getPackageName',
    value: function getPackageName(node) {
      if (node.node === 'QualifiedName') {
        return CommonUtils.getPackageName(node.qualifier) + '.' + node.name.identifier;
      } else {
        return node.identifier;
      }
    }

    // Utility function to create a deferred promise

  }, {
    key: 'deferred',
    value: function deferred() {
      this.promise = new Promise(function (resolve, reject) {
        this.resolve = resolve;
        this.reject = reject;
      }.bind(this));
      Object.freeze(this);
      return this;
    }
  }]);

  return CommonUtils;
}();

exports.default = CommonUtils;
