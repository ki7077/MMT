import info.kwarc.mmt.api.presentation.{ConsoleWriter, FlatMMTSyntaxPresenter, MMTSyntaxPresenter}
import info.kwarc.mmt.api.utils.URI
import info.kwarc.mmt.api.{DPath, Path}

/**
  * Playground for Navid's implementation of diagram operators.
  * For debugging purposes only - might contain dirty code.
  *
  * @author Navid
  */
object DiagramOperatorTest extends MagicTest("debug"/*, "DiagramDefinition"*/) {
  private var presenter: MMTSyntaxPresenter = _

  override def doFirst: Unit = {
    // Only uncomment if rebuild is really necessary
    // hl("build MMT/urtheories -mmt-omdoc")
    // hl("build MMT/urtheories mmt-omdoc")

    // Only uncomment if rebuild is really necessary
    // hl("build MitM/Foundation mmt-omdoc")

    // Clean first to prevent some spurious caching errors
    hl("build Playground/diagops -mmt-omdoc")
    hl("build Playground/diagops mmt-omdoc")

    presenter = new FlatMMTSyntaxPresenter()
    controller.extman.addExtension(presenter)
  }

  /**
    * Waits - possibly ad infinitum - for the object identified by the path to appear in the [[controller]].
    *
    * @param path A path to a theory, document etc.
    */
  private def waitUntilAvailable(path: Path): Unit = {
    while (controller.getO(path).isEmpty) {
      Thread.sleep(500)
    }
  }

  private def waitThenPrint(path: Path): Unit = {
    waitUntilAvailable(path)
    presenter(controller.get(path))(ConsoleWriter)
    print("\n")
  }

  private val diagops = DPath(URI("https://example.com/diagops"))
  private val typeindexifier = diagops / "typeindexifier"
  private val typifier = diagops / "typifier"
  private val pushout = diagops / "pushout"

  private def space(): Unit = {
    print("\n".repeat(5))
  }

  // This [[run]] method is run in parallel to the build process started above in [[doFirst]],
  // hence, we apply some dirty waiting mechanism here.
  override def run: Unit = {
    // Demo MultiTypeIndexifier and extension to morphisms
    waitThenPrint(typeindexifier ? "EndoMagma_pres")
    waitThenPrint(typeindexifier ? "EndoMagma_https:%2F%2Fexample.com%2Fdiagops%2Ftypeindexifier%3FOppositeMagma")
    waitThenPrint(typeindexifier ? "EndoMonoid_https:%2F%2Fexample.com%2Fdiagops%2Ftypeindexifier%3FOppositeMonoid")
    waitThenPrint(typeindexifier ? "MultiTypeIndexedTestTheory_pres")

    space()

    waitThenPrint(typeindexifier ? "EndoMagmaSingle_pres")

    waitThenPrint(typeindexifier ? "EndoMagmaSingle_pres")
    waitThenPrint(typeindexifier ? "EndoMagmaSingle_https:%2F%2Fexample.com%2Fdiagops%2Ftypeindexifier%3FOppositeMagma")
    waitThenPrint(typeindexifier ? "SingleTypeIndexedTestTheory_pres")

    space()

    waitThenPrint(typifier ? "TypifySFOLTheory_pres")

    space()

    waitThenPrint((pushout / "list") ? "ListNat_pres")
    waitThenPrint((pushout / "nvs") ? "ThingsInNormedVectorspace_pres")

    sys.exit(0)
  }
}
