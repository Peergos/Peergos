/* jQuery Pointy plugin v1.0.1-beta (updated 3/14/2015)
 * by Rob Garrison (Mottie)
 * MIT License
 * Requires: jQuery v1.7+
 */
/*jshint browser:true, jquery:true, unused:false */
/*global require:false, define:false, module:false */
;( function( factory ) {
	if ( typeof define === 'function' && define.amd ) {
		define( ['jquery'], factory );
	} else if ( typeof module === 'object' && typeof module.exports === 'object' ) {
		module.exports = factory( require( 'jquery' ) );
	} else {
		factory( jQuery );
	}
}( function( $ ) {
'use strict';

var $pointy =	$.pointy = function( pointee, options ) {
	var pointy = this, o;

	$pointy.version = '1.0.1-beta';

	pointy.$pointee = $( pointee );
	pointy.pointee = pointee;

	pointy.defaults = {
		pointer         : null,
		arrowWidth      : 20, // width of pointer pointer
		borderWidth     : 1,  // pointer stroke width
		flipAngle       : 45, // angle @ which to flip pointer to a closer side
		defaultClass    : '', // additional class name added to the pointer & the arrow (canvas)
		activeClass     : 'pointy-active', // class added to pointer & pointer on updating

		// optional; if not set, plugin will attempt to match the pointer color
		borderColor     : null,
		backgroundColor : null,

		// tweaks
		useOffset       : null
	};

	// Add a reverse reference to the DOM object
	pointy.$pointee.data( 'pointy', pointy );

	pointy.init = function() {
		pointy.options = o = $.extend( {}, pointy.defaults, options );

		// create a unique namespace
		pointy.namespace = '.pointy' + Math.random().toString( 16 ).slice( 2 );

		var events = 'pointy-update pointy-refresh pointy-show pointy-hide pointy-destroy '
			.split( ' ' )
			.join( pointy.namespace + ' ' );
		pointy.$pointee
			.on( events, function(event) {
				var fxn = ( event.type || '' ).replace( 'pointy-', '' );
				if ( fxn in pointy && $.isFunction( pointy[ fxn ] ) ) {
					pointy[ fxn ]();
				}
			});

		$( window )
			.off( 'resize' + pointy.namespace )
			.on( 'resize' + pointy.namespace, function() {
				pointy.refresh();
			});

		pointy.show();
	};

	// convert radians to degrees
	pointy.rad2deg = 180 / Math.PI;

	// nw  n  ne
	//  w  x  e  ( x = pointee; outer = pointer position )
	// sw  s  se
	pointy.regions = {
		n : function( data ) {
			// **** pointer is above (north) of the pointee ****
			if ( data.pointer.bottom < data.pointee.top &&
				// left edge
				data.pointer.midW > data.pointee.left &&
				// right edge
				data.pointer.midW < data.pointee.right ) {

				var flip = data.pointer.midW > data.pointee.midW;

				data.canvas.width = Math.abs( data.pointer.midW - data.pointee.midW ) + o.arrowWidth;
				data.canvas.height = data.pointee.top - data.pointer.bottom - data.pointer.border;

				return {
					start : [ flip ? data.canvas.width - o.arrowWidth : 0, 0 ],
					mid   : [ flip ? data.halfArrowW : data.canvas.width - data.halfArrowW, data.canvas.height ],
					end   : [ flip ? data.canvas.width : o.arrowWidth, 0 ],
					cLeft : ( flip ? data.pointee.midW : data.pointer.midW ) - data.halfArrowW,
					cTop  : data.pointer.bottom - data.pointer.border
				};
			}
			return false;
		},
		ne : function( data ) {
			// **** pointer is above-right (northeast) of the pointee ****
			if ( data.pointer.bottom <= data.pointee.top &&
				// left edge
				data.pointer.midW > data.pointee.right ) {

				var angle = Math.atan( ( data.pointer.left - data.pointee.right ) / ( data.pointer.bottom - data.pointee.top ) ) * pointy.rad2deg,
					flip = -angle > o.flipAngle && data.pointer.left > data.pointee.right;

				data.canvas.width = flip ? data.pointer.left - data.pointee.right + data.pointer.border : data.pointer.midW - data.pointee.right + data.pointer.border + o.arrowWidth;
				data.canvas.height = flip ? data.pointee.top - data.pointer.midH : data.pointee.top - data.pointer.bottom - data.pointer.border;

				return {
					start : [ data.canvas.width - ( flip ? 0 : o.arrowWidth ), 0 ],
					mid   : [ 0, data.canvas.height ],
					end   : [ data.canvas.width, flip ? o.arrowWidth : 0 ],
					cLeft : data.pointee.right + ( flip ? data.pointer.border : 0 ),
					cTop  : flip ? data.pointer.midH : data.pointer.bottom - data.pointer.border
				};
			}
			return false;
		},
		e : function( data ) {
			// **** pointer is to the right (east) of the pointee ****
			if ( data.pointer.top < data.pointee.bottom && data.pointer.bottom > data.pointee.top &&
				// left edge
				data.pointer.left >= data.pointee.right ) {

				var flip = data.pointer.midH < data.pointee.midH;

				data.canvas.width = data.pointer.left - data.pointee.right + data.pointer.border;
				data.canvas.height = Math.abs( data.pointee.midH - data.pointer.midH ) + o.arrowWidth;

				return {
					start : [ data.canvas.width, flip ? 0 : data.canvas.height ],
					mid   : [ 0, flip ? data.canvas.height - data.halfArrowW : data.halfArrowW ],
					end   : [ data.canvas.width, flip ? o.arrowWidth : data.canvas.height - o.arrowWidth ],
					cLeft : data.pointee.right + data.pointer.border,
					cTop  : ( flip ? data.pointer.midH : data.pointee.midH ) - data.halfArrowW
				};
			}
			return false;
		},
		se : function( data ) {
			// **** pointer is below-right (southeast) of the pointee ****
			if ( data.pointer.top >= data.pointee.bottom &&
				// left edge
				data.pointer.midW >= data.pointee.right ) {

				var angle = Math.atan( ( data.pointer.left - data.pointee.right ) / ( data.pointer.top - data.pointee.bottom ) ) * pointy.rad2deg,
					flip = angle > o.flipAngle && data.pointer.left > data.pointee.right + o.arrowWidth;

				data.canvas.width = ( flip ? data.pointer.left : data.pointer.midW + o.arrowWidth ) - data.pointee.right + data.pointer.border;
				data.canvas.height = ( flip ? data.pointer.midH : data.pointer.top ) - data.pointee.bottom;

				return {
					start : [ data.canvas.width - ( flip ? 0 : o.arrowWidth ), data.canvas.height ],
					mid   : [ 0, 0 ],
					end   : [ data.canvas.width, data.canvas.height - ( flip ? o.arrowWidth : 0 ) ],
					cLeft : data.pointee.right + data.pointer.border,
					cTop  : data.pointee.bottom + data.pointer.border
				};
			}
			return false;
		},
		s : function( data ) {
			// **** pointer is below (south) the pointee ****
			if ( data.pointer.top - data.pointee.bottom >= 0 &&
				// left edge
				data.pointer.midW > data.pointee.left &&
				// right edge
				data.pointer.midW < data.pointee.right ) {

				var flip = data.pointer.midW > data.pointee.midW;

				data.canvas.width = Math.abs( data.pointer.midW - data.pointee.midW ) + o.arrowWidth;
				data.canvas.height = data.pointer.top - data.pointee.bottom;

				return {
					start : [ flip ? data.canvas.width - o.arrowWidth : 0, data.canvas.height ],
					mid   : [ flip ? data.halfArrowW : data.canvas.width - data.halfArrowW, 0 ],
					end   : [ flip ? data.canvas.width : o.arrowWidth, data.canvas.height ],
					cLeft : ( flip ? data.pointee.midW : data.pointer.midW ) - data.halfArrowW,
					cTop  : data.pointee.bottom + data.pointer.border
				};
			}
			return false;
		},
		sw : function( data ) {
			// **** pointer is below-left (southwest) of the pointee ****
			if ( data.pointer.top - data.pointee.bottom >= 0 &&
				// right edge
				data.pointer.midW <= data.pointee.left ) {

				var angle = Math.atan( ( data.pointer.right - data.pointee.left ) / ( data.pointer.top - data.pointee.bottom ) ) * pointy.rad2deg,
					flip = -angle > o.flipAngle;

				data.canvas.width = flip ? data.pointee.left - data.pointer.left - data.pointer.width + o.borderWidth : Math.abs( data.pointer.midW - data.pointee.left ) + o.arrowWidth;
				data.canvas.height = flip ? data.pointer.midH - data.pointee.bottom : data.pointer.top - data.pointee.bottom;

				return {
					start : [ 0, data.canvas.height ],
					mid   : [ data.canvas.width - ( flip ? 0 : o.arrowWidth ), 0 ],
					end   : [ flip ? 0 : o.arrowWidth, flip ? data.canvas.height - o.arrowWidth : data.canvas.height ],
					cLeft : flip ? data.pointer.left + data.pointer.width - data.pointer.border : data.pointer.midW,
					cTop  : data.pointee.bottom + data.pointer.border
				};
			}
			return false;
		},
		w : function( data ) {
			// **** pointee to the left (west) of pointee ****
			if ( data.pointer.top < data.pointee.bottom && data.pointer.bottom > data.pointee.top &&
				// right edge
				data.pointer.right <= data.pointee.left ) {

				var flip = data.pointer.midH < data.pointee.midH;

				data.canvas.width = data.pointee.left - data.pointer.left - data.pointer.width;
				data.canvas.height = Math.abs( data.pointee.midH - data.pointer.midH ) + o.arrowWidth;

				return {
					start : [ 0, flip ? 0 : data.canvas.height ],
					mid   : [ data.canvas.width, flip ? data.canvas.height - data.halfArrowW : data.halfArrowW ],
					end   : [ 0, flip ? o.arrowWidth : data.canvas.height - o.arrowWidth ],
					cLeft : data.pointer.left + data.pointer.width - data.pointer.border,
					cTop  : ( flip ? data.pointer.midH : data.pointee.midH ) - data.halfArrowW
				};
			}
			return false;
		},
		nw : function( data ) {
			// **** pointer is above-left (northwest) of the pointee ****
			if ( data.pointer.bottom <= data.pointee.top &&
				// left edge
				data.pointer.midW < data.pointee.left ) {

				var angle = Math.atan( ( data.pointee.top - data.pointer.bottom ) / ( data.pointer.right - data.pointee.left ) ) * pointy.rad2deg,
					flip = angle < o.flipAngle && data.pointer.right < data.pointee.left - o.arrowWidth;

				data.canvas.width = data.pointee.left + data.pointer.border - ( flip ? data.pointer.right : data.pointer.midW - o.arrowWidth );
				data.canvas.height = data.pointee.top - ( flip ? data.pointer.midH : data.pointer.bottom - data.pointer.border );

				return {
					start : [ 0, 0 ],
					mid   : [ data.canvas.width - ( flip ? 0 : o.arrowWidth ), data.canvas.height ],
					end   : [ flip ? 0 : o.arrowWidth, flip ? o.arrowWidth : 0 ],
					cLeft : flip ? data.pointer.right - data.pointer.border : data.pointer.midW,
					cTop  : flip ? data.pointer.midH : data.pointer.bottom - data.pointer.border
				};
			}
			return false;
		}
	};

	pointy.show = function( pointer ) {
		// refresh pointer in case pointee is dynamically added
		// named "pointer" because the pointer of the arrow is sitting on it
		pointy.$pointer = $( pointer || pointy.options.pointer ).addClass( 'pointy ' + o.defaultClass );

		// nothing to point to!
		if ( !pointy.$pointer.length ) { return; }

		pointy.add();
		pointy.refresh();
	};

	pointy.hide = function() {
		// remove from DOM
		pointy.$arrow.remove();
		pointy.$arrow = null;
		pointy.visible = false;
	};

	pointy.add = function() {
		// remove previous pointer, if it still exists
		if ( pointy.$arrow && pointy.$arrow.length ) {
			pointy.$arrow.remove();
		}

		pointy.$arrow = $( '<canvas class="pointy ' + o.defaultClass + '">' )
			.css({
				position : 'absolute',
				display  : 'none',
				'pointer-events' : 'none'
			});
		pointy.$pointee.after( pointy.$arrow );
	};

	// easy to use API name
	pointy.refresh = function() {
		pointy.setConstants();
		pointy.update();
	};

	pointy.setConstants = function() {
		if ( pointy.$arrow && pointy.$arrow[0].getContext ) {
			var $pointer = pointy.$pointer,
				$pointee = pointy.$pointee,
				$arrow = pointy.$arrow;

			pointy.data = {
				$pointer : $pointer,
				$pointee : $pointee,
				canvas : $arrow[0],
				ctx : $arrow[0].getContext( '2d' ),
				pointer : {
					height : $pointer.outerHeight( true ),
					width  : $pointer.outerWidth(),
					border : parseInt( $pointer.css( 'border-width' ), 10 ) || 0
				},
				pointee : {
					height : $pointee.outerHeight( true ),
					width  : $pointee.outerWidth()
				},
				halfArrowW : o.arrowWidth / 2,
				borderColor : o.borderColor || $pointer.css( 'border-color' ),
				backgroundColor : o.backgroundColor || $pointer.css( 'background-color' )
			};
		}
	};

	pointy.update = function() {
		if ( pointy.$arrow && pointy.$arrow[0].getContext ) {
			if ( o.activeClass ) {
				// add class to last active pointy (possibly for z-index settings)
				$( '.pointy.' + o.activeClass ).removeClass( o.activeClass );
				pointy.$pointer.add( pointy.$arrow ).addClass( o.activeClass );
			}

			var data = pointy.data,
				reg = data.$pointer.offset(),
				pos = data.$pointer.position(),
				// compare offset & position left, if position === offset, then use offset
				useOffset = typeof o.useOffset === 'boolean' ? o.useOffset : pos.left === reg.left;

			pos = useOffset ? reg : pos;
			data.pointer.left   = pos.left;
			data.pointer.right  = pos.left + data.pointer.width;
			data.pointer.top    = pos.top;
			data.pointer.bottom = pos.top + data.pointer.height;
			data.pointer.midW   = data.pointer.left + data.pointer.width / 2;
			data.pointer.midH   = data.pointer.top + data.pointer.height / 2;

			pos = useOffset ? data.$pointee.offset() : data.$pointee.position();
			data.pointee.left   = pos.left;
			data.pointee.right  = pos.left + data.pointee.width;
			data.pointee.top    = pos.top;
			data.pointee.bottom = pos.top + data.pointee.height;
			data.pointee.midW   = data.pointee.left + data.pointee.width / 2;
			data.pointee.midH   = data.pointee.top + data.pointee.height / 2;

			// clear out previous calculations
			pointy.calculated = null;
			$.each( pointy.regions, function( i, region ) {
				reg = region( data );
				if ( reg ) {
					pointy.calculated = reg;
					// break out of loop
					return false;
				}
			});
			pointy.draw();

		}
	};

	pointy.draw = function() {
		if ( !$.isEmptyObject( pointy.calculated ) ) {
			var data = pointy.data,
				ctx = data.ctx,
				calc = pointy.calculated;

			pointy.$arrow
				.show()
				.css({
					left : calc.cLeft,
					top  : calc.cTop,
				});
			pointy.visible = true;

			ctx.fillStyle = data.backgroundColor;
			ctx.strokeStyle = data.borderColor;
			ctx.lineWidth = data.borderWidth;

			ctx.beginPath();
			ctx.moveTo( calc.start[0], calc.start[1] );
			ctx.lineTo( calc.mid[0], calc.mid[1] );
			ctx.lineTo( calc.end[0], calc.end[1] );
			ctx.fill();
			ctx.stroke();

		} else {
			// no positioning data, so hide the arrow
			// (the pointee elements are overlapping)
			pointy.$arrow.hide();
		}
	};

	pointy.destroy = function() {
		pointy.$arrow.remove();
		pointy.$pointee.off( pointy.namespace );
		// jQuery v1.7-1.8 has a bug; if the string in removeClass has a double space, it removes ALL class names
		pointy.$pointer.removeClass( ( 'pointy ' + o.defaultClass + ' ' + o.activeClass ).replace(/\s+/g, ' ') );
		$( window ).off( pointy.namespace );
		$.removeData( pointy.pointee, 'pointy' );
	};

	pointy.init();

};

$.fn.pointy = function( options ) {
	return this.each( function() {
		var pointy = $( this ).data( 'pointy' );
		if ( !pointy ) {
			( new $.pointy( this, options ) );
		} else if ( pointy.visible || pointy.$arrow ) {
			// hide pointy if already visible
			pointy.hide();
		} else {
			// show pointy if not visible & update pointer dynamically
			pointy.show( options.pointer );
		}
	});
};

$.fn.getpointy = function() {
	return this.data( 'pointy' );
};

return $pointy;

}));
