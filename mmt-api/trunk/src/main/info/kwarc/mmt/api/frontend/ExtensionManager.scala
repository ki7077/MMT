package info.kwarc.mmt.api.frontend

import info.kwarc.mmt.api._
import backend._
import presentation._
import libraries._
import archives.BuildTarget
import parser._
import utils._
import web._

trait Extension extends Logger {
   protected var controller : Controller = null
   protected var report : Report = null
   
   /** the prefix used to identify this extension for logging, by default the qualified class name */
   def logPrefix = getClass.toString
   
   /** a custom error class for this extension */
   case class LocalError(s: String) extends ExtensionError(logPrefix, s)
   
   /** initialization (empty by default) */
   def init(controller: Controller, args: List[String]) {
      this.controller = controller
      report = controller.report
   }
   /** termination (empty by default)
    *
    * Extensions may create persistent data structures and processes,
    * but they must clean up after themselves in this method
    */
   def destroy {}
}

/** An ExtensionManager maintains all language-specific extensions of MMT.
 *  This includes Compilers, QueryTransformers, and Foundations.
 *  They are provided as plugins and registered via their qualified class name, which is instantiated by reflection.
 */
class ExtensionManager(controller: Controller) extends Logger {
   
   private[frontend] var foundations   : List[Foundation]   = Nil
   private[frontend] var targets       : List[BuildTarget]  = Nil
   private[frontend] var querytransformers : List[QueryTransformer] = Nil
   private[frontend] var roleHandlers  : List[RoleHandler]  = Nil
   private[frontend] var presenters    : List[Presenter]    = Nil
   private[frontend] var serverPlugins : List[ServerPlugin] = Nil
   private[frontend] var loadedPlugins : List[Plugin]       = Nil
   private[frontend] var parserExtensions : List[ParserExtension] = Nil
   var lexerExtensions : List[LexerExtension] = Nil

   private var mws : Option[URI] = None

   val report = controller.report
   val logPrefix = "extman"

   val ruleStore = new objects.RuleStore
   val pragmaticStore = new pragmatics.PragmaticStore
   
   def addDefaultExtensions {
      targets    ::= new MMTCompiler
      targets    ::= new archives.Index
      presenters ::= TextPresenter
      presenters ::= OMDocPresenter
      presenters ::= controller.presenter
      parserExtensions ::= parser.MetadataParser
      parserExtensions ::= parser.CommentIgnorer
      lexerExtensions ::= GenericEscapeHandler
      lexerExtensions ::= QuoteHandler
      lexerExtensions ::= new PrefixEscapeHandler('\\')
      lexerExtensions ::= new NumberLiteralHandler(true)
      // initialize all extensions
      getAll.foreach(_.init(controller, Nil))
   }

   /** adds an Importer and initializes it */
   def addExtension(cls: String, args: List[String]) {
       log("adding extension " + cls)
       val clsJ = java.lang.Class.forName(cls)
       val ext = try {
          val Ext = clsJ.asInstanceOf[java.lang.Class[Extension]]
          Ext.newInstance
       } catch {
          case e : java.lang.Exception => throw RegistrationError("error while trying to instantiate class " + cls).setCausedBy(e) 
       }
       if (ext.isInstanceOf[Plugin]) {
          log("  ... as plugin")
          val pl = ext.asInstanceOf[Plugin]
          loadedPlugins ::= pl
          pl.dependencies foreach {d => if (! loadedPlugins.exists(_.getClass == java.lang.Class.forName(d))) addExtension(d, Nil)}
       }
       ext.init(controller, args)
       if (ext.isInstanceOf[Foundation]) {
          log("  ... as foundation")
          foundations ::= ext.asInstanceOf[Foundation]
       }
       if (ext.isInstanceOf[RoleHandler]) {
          log("  ... as role handler")
          roleHandlers ::= ext.asInstanceOf[RoleHandler]
       }
       if (ext.isInstanceOf[BuildTarget]) {
          log("  ... as compiler")
          targets ::= ext.asInstanceOf[BuildTarget]
       }
       if (ext.isInstanceOf[QueryTransformer]) {
          log("  ... as query transformer")
          querytransformers ::= ext.asInstanceOf[QueryTransformer]
       }
       if (ext.isInstanceOf[Presenter]) {
          log("  ... as presenter")
          presenters ::= ext.asInstanceOf[Presenter]
       }
       if (ext.isInstanceOf[ParserExtension]) {
          log("  ... as parser extension")
          parserExtensions ::= ext.asInstanceOf[ParserExtension]
       }
       if (ext.isInstanceOf[ServerPlugin]) {
          log("  ... as server plugin")
          serverPlugins ::= ext.asInstanceOf[ServerPlugin]
       }
   }

   /** retrieves an applicable Compiler */
   def getTarget(src: String) : Option[BuildTarget] = targets.find(_.isApplicable(src))
   /** retrieves an applicable query transformer */
   def getQueryTransformer(src: String) : Option[QueryTransformer] = querytransformers.find(_.isApplicable(src))
   /** retrieves an applicable role handler */
   def getRoleHandler(role: String) : List[RoleHandler] = roleHandlers.filter(_.isApplicable(role))
   /** retrieves an applicable Presenter */
   def getPresenter(format: String) : Option[Presenter] = presenters.find(_.isApplicable(format))
   /** retrieves an applicable server plugin */
   def getServerPlugin(cont : String) : Option[ServerPlugin] = serverPlugins.find(_.isApplicable(cont))
   /** retrieves an applicable parser extension */
   def getParserExtension(se: StructuralElement, keyword: String) : Option[ParserExtension] = parserExtensions find {_.isApplicable(se, keyword)}
   /** retrieves an applicable Foundation */
   def getFoundation(p: MPath) : Option[Foundation] = foundations find {_.foundTheory == p}

   /** sets the URL of the MathWebSearch backend */
   def setMWS(uri: URI) {mws = Some(uri)}
   def getMWS : Option[URI] = mws
   
   /** retrieves all registered extensions */
   private def getAll = foundations:::targets:::querytransformers:::roleHandlers:::presenters:::serverPlugins:::parserExtensions:::loadedPlugins
   
   def stringDescription = {
      def mkL(label: String, es: List[Extension]) =
         if (es.isEmpty) "" else label + "\n" + es.map("  " + _.toString + "\n").mkString("") + "\n\n"
      mkL("foundations", foundations) +
      mkL("build targets", targets) +
      mkL("querytransformers", querytransformers) +
      mkL("roleHandlers", roleHandlers) +
      mkL("presenters", presenters) +
      mkL("serverPlugins", serverPlugins) +
      mkL("parserExtensions", parserExtensions)
      mkL("plugins", loadedPlugins) +
      "rules\n" + ruleStore.stringDescription 
   }
   
   def cleanup {
      getAll.foreach(_.destroy)
      foundations = Nil
      targets = Nil
      querytransformers = Nil
      roleHandlers = Nil
      presenters = Nil
      serverPlugins = Nil
      loadedPlugins = Nil
      parserExtensions = Nil
   }
}