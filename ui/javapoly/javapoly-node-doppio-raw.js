"use strict";

var _JavaPolyNodeDoppio = require("./core/JavaPolyNodeDoppio");

var _JavaPolyNodeDoppio2 = _interopRequireDefault(_JavaPolyNodeDoppio);

function _interopRequireDefault(obj) { return obj && obj.__esModule ? obj : { default: obj }; }

// Create main object that will be accessed via global objects J and Java
global.JavaPoly = _JavaPolyNodeDoppio2.default;

// For running this code as Node module
module.exports = _JavaPolyNodeDoppio2.default;
