package info.kwarc.mmt.frameit.communication.server

import cats.effect.IO
import com.twitter.finagle.Service
import com.twitter.finagle.http.{Request, Response}
import info.kwarc.mmt.api._
import info.kwarc.mmt.api.frontend.Controller
import info.kwarc.mmt.api.modules.View
import info.kwarc.mmt.api.notations.NotationContainer
import info.kwarc.mmt.api.objects.OMMOD
import info.kwarc.mmt.api.ontology.IsTheory
import info.kwarc.mmt.api.presentation.MMTSyntaxPresenter
import info.kwarc.mmt.api.symbols.{ApplyMorphism, Constant, FinalConstant, TermContainer, Visibility}
import info.kwarc.mmt.frameit.archives.FrameIT.FrameWorld
import info.kwarc.mmt.frameit.business._
import info.kwarc.mmt.frameit.business.datastructures.{Fact, FactReference, Scroll}
import info.kwarc.mmt.frameit.communication.datastructures.DataStructures._
import info.kwarc.mmt.moduleexpressions.operators.NewPushoutUtils
import io.circe.Json
import io.finch._
import io.finch.circe._

import scala.util.{Random, Try}

sealed abstract class ValidationException(message: String, cause: Throwable = None.orNull)
  extends Exception(message, cause)

sealed case class ProcessedFactDebugInfo(tpAST: String, dfAST: String, presentedString: String, omdocXml: String) {
  def asJson: Json = Json.obj(
    "tpAST" -> Json.fromString(tpAST),
    "dfAST" -> Json.fromString(dfAST),
    "presentedString" -> Json.fromString(presentedString),
    "omdocXml" -> Json.fromString(omdocXml)
  )
}
object ProcessedFactDebugInfo {
  def fromConstant(c: Constant)(implicit ctrl: Controller, presenter: MMTSyntaxPresenter): ProcessedFactDebugInfo = {
    ProcessedFactDebugInfo(
      // avoid bubbling up exceptions to always have at least some debug information instead of none
      c.tp.map(_.toString).getOrElse("<no type available>"),
      c.df.map(_.toString).getOrElse("<no definiens available>"),
      Try(presenter.presentToString(c)).getOrElse("<presentation threw exception>"),
      Try(c.toNode.toString()).getOrElse("<conversion to XML threw exception>")
    )
  }
}

final case class FactValidationException(message: String, processedFacts: List[ProcessedFactDebugInfo], cause: Throwable = None.orNull) extends ValidationException(message, cause) {
  def asJson: Json = Json.obj(
    "message" -> Json.fromString(message),
    "processedFacts" -> Json.arr(processedFacts.map(_.asJson) : _*),
    "cause" -> Json.fromString(Option(cause).toString)
  )
}

/**
  * A collection of REST routes for our [[Server server]]
  */
object ServerEndpoints extends EndpointModule[IO] {
  // vvvvvvv DO NOT REMOVE IMPORTS (even if IntelliJ marks it as unused)
  import info.kwarc.mmt.frameit.communication.datastructures.Codecs
  import Codecs.DataStructureCodecs._
  import ServerErrorHandler._
  // ^^^^^^^ END: DO NOT REMOVE

  private def getEndpointsForState(state: ServerState) =
    printHelp(state) :+: buildArchiveLight(state) :+: buildArchive(state) :+: reloadArchive(state) :+:
      addFact(state) :+: listFacts(state) :+: listScrolls(state) :+: applyScroll(state) :+: dynamicScroll(state) :+: printSituationTheory(state)

  def getServiceForState(state: ServerState): Service[Request, Response] =
    getEndpointsForState(state).toServiceAs[Application.Json]

  // ENDPOINTS (all private functions)
  // ======================================
  private def printHelp(state: ServerState): Endpoint[IO, String] = get(path("help")) {
    Ok(getEndpointsForState(state).toString)
  }

  private def buildArchiveLight(state: ServerState): Endpoint[IO, Unit] = post(path("archive") :: path("build-light")) {
    state.ctrl.handleLine(s"build ${FrameWorld.archiveID} mmt-omdoc Scrolls")

    Ok(())
  }

  private def buildArchive(state: ServerState): Endpoint[IO, Unit] = post(path("archive") :: path("build")) {
    state.ctrl.handleLine(s"build ${FrameWorld.archiveID} mmt-omdoc")

    Ok(())
  }

  private def reloadArchive(state: ServerState): Endpoint[IO, Unit] = post(path("archive") :: path("reload")) {
    state.ctrl.backend.getArchive(FrameWorld.archiveID).map(frameWorldArchive => {
      val root = frameWorldArchive.root

      state.ctrl.backend.removeStore(frameWorldArchive)
      state.ctrl.addArchive(root)

      Ok(())
    }).getOrElse(NotFound(new Exception("MMT backend did not know FrameWorld archive by ID, but upon server start it did apparently (otherwise we would have aborted there). Something is inconsistent.")))
  }

  private def addFact(state: ServerState): Endpoint[IO, FactReference] = post(path("fact") :: path("add") :: jsonBody[SFact]) {
    (fact: SFact) => {
      val factConstant = fact.toFinalConstant(state.situationTheory.toTerm)

      state.synchronized {
        (if (state.doTypeChecking) state.contentValidator.checkDeclarationAgainstTheory(state.situationTheory, factConstant) else Nil) match {
          case Nil =>
            // success (i.e. no errors)
            try {
              state.ctrl.add(factConstant)
              Ok(FactReference(factConstant.path))
            } catch {
              case err: AddError =>
                NotAcceptable(err)
            }

          case errors =>
            NotAcceptable(FactValidationException(
              message = "Could not validate fact, errors were:\n\n" + errors.map {
                // for [[InvalidUnit]] also elaborate their history for better feedback
                case err: InvalidUnit => err.toString + "\n" + err.history
                case err => err
              }.mkString("\n"),
              processedFacts = List(ProcessedFactDebugInfo.fromConstant(factConstant)(state.ctrl, state.presenter))
            ))
        }
      }
    }
  }

  private def listFacts(state: ServerState): Endpoint[IO, List[SFact]] = get(path("fact") :: path("list")) {
    Ok(
      Fact
        .findAllIn(state.situationTheory, recurseOnInclusions = true)(state.ctrl)
        .map(_.renderStatic()(state.ctrl))
    )
  }

  private def printSituationTheory(state: ServerState): Endpoint[IO, String] = get(path("debug") :: path("situationtheory") :: path("print")) {
    val stringRenderer = new presentation.StringBuilder
    DebugUtils.syntaxPresentRecursively(state.situationTheory)(state.ctrl, state.presenter, stringRenderer)

    Ok(stringRenderer.get)
  }

  private def listScrolls(state: ServerState): Endpoint[IO, List[SScroll]] = get(path("scroll") :: path("list")) {
    if (!state.readScrollData) {
      // TODO hack to read latest scroll meta data, should not be needed
      //      due to https://github.com/UniFormal/MMT/issues/528
      state.ctrl.handleLine(s"build ${FrameWorld.archiveID} mmt-omdoc Scrolls/")

      state.readScrollData = true
    }

    Ok(Scroll.findAll()(state.ctrl).map(_.render()(state.ctrl)))
  }

  private def applyScroll(state: ServerState): Endpoint[IO, List[SFact]] = post(path("scroll") :: path("apply") :: jsonBody[SScrollApplication]) { (scrollApp: SScrollApplication) => {

    val scrollViewDomain = scrollApp.scroll.problemTheory
    val scrollViewCodomain = state.situationTheoryPath

    // create view out of [[SScrollApplication]]
    val scrollView = scrollApp.toView(
      state.situationDocument ? LocalName.random("frameit_scroll_view"),
      OMMOD(scrollViewCodomain)
    )(state.ctrl)

    // collect all assignments such that if typechecking later fails, we can conveniently output
    // debug information
    val scrollViewAssignments = scrollView.getDeclarations.collect {
      case c: FinalConstant => c
    }

    (if (state.doTypeChecking) state.contentValidator.checkView(scrollView) else Nil) match {
      case Nil =>
        val (situationTheoryExtension, _) = NewPushoutUtils.computeNamedPushoutAlongDirectInclusion(
          state.ctrl.getTheory(scrollViewDomain),
          state.ctrl.getTheory(scrollViewCodomain),
          state.ctrl.getTheory(scrollApp.scroll.solutionTheory),
          state.situationDocument ? LocalName.random("situation_theory_extension"),
          scrollView,
          w_to_generate = state.situationDocument ? LocalName.random("pushed_out_scroll_view")
        )(state.ctrl)

        state.setSituationTheory(situationTheoryExtension)

        Ok(
          Fact
            .findAllIn(situationTheoryExtension, recurseOnInclusions = false)(state.ctrl)
            .map(_.renderStatic()(state.ctrl))
        )

      case errors =>
        state.ctrl.delete(scrollView.path)

        NotAcceptable(FactValidationException(
          "View for scroll application does not validate, errors were:\n\n" + errors.mkString("\n"),
          scrollViewAssignments.map(d => ProcessedFactDebugInfo.fromConstant(d)(state.ctrl, state.presenter))
        ))
    }
  }}

  private def dynamicScroll(state: ServerState): Endpoint[IO, SScroll] = post(path("scroll") :: path("dynamic") :: jsonBody[SScrollApplication]) { (scrollApp: SScrollApplication) =>
    Scroll.fromReference(scrollApp.scroll)(state.ctrl) match {
      case Some(scroll) =>

        val scrollView = scrollApp.toView(
          target = state.situationDocument ? "blah",
          codomain = state.situationTheory.toTerm
        )(state.ctrl)

        Ok(scroll.render(Some(scrollView))(state.ctrl))

      case _ =>
        NotFound(InvalidScroll("Scroll not found or (meta)data invalid"))
  }}
}