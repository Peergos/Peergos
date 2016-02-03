/*!
 * JavaScript for Bootstrap's docs (http://getbootstrap.com)
 * Copyright 2011-2014 Twitter, Inc.
 * Licensed under the Creative Commons Attribution 3.0 Unported License. For
 * details, see http://creativecommons.org/licenses/by/3.0/.
 */

var $window = $(window)
var $body   = $(document.body)

var navHeight = $('.navbar').outerHeight(true) + 10

$body.scrollspy({
  target: '.bs-docs-sidebar',
  offset: 70
})

$body.on('click', '.bs-docs-sidebar a', function (e) {
  setTimeout(function() {
    $window.scrollTop($window.scrollTop() - 65)
  }, 10);
})

$window.on('load', function () {
  $body.scrollspy('refresh')
})

$('.bs-docs-container [href=#]').click(function (e) {
  e.preventDefault()
})

// back to top
setTimeout(function () {
  var $sideBar = $('.bs-docs-sidebar')

  $sideBar.affix();
  $(document).on('')
}, 100)

setTimeout(function () {
  $('.bs-top').affix()
}, 100)

$(function() {
  $('pre:not(pre:has(code))').each(function(i, e) {
    hljs.highlightBlock(e);
  })
  $('pre code').each(function(i, e) {
    hljs.highlightBlock(e);
  })
})
