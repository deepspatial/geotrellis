/*
 * Copyright 2016 Azavea
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package geotrellis.spark.io.hadoop

import geotrellis.spark.io.hadoop.formats._
import geotrellis.vector.Extent

import io.pdal._
import org.apache.hadoop.fs.Path
import org.apache.spark.SparkContext
import org.apache.spark.rdd.RDD
import spray.json._

/**
  * Allows for reading point data files using PDAL as RDD[(ProjectedPackedPointsBounds, PointCloud)]s through Hadoop FileSystem API.
  */
object HadoopPointCloudRDD {

  /**
    * This case class contains the various parameters one can set when reading RDDs from Hadoop using Spark.
    */

  case class Options(
    filesExtensions: Seq[String] = PointCloudInputFormat.filesExtensions,
    tmpDir: Option[String] = None,
    filterExtent: Option[Extent] = None,
    dimTypes: Option[Iterable[String]] = None,
    inputCrs: Option[String] = None,
    targetCrs: Option[String] = None,
    additionalPipelineSteps: Seq[JsObject] = Seq()
  )

  object Options {
    def DEFAULT = Options()
  }

  /**
    * Creates a RDD[(ProjectedPackedPointsBounds, PointCloud)] whose K depends on the type of the point data file that is going to be read in.
    *
    * @param path     Hdfs point data files path.
    * @param options  An instance of [[Options]] that contains any user defined or default settings.
    */
  def apply(path: Path, options: Options = Options.DEFAULT)(implicit sc: SparkContext): RDD[(HadoopPointCloudHeader, Iterator[PointCloud])] = {
    val conf = sc.hadoopConfiguration.withInputDirectory(path, options.filesExtensions)

    options.tmpDir.foreach { dir =>
      PointCloudInputFormat.setTmpDir(conf, dir)
    }

    options.dimTypes.foreach { dt =>
      PointCloudInputFormat.setDimTypes(conf, dt)
    }

    options.targetCrs.foreach { crs =>
      PointCloudInputFormat.setTargetCrs(conf, crs)
    }

    options.inputCrs.foreach { crs =>
      PointCloudInputFormat.setInputCrs(conf, crs)
    }

    PointCloudInputFormat.setAdditionalPipelineSteps(conf, options.additionalPipelineSteps)

    options.filterExtent match {
      case Some(filterExtent) =>
        PointCloudInputFormat.setFilterExtent(conf, filterExtent)

        sc.newAPIHadoopRDD(
          conf,
          classOf[PointCloudInputFormat],
          classOf[HadoopPointCloudHeader],
          classOf[Iterator[PointCloud]]
        ).filter { case (header, _) =>
          header.extent3D.toExtent.intersects(filterExtent)
        }
      case None =>
        sc.newAPIHadoopRDD(
          conf,
          classOf[PointCloudInputFormat],
          classOf[HadoopPointCloudHeader],
          classOf[Iterator[PointCloud]]
        )
    }
  }
}