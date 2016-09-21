'use strict';

var MAGIC_NUMBER = 'cafebabe';
var CP_TAG_SIZE = 1;

function uintFromBuffer(buffer, from, count) {
  var res = 0;
  var bufferGetter = global.BrowserFS ? function (index) {
    return buffer.get(index);
  } : function (index) {
    return buffer[index];
  };
  for (var i = 0; i < count; i++) {
    res = res * 256 + bufferGetter(from + i);
  }
  return res;
}

function stringFromBuffer(buffer, from, count) {
  var res = '';
  var bufferGetter = global.BrowserFS ? function (index) {
    return buffer.get(index);
  } : function (index) {
    return buffer[index];
  };
  for (var i = 0; i < count; i++) {
    res += String.fromCharCode(bufferGetter(from + i));
  }
  return res;
}

function hexFromBuffer(buffer, from, count) {
  var res = '';
  var bufferGetter = global.BrowserFS ? function (index) {
    return buffer.get(index);
  } : function (index) {
    return buffer[index];
  };
  for (var i = 0; i < count; i++) {
    res += bufferGetter(from + i).toString(16);
  }
  return res;
}

function ConstantPoolClassRef(buffer, from) {
  this.ref = uintFromBuffer(buffer, from, 2);
}

/**
 * This function analyze buffer that contains class-file and returns basic info about it.
 * @param  {Buffer} buffer that contains binary data of class-file
 * @return {Object}        object that represents key-value info of file
 */
function analyze(data) {
  'use strict';

  var magic_number = hexFromBuffer(data, 0, 4);
  if (magic_number !== MAGIC_NUMBER) throw 'Class file should starts with ' + MAGIC_NUMBER + ' string';

  var minor_version = uintFromBuffer(data, 4, 2);
  var major_version = uintFromBuffer(data, 6, 2);
  var constant_pool_count = uintFromBuffer(data, 8, 2);

  var constant_pool = [];

  var cpsize = 10;

  for (var i = 1; i < constant_pool_count; i++) {
    var cp_tag = uintFromBuffer(data, cpsize, CP_TAG_SIZE);
    var size = 0;
    switch (cp_tag) {
      case 1:
        size = 2 + uintFromBuffer(data, cpsize + 1, 2);
        constant_pool[i] = stringFromBuffer(data, cpsize + 3, size - 2);
        break;
      case 3:
        size = 4;break;
      case 4:
        size = 4;break;
      case 5:
        size = 8;i++;break;
      case 6:
        size = 8;i++;break;
      case 7:
        size = 2;
        constant_pool[i] = new ConstantPoolClassRef(data, cpsize + 1);
        break;
      case 8:
        size = 2;break;
      case 9:
        size = 4;break;
      case 10:
        size = 4;break;
      case 11:
        size = 4;break;
      case 12:
        size = 4;break;
      case 15:
        size = 3;break;
      case 16:
        size = 2;break;
      case 18:
        size = 4;break;
      default:
        throw 'Wrong cp_tag: ' + cp_tag;
    }
    cpsize += CP_TAG_SIZE + size;
  }

  var access_flags = uintFromBuffer(data, cpsize, 2);
  var this_class = uintFromBuffer(data, cpsize + 2, 2);
  var super_class = uintFromBuffer(data, cpsize + 4, 2);

  return {
    minor_version: minor_version,
    major_version: major_version,
    constant_pool: constant_pool,
    this_class: constant_pool[constant_pool[this_class].ref],
    super_class: constant_pool[constant_pool[super_class].ref]
  };
}

module.exports.analyze = analyze;
