from markdown.postprocessors import Postprocessor
from markdown.util import etree
from markdown.util import AtomicString
from markdown.extensions import Extension
import re


class FixCodeBlocksPost(Postprocessor):

	def run(self, text):

		text = text.replace("<pre>", "<pre><code>")
		text = text.replace("</pre>", "</code></pre>")

		return text

class Bootstrap_Markdown_Extension(Extension):

	def extendMarkdown(self, md, md_globals):
		md.postprocessors.add('fix_code_blocks', FixCodeBlocksPost(), "_end")

def makeExtension(**kwargs):
	return Bootstrap_Markdown_Extension(**kwargs)
