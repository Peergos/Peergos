from markdown.blockprocessors import BlockProcessor
from markdown.util import etree
from markdown.util import AtomicString
from markdown.extensions import Extension
import re

class SequenceDiagBlockProcessor(BlockProcessor):
	"""
	*Bootstrap Sequence diagrams block processor*.

	Necessary to avoid having Markdown surround the
	JS-Sequence-Diagrams Markdown markups with undesired HTML <p> tags.

	Each block of JSSDM markup is then surrounded by 
	<div id='js_sequence_diagram'> tags which are used by
	js-sequence-diagrams.js to generate the sequence diagrams.
	"""

	def __init__(self):
		self.titre = re.compile("^.* *title *: *.* *.*$", 
				flags = re.DOTALL | re.IGNORECASE)
		self.parre = re.compile("^.* *participant *.* *.*$",
				flags = re.DOTALL | re.IGNORECASE)
		self.lefre = re.compile("^.* *note *left of *: *.* *.*$",
				flags = re.DOTALL | re.IGNORECASE)
		self.rigre = re.compile("^.* *note *right of *: *.* *.*$",
				flags = re.DOTALL | re.IGNORECASE)
		self.overe = re.compile("^.* *note *over *: *.* *.*$",
				flags = re.DOTALL | re.IGNORECASE)
		self.actre = re.compile("^.* *--?>>? *.* *: *.* *.*$",
				flags = re.DOTALL | re.IGNORECASE)

	def test(self, parent, block):

		veredict = (  self.titre.search(block) != None 
			   or self.parre.search(block) != None
			   or self.lefre.search(block) != None
			   or self.rigre.search(block) != None
			   or self.overe.search(block) != None
			   or self.actre.search(block) != None )

		return veredict

	def run(self, parent, blocks):

		sequence_diag = etree.SubElement(parent, "div")
		sequence_diag.attrib['class'] = "js_sequence_diagram"
		sequence_diag.text = AtomicString(blocks[0])
		blocks.pop(0)

class Bootstrap_Markdown_Extension(Extension):

	def extendMarkdown(self, md, md_globals):
		md.parser.blockprocessors.add('sequence_diag',
			SequenceDiagBlockProcessor(), "_begin")

def makeExtension(**kwargs):
	return Bootstrap_Markdown_Extension(**kwargs)
