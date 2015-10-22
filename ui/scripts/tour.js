arrow = function(from, to) {
    // initialize pointy
    to.pointy({
        pointer      : from,
        // additional class name added to the pointer & the arrow (canvas)
        // to add a z-index of 1
        defaultClass : 'zindex',
        // "pointy-active" class is used to keep the last updated pointer on top
        // this is the default value
        activeClass  : 'pointy-active',
        // arrow base width (in pixels)
        arrowWidth   : 30
    });
}

// String -> String
drawArrow = function(sourceClass, targetClass) {
    arrow($("."+sourceClass), $("."+targetClass));
}

tourStep = function(text, targetClass) {
    $(".tour-text").text(text);
    drawArrow("pointer", targetClass);
}
