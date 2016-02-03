## buddycloud website
[![Build Status](https://travis-ci.org/buddycloud/buddycloud.com.png?branch=master)](https://travis-ci.org/buddycloud/buddycloud.com)

### Requirements

* Pelican 3.3
* ghp-import
* Markdown 2.3.1

### Introduction to Pelican

Pelican's [GETTING STARTED](http://docs.getpelican.com/en/latest/getting_started.html/) page is a good place to learn about the basics of Pelican (installation, project skeleton, development cycle, etc.).

### Installation instructions
```bash
git clone ssh://git@github.com/buddycloud/buddycloud.com.git
# install Pelican and dependencies
cd buddycloud.com
```

### Runing the server in development mode

```bash
make serve
```

If you want the server to autoreload whenever a file change, you can instead do:

```bash
make devserver
```
View at `http://localhost:8000`

### Configuration

```
<repo>
  fabfile.py
  develop_server.sh
  Makefile
  README.md
  pelicanconf.py (development configuration)
  publishconf.py (production configuration)
  output
    <generated files - published to gh-pages branch>
  content
    pages
      <website page files>
  pelican-bootstrap3
    <website theme>
```

### Site generation

To just generate a new version (without starting up a local webserver) just do:

`make html`

### Publishing your changes

Files are updated to the `gh_pages` branch
```bash
make github
```
view the updates on http://buddycloud.com

### Markdown Extensions

We have developed several markdown extensions to fit our needs.

#### Bootstrap Tooglable tabs Markdown Enxtension

Our newly defined Markdown extension for creating Bootstrap Togglable tabs.

Since Bootstrap requires two HTML elements to compose Tooglable tabs, we also decided it would be easier to implement the transformation if the new Markdown syntax also contained two sections, as follows:

There's the **Tab Key declaration** section and the **Tab Content declaration** section.

Tab Key declaration sections must be surrounded by the following lines:

	{@
	@}

And each line amidst those will contain a Tab Key declaration and should be as follows:

	{@[ KEY ]} where KEY can be any character

*Important:* you need to specify one of the Tab Key declarations to be the active one. To do so, you insert an $ sign before the enclosing brackets, as follows:

	{@$[ ACTIVE_KEY ]}

Tab Content declaration sections must be surrounded by the following lines:

	{{@
	@}}

And each block of lines amidst those will contain a Tab Content declaration. Remember, it is a block of lines. That block of lines must be surrounded by the following lines:

	{{@[ KEY ] where KEY must match a key declared at Tab Key declarations
	/@}}

*Important:* The Tab Key declaration that will be automatically active must have a $ sign before the enclosing brackets:

	{{@$[ ACTIVE_KEY ]

The active KEY must match the active KEY ofthe Tab Key declarations section.

The lines amidst those will be the content of your tabs.
Feel free to use any markup syntax there.

Example Usage:

	{@
	{@[KEY]}
	{@[KEY2]}
	...		(denoting multiple declarations in between)
	{@[KEYn]}
	@}
	{{@
	{{@$[KEY]
	...
	Your content for this tab
	...
	/@}}
	{{@[KEY2]
	...
	Your content for this tab
	/@}}
	...		(denoting multiple declarations in between
	{@[KEYn]
	...
	Your content for this tab
	...
	/@}}
	@}}

#### js-sequence-diagrams Markdown Extension

A new Markdown extension for writing Sequence Diagrams using the js-sequence-diagrams library.

All it does in fact is merge the js-sequence-diagrams syntax into the existing Markdown syntax, handling potential conflicts between the two.

We have the following restrictions upon the original js-sequence-diagrams syntax: definitions of sequence diagrams must not have black lines in between - the blocks of lines in between those blank lines will be understood as separate sequence diagram definitions.

For example:

    A->B: Message
    Note right of B: "B is receiving Message"
    
    B-->A: Message back

will not render a single sequence diagrams with the two messages specified.
Instead, there will be two sequence diagrams, each with one of the messages and the first one containing the note on the right of B.
To reach this result, the definition should be as follows:

    A->B: Message
    Note right of B: "B is receiving Message"
    B-->A: Message back

*IMPORTANT*: There must two blank lines, one before the sequence diagram block and another afterwards, separating the sequence diagram definition from other markup in the text.

#### Omnigraffle image exporting

Exports from Omnigraffle on OS X.

```
pip install omnigraffle-image-export
omnigraffle-export -f png buddycloud.com-diagrams.graffle ~/Documents/src/buddycloud.com/buddycloud-theme/static/img/diagrams
```

#### Slate pages

Coming soon.
