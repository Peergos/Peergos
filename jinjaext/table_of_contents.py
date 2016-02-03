from xml.etree import ElementTree
from string import punctuation

class TableOfContents:

	hooks_taken = []

	@staticmethod
	def parse_hook_from_text(text):

		hook = text.strip().replace(" ", "_").lower()
		hook = hook.replace("/:", ":").replace("/", ":")
		hook = hook.replace(":", "_")

		for token in punctuation.replace("_", ""):
			hook = hook.replace(token, "")

		if ( hook.startswith("_") ):
			hook = hook[1:]
		if ( hook.endswith("_") ):
			hook = hook[:-1]

		return hook

	@staticmethod
	def produce_new_hook(text):

		hook_base = TableOfContents.parse_hook_from_text(text)
		hook = hook_base + "_"
		hook_id = 2
		while (hook in TableOfContents.hooks_taken):

			hook = hook_base + "_" + str(hook_id)
			hook_id += 1

		TableOfContents.hooks_taken.append(hook)
		return hook

	@staticmethod
	def consume_existing_hook(text):

		hook = TableOfContents.parse_hook_from_text(text)
		hook += "_"
		stub_hooks = map(lambda x: x[:x.rfind("_")], TableOfContents.hooks_taken)

		if hook[:hook.rfind("_")] in stub_hooks:

			hook_at = stub_hooks.index(hook[:hook.rfind("_")])
                        hook = TableOfContents.hooks_taken[hook_at]

		TableOfContents.hooks_taken.remove(hook)
		return hook

	@staticmethod
	def extractTableOfContentsInfo(content, active_page):

		hooks_taken = []

		try:
			root = ElementTree.fromstring("<root>"+content+"</root>")
		except ElementTree.ParseError as e:
			print "Error while extracting ToC from content"
			print "Could not parse page: %s" % active_page
			print "The problem: " + str(e)
			return None
		except Exception as e:
			print "Error while extracting ToC from content"
			print "Something unexpected happened while parsing page: %s" % active_page
			print "The problem: " + str(e)
			return None

		toc_info = []

		def process(element):

			if ( ( element.tag == 'h1'
			  or element.tag == 'h2'
                          or element.tag == 'h3' )
                          and element.attrib.get("data-hidden-from-toc", "false") != "true" ):
				toc_info.append({
					'tag' : element.tag,
					'text' : element.text,
					'hook' : TableOfContents.produce_new_hook(element.text)
				})

			for child in element:
				process(child)

		process(root)
		return toc_info

	@staticmethod
	def createTableOfContents(toc_info):

		if ( toc_info == None ):
			return ""

		if ( len(toc_info) == 0 ):
			toc_html = ""
			return toc_html
		elif ( len(toc_info) == 1 ):
			toc_html = "<ul class='nav bs-docs-sidenav'><li class='active'><a href='#%s' data-scroll data-url>%s</a></li></ul>"
			toc_html %= (toc_info[0]['hook'], toc_info[0]['text'])
			return toc_html
		else:
			a_html = "<a href='#%s' data-scroll data-url>%s</a>"
			toc_html = "<ul class='nav bs-docs-sidenav'>"
			toc_html += "<li class='active'>"
			toc_html += a_html % (toc_info[0]['hook'], toc_info[0]['text'])
			last_tag = toc_info[0]['tag']
			for i in range(1,len(toc_info)):
				if ( toc_info[i]['tag'] == 'h1' ):
					if ( last_tag == 'h2' ):
						toc_html += "</ul>"
					toc_html += "</li>"
					toc_html += "<li>"
					toc_html += a_html % (toc_info[i]['hook'], toc_info[i]['text'])
				elif ( toc_info[i]['tag'] == 'h2' ):
					if ( last_tag == 'h1' ):
						toc_html += "<ul class='nav'>"
                                        elif ( last_tag == 'h2' ):
                                                toc_html += "</li>"
                                        elif ( last_tag == 'h3' ):
                                                toc_html += "</ul>"
					toc_html += "<li>"
					toc_html += a_html % (toc_info[i]['hook'], toc_info[i]['text'])
                                elif ( toc_info[i]['tag'] == 'h3' ):
                                        if ( last_tag == 'h2' ):
                                                toc_html += "<ul class='nav'>"
                                        toc_html += "<li>"
					toc_html += a_html % (toc_info[i]['hook'], toc_info[i]['text'])
                                        toc_html += "</li>"

				last_tag = toc_info[i]['tag']

                        if ( last_tag == 'h1' ):
                                toc_html += "</li>"
			toc_html += "</ul>"
			return toc_html

	@staticmethod
	def addTableOfContentsHooks(content, toc_info, active_page):

		if ( toc_info == None ):
			return content

		try:
			root = ElementTree.fromstring("<root>"+content+"</root>")
		except ElementTree.ParseError as e:
			print "Error while adding ToC hooks to content"
			print "Could not parse page: %s" % active_page
			print "The problem: " + str(e)
			return content
		except Exception as e:
			print "Error while adding ToC hooks to content"
			print "Something unexpected happened while parsing page: %s" % active_page
			print "The problem: " + str(e)
			return content

		def process(element):

			if ( ( element.tag == 'h1'
			  or element.tag == 'h2'
                          or element.tag == 'h3' )
                          and element.attrib.get("data-hidden-from-toc", "false") != "true" ):
				element.attrib["id"] = TableOfContents.consume_existing_hook(element.text)

			for child in element:
				process(child)

		process(root)

		content = ElementTree.tostring(root, method="html")
		content = content.replace("<root>", "").replace("</root>", "")
		return content
