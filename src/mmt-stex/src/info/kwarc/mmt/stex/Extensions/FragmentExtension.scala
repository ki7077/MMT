package info.kwarc.mmt.stex.Extensions

import info.kwarc.mmt.api.modules.Theory
import info.kwarc.mmt.api.{CPath, ContentPath, DefComponent, GlobalName, MPath, NamespaceMap, Path, StructuralElement, TypeComponent}
import info.kwarc.mmt.api.objects.{OMID, OMS, Obj, Term}
import info.kwarc.mmt.api.ontology.{Binary, CustomBinary, RelationalElement, RelationalExtractor, Unary}
import info.kwarc.mmt.api.parser.SourceRef
import info.kwarc.mmt.api.symbols.{Constant, DerivedDeclaration}
import info.kwarc.mmt.api.utils.{MMTSystem, XMLEscaping}
import info.kwarc.mmt.api.web.{ServerExtension, ServerRequest, ServerResponse}
import info.kwarc.mmt.stex.OMDocHTML
import info.kwarc.mmt.stex.vollki.FullsTeXGraph
import info.kwarc.mmt.stex.xhtml.HTMLParser.ParsingState
import info.kwarc.mmt.stex.xhtml.{HTMLParser, OMDocHTML}

import scala.xml.Elem
//import info.kwarc.mmt.stex.xhtml.{HTMLConstant, HTMLParser, HTMLRule, HTMLTheory, HasLanguage, OMDocHTML}
import info.kwarc.mmt.stex.xhtml.HTMLParser.HTMLNode
import info.kwarc.mmt.stex.{STeX, translations}

import scala.xml.Node

trait FragmentExtension extends STeXExtension {
  def doConstant(c : Constant, preview : Boolean) : Option[HTMLNode] = None
  def doDerivedDecl(node : HTMLNode, c : DerivedDeclaration, preview : Boolean) : Unit = {}
  def doExpression(node : HTMLNode, tm : Term, component : Option[CPath], preview : Boolean) : Unit = {}
}

object FragmentExtension extends STeXExtension {

  override def serverReturn(request: ServerRequest): Option[ServerResponse] = request.path.lastOption match {
    case Some("fragment") =>
      request.query match {
        case "" =>
          Some(ServerResponse("Empty fragment","txt"))
        case ps =>
          val (comp,lang) = ps.split('&') match {
            case Array(a) => (a,None)
            case Array(a,l) if l.startsWith("language=") => (a,if (l.drop(9).isEmpty) None else Some(l.drop(9)))
            case _ => (ps,None)
          }
          val path = Path.parse(comp)
          doFragment(path,lang)
      }
    case Some("symbol") =>
      request.query match {
        case "" =>
          Some(ServerResponse("Empty Document path", "txt"))
        case s =>
          var html = MMTSystem.getResourceAsString("mmt-web/stex/mmt-viewer/index.html")
          html = html.replace("CONTENT_URL_PLACEHOLDER", "/:" + server.pathPrefix + "/declaration?" + s)
          html = html.replace("BASE_URL_PLACEHOLDER", "")
          Some(ServerResponse(html, "text/html"))
      }
    case Some("declaration") =>
      request.query match {
        case "" =>
          Some(ServerResponse("Empty fragment","txt"))
        case ps =>
          val (comp,lang) = ps.split('&') match {
            case Array(a) => (a,None)
            case Array(a,l) if l.startsWith("language=") => (a,if (l.drop(9).isEmpty) None else Some(l.drop(9)))
            case _ => (ps,None)
          }
          val path = Path.parse(comp)
          Some(doDeclaration(path,lang))
      }
    case Some("declheader") =>
      request.query match {
        case "" =>
          Some(ServerResponse("Empty fragment","txt"))
        case ps =>
          val path = Path.parse(ps)
          Some(controller.getO(path) match {
            case Some(c: Constant) =>
              val (doc, body) = server.emptydoc
              body.add(doDeclHeader(c))
              ServerResponse("<body>" + body.toString + "</body>", "text/html")
            case Some(d) =>
              ServerResponse("Not yet implemented: " + d.getClass.toString, "txt")
            case _ =>
              ServerResponse("Declaration not found", "txt")
          })
      }
    case _ => None
  }

  def doDeclHeader(c:Constant) : Elem = {
    <div style="font-size:small">
      <table><tr><td>
        <font size="+2">{" ☞ "}</font><code>{c.path.toString}</code>
      </td><td>{if (controller.extman.get(classOf[ServerExtension]).contains(FullsTeXGraph)) {
        <a href={"/:vollki?path=" + c.parent.toString} target="_blank" style="pointer-events:all;color:blue">{"> Guided Tour"}</a>
      } else <span></span>
        }</td>
      </tr></table><hr/>
      <table>
        {
        OMDocHTML.getMacroName(c) match {
          case None => <tr><td></td><td></td></tr>
          case Some(s) => <tr><td style="padding:3px"><b>TeX Macro:</b></td><td><code>{"\\" + s}</code></td></tr>
        }
        }
        {
        def td[A](a:A) = <td style="border:1px solid;padding:3px">{a}</td>
        OMDocHTML.getNotations(c.path)(controller) match {
          case Nil => <tr><td></td><td></td></tr>
          case ls =>
            <tr><td style="padding-right:3px"><b>Notations:</b></td><td>
              <table>
                <tr>{td("identifier")}{td("notation")}{td("operator notation")}{td("in module")}</tr>
                {ls.map(n =>
                <tr>
                  {td(n._4 match {
                  case "" => "(None)"
                  case s => s
                })
                  }
                  {td(<math xmlns="http://www.w3.org/1998/Math/MathML">{server.htmlpres.doNotation(n._2)}</math>)}
                  {td(n._5 match {
                  case Some(n) => <math xmlns="http://www.w3.org/1998/Math/MathML">{server.htmlpres.doNotation(n)}</math>
                  case None => <span></span>
                })}
                  {td(if (n._1 != c.parent) n._1.toString else "(here)")}
                </tr>
              )}
              </table>
            </td></tr>
        }
        }
        {c.tp match {
        case None => <tr><td></td><td></td></tr>
        case Some(tp) =>
          <tr><td style="padding:3px"><b>Type:</b></td><td>{server.xhtmlPresenter.asXML(tp,Some(c.path $ TypeComponent))}</td></tr>
      }}
        {c.df match {
        case None => <tr><td></td><td></td></tr>
        case Some(df) =>
          <tr><td style="padding:3px"><b>Definiens:</b></td><td>{server.xhtmlPresenter.asXML(df,Some(c.path $ DefComponent))}</td></tr>
      }}
      </table></div>
  }

  def doDeclaration(path : Path,language : Option[String]) : ServerResponse = {
    controller.getO(path) match {
      case Some(c: Constant) =>
        val (doc,body) = server.emptydoc
        body.add(doDeclHeader(c))
        getFragment(path,language) match {
          case Some(htm) =>
            body.add(<hr/>)
            body.add(htm)
          case _ =>
        }
        val docrules = server.extensions.collect {
          case e : DocumentExtension =>
            e.documentRules
        }.flatten
        def doE(e : HTMLNode) : Unit = docrules.foreach(r => r.unapply(e))
        body.iterate(doE)
        ServerResponse("<body>" + body.toString + "</body>","text/html")
      case Some(d) =>
        ServerResponse("Not yet implemented: " + d.getClass.toString,"txt")
      case _ =>
        ServerResponse("Declaration not found","txt")
    }
  }

  def doDeclarationOld(path : Path,language : Option[String]) = {
    path match {
      case mp: MPath =>
        controller.simplifier(mp)
      case gn: GlobalName =>
        controller.simplifier(gn.module)
    }
    controller.getO(path) match {
      case Some(c : Constant) =>
        val (doc,body) = server.emptydoc
        /*val head = doc.get("head")()().head
        head.addBefore(<style>{
          List(
            "/mmt-web/css/bootstrap-jobad/css/bootstrap.less.css",
            "/mmt-web/css/mmt.css",
            "/mmt-web/css/browser.css",
            "/mmt-web/css/JOBAD.css",
            "/mmt-web/css/jquery/jquery-ui.css",
            "/mmt-web/css/incsearch/jstree.css",
            "/mmt-web/css/incsearch/index.css",
            "/mmt-web/css/incsearch/incsearch.css",
            "/mmt-web/css/incsearch/treeview.css"
          ).map(MMTSystem.getResourceAsString).mkString("\n")
          }</style>,head.children.head)
        head.addBefore("""<script type="text/javascript" src="script/jquery/jquery.js"></script>
                         |<script type="text/javascript" src="script/jquery/jquery-ui.js"></script>
                         |<script type="text/javascript" src="script/tree/jquery.hotkeys.js"></script><!-- used by  stree -->
                         |<script type="text/javascript" src="script/tree/jquery.jstree.js"></script>
                         |<script type="text/javascript" src="script/incsearch/incsearch.js"></script> -->
                         |<script type="text/javascript" src="script/incsearch/treeview.js"></script>
                         |<script type="text/javascript" src="script/mmt/mmt-js-api.js"></script>
                         |<script type="text/javascript" src="script/jobad/deps/underscore-min.js"></script>
                         |<script type="text/javascript" src="script/bootstrap2/bootstrap.js"></script>
                         |<script type="text/javascript" src="script/jobad/JOBAD.js"></script>
                         |<script type="text/javascript" src="script/jobad/modules/hovering.js"></script>
                         |<script type="text/javascript" src="script/jobad/modules/interactive-viewing.js"></script>
                         |<script type="text/javascript" src="script/mmt/browser.js"></script>""".stripMargin,head.children.head)

         */
        body.attributes((HTMLParser.ns_html,"style")) = "background-color:white"
        stripMargins(doc)
        val border = body.add(<div style="font-size:small"/>)
        border.add(<b>Symbol </b>)
        border.add("&nbsp;")
        border.add(<a href={"/?"+path} target="_blank" style="pointer-events:all">{XMLEscaping(c.path.toString)}</a>)
        border.add(<br/>)
        if (controller.extman.get(classOf[ServerExtension]).contains(FullsTeXGraph)) {
          border.add(<a href={"/:vollki?path=" + c.parent.toString} target="_blank" style="pointer-events:all;color:blue">{"> Guided Tour"}</a>)
          border.add(<br/>)
        }
        border.add(
          server.htmlpres.asString(c)
          /*
          <table>
            <tr><th>Macro</th><th>Presentation</th><th>Type</th><th></th></tr>
            <tr>
              <td>{c.notC.parsing match {
                case Some(tn) => scala.xml.Text(tn.markers.mkString(""))
                case _ => scala.xml.Text("(None)")
              }}</td>
              <td>{c.notC.presentation match {
                case Some(tn) => scala.xml.Text(tn.markers.mkString(""))
                case _ => scala.xml.Text("(None)")
              }}</td>
              <td>{c.tp match {
                case Some(tpi) => server.xhtmlPresenter.asXML(tpi,Some(c.path $ TypeComponent))
                case _ => scala.xml.Text("(None)")
              }}</td>
            </tr>
          </table>
           */
        )
        getFragment(path,language).foreach(s => border.add(s))

        val docrules = server.extensions.collect {
          case e : DocumentExtension =>
            e.documentRules
        }.flatten
        def doE(e : HTMLNode) : Unit = docrules.foreach(r => r.unapply(e))
        val nbody = doc.get("body")()().head
        nbody.iterate(doE)
        Some(ServerResponse(nbody.toString,"text/html"))
      case _ =>
        Some(ServerResponse("Declaration not found","txt"))
    }
  }

  def getFragment(path : Path,language : Option[String]) = {
    path match {
      case mp: MPath =>
        controller.simplifier(mp)
      case gn: GlobalName =>
        controller.simplifier(gn.module)
    }
    controller.getO(path) match {
      case Some(elem) =>
        val exts = server.extensions.collect {case fe : FragmentExtension => fe}
        elem match {
          case c : Constant =>
            var ret : Option[HTMLNode] = None
            exts.collectFirst{
              case s if {ret = s.doConstant(c,true);ret}.isDefined => ret
            }
            ret match {
              case Some(s) => Some(s)
              case _ =>
                val rules = server.extensions.flatMap(_.rules)
                val state = new ParsingState(controller,rules)
                Some(HTMLParser.apply(getFragmentDefault(c,language))(state))
            }
          case _ =>
            ???
        }
      case _ => None
    }
  }



  def doFragment(path : Path,language : Option[String]) = {
    getFragment(path,language) match {
      case Some(htm) =>
        val (doc,body) = server.emptydoc
        body.attributes((HTMLParser.ns_html,"style")) = "background-color:white"
        stripMargins(doc)
        val border = body.add(<div style="font-size:small"/>)
        border.add(<font size="+2">{" ☞ "}</font>)
        border.add(<code>{path.toString}</code>)
        border.add(<hr/>)
        border.add(htm)
        val docrules = server.extensions.collect {
          case e : DocumentExtension =>
            e.documentRules
        }.flatten
        def doE(e : HTMLNode) : Unit = docrules.foreach(r => r.unapply(e))
        val nbody = doc.get("body")()().head
        nbody.iterate(doE)
        Some(ServerResponse(nbody.toString,"text/html"))
      case None =>
        Some(ServerResponse("Empty fragment","txt"))
    }
  }

  def getFragmentDefault(ce : StructuralElement,language:Option[String]) : String = {
    def text(s : String) = scala.xml.Text(s)
    var ret : List[Path] = Nil
    controller.depstore.query(ce.path,-SymdocRelational.documents)(s => ret ::= s)
    ret.flatMap(controller.getO).headOption match {
      case Some(c : Constant) => c.df match {
        case Some(STeX.symboldoc(_,lang,node)) if language.contains(lang) || lang.isEmpty || (language.isEmpty && lang.contains("en")) => // TODO language
          return node.map(_.toString()).mkString//XMLEscaping.unapply(str)
        case _ =>
      }
      case _ =>
    }
    ce match {
      case c : Constant =>
        val macroname = OMDocHTML.getMacroName(c)
        (<table>
          <tr><td><b>Type</b></td><td>{c.tp.map(server.xhtmlPresenter.asXML(_,Some(c.path $ TypeComponent))).getOrElse(text("None"))}</td></tr>
          {macroname.foreach{name => OMDocHTML.getNotations(c,controller).map{
            case ("",_,_,n,_) =>
              <tr><td>{"\\"+name}</td><td>{n}</td></tr>
            case (f,_,_,n,_) =>
              <tr><td>{"\\"+name+"[" + f + "]"}</td><td>{n}</td></tr>
          }}}
        </table>).toString()
      case _ =>
        ???
    }
  }

  def stripMargins(ltx : HTMLNode) = {
    val body = ltx.get("body")()().head
    body.attributes((HTMLParser.ns_html, "style")) = "margin:0;padding:0;"
    val doc = body.get("div")()("body").head
    doc.attributes((HTMLParser.ns_html, "style")) = "margin:0;padding:0.1em 0.5em 0.5em 0.5em;"
  }

  def termLink(o : Obj, comp : Option[CPath]) = DocumentExtension.makePostButton(
    server.xhtmlPresenter.asXML(o, comp),
    "/:" + server.pathPrefix + "/expression",
    ("openmath",o.toNode.toString().replace("\n","").replace("\n","")),
    ("component",comp.map(_.toString).getOrElse("None"))
  )

}