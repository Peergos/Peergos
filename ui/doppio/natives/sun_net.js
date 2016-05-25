'use strict';
var sun_net_spi_DefaultProxySelector = function () {
    function sun_net_spi_DefaultProxySelector() {
    }
    sun_net_spi_DefaultProxySelector['init()Z'] = function (thread) {
        return true;
    };
    sun_net_spi_DefaultProxySelector['getSystemProxy(Ljava/lang/String;Ljava/lang/String;)Ljava/net/Proxy;'] = function (thread, javaThis, arg0, arg1) {
        return null;
    };
    return sun_net_spi_DefaultProxySelector;
}();
registerNatives({ 'sun/net/spi/DefaultProxySelector': sun_net_spi_DefaultProxySelector });
//# sourceMappingURL=sun_net.js.map