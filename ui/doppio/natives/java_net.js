'use strict';
var Doppio = require('../doppiojvm');
var logging = Doppio.Debug.Logging;
var util = Doppio.VM.Util;
var ThreadStatus = Doppio.VM.Enums.ThreadStatus;
var debug = logging.debug;
var host_lookup = {}, host_reverse_lookup = {}, next_host_address = 4026531840;
function websocket_status_to_message(status) {
    switch (status) {
    case 1000:
        return 'Normal closure';
    case 1001:
        return 'Endpoint is going away';
    case 1002:
        return 'WebSocket protocol error';
    case 1003:
        return 'Server received invalid data';
    }
    return 'Unknown status code or error';
}
function next_address() {
    next_host_address++;
    if (next_host_address > 4194304000) {
        logging.error('Out of addresses');
        next_host_address = 4026531840;
    }
    return next_host_address;
}
function pack_address(address) {
    var i, ret = 0;
    for (i = 3; i >= 0; i--) {
        ret |= address[i] & 255;
        ret <<= 8;
    }
    return ret;
}
function host_allocate_address(address) {
    var ret = next_address();
    host_lookup[ret] = address;
    host_reverse_lookup[address] = ret;
    return ret;
}
function socket_read_async(impl, b, offset, len, resume_cb) {
    var i, available = impl.$ws.rQlen(), trimmed_len = available < len ? available : len, read = impl.$ws.rQshiftBytes(trimmed_len);
    for (i = 0; i < trimmed_len; i++) {
        b.array[offset++] = read[i];
    }
    resume_cb(trimmed_len);
}
var java_net_Inet4Address = function () {
    function java_net_Inet4Address() {
    }
    java_net_Inet4Address['init()V'] = function (thread) {
    };
    return java_net_Inet4Address;
}();
var java_net_Inet4AddressImpl = function () {
    function java_net_Inet4AddressImpl() {
    }
    java_net_Inet4AddressImpl['getLocalHostName()Ljava/lang/String;'] = function (thread, javaThis) {
        return thread.getJVM().internString('localhost');
    };
    java_net_Inet4AddressImpl['lookupAllHostAddr(Ljava/lang/String;)[Ljava/net/InetAddress;'] = function (thread, javaThis, hostname) {
        var rv = util.newObject(thread, thread.getBsCl(), 'Ljava/net/Inet4Address;');
        rv['<init>(Ljava/lang/String;I)V'](thread, [
            hostname,
            host_allocate_address(hostname.toString())
        ], function (e) {
            if (e) {
                thread.throwException(e);
            } else {
                thread.asyncReturn(util.newArrayFromData(thread, thread.getBsCl(), '[Ljava/net/InetAddress;', [rv]));
            }
        });
    };
    java_net_Inet4AddressImpl['getHostByAddr([B)Ljava/lang/String;'] = function (thread, javaThis, addr) {
        var ret = host_reverse_lookup[pack_address(addr.array)];
        if (ret == null) {
            return null;
        }
        return util.initString(thread.getBsCl(), '' + ret);
    };
    java_net_Inet4AddressImpl['isReachable0([BI[BI)Z'] = function (thread, javaThis, arg0, arg1, arg2, arg3) {
        return false;
    };
    return java_net_Inet4AddressImpl;
}();
var java_net_Inet6Address = function () {
    function java_net_Inet6Address() {
    }
    java_net_Inet6Address['init()V'] = function (thread) {
    };
    return java_net_Inet6Address;
}();
var java_net_InetAddress = function () {
    function java_net_InetAddress() {
    }
    java_net_InetAddress['init()V'] = function (thread) {
    };
    return java_net_InetAddress;
}();
var java_net_InetAddressImplFactory = function () {
    function java_net_InetAddressImplFactory() {
    }
    java_net_InetAddressImplFactory['isIPv6Supported()Z'] = function (thread) {
        return false;
    };
    return java_net_InetAddressImplFactory;
}();
var java_net_PlainSocketImpl = function () {
    function java_net_PlainSocketImpl() {
    }
    java_net_PlainSocketImpl['socketCreate(Z)V'] = function (thread, javaThis, isServer) {
        if (!util.are_in_browser()) {
            thread.throwNewException('Ljava/io/IOException;', 'WebSockets are disabled');
        } else {
            var fd = javaThis['java/net/SocketImpl/fd'];
            fd['java/io/FileDescriptor/fd'] = 8374;
            javaThis.$ws = new Websock();
            javaThis.$is_shutdown = false;
        }
    };
    java_net_PlainSocketImpl['socketConnect(Ljava/net/InetAddress;II)V'] = function (thread, javaThis, address, port, timeout) {
        var i, holder = address['java/net/InetAddress/holder'], addy = holder['java/net/InetAddress$InetAddressHolder/address'], host = 'ws://';
        if (host_lookup[addy] == null) {
            for (i = 3; i >= 0; i--) {
                var shift = i * 8;
                host += '' + ((addy & 255 << shift) >>> shift) + '.';
            }
            host = host.substring(0, host.length - 1);
        } else {
            host += host_lookup[addy];
        }
        host += ':' + port;
        ;
        thread.setStatus(ThreadStatus.ASYNC_WAITING);
        var id = 0, clear_state = function () {
                window.clearTimeout(id);
                javaThis.$ws.on('open', function () {
                });
                javaThis.$ws.on('close', function () {
                });
                javaThis.$ws.on('error', function () {
                });
            }, error_cb = function (msg) {
                return function (e) {
                    clear_state();
                    thread.throwNewException('Ljava/io/IOException;', msg + ': ' + e);
                };
            }, close_cb = function (msg) {
                return function (e) {
                    clear_state();
                    thread.throwNewException('Ljava/io/IOException;', msg + ': ' + websocket_status_to_message(e.status));
                };
            };
        javaThis.$ws.on('open', function () {
            ;
            clear_state();
            thread.asyncReturn();
        });
        javaThis.$ws.on('close', close_cb('Connection failed! (Closed)'));
        if (timeout === 0) {
            timeout = 10000;
        }
        id = setTimeout(error_cb('Connection timeout!'), timeout);
        ;
        try {
            javaThis.$ws.open(host);
        } catch (err) {
            error_cb('Connection failed! (exception)')(err.message);
        }
    };
    java_net_PlainSocketImpl['socketBind(Ljava/net/InetAddress;I)V'] = function (thread, javaThis, arg0, arg1) {
        thread.throwNewException('Ljava/io/IOException;', 'WebSockets doesn\'t know how to bind');
    };
    java_net_PlainSocketImpl['socketListen(I)V'] = function (thread, javaThis, arg0) {
        thread.throwNewException('Ljava/io/IOException;', 'WebSockets doesn\'t know how to listen');
    };
    java_net_PlainSocketImpl['socketAccept(Ljava/net/SocketImpl;)V'] = function (thread, javaThis, arg0) {
        thread.throwNewException('Ljava/io/IOException;', 'WebSockets doesn\'t know how to accept');
    };
    java_net_PlainSocketImpl['socketAvailable()I'] = function (thread, javaThis) {
        thread.setStatus(ThreadStatus.ASYNC_WAITING);
        setImmediate(function () {
            thread.asyncReturn(javaThis.$ws.rQlen());
        });
    };
    java_net_PlainSocketImpl['socketClose0(Z)V'] = function (thread, javaThis, arg0) {
        javaThis.$ws.close();
    };
    java_net_PlainSocketImpl['socketShutdown(I)V'] = function (thread, javaThis, arg0) {
        javaThis.$is_shutdown = true;
    };
    java_net_PlainSocketImpl['initProto()V'] = function (thread) {
    };
    java_net_PlainSocketImpl['socketSetOption0(IZLjava/lang/Object;)V'] = function (thread, javaThis, arg0, arg1, arg2) {
    };
    java_net_PlainSocketImpl['socketGetOption(ILjava/lang/Object;)I'] = function (thread, javaThis, arg0, arg1) {
        return 0;
    };
    java_net_PlainSocketImpl['socketSendUrgentData(I)V'] = function (thread, javaThis, data) {
        javaThis.$ws.send(data);
    };
    return java_net_PlainSocketImpl;
}();
var java_net_SocketInputStream = function () {
    function java_net_SocketInputStream() {
    }
    java_net_SocketInputStream['socketRead0(Ljava/io/FileDescriptor;[BIII)I'] = function (thread, javaThis, fd, b, offset, len, timeout) {
        var impl = javaThis['java/net/SocketInputStream/impl'];
        if (impl.$is_shutdown === true) {
            thread.throwNewException('Ljava/io/IOException;', 'Socket is shutdown.');
        } else {
            thread.setStatus(ThreadStatus.ASYNC_WAITING);
            setTimeout(function () {
                socket_read_async(impl, b, offset, len, function (arg) {
                    thread.asyncReturn(arg);
                });
            }, timeout);
        }
    };
    java_net_SocketInputStream['init()V'] = function (thread) {
    };
    return java_net_SocketInputStream;
}();
var java_net_SocketOutputStream = function () {
    function java_net_SocketOutputStream() {
    }
    java_net_SocketOutputStream['socketWrite0(Ljava/io/FileDescriptor;[BII)V'] = function (thread, javaThis, fd, b, offset, len) {
        var impl = javaThis['java/net/SocketOutputStream/impl'];
        if (impl.$is_shutdown === true) {
            thread.throwNewException('Ljava/io/IOException;', 'Socket is shutdown.');
        } else if (impl.$ws.get_raw_state() !== WebSocket.OPEN) {
            thread.throwNewException('Ljava/io/IOException;', 'Connection isn\'t open');
        } else {
            impl.$ws.send(b.array.slice(offset, offset + len));
            thread.setStatus(ThreadStatus.ASYNC_WAITING);
            setImmediate(function () {
                thread.asyncReturn();
            });
        }
    };
    java_net_SocketOutputStream['init()V'] = function (thread) {
    };
    return java_net_SocketOutputStream;
}();
var java_net_NetworkInterface = function () {
    function java_net_NetworkInterface() {
    }
    java_net_NetworkInterface['init()V'] = function (thread) {
    };
    java_net_NetworkInterface['getAll()[Ljava/net/NetworkInterface;'] = function (thread) {
        var bsCl = thread.getBsCl();
        thread.import([
            'Ljava/net/NetworkInterface;',
            'Ljava/net/InetAddress;'
        ], function (rv) {
            var niCons = rv[0], inetStatics = rv[1], iName = thread.getJVM().internString('doppio1');
            inetStatics['getByAddress(Ljava/lang/String;[B)Ljava/net/InetAddress;'](thread, [
                iName,
                util.newArrayFromData(thread, thread.getBsCl(), '[B', [
                    127,
                    1,
                    1,
                    1
                ])
            ], function (e, rv) {
                if (e) {
                    thread.throwException(e);
                } else {
                    var niObj = new niCons(thread);
                    niObj['<init>(Ljava/lang/String;I[Ljava/net/InetAddress;)V'](thread, [
                        iName,
                        0,
                        util.newArrayFromData(thread, bsCl, '[Ljava/net/InetAddress;', [rv])
                    ], function (e) {
                        if (e) {
                            thread.throwException(e);
                        } else {
                            thread.asyncReturn(util.newArrayFromData(thread, bsCl, '[Ljava/net/NetworkInterface;', [niObj]));
                        }
                    });
                }
            });
        });
    };
    java_net_NetworkInterface['getMacAddr0([BLjava/lang/String;I)[B'] = function (thread, inAddr, name, ind) {
        return util.newArrayFromData(thread, thread.getBsCl(), '[B', [
            1,
            1,
            1,
            1,
            1,
            1
        ]);
    };
    return java_net_NetworkInterface;
}();
registerNatives({
    'java/net/Inet4Address': java_net_Inet4Address,
    'java/net/Inet4AddressImpl': java_net_Inet4AddressImpl,
    'java/net/Inet6Address': java_net_Inet6Address,
    'java/net/InetAddress': java_net_InetAddress,
    'java/net/InetAddressImplFactory': java_net_InetAddressImplFactory,
    'java/net/PlainSocketImpl': java_net_PlainSocketImpl,
    'java/net/SocketInputStream': java_net_SocketInputStream,
    'java/net/SocketOutputStream': java_net_SocketOutputStream,
    'java/net/NetworkInterface': java_net_NetworkInterface
});
//# sourceMappingURL=java_net.js.map