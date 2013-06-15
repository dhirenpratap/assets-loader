package com.ee.assets

import com.ee.assets.deployment.Deployer
import com.ee.assets.models._
import com.ee.assets.processors._
import com.ee.js.JavascriptCompiler
import com.ee.log.Logger
import com.ee.utils.file._
import com.ee.utils.string._
import java.io.{StringWriter, StringReader, File}
import play.api.Play
import play.api.Play.current
import play.api.templates.Html

object Loader{

  val ScriptTemplate = """<script type="text/javascript" src="${src}"></script>"""
  val CssTemplate = """<link rel="stylesheet" type="text/css" href="${src}"/>"""

  val AssetLoaderTemplate =
    """<!-- Asset Loader -->
      |    <!--
      |    files:
      |    ${files}
      |    -->
      |    ${content}
      |<!-- End -->
    """.stripMargin
}

class Loader(deployer:Option[Deployer] = None) {

  private val jsProcessor: AssetProcessor =
    new SimpleFileProcessor(Info, Config, targetFolder, Loader.ScriptTemplate, ".js", minifyJs, loaderHash, deployer)

  private val cssProcessor: AssetProcessor =
    new SimpleFileProcessor(Info, Config, targetFolder, Loader.CssTemplate, ".css", minifyCss, loaderHash, deployer)

  def scripts(concatPrefix: String)(paths: String*): play.api.templates.Html = run(jsProcessor, concatPrefix)(paths: _*)

  def css(concatPrefix: String)(paths: String*): play.api.templates.Html = run(cssProcessor, concatPrefix)(paths: _*)

  private def run(processor: AssetProcessor, concatPrefix: String)(paths: String*): play.api.templates.Html = {
    if (paths.length == 0) {
      Html("<!-- AssetLoader :: error : no paths to load -->")
    } else {
      val pathsAsFiles: List[File] = paths.map(p => new File("." + Info.filePath + "/" + p)).toList
      val allFiles = distinctFiles(pathsAsFiles: _*)
      val typedFiles = typeFilter(processor.suffix, allFiles)
      val assets = processor.process(concatPrefix, typedFiles)
      val out = interpolate(Loader.AssetLoaderTemplate,
        "content" -> assets.mkString("\n"),
        "files" -> typedFiles.map(_.getName).mkString("\n\t"))
      Html(out)
    }
  }

  def minifyCss(file: File, destination: String) {
    Logger.debug("[minifyCss]  " + file + " destination: " + destination)
    val contents = readContents(file)
    val compressor = new com.yahoo.platform.yui.compressor.CssCompressor(new StringReader(contents))
    val writer = new StringWriter()
    compressor.compress(writer, 0)
    writeToFile(destination, writer.toString)
  }

  def minifyJs(file: File, destination: String) {
    Logger.debug("[minifyJs]  " + file + " destination: " + destination)
    val contents = readContents(file)
    val out = JavascriptCompiler.minify(contents, None)
    writeToFile(destination, out)
  }

  /** Use only the name for hashing on production as the file will not change */
  private def loaderHash(files:List[File]) : String = {
    import com.ee.utils.file._

    val fileToStringFn : (File => String)= Play.mode match{
      case play.api.Mode.Prod => {
        (f : File )=> {
          Logger.debug("return simple file name for Production mode")
          f.getName
        }
      }
      case _ => (f : File )=> f.getName + "_" + f.lastModified
    }
    hash(files, fileToStringFn)
  }

  private lazy val Info: AssetsInfo = AssetsInfo("/assets", "/public")

  //TODO: move mode to constructor param so we can remove dependency on current Application
  private lazy val Config: AssetsLoaderConfig = {

    val modeKey = Play.mode match {
      case play.api.Mode.Dev => "dev"
      case play.api.Mode.Test => "test"
      case play.api.Mode.Prod => "prod"
    }

    def bool(property: String, default: Boolean = false): Boolean = {
      val maybeBoolean = current.configuration.getBoolean("assetsLoader." + modeKey + "." + property)
      com.ee.log.Logger.debug(property + ": " + maybeBoolean)
      maybeBoolean.getOrElse(default)
    }

    AssetsLoaderConfig(bool("concatenate"), bool("minify"), bool("gzip"))
  }

  private lazy val targetFolder: String = {
    val Regex = """.*(target/.*?/classes).*""".r
    val Regex(path) = com.ee.utils.play.classesFolder().getAbsolutePath
    path + "/"
  }


}