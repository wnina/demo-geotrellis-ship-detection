package tutorial

import geotrellis.raster._
import geotrellis.raster.render._
import geotrellis.spark._
import geotrellis.spark.io._
import geotrellis.spark.io.hadoop._
//import geotrellis.spark.io.file._

import org.apache.hadoop.fs.Path
import org.apache.hadoop.conf.Configuration
import org.apache.spark._

import akka.actor._
import akka.event.{Logging, LoggingAdapter}
import akka.http.scaladsl.Http
import akka.http.scaladsl.model._
import akka.http.scaladsl.server.Directives._
import akka.stream.{ActorMaterializer, Materializer}

import scala.concurrent._
import com.typesafe.config.ConfigFactory
import MaskBandsRandGandNIR.{R_BAND, G_BAND, NIR_BAND}

object Serve extends App with Service {
  // val catalogPath = new java.io.File("data/catalog").getAbsolutePath
  // Create a reader that will read in the indexed tiles we produced in IngestImage.
  // val fileValueReader = FileValueReader(catalogPath)
  // def reader(layerId: LayerId) = fileValueReader.reader[SpatialKey, MultibandTile](layerId)
  val rootPath: Path = new Path("hdfs://master:9000/data/catalog")
  val config: Configuration = new Configuration
  val conf = new SparkConf()
     .setMaster("spark://master:7077")
     .setAppName("Spark Barcos - Serve")
  implicit val sc = new SparkContext(conf)
  // val store: geotrellis.spark.io.AttributeStore = HadoopAttributeStore(rootPath)
  val hreader = HadoopValueReader(rootPath)(sc)
  def reader(layerId: LayerId) = hreader.reader[SpatialKey, MultibandTile](layerId)
  val ndviColorMap =
    ColorMap.fromStringDouble(ConfigFactory.load().getString("tutorial.ndviColormap")).get
  val ndwiColorMap =
    ColorMap.fromStringDouble(ConfigFactory.load().getString("tutorial.ndwiColormap")).get

  override implicit val system = ActorSystem("tutorial-system")
  override implicit val executor = system.dispatcher
  override implicit val materializer = ActorMaterializer()
  override val logger = Logging(system, getClass)

  Http().bindAndHandle(root, "0.0.0.0", 8070)
}

trait Service {
  implicit val system: ActorSystem
  implicit def executor: ExecutionContextExecutor
  implicit val materializer: Materializer
  val logger: LoggingAdapter

  val staticPath = "/home/hduser/geotrellis-landsat-tutorial/static"

  def pngAsHttpResponse(png: Png): HttpResponse =
    HttpResponse(entity = HttpEntity(ContentType(MediaTypes.`image/png`), png.bytes))

  def root =
    pathPrefix(Segment / IntNumber / IntNumber / IntNumber) { (render, zoom, x, y) =>
      complete {
        Future {
          // Read in the tile at the given z/x/y coordinates.
          val tileOpt: Option[MultibandTile] =
            try {
              Some(Serve.reader(LayerId("barcos", zoom)).read(x, y))
            } catch {
              case _: ValueNotFoundError =>
                None
            }
          render match {
            case "ndvi" =>
              tileOpt.map { tile =>
                // Compute the NDVI
                val ndvi =
                  tile.convert(DoubleConstantNoDataCellType).combineDouble(R_BAND, NIR_BAND) { (r, ir) =>
                    Calculations.ndvi(r, ir);
                  }
                // Render as a PNG
                val png = ndvi.renderPng(Serve.ndviColorMap)
                pngAsHttpResponse(png)
              }
            case "ndwi" =>
              tileOpt.map { tile =>
                // Compute the NDWI
                val ndwi =
                  tile.convert(DoubleConstantNoDataCellType).combineDouble(G_BAND, NIR_BAND) { (g, ir) =>
                    Calculations.ndwi(g, ir)
                  }
                // Render as a PNG
                val png = ndwi.renderPng(Serve.ndwiColorMap)
                pngAsHttpResponse(png)
              }
          }
        }
      }
    } ~
      pathEndOrSingleSlash {
        getFromFile(staticPath + "/index.html")
      } ~
      pathPrefix("") {
        getFromDirectory(staticPath)
      }
}
