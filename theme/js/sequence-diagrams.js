if ($('.js_sequence_diagram').length) {
  $(".js_sequence_diagram").sequenceDiagram({theme: 'simple'});

  $("rect").attr("rx", "2px");
  $("rect").attr("ry", "2px");

  function nextSequence(elements, current, elementSequence){
    if ( current + (elementSequence.length-1) < elements.length ){
      for (var i=0; i<(elementSequence.length); i++ ){
        if ( elements[current + i].tagName != elementSequence[i] ){
          return false;
        }
      }
      return true;
    }
    return false;
  }

  $("svg").each(function(i, svg){

    var numElements = 0;
    var elements = $(svg).children();
    var current = 0;
    var elementSequence = [ "rect", "rect", "text", "rect", "rect", "text", "path" ];
    var linkSequence = [ "rect", "text", "path" ];
    var noteSequence = [ "rect", "rect", "text" ];
    while ( elements[current].tagName != "rect" ){
      current++;
    }
    while ( current < elements.length ){
      var newElement = false;
      var newLink = false;
      var newNote = false;
      if ( nextSequence(elements, current, elementSequence) ){
        newElement = true;
      }
      else if ( nextSequence(elements, current, linkSequence) ){
        newLink = true;
      }
      else if ( nextSequence(elements, current, noteSequence) ){
        newNote = true;
      }
      if ( newElement ){
        var elementKind = (numElements % 2 == 0)? "seq-diag-primary" : "seq-diag-secondary";
        current--;
        do{
          current++;
          $(elements[current]).attr("class", elementKind);
        }
        while ( elements[current].tagName != "path" );
        numElements++;
        current++;
      }
      else if ( newLink ){
        current--;
        do{
          current++;
          $(elements[current]).attr("class", "seq-diag-link");
        }
        while ( elements[current].tagName != "path" );
        current++;
      }
      else if ( newNote ){
        current--;
        do{
          current++;
          $(elements[current]).attr("class", "seq-diag-note");
        }
        while ( elements[current].tagName != "text" );
        current++;
      }
      else{
        current++;
      }
    }
  });
}
