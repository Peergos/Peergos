$(window.document).ready(function(data){
    var uuid = (function() {
        function s4() {
            return Math.floor((1 + Math.random()) * 0x10000)
               .toString(16)
               .substring(1);
        }
        return function() {
            return s4() + s4() + '-' + s4() + '-' + s4() + '-' +
           s4() + '-' + s4() + s4() + s4();
        };
    })();

    jid = uuid() + "@starter.buddycloud.com";
    password = uuid() + "_password";
    $(".generated-u").text(jid);
    $(".generated-p").text(password);
});
