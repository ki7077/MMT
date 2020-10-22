package info.kwarc.mmt.mizar.mizarobjects

import info.kwarc.mmt.api._

sealed abstract class ArgumentInfixity {}
case class LeftSideArgument() extends ArgumentInfixity {}
case class RightSideArgument() extends ArgumentInfixity {}

/** translate to VarDecls in MMT */
case class MizarLocalVariable(name: LocalName, tp: MizarType, infixity:Option[ArgumentInfixity] = RightSideArgument()) {
}

/** translate to GlobalName in MMT */
case class MizarGlobalName(name:GlobalName) {
}

sealed abstract class DefiningFormulaCaseResult {}
case class ExplicitFormula() extends DefiningFormulaCaseResult {}
case class ImplicitFormula() extends DefiningFormulaCaseResult {}

case class MizarCase(kind:MizarType, result:DefiningFormulaCaseResult)
sealed abstract class DefiningFormula() {
  def cases: List[MizarCase]
  def partial:Boolean
}
/** Effectively an inductive definition over the (implicite) inductive type with one unary constructor of the type of each case match*/
case class MizarCompleteDefiningFormula(cases: List[MizarCase]) extends DefiningFormula {
  def partial = {false}
}
case class MizarPartialDefiningFormula(cases: List[MizarCase], defaultResult:DefiningFormulaCaseResult) extends DefiningFormula {
  def partial = {true}
}


case class MizarStatement(claim:DefiningFormula) {}
/** Will be formalized as Ded (claim) */
case class MizarProof(claim:DefiningFormula) {}

abstract class MizarDeclaration() {
  def name:MizarGlobalName
}