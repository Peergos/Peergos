from markdown.preprocessors import Preprocessor
from markdown.blockprocessors import BlockProcessor
from markdown.postprocessors import Postprocessor
from markdown.util import etree
from markdown.util import AtomicString
from markdown.extensions import Extension
import re

class TabbedNavPre(Preprocessor):
	"""
	*Bootstrap Tooglable tabs preprocessor*.
	
	Necessary if the tabbed content was originally converted from
	wikitext from https://buddycloud.org/wiki,
	therefore formatted like this:

	<tabbed>
	KEY=
	VALUE
	|-| KEY2=
	VALUE
	...
	|-| KEYn=
	VALUE
	</tabbed>

	This preprocessor will transform that into this:

	{@
	{@$[KEY]}
	{@[KEY2]}
	...
	{@[KEYn]}
	@}
	{{@
	{{@$[KEY]
	VALUE
	/@}}
	{{@[KEY2]
	VALUE
	/@}}
	...
	{@[KEYn]
	VALUE
	/@}}
	@}}

	Which will be easier to be handled by our postprocessor.
	"""

	def __init__(self):

		self.brkstartre = re.compile("^.*< *tabber *>")
		self.brkendre = re.compile("^.*< */tabber *>")

	def run(self, lines):

		new_lines = []
		tabbed_block = ""
		inside_block = False

		while ( len(lines) != 0 ):
			
			line = lines[0]

		#Is it just starting a new tabbed block?
			if line.strip().startswith("<tabber>"):
				inside_block = True

		#Does it have </tabber> ending tags in the middle of the line?
			if ( not line.strip().startswith("</tabber>")
			     and self.brkendre.match(line.strip()) ):
				split = [line[:line.find("</tabber>")],
					line[line.find("</tabber>"):] ]
				lines.pop(0)
				lines.insert(0, split[1])
				lines.insert(0, split[0])
				continue

		#What about <tabber> starting tags?
			if ( not line.strip().startswith("<tabber>")
			     and self.brkstartre.match(line.strip()) ):
				split = [line[:line.find("<tabber>")],
					line[line.find("<tabber>"):] ]
				lines.pop(0)
				lines.insert(0, split[1])
				lines.insert(0, split[0])
				continue

		#Is the line empty, within a tabbed block?
			if line.strip() == "" and inside_block:
				line = "\n"

		#If inside block, store line content
		#to be added as a single line later
			if inside_block:
				tabbed_block += "\n" + line
		#Otherwise just add new line
			else:
				new_lines.append(line)
			lines.pop(0)

		#Is it finishing a tabbed block?
			if line.startswith("</tabber>"):
				inside_block = False
				new_lines.append(tabbed_block)
				tabbed_block = ""

		i = 0
		while ( i < len(new_lines) ):

			line = new_lines[i]
			i += 1

		#Is this line representing a tabbed content?
			if line.strip().startswith("<tabber>"):

				i -= 1
			#Swap this line for a bunch of other lines
			#with a different structure representing
			#the tabbed content
				new_lines = new_lines[:i] + new_lines[i+1:]

				keys = []
				values = {}

				line = line.replace("<tabber>", "")
				line = line.replace("</tabber>", "")

				for keyval in line.split("|-|"):
					sep = keyval.find("=")
					key = keyval[:sep].strip()
					val = keyval[sep+1:]
					keys.append(key)
					values[key] = val

				new_lines.insert(i, "{@")
				i += 1
				first = True
				for key in keys:
					if first:
						new_lines.insert(i,
							"{@$[%s]}" % key)
						first = False
					else:
						new_lines.insert(i,
							"{@[%s]}" % key)
					i += 1
				new_lines.insert(i, "@}")
				i += 1

				new_lines.insert(i, "{{@")
				i += 1
				first = True
				for key in keys:
					if first:
						new_lines.insert(i,
							"{{@$[%s]" % key)
						first = False
					else:
						new_lines.insert(i,
							"{{@[%s]" % key)
					i += 1
					content_lines = values[key].split("\n")
					for c_line in content_lines:
						new_lines.insert(i, c_line)
						i += 1
					new_lines.insert(i, "/@}}")
					i += 1
				new_lines.insert(i, "@}}")
				i += 1

		#Now make sure there's at least one blank line amidst each
		#Tabbed Nav block (Bootstrap Tooglable tabs Markdown syntax)

		add_blanks_at = []
		aftertabcontentdefre = re.compile("{{@\[.*\]$")
		afteractivetabcontentdefre = re.compile("{{@\$\[.*\]$")

		for i in range(len(new_lines)):

			line = new_lines[i]

			if line == "{@":
				add_blanks_at.append(i)
			elif line == "/@}}":
				add_blanks_at.append(i)
			elif line == "@}}":
				add_blanks_at.append(i+1)
			elif aftertabcontentdefre.match(line):
				add_blanks_at.append(i+1)
			elif afteractivetabcontentdefre.match(line):
				add_blanks_at.append(i+1)

		for k in range(len(add_blanks_at)):
			new_lines.insert(add_blanks_at[k], "\n")
			for j in range(k+1, len(add_blanks_at)):
				add_blanks_at[j] += 1

		return new_lines

class TabbedNavBlockProcessor(BlockProcessor):
	"""
	*Bootstrap Tooglable tabs block processor*.

	Necessary to avoid having Markdown surround the
	Bootstrap Tooglable tabs Markdown markups with undesired HTML tags.

	Each block of BTNM markup is then surrounded by a <tabbed_nav> element
	which is then parsed out by our postprocessor.
	"""

	def __init__(self):
		pass

	def test(self, parent, block):

		veredict = ( ( block.startswith("{@\n{@[")
				and block.endswith("]") )
			or   ( block.startswith("{@\n{@$[")
				and block.endswith("]") )
			or   ( block.startswith("/@}}\n{{@[")
				and block.endswith("]") )
			or   ( block.startswith("/@}}\n{{@$[")
				and block.endswith("]") )
			or   ( block.startswith("/@}}\n@}}") ) )

		return veredict

	def run(self, parent, blocks):

		tabbed_nav = etree.SubElement(parent, "tabbed_nav")
		tabbed_nav.text = AtomicString(blocks[0])
		blocks.pop(0)

class TabbedNavPost(Postprocessor):
	"""
	*Bootstrap Tooglable tabs postprocessor*.

	Processes our newly defined Markdown syntax
	for creating Bootstrap Togglable tabs.

	Since Bootstrap requires two HTML elements to compose Tooglable tabs,
	we also decided it would be easier to implement the transformation if
	the Markdown syntax also contained two sections, as follows:

	There's the *Tab Key declaration* section and the *Tab Content declaration* section.

	Tab Key declaration sections must be surrounded by the following lines:

	{@
	@}

	And each line amidst those will contain a Tab Key declaration and should be as follows:

	{@[ KEY ]} where KEY can be any character

	Important: you need to specify one of the Tab Key declarations to be the active one. To do so, you insert an $ sign before the enclosing brackets, as follows:

	{@$[ ACTIVE_KEY ]}

	Tab Content declaration sections must be surrounded by the following lines:

	{{@
	@}}

	And each block of lines amidst those will contain a Tab Content declaration. Remember, it is a block of lines. That block of lines must be surrounded by the following lines:

	{{@[ KEY ] where KEY must match a key declared at Tab Key declarations
	/@}}

	Important: The Tab Key declaration that will be automatically active must have a $ sign before the enclosing brackets:

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
 
	"""

	def __init__(self):

		self.starttabsre = re.compile("(?<!{){@\s+")
		self.tabkeydeclre = re.compile("(?<!{){@\[.*\]}")
		self.activetabkeydeclre = re.compile("(?<!{){@\$\[.*\]}")
		self.endtabsre = re.compile("@}\s+")
		self.startcontentsre = re.compile("{{@\s+")
		self.tabcontentdeclre = re.compile("{{@\[.*\]\s*")
		self.activetabcontentdeclre = re.compile("{{@\$\[.*\]\s*")
		self.endcontentsre = re.compile("/?@}}")
		self.keys_taken = []

	def produce_new_id(self, key):

		key_id = key.strip().replace(" ", "_").lower()

		while (key_id in self.keys_taken):

			key_id = "_" + key_id

		self.keys_taken.append(key_id)
		return key_id

	def consume_existing_id(self, key):

		key_id = key.strip().replace(" ", "_").lower()
		stub_keys = map(lambda x: x.replace("_", ""), self.keys_taken)

		if key_id.replace("_", "") in stub_keys:
		
			key_id_at = stub_keys.index(key_id.replace("_", ""))
			key_id = self.keys_taken[key_id_at]
			self.keys_taken.remove(key_id)

		return key_id

	def tabkeydeclrepl(self, matchobj):
		
		matched = matchobj.group(0).strip()
		key = matched.replace("{@[", "").replace("]}", "")
		html = "\t<li><a href='#togglable_tabs_id_%s' data-toggle='tab'>%s</a></li>"
		return html % (self.produce_new_id(key), key)

	def activetabkeydeclrepl(self, matchobj):
		
		matched = matchobj.group(0).strip()
		key = matched.replace("{@$[", "").replace("]}", "")
		html = "\t<li class='active'><a href='#togglable_tabs_id_%s' data-toggle='tab'>%s</a></li>"
		return html % (self.produce_new_id(key), key)

	def tabcontentdeclrepl(self, matchobj):

		matched = matchobj.group(0).strip()
		key = matched.replace("{{@[", "").replace("]", "")
		html = "\t<div class='tab-pane fade' id='togglable_tabs_id_%s'>\n\t\t"
		return html % self.consume_existing_id(key)

	def activetabcontentdeclrepl(self, matchobj):

		matched = matchobj.group(0).strip()
		key = matched.replace("{{@$[", "").replace("]", "")
		html ="\t<div class='tab-pane fade in active' id='togglable_tabs_id_%s'>\n\t\t"
		return html % self.consume_existing_id(key)

	def endingcontentsrepl(self, matchobj):

		matched = matchobj.group(0).strip()
		html = "</div>"
		return "\t" + html if matched.startswith("/") else html	

	def run(self, text):

	#Removing the surrounding <tabbed_nav> and </tabbed_nav`> tags
		text = text.replace("<tabbed_nav>", "")
		text = text.replace("</tabbed_nav>", "")

	#Replacing all proper starting flags by bootstrap nav tab <ul> tags
		html = "<ul class='nav nav-tabs'>\n"
		text = re.sub(self.starttabsre, html, text)

	#Replacing all proper starting flags by bootstrap tab content <div> tags
		html = "<div class='tab-content'>\n"
		text = re.sub(self.startcontentsre, html, text)

	#Replacing all nav tab declarations by bootstrap <li><a> tags
		text = re.sub(self.tabkeydeclre, self.tabkeydeclrepl, text)
		text = re.sub(self.activetabkeydeclre,
			self.activetabkeydeclrepl, text)

	#Replacing all tab pane declarations by bootstrap <div> tags
		text = re.sub(self.tabcontentdeclre, 
			self.tabcontentdeclrepl, text)
		text = re.sub(self.activetabcontentdeclre,
			self.activetabcontentdeclrepl, text)

	#Replacing all proper ending flags by bootstrap </ul> tags
		html = "</ul>\n"
		text = re.sub(self.endtabsre, html, text)

	#Replacing all proper ending flags by bootstrap </div> tags
		text = re.sub(self.endcontentsre, 
			self.endingcontentsrepl, text)

		return text

class Bootstrap_Markdown_Extension(Extension):

	def extendMarkdown(self, md, md_globals):
		md.preprocessors.add('tabbed_nav', TabbedNavPre(), "_begin")
		md.postprocessors.add('tabbed_nav', TabbedNavPost(), "_begin")
		md.parser.blockprocessors.add('tabbed_nav',
			TabbedNavBlockProcessor(), "_begin")

def makeExtension(**kwargs):
	return Bootstrap_Markdown_Extension(**kwargs)
