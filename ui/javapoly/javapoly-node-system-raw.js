"use strict";

var _JavaPolyNodeSystem = require("./core/JavaPolyNodeSystem");

var _JavaPolyNodeSystem2 = _interopRequireDefault(_JavaPolyNodeSystem);

function _interopRequireDefault(obj) { return obj && obj.__esModule ? obj : { default: obj }; }

// Create main object that will be accessed via global objects J and Java
global.JavaPoly = _JavaPolyNodeSystem2.default;

// For running this code as Node module
module.exports = _JavaPolyNodeSystem2.default;
