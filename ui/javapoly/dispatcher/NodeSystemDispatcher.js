"use strict";

Object.defineProperty(exports, "__esModule", {
  value: true
});

var _typeof = typeof Symbol === "function" && typeof Symbol.iterator === "symbol" ? function (obj) { return typeof obj; } : function (obj) { return obj && typeof Symbol === "function" && obj.constructor === Symbol ? "symbol" : typeof obj; };

var _createClass = function () { function defineProperties(target, props) { for (var i = 0; i < props.length; i++) { var descriptor = props[i]; descriptor.enumerable = descriptor.enumerable || false; descriptor.configurable = true; if ("value" in descriptor) descriptor.writable = true; Object.defineProperty(target, descriptor.key, descriptor); } } return function (Constructor, protoProps, staticProps) { if (protoProps) defineProperties(Constructor.prototype, protoProps); if (staticProps) defineProperties(Constructor, staticProps); return Constructor; }; }();

var _CommonUtils = require('../core/CommonUtils.js');

var _CommonUtils2 = _interopRequireDefault(_CommonUtils);

var _CommonDispatcher2 = require('./CommonDispatcher.js');

var _CommonDispatcher3 = _interopRequireDefault(_CommonDispatcher2);

var _NodeSystemManager = require('../jvmManager/NodeSystemManager.js');

var _NodeSystemManager2 = _interopRequireDefault(_NodeSystemManager);

var _WrapperUtil = require('../core/WrapperUtil.js');

var _WrapperUtil2 = _interopRequireDefault(_WrapperUtil);

var _csrf = require('csrf');

var _csrf2 = _interopRequireDefault(_csrf);

var _http = require('http');

var _http2 = _interopRequireDefault(_http);

var _url = require('url');

var _url2 = _interopRequireDefault(_url);

function _interopRequireDefault(obj) { return obj && obj.__esModule ? obj : { default: obj }; }

function _classCallCheck(instance, Constructor) { if (!(instance instanceof Constructor)) { throw new TypeError("Cannot call a class as a function"); } }

function _possibleConstructorReturn(self, call) { if (!self) { throw new ReferenceError("this hasn't been initialised - super() hasn't been called"); } return call && (typeof call === "object" || typeof call === "function") ? call : self; }

function _inherits(subClass, superClass) { if (typeof superClass !== "function" && superClass !== null) { throw new TypeError("Super expression must either be null or a function, not " + typeof superClass); } subClass.prototype = Object.create(superClass && superClass.prototype, { constructor: { value: subClass, enumerable: false, writable: true, configurable: true } }); if (superClass) Object.setPrototypeOf ? Object.setPrototypeOf(subClass, superClass) : subClass.__proto__ = superClass; }

/* Used for the case when javaploy is running in node with system JVM */

var NodeSystemDispatcher = function (_CommonDispatcher) {
  _inherits(NodeSystemDispatcher, _CommonDispatcher);

  function NodeSystemDispatcher(javapoly) {
    _classCallCheck(this, NodeSystemDispatcher);

    var _this2 = _possibleConstructorReturn(this, Object.getPrototypeOf(NodeSystemDispatcher).call(this, javapoly));

    _this2.javapoly = javapoly;

    _this2.heartBeatPeriodMillis = 1000;
    _this2.reflected = [];
    _this2.reflectedCount = 0;

    var _this = _this2;
    _this2.count = 0;
    _this2.terminating = false;
    var process = require('process');
    process.on('beforeExit', function () {
      // console.log("before exit: ", _this.count);
      if (!_this.terminating) {
        _WrapperUtil2.default.dispatchOnJVM(javapoly, 'TERMINATE', 0, [], function (willTerminate) {
          _this.count++;
          _this.terminating = willTerminate;
          if (!willTerminate) {
            setTimeout(function () {}, 500); // breathing space to avoid busy polling. TODO: Exponential backoff with ceiling
          } else {
              _WrapperUtil2.default.dispatchOnJVM(javapoly, 'TERMINATE_NOW', 0, [], function (willTerminate) {});
            }
        });
      }
    });

    process.on('exit', function () {
      // console.log("node process Exit");
      _this.terminating = true;
    });

    process.on('SIGINT', function () {
      _this.terminating = true;
      _WrapperUtil2.default.dispatchOnJVM(javapoly, 'TERMINATE_NOW', 0, [], function (willTerminate) {});
    });

    process.on('uncaughtException', function (e) {
      console.log("Uncaught exception: " + e);
      console.log("stack: " + e.stack);
      _this.terminating = true;
      _WrapperUtil2.default.dispatchOnJVM(javapoly, 'TERMINATE_NOW', 0, [], function (willTerminate) {});
    });

    var timer = setInterval(function () {
      if (!_this.terminating) {
        _WrapperUtil2.default.dispatchOnJVM(javapoly, 'HEARTBEAT', 0, [], function (willTerminate) {});
      } else {
        clearInterval(timer);
      }
    }, _this2.heartBeatPeriodMillis);

    timer.unref();

    return _this2;
  }

  _createClass(NodeSystemDispatcher, [{
    key: 'initDoppioManager',
    value: function initDoppioManager(javapoly) {
      this.httpPortDeffered = new _CommonUtils2.default.deferred();
      this.tokens = new _csrf2.default();
      this.secret = this.tokens.secretSync();
      var mgr = new _NodeSystemManager2.default(javapoly, this.secret, this.httpPortDeffered, this.startJSServer());
      return Promise.resolve(mgr);
    }
  }, {
    key: 'verifyToken',
    value: function verifyToken(token) {
      return this.tokens.verify(this.secret, token);
    }
  }, {
    key: 'postMessage',
    value: function postMessage(messageType, priority, data, callback) {
      var id = this.javaPolyIdCount++;

      this.handleIncomingMessage(id, priority, messageType, data, callback);
    }
  }, {
    key: 'handleRequest',
    value: function handleRequest(incoming, response) {
      var _this3 = this;

      var _this = this;
      var urlParts = _url2.default.parse(incoming.url, true);
      if (urlParts.pathname === "/informPort") {
        this.httpPortDeffered.resolve(incoming.headers["jvm-port"]);
        response.writeHead(200, { 'Content-Type': 'text/plain' });
        response.end();
      } else if (urlParts.pathname === "/releaseObject") {
        var objId = incoming.headers["obj-id"];
        delete this.reflected[objId];
        response.writeHead(200, { 'Content-Type': 'text/plain' });
        response.end();
      } else if (urlParts.pathname === "/getProperty") {
        var queryData = urlParts.query;
        var jsId = queryData.id;
        var fieldName = queryData.fieldName;
        var jsObj = this.reflected[jsId];
        var field = jsObj[fieldName];
        response.writeHead(200, { 'Content-Type': 'text/plain' });
        response.write(JSON.stringify({ result: this.reflect(field) }));
        response.end();
      } else if (urlParts.pathname === "/eval") {
        this.readStream(incoming, function (s) {
          var result = eval(s);
          response.writeHead(200, { 'Content-Type': 'text/plain' });
          response.write(JSON.stringify({ result: _this.reflect(result) }));
          response.end();
        });
      } else if (urlParts.pathname === "/invoke") {
        this.readStream(incoming, function (s) {
          var json = JSON.parse(s);
          var func = _this3.reflected[json.functionId];
          var result = func.apply(null, json.args);
          response.writeHead(200, { 'Content-Type': 'text/plain' });
          response.write(JSON.stringify({ result: _this.reflect(result) }));
          response.end();
        });
      }
    }
  }, {
    key: 'readStream',
    value: function readStream(rr, cb) {
      rr.setEncoding('utf8');
      var data = "";
      rr.on('readable', function () {
        var rcvd = rr.read();
        if (rcvd != null) {
          data += rcvd;
        }
      });
      rr.on('end', function () {
        cb(data);
      });
    }
  }, {
    key: 'startJSServer',
    value: function startJSServer() {
      var _this = this;

      return new Promise(function (resolve, reject) {
        var srv = _http2.default.createServer(function (incoming, response) {
          if (_this.verifyToken(incoming.headers["token"])) {
            _this.handleRequest(incoming, response);
          } else {
            response.writeHead(404, { 'Content-Type': 'text/plain' });
            response.end();
          }
          srv.unref();
        });
        srv.listen(0, 'localhost', function () {
          resolve(srv.address().port);
        });
      });
    }
  }, {
    key: 'handleJVMMessage',
    value: function handleJVMMessage(id, priority, messageType, data, callback) {
      var _this = this;
      var thisDispatcher = this;
      var token = this.tokens.create(this.secret);
      this.javaPolyCallbacks[id] = callback;
      this.httpPortDeffered.promise.then(function (port) {
        var msgObj = { id: "" + id, priority: priority, messageType: messageType, data: data, token: token };
        var msg = JSON.stringify(msgObj);
        var requestOptions = {
          port: port,
          hostname: "localhost",
          path: "/message",
          headers: { "Content-Length": msg.length },
          agent: false
        };
        var req = _http2.default.request(requestOptions, function (res) {
          res.setEncoding('utf8');
          res.on('data', function (chunk) {
            var msg = JSON.parse(chunk);
            if (thisDispatcher.verifyToken(msg.token)) {
              thisDispatcher.callbackMessage(msg.id, msg.result);
            } else {
              console.log("[Node] Invalid CSRF token in message's response from jvm, ignoring message");
              console.log("[Node] Rcvd token: ", msg.token);
            }
          });
        });
        req.on('error', function () {
          if (_this.terminating) {
            // Expected
          } else {
              throw new Error("Unexpected error in request");
            }
        });
        req.write(msg);
        req.end();
      });
    }
  }, {
    key: 'reflect',
    value: function reflect(jsObj) {
      var objType = typeof jsObj === 'undefined' ? 'undefined' : _typeof(jsObj);
      if (objType === 'function' || objType === 'object' && !jsObj._javaObj) {
        var id = this.reflectedCount++;
        this.reflected[id] = jsObj;
        return { "jsId": id, "type": objType };
      } else {
        return jsObj;
      }
    }
  }, {
    key: 'unreflect',
    value: function unreflect(result) {
      if (!!result && (typeof result === 'undefined' ? 'undefined' : _typeof(result)) === "object" && !!result.jsObj) {
        return this.reflected[result.jsObj];
      }
      return result;
    }
  }]);

  return NodeSystemDispatcher;
}(_CommonDispatcher3.default);

exports.default = NodeSystemDispatcher;
