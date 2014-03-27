package info.kwarc.mmt.latex
import info.kwarc.mmt.api._
import utils._
import info.kwarc.mmt.api.web._
import objects._
import parser._
import symbols._
import presentation._
import frontend._
import scala.collection._
import scala.concurrent._
import tiscaf._

case class LatexError(val text : String) extends Error(text)

class LatexState(val dpath : DPath, controller : Controller) {
  
 
  val dictionary = new mutable.HashMap[LocalName, String] // ?needed
  
  val parser = new LatexStructureParser(this, controller)        

  def context = varContexts match {
    case Nil => Context()
    case hd :: Nil => hd
    case l => l.reduceLeft((x,y) => x ++ y)
  }
  
  var varContexts : List[Context] = Nil
  def addContext(con : Context = Context()) = varContexts ::= con
  def addVar(v : VarDecl) = varContexts = (varContexts.head ++ v) :: varContexts.tail
  
  def clearContext() = varContexts = varContexts.tail
  
  var mod : MPath = null //current module
  
  val seqReader = new SeqBufReader
  val parserState = new ParserState(new Reader(seqReader), dpath)
  
  var notationQueue = new mutable.Queue[String]
  
  def setParserState(text : String) {
    seqReader.appendLine(text)
  }
  
  val varIds = new mutable.HashMap[LocalName,Int]
  
  var currentId = 0
  def getFreshId = {
    currentId += 1
    currentId
  }
  
  def addVar(name : LocalName) = {
    varIds(name) = getFreshId
    resolveVar(name)
  }
  
  def resolveVar(name : LocalName) = varIds(name)
  
  def waitDone() {  
    
    while(!seqReader.isDone) {
      Thread.sleep(10)
    } 
    Thread.sleep(100)
  }
}
                                                    //with Presenter
class LatexPresenter extends ServerExtension("latex") with Logger {
   var currentJob : String = ""
   
   override val logPrefix = "latexPlugin"    
     
   val states = new mutable.HashMap[String, LatexState] 
   
   def apply(c : StructuralElement, rh : RenderingHandler) {
      rh(c.toString)
   }
   
   def apply(o : Obj, rh : RenderingHandler) : Unit = apply(o, rh, states(currentJob).dictionary)
   
   def apply(o: Obj, rh: RenderingHandler, con : Map[LocalName,String]) {
      o match {
         case OMS(p) => rh(s"\\${p.last}{${p.toPath}}")
         case OMA(OMID(p), args) =>
            rh(s"\\${p.last}{${p.toPath}}")
            args foreach {a =>
              rh("{")
              apply(a, rh)
              rh("}")
            }
         case OMBIND(OMID(p), Context(VarDecl(v, tpOpt, _)), scope) =>
         case OMV(v) => s"\\varref{${v.toPath}{${con(v)}}"
      }
   }
   
   private def toLatex(tm : Term, scope : MPath, state : LatexState) : String = {
     val (notations, extensions) = AbstractObjectParser.getNotations(controller, OMMOD(scope))
     val notationsHash = notations.map(n => (n.name, n)).toMap
     val contentRep = toLatex(tm)(new immutable.HashMap[GlobalName,TextNotation], state, None)
     val pres = toLatex(tm)(notationsHash, state, None)
     s"\\mmtTooltip{$pres}{$contentRep}"
   }

   private def loadVars(tm : Term)(implicit state : LatexState) : Unit = {
     controller.pragmatic.pragmaticHead(tm) match {
       case OMS(p) => 
       case OMV(v) =>
       case OMA(f, args) => 
         loadVars(f)
         args.map(loadVars)
       case OMBIND(OMS(p), con, body) => con.variables.map(loadVar)
       case OMBIND(OMA(OMS(p), args), con, body) => con.variables.map(loadVar)
      case t => throw LatexError("Invalid term in load Vars : " + t.toString)
     }
   }
   
   private def loadVar(v : VarDecl)(implicit state : LatexState) = {
     state.addVar(v.name)
   }
   
   
   private def wrapInferredArgs(s : String, args : List[Term])
     (implicit notations : Map[GlobalName,TextNotation], state : LatexState, parentNot : Option[TextNotation]) : String = {
     val inferred = args filter {t => SourceRef.get(t) match {
       case None => true //inferred
       case _ => false
     }}          
     val infS = inferred.map(toLatex).mkString(" ")
     s"\\mmtinferredargs{$s}{$infS}"
     
   }
   
   private def toLatex(tm : Term)(implicit notations : Map[GlobalName,TextNotation], state : LatexState, parentNot : Option[TextNotation]) : String = {
     controller.pragmatic.pragmaticHead(tm) match {
       case OMS(p) => "\\" + Utils.latexName(p)
       case OMA(OMS(p), args) => notations.get(p) match {
         case Some(not) => 
           val markers = not.parsingMarkers
           val sqargs = markers collect {
             case SeqArg(n,sep) => (n,sep) 
           }
           sqargs match {
             case Nil => 
               val str = toLatex(OMS(p)) + args.map(t => toLatex(t)(notations, state, Some(not))).mkString("{","}{","}") 
               parentNot match {
                 case Some(n) if n == not => "(" + str  + ")"
                 case _ => str
               }
             case hd :: Nil => //one seq arg, must detect corresponding content arguments
               val pos = hd._1
               val sep = hd._2
               val arity = not.arity.length
               val (begin,rest) = args.splitAt(pos - 1)
               val (seq, end) = rest.splitAt(args.length - arity)
               val beginS = begin.map(toLatex).mkString("{","}{","}")
               val seqS = s"{\\mmtseq{${seq.map(toLatex).mkString(sep.s)}}}"               
               val endS = begin.map(toLatex).mkString("{","}{","}")
               toLatex(OMS(p)) + beginS + seqS + endS
              case _ => throw LatexError("Multiple sequence arguments in the same notation not supported yet")
           }   
         case None => "(" + p.last + "\\;" + args.map(toLatex).mkString("\\:") + ")"
     }
       case OMBIND(b, con, body) => controller.pragmatic.pragmaticHead(b) match {
       //case args = Nil with OMS(p) 
       case OMA(OMS(p), args) => notations.get(p) match { 
         case Some(not) =>
           val argsS = args match {
             case Nil => ""
             case _ => args.map(toLatex).mkString("{","}{","}")
           }
           not.parsingMarkers find {
             case Var(_,_,Some(sep)) => true
             case _ => false
           } match {
             case Some(Var(_,_,Some(sep))) => 
               val vars = con.variables.map(vToLatex).mkString(sep.s)
               s"${toLatex(OMS(p))}$argsS{$vars}{${toLatex(body)}}"
             case _ => 
               assert(con.variables.length == 1, "expected one variable argument")
               s"${toLatex(OMS(p))}$argsS{${vToLatex(con.variables.head)}}{${toLatex(body)}}"
           }
         case None => 
           assert(con.variables.length == 1, "expected one variable argument")
           val v = con.variables.head
           s"(${p.last}\\;${vToLatex(v)}\\, . \\, ${toLatex(body)}"
        }
       }
       case OMA(OMV(v), args) => s"${toLatex(OMV(v))} ${args.map(toLatex).mkString("{ }")}"
       case OMV(n) => try {
         s"\\mmtboundvarref{$n}{${state.resolveVar(n)}}"
       } catch {
         case e : Exception => n.toString
       }
       case OMA(f, args) => s"(${toLatex(f)}\\;${args.map(toLatex).mkString("\\:")})"
       case t => throw LatexError("Term cannot be converted to latex : " + t.toString)
     }
   }
   
   def vToLatex(v : VarDecl)(implicit notations : Map[GlobalName,TextNotation], state : LatexState, parentNot : Option[TextNotation]) : String = {
     val vname = try {
       val id = state.resolveVar(v.name)
       s"\\mmtboundvar{${v.name}}{$id}"
     } catch {
         case e : Exception => v.name.toString
     }
     v match {
       case VarDecl(a, Some(tp), None) =>
         SourceRef.get(tp) match {
           case None => //inferred
             s"\\mmtinferredtype{$vname}{${tp.toString}}"
           case _ => //already typed
             s"\\mmttype{$vname}{${toLatex(tp)}}"
         }
       case _ => vname
     }
   }
   
   
   /** Server */   
   def isApplicable(uriComp : List[String]) = {
     uriComp.head == ":latex"
   }
   
   def apply(uriComps: List[String], query: String, body : Body): HLet = {
     try {
       uriComps match {
         case "postdecl" :: _ => PostDeclResponse
         case "getobjpres" :: _ => GetObjPresResponse
         case "context" :: _ => ContextResponse
         case _ => errorResponse("Invalid request : " + uriComps.mkString("/"))
       }
     } catch {
       case e : LatexError => errorResponse(e.text)
       case e : Error => errorResponse(e.longMsg)
       case e : Exception => errorResponse("Exception occured : " + e.getMessage())
     }
   }
 
   private def ContextResponse : HLet = new HLet {
    def aact(tk : HTalk)(implicit ec : ExecutionContext) = try {
    	val jobname = tk.req.header("jobname").getOrElse(throw LatexError("found no jobname for request"))
    	val request = tk.req.header("request").getOrElse(throw LatexError("found no context update request"))
    	val text = bodyAsString(tk)
    	var resp = ""
    	if (!states.isDefinedAt(jobname)) {
    	  throw LatexError(s"given jobname: $jobname not active")
    	} else {
    	  val state = states(jobname)
    	  request match {
    	    case "clear" => state.clearContext()
    	    case "new" => state.addContext()
    	    case "addvar" =>  text.split(":").toList match {
    	      case name :: tpS :: Nil => 
    	        val vname = LocalName(name)
    	        val id = state.addVar(vname)
    	        resp = s"\\mmtboundvar{$vname}{$id}"
    	        log("currentContext :" + state.context)
    	        val pu = new parser.ParsingUnit(parser.SourceRef(state.mod.doc.uri,SourceRegion.ofString(tpS)), OMMOD(state.mod), state.context, tpS)
    	        val tp = controller.termParser(pu, err => log(err))
    	        val v = VarDecl(LocalName(name), Some(tp), None)
    	        state.addVar(v)
    	      case _ =>  throw LatexError(s"invalid var: $text")
    	    }    	    
    	  }

    	  Server.TextResponse(resp).aact(tk)
    	}		
     } catch {
       case e : LatexError => errorResponse(e.text).aact(tk)
       case e : Error => errorResponse(e.shortMsg).aact(tk)
       case e : Exception => errorResponse("Exception occured : " + e.getMessage()).aact(tk)
     }
   }
      
   private def PostDeclResponse : HLet = new HLet {
    def aact(tk : HTalk)(implicit ec : ExecutionContext) = try {
      val text = bodyAsString(tk)
      val jobname = tk.req.header("jobname").getOrElse(throw LatexError("found no jobname for request"))
      val dpathS =  tk.req.header("dpath").getOrElse(throw LatexError("found no dpath"))
      val notPresS = tk.req.header("pres").getOrElse("")
      
      
      val dpath = DPath(URI(dpathS)) 
      log("received : " + jobname + " and " +  dpathS + " : " + text)
      if (!states.isDefinedAt(jobname)) { 
        val state = new LatexState(dpath, controller)
        state.setParserState(text)
        if(!notPresS.isEmpty) 
          state.notationQueue.enqueue(notPresS) 
        states(jobname) = state        
        val thread = new Thread(new Runnable {          
          def run() {
            state.parser(state.parserState, dpath)
          }
        })
        thread.start        
        state.waitDone
        val resp = state.parser.addedMacros.mkString("\n")
        state.parser.addedMacros = Nil
        Server.TextResponse(resp).aact(tk)	
      } else {
    	val ltxState = states(jobname)
    	if(!notPresS.isEmpty) 
          ltxState.notationQueue.enqueue(notPresS)
        ltxState.setParserState(text)       
        ltxState.waitDone
        val resp = ltxState.parser.addedMacros.mkString("\n")
        log("RESP :" + resp + ": END RESP")
        ltxState.parser.addedMacros = Nil
        Server.TextResponse(resp).aact(tk)
      }
    } catch {
       case e : LatexError => errorResponse(e.text).aact(tk)
       case e : Error => errorResponse(e.shortMsg).aact(tk)
       case e : Exception => errorResponse("Exception occured : " + e.getMessage()).aact(tk)
     }
  }
  
   
  private def GetObjPresResponse : HLet = new HLet {
    def aact(tk : HTalk)(implicit ec : ExecutionContext) = try {
      val text = bodyAsString(tk)
      val jobname = tk.req.header("jobname").getOrElse(throw LatexError("found no jobname in request"))
      val state = states(jobname)
      val home = tk.req.header("home").map(Path.parse).getOrElse {        
        state.mod
      }
      
      log("received get : " + text)
      
      home match { 
        case mod : MPath => 
          val pu = new parser.ParsingUnit(parser.SourceRef(mod.doc.uri,SourceRegion.ofString(text)), objects.OMMOD(mod), state.context, text)
          val tm = controller.termParser(pu, err => log(err))
          val (unknowns,tmU) = parser.AbstractObjectParser.splitOffUnknowns(tm)
          val etp = LocalName("expected_type")
          val oc = new Solver(controller, OMMOD(mod), unknowns ++ VarDecl(etp, None, None))
          val j = Typing(Stack(mod, state.context), tmU, OMV(etp))
          oc(j)
          oc.getSolution match {
            case Some(sub) =>
              val tmR = tmU ^ sub
              val tmRU = controller.uom.simplify(tmR, OMMOD(mod))
//              val tmRS = Utils.rreduce(tmRU)
              log("Orginal term : " + tmU.toString)
              log("Reduced Term : " + tmRU.toString)
              loadVars(tmRU)(state)
              val resp =  toLatex(tmRU, mod, state)
              log("Sending ResponseRS" + resp)
              Server.TextResponse(resp).aact(tk)
            case None =>
              loadVars(tmU)(state)
              val resp = toLatex(tmU,mod, state)
              log("Sending ResponseU" + resp)

              // throw LatexError("type reconstruction failed")
              Server.TextResponse(resp).aact(tk) //until things are stable sending non-reconstructed term 
          }
        case _ => throw LatexError("support for non-module paths not implemented yet")
      } 
    } catch {
       case e : LatexError => errorResponse(e.text).aact(tk)
       case e : Error => errorResponse(e.shortMsg).aact(tk)
       case e : Exception => errorResponse("Exception occured : " + e.getMessage()).aact(tk)
     }
  }
  
  private def sanitizeInput(text : String) : String = {
    text.replaceAllLiterally("\\ldots", "…")
  }
  
  private def bodyAsString(tk: HTalk): String = {
    val bodyArray: Array[Byte] = tk.req.octets.getOrElse(throw LatexError("no body found"))
    val text = new String(bodyArray, "UTF-8")
    sanitizeInput(text)
  }
  
  private def errorResponse(text : String) : HLet = {
    Server.TextResponse(s"\\mmtError{$text}")
  }

  
}


class LatexStructureParser(ltxState : LatexState, controller : Controller) extends StructureAndObjectParser(controller) {
   var addedMacros : List[String] = Nil
   def getSourceFile(state : ParserState) : String = {
     val fs = state.container.uri.pathAsString
     val pos = fs.lastIndexOf('.')
     "run:./" + fs.substring(0,pos) + ".pdf"
   }
   
   override def seCont(se: StructuralElement)(implicit state: ParserState) {
      log(se.toString)
      SourceRef.update(se, SourceRef(state.container.uri,currentSourceRegion))
      val source = getSourceFile(state)
      se match {
        case c : Constant => 
          if (!ltxState.notationQueue.isEmpty) {
            val defaultNot = TextNotation.parse(ltxState.notationQueue.dequeue(), c.path)
            addedMacros ::= makeMacro(defaultNot, source)
          } else {//no explicit presentation notation
            c.not match {
              case None => 
                val m1 = s"\\gdef\\${Utils.latexName(c.path)}{${wrapLink(c.name.toString,c.path,source)}}"
                val m2 = s"\\hyperdef{mmt}{${c.path}}{}"
                addedMacros ::= (m1 + "\n" + m2)
              case Some(notation) => addedMacros ::= makeMacro(notation, source) 
            }
          }
        case m : modules.Module => ltxState.mod = m.path
        case _ => 
      }      
      controller.add(se)
   }
   
     private def makeMacro(marker : Marker, mmtName : GlobalName, source : String) : String = marker match {
     case Delim(str) => wrapLink(str, mmtName, source)
     case Arg(i) => s"#${i.abs}"
     case SeqArg(i,d) => s"\\mmtseq{#${i.abs}}{${makeMacro(d, mmtName, source)}}"
     case Var(i, t, sO) => s"#${i.abs}" //TODO 
     case p : PlaceholderDelimiter => throw LatexError("Marker PlaceholderDelimiter shouldn't occur in Notations")
   }
    
   def wrapLink(del : String, path : GlobalName, source : String) : String = del match {
     case "_" => "_"
     case "^" => "^"
     case "{" => "{"
     case "}" => "}"
     case str => s"\\mmtlink[$source]{$str}{${path.toPath}}"     
   } 
     
     
   def makeMacro(not : TextNotation, source : String) : String = {
     val mmtName = not.name
     val markers = not.parsingMarkers
     val body = markers.map(makeMacro(_, mmtName, source)).mkString(" ")
     val label = s"\\hyperdef{mmt}{${mmtName.toPath}}{}"
     val args = (1 to not.arity.length).map(i => s"#$i").mkString("")
     s"\\gdef\\${Utils.latexName(mmtName)}$args{$body}\n $label\n"     
   }
   
}

import info.kwarc.mmt.lf._

object Utils {
   private val sep = ""
   def latexName(mmtName : GlobalName) : String = "mmt" + sep + mmtName.module.toMPath.last + sep + mmtName.last
//   def reduce(t: Term) : Term = {
//     t match {
//       case OMA(OMS(Apply.path), OMBIND(OMS(Lambda.path), con, body) :: OMV(n) :: rest) => 
//         val names = con.variables.map(_.name)
//         if (names.head == n) {
//           rest match {
//             case Nil => reduce(body)
//             case _ => reduce(OMA(OMS(Apply.path), body :: rest)) 
//           }
//         } else 
//           OMA(OMS(Apply.path), OMBIND(OMS(Lambda.path), reduceCon(con), reduce(body)) :: OMV(n) :: Nil)
// //      case OMA(OMS(Apply.path), hd :: rest) => reduce(OMA(hd, rest))  
//       case OMA(f, args) => 
//         OMA(reduce(f), args map reduce).from(t)
//       case OMBIND(b,con, s) => OMBIND(reduce(b), reduceCon(con), reduce(s)).from(t)
//       case _ => t
//     }
//   }
//   
//   def reduceCon(con : Context) : Context = {
//     val nvars = con.variables map {
//       case VarDecl(n, Some(tp), df, _*) => VarDecl(n, Some(reduce(tp)), df)
//       case v => v
//     }
//     Context(nvars : _*)
//   }

}

