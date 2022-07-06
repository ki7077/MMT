package info.kwarc.mmt.lf.itp

import info.kwarc.mmt.api.LocalName
import info.kwarc.mmt.api.checking.{CheckingUnit, History, Hole, Solver}
import info.kwarc.mmt.api.objects.{Context, Equality, Free, MatchFail, MatchSuccess, Matcher, OMA, OMATTR, OMBINDC, OMFOREIGN, OMID, OML, OMLIT, OMLITTrait, OMSemiFormal, OMV, PlainSubstitutionApplier, Stack, StatelessTraverser, Sub, Substitution, Term, Traverser, Typing, UnknownOMLIT, VarDecl}
import info.kwarc.mmt.api.proving.itp.Goal
import info.kwarc.mmt.api.proving.itp.ProofUtil.unify
import info.kwarc.mmt.lf.{Arrow, FunType, Lambda, OfType, Pi}

import scala.collection.Map
import scala.collection.mutable.ListBuffer
import scala.collection.{Map, mutable}
import scala.collection.mutable.{ListBuffer, Map => MMap}
/**
  * contains several helper methods used throughout the prover implementation
  */
object ProofUtil {

  /**
    * generates a new hypothesis name, unlike [[Context.pickFresh(...)]] this function avoids backslash
    * @param s the original name upon which a unique variant of it is to be created
    * @param ctx the context in which the new name should be unique
    * @return a new hypothesis name that is unique in the context of `ctx`
    */
  def pickFreshHypName(s : String , ctx : Context) : String = {
    var i : Option[Int]= None
    var res = s
    val names = ctx.variables.map(x => x.name.toString)
    while(names.contains(res)){
      res = s +  i.getOrElse("0")
      i = Some(i.getOrElse(0) + 1)
    }
    res
  }


  /**
    * get all the `LocalName`s of bound vairables in the term `t0`. this function was made early in development when
    * the author was not aware of the [[Traverser]] trait and object
    * TODO: reimplement using the Traverser trait
    * @param t0 the term to be searched
    * @param lss list containing `LocalName`
    * @return
    */
  def getBoundNames(t0 : Term, lss : List[LocalName]): List[LocalName] = {


    def getBoundNamesL(t: Term, ls : List[LocalName]): List[LocalName] = {
      t match {
        case Lambda(nm ,tp , bd) => getBoundNamesL(bd , nm :: ls)
        case Pi(nm , tp , bd) => getBoundNamesL(bd ,  nm :: ls)
        case OMID(path) => if (ls.contains((x : LocalName) => x == path.name)) Nil else List(path.name)
        case OMBINDC(binder, context, scopes) => ???
        case OMA(fun, args) =>getBoundNamesL(fun , ls) ++ args.flatMap(x => getBoundNamesL(x , ls))
        case OMV(name) => if (ls.contains((x : LocalName) => x == name)) Nil else List(name)
        case OMATTR(arg, key, value) => ???
        case tTrait: OMLITTrait => ???
        case OMLIT(value, rt) => ???
        case UnknownOMLIT(valueString, synType) => ???
        case OMFOREIGN(node) => ???
        case OMSemiFormal(tokens) =>  ???
        case OML(name, tp, df, nt, featureOpt) => ???
      }
    }

    getBoundNamesL(t0  ,lss)
  }


  /**
    * just returns the first element in ls that satisfies p
    * I don't know why did not use "find"
    * @param ls
    * @param p
    * @tparam A
    * @return
    */
  def getFirst[A](ls : List[A] , p : A => Boolean ) : Option[A] = ls match {
    case Nil => None
    case x::xs => {
      if (p(x)) return Some(x) else getFirst(xs , p)
    }
  }

  /**
    * a substitute function that can replace whole terms `old0` (not just OMVs) by
    * this function was made early in development when
    * the author was not aware of the [[Traverser]] trait and object
    * TODO: reimplement using the Traverser trait or remove this function
    * @param t0 term which contains the to be replaced (sub)term
    * @param old0 term to be replaced
    * @param newt0 term that replaces `old0`
    * @param rel relation that decides whether a subterm and `old0` are equal
    * @return `t0` but with old0 replaced by
    */
  def substitute(t0 : Term , old0 : Term , newt0 : Term, rel : (Term , Term, Context) => Boolean) : Term = {
    val tmp = getBoundNames(newt0, Nil)


    def substituteL(t : Term , old : Term , newt : Term, additionalContext : Context) : Term = {
      if (rel(t , old, additionalContext)) return newt
      t match {
        case Lambda(nm, tp, bd)  => {
          if (tmp.contains(nm)) {
            val newn = pickFreshHypName(nm.toString , Context(tmp.map(x => VarDecl(x)) : _*))
            val newbd = bd.substitute(Substitution(Sub(nm , OMV(newn))))(PlainSubstitutionApplier)
            val newbd0 = substituteL(newbd , old , newt, additionalContext ++ VarDecl(LocalName(newn) , tp , null ))
            Lambda(LocalName(newn) , tp , newbd0)
          }else {Lambda(nm , tp , substituteL(bd , old , newt, additionalContext ++ VarDecl(nm , tp , null)))}
        }
        case Pi(nm ,tp, bd) if (nm.toString != "_") => {
          if (tmp.contains(nm)) {
            val newn = pickFreshHypName(nm.toString , Context(tmp.map(x => VarDecl(x)) : _*))
            val newbd = bd.substitute(Substitution(Sub(nm , OMV(newn))))(PlainSubstitutionApplier)
            val newbd0 = substituteL(newbd , old , newt, additionalContext ++ VarDecl(LocalName(newn) , tp , null))
            Pi(LocalName(newn) , tp , newbd0)
          }else {Pi(nm , tp , substituteL(bd , old , newt ,additionalContext ++ VarDecl(nm , tp , null)))}
        }
        case OMA(fun , args) => {
          OMA(substituteL(fun , old , newt , additionalContext) , args.map(x => substituteL(x , old , newt, additionalContext)))
        }
        case _ => t
      }

    }
    substituteL(t0 , old0 , newt0 , Context())
  }

  /**
    * traverser used for substitution
    * @param old term to replace
    * @param newt replacement for "old"
    * @param rel a function that states whether two terms are equal under a given context (usually used to pass something like (t,t0,lctx) => solver.check(Equality(lctx , t , t0))
    * @param pos optionally indicates at which position to do the substitution in case there a multiple viable positoins (counts in a depth first manner)
    */
  class SubTraverser(old : Term , newt : Term, rel : (Term , Term, Context) => Boolean , pos : Option[Int]) extends  StatelessTraverser {
    var currpos = pos
    val tmp = newt.freeVars

    override def traverse(t: Term)(implicit additionalContext: Context, state: Unit): Term = {
      if (currpos.isDefined && currpos.get < 0) return t
      if (rel(t , old, additionalContext)) {
        if (currpos.isEmpty || (currpos.get == 0)) {
          currpos = currpos.map(x => x - 1)
          return newt
        }else {
          currpos = currpos.map(x => x - 1)
        }
      }
      t match {
        case Lambda(nm, tp, bd)  => {
          if (tmp.contains(nm)) {
            val newn = pickFreshHypName(nm.toString , Context(tmp.map(x => VarDecl(x)) : _*))
            val newbd = bd.substitute(Substitution(Sub(nm , OMV(newn))))(PlainSubstitutionApplier)
            val newbd0 = traverse(newbd)( additionalContext ++ VarDecl(LocalName(newn) , tp , null ) , ())
            Lambda(LocalName(newn) , tp , newbd0)
          }else {Lambda(nm , tp , traverse(bd)(additionalContext ++ VarDecl(nm , tp , null) , ()) )}
        }
        case Pi(nm ,tp, bd) if (nm.toString != "_") => {
          if (tmp.contains(nm)) {
            val newn = pickFreshHypName(nm.toString , Context(tmp.map(x => VarDecl(x)) : _*))
            val newbd = bd.substitute(Substitution(Sub(nm , OMV(newn))))(PlainSubstitutionApplier)
            val newbd0 = traverse(newbd)( additionalContext ++ VarDecl(LocalName(newn) , tp , null), ())
            Pi(LocalName(newn) , tp , newbd0)
          }else {Pi(nm , tp , traverse(bd)(additionalContext ++ VarDecl(nm , tp , null) , ()))}
        }

        case _ => Traverser(this , t)
      }
    }
  }

  /**
    * check if t is an lf-arrow or pi
    * @param t
    * @return
    */
  def isArrowOrPi(t : Term): Boolean = t match  {
    case Arrow(hd , bd) => true
    case Pi(_ , _ , _) => true
    case _ => false
  }

  /**
    * if a term is a Free(context , term) then this function removes all variables in this Free which are also in ctx
    * @param t
    * @param ctx
    * @return
    */
  def remOuterFree(t : Term , ctx : Context) : Term  = t match {
    case Free(ctx0  , bd) => {
      val newctx = ctx0.filter(p => ctx.index(p.name).isEmpty )
      if(newctx.nonEmpty) Free(newctx , bd) else bd
    }
    case _ => t
  }

  /**
    * like [[remOuterFree]] but returns the remaining variables in Free which are not also in ctx
    * @param t
    * @param ctx
    * @return
    */
  def remOuterFreeA(t : Term , ctx : Context) : (Term , List[VarDecl])  = t match {
    case Free(ctx0  , bd) => {
      val rems = ctx0.filter(p => ctx.index(p.name).isEmpty )
      (bd, rems)
    }
    case _ => (t, Nil)
  }

  /**
    * adds dependencies to ln by applying ctx to ln (i.e. turing it to an OMA)
    * @param ln
    * @param ctx
    */
  class ExtendUnkownInTerm( ln : LocalName , ctx: Context) extends StatelessTraverser   {
    override def traverse(t: Term)(implicit con: Context, state: State ): Term = t match {
      case Lambda(nm , tp , bd)  if (nm == ln) => t
      case OMA(OMV(vname) , args) if ln == vname =>{
        OMA(OMV(vname) , ctx.variables.map(x => x.toTerm).toList ++ args)
      }
      case OMV(vname) if ln == vname => {
        OMA(OMV(vname) , ctx.variables.map(x => x.toTerm).toList )
      }
      case _ => Traverser( this, t)
    }
  }

  /**
    * removes dependencies from unknowns
    * @param ctx
    */
  class Appremover(ctx : Context) extends StatelessTraverser {
    override def traverse(t: Term)(implicit con: Context, state: State): Term =  t match {
      case OMA(OMV(ln) , args) if ctx.getO(ln).isDefined => OMV(ln)
      case _ => Traverser(this , t)
    }
  }

  /**
    * check if t contains a hole
    * @param t term to check for holes
    * @return
    */
  def containsMissing(t : Term): Boolean = t match {
    case Hole(_) => true
    case OMID(path) => false
    case OMBINDC(binder, context, scopes) => (binder :: scopes).exists(p => containsMissing(p))
    case OMA(fun, args) => (fun :: args).exists(p => containsMissing(p))
    case OMV(name) => false
    case OMATTR(arg, key, value) => ???
    case tTrait: OMLITTrait => ???
    case OMLIT(value, rt) => ???
    case UnknownOMLIT(valueString, synType) => ???
    case OMFOREIGN(node) => ???
    case OMSemiFormal(tokens) => ???
    case OML(name, tp, df, nt, featureOpt) => ???
  }

  /**
    * apply a list of terms (applicants) to the term "to" under the local context of "ctx"
    * basically tries to find where the terms in the list "applicants" should be applied in a target term. f.ex. applicants = xa , xy , xz , with types a b c, and target term "to" has type a -> v -> b -> c -> d. Then the
    * term xa ist applied at position 0 , xb at position 2 and xc at position 3
    * @param ctx
    * @param applicants
    * @param to
    * @param hist
    * @param s
    * @return
    */
  def simpleApplyTermToTerm(ctx : Context, applicants : List[Term] , to : Term, hist : History , s : Solver): Option[(List[Goal] , Term , Term)] ={
    /**
      * helper class used to represent a [[Term]] in a, for proving, more handelable way
      */
    abstract class Param {
      def getTp : Term
    }
    /**
      * represents a Pi
      * @param nm name of the bound variable
      * @param tp type of the bound variable
      */
    case class PPi(nm : LocalName, tp : Term) extends Param {
      override def getTp: Term = tp
    }
    /**
      * represents the immediate lhs of an arrow as in  lhs -> rhs. f.ex in  a -> b -> the lhs of the first arrow is a and the lhs of the second one is b not a -> b
      * @param tp the lhs of the arrow
      */
    case class PArr(tp : Term) extends Param {
      override def getTp: Term = tp
    }
    /**
      * basically represents anything that is not a Pi or an Arrow
      * @param tp
      */
    case class Const(tp  : Term) extends Param {
      override def getTp: Term = tp
    }
    // connects a position in a term (when splittet by Pi and Arrow like in a -> b -> c  ==> List(a,b,c)) with a concrete term
    val terms = MMap[Int , Param]()
    val subs = MMap[Int , Option[Term]]()

    val totp0 = s.inferType(to)(Stack(ctx), hist).getOrElse(return None)
    val apptps0 = applicants.map(x => s.inferType(x)(Stack(ctx), hist).getOrElse(return None))
    val apptps = applicants.zip(apptps0)

    // traverser that does alpha renaming under context con  (see apply method)
    class AlphaRename() extends StatelessTraverser {

      override def apply(t: Term, con: Context): Term = t match {
        case Arrow(hd , bd) => Arrow(hd , apply(bd , con))
        case Pi(nm , tp , bd) => {
          if (con.variables.exists(p => p.name == nm)){
            val (newnm , sb) = Context.pickFresh(con, nm)
            Pi(newnm , tp , apply(bd ^ sb , con ++ VarDecl(newnm)))
          }else {
            t
          }
        }
        case _ => Traverser(this , t)(con , ())
      }

      override def traverse(t: Term)(implicit con: Context, state: this.State): Term = apply(t, con)
    }


    val totp = new AlphaRename()(totp0 , ctx)

    /**
      * returns the  length of the term t (starting with initial value pos)
      * @param t
      * @param pos inital value
      * @return
      */
    def splitTerm(t  : Term, pos : Int) : Int = t match {
      case Arrow(hd , bd) => {
        terms(pos) = PArr(hd)
        splitTerm(bd , pos + 1)
      }
      case Pi(nm , tp , bd) => {
        terms(pos) = PPi(nm , tp)
        splitTerm(bd , pos + 1)
      }
      case _ =>{ terms(pos) = Const(t) ; pos }
    }


    /**
      * turn a list of [[Param]] into a proper term
      * @param ls
      * @return
      */
    def genTerm(ls : List[Param]):Term = ls match {
      case List(Const(tp)) => tp
      case PArr(tp)::xs => Arrow(tp ,genTerm(xs))
      case PPi(nm , tp) :: xs => Pi(nm , tp , genTerm(xs))
    }



    val initlen = splitTerm(totp, 0)
    def maxpos = terms.toList.length - 1


    /**
      * splits a term (uses the "[[terms]]" mutable map to store the splitted term)
      * @param t term to be splitted
      * @param pos
      */
    def splitTermA(t : Term , pos : Int ) : Unit = {
      if (pos >= initlen) {terms(initlen) = Const(t) ;return }
      t match {
        case Arrow(hd , bd) => {
          terms(pos) = PArr(hd)
          splitTermA(bd , pos + 1)
        }
        case Pi(nm , tp , bd) => {
          terms(pos) = PPi(nm , tp)
          splitTermA(bd , pos + 1)
        }
        case _ =>{ terms(pos) = Const(t)}
      }
    }

    /**
      * applies a substitution to a splitted term (i.e. mergest the parts >=pos in terms togehter , applies the substitution and then splits them again)
      * @param pos
      * @param s
      */
    def subfrom(pos : Int , s: Substitution) = {
      val tmp = terms.filter(p => p._1 >= pos).toList
      val tmp0 = tmp.sortBy(x => x._1).map(x => x._2)
      val t = genTerm(tmp0) ^ s
      splitTermA(t , pos)
    }


    /**
      * wipes the solutions/substitutions in the [[subs]] mutable map
      * @param ls
      */
    def preparesubs(ls : List[(Int,Param)]): Unit  = ls match {
      case Nil =>
      case (pos , PPi(nm , tp))::xs => {
        subs(pos) = None
        preparesubs(xs)
      }
      case _::xs => preparesubs(xs)
    }

    preparesubs(terms.toList)

    /**
      * TODO: clumsy, should be replaced
      * this function goes through the splitted term and "applies" the passed solutions/applicants (app) to
      * the target term (i.e. the splitted term stored in the mutable map "terms") by finding the viable position for
      * every passed solution (i.e. what position it takes when applied to the term) while also solving bound variables withing the term
      * (i.e. when an applicant solves a term which depends on a bound variable).
      * While going through the splitted term (pos indicating the current positition in the term) named bound variables
      * are stored in "vars" including their type and position in the splitted term. When a solution
      * is applied the function then checks whether bound variables are solved. For example
      * the term X has the type {a: tp} p a -> b then the splitted term is  List(PPi(a,tp) , PArr(p a) , PConst(b))
      * now the term U with type p aa shall be applied to X. The function now goes through the splitted term.
      * After checking that U can not be inserted at pos = 0 because "a" has type tp the function adds key "a" with
      * value (tp , 0) to the vars Map. At pos = 1 U gets inserted and "a" in "p a" gets solved as "aa". Both solutions
      * (the inserted term being the first) get added to the subs solution map
      * @param pos current position in the splitted term
      * @param app terms to apply (_1 = actual term to apply , _2 = type of the applicant)
      * @param vars stores passed
      * @return true if all pi terms got solved , false otherwise (not a good design but thats how it is right now)
      */
    def traverse(pos : Int, app : List[(Term , Term)], vars : Map[LocalName , (Term ,Int)]): Boolean = {
      //should not happen
      if (pos < 0) return false
      //went through the whole splitted term
      if (pos >= initlen) return subs.forall(p => p._2.isDefined) && app.isEmpty
      val tm = terms(pos)
      // if the applicant does not solve bound names this will be nonEmpty = true
      lazy val res : Option[Substitution] = if (app.isEmpty) None else {
        val tmp = unifyA(ctx  , app.head._2 , Context(vars.toList.map(x => VarDecl(n = x._1, tp = x._2._1)) : _*) , tm.getTp, hist,  s)
        tmp
      }
      (app , tm , res) match {
        // with no applicants left , the pi can't be solved
        case (Nil , PPi(_,_), _) => false
        // solve a pi , but the solution does not solve other bound variables
        case ((app0)::xs, PPi(nm , tp), None) => {
          // add new bound variable to the vars Map , potentially replacing (shadowing) the old one
          val newvars = vars + (nm -> (tp, pos))
          traverse(pos + 1, app, newvars)
        }
        // solve a pi , but the solution does solve other bound variables
        case ((applicant,app0)::xs, PPi(nm, tp) , Some((sb))) => {
          sb.foreach(f => {
            var (_ , fpos) = vars(f.name)
            subfrom(fpos + 1 , Substitution(f))
            if (subs(fpos).isDefined && subs(fpos).get != f.target) {return false}
            subs(fpos) = Some(f.target)
          })
          // substiture the solution to the splitted term at the current position
          subfrom(pos + 1 , Substitution(Sub(nm , applicant)))
          subs(pos) = Some(applicant)
          //   subs.forall(p => p._2.isDefined)
          traverse(pos + 1 , xs , vars)

        }
        // no applicants left, check if all Pis got solved
        case (Nil , PArr(tp)  , _) => subs.forall(p => p._2.isDefined)
        // the unification already happend at the begining of the function (this should be rewritten)
        // since no Pis have been solved (res = None) move on
        case (app0::xs , PArr(tp) , None) => {
          traverse(pos + 1 , app , vars)
        }

        case ((applicant, app0)::xs , PArr(tp) , Some((sb ))) => {
          sb.foreach(f => {
            var (_ , fpos) = vars(f.name)
            subfrom(fpos + 1 , Substitution(f))
            if (subs(fpos).isDefined && subs(fpos).get != f.target) {return false}
            subs(fpos) = Some(f.target)
          })
          // solve current term with applicant
          subs(pos) = Some(applicant)
          traverse(pos + 1 , xs , vars)
        }
        // reached the end of the splitted terms (i.e. the conclusion which cannot be a Pi) just check is all Pis got solved
        case _ => subs.forall(p => p._2.isDefined)
      }
    }

    if (! traverse(0 , apptps , Map.empty)) return None

    /**
      * generate new goals from unsolved non Pi terms and generate the final term
      * @return
      */
    def buildTermAndGoals: (List[Goal],Term) = {

      var gnames = ctx ++ s.getPartialSolution

      def loop(pos : Int) : (List[Goal],List[Term]) = {
        if (pos >= initlen) return (Nil , Nil)
        val tmp  = terms(pos)
        lazy val sb = subs.get(pos)
        val (gls , tms) = loop(pos + 1)
        (tmp,  sb) match {
          // solved Pi (there are by design no unsolved Pis)
          case (PPi(nm, tp), Some(Some(sbt))) => {
            (gls, sbt :: tms)
          }
          // unsolved Arrow, make a new goal and solve this term as an unknown
          case (PArr(tp) , None) => {
            val newgname = Context.pickFresh(gnames , LocalName("", "goal"))
            gnames = gnames ++ VarDecl(newgname._1)
            ( Goal(tp ,  ctx , newgname._1) :: gls , s.Unknown(newgname._1 , ctx.variables.map(x => x.toTerm).toList) :: tms)
          }
          // solved Arrow, add it to the final term
          case (PArr(tp) , Some(Some(sbt))) => {
            (gls , sbt :: tms)
          }
          case _ => (Nil , Nil)
        }

      }

      val (gls , tms ) = loop(0)
      val resterm = if (tms.isEmpty) to else OMA(to , tms)
      (gls , resterm)


    }

    val (gls, resterm) = buildTermAndGoals
    Some(gls, resterm , terms(initlen).getTp)
  }

  /**
    *
    * @param context
    * @param uks
    * @param goal
    * @param fact
    * @param s
    * @return
    */
  def makeSubgoals(context: Context, uks : Context ,  goal: Term, fact: Term,  s : Solver): Option[Context] = {
    // tp must be of the form Pi bindings.scope
    val (bindings, scope) = FunType.unapply(fact).get
    val (paramList, subgoalList) = bindings.span(_._1.isDefined)
    // we do not allow named arguments after unnamed ones (this is a design flaw)
    if (subgoalList.exists(_._1.isDefined))
      return None
    // we do not allow shadowed parameters
    val paramNames = paramList.map(_._1)
    if (paramNames.distinct.length != paramNames.length)
      return None
    // the free variables of scope (we drop the types because matching does not need them)
    val params = FunType.argsAsContext(paramList)
    // fact may contain free variables from stack.context, so make sure there are no name clashes
    // sub is a renaming from unknowns to unknownsFresh
    val (paramsFresh, rename) = Context.makeFresh(params, context.map(_.name))
    val scopeFresh = scope ^? rename
    // match goal against scope, trying to solve for scope's free variables
    // val unifiableparams = Context(paramsFresh.map(x => x.copy(tp = x.tp.map(tpp => Free(context,  tpp)))) : _ *)
    // first element of the foldLeft accumulator is the modified remanmed  Pi bound names the goals depend on the second
    // parameter accumulates substitutions
    val (unifiableparams0 , unifiablesub) =  paramsFresh.foldLeft(Nil : List[VarDecl] , Substitution())((res , x) =>  {
      //    val newctx = Context(ctx.map(y => y.copy(df = y.df.map(z => z ^ res._2) , tp = y.tp.map(z => z ^ res._2))   ) : _ *)
      // needs to modify the type of the new goals because only those local variables that are mentioned via Free(... , ...) can occure in the solution
      val tmp =  x.copy(tp = x.tp.map(tpp => Free(context,  tpp ^ res._2)))
      val tmpsub = Sub(x.name, if (context.isEmpty) OMV(x.name) else OMA(OMV(x.name) , context.map(x => x.toTerm))) +: res._2.subs
      (  res._1 ++ List(tmp) , Substitution( tmpsub : _*))
    } )

    val unifiableparams = Context(unifiableparams0 : _*)
    //   val unifiablesub = Substitution(paramsFresh.map(x =>  Sub(x.name, if (context.isEmpty) OMV(x.name) else OMA(OMV(x.name) , context.map(x => x.toTerm)))) : _ *)
    val unifiablescope = scopeFresh ^ unifiablesub
    //    val unifyresult = unify(context, goal , paramsFresh ++ uks , scopeFresh,new History(Nil))
    val unifyresult0 = unify(context, goal , (unifiableparams ++ uks).filter(x => unifiablescope.freeVars.contains(x.name)) , unifiablescope,new History(Nil), s)

    val solution0 = unifyresult0 match {
      case Some(subs) => subs
      case None => return None
    }
    val solution = Substitution(solution0.subs.map(x =>  Sub(x.name , ProofUtil.remOuterFree(x.target , context))) : _*)
    // now scope ^ rename ^ solution == goal
    var result = Context()
    bindings foreach {b =>
      // named bound variables that are substituted by solution can be filled in
      // others are holes representing subgoals
      val renameResult = rename ^ result.toPartialSubstitution // maps unsolved variables to their renaming
      b match {
        case (Some(x), xtp) =>
          val xFresh = (OMV(x) ^ rename).asInstanceOf[OMV].name // rename is a renaming
          result ++= VarDecl(xFresh, None, Some(xtp ^? renameResult), solution(xFresh), None)
        case (None, anontp) =>
          result ++= VarDecl(OMV.anonymous, anontp ^? renameResult)
      }
    }

    val ukssol = solution0.filter(p => uks.variables.exists(v => v.name == p.name))
    ukssol.foreach(x => s.solve(x.name , x.target)(new History(Nil)))
    val result0 = result ^ Substitution(ukssol : _*)
    Some(result0)
  }

  /**
    * does type checking without polluting the original solver
    * @param t target term
    * @param tp type to check against
    * @param uks possible unknowns encountered during type checking
    * @param currctx the current context
    * @param s the solver to base the type check upon
    * @return a boolen which indicates whether t is of type tp and additionally solved unknowns
    */
  def standAloneTypeCheck(t : Term , tp : Term , uks : Context , currctx : Context, s : Solver) = {
    val cu = new CheckingUnit(None , currctx ,uks , Typing(Stack(currctx), t, tp , Some(OfType.path)))
    val exslv = new Solver(s.controller , cu , s.rules)
    val res = exslv.applyMain
    (res , exslv.getPartialSolution)
  }

  /**
    * stand alone unification of term goal with term query under context ctx where query contains unknowns params0
    * @param ctx
    * @param goal
    * @param params0
    * @param query
    * @param hist
    * @param s
    * @return
    */
  def unifyA(ctx : Context , goal : Term , params0 : Context , query : Term , hist : History, s : Solver)  = {
    val params = params0.filter(x => query.freeVars.contains(x.name))
    val (unifiableparams0 , unifiablesub) =  params.foldLeft(Nil : List[VarDecl] , Substitution())((res , x) =>  {
      val tmp =  x.copy(tp = x.tp.map(tpp => Free(ctx,  tpp ^ res._2)))
      val tmpsub = Sub(x.name, if (ctx.isEmpty) OMV(x.name) else OMA(OMV(x.name) , ctx.map(x => x.toTerm))) +: res._2.subs
      (  res._1 ++ List(tmp) , Substitution( tmpsub : _*))
    } )

    val unifiableparams = Context(unifiableparams0 : _*)
    val unifiablescope = query ^ unifiablesub

    val (newparams , subparams) = Context.makeFresh(unifiableparams , ctx.variables.map(_.name).toList)
    val revert = subparams.map(x => (x.target.asInstanceOf[OMV].name , x.name))
    val unifier = new Solver(s.controller ,  CheckingUnit(None, s.constantContext  , newparams, null) , s.rules )
    val ures = unifier.apply(Equality(Stack(ctx) , goal , unifiablescope ^ subparams , Some(OfType)))

    ures && unifier.getConstraints.isEmpty && unifier.getErrors.isEmpty match {
      case false => None
      case true => {
        Some(Substitution(unifier.getPartialSolution.toPartialSubstitution.map(x => {val tmp = revert.find(p => p._1 == x.name).get ; x.copy(name = tmp._2 , target = ProofUtil.remOuterFree(x.target ,  ctx)) }) : _ *))
      }
    }
  }

  /**
    * a dry run that, unlike the one found in [[Solver]] does not throw an exception when encountering an error
    * this is needed for solving unknowns which have a non unique solution
    * @param s solve used for saving and restoring the solver state
    * @param a target computation to execute during the dry run
    * @tparam A type of the result of the dry run
    * @return result of the dry run
    */
  def dryRunAllowErrors[A](s : Solver)(a: => A): A = {
    s.saveCurrentState()
    val res = a
    s.undoCurrentState()
    res
  }

}


