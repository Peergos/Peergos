"use strict";
/**
 * The CommonDispatcher is an abstract base class for other dispatchers.
 *
 * we define the some global Object{javaPolyMessageTypes,javaPolyCallbacks,javaPolyData}
 * for sharing args, callback function.
 *
 */

Object.defineProperty(exports, "__esModule", {
  value: true
});

var _createClass = function () { function defineProperties(target, props) { for (var i = 0; i < props.length; i++) { var descriptor = props[i]; descriptor.enumerable = descriptor.enumerable || false; descriptor.configurable = true; if ("value" in descriptor) descriptor.writable = true; Object.defineProperty(target, descriptor.key, descriptor); } } return function (Constructor, protoProps, staticProps) { if (protoProps) defineProperties(Constructor.prototype, protoProps); if (staticProps) defineProperties(Constructor, staticProps); return Constructor; }; }();

function _classCallCheck(instance, Constructor) { if (!(instance instanceof Constructor)) { throw new TypeError("Cannot call a class as a function"); } }

var CommonDispatcher = function () {
  function CommonDispatcher(javapoly) {
    _classCallCheck(this, CommonDispatcher);

    // This class is abstract (can't be instantiated directly)
    if (this.constructor === CommonDispatcher) {
      throw TypeError("new of abstract class CommonDispatcher");
    }

    this.options = javapoly.options;

    this.initDispatcher(javapoly);
  }

  _createClass(CommonDispatcher, [{
    key: "initDispatcher",
    value: function initDispatcher(javapoly) {
      this.javaPolyEvents = [];
      this.javaPolyMessageTypes = {};
      this.javaPolyCallbacks = {};
      this.javaPolyData = {};
      this.javaPolyIdCount = 0;

      this.javaPolyCallback = null;

      this.doppioManager = this.initDoppioManager(javapoly);
    }
  }, {
    key: "handleIncomingMessage",
    value: function handleIncomingMessage(id, priority, messageType, data, callback) {
      if (messageType.startsWith("META_")) {
        this.handleMetaMessage(id, priority, messageType, data, callback);
      } else if (messageType.startsWith("FS_")) {
        this.handleFSMessage(id, priority, messageType, data, callback);
      } else {
        this.handleJVMMessage(id, priority, messageType, data, callback);
      }
    }

    // JVM messages are added to a queue and dequed from the JVM main thread.

  }, {
    key: "handleJVMMessage",
    value: function handleJVMMessage(id, priority, messageType, data, callback) {

      this.addMessage(id, priority, messageType, data, callback);

      if (this.javaPolyCallback) {
        this.javaPolyCallback();
      }
    }

    // FS messages are processed immediately

  }, {
    key: "handleFSMessage",
    value: function handleFSMessage(id, priority, messageType, data, callback) {
      switch (messageType) {
        case "FS_MOUNT_JAVA":
          this.doppioManager.then(function (dm) {
            return dm.mountJava(data.src);
          });
          break;
        case "FS_DYNAMIC_MOUNT_JAVA":
          this.doppioManager.then(function (dm) {
            return dm.dynamicMountJava(data.src).then(function (msg) {
              if (msg === "OK") {
                callback({ success: true, returnValue: 'Add Class success' });
              } else {
                console.log("Failed to mount java file:", msg, data);
                callback({ success: false, returnValue: 'Add Class fail' });
              }
            }, function (msg) {
              return callback({ success: false, cause: { stack: [msg] } });
            });
          });
          break;
        default:
          console.log("FS TODO", messageType);
          break;
      }
    }

    // Meta messages are processed immediately

  }, {
    key: "handleMetaMessage",
    value: function handleMetaMessage(id, priority, messageType, data, callback) {
      switch (messageType) {
        case "META_START_JVM":
          this.doppioManager.then(function (dm) {
            return dm.initJVM();
          });
          break;
        default:
          console.log("META TODO", messageType);
          break;
      }
    }

    /* Add message with higher priority messages ahead of the lower priority ones */

  }, {
    key: "addMessage",
    value: function addMessage(id, priority, messageType, data, callback) {
      this.javaPolyMessageTypes[id] = messageType;
      this.javaPolyData[id] = data;
      this.javaPolyCallbacks[id] = callback;

      var queue = this.javaPolyEvents;
      var pos = queue.findIndex(function (e) {
        return e[1] < priority;
      });
      var value = ["" + id, priority];
      if (pos < 0) {
        // insert at end
        queue.push(value);
      } else {
        // insert at position
        queue.splice(pos, 0, value);
      }
    }

    /**
     * dequeue a message and get the messageID. Returns undefined when there is no message in the queue.
     */

  }, {
    key: "getMessageId",
    value: function getMessageId() {
      var msg = this.javaPolyEvents.shift();
      if (msg) {
        var id = msg[0];
        return id;
      } else {
        return undefined;
      }
    }
  }, {
    key: "getMessageType",
    value: function getMessageType(msgId) {
      // may want to delete the data after fetch
      var messageType = this.javaPolyMessageTypes[msgId];
      delete this.javaPolyMessageTypes[msgId];
      return messageType;
    }
  }, {
    key: "getMessageData",
    value: function getMessageData(msgId) {
      // may want to delete the data after fetch
      var messageData = this.javaPolyData[msgId];
      delete this.javaPolyData[msgId];
      return messageData;
    }
  }, {
    key: "getMessageCallback",
    value: function getMessageCallback(msgId) {
      var callback = this.javaPolyCallbacks[msgId];
      delete this.javaPolyCallbacks[msgId];
      return callback;
    }
  }, {
    key: "setJavaPolyCallback",
    value: function setJavaPolyCallback(callback) {
      this.javaPolyCallback = callback;
    }
  }, {
    key: "callbackMessage",
    value: function callbackMessage(msgId, returnValue) {
      var callback = this.javaPolyCallbacks[msgId];
      delete this.javaPolyCallbacks[msgId];
      if (callback) {
        callback(returnValue);
      }
    }
  }]);

  return CommonDispatcher;
}();

exports.default = CommonDispatcher;
