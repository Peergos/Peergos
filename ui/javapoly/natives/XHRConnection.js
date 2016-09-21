(function e(t,n,r){function s(o,u){if(!n[o]){if(!t[o]){var a=typeof require=="function"&&require;if(!u&&a)return a(o,!0);if(i)return i(o,!0);var f=new Error("Cannot find module '"+o+"'");throw f.code="MODULE_NOT_FOUND",f}var l=n[o]={exports:{}};t[o][0].call(l.exports,function(e){var n=t[o][1][e];return s(n?n:e)},l,l.exports,e,t,n,r)}return n[o].exports}var i=typeof require=="function"&&require;for(var o=0;o<r.length;o++)s(r[o]);return s})({1:[function(require,module,exports){
"use strict";

registerNatives({
  'com/javapoly/XHRHttpURLConnection': {

    'getResponse([Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;[B)Lcom/javapoly/XHRResponse;': function getResponseLjavaLangStringLjavaLangStringLjavaLangStringBLcomJavapolyXHRResponse(thread, headers, method, url, outputBytes) {
      var methodStr = method.toString();
      var urlStr = url.toString();
      var myRequest = new XMLHttpRequest();
      myRequest.open(methodStr, urlStr);

      // Set the headers
      {
        var headerArray = headers.array;
        var headerCount = headerArray.length / 2;
        for (var i = 0; i < headerCount; i++) {
          myRequest.setRequestHeader(headerArray[2 * i], headerArray[2 * i + 1]);
        }
      }

      myRequest.responseType = "arraybuffer";
      myRequest.addEventListener("load", function () {
        thread.getBsCl().initializeClass(thread, 'Lcom/javapoly/XHRResponse;', function () {
          var responseObj = util.newObject(thread, thread.getBsCl(), 'Lcom/javapoly/XHRResponse;');
          responseObj['<init>(Ljava/lang/Object;)V'](thread, [myRequest], function (e) {
            if (e) {
              thread.throwException(e);
            } else {
              thread.asyncReturn(responseObj);
            }
          });
        });
      });

      thread.setStatus(6); // ASYNC_WAITING

      if (outputBytes == null) {
        myRequest.send();
      } else {
        myRequest.send(outputBytes.array);
      }
    }
  },

  'com/javapoly/XHRResponse': {

    'getResponseBytes(Ljava/lang/Object;)[B': function getResponseBytesLjavaLangObjectB(thread, xhrObj) {
      var array = Array.from(new Uint8Array(xhrObj.response));
      return util.newArrayFromData(thread, thread.getBsCl(), "[B", array);
    },

    'getHeaderField(Ljava/lang/Object;Ljava/lang/String;)Ljava/lang/String;': function getHeaderFieldLjavaLangObjectLjavaLangStringLjavaLangString(thread, xhrObj, name) {
      return util.initString(thread.getBsCl(), xhrObj.getResponseHeader(name));
    }
  }
});

},{}]},{},[1]);
