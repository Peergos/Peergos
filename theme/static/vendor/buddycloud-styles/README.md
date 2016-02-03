buddycloud-styles
=================

### Requirements

For production:

* [bower](http://bower.io/)

For development:

* [grunt](http://gruntjs.com/)
* [compass](http://compass-style.org/)
* [pelican](http://getpelican.com/) (if you want to run docs)

### How to use

#### Production

Install it via bower using:

`bower install buddycloud/buddycloud-styles`

Then add the stylesheets and scripts to the page:

```html
<link rel="stylesheet" href="{{ bower directory }}/bootstrap/dist/css/bootstrap.min.css" type="text/css" />
<link rel="stylesheet" href="{{ bower directory }}/buddycloud-styles/dist/css/buddycloud-styles.min.css" type="text/css" />
```

```html
<script src="{{ bower directory }}/jquery/dist/jquery.min.js"></script>
<script src="{{ bower directory }}/bootstrap/dist/js/bootstrap.min.js"></script>
<script src="{{ bower directory }}/buddycloud-styles/dist/js/buddycloud-styles.min.js"></script>
```

#### Development

For the assets generation, you can run `grunt watch` in the terminal.

For the docs pages preview, run `make devserver` inside docs/ folder in the terminal.

### How to build and publish package

Just run `grunt build` in the terminal.
