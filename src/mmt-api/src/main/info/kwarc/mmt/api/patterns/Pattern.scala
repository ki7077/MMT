package info.kwarc.mmt.api.patterns

import info.kwarc.mmt.api._
import modules._
import objects._
import notations._
import symbols._
import utils._
import checking._

/** patterns are derived declarations that are syntactically parametric theories */ 
class PatternFeature extends StructuralFeature(Pattern.feature) with ParametricTheoryLike {

  def checkInContext(prev : Context, dv: VarDecl): Unit = {}
  def elaborateInContext(prev: Context, dv: VarDecl): Context = Context.empty

   def elaborate(parent: DeclaredModule, dd: DerivedDeclaration) = new Elaboration {
     def domain = Nil
     def getO(n: LocalName) = None
   }
}

object Pattern {
  val feature = "pattern"
  
  val Type = ParametricTheoryLike.Type(classOf[PatternFeature])
  
  def apply(home: Term, name : LocalName, params: Context, body : Context, notC: NotationContainer) = {
    val tp = Type(params)
    val dd = new DerivedDeclaration(home, name, "pattern", TermContainer(tp), notC)
    body.mapVarDecls {case (con, vd) =>
      val c = vd.toConstant(home.toMPath, con)
      dd.module.add(c)
    }
    dd
  }
  
  def unapply(dd: DerivedDeclaration): Option[(Term, LocalName, Context, DeclaredTheory)] = {
    if (dd.feature != feature) return None
    val pars = Type.getParameters(dd)
    val thy = dd.module match {
      case thy: DeclaredTheory => thy
      case _ => throw ImplementationError("pattern must contain theory")
    }
    Some((dd.home,dd.name, pars, thy)) 
  }
  
  /** pre: d.feature == "pattern" */
  def getParameters(d: DerivedDeclaration) = {
     d.getComponent(ParamsComponent).flatMap {
       case cc: ContextContainer => cc.get
       case _ => None
     } getOrElse Context.empty
  }
}
