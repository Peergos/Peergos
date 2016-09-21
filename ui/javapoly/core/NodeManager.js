/**
 * Created by titan on 12.05.16.
 */
"use strict";

Object.defineProperty(exports, "__esModule", {
    value: true
});

var _createClass = function () { function defineProperties(target, props) { for (var i = 0; i < props.length; i++) { var descriptor = props[i]; descriptor.enumerable = descriptor.enumerable || false; descriptor.configurable = true; if ("value" in descriptor) descriptor.writable = true; Object.defineProperty(target, descriptor.key, descriptor); } } return function (Constructor, protoProps, staticProps) { if (protoProps) defineProperties(Constructor.prototype, protoProps); if (staticProps) defineProperties(Constructor, staticProps); return Constructor; }; }();

function _classCallCheck(instance, Constructor) { if (!(instance instanceof Constructor)) { throw new TypeError("Cannot call a class as a function"); } }

var tempDirectory = function () {
    var path = require('path');
    var os = require('os');
    var directoryPath = path.join(os.tmpdir(), 'javapoly-' + process.pid.toString());
    var fs = require('fs');
    fs.mkdirSync(directoryPath);
    console.log('Temp directory', directoryPath, 'was created.');
    return directoryPath;
}();

var tempAlreadyDeleted = false;

var deletingTempDirectory = function deletingTempDirectory() {
    if (tempAlreadyDeleted) {
        return;
    }
    try {
        (function () {
            var path = require('path');
            var fs = require('fs');
            var deleteFolderRecursive = function deleteFolderRecursive(pathTo) {
                if (fs.existsSync(pathTo)) {
                    fs.readdirSync(pathTo).forEach(function (file, index) {
                        var curPath = pathTo + "/" + file;
                        if (fs.lstatSync(curPath).isDirectory()) {
                            // recurse
                            deleteFolderRecursive(curPath);
                        } else {
                            // delete file
                            fs.unlinkSync(curPath);
                        }
                    });
                    fs.rmdirSync(pathTo);
                }
            };
            deleteFolderRecursive(tempDirectory);
            console.log('Temp directory', tempDirectory, 'successfully deleted.');
        })();
    } catch (error) {
        console.error('Error on while deleting temp directory.');
        console.error(error);
    }
    tempAlreadyDeleted = true;
};

process.on('exit', function () {
    console.log('exit');
    deletingTempDirectory();
});

process.on('SIGINT', function () {
    console.log('SIGINT');
    deletingTempDirectory();
    process.exit(2);
});

process.on('uncaughtException', function (error) {
    console.error('uncaughtException');
    console.error(error);
    deletingTempDirectory();
});

var NodeManager = function () {
    function NodeManager() {
        _classCallCheck(this, NodeManager);
    }

    _createClass(NodeManager, null, [{
        key: 'getTempDirectory',
        value: function getTempDirectory() {
            return tempDirectory;
        }
    }]);

    return NodeManager;
}();

exports.default = NodeManager;
