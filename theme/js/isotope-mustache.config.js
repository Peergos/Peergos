var reorder = null;
var shuffleAll = null;
var showIsotope = function(container, template, view){

    template = $(template).html();
    Mustache.parse(template);

    container = $(container);

    reorder = function(selected){
        $(".isotope-btn.active").removeClass("btn-primary");
        $(".isotope-btn.active").addClass("btn-default");
        $(".isotope-btn.active").removeClass("active");
        $(".isotope-btn[data-type='"+selected+"']").addClass("active");
        $(".isotope-btn[data-type='"+selected+"']").removeClass("btn-default");
        $(".isotope-btn[data-type='"+selected+"']").addClass("btn-primary");

        $(".width-medium").removeClass("width-medium");
        $(".isotope-item[data-type='"+selected+"']").addClass("width-medium");
//        $("#isotope-container").isotope('reloadItems');

        SELECTED_TYPE = selected;
        $("#isotope-container").isotope({ sortBy: 'priority' });
        $("#isotope-container").isotope('updateSortData').isotope();
    }

    shuffleAll = function(){
        $(".isotope-btn.active").removeClass("btn-primary");
        $(".isotope-btn.active").addClass("btn-default");
        $(".isotope-btn.active").removeClass("active");
        $(".isotope-btn[data-type='All']").addClass("active");
        $(".isotope-btn[data-type='All']").removeClass("btn-default");
        $(".isotope-btn[data-type='All']").addClass("btn-primary");

        $(".isotope-item").addClass("width-medium");
//        $("#isotope-container").isotope('reloadItems');

        $("#isotope-container").isotope({ sortBy: 'random' });
        $("#isotope-container").isotope('updateSortData').isotope();
    }

    container.html(Mustache.render(template, view));

    $(".isotope-btn").first().addClass("active");
    $(".isotope-btn").first().removeClass("btn-default");
    $(".isotope-btn").first().addClass("btn-primary");
    SELECTED_TYPE = $(".isotope-btn").first().attr('data-type');

    $("#isotope-container").isotope({
        itemSelector: '.isotope-item',
        getSortData: {
            priority: function(itemElem){
                type = $(itemElem).attr('data-type');
                if ( type === SELECTED_TYPE ){
                    return 1;
                }
                return 0;
            }
        },
        sortBy: 'priority',
        sortAscending: {
            priority: false
        },
        layoutMode: 'masonry',
        animationEngine: 'best-available',
        masonry: {
            columnWidth: 210,
        }
    });
}

$(window.document).ready(function(){
    if ( typeof _isotopeRoot !== "undefined" ){
        showIsotope(_isotopeRoot, _isotopeTemplate, _isotopeView);
        window.setTimeout(function(){
            reorder(SELECTED_TYPE);
        }, 300);
    }
});
