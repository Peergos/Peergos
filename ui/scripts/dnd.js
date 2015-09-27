    function dragHandler(e) {
		e.stopPropagation();
		e.preventDefault();
	}
	function selectHandler(e) {
		dragHandler(e);
		var files = e.target.files || e.dataTransfer.files;
        for (var i = 0; i < files.length; i++) {
            var file = files[i];
            console.log("File:" + file.name + " type: " + file.type + " size (bytes): " + file.size);
		}

	}
    var filedrag = document.getElementById("filedrag");
    filedrag.addEventListener("dragover", dragHandler, false);
    filedrag.addEventListener("dragleave", dragHandler, false);
    filedrag.addEventListener("drop", selectHandler, false);
